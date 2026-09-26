package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.encoding.AbiDecoder;
import com.github.guoyyit.contractabicodecjava.encoding.AbiEncoder;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.util.Hex;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodecRoundTripTest {

    private final AbiEncoder encoder = new AbiEncoder(32);
    private final AbiDecoder decoder = new AbiDecoder(32);

    @Test
    void encodesKnownTransferCalldataArgs() {
        // transfer(address,uint256): selector a9059cbb
        List<AbiType> types = List.of(new AbiType.Address(), new AbiType.UInt(256));
        byte[] out = encoder.encode(types, List.of(
                "0x1122334455667788990011223344556677889900",
                new BigInteger("12345")));
        String hex = Hex.encode(out);
        assertEquals(
                "0000000000000000000000001122334455667788990011223344556677889900"
              + "0000000000000000000000000000000000000000000000000000000000003039",
                hex);
    }

    @Test
    void roundTripsScalars() {
        List<AbiType> types = List.of(
                new AbiType.UInt(256), new AbiType.Int(128), new AbiType.Bool(),
                new AbiType.Address(), new AbiType.FixedBytes(4));
        List<Object> values = List.of(
                new BigInteger("123456789012345678901234567890"),
                BigInteger.valueOf(-42), true,
                Hex.decode("0x00112233445566778899aabbccddeeff00112233"),
                Hex.decode("0xdeadbeef"));
        byte[] encoded = encoder.encode(types, values);
        List<Object> decoded = decoder.decode(types, encoded);
        assertEquals(values.get(0), decoded.get(0));
        assertEquals(values.get(1), decoded.get(1));
        assertEquals(Boolean.TRUE, decoded.get(2));
        assertArrayEquals((byte[]) values.get(3), (byte[]) decoded.get(3));
        assertArrayEquals((byte[]) values.get(4), (byte[]) decoded.get(4));
    }

    @Test
    void roundTripsDynamicBytesAndString() {
        List<AbiType> types = List.of(new AbiType.DynamicBytes(), new AbiType.String());
        List<Object> values = List.of(Hex.decode("0x010203"), "hello, 世界");
        byte[] encoded = encoder.encode(types, values);
        List<Object> decoded = decoder.decode(types, encoded);
        assertArrayEquals((byte[]) values.get(0), (byte[]) decoded.get(0));
        assertEquals(values.get(1), decoded.get(1));
    }

    @Test
    void roundTripsDynamicArrayOfDynamicArrays() {
        AbiType type = new AbiType.Array(new AbiType.Array(new AbiType.UInt(32)));
        List<Object> inner1 = List.of(1, 2, 3);
        List<Object> inner2 = List.of(9);
        List<Object> outer = List.of(inner1, inner2);
        byte[] encoded = encoder.encode(List.of(type), List.of(outer));
        List<Object> decoded = decoder.decode(List.of(type), encoded);
        assertNumbersDeepEqual(outer, decoded.get(0));
    }

    @SuppressWarnings("unchecked")
    private void assertNumbersDeepEqual(Object expected, Object actual) {
        if (expected instanceof List<?> el && actual instanceof List<?> al) {
            assertEquals(el.size(), al.size());
            for (int i = 0; i < el.size(); i++) {
                assertNumbersDeepEqual(el.get(i), al.get(i));
            }
        } else if (expected instanceof Number en && actual instanceof Number an) {
            assertEquals(0, new BigInteger(en.toString()).compareTo(new BigInteger(an.toString())));
        } else {
            assertEquals(expected, actual);
        }
    }

    @Test
    void roundTripsNestedTupleWithDynamics() {
        // (uint256 a, (bytes b, string c) inner, address d)
        AbiType inner = new AbiType.Tuple(List.of(new AbiType.DynamicBytes(), new AbiType.String()));
        AbiType outer = new AbiType.Tuple(List.of(
                new AbiType.UInt(256), inner, new AbiType.Address()));
        List<Object> innerValue = List.of(Hex.decode("0xaabb"), "nested");
        List<Object> value = List.of(
                BigInteger.valueOf(7),
                innerValue,
                Hex.decode("0x1122334455667788990011223344556677889900"));
        byte[] encoded = encoder.encode(List.of(outer), List.of(value));
        List<Object> decoded = decoder.decode(List.of(outer), encoded);
        assertEquals(BigInteger.valueOf(7), ((List<?>) decoded.get(0)).get(0));
        assertEquals("nested", ((List<?>) ((List<?>) decoded.get(0)).get(1)).get(1));
    }

    @Test
    void roundTripsFixedArrayOfDynamicElements() {
        // string[2] is dynamic (element dynamic), but length is fixed.
        AbiType type = new AbiType.FixedArray(new AbiType.String(), 2);
        List<Object> value = List.of("a", "bb");
        byte[] encoded = encoder.encode(List.of(type), List.of(value));
        List<Object> decoded = decoder.decode(List.of(type), encoded);
        assertEquals(value, decoded.get(0));
    }

    @Test
    void roundTripsStaticNestedTupleArray() {
        // (uint64,bool)[2] static inside an outer tuple with a dynamic member.
        AbiType pair = new AbiType.Tuple(List.of(new AbiType.UInt(64), new AbiType.Bool()));
        AbiType arr = new AbiType.FixedArray(pair, 2);
        AbiType outer = new AbiType.Tuple(List.of(arr, new AbiType.DynamicBytes()));
        List<Object> pairs = List.of(List.of(1L, true), List.of(2L, false));
        List<Object> value = List.of(pairs, Hex.decode("0xff"));
        byte[] encoded = encoder.encode(List.of(outer), List.of(value));
        List<Object> decoded = decoder.decode(List.of(outer), encoded);
        List<?> outerList = (List<?>) decoded.get(0);
        assertEquals(BigInteger.ONE, ((List<?>) ((List<?>) outerList.get(0)).get(0)).get(0));
        assertArrayEquals(new byte[]{(byte) 0xff}, (byte[]) outerList.get(1));
    }
}
