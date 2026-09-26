package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses canonical ABI type names back into {@link AbiType}.
 * Supports uintN/intN (8..256, step 8), address, bool, bytesN, bytes, string,
 * T[k]/T[] arrays (multi-dimensional) and nested tuples with parentheses.
 */
public final class AbiTypeParser {

    private final String s;
    private int pos;

    private AbiTypeParser(String s) {
        this.s = s;
        this.pos = 0;
    }

    public static AbiType parse(String canonical) {
        if (canonical == null || canonical.isEmpty()) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE, "empty type");
        }
        AbiTypeParser p = new AbiTypeParser(canonical);
        try {
            AbiType t = p.parseType();
            if (p.pos != canonical.length()) {
                throw new CodecException(ErrorCode.INVALID_SIGNATURE,
                        "unexpected trailing characters in type: " + canonical);
            }
            return t;
        } catch (CodecException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE,
                    "invalid type '" + canonical + "': " + e.getMessage());
        }
    }

    private AbiType parseType() {
        AbiType base;
        if (peek() == '(') {
            base = parseTuple();
        } else {
            base = parseElementary();
        }
        while (peek() == '[') {
            expect('[');
            if (peek() == ']') {
                expect(']');
                base = new AbiType.Array(base);
            } else {
                int len = parseNumber();
                expect(']');
                base = new AbiType.FixedArray(base, len);
            }
        }
        return base;
    }

    private AbiType parseTuple() {
        expect('(');
        List<AbiType> components = new ArrayList<>();
        if (peek() != ')') {
            components.add(parseType());
            while (peek() == ',') {
                expect(',');
                components.add(parseType());
            }
        }
        expect(')');
        return new AbiType.Tuple(components);
    }

    private AbiType parseElementary() {
        int start = pos;
        while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
            pos++;
        }
        String word = s.substring(start, pos);
        int num = -1;
        if (pos < s.length() && Character.isDigit(s.charAt(pos)) && !word.equals("address")) {
            int nStart = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            num = Integer.parseInt(s.substring(nStart, pos));
        }
        switch (word) {
            case "uint": {
                int bits = num < 0 ? 256 : num;
                return new AbiType.UInt(bits);
            }
            case "int": {
                int bits = num < 0 ? 256 : num;
                return new AbiType.Int(bits);
            }
            case "address":
                return new AbiType.Address();
            case "bool":
                return new AbiType.Bool();
            case "bytes":
                if (num < 0) {
                    return new AbiType.DynamicBytes();
                }
                return new AbiType.FixedBytes(num);
            case "string":
                return new AbiType.String();
            default:
                throw new CodecException(ErrorCode.INVALID_SIGNATURE, "unknown type: " + word);
        }
    }

    private int parseNumber() {
        int start = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE,
                    "expected number at position " + pos + " in: " + s);
        }
        return Integer.parseInt(s.substring(start, pos));
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (pos >= s.length() || s.charAt(pos) != c) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE,
                    "expected '" + c + "' at position " + pos + " in: " + s);
        }
        pos++;
    }
}
