package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.JsonFileFunctionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Requirements 1-6: fault atomicity of registration and persistence.
 */
class FaultAtomicityTest {

    private FunctionDefinition fn(String name) {
        return new FunctionDefinition(name,
                List.of(new Parameter("x", new AbiType.UInt(256))),
                List.of());
    }

    private FunctionRegistry newRegistry(JsonFileFunctionStore store) {
        return new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(16), store);
    }

    // 1: a fault at every write stage surfaces as PERSISTENCE_ERROR, never success.
    @Test
    void faultsAtEveryStageFailRegistration(@TempDir Path dir) {
        for (FaultInjection.Stage stage : FaultInjection.Stage.values()) {
            Path file = dir.resolve("reg-" + stage + ".json");
            FaultInjection store = new FaultInjection(file);
            FunctionRegistry registry = newRegistry(store);
            registry.register(fn("alpha"));

            store.failAt(stage);
            CodecException e = assertThrows(CodecException.class,
                    () -> registry.register(fn("beta" + stage)),
                    "stage " + stage);
            assertEquals(ErrorCode.PERSISTENCE_ERROR, e.code(), "stage " + stage);
        }
    }

    // 2: after a failed registration the function is invisible in memory; no tmp remains.
    @Test
    void failedRegistrationLeavesNoTrace(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        Path tmp = dir.resolve("registry.json.tmp");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);
        registry.register(fn("alpha"));

        store.failAt(FaultInjection.Stage.WRITE);
        assertThrows(CodecException.class, () -> registry.register(fn("ghost")));

        assertThrows(CodecException.class, () -> registry.resolveByName("ghost"));
        assertEquals(1, registry.snapshot().byName().size());
        assertNotNull(registry.resolveByName("alpha"));
        assertFalse(Files.exists(tmp), "no temp file may remain after a failed write");
    }

    // 2/4: a later successful registration must not carry the failed definition.
    @Test
    void laterSuccessDoesNotResurrectFailedDefinition(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);
        registry.register(fn("alpha"));

        store.failAt(FaultInjection.Stage.REPLACE);
        assertThrows(CodecException.class, () -> registry.register(fn("ghost")));

        store.disarm();
        registry.register(fn("beta"));
        assertEquals(2, registry.snapshot().byName().size());
        assertThrows(CodecException.class, () -> registry.resolveByName("ghost"));

        FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
        assertEquals(2, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("alpha"));
        assertNotNull(restarted.resolveByName("beta"));
        assertThrows(CodecException.class, () -> restarted.resolveByName("ghost"));
    }

    // 3: restart right after a failed write restores the full pre-failure state.
    @Test
    void restartAfterFailureRestoresPreviousCompleteState(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);
        registry.register(fn("alpha"));
        registry.register(fn("bravo"));

        for (FaultInjection.Stage stage : FaultInjection.Stage.values()) {
            FaultInjection failing = new FaultInjection(file);
            failing.failAt(stage);
            FunctionRegistry live = newRegistry(failing);
            assertThrows(CodecException.class, () -> live.register(fn("ghost" + stage)));

            // "Restart": a brand-new registry/store over the same file.
            FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
            assertEquals(2, restarted.snapshot().byName().size(),
                    "stage " + stage + " must preserve both prior functions");
            assertNotNull(restarted.resolveByName("alpha"));
            assertNotNull(restarted.resolveByName("bravo"));
            assertThrows(CodecException.class,
                    () -> restarted.resolveByName("ghost" + stage));
            assertFalse(Files.exists(dir.resolve("registry.json.tmp")));
        }
    }

    // 3: once the fault clears, the next registration succeeds and lands completely.
    @Test
    void nextRegistrationAfterFaultClearsSucceedsAndPersists(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);
        registry.register(fn("alpha"));

        store.failAt(FaultInjection.Stage.FORCE);
        assertThrows(CodecException.class, () -> registry.register(fn("ghost")));

        store.disarm();
        registry.register(fn("charlie"));
        FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
        assertEquals(2, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("charlie"));
    }

    // 4: a parseable-but-incomplete main file (fake checksum) is CORRUPTED_STATE, never
    // silently accepted as an empty/partial registry.
    @Test
    void tornWriteWithValidJsonButBadChecksumIsCorrupted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        newRegistry(store).register(fn("alpha"));

        // Tamper with the payload inside the envelope: still valid JSON, checksum wrong.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(file));
        String payload = root.get("payload").asText();
        String tampered = payload.replace("alpha", "zzzzz");
        root.put("payload", tampered);
        Files.write(file, mapper.writeValueAsBytes(root));

        JsonFileFunctionStore fresh = new JsonFileFunctionStore(file);
        CodecException e = assertThrows(CodecException.class, fresh::load);
        assertEquals(ErrorCode.CORRUPTED_STATE, e.code());
    }

    // 4: a parseable v2 envelope missing its integrity fields is CORRUPTED_STATE.
    @Test
    void parseableEnvelopeMissingChecksumIsCorrupted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode incomplete = mapper.createObjectNode();
        incomplete.put("version", 2);
        incomplete.put("payload", "{\"version\":2,\"functions\":[]}");
        Files.write(file, mapper.writeValueAsBytes(incomplete));

        JsonFileFunctionStore fresh = new JsonFileFunctionStore(file);
        CodecException e = assertThrows(CodecException.class, fresh::load);
        assertEquals(ErrorCode.CORRUPTED_STATE, e.code());
    }

    // 4: a torn v2 write that is syntactically valid JSON with an empty registry but
    // keeps a mismatched checksum must never silently empty the registry.
    @Test
    void tornValidJsonNeverSilentlyEmptiesRegistry(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        newRegistry(store).register(fn("alpha"));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(file));
        ObjectNode emptyState = mapper.createObjectNode();
        emptyState.put("version", 2);
        emptyState.putArray("functions");
        root.put("payload", mapper.writeValueAsString(emptyState));
        Files.write(file, mapper.writeValueAsBytes(root));

        CodecException e = assertThrows(CodecException.class,
                () -> newRegistry(new JsonFileFunctionStore(file)));
        assertEquals(ErrorCode.CORRUPTED_STATE, e.code());
    }

    // 4: a crash leftover temp file (even one containing valid JSON) is never loaded.
    @Test
    void leftoverTempIsNeverTreatedAsState(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Path tmp = dir.resolve("registry.json.tmp");
        FaultInjection store = new FaultInjection(file);
        newRegistry(store).register(fn("alpha"));

        // Drop a plausible-looking newer state into the temp slot.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fake = mapper.createObjectNode();
        fake.put("version", 1);
        fake.putArray("functions").addObject().put("name", "fromTmp");
        Files.writeString(tmp, mapper.writeValueAsString(fake));

        FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
        assertEquals(1, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("alpha"));
        assertThrows(CodecException.class, () -> restarted.resolveByName("fromTmp"));
        assertFalse(Files.exists(tmp));
    }

    // 4: a previous-version (v1, no checksum) registry file loads with no migration.
    @Test
    void versionOneFileLoadsWithoutMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode v1 = mapper.createObjectNode();
        v1.put("version", 1);
        var fnNode = v1.putArray("functions").addObject();
        fnNode.put("name", "legacy");
        fnNode.putArray("inputs").addObject().put("name", "x").put("type", "uint256");
        fnNode.putArray("outputs");
        Files.write(file, mapper.writeValueAsBytes(v1));

        FunctionRegistry registry = newRegistry(new JsonFileFunctionStore(file));
        FunctionDefinition legacy = registry.resolveByName("legacy");
        assertEquals("uint256", legacy.inputs().get(0).type().canonicalName());

        // A subsequent write upgrades to the checksummed format without losing data.
        registry.register(fn("modern"));
        FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
        assertEquals(2, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("legacy"));
        assertNotNull(restarted.resolveByName("modern"));
        byte[] written = Files.readAllBytes(file);
        assertTrue(new String(written).contains("\"checksum\""));
    }

    // 5: persistence failures and caller input errors use distinct codes.
    @Test
    void storageFaultAndInputErrorHaveDistinctCodes(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);

        store.failAt(FaultInjection.Stage.WRITE);
        CodecException storageError = assertThrows(CodecException.class,
                () -> registry.register(fn("ok")));
        assertEquals(ErrorCode.PERSISTENCE_ERROR, storageError.code());

        store.disarm();
        // Caller input errors are IllegalArgument/CodecException with an input code,
        // never a storage failure. A blank name is rejected by the definition
        // constructor before any persistence is attempted.
        assertThrows(IllegalArgumentException.class,
                () -> new FunctionDefinition("", List.of(), List.of()));
        assertEquals(0, registry.snapshot().byName().size(),
                "failed input validation must not register anything");
    }

    // 6: one concurrent registration failing with a storage fault does not affect the
    // others; successful registrations are queryable and survive a restart.
    @Test
    void concurrentFailureDoesNotAffectOtherRegistrations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);
        registry.register(fn("seed"));

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger failures = new AtomicInteger();
        ConcurrentHashMap<String, Boolean> succeeded = new ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicReference<Thread> faultingThread =
                new java.util.concurrent.atomic.AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    if (idx == 0) {
                        faultingThread.compareAndSet(null, Thread.currentThread());
                        store.failOnceForThread(FaultInjection.Stage.WRITE, faultingThread.get());
                    }
                    String name = "fn" + idx;
                    registry.register(fn(name));
                    succeeded.put(name, Boolean.TRUE);
                } catch (CodecException e) {
                    if (e.code() == ErrorCode.PERSISTENCE_ERROR) {
                        failures.incrementAndGet();
                    } else {
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // Exactly the faulting thread (idx 0) fails; all other registrations succeed.
        assertEquals(1, failures.get());
        for (int t = 1; t < threads; t++) {
            assertNotNull(registry.resolveByName("fn" + t), "fn" + t + " must be visible");
        }
        assertThrows(CodecException.class, () -> registry.resolveByName("fn0"));

        FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
        assertEquals(threads, restarted.snapshot().byName().size()); // seed + fn1..fn7
        for (int t = 1; t < threads; t++) {
            assertNotNull(restarted.resolveByName("fn" + t));
        }
        assertThrows(CodecException.class, () -> restarted.resolveByName("fn0"));
    }

    // 6: after a failed single registration, a subsequent atomic batch fully succeeds
    // and all its members are queryable and recoverable (the failed call poisoned
    // nothing).
    @Test
    void batchAfterFailureIsFullyDurable(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultInjection store = new FaultInjection(file);
        FunctionRegistry registry = newRegistry(store);
        registry.register(fn("seed"));

        store.failAt(FaultInjection.Stage.REPLACE);
        assertThrows(CodecException.class, () -> registry.register(fn("ghost")));
        store.disarm();

        registry.registerAll(List.of(fn("batchA"), fn("batchB"), fn("batchC")));
        for (String name : List.of("batchA", "batchB", "batchC")) {
            assertNotNull(registry.resolveByName(name));
        }
        assertThrows(CodecException.class, () -> registry.resolveByName("ghost"));

        FunctionRegistry restarted = newRegistry(new JsonFileFunctionStore(file));
        assertEquals(4, restarted.snapshot().byName().size());
        for (String name : List.of("seed", "batchA", "batchB", "batchC")) {
            assertNotNull(restarted.resolveByName(name));
        }
    }
}
