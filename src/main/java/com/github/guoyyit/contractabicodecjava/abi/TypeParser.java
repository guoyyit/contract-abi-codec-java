package com.github.guoyyit.contractabicodecjava.abi;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析规范类型字符串（如 "uint256"、"bytes32"、"(uint256,bytes)[]"）为 {@link AbiType}。
 * 只接受规范形式：uint/int 必须带 8..256 的 8 倍数位宽（不允许裸 "uint"/"int"），
 * bytesN 长度必须在 1..32，数组长度必须为正整数，拒绝空白与未知类型。
 */
public final class TypeParser {

    private TypeParser() {}

    public static AbiType parse(String type) {
        if (type == null) {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "type string is null");
        }
        Parser p = new Parser(type);
        AbiType t = p.parseType();
        if (p.pos != type.length()) {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                    "unexpected trailing characters in type: " + type);
        }
        return t;
    }

    /** 解析逗号分隔的类型列表，如 "uint256,(bytes,uint8)[]"。 */
    public static List<AbiType> parseList(String types) {
        if (types == null || types.isBlank()) {
            return List.of();
        }
        List<AbiType> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < types.length(); i++) {
            char c = types.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(parse(types.substring(start, i)));
                start = i + 1;
            }
            if (depth < 0) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "unbalanced parens: " + types);
            }
        }
        if (depth != 0) {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "unbalanced parens: " + types);
        }
        out.add(parse(types.substring(start)));
        return out;
    }

    /** 返回类型的规范字符串形式（用于选择器计算）。 */
    public static String canonical(AbiType type) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(type, sb);
        return sb.toString();
    }

    private static void writeCanonical(AbiType t, StringBuilder sb) {
        if (t instanceof AbiType.UIntType u) {
            sb.append("uint").append(u.bits());
        } else if (t instanceof AbiType.IntType i) {
            sb.append("int").append(i.bits());
        } else if (t instanceof AbiType.BoolType) {
            sb.append("bool");
        } else if (t instanceof AbiType.AddressType) {
            sb.append("address");
        } else if (t instanceof AbiType.FixedBytesType f) {
            sb.append("bytes").append(f.size());
        } else if (t instanceof AbiType.DynamicBytesType) {
            sb.append("bytes");
        } else if (t instanceof AbiType.StringType) {
            sb.append("string");
        } else if (t instanceof AbiType.StaticArrayType a) {
            writeCanonical(a.elementType(), sb);
            sb.append('[').append(a.length()).append(']');
        } else if (t instanceof AbiType.DynamicArrayType a) {
            writeCanonical(a.elementType(), sb);
            sb.append("[]");
        } else if (t instanceof AbiType.TupleType tuple) {
            sb.append('(');
            for (int i = 0; i < tuple.components().size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(tuple.components().get(i), sb);
            }
            sb.append(')');
        } else {
            throw new IllegalArgumentException("unknown AbiType: " + t);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        AbiType parseType() {
            AbiType base = parseBase();
            // 数组后缀，可能多级：T[2][]...
            while (pos < s.length() && s.charAt(pos) == '[') {
                pos++;
                int start = pos;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
                String num = s.substring(start, pos);
                if (pos >= s.length() || s.charAt(pos) != ']') {
                    throw err("missing ']' in array type: " + s);
                }
                pos++;
                if (num.isEmpty()) {
                    base = new AbiType.DynamicArrayType(base);
                } else {
                    int len;
                    try {
                        len = Integer.parseInt(num);
                    } catch (NumberFormatException e) {
                        throw err("array length overflow in: " + s);
                    }
                    if (len <= 0) {
                        throw err("static array length must be positive: " + s);
                    }
                    base = new AbiType.StaticArrayType(base, len);
                }
            }
            return base;
        }

        private AbiType parseBase() {
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                List<AbiType> components = new ArrayList<>();
                if (pos < s.length() && s.charAt(pos) == ')') {
                    throw err("empty tuple not allowed: " + s);
                }
                while (true) {
                    components.add(parseType());
                    if (pos >= s.length()) {
                        throw err("unclosed tuple: " + s);
                    }
                    char c = s.charAt(pos);
                    if (c == ',') {
                        pos++;
                        continue;
                    }
                    if (c == ')') {
                        pos++;
                        break;
                    }
                    throw err("expected ',' or ')' in tuple: " + s);
                }
                return new AbiType.TupleType(List.copyOf(components));
            }
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)))) pos++;
            String word = s.substring(start, pos);
            return switch (word) {
                case "bool" -> new AbiType.BoolType();
                case "address" -> new AbiType.AddressType();
                case "bytes" -> new AbiType.DynamicBytesType();
                case "string" -> new AbiType.StringType();
                case "uint", "int" ->
                        throw err("bare '" + word + "' is not canonical, use explicit bit width: " + s);
                default -> {
                    if (word.startsWith("uint")) {
                        yield new AbiType.UIntType(parseBits(word.substring(4), s));
                    }
                    if (word.startsWith("int")) {
                        yield new AbiType.IntType(parseBits(word.substring(3), s));
                    }
                    if (word.startsWith("bytes")) {
                        int size = parseFixedSize(word.substring(5), s);
                        yield new AbiType.FixedBytesType(size);
                    }
                    throw err("unknown type: " + word);
                }
            };
        }

        private static int parseBits(String digits, String whole) {
            if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
                throw err("invalid bit width in: " + whole);
            }
            int bits;
            try {
                bits = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                throw err("bit width overflow in: " + whole);
            }
            if (bits < 8 || bits > 256 || bits % 8 != 0) {
                throw err("bit width must be multiple of 8 in [8,256]: " + whole);
            }
            return bits;
        }

        private static int parseFixedSize(String digits, String whole) {
            if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
                throw err("invalid fixed bytes size in: " + whole);
            }
            int size;
            try {
                size = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                throw err("fixed bytes size overflow in: " + whole);
            }
            if (size < 1 || size > 32) {
                throw err("fixed bytes size must be in [1,32]: " + whole);
            }
            return size;
        }

        private static AbiException err(String msg) {
            return new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, msg);
        }
    }
}
