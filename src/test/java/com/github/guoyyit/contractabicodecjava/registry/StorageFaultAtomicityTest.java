package com.github.guoyyit.contractabicodecjava.registry;

import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault-injection coverage for requirements 1-6: every write/fsync/move failure
 * surfaces as PERSISTENCE_ERROR, leaves no trace in memory or on disk, preserves the
 * last complete state recoverably, and never affects sibling concurrent registrations.
 */
class StorageFaultAtomicityTest {

    private FunctionDefinition fn(String name) {
        return new FunctionDefinition(name,
                List.of(new Parameter("v", new AbiType.UInt(256))),
                List.of(new Parameter("ok", new AbiType.Bool())));
    }

    /**
     * Fails once at a chosen stage of the write pipeline, but only after being armed:
     * the first (baseline) successful persist is always allowed through.
     */
    private static final class FaultyStore extends JsonFileFunctionStore {
        private final WriteStage failAt;
        private boolean armed;
        private boolean injected;

        FaultyStore(Path file, WriteStage failAt) {
            super(file);
            this.failAt = failAt;
        }

        void arm() {
            armed = true;
        }

        @Override
        protected void injectWriteFault(WriteStage stage) throws IOException {
            if (armed && !injected && stage == failAt) {
                injected = true;
                throw new IOException("simulated storage fault at " + stage
                        + " (disk full / quota exhausted / read-only directory)");
            }
        }
    }

    private FunctionRegistry registry(FunctionStore store) {
        return new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(16),
                store);
    }

    private void assertNotFound(FunctionRegistry r, String name) {
        CodecException e = assertThrows(CodecException.class, () -> r.resolveByName(name));
        assertEquals(ErrorCode.FUNCTION_NOT_FOUND, e.code());
    }

    @Test
    void everyWriteStageFailureIsAPersistenceErrorAndLeavesNoTrace(@TempDir Path dir) {
        for (JsonFileFunctionStore.WriteStage stage : JsonFileFunctionStore.WriteStage.values()) {
            Path file = dir.resolve("registry-" + stage.name() + ".json");
            Path tmp = dir.resolve("registry-" + stage.name() + ".json.tmp");
            FaultyStore store = new FaultyStore(file, stage);
            FunctionRegistry r = registry(store);

            // A complete prior state exists; arm the fault only after it is durable.
            r.register(fn("alpha"));
            store.arm();

            CodecException e = assertThrows(CodecException.class, () -> r.register(fn("beta")),
                    "fault at " + stage + " must fail the registration");
            assertEquals(ErrorCode.PERSISTENCE_ERROR, e.code(),
                    "fault at " + stage + " must be classified as a storage fault");

            // No observable trace of the failed registration in memory or on disk.
            assertEquals(1, r.snapshot().byName().size(), "memory state rolled back at " + stage);
            assertNotNull(r.resolveByName("alpha"));
            assertNotFound(r, "beta");
            assertFalse(Files.exists(tmp), "temp residue must be cleaned at " + stage);

            // The last complete file is intact and verifiable after "restart".
            FunctionRegistry restarted = registry(new JsonFileFunctionStore(file));
            assertEquals(1, restarted.snapshot().byName().size(),
                    "restart must recover exactly the prior complete state at " + stage);
            assertNotNull(restarted.resolveByName("alpha"));
            assertNotFound(restarted, "beta");

            // Fault lifted: next registration succeeds fully.
            restarted.register(fn("gamma"));
            FunctionRegistry again = registry(new JsonFileFunctionStore(file));
            assertEquals(2, again.snapshot().byName().size(),
                    "post-fault registration must durably include the new function at " + stage);
            assertNotNull(again.resolveByName("alpha"));
            assertNotNull(again.resolveByName("gamma"));
        }
    }

    @Test
    void failedRegistrationIsNotCarriedIntoLaterSnapshots(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultyStore store = new FaultyStore(file, JsonFileFunctionStore.WriteStage.WRITE);
        FunctionRegistry r = registry(store);
        r.register(fn("alpha"));
        store.arm();

        assertThrows(CodecException.class, () -> r.register(fn("beta")));

        // A later successful registration persists only real state, never the failed one.
        FunctionRegistry r2 = registry(new JsonFileFunctionStore(file));
        r2.register(fn("gamma"));
        FunctionRegistry r3 = registry(new JsonFileFunctionStore(file));
        assertEquals(2, r3.snapshot().byName().size());
        assertNotNull(r3.resolveByName("gamma"));
        assertNotFound(r3, "beta");
    }

    @Test
    void realReadOnlyDirectoryFailsRegistration(@TempDir Path dir) throws Exception {
        // Requires POSIX permissions; skipped on file systems where the check cannot apply.
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Path file = dir.resolve("registry.json");
        FunctionRegistry r = registry(new JsonFileFunctionStore(file));
        r.register(fn("alpha"));

        Set<PosixFilePermission> readOnly = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        Set<PosixFilePermission> writable = new HashSet<>(readOnly);
        writable.add(PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(dir, readOnly);
            // Root bypasses POSIX write permission; skip when the directory stays writable.
            if (Files.isWritable(dir)) {
                return;
            }
            CodecException e = assertThrows(CodecException.class, () -> r.register(fn("beta")));
            assertEquals(ErrorCode.PERSISTENCE_ERROR, e.code());
            assertEquals(1, r.snapshot().byName().size());
            assertNotFound(r, "beta");
            FunctionRegistry restarted = registry(new JsonFileFunctionStore(file));
            assertEquals(1, restarted.snapshot().byName().size());
            assertNotNull(restarted.resolveByName("alpha"));
        } finally {
            Files.setPosixFilePermissions(dir, writable);
        }
    }

    @Test
    void tornTempFilesNeverLoadAsStateAndNeverBlockRegistration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Path tmp = dir.resolve("registry.json.tmp");
        FunctionRegistry r = registry(new JsonFileFunctionStore(file));
        r.register(fn("alpha"));

        // Simulate a crash leftover; even a syntactically valid torn temp is ignored.
        Files.writeString(tmp, "{\"version\":2,\"functions\":[],\"checksum\":\""
                + "0".repeat(64) + "\"}");
        FunctionRegistry restarted = registry(new JsonFileFunctionStore(file));
        assertEquals(1, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("alpha"));
        assertFalse(Files.exists(tmp), "stale temp must be removed on startup");

        // A leftover temp must not block the next write (tmp is truncated/recreated).
        Files.writeString(tmp, "garbage");
        restarted.register(fn("beta"));
        assertFalse(Files.exists(tmp));
        assertEquals(2, registry(new JsonFileFunctionStore(file)).snapshot().byName().size());
    }

    @Test
    void newWritesCarryVerifiedChecksum(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        FunctionRegistry r = registry(new JsonFileFunctionStore(file));
        r.register(fn("alpha"));
        String content = Files.readString(file);
        assertTrue(content.contains("\"version\" : 2"));
        assertTrue(content.matches("(?s).*\"checksum\" : \"[0-9a-f]{64}\".*"),
                "new state files must carry a SHA-256 integrity checksum");

        // Tamper with a function body without updating the checksum -> CORRUPTED_STATE.
        String tampered = content.replace("alpha", "alpha2");
        Files.writeString(file, tampered);
        CodecException e = assertThrows(CodecException.class,
                () -> new JsonFileFunctionStore(file).load());
        assertEquals(ErrorCode.CORRUPTED_STATE, e.code());
    }

    @Test
    void parseableButTruncatedStateIsCorruptedNeverSilentEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        FunctionRegistry r = registry(new JsonFileFunctionStore(file));
        r.register(fn("alpha"));
        String content = Files.readString(file);

        // Different tail cuts (missing checksum / cut mid-body) must all be CORRUPTED_STATE.
        for (int cut : new int[]{content.length() - 16, content.length() / 2, 64}) {
            if (cut <= 0) {
                continue;
            }
            Files.writeString(file, content.substring(0, cut));
            CodecException e = assertThrows(CodecException.class,
                    () -> new JsonFileFunctionStore(file).load());
            assertEquals(ErrorCode.CORRUPTED_STATE, e.code(),
                    "truncation at " + cut + " must not be accepted as an empty registry");
        }
    }

    @Test
    void v2WithoutChecksumIsRejectedEvenWhenItIsParseableJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Files.writeString(file, "{\"version\":2,\"functions\":[]}");
        CodecException e = assertThrows(CodecException.class,
                () -> new JsonFileFunctionStore(file).load());
        assertEquals(ErrorCode.CORRUPTED_STATE, e.code());
    }

    @Test
    void legacyV1FileLoadsWithoutMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Files.writeString(file, """
                {
                  "version" : 1,
                  "functions" : [ {
                    "name" : "alpha",
                    "inputs" : [ { "name" : "v", "type" : "uint256" } ],
                    "outputs" : [ { "name" : "ok", "type" : "bool" } ]
                  } ]
                }
                """);
        FunctionRegistry r = registry(new JsonFileFunctionStore(file));
        assertEquals(1, r.snapshot().byName().size());
        assertNotNull(r.resolveByName("alpha"));

        // The first new write upgrades the file to version 2 with verification intact.
        r.register(fn("beta"));
        FunctionRegistry restarted = registry(new JsonFileFunctionStore(file));
        assertEquals(2, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("beta"));
    }

    @Test
    void legacyUnversionedFileLoads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Files.writeString(file, """
                {
                  "functions" : [ {
                    "name" : "alpha",
                    "inputs" : [ { "name" : "v", "type" : "uint256" } ],
                    "outputs" : [ ]
                  } ]
                }
                """);
        FunctionRegistry r = registry(new JsonFileFunctionStore(file));
        assertNotNull(r.resolveByName("alpha"));
    }

    @Test
    void oneFailingConcurrentRegistrationDoesNotAffectOthers(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        // Fail roughly every other fsync so part of the batch hits storage faults.
        JsonFileFunctionStore flaky = new JsonFileFunctionStore(file) {
            private int writes;

            @Override
            protected void injectWriteFault(WriteStage stage) throws IOException {
                if (stage == WriteStage.FORCE && (writes++ % 2 == 1)) {
                    throw new IOException("simulated intermittent fsync failure");
                }
            }
        };
        FunctionRegistry r = registry(flaky);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                start.await();
                r.register(fn("fn" + idx));
                return null;
            }));
        }
        start.countDown();

        int failures = 0;
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception outer) {
                failures++;
                Throwable cause = outer.getCause();
                if (!(cause instanceof CodecException)) {
                    cause = cause.getCause();
                }
                assertTrue(cause instanceof CodecException, "unexpected failure: " + outer);
                assertEquals(ErrorCode.PERSISTENCE_ERROR, ((CodecException) cause).code());
            }
        }
        pool.shutdownNow();

        assertTrue(failures > 0, "at least one registration must have failed");
        List<String> live = r.snapshot().byName().keySet().stream()
                .map(s -> s.substring(0, s.indexOf('('))).sorted().toList();
        List<String> restarted = registry(new JsonFileFunctionStore(file))
                .snapshot().byName().keySet().stream()
                .map(s -> s.substring(0, s.indexOf('('))).sorted().toList();
        assertEquals(live, restarted, "every visible success must survive a restart");
        assertTrue(live.size() < threads, "failed registrations must not appear");
        assertEquals(threads, live.size() + failures, "all registrations accounted for");
    }

    @Test
    void batchRegistrationIsAllOrNothingOnStorageFault(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FaultyStore store = new FaultyStore(file, JsonFileFunctionStore.WriteStage.FORCE);
        FunctionRegistry r = registry(store);
        r.register(fn("alpha"));
        store.arm();

        CodecException e = assertThrows(CodecException.class,
                () -> r.registerAll(List.of(fn("beta"), fn("gamma"))));
        assertEquals(ErrorCode.PERSISTENCE_ERROR, e.code());
        assertEquals(1, r.snapshot().byName().size());
        assertNotFound(r, "beta");
        assertNotFound(r, "gamma");

        FunctionRegistry restarted = registry(new JsonFileFunctionStore(file));
        assertEquals(1, restarted.snapshot().byName().size());
        assertNotNull(restarted.resolveByName("alpha"));
        assertNotFound(restarted, "beta");
        assertNotFound(restarted, "gamma");
    }
}
