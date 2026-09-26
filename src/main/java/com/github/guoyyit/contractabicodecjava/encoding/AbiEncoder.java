package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.util.Hex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Canonical ABI encoder.
 *
 * <p>Each value is accepted in exactly one canonical form:
 * <ul>
 *   <li>uint/int: integer numbers (or decimal strings) within the declared range —
 *       negative values are rejected for unsigned types;</li>
 *   <li>address: 20 bytes (0x-hex string or byte[20]);</li>
 *   <li>bool: a Java Boolean (numbers are rejected);</li>
 *   <li>bytesN: a byte array of exactly N bytes;</li>
 *   <li>bytes/string: byte array (hex string) / java.lang.String;</li>
 *   <li>arrays: java.util.List / Object[]; tuples: positional List.</li>
 * </ul>
 * Overflow and truncation are rejected rather than silently masked.
 */
public final class AbiEncoder {

    private static final int WORD = 32;
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private final int maxDepth;

    public AbiEncoder(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /** ABI "head+tail" encoding of the values, without selector. */
    public byte[] encode(List<AbiType> types, List<Object> values) {
        if (types == null || values == null) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "types and values are required");
        }
        if (types.size() != values.size()) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH,
                    "arity mismatch: expected " + types.size() + " values, got " + values.size());
        }
        try {
            return encodeBlock(types, values, 0, "$");
        } catch (CodecException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "encoding failed: " + e.getMessage(), e);
        }
    }

    /** Encodes a single bare value (e.g. a {@code bytes} return value), no wrapping block. */
    public byte[] encodeValue(AbiType type, Object value) {
        try {
            return encodeValueInternal(type, value, 0, "$[0]");
        } catch (CodecException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "encoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encodes a tuple / array-element block. Offsets are relative to the start of THIS
     * block, so nested dynamic blocks are self-contained.
     */
    private byte[] encodeBlock(List<AbiType> types, List<Object> values, int depth, String path) {
        if (depth > maxDepth) {
            throw new CodecException(ErrorCode.DEPTH_EXCEEDED, path,
                    "nesting depth " + depth + " exceeds limit " + maxDepth);
        }
        int n = types.size();
        List<Integer> dynamicIndexes = new ArrayList<>();
        int headSize = 0;
        List<byte[]> dynamicBlocks = new ArrayList<>();

        // 1) Encode every static head inline and every dynamic value into its own block.
        for (int i = 0; i < n; i++) {
            AbiType t = types.get(i);
            String childPath = path + "[" + i + "]";
            if (t.isDynamic()) {
                dynamicIndexes.add(i);
                headSize += WORD;
                dynamicBlocks.add(encodeValueInternal(t, values.get(i), depth + 1, childPath));
            } else {
                byte[] block = encodeValueInternal(t, values.get(i), depth + 1, childPath);
                if (block.length != staticSize(t)) {
                    throw new CodecException(ErrorCode.LAYOUT_INVALID, childPath,
                            "static block size " + block.length + " != " + staticSize(t));
                }
                headSize += block.length;
            }
        }

        // 2) Assemble heads; offsets are relative to this block's first byte.
        byte[] out = new byte[headSize + dynamicBlocks.stream().mapToInt(b -> b.length).sum()];
        int headPos = 0;
        int tailPos = headSize;
        int dynIdx = 0;
        for (int i = 0; i < n; i++) {
            AbiType t = types.get(i);
            if (t.isDynamic()) {
                writeUint(out, headPos, BigInteger.valueOf(tailPos), WORD);
                byte[] block = dynamicBlocks.get(dynIdx++);
                System.arraycopy(block, 0, out, tailPos, block.length);
                tailPos += block.length;
                headPos += WORD;
            } else {
                byte[] block = encodeValueInternal(t, values.get(i), depth + 1, path + "[" + i + "]");
                System.arraycopy(block, 0, out, headPos, block.length);
                headPos += block.length;
            }
        }
        return out;
    }

    private byte[] encodeValueInternal(AbiType type, Object value, int depth, String path) {
        if (depth > maxDepth) {
            throw new CodecException(ErrorCode.DEPTH_EXCEEDED, path,
                    "nesting depth " + depth + " exceeds limit " + maxDepth);
        }
        if (type instanceof AbiType.UInt u) return encodeInt(value, u.bits(), false, path);
        if (type instanceof AbiType.Int i) return encodeInt(value, i.bits(), true, path);
        if (type instanceof AbiType.Address) return encodeAddress(value, path);
        if (type instanceof AbiType.Bool) return encodeBool(value, path);
        if (type instanceof AbiType.FixedBytes fb) return encodeFixedBytes(value, fb.length(), path);
        if (type instanceof AbiType.DynamicBytes) return encodeBytes(value, path);
        if (type instanceof AbiType.String) return encodeString(value, path);
        if (type instanceof AbiType.Tuple tup) {
            return encodeBlock(tup.components(), asList(value, path), depth, path);
        }
        if (type instanceof AbiType.Array arr) {
            return encodeDynamicArray(arr.element(), asList(value, path), depth, path);
        }
        if (type instanceof AbiType.FixedArray fa) {
            return encodeFixedArray(fa, asList(value, path), depth, path);
        }
        throw new CodecException(ErrorCode.LAYOUT_INVALID, path, "unknown type: " + type);
    }

    private byte[] encodeDynamicArray(AbiType element, List<Object> values, int depth, String path) {
        byte[] body = encodeArrayBody(element, values, depth, path);
        byte[] out = new byte[WORD + body.length];
        writeUint(out, 0, BigInteger.valueOf(values.size()), WORD);
        System.arraycopy(body, 0, out, WORD, body.length);
        return out;
    }

    private byte[] encodeFixedArray(AbiType.FixedArray fa, List<Object> values, int depth, String path) {
        if (values.size() != fa.length()) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                    "fixed array length " + values.size() + " != declared " + fa.length());
        }
        return encodeArrayBody(fa.element(), values, depth, path);
    }

    /** Encodes array elements as one self-contained block (no length word). */
    private byte[] encodeArrayBody(AbiType element, List<Object> values, int depth, String path) {
        List<AbiType> types = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            types.add(element);
        }
        return encodeBlock(types, values, depth, path);
    }

    private byte[] encodeInt(Object value, int bits, boolean signed, String path) {
        BigInteger v = toBigInteger(value, path);
        BigInteger max = TWO.pow(bits - (signed ? 1 : 0));
        if (signed) {
            if (v.compareTo(max.negate()) < 0 || v.compareTo(max) >= 0) {
                throw new CodecException(ErrorCode.INTEGER_OVERFLOW, path,
                        "int" + bits + " value out of range: " + v);
            }
        } else {
            if (v.signum() < 0) {
                throw new CodecException(ErrorCode.INTEGER_OVERFLOW, path,
                        "negative value for uint" + bits + ": " + v);
            }
            if (v.compareTo(max) >= 0) {
                throw new CodecException(ErrorCode.INTEGER_OVERFLOW, path,
                        "uint" + bits + " value out of range: " + v);
            }
        }
        byte[] word = new byte[WORD];
        if (signed && v.signum() < 0) {
            v = v.add(TWO.pow(256)); // two's complement
        }
        writeUint(word, 0, v, WORD);
        return word;
    }

    private BigInteger toBigInteger(Object value, String path) {
        if (value instanceof BigInteger bi) {
            return bi;
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof String s) {
            try {
                return new BigInteger(s.trim(), 10);
            } catch (NumberFormatException e) {
                throw new CodecException(ErrorCode.TYPE_MISMATCH, path, "not an integer: " + value);
            }
        }
        throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                "expected integer, got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private byte[] encodeAddress(Object value, String path) {
        byte[] raw = asBytes(value, path);
        if (raw.length != 20) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                    "address must be exactly 20 bytes, got " + raw.length);
        }
        byte[] word = new byte[WORD];
        System.arraycopy(raw, 0, word, 12, 20); // address is right-aligned in the word
        return word;
    }

    private byte[] encodeBool(Object value, String path) {
        if (!(value instanceof Boolean b)) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                    "bool must be a JSON/Java boolean, got " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        byte[] word = new byte[WORD];
        word[WORD - 1] = (byte) (b ? 1 : 0);
        return word;
    }

    private byte[] encodeFixedBytes(Object value, int length, String path) {
        byte[] raw = asBytes(value, path);
        if (raw.length != length) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                    "bytes" + length + " must be exactly " + length + " bytes, got " + raw.length);
        }
        byte[] word = new byte[WORD];
        System.arraycopy(raw, 0, word, 0, length);
        return word;
    }

    private byte[] encodeBytes(Object value, String path) {
        byte[] raw = asBytes(value, path);
        return padRightWithLength(raw);
    }

    private byte[] encodeString(Object value, String path) {
        if (!(value instanceof CharSequence cs)) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                    "string must be a Java String, got " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        byte[] raw = cs.toString().getBytes(StandardCharsets.UTF_8);
        return padRightWithLength(raw);
    }

    private static byte[] padRightWithLength(byte[] raw) {
        int padded = (raw.length + WORD - 1) / WORD * WORD;
        byte[] out = new byte[WORD + padded];
        writeUint(out, 0, BigInteger.valueOf(raw.length), WORD);
        System.arraycopy(raw, 0, out, WORD, raw.length);
        return out;
    }

    /** Writes a non-negative big integer as big-endian into {@code len} bytes at offset. */
    static void writeUint(byte[] buf, int off, BigInteger v, int len) {
        if (v.signum() < 0) {
            throw new IllegalArgumentException("negative value in writeUint");
        }
        byte[] mag = v.toByteArray();
        int srcLen = mag.length;
        // toByteArray may include a leading zero sign byte for values like 2^31.
        int start = (srcLen > 0 && mag[0] == 0) ? 1 : 0;
        int digits = srcLen - start;
        if (digits > len) {
            throw new CodecException(ErrorCode.INTEGER_TRUNCATION,
                    "integer needs " + digits + " bytes, slot is " + len);
        }
        System.arraycopy(mag, start, buf, off + len - digits, digits);
    }

    private byte[] asBytes(Object value, String path) {
        if (value instanceof byte[] b) {
            return b;
        }
        if (value instanceof String s) {
            try {
                return Hex.decode(s);
            } catch (IllegalArgumentException e) {
                throw new CodecException(ErrorCode.TYPE_MISMATCH, path, e.getMessage());
            }
        }
        throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                "expected hex string or byte[], got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private List<Object> asList(Object value, String path) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Object[] arr) {
            return new ArrayList<>(List.of(arr));
        }
        if (value instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                "expected array/tuple list, got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /** Static encoded size of a non-dynamic type. */
    static int staticSize(AbiType type) {
        if (type.isDynamic()) {
            throw new CodecException(ErrorCode.LAYOUT_INVALID,
                    "staticSize requested for dynamic type " + type.canonicalName());
        }
        if (type instanceof AbiType.FixedArray fa) {
            return fa.length() * staticSize(fa.element());
        }
        if (type instanceof AbiType.Tuple tup) {
            int size = 0;
            for (AbiType c : tup.components()) {
                size += staticSize(c);
            }
            return size;
        }
        // All remaining static types occupy one word.
        return WORD;
    }
}
