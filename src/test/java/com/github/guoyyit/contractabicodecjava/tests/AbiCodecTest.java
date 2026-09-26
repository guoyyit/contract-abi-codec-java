package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.abi.AbiCodec;
import com.github.guoyyit.contractabicodecjava.abi.AbiException;
import com.github.guoyyit.contractabicodecjava.abi.AbiType;
import com.github.guoyyit.contractabicodecjava.abi.TypeParser;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbiCodecTest {

    private final AbiCodec codec = new AbiCodec(32);

    private static AbiType t(String s) {
        return TypeParser.parse(s);
    }

    private static byte[] word(int v) {
        byte[] w = new byte[32];
        w[31] = (byte) v;
        return w;
    }

    // ------------------------------------------------------------ 嵌套动态类型

    @Test
    void nestedDynamicRoundTrip() {
        // (string, (uint256, bytes)[], bytes32)
        List<AbiType> types = List.of(
                t("string"),
                t("(uint256,bytes)[]"),
                t("bytes32"));
        byte[] b32 = new byte[32];
        Arrays.fill(b32, (byte) 0xAB);
        List<Object> values = List.of(
                "héllo",
                List.of(
                        List.of(BigInteger.ONE, new byte[]{1}),
                        List.of(BigInteger.TWO, new byte[]{2, 3})),
                b32);
        byte[] enc = codec.encodeTuple(types, values);
        List<Object> dec = codec.decodeTuple(types, enc);
        assertEquals("héllo", dec.get(0));
        @SuppressWarnings("unchecked")
        List<List<Object>> arr = (List<List<Object>>) dec.get(1);
        assertEquals(2, arr.size());
        assertEquals(BigInteger.ONE, arr.get(0).get(0));
        assertArrayEquals(new byte[]{1}, (byte[]) arr.get(0).get(1));
        assertArrayEquals(new byte[]{2, 3}, (byte[]) arr.get(1).get(1));
        assertArrayEquals(b32, (byte[]) dec.get(2));
    }

    @Test
    void deeplyNestedArraysRoundTrip() {
        // uint256[][] 与 string[2][]
        List<AbiType> types = List.of(t("uint256[][]"), t("string[2][]"));
        List<Object> values = List.of(
                List.of(List.of(BigInteger.ONE, BigInteger.TWO), List.of(), List.of(BigInteger.TEN)),
                List.of(List.of("a", "b"), List.of("c", "d")));
        byte[] enc = codec.encodeTuple(types, values);
        List<Object> dec = codec.decodeTuple(types, enc);
        assertEquals(values, dec);
    }

    @Test
    void staticArrayOfDynamicTupleRoundTrip() {
        List<AbiType> types = List.of(t("(bytes,int8)[2]"), t("int8"));
        List<Object> values = List.of(
                List.of(List.of(new byte[]{9}, 7), List.of(new byte[]{8, 8}, -5)),
                -128);
        byte[] enc = codec.encodeTuple(types, values);
        List<Object> dec = codec.decodeTuple(types, enc);
        @SuppressWarnings("unchecked")
        List<List<Object>> arr = (List<List<Object>>) dec.get(0);
        assertArrayEquals(new byte[]{9}, (byte[]) arr.get(0).get(0));
        assertEquals(BigInteger.valueOf(7), arr.get(0).get(1));
        assertArrayEquals(new byte[]{8, 8}, (byte[]) arr.get(1).get(0));
        assertEquals(BigInteger.valueOf(-5), arr.get(1).get(1));
        assertEquals(BigInteger.valueOf(-128), dec.get(1));
    }

    @Test
    void emptyDynamicValues() {
        List<AbiType> types = List.of(t("bytes"), t("uint256[]"), t("string"));
        List<Object> values = List.of(new byte[0], List.of(), "");
        byte[] enc = codec.encodeTuple(types, values);
        List<Object> dec = codec.decodeTuple(types, enc);
        assertArrayEquals(new byte[0], (byte[]) dec.get(0));
        assertEquals(List.of(), dec.get(1));
        assertEquals("", dec.get(2));
    }

    // ------------------------------------------------------------ 非规范拒绝

    @Test
    void rejectsNonZeroPaddingInFixedBytes() {
        List<AbiType> types = List.of(t("bytes4"));
        byte[] enc = codec.encodeTuple(types, List.of(new byte[]{1, 2, 3, 4}));
        enc[31] = 1; // 尾部填充必须为零
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void rejectsNonCanonicalOffset() {
        List<AbiType> types = List.of(t("bytes"));
        byte[] enc = codec.encodeTuple(types, List.of(new byte[]{1, 2}));
        // 偏移从 0x20 改成 0x40（非最小尾部位置）
        enc[31] = 0x40;
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void rejectsUnalignedOffset() {
        List<AbiType> types = List.of(t("bytes"));
        byte[] enc = codec.encodeTuple(types, List.of(new byte[]{1, 2}));
        enc[31] = 0x21; // 非 32 对齐
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void rejectsShortLengthPrefix() {
        // 长度前缀（索引 32..63）改短后，被截断区域落入填充区：非零则 NON_CANONICAL
        List<AbiType> types = List.of(t("bytes"));
        byte[] enc = codec.encodeTuple(types, List.of(new byte[]{1, 2}));
        enc[63] = 1;
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());

        // 33 字节数据声称长度 32：消费不完整个缓冲区 -> TRAILING_BYTES
        byte[] data33 = new byte[33];
        data33[32] = 7;
        byte[] enc2 = codec.encodeTuple(types, List.of(data33));
        enc2[63] = 32;
        AbiException e2 = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc2));
        assertEquals(AbiException.ErrorCode.TRAILING_BYTES, e2.code());
    }

    @Test
    void rejectsUintHighBitsSet() {
        List<AbiType> types = List.of(t("uint8"));
        byte[] enc = word(0);
        enc[30] = 1; // 值 256 超出 uint8
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void rejectsBadSignExtension() {
        List<AbiType> types = List.of(t("int8"));
        byte[] enc = new byte[32];
        enc[31] = (byte) 0xFF; // 256 位下是 +255，不是 int8 的 -1（-1 应全 0xFF 填充）
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void acceptsProperNegativeInt() {
        List<AbiType> types = List.of(t("int8"));
        byte[] enc = codec.encodeTuple(types, List.of(-1));
        assertEquals(BigInteger.valueOf(-1), codec.decodeTuple(types, enc).get(0));
    }

    @Test
    void rejectsNonBinaryBool() {
        List<AbiType> types = List.of(t("bool"));
        byte[] enc = word(2);
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void rejectsAddressHighBytes() {
        List<AbiType> types = List.of(t("address"));
        byte[] enc = new byte[32];
        enc[11] = 1; // 高 12 字节必须为零
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.NON_CANONICAL, e.code());
    }

    @Test
    void rejectsTrailingBytes() {
        List<AbiType> types = List.of(t("uint256"));
        byte[] enc = codec.encodeTuple(types, List.of(BigInteger.ONE));
        byte[] longer = Arrays.copyOf(enc, enc.length + 32);
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, longer));
        assertEquals(AbiException.ErrorCode.TRAILING_BYTES, e.code());
    }

    @Test
    void rejectsTruncatedInput() {
        List<AbiType> types = List.of(t("uint256"), t("bytes"));
        byte[] enc = codec.encodeTuple(types, List.of(BigInteger.ONE, new byte[]{1}));
        byte[] cut = Arrays.copyOf(enc, enc.length - 16);
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, cut));
        assertEquals(AbiException.ErrorCode.OUT_OF_BOUNDS, e.code());
    }

    @Test
    void rejectsHugeLengthPrefix() {
        List<AbiType> types = List.of(t("uint256[]"));
        byte[] enc = new byte[64];
        enc[31] = 0x20; // offset
        enc[62] = 0x10; // 长度 = 2^68，远超缓冲区
        enc[63] = 0x00;
        // 手工构造 length = 1<<40
        byte[] lenWord = new byte[32];
        lenWord[26] = 1; // 2^(8*5)=2^40
        System.arraycopy(lenWord, 0, enc, 32, 32);
        AbiException e = assertThrows(AbiException.class, () -> codec.decodeTuple(types, enc));
        assertEquals(AbiException.ErrorCode.OUT_OF_BOUNDS, e.code());
    }

    // ------------------------------------------------------------ 编码侧错误

    @Test
    void encodeRejectsOverflow() {
        List<AbiType> types = List.of(t("uint8"));
        AbiException e = assertThrows(AbiException.class,
                () -> codec.encodeTuple(types, List.of(BigInteger.valueOf(256))));
        assertEquals(AbiException.ErrorCode.INTEGER_OVERFLOW, e.code());
    }

    @Test
    void encodeRejectsNegativeUint() {
        List<AbiType> types = List.of(t("uint256"));
        AbiException e = assertThrows(AbiException.class,
                () -> codec.encodeTuple(types, List.of(BigInteger.valueOf(-1))));
        assertEquals(AbiException.ErrorCode.INTEGER_OVERFLOW, e.code());
    }

    @Test
    void encodeRejectsTruncation() {
        List<AbiType> types = List.of(t("bytes2"));
        AbiException e = assertThrows(AbiException.class,
                () -> codec.encodeTuple(types, List.of(new byte[]{1, 2, 3})));
        assertEquals(AbiException.ErrorCode.TRUNCATION, e.code());
    }

    @Test
    void encodeRejectsTypeMismatch() {
        List<AbiType> types = List.of(t("uint256"));
        AbiException e = assertThrows(AbiException.class,
                () -> codec.encodeTuple(types, List.of(true)));
        assertEquals(AbiException.ErrorCode.TYPE_MISMATCH, e.code());
    }

    @Test
    void encodeRejectsArityMismatch() {
        List<AbiType> types = List.of(t("uint256"), t("bool"));
        AbiException e = assertThrows(AbiException.class,
                () -> codec.encodeTuple(types, List.of(BigInteger.ONE)));
        assertEquals(AbiException.ErrorCode.TYPE_MISMATCH, e.code());
    }

    // ------------------------------------------------------------ 类型解析

    @Test
    void parserRejectsBareUint() {
        AbiException e = assertThrows(AbiException.class, () -> TypeParser.parse("uint"));
        assertEquals(AbiException.ErrorCode.TYPE_MISMATCH, e.code());
    }

    @Test
    void parserRejectsBadBitWidth() {
        assertThrows(AbiException.class, () -> TypeParser.parse("uint7"));
        assertThrows(AbiException.class, () -> TypeParser.parse("uint264"));
        assertThrows(AbiException.class, () -> TypeParser.parse("bytes33"));
        assertThrows(AbiException.class, () -> TypeParser.parse("bytes0"));
        assertThrows(AbiException.class, () -> TypeParser.parse("uint256[0]"));
        assertThrows(AbiException.class, () -> TypeParser.parse("()"));
    }

    @Test
    void canonicalFormRoundTrip() {
        String[] cases = {"uint256", "bytes32", "(uint256,bytes)[]", "(address,(uint8,bool)[3])[2]",
                "string", "int128", "bytes"};
        for (String c : cases) {
            assertEquals(c, TypeParser.canonical(TypeParser.parse(c)));
        }
    }

    @Test
    void depthLimitEnforced() {
        AbiCodec shallow = new AbiCodec(3);
        // 4 层嵌套数组
        List<AbiType> types = List.of(t("uint256[][][][]"));
        List<Object> v = List.of(List.of(List.of(List.of(List.of(BigInteger.ONE)))));
        AbiException e = assertThrows(AbiException.class, () -> shallow.encodeTuple(types, v));
        assertEquals(AbiException.ErrorCode.DEPTH_LIMIT_EXCEEDED, e.code());
    }
}
