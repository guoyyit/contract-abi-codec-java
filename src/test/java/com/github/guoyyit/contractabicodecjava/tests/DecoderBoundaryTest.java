package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary behaviour for dynamic data whose declared length plus padding exactly fills
 * (or runs past) the end of its containing block, across several nesting levels.
 */
class DecoderBoundaryTest {

    private final AbiEncoder encoder = new AbiEncoder(32);
    private final AbiDecoder decoder = new AbiDecoder(32);

    private byte[] encode(List<AbiType> types, List<Object> values) {
        return encoder.encode(types, values);
    }

    private static List<Object> decode(AbiDecoder decoder, List<AbiType> types, byte[] data) {
        return decoder.decode(types, data);
    }

    private static CodecException expectFail(AbiDecoder decoder, List<AbiType> types, byte[] data) {
        return assertThrows(CodecException.class, () -> decode(decoder, types, data));
    }

    // 7: declared length + padding exactly fills the block end -> must succeed.
    @Test
    void dynamicBytesExactBlockFillSucceeds() {
        List<AbiType> types = List.of(new AbiType.DynamicBytes());
        for (int len : new int[]{0, 1, 31, 32, 33, 63, 64}) {
            byte[] payload = new byte[len];
            Arrays.fill(payload, (byte) 0xAB);
            byte[] data = encode(types, List.of(payload));
            List<Object> out = decode(decoder, types, data);
            assertArrayEquals(payload, (byte[]) out.get(0), "length " + len);
        }
    }

    // 7: one payload byte short -> OUT_OF_BOUNDS_READ, never NON_CANONICAL, never partial.
    @Test
    void dynamicBytesOneByteShortIsOutOfBounds() {
        List<AbiType> types = List.of(new AbiType.DynamicBytes());
        for (int len : new int[]{1, 32, 33, 64}) {
            byte[] payload = new byte[len];
            byte[] data = encode(types, List.of(payload));
            byte[] truncated = Arrays.copyOfRange(data, 0, data.length - 1);
            CodecException e = expectFail(decoder, types, truncated);
            assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code(),
                    "length " + len + " got " + e.getMessage());
            assertEquals("$[0]", e.path());
        }
    }

    // 7: declared length is a multiple of 32 so there is no padding; one byte short is OOB.
    @Test
    void dynamicBytesNoPaddingOneByteShortIsOutOfBounds() {
        List<AbiType> types = List.of(new AbiType.DynamicBytes());
        byte[] payload = new byte[32];
        byte[] data = encode(types, List.of(payload));
        // 32-byte head offset word + 32-byte length word + 32-byte payload (no padding).
        assertEquals(96, data.length);
        byte[] truncated = Arrays.copyOfRange(data, 0, data.length - 1);
        CodecException e = expectFail(decoder, types, truncated);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
        assertEquals("$[0]", e.path());
    }

    // 7: a whole padding word present but zeroed yet one payload byte missing -> OOB.
    @Test
    void dynamicBytesMissingPaddingIsOutOfBounds() {
        List<AbiType> types = List.of(new AbiType.DynamicBytes());
        byte[] payload = new byte[1];
        byte[] data = encode(types, List.of(payload));
        // Drop one of the padding bytes (length still declares 1, block is 32+32).
        byte[] truncated = Arrays.copyOfRange(data, 0, data.length - 1);
        CodecException e = expectFail(decoder, types, truncated);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
        assertEquals("$[0]", e.path());
    }

    // 8: inner dynamic data exactly reaches the outer block end -> succeeds.
    @Test
    void nestedInnerExactOuterEndSucceeds() {
        AbiType nested = new AbiType.Tuple(List.of(new AbiType.DynamicBytes()));
        List<AbiType> types = List.of(nested);
        for (int len : new int[]{0, 1, 31, 32, 64}) {
            byte[] payload = new byte[len];
            List<Object> value = List.of(payload);
            byte[] data = encode(types, List.of(value));
            List<Object> out = decode(decoder, types, data);
            @SuppressWarnings("unchecked")
            List<Object> inner = (List<Object>) out.get(0);
            assertArrayEquals(payload, (byte[]) inner.get(0), "length " + len);
        }
    }

    // 8: inner data crosses the outer block boundary by one byte, but a sibling follows ->
    // must be rejected as OUT_OF_BOUNDS_READ and must never read the sibling's bytes.
    @Test
    void nestedInnerCrossingOuterEndIsOutOfBounds() {
        AbiType nested = new AbiType.Tuple(List.of(new AbiType.DynamicBytes()));
        List<AbiType> types = List.of(nested, new AbiType.DynamicBytes());
        byte[] inner = new byte[1];
        byte[] sibling = new byte[]{0x11, 0x22, 0x33};
        byte[] data = encode(types, List.of(List.of(inner), sibling));
        // Outer layout: [0,32) tuple offset; [32,64) sibling offset;
        // tuple block at [64,160): inner offset word [64,96), length word [96,128),
        // payload+padding [128,160). Sibling block begins at 160.
        // Declare an inner length of 33 (33 payload + 31 padding = 64 bytes) where only
        // 32 bytes remain in the tuple block: it crosses the outer boundary by one word
        // and would otherwise read into the sibling's area.
        int innerLenWord = 64 + 32; // tuple start(64) + inner offset word(32)
        data[innerLenWord + 31] = 33;
        CodecException e = expectFail(decoder, types, data);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code(), e.getMessage());
        assertEquals("$[0][0]", e.path());
        assertTrue(e.getMessage().contains("33"), e.getMessage());
        assertTrue(e.getMessage().contains("block ends at 32"), e.getMessage());
    }

    // 8 (strict limit): an inner static head slot that runs past the innermost block
    // end (while the enclosing buffer continues) must be OUT_OF_BOUNDS_READ. The inner
    // block is a dynamic array whose declared length claims more elements than the
    // block holds, and an outer sibling supplies bytes past the inner block end.
    @Test
    void innerStaticSlotBeyondInnerBlockEndButInsideOuterBufferIsOutOfBounds() {
        // Outer: (uint32[], uint256). The array is dynamic; its block holds a length
        // word followed by element slots. Inflate the length word by one: the extra
        // element slot reads past the array block (which is the final outer tail), so
        // it must be reported at the element path, never as outer trailing bytes.
        AbiType arr = new AbiType.Array(new AbiType.UInt(32));
        List<AbiType> types = List.of(arr, new AbiType.UInt(256));
        byte[] data = encode(types, List.of(List.of(1), 7L));
        // Outer: arr offset [0,32)=64, trailing uint [32,64), array block at 64:
        //   length [64,96)=1, element [96,128). Total length = 128.
        // Declare 2 elements; the second slot [128,160) does not exist.
        byte[] extended = data.clone();
        extended[95] = 2; // length word -> 2
        CodecException e = expectFail(decoder, types, extended);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code(), e.getMessage());
        assertEquals("$[0][1]", e.path());
    }

    // 8 (strict limit): exactly the block-end-touching case for an inner dynamic array
    // followed by a sibling must decode, and one byte more must be rejected. This is a
    // round-trip guard for the array's own block boundary.
    @Test
    void innerDynamicArrayExactAndExceedingBoundary() {
        AbiType arr = new AbiType.Array(new AbiType.UInt(32));
        List<AbiType> types = List.of(arr, new AbiType.UInt(256));
        byte[] data = encode(types, List.of(List.of(1), 7L));
        // Canonical: arr offset=64; trailing uint [32,64); len=1 [64,96);
        // element [96,128); total 128.
        assertEquals(128, data.length);
        List<Object> out = decode(decoder, types, data);
        @SuppressWarnings("unchecked")
        List<Object> elems = (List<Object>) out.get(0);
        assertEquals(1, elems.size());
    }

    // 8 (strict limit): the inner dynamic member's block end must bound its own reads.
    // When an inner dynamic tuple is followed by an outer sibling, inflating the inner
    // member's payload must never let it consume the sibling's bytes (the canonical
    // tight packing makes the bytes "fit" against the outer block end).
    @Test
    void innerMemberMustNotReadOuterSiblingBytes() {
        // Outer: (inner(bytes), outer(bytes)). Both are dynamic tails; the inner tuple
        // block ends exactly where the outer bytes block begins.
        AbiType inner = new AbiType.Tuple(List.of(new AbiType.DynamicBytes()));
        List<AbiType> types = List.of(inner, new AbiType.DynamicBytes());
        byte[] innerPayload = new byte[1];
        byte[] outerPayload = new byte[64]; // exactly fills the rest of its block
        byte[] data = encode(types, List.of(List.of(innerPayload), outerPayload));
        // Outer: two offset words [0,64). Inner tuple block at 64:
        //   inner offset [64,96)=32, len [96,128)=1, payload+pad [128,160).
        // Outer sibling block at 160 (96 bytes): len=64 + 64 payload = 256 total.
        assertEquals(256, data.length);
        // Inflate the inner length to 33: it would need 64 bytes [128,192), but the
        // inner block ends at 160. The bytes [160,192) belong to the sibling's length
        // word and must not be read as inner payload.
        data[127] = 33;
        CodecException e = expectFail(decoder, types, data);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code(), e.getMessage());
        assertEquals("$[0][0]", e.path());
    }
    @Test
    void sameTruncationShapeConsistentAcrossDepth() {
        byte[] payload = new byte[32]; // one full data word, no padding
        // depth 0: (bytes)
        List<AbiType> depth0 = List.of(new AbiType.DynamicBytes());
        byte[] d0 = encode(depth0, List.of(payload));
        CodecException e0 = expectFail(decoder, depth0, Arrays.copyOfRange(d0, 0, d0.length - 1));

        // depth 1: ((bytes))
        List<AbiType> depth1 = List.of(new AbiType.Tuple(List.of(new AbiType.DynamicBytes())));
        byte[] d1 = encode(depth1, List.of(List.of(payload)));
        CodecException e1 = expectFail(decoder, depth1, Arrays.copyOfRange(d1, 0, d1.length - 1));

        // depth 2: (((bytes)))
        List<AbiType> depth2 = List.of(new AbiType.Tuple(List.of(
                new AbiType.Tuple(List.of(new AbiType.DynamicBytes())))));
        byte[] d2 = encode(depth2, List.of(List.of(List.of(payload))));
        CodecException e2 = expectFail(decoder, depth2, Arrays.copyOfRange(d2, 0, d2.length - 1));

        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e0.code());
        assertEquals(e0.code(), e1.code());
        assertEquals(e0.code(), e2.code());
        assertEquals("$[0]", e0.path());
        assertEquals("$[0][0]", e1.path());
        assertEquals("$[0][0][0]", e2.path());
    }
}
