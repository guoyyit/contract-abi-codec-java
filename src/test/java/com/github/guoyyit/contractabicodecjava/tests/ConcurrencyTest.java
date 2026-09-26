package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.api.CodecApi;
import com.github.guoyyit.contractabicodecjava.api.InProcessCodecApi;
import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.encoding.CodecService;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.FunctionStore;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {

    private static final FunctionStore NOOP_STORE = new FunctionStore() {
        @Override public void persist(List<FunctionDefinition> functions) { }
        @Override public List<FunctionDefinition> load() { return List.of(); }
    };

    private FunctionDefinition fn(int i) {
        return new FunctionDefinition("fn" + i,
                List.of(new Parameter("x", new AbiType.UInt(256))),
                List.of(new Parameter("v", new AbiType.UInt(256))));
    }

    @Test
    void concurrentRegistrationsNeverExposePartialState() throws Exception {
        FunctionRegistry registry = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(64), NOOP_STORE);
        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        // Reader constantly verifies snapshot consistency while writers register.
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 5_000; i++) {
                    var snap = registry.snapshot();
                    // Invariant: every byName entry is also present and consistent in snapshot maps.
                    for (var e : snap.byName().entrySet()) {
                        assertNotNull(e.getValue().selector());
                    }
                    assertEquals(snap.byName().size(), countDistinctSelectors(snap));
                }
            } catch (Throwable t) {
                errors.incrementAndGet();
            }
        });

        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.register(fn(base + i));
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(threads * perThread, registry.snapshot().byName().size());
    }

    private int countDistinctSelectors(com.github.guoyyit.contractabicodecjava.model.RegistrySnapshot snap) {
        return (int) snap.byName().values().stream().map(FunctionDefinition::selector).distinct().count();
    }

    @Test
    void concurrentEncodeDecodeCallsAreCorrectAndIsolated() throws Exception {
        FunctionRegistry registry = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(64), NOOP_STORE);
        registry.register(new FunctionDefinition("add",
                List.of(new Parameter("a", new AbiType.UInt(256)),
                        new Parameter("b", new AbiType.UInt(256))),
                List.of()));
        CodecService service = new CodecService(registry,
                new AbiEncoder(32), new AbiDecoder(32), new TypeLayoutCache(64), 1 << 20);
        CodecApi api = new InProcessCodecApi(registry, service);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<Integer, Boolean> seen = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final long base = t * 1000L;
            pool.submit(() -> {
                try {
                    start.await();
                    for (long i = 0; i < 200; i++) {
                        long a = base + i;
                        long b = i + 1;
                        byte[] call = api.encode("add", List.of(BigInteger.valueOf(a), BigInteger.valueOf(b)));
                        // Strip selector and verify args round-trip.
                        byte[] args = new byte[call.length - 4];
                        System.arraycopy(call, 4, args, 0, args.length);
                        List<Object> decoded = service.decodeArgs("add", args);
                        assertEquals(BigInteger.valueOf(a), decoded.get(0));
                        seen.put((int) a, Boolean.TRUE);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(threads * 200, seen.size());
    }

    @Test
    void batchRegistrationIsAtomic() throws Exception {
        FunctionRegistry registry = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(64), NOOP_STORE);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger partialObservations = new AtomicInteger();
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 50; round++) {
                        var snap = registry.snapshot();
                        // After any visible batch of 10 functions, a reader may see 0 or a
                        // multiple-of-10 count, never an in-between partial registration.
                        if (snap.byName().size() % 10 != 0 && snap.byName().size() != 0) {
                            partialObservations.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }
        start.countDown();
        for (int batch = 0; batch < 50; batch++) {
            int b = batch;
            List<FunctionDefinition> funcs = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                funcs.add(new FunctionDefinition("batch" + b + "_" + i,
                        List.of(new Parameter("x", new AbiType.UInt(8))), List.of()));
            }
            registry.registerAll(funcs);
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertEquals(0, partialObservations.get());
    }
}
