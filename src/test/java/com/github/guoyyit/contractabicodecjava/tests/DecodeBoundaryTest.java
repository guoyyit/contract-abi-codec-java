package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.util.Hex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary decoding rules (requirements 7-9):
 * declared length + padding exactly reaching the block end decodes; one missing payload
 * or padding byte is OUT_OF_BOUNDS_READ (never NON_CANONICAL_ENCODING); nested dynamic
 * data is bounded by its enclosing block and cannot read sibling/out-of-block bytes;
 * the same truncation shape reports the same code at every nesting depth and the error
 * path names the parameter that actually fails.
 */
class DecodeBoundaryTest {

    private final AbiEncoder encoder = new AbiEncoder(32);
    private final AbiDecoder decoder = new AbiDecoder(32);

    private static final AbiType BYTES = new AbiType.DynamicBytes();

    private byte[] enc(AbiType t, Object v) {
        return encoder.encode(List.of(t), List.of(v));
    }

    private CodecException expectDecodeError(AbiType t, byte[] data) {
        return assertThrows(CodecException.class,
                () -> decoder.decode(List.of(t), data));
    }

    // ---------- requirement 7: payload + padding exactly filling the block end ----------

    @Test
    void bytesWithOneWordPaddingExactlyFillsBlockEnd() {
        // 31 payload bytes + 1 padding byte: tail ends exactly at the block end.
        byte[] payload = new byte[31];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i + 1);
        }
        byte[] data = enc(BYTES, payload);
        assertEquals(96, data.length); // offset word + length word + 31 data + 1 pad
        List<Object> out = decoder.decode(List.of(BYTES), data);
        assertArrayEquals(payload, (byte[]) out.get(0));
    }

    @Test
    void bytesWithExactWordPayloadNoPaddingFillsBlockEnd() {
        byte[] payload = new byte[32];
        payload[31] = 0x7F;
        byte[] data = enc(BYTES, payload);
        assertEquals(96, data.length); // offset + length + 32 data, zero padding words
        assertArrayEquals(payload, (byte[]) decoder.decode(List.of(BYTES), data).get(0));
    }

    @Test
    void onePayloadByteShortIsOutOfBoundsNotNonCanonical() {
        byte[] full = enc(BYTES, new byte[32]);
        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);
        CodecException e = expectDecodeError(BYTES, cut);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
    }

    @Test
    void onePaddingByteShortIsOutOfBoundsNotNonCanonical() {
        byte[] full = enc(BYTES, new byte[31]);
        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);
        CodecException e = expectDecodeError(BYTES, cut);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
    }

    @Test
    void nonZeroPaddingIsStillNonCanonicalWhenBlockIsComplete() {
        byte[] full = enc(BYTES, new byte[31]);
        full[full.length - 1] = 0x01; // corrupt the single padding byte
        CodecException e = expectDecodeError(BYTES, full);
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING, e.code());
    }

    @Test
    void bareDynamicValueExactEndAndTruncation() {
        byte[] full = encoder.encodeValue(BYTES, new byte[31]);
        assertEquals(64, full.length); // length word + 31 data + 1 pad
        assertArrayEquals(new byte[31], (byte[]) decoder.decodeValue(BYTES, full));

        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ,
                assertThrows(CodecException.class,
                        () -> decoder.decodeValue(BYTES, cut)).code());
    }

    // ---------- requirement 8: nested data bounded by the enclosing block ----------

    @Test
    void doublyNestedBytesExactlyTouchesOuterBlockEnd() {
        // ((bytes31)): each tuple level contributes an offset word:
        // outer offset + inner offset + length + 31 data + 1 pad = 160.
        AbiType nested = new AbiType.Tuple(List.of(
                new AbiType.Tuple(List.of(BYTES))));
        byte[] data = enc(nested, List.of(List.of(new byte[31])));
        assertEquals(160, data.length);
        List<Object> out = decoder.decode(List.of(nested), data);
        assertArrayEquals(new byte[31],
                (byte[]) ((List<?>) ((List<?>) out.get(0)).get(0)).get(0));
    }

    @Test
    void doublyNestedBytesOneByteShortIsOutOfBounds() {
        AbiType nested = new AbiType.Tuple(List.of(
                new AbiType.Tuple(List.of(BYTES))));
        byte[] full = enc(nested, List.of(List.of(new byte[31])));
        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);
        CodecException e = expectDecodeError(nested, cut);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
        assertEquals("$[0][0][0]", e.path(), "path must point at the innermost bytes value");
    }

    @Test
    void innerBytesCannotReadSiblingDataAsItsOwnPadding() {
        // ((bytes32), bytes1): canonical encoding is 256 bytes:
        //   [outer offsets 32, 64][inner head offset 32][inner length 32][32 payload]
        //   [sibling length 1][0x01 + 31 zero padding]
        // The inner tuple block ends at absolute 192; the sibling tail begins there.
        // Forge only the inner declared length 32 -> 33. The 33rd payload byte would
        // have to come from the sibling tail (its length byte 1); a decoder bounding
        // reads by buffer length would swallow sibling bytes and succeed. The correct
        // result is OUT_OF_BOUNDS_READ at the inner bytes leaf, path $[0][0][0].
        AbiType type = new AbiType.Tuple(List.of(
                new AbiType.Tuple(List.of(BYTES)), BYTES));
        // As a single dynamic parameter the outer tuple is wrapped in an argument block:
        // arg offset(32), outer offsets(32,160-relative), inner head offset 32, then the
        // inner bytes length word 32 at absolute offset 128.
        byte[] data = enc(type, List.of(List.of(new byte[32]), new byte[]{0x01}));
        assertEquals(256, data.length);
        // Inner block [96,192): head offset word then the bytes length word at 128.
        data[159] = 33; // forge inner declared length 32 -> 33

        CodecException e = expectDecodeError(type, data);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code(),
                "reading into the sibling tail must be an out-of-bounds read, got: " + e.getMessage());
        assertEquals("$[0][0][0]", e.path(), "the inner bytes leaf is at fault, not the sibling");
        assertTrue(e.getMessage().contains("offset 160"),
                "message must name the absolute offset: " + e.getMessage());
        assertTrue(e.getMessage().contains("192"),
                "message must name the enclosing block end: " + e.getMessage());
        assertTrue(e.getMessage().contains("256"),
                "message must name the actual input length: " + e.getMessage());
    }

    @Test
    void dynamicArrayOfBytesExactEndAndTruncation() {
        AbiType arr = new AbiType.Array(BYTES);
        List<Object> value = List.of(new byte[31], new byte[1]);
        byte[] full = enc(arr, value);
        // length word + 2 head offsets + two tails (each 64 bytes)
        assertEquals(32 + 64 + 64 + 64 + 32, full.length);
        assertEquals(2, ((List<?>) decoder.decode(List.of(arr), full).get(0)).size());

        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);
        CodecException e = expectDecodeError(arr, cut);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
        assertEquals("$[0][1]", e.path(), "truncation belongs to the second element");
    }

    // ---------- requirement 9: identical truncation shape across nesting levels ----------

    @Test
    void sameTruncationShapeReportsSameCodeAtEveryNestingLevel() {
        record Case(String label, AbiType type, Object value, String path) {}
        List<Case> cases = List.of(
                new Case("flat", BYTES, new byte[31], "$[0]"),
                new Case("tuple",
                        new AbiType.Tuple(List.of(BYTES)),
                        List.of(new byte[31]), "$[0][0]"),
                new Case("nested tuple",
                        new AbiType.Tuple(List.of(new AbiType.Tuple(List.of(BYTES)))),
                        List.of(List.of(new byte[31])), "$[0][0][0]"),
                new Case("fixed array",
                        new AbiType.FixedArray(BYTES, 1),
                        List.of(new byte[31]), "$[0][0]"),
                new Case("dynamic array",
                        new AbiType.Array(BYTES),
                        List.of(new byte[31]), "$[0][0]"));
        for (Case c : cases) {
            byte[] full = enc(c.type(), c.value());
            byte[] cut = new byte[full.length - 1];
            System.arraycopy(full, 0, cut, 0, cut.length);
            CodecException e = expectDecodeError(c.type(), cut);
            assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code(),
                    c.label() + ": identical truncation must be OUT_OF_BOUNDS_READ");
            assertEquals(c.path(), e.path(),
                    c.label() + ": error path must name the actually failing bytes value");
        }
    }

    @Test
    void errorMessageUsesConsistentByteOffsetsAndRemainingBytes() {
        byte[] full = enc(BYTES, new byte[31]);
        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);
        CodecException e = expectDecodeError(BYTES, cut);
        // The read starts at absolute byte 64; 95 bytes are actually available.
        String msg = e.getMessage();
        assertTrue(msg.contains("64"), "message must name the absolute offset: " + msg);
        assertTrue(msg.contains("95"), "message must name the actual input length: " + msg);
    }
}
