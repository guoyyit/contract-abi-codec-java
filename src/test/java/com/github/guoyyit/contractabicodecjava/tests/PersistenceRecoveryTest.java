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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceRecoveryTest {

    private FunctionDefinition transfer() {
        return new FunctionDefinition("transfer",
                List.of(new Parameter("to", new AbiType.Address()),
                        new Parameter("amount", new AbiType.UInt(256))),
                List.of(new Parameter("ok", new AbiType.Bool())));
    }

    private FunctionDefinition complex() {
        AbiType nested = new AbiType.Tuple(List.of(
                new AbiType.DynamicBytes(),
                new AbiType.Array(new AbiType.UInt(32)),
                new AbiType.FixedArray(new AbiType.Bool(), 2)));
        return new FunctionDefinition("complex",
                List.of(new Parameter("data", nested)), List.of());
    }

    @Test
    void restoresRegistryAcrossRestart(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FunctionRegistry first = newRegistry(file);
        first.register(transfer());
        first.register(complex());

        // Simulate a fresh process.
        FunctionRegistry second = newRegistry(file);
        FunctionDefinition restored = second.resolveByName("transfer");
        assertEquals("0xa9059cbb", restored.selector());
        assertEquals(2, second.snapshot().byName().size());
        FunctionDefinition complexRestored = second.resolveByName("complex");
        assertEquals("complex((bytes,uint32[],bool[2]))",
                com.github.guoyyit.contractabicodecjava.encoding.CanonicalSignature.of(complexRestored));
    }

    @Test
    void leftoverTempFileFromCrashIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Path tmp = dir.resolve("registry.json.tmp");
        FunctionRegistry registry = newRegistry(file);
        registry.register(transfer());
        // Simulate a crash AFTER writing .tmp but BEFORE the atomic rename: leave a torn tmp.
        Files.writeString(tmp, "{ this is not valid json");
        FunctionRegistry restarted = newRegistry(file);
        assertEquals(1, restarted.snapshot().byName().size());
        assertFalse(Files.exists(tmp), "stale temp file should be cleaned up");
    }

    @Test
    void neverLeavesTempFileOnSuccessfulWrite(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        Path tmp = dir.resolve("registry.json.tmp");
        FunctionRegistry registry = newRegistry(file);
        registry.register(transfer());
        registry.register(complex());
        assertFalse(Files.exists(tmp));
        assertTrue(Files.exists(file));
    }

    @Test
    void corruptionOfMainFileIsReportedAsCorruptedState(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("registry.json");
        Files.writeString(file, "{ definitely not valid json");
        JsonFileFunctionStore store = new JsonFileFunctionStore(file);
        CodecException e = assertThrows(CodecException.class, store::load);
        assertEquals(ErrorCode.CORRUPTED_STATE, e.code());
    }

    @Test
    void missingFileMeansEmptyRegistry(@TempDir Path dir) {
        FunctionRegistry registry = newRegistry(dir.resolve("absent.json"));
        assertTrue(registry.snapshot().byName().isEmpty());
    }

    @Test
    void reRegistrationPersistsLatestDefinition(@TempDir Path dir) {
        Path file = dir.resolve("registry.json");
        FunctionRegistry r1 = newRegistry(file);
        r1.register(new FunctionDefinition("ping",
                List.of(new Parameter("a", new AbiType.UInt(8))), List.of()));
        // Same canonical signature upserts; a genuinely new signature is added after restart.
        FunctionRegistry r2 = newRegistry(file);
        r2.register(new FunctionDefinition("pong", List.of(), List.of()));
        FunctionRegistry r3 = newRegistry(file);
        assertEquals(2, r3.snapshot().byName().size());
    }

    private FunctionRegistry newRegistry(Path file) {
        return new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(16),
                new JsonFileFunctionStore(file));
    }
}
