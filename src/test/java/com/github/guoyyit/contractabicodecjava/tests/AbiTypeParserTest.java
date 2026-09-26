package com.github.guoyyit.contractabicodecjava.tests;

import com.github.guoyyit.contractabicodecjava.encoding.AbiTypeParser;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbiTypeParserTest {

    @Test
    void parsesElementaryTypes() {
        assertEquals("uint256", AbiTypeParser.parse("uint256").canonicalName());
        assertEquals("uint256", AbiTypeParser.parse("uint").canonicalName());
        assertEquals("int128", AbiTypeParser.parse("int128").canonicalName());
        assertEquals("address", AbiTypeParser.parse("address").canonicalName());
        assertEquals("bool", AbiTypeParser.parse("bool").canonicalName());
        assertEquals("bytes32", AbiTypeParser.parse("bytes32").canonicalName());
        assertEquals("bytes", AbiTypeParser.parse("bytes").canonicalName());
        assertEquals("string", AbiTypeParser.parse("string").canonicalName());
    }

    @Test
    void parsesNestedArraysAndTuples() {
        AbiType t = AbiTypeParser.parse("(bytes,uint32[],bool[2])");
        assertTrue(t instanceof AbiType.Tuple);
        assertEquals("(bytes,uint32[],bool[2])", t.canonicalName());
        AbiType matrix = AbiTypeParser.parse("uint256[3][]");
        assertEquals("uint256[3][]", matrix.canonicalName());
        assertTrue(matrix.isDynamic());
    }

    @Test
    void rejectsInvalidTypes() {
        assertEquals(ErrorCode.INVALID_SIGNATURE,
                assertThrows(CodecException.class, () -> AbiTypeParser.parse("uint7")).code());
        assertEquals(ErrorCode.INVALID_SIGNATURE,
                assertThrows(CodecException.class, () -> AbiTypeParser.parse("bytes33")).code());
        assertEquals(ErrorCode.INVALID_SIGNATURE,
                assertThrows(CodecException.class, () -> AbiTypeParser.parse("uint256[")).code());
        assertEquals(ErrorCode.INVALID_SIGNATURE,
                assertThrows(CodecException.class, () -> AbiTypeParser.parse("uint256 extra")).code());
    }

    @Test
    void roundTripsCanonicalNames() {
        for (String name : new String[]{"address", "uint8", "(uint256,bytes)[]", "((bool,address)[2],string)"}) {
            assertEquals(name, AbiTypeParser.parse(name).canonicalName());
        }
    }
}
