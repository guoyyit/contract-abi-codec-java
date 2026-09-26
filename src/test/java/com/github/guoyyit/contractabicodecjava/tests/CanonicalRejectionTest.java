package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.util.Hex;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalRejectionTest {

    private final AbiEncoder encoder = new AbiEncoder(32);
    private final AbiDecoder decoder = new AbiDecoder(32);

    private CodecException decodeOne(AbiType type, String hex) {
        return assertThrows(CodecException.class,
                () -> decoder.decode(List.of(type), Hex.decode(hex)));
    }

    private CodecException decodeBare(AbiType type, String hex) {
        return assertThrows(CodecException.class,
                () -> decoder.decodeValue(type, Hex.decode(hex)));
    }

    // ---------- encode-side canonical value checks ----------

    @Test
    void rejectsNegativeUnsigned() {
        CodecException e = assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.UInt(256)), List.of(-1)));
        assertEquals(ErrorCode.INTEGER_OVERFLOW, e.code());
    }

    @Test
    void rejectsUnsignedOverflow() {
        CodecException e = assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.UInt(8)),
                        List.of(BigInteger.valueOf(256))));
        assertEquals(ErrorCode.INTEGER_OVERFLOW, e.code());
    }

    @Test
    void rejectsSignedOverflowAndUnderflow() {
        assertEquals(ErrorCode.INTEGER_OVERFLOW, assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.Int(8)), List.of(128))).code());
        assertEquals(ErrorCode.INTEGER_OVERFLOW, assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.Int(8)), List.of(-129))).code());
    }

    @Test
    void rejectsWrongLengthFixedBytes() {
        assertEquals(ErrorCode.TYPE_MISMATCH, assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.FixedBytes(4)),
                        List.of(new byte[3]))).code());
        assertEquals(ErrorCode.TYPE_MISMATCH, assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.Address()),
                        List.of(new byte[19]))).code());
    }

    @Test
    void rejectsNonBooleanForBool() {
        assertEquals(ErrorCode.TYPE_MISMATCH, assertThrows(CodecException.class,
                () -> encoder.encode(List.of(new AbiType.Bool()), List.of(1))).code());
    }

    @Test
    void rejectsWrongFixedArrayLength() {
        AbiType t = new AbiType.FixedArray(new AbiType.UInt(256), 2);
        assertEquals(ErrorCode.TYPE_MISMATCH, assertThrows(CodecException.class,
                () -> encoder.encode(List.of(t), List.of(List.of(1)))).code());
    }

    // ---------- decode-side strict canonical checks ----------

    @Test
    void rejectsTrailingBytes() {
        // valid uint256 word plus one extra byte
        String word = "00".repeat(31) + "01";
        CodecException e = decodeOne(new AbiType.UInt(256), word + "00");
        assertEquals(ErrorCode.TRAILING_BYTES, e.code());
    }

    @Test
    void rejectsOutOfBoundsReadOnOffset() {
        // bare bytes return: length word declares payload but it is absent
        String data = "00".repeat(31) + "01";
        CodecException e = decodeBare(new AbiType.DynamicBytes(), data);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ, e.code());
    }

    @Test
    void rejectsNonCanonicalTailZeroInUint32() {
        // uint32 must live in the low 4 bytes; high bytes must be zero
        String nonCanonical = "00".repeat(27) + "01" + "00".repeat(4);
        CodecException e = decodeOne(new AbiType.UInt(32), nonCanonical);
        assertEquals(ErrorCode.INTEGER_TRUNCATION, e.code());
    }

    @Test
    void rejectsNonCanonicalBoolWord() {
        String bad = "00".repeat(31) + "02";
        assertEquals(ErrorCode.TYPE_MISMATCH,
                decodeOne(new AbiType.Bool(), bad).code());
        String nonzeroPad = "00".repeat(30) + "0100";
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING,
                decodeOne(new AbiType.Bool(), nonzeroPad).code());
    }

    @Test
    void rejectsNonZeroAddressPadding() {
        String bad = "01" + "00".repeat(11) + "11".repeat(20);
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING,
                decodeOne(new AbiType.Address(), bad).code());
    }

    @Test
    void rejectsNonZeroFixedBytesPadding() {
        String bad = "deadbeef" + "00".repeat(27) + "01";
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING,
                decodeOne(new AbiType.FixedBytes(4), bad).code());
    }

    @Test
    void rejectsNonCanonicalDynamicPadding() {
        // bytes len=3, payload 010203 but padding byte 0x01 instead of zero
        String good = "00".repeat(31) + "03" + "010203" + "00".repeat(29);
        decoder.decodeValue(new AbiType.DynamicBytes(), Hex.decode(good));
        String bad = "00".repeat(31) + "03" + "010203" + "01" + "00".repeat(28);
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING,
                decodeBare(new AbiType.DynamicBytes(), bad).code());
    }

    @Test
    void rejectsShortLengthEncodingForBytes() {
        // declares length 4 but provides only 3 payload bytes
        String bad = "00".repeat(31) + "04" + "010203" + "00".repeat(28);
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ,
                decodeBare(new AbiType.DynamicBytes(), bad).code());
    }

    @Test
    void rejectsArrayLengthMismatchAndTruncation() {
        // uint256[] block: head offset=32, length=2, only one element present
        String bad = "00".repeat(31) + "20" + "00".repeat(31) + "02"
                + "00".repeat(31) + "01";
        assertEquals(ErrorCode.OUT_OF_BOUNDS_READ,
                decodeOne(new AbiType.Array(new AbiType.UInt(256)), bad).code());
    }

    @Test
    void rejectsNonSequentialDynamicOffsets() {
        // (bytes,bytes): canonical has both tails sequential after 64 bytes of heads.
        // Corrupt second offset to 33 (inside heads/overlapping).
        String firstOffset = "00".repeat(31) + "20"; // 32
        String secondOffset = "00".repeat(31) + "21"; // 33 -> invalid
        String len = "00".repeat(31) + "00";
        String bad = firstOffset + secondOffset + len;
        CodecException e = assertThrows(CodecException.class, () -> decoder.decode(
                List.of(new AbiType.DynamicBytes(), new AbiType.DynamicBytes()),
                Hex.decode(bad)));
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING, e.code());
    }

    @Test
    void rejectsGapBetweenTails() {
        // (bytes,bytes): second tail offset skips one word past first tail end.
        String firstOffset = "00".repeat(31) + "20"; // 32
        String secondOffset = "00".repeat(30) + "0060"; // 96 instead of 64 -> gap
        String empty = "00".repeat(32);
        String bad = firstOffset + secondOffset + empty + empty;
        CodecException e = assertThrows(CodecException.class, () -> decoder.decode(
                List.of(new AbiType.DynamicBytes(), new AbiType.DynamicBytes()),
                Hex.decode(bad)));
        assertEquals(ErrorCode.NON_CANONICAL_ENCODING, e.code());
    }

    @Test
    void rejectsInvalidUtf8ForString() {
        // bytes 0xFF 0xFE is not valid UTF-8.
        String bad = "00".repeat(31) + "02" + "fffe" + "00".repeat(30);
        assertEquals(ErrorCode.TYPE_MISMATCH,
                decodeBare(new AbiType.String(), bad).code());
    }

    @Test
    void rejectsNonCanonicalNegativeIntPadding() {
        // int8 encoded as -1 but padding uses 0x00 instead of 0xFF
        String bad = "00".repeat(31) + "ff";
        assertEquals(ErrorCode.INTEGER_TRUNCATION,
                decodeOne(new AbiType.Int(8), bad).code());
        // canonical -1 decodes fine
        String good = "ff".repeat(32);
        assertEquals(BigInteger.valueOf(-1),
                decoder.decode(List.of(new AbiType.Int(8)), Hex.decode(good)).get(0));
    }
}
