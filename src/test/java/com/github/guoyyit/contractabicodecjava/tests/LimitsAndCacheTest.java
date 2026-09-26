package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.cache.BoundedLruCache;
import com.github.guoyyit.contractabicodecjava.config.CodecProperties;
import com.github.guoyyit.contractabicodecjava.service.CodecService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LimitsAndCacheTest {

    @TempDir
    Path tempDir;

    private CodecService newService(int maxInputBytes, int maxDepth, int cacheCapacity) {
        CodecProperties p = new CodecProperties();
        p.setStoreFile(tempDir.resolve("registry.bin").toString());
        p.setPersistOnChange(false);
        p.setMaxInputBytes(maxInputBytes);
        p.setMaxDepth(maxDepth);
        p.setCacheCapacity(cacheCapacity);
        return new CodecService(p);
    }

    // ------------------------------------------------------------ 输入长度上限

    @Test
    void oversizedInputFailsFast() {
        CodecService service = newService(1024, 32, 16);
        service.register("f", List.of("bytes"), List.of(), null);
        byte[] huge = new byte[2048];
        // 选择器填成已注册的 f
        byte[] call = service.encodeCall("f", List.of(new byte[]{1}));
        System.arraycopy(call, 0, huge, 0, call.length);
        AbiException e = assertThrows(AbiException.class, () -> service.decodeCall(huge, null));
        assertEquals(AbiException.ErrorCode.INPUT_LIMIT_EXCEEDED, e.code());

        // 超限失败后服务仍正常处理其他请求
        List<Object> args = service.decodeCall(call, null);
        assertArrayEquals(new byte[]{1}, (byte[]) args.get(0));
    }

    @Test
    void oversizedEncodedOutputRejected() {
        CodecService service = newService(256, 32, 16);
        service.register("f", List.of("bytes"), List.of(), null);
        AbiException e = assertThrows(AbiException.class,
                () -> service.encodeCall("f", List.of(new byte[1024])));
        assertEquals(AbiException.ErrorCode.INPUT_LIMIT_EXCEEDED, e.code());
    }

    // ------------------------------------------------------------ 嵌套深度上限

    @Test
    void deepTypeRejectedAtRegistration() {
        CodecService service = newService(65536, 4, 16);
        AbiException e = assertThrows(AbiException.class,
                () -> service.register("deep", List.of("uint256[][][][][]"), List.of(), null));
        assertEquals(AbiException.ErrorCode.DEPTH_LIMIT_EXCEEDED, e.code());
        // 注册失败不留残留
        assertEquals(0, service.snapshot().list().size());
    }

    @Test
    void deepNestingRejectedAtDecode() {
        // 用宽松编解码器构造合法编码，再用严格深度限制的编解码器解码
        com.github.guoyyit.contractabicodecjava.abi.AbiCodec loose =
                new com.github.guoyyit.contractabicodecjava.abi.AbiCodec(32);
        com.github.guoyyit.contractabicodecjava.abi.AbiCodec strict =
                new com.github.guoyyit.contractabicodecjava.abi.AbiCodec(2);
        java.util.List<com.github.guoyyit.contractabicodecjava.abi.AbiType> types =
                java.util.List.of(com.github.guoyyit.contractabicodecjava.abi.TypeParser.parse("uint256[][]"));
        byte[] enc = loose.encodeTuple(types,
                java.util.List.of(java.util.List.of(java.util.List.of(BigInteger.ONE))));
        AbiException e = assertThrows(AbiException.class, () -> strict.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.DEPTH_LIMIT_EXCEEDED, e.code());
    }

    // ------------------------------------------------------------ 缓存有界与失效

    @Test
    void lruCacheEvictsOldest() {
        BoundedLruCache<Integer, Integer> cache = new BoundedLruCache<>(3);
        for (int i = 0; i < 10; i++) {
            cache.put(i, i);
        }
        assertEquals(3, cache.size());
        assertNull(cache.get(0)); // 最旧的已被淘汰
        assertEquals(9, cache.get(9));
    }

    @Test
    void cacheNeverExceedsCapacityUnderLoad() {
        CodecService service = newService(65536, 32, 8);
        for (int i = 0; i < 100; i++) {
            service.register("f" + i, List.of("uint256"), List.of("uint256"), null);
            service.encodeCall("f" + i, List.of(BigInteger.ONE));
        }
        assertTrue(service.cache().size() <= 8,
                "cache size " + service.cache().size() + " exceeds capacity");
    }

    @Test
    void cacheInvalidatesOnRegistryUpdate() {
        CodecService service = newService(65536, 32, 64);
        service.register("f", List.of("uint256"), List.of(), null);
        byte[] call1 = service.encodeCall("f", List.of(BigInteger.ONE));
        long v1 = service.snapshot().version();
        int sizeAfterFirst = service.cache().size();
        assertTrue(sizeAfterFirst > 0);

        // 更新定义：先移除旧签名再注册新签名（别名随之释放）
        assertTrue(service.remove("f(uint256)"));
        service.register("f", List.of("uint256", "bool"), List.of(), null);
        long v2 = service.snapshot().version();
        assertTrue(v2 > v1);

        // 新编码必须基于新定义（两个参数），而不是旧布局
        byte[] call2 = service.encodeCall("f", List.of(BigInteger.ONE, true));
        assertEquals(4 + 64, call2.length);
        assertEquals(4 + 32, call1.length);

        // 解码旧调用数据现在应当失败（参数布局已变：旧数据长度不符）
        assertThrows(AbiException.class, () -> service.decodeCall(call1, null));
    }

    @Test
    void unknownSelectorFailsCleanly() {
        CodecService service = newService(65536, 32, 16);
        service.register("f", List.of("uint256"), List.of(), null);
        byte[] call = service.encodeCall("f", List.of(BigInteger.ONE));
        call[0] ^= 0x7F; // 破坏选择器
        AbiException e = assertThrows(AbiException.class, () -> service.decodeCall(call, null));
        assertEquals(AbiException.ErrorCode.UNKNOWN_SELECTOR, e.code());
    }
}
