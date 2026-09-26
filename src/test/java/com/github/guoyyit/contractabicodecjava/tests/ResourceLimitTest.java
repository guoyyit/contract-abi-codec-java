package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.api.CodecApi;
import com.github.guoyyit.contractabicodecjava.api.InProcessCodecApi;
import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.encoding.CodecService;
import com.github.guoyyit.contractabicodecjava.encoding.SelectorCalculator;
import com.github.guoyyit.contractabicodecjava.encoding.TypeLayoutCache;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import com.github.guoyyit.contractabicodecjava.model.TypeLayout;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.registry.FunctionStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLimitTest {

    private static final FunctionStore NOOP_STORE = new FunctionStore() {
        @Override public void persist(List<FunctionDefinition> functions) { }
        @Override public List<FunctionDefinition> load() { return List.of(); }
    };

    private FunctionRegistry registry() {
        return new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()),
                new TypeLayoutCache(8), NOOP_STORE);
    }

    @Test
    void encodingBeyondNestingDepthFailsFast() {
        AbiEncoder encoder = new AbiEncoder(3);
        AbiType encType = new AbiType.UInt(256);
        for (int i = 0; i < 5; i++) {
            encType = new AbiType.Tuple(List.of(encType));
        }
        Object encValue = 1;
        for (int i = 0; i < 5; i++) {
            encValue = List.of(encValue);
        }
        final AbiType finalType = encType;
        final Object finalValue = encValue;
        CodecException e = assertThrows(CodecException.class,
                () -> encoder.encode(List.of(finalType), List.of(finalValue)));
        assertEquals(ErrorCode.DEPTH_EXCEEDED, e.code());
    }

    @Test
    void decodingBeyondNestingDepthFailsFast() {
        // An encoding that is valid at depth 5 but decoded with limit 2.
        AbiEncoder deepEncoder = new AbiEncoder(10);
        AbiType decType = new AbiType.UInt(256);
        for (int i = 0; i < 5; i++) {
            decType = new AbiType.Tuple(List.of(decType));
        }
        Object decValue = 1;
        for (int i = 0; i < 5; i++) {
            decValue = List.of(decValue);
        }
        byte[] encoded = deepEncoder.encode(List.of(decType), List.of(decValue));
        AbiDecoder shallow = new AbiDecoder(2);
        final AbiType finalDecType = decType;
        CodecException e = assertThrows(CodecException.class,
                () -> shallow.decode(List.of(finalDecType), encoded));
        assertEquals(ErrorCode.DEPTH_EXCEEDED, e.code());
    }

    @Test
    void oversizedInputIsRejectedBeforeDecode() {
        FunctionRegistry registry = registry();
        CodecService service = new CodecService(registry,
                new AbiEncoder(32), new AbiDecoder(32), new TypeLayoutCache(8), 64);
        registry.register(new FunctionDefinition("big",
                List.of(new Parameter("a", new AbiType.UInt(256)),
                        new Parameter("b", new AbiType.UInt(256)),
                        new Parameter("c", new AbiType.UInt(256))),
                List.of()));
        byte[] tooBig = new byte[96]; // 3 words > 64 byte limit
        CodecException e = assertThrows(CodecException.class,
                () -> service.decodeArgs("big", tooBig));
        assertEquals(ErrorCode.INPUT_TOO_LARGE, e.code());
    }

    @Test
    void oversizedEncodedOutputIsRejected() {
        FunctionRegistry registry = registry();
        CodecService service = new CodecService(registry,
                new AbiEncoder(32), new AbiDecoder(32), new TypeLayoutCache(8), 64);
        registry.register(new FunctionDefinition("many",
                List.of(new Parameter("a", new AbiType.UInt(256)),
                        new Parameter("b", new AbiType.UInt(256)),
                        new Parameter("c", new AbiType.UInt(256))),
                List.of()));
        CodecException e = assertThrows(CodecException.class,
                () -> service.encodeArgs(registry.resolveByName("many"),
                        List.of(1, 2, 3)));
        assertEquals(ErrorCode.INPUT_TOO_LARGE, e.code());
    }

    @Test
    void oneOversizedRequestDoesNotAffectOthers() {
        FunctionRegistry registry = registry();
        CodecService service = new CodecService(registry,
                new AbiEncoder(32), new AbiDecoder(32), new TypeLayoutCache(8), 64);
        registry.register(new FunctionDefinition("one",
                List.of(new Parameter("a", new AbiType.UInt(256))), List.of()));
        assertThrows(CodecException.class,
                () -> service.decodeArgs("one", new byte[128]));
        // Service remains usable for compliant requests.
        byte[] ok = new byte[32];
        ok[31] = 5;
        assertEquals(java.math.BigInteger.valueOf(5), service.decodeArgs("one", ok).get(0));
    }

    @Test
    void layoutCacheIsBounded() {
        TypeLayoutCache cache = new TypeLayoutCache(3);
        for (int i = 0; i < 10; i++) {
            cache.getOrCompute(List.of(new AbiType.UInt(8 * ((i % 31) + 1))),
                    () -> new TypeLayout(List.of(new AbiType.UInt(256))));
        }
        assertTrue(cache.size() <= 3, "cache must respect capacity, size=" + cache.size());
    }

    @Test
    void layoutCacheInvalidatesOnRegistryChange() {
        TypeLayoutCache cache = new TypeLayoutCache(8);
        cache.getOrCompute(List.of(new AbiType.UInt(256)),
                () -> new TypeLayout(List.of(new AbiType.UInt(256))));
        assertEquals(1, cache.size());
        FunctionRegistry registry = new FunctionRegistry(
                new SelectorCalculator(SelectorHasher.keccak()), cache, NOOP_STORE);
        registry.register(new FunctionDefinition("x",
                List.of(new Parameter("a", new AbiType.UInt(8))), List.of()));
        assertEquals(0, cache.size(), "cache must be invalidated when definitions change");
    }

    @Test
    void structuredErrorCarriesCodeAndPath() {
        AbiEncoder encoder = new AbiEncoder(32);
        CodecException e = assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.UInt(8)), List.of(300)));
        assertEquals(ErrorCode.INTEGER_OVERFLOW, e.code());
        assertNotNull(e.toStructuredError().message());
        assertEquals("INTEGER_OVERFLOW", e.toStructuredError().code());
    }
}
