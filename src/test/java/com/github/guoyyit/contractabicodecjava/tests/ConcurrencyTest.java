package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.config.CodecProperties;
import com.github.guoyyit.contractabicodecjava.registry.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.registry.RegistrySnapshot;
import com.github.guoyyit.contractabicodecjava.service.CodecService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {

    @TempDir
    Path tempDir;

    private CodecService newService() {
        CodecProperties props = new CodecProperties();
        props.setStoreFile(tempDir.resolve("registry.bin").toString());
        props.setPersistOnChange(false); // 并发测试聚焦注册表本身
        return new CodecService(props);
    }

    @Test
    void concurrentRegistrationKeepsRegistryConsistent() throws Exception {
        CodecService service = newService();
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        service.register("fn" + (base + i),
                                List.of("uint256", "bytes"), List.of("bool"), null);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, failures.get());
        // 全部注册成功，无丢失、无半成品
        RegistrySnapshot snap = service.snapshot();
        assertEquals(threads * perThread, snap.list().size());
        for (FunctionDefinition def : snap.list()) {
            assertEquals(2, def.inputs().size());
            assertEquals(4, def.selector().length);
        }
    }

    @Test
    void concurrentEncodeDecodeDuringRegistration() throws Exception {
        CodecService service = newService();
        service.register("base", List.of("uint256", "string"), List.of("uint256"), null);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        // 3 个线程持续注册新函数
        for (int t = 0; t < 3; t++) {
            final int base = t * 1000;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        service.register("extra" + (base + i), List.of("bool"), List.of(), null);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        // 3 个线程持续编解码 base 函数
        for (int t = 0; t < 3; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        byte[] call = service.encodeCall("base",
                                List.of(BigInteger.valueOf(i), "x" + i));
                        List<Object> args = service.decodeCall(call, null);
                        assertEquals(BigInteger.valueOf(i), args.get(0));
                        assertEquals("x" + i, args.get(1));
                        byte[] result = service.encodeCall("base",
                                List.of(BigInteger.ONE, "y")); // 只是验证不抛异常
                        assertNotNull(result);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, failures.get());
        assertEquals(301, service.snapshot().list().size());
    }

    @Test
    void snapshotTakenMidStreamNeverShowsPartialDefinition() throws Exception {
        CodecService service = newService();
        int n = 200;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < n; i++) {
                    service.register("w" + i, List.of("uint256"), List.of(), null);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        List<Throwable> errors = new ArrayList<>();
        Future<?> reader = pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 500; i++) {
                    RegistrySnapshot snap = service.snapshot();
                    // 快照内每个定义都必须完整：名称、签名、选择器一致
                    for (FunctionDefinition def : snap.list()) {
                        assertTrue(def.canonicalSignature().startsWith(def.name() + "("));
                        assertEquals(4, def.selector().length);
                        assertNotNull(def.alias());
                    }
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        reader.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(errors.isEmpty(), () -> "reader saw partial state: " + errors);
    }
}
