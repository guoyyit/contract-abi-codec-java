package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.config.CodecProperties;
import com.github.guoyyit.contractabicodecjava.persist.RegistryStore;
import com.github.guoyyit.contractabicodecjava.registry.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.service.CodecService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceRecoveryTest {

    @TempDir
    Path tempDir;

    private CodecProperties props(Path store) {
        CodecProperties p = new CodecProperties();
        p.setStoreFile(store.toString());
        p.setPersistOnChange(true);
        return p;
    }

    @Test
    void restartRecoversFullRegistry() {
        Path store = tempDir.resolve("registry.bin");
        CodecService first = new CodecService(props(store));
        first.recover();
        first.register("transfer", List.of("address", "uint256"), List.of("bool"), null);
        first.register("nested", List.of("(uint256,bytes)[]", "string"), List.of("bytes32"), "nest");

        // 模拟重启：全新服务实例从同一文件恢复
        CodecService second = new CodecService(props(store));
        second.recover();
        assertEquals(2, second.snapshot().list().size());

        // 恢复后编解码完整可用
        byte[] call = second.encodeCall("transfer",
                List.of(new byte[20], BigInteger.valueOf(1000)));
        List<Object> args = second.decodeCall(call, null);
        assertEquals(BigInteger.valueOf(1000), args.get(1));
        assertArrayEquals(new byte[20], (byte[]) args.get(0));
    }

    @Test
    void crashDuringWriteLeavesPreviousStateIntact() throws Exception {
        Path store = tempDir.resolve("registry.bin");
        RegistryStore rs = new RegistryStore(store);
        CodecService service = new CodecService(props(store));
        service.recover();
        service.register("stable", List.of("uint256"), List.of(), null);
        byte[] goodContent = Files.readAllBytes(store);

        // 模拟崩溃：留下半写入的 .tmp 文件，正式文件未被触碰
        Path tmp = tempDir.resolve("registry.bin.tmp");
        Files.write(tmp, new byte[]{0x41, 0x42, 0x49, 0x52, 0, 0, 0, 1, 0, 0}); // 截断的写入

        List<FunctionDefinition> loaded = rs.load();
        assertEquals(1, loaded.size());
        assertEquals("stable(uint256)", loaded.get(0).canonicalSignature());
        assertArrayEquals(goodContent, Files.readAllBytes(store));

        // 清理临时文件后再次保存正常
        rs.cleanupTemp();
        assertFalse(Files.exists(tmp));
        service.register("more", List.of("bool"), List.of(), null);
        assertEquals(2, rs.load().size());
    }

    @Test
    void corruptedMainFileDetected() throws Exception {
        Path store = tempDir.resolve("registry.bin");
        CodecService service = new CodecService(props(store));
        service.recover();
        service.register("f", List.of("uint256"), List.of(), null);

        // 翻转正式文件中间一个字节（校验和必须发现）
        byte[] content = Files.readAllBytes(store);
        content[content.length / 2] ^= 0x01;
        Files.write(store, content);

        RegistryStore rs = new RegistryStore(store);
        AbiException e = assertThrows(AbiException.class, rs::load);
        assertEquals(AbiException.ErrorCode.PERSISTENCE_CORRUPT, e.code());
    }

    @Test
    void truncatedMainFileDetected() throws Exception {
        Path store = tempDir.resolve("registry.bin");
        CodecService service = new CodecService(props(store));
        service.recover();
        service.register("f", List.of("uint256"), List.of(), null);

        byte[] content = Files.readAllBytes(store);
        Files.write(store, java.util.Arrays.copyOf(content, content.length - 5));

        RegistryStore rs = new RegistryStore(store);
        AbiException e = assertThrows(AbiException.class, rs::load);
        assertEquals(AbiException.ErrorCode.PERSISTENCE_CORRUPT, e.code());
    }

    @Test
    void missingFileLoadsEmpty() {
        RegistryStore rs = new RegistryStore(tempDir.resolve("nonexistent.bin"));
        assertTrue(rs.load().isEmpty());
    }

    @Test
    void persistedSelectorsAreRecomputedOnLoad() {
        Path store = tempDir.resolve("registry.bin");
        CodecService service = new CodecService(props(store));
        service.recover();
        FunctionDefinition def = service.register("transfer",
                List.of("address", "uint256"), List.of(), null);
        assertEquals("0xa9059cbb", def.selectorHex());

        CodecService revived = new CodecService(props(store));
        revived.recover();
        FunctionDefinition loaded = revived.snapshot().list().get(0);
        assertEquals("0xa9059cbb", loaded.selectorHex());
    }
}
