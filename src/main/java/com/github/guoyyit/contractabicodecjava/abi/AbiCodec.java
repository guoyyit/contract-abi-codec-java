package com.github.guoyyit.contractabicodecjava.abi;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范 ABI 编解码器。
 *
 * 编码：只产生规范表示（最小长度、零填充、精确偏移）。
 * 解码：只接受规范表示 —— 动态成员偏移必须精确等于尾部当前位置，
 * 填充字节必须为零，长度前缀必须与内容一致，缓冲区必须被恰好消费完。
 * 所有失败以结构化 {@link AbiException} 抛出，绝不返回部分结果。
 */
public final class AbiCodec {

    private static final BigInteger TWO = BigInteger.valueOf(2);

    private final int maxDepth;

    public AbiCodec(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        this.maxDepth = maxDepth;
    }

    // ---------------------------------------------------------------- 类型属性

    public static boolean isDynamic(AbiType t) {
        if (t instanceof AbiType.DynamicBytesType || t instanceof AbiType.StringType
                || t instanceof AbiType.DynamicArrayType) {
            return true;
        }
        if (t instanceof AbiType.StaticArrayType a) {
            return isDynamic(a.elementType());
        }
        if (t instanceof AbiType.TupleType tuple) {
            for (AbiType c : tuple.components()) {
                if (isDynamic(c)) return true;
            }
            return false;
        }
        return false;
    }

    /** 静态类型的编码字节长度；动态类型抛异常。用 long 防溢出。 */
    public static long staticSize(AbiType t) {
        if (t instanceof AbiType.UIntType || t instanceof AbiType.IntType
                || t instanceof AbiType.BoolType || t instanceof AbiType.AddressType
                || t instanceof AbiType.FixedBytesType) {
            return 32;
        }
        if (t instanceof AbiType.StaticArrayType a) {
            return Math.multiplyExact((long) a.length(), staticSize(a.elementType()));
        }
        if (t instanceof AbiType.TupleType tuple) {
            long sum = 0;
            for (AbiType c : tuple.components()) {
                sum = Math.addExact(sum, staticSize(c));
            }
            return sum;
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "dynamic type has no static size");
    }

    /** 类型在元组头中占用的字节数：动态 32（偏移），静态为 staticSize。 */
    private static long headSize(AbiType t) {
        return isDynamic(t) ? 32 : staticSize(t);
    }

    // ---------------------------------------------------------------- 编码

    /** 按类型列表编码一组值（head/tail 布局）。 */
    public byte[] encodeTuple(List<AbiType> types, List<Object> values) {
        return encodeTuple(types, values, null);
    }

    /**
     * 带预计算头部布局的编码。layout 为 {@link #computeHeadLayout} 的结果，
     * 传 null 时内部现算。布局来自缓存时调用方必须保证与 types 对应。
     */
    public byte[] encodeTuple(List<AbiType> types, List<Object> values, long[] layout) {
        if (types.size() != values.size()) {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                    "arity mismatch: " + types.size() + " types vs " + values.size() + " values");
        }
        return encodeTupleInternal(types, values, 1, layout);
    }

    /** 计算每个类型在元组头中的字节数布局。 */
    public static long[] computeHeadLayout(List<AbiType> types) {
        long[] layout = new long[types.size()];
        for (int i = 0; i < types.size(); i++) {
            layout[i] = headSize(types.get(i));
        }
        return layout;
    }

    private byte[] encodeTupleInternal(List<AbiType> types, List<Object> values, int depth) {
        return encodeTupleInternal(types, values, depth, null);
    }

    private byte[] encodeTupleInternal(List<AbiType> types, List<Object> values, int depth, long[] layout) {
        checkDepth(depth);
        if (types.size() != values.size()) {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                    "arity mismatch: " + types.size() + " types vs " + values.size() + " values");
        }
        long headLen = 0;
        if (layout != null && layout.length == types.size()) {
            for (long h : layout) {
                headLen = Math.addExact(headLen, h);
            }
        } else {
            for (AbiType t : types) {
                headLen = Math.addExact(headLen, headSize(t));
            }
        }
        if (headLen > Integer.MAX_VALUE) {
            throw new AbiException(AbiException.ErrorCode.INTEGER_OVERFLOW, "head size overflow");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) headLen);
        ByteArrayOutputStream tails = new ByteArrayOutputStream();
        long tailOffset = headLen;
        for (int i = 0; i < types.size(); i++) {
            AbiType t = types.get(i);
            Object v = values.get(i);
            if (isDynamic(t)) {
                byte[] tail = encodeTail(t, v, depth + 1);
                writeWord(out, BigInteger.valueOf(tailOffset));
                tails.writeBytes(tail);
                tailOffset += tail.length;
            } else {
                encodeStatic(out, t, v, depth + 1);
            }
        }
        out.writeBytes(tails.toByteArray());
        return out.toByteArray();
    }

    /** 动态类型的尾部编码。 */
    private byte[] encodeTail(AbiType t, Object v, int depth) {
        checkDepth(depth);
        if (t instanceof AbiType.DynamicBytesType) {
            return encodeBytesTail(toBytes(v));
        }
        if (t instanceof AbiType.StringType) {
            if (!(v instanceof String s)) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "expected String for string type, got " + className(v));
            }
            return encodeBytesTail(s.getBytes(StandardCharsets.UTF_8));
        }
        if (t instanceof AbiType.DynamicArrayType a) {
            List<Object> items = toList(v);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeWord(out, BigInteger.valueOf(items.size()));
            out.writeBytes(encodeArrayBody(a.elementType(), items, depth));
            return out.toByteArray();
        }
        if (t instanceof AbiType.StaticArrayType a) {
            List<Object> items = toList(v);
            if (items.size() != a.length()) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "static array expects " + a.length() + " elements, got " + items.size());
            }
            return encodeArrayBody(a.elementType(), items, depth);
        }
        if (t instanceof AbiType.TupleType tuple) {
            return encodeTupleInternal(tuple.components(), toList(v), depth);
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "not a dynamic type");
    }

    private byte[] encodeArrayBody(AbiType elementType, List<Object> items, int depth) {
        List<AbiType> types = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            types.add(elementType);
        }
        return encodeTupleInternal(types, items, depth);
    }

    private static byte[] encodeBytesTail(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + paddedLength(data.length));
        writeWord(out, BigInteger.valueOf(data.length));
        out.writeBytes(data);
        int pad = paddedLength(data.length) - data.length;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    /** 静态类型内联编码。 */
    private void encodeStatic(ByteArrayOutputStream out, AbiType t, Object v, int depth) {
        checkDepth(depth);
        if (t instanceof AbiType.UIntType u) {
            BigInteger value = toBigInteger(v);
            BigInteger max = TWO.pow(u.bits());
            if (value.signum() < 0 || value.compareTo(max) >= 0) {
                throw new AbiException(AbiException.ErrorCode.INTEGER_OVERFLOW,
                        "value " + value + " out of range for uint" + u.bits());
            }
            writeWord(out, value);
        } else if (t instanceof AbiType.IntType i) {
            BigInteger value = toBigInteger(v);
            BigInteger min = TWO.pow(i.bits() - 1).negate();
            BigInteger max = TWO.pow(i.bits() - 1).subtract(BigInteger.ONE);
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new AbiException(AbiException.ErrorCode.INTEGER_OVERFLOW,
                        "value " + value + " out of range for int" + i.bits());
            }
            writeWord(out, toTwosComplement256(value));
        } else if (t instanceof AbiType.BoolType) {
            if (!(v instanceof Boolean b)) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "expected Boolean for bool type, got " + className(v));
            }
            writeWord(out, b ? BigInteger.ONE : BigInteger.ZERO);
        } else if (t instanceof AbiType.AddressType) {
            writeWord(out, toAddressWord(v));
        } else if (t instanceof AbiType.FixedBytesType f) {
            byte[] data = toBytes(v);
            if (data.length > f.size()) {
                throw new AbiException(AbiException.ErrorCode.TRUNCATION,
                        "value of " + data.length + " bytes would be truncated to bytes" + f.size());
            }
            if (data.length < f.size()) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "expected exactly " + f.size() + " bytes for bytes" + f.size()
                                + ", got " + data.length);
            }
            out.writeBytes(data);
            for (int i = data.length; i < 32; i++) {
                out.write(0);
            }
        } else if (t instanceof AbiType.StaticArrayType a) {
            List<Object> items = toList(v);
            if (items.size() != a.length()) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "static array expects " + a.length() + " elements, got " + items.size());
            }
            for (Object item : items) {
                encodeStatic(out, a.elementType(), item, depth + 1);
            }
        } else if (t instanceof AbiType.TupleType tuple) {
            List<Object> items = toList(v);
            if (items.size() != tuple.components().size()) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "tuple expects " + tuple.components().size() + " values, got " + items.size());
            }
            for (int i = 0; i < tuple.components().size(); i++) {
                encodeStatic(out, tuple.components().get(i), items.get(i), depth + 1);
            }
        } else {
            throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "not a static type");
        }
    }

    // ---------------------------------------------------------------- 解码

    /** 解码整个缓冲区，要求恰好消费完，否则报 TRAILING_BYTES。 */
    public List<Object> decodeTuple(List<AbiType> types, byte[] data) {
        return decodeTuple(types, data, null);
    }

    /** 带预计算头部布局的解码，layout 语义同 {@link #encodeTuple}。 */
    public List<Object> decodeTuple(List<AbiType> types, byte[] data, long[] layout) {
        if (data == null) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS, "null input");
        }
        Decoded d = decodeTupleAt(types, data, 0, 1, layout);
        if (d.consumed != data.length) {
            throw new AbiException(AbiException.ErrorCode.TRAILING_BYTES, d.consumed,
                    "trailing bytes: consumed " + d.consumed + " of " + data.length);
        }
        return d.values;
    }

    private record Decoded(List<Object> values, long consumed) {}

    /** 在 offset 处解码元组，返回值与消费的总字节数（含尾部）。 */
    private Decoded decodeTupleAt(List<AbiType> types, byte[] data, long offset, int depth) {
        return decodeTupleAt(types, data, offset, depth, null);
    }

    private Decoded decodeTupleAt(List<AbiType> types, byte[] data, long offset, int depth, long[] layout) {
        checkDepth(depth);
        long headLen = 0;
        if (layout != null && layout.length == types.size()) {
            for (long h : layout) {
                headLen = Math.addExact(headLen, h);
            }
        } else {
            for (AbiType t : types) {
                headLen = Math.addExact(headLen, headSize(t));
            }
        }
        require(data, offset, headLen);
        List<Object> values = new ArrayList<>(types.size());
        long pos = offset;
        long expectedTail = headLen; // 相对 offset
        for (AbiType t : types) {
            if (isDynamic(t)) {
                BigInteger offWord = readUintWord(data, pos);
                long rel = toExactOffset(offWord, expectedTail, pos);
                Decoded inner = decodeTailAt(t, data, offset + rel, depth + 1);
                values.add(inner.values.get(0));
                expectedTail = Math.addExact(expectedTail, inner.consumed);
                pos += 32;
            } else {
                values.add(decodeStaticAt(t, data, pos, depth + 1));
                pos += staticSize(t);
            }
        }
        return new Decoded(values, expectedTail);
    }

    /** 解码动态类型尾部，包装成单元素列表返回以复用 Decoded。 */
    private Decoded decodeTailAt(AbiType t, byte[] data, long pos, int depth) {
        checkDepth(depth);
        if (t instanceof AbiType.DynamicBytesType) {
            long[] meta = readLengthAndCheck(data, pos);
            long len = meta[0];
            require(data, pos + 32, ceil32(len));
            byte[] value = slice(data, pos + 32, len);
            checkZeroPadding(data, pos + 32 + len, ceil32(len) - len);
            return new Decoded(List.of(value), 32 + ceil32(len));
        }
        if (t instanceof AbiType.StringType) {
            long[] meta = readLengthAndCheck(data, pos);
            long len = meta[0];
            require(data, pos + 32, ceil32(len));
            byte[] raw = slice(data, pos + 32, len);
            checkZeroPadding(data, pos + 32 + len, ceil32(len) - len);
            return new Decoded(List.of(strictUtf8(raw, pos + 32)), 32 + ceil32(len));
        }
        if (t instanceof AbiType.DynamicArrayType a) {
            long[] meta = readLengthAndCheck(data, pos);
            long len = meta[0];
            long elemHead = headSize(a.elementType());
            // 快速失败：元素头部区必然需要 len*elemHead 字节
            long need = Math.multiplyExact(len, elemHead);
            require(data, pos + 32, need);
            List<AbiType> types = repeatType(a.elementType(), len);
            Decoded inner = decodeTupleAt(types, data, pos + 32, depth + 1);
            return new Decoded(List.of(inner.values), 32 + inner.consumed);
        }
        if (t instanceof AbiType.StaticArrayType a) {
            List<AbiType> types = repeatType(a.elementType(), a.length());
            Decoded inner = decodeTupleAt(types, data, pos, depth + 1);
            return new Decoded(List.of(inner.values), inner.consumed);
        }
        if (t instanceof AbiType.TupleType tuple) {
            Decoded inner = decodeTupleAt(tuple.components(), data, pos, depth + 1);
            return new Decoded(List.of(inner.values), inner.consumed);
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "not a dynamic type");
    }

    private Object decodeStaticAt(AbiType t, byte[] data, long pos, int depth) {
        checkDepth(depth);
        if (t instanceof AbiType.UIntType u) {
            BigInteger word = readUintWord(data, pos);
            if (word.bitLength() > u.bits()) {
                throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, pos,
                        "uint" + u.bits() + " word has non-zero high bits");
            }
            return word;
        }
        if (t instanceof AbiType.IntType i) {
            BigInteger word = readSignedWord(data, pos);
            // 规范要求符号扩展：值必须能装入 bits 位
            BigInteger min = TWO.pow(i.bits() - 1).negate();
            BigInteger max = TWO.pow(i.bits() - 1).subtract(BigInteger.ONE);
            if (word.compareTo(min) < 0 || word.compareTo(max) > 0) {
                throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, pos,
                        "int" + i.bits() + " word is not properly sign-extended");
            }
            return word;
        }
        if (t instanceof AbiType.BoolType) {
            BigInteger word = readUintWord(data, pos);
            if (!word.equals(BigInteger.ZERO) && !word.equals(BigInteger.ONE)) {
                throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, pos,
                        "bool word must be 0 or 1");
            }
            return word.equals(BigInteger.ONE);
        }
        if (t instanceof AbiType.AddressType) {
            require(data, pos, 32);
            for (long i = pos; i < pos + 12; i++) {
                if (data[(int) i] != 0) {
                    throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, pos,
                            "address word has non-zero high 12 bytes");
                }
            }
            return slice(data, pos + 12, 20);
        }
        if (t instanceof AbiType.FixedBytesType f) {
            require(data, pos, 32);
            for (long i = pos + f.size(); i < pos + 32; i++) {
                if (data[(int) i] != 0) {
                    throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, pos,
                            "bytes" + f.size() + " has non-zero padding bytes");
                }
            }
            return slice(data, pos, f.size());
        }
        if (t instanceof AbiType.StaticArrayType a) {
            List<Object> items = new ArrayList<>(a.length());
            long p = pos;
            for (int i = 0; i < a.length(); i++) {
                items.add(decodeStaticAt(a.elementType(), data, p, depth + 1));
                p += staticSize(a.elementType());
            }
            return items;
        }
        if (t instanceof AbiType.TupleType tuple) {
            List<Object> items = new ArrayList<>(tuple.components().size());
            long p = pos;
            for (AbiType c : tuple.components()) {
                items.add(decodeStaticAt(c, data, p, depth + 1));
                p += staticSize(c);
            }
            return items;
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH, "not a static type");
    }

    // ---------------------------------------------------------------- 基础读取

    private static void require(byte[] data, long pos, long len) {
        if (pos < 0 || len < 0 || pos + len > data.length) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS, pos,
                    "read of " + len + " bytes at " + pos + " exceeds buffer of " + data.length);
        }
    }

    private static BigInteger readUintWord(byte[] data, long pos) {
        require(data, pos, 32);
        byte[] word = new byte[32];
        System.arraycopy(data, (int) pos, word, 0, 32);
        return new BigInteger(1, word);
    }

    private static BigInteger readSignedWord(byte[] data, long pos) {
        require(data, pos, 32);
        byte[] word = new byte[32];
        System.arraycopy(data, (int) pos, word, 0, 32);
        return new BigInteger(word);
    }

    /** 读取长度前缀并做溢出/越界检查，返回 {len}。 */
    private static long[] readLengthAndCheck(byte[] data, long pos) {
        BigInteger lenWord = readUintWord(data, pos);
        if (lenWord.bitLength() > 31) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS, pos,
                    "length prefix too large: " + lenWord);
        }
        long len = lenWord.longValue();
        // 数据区至少需要 len 字节（动态字节）——上界检查在具体类型里做
        return new long[]{len};
    }

    /** 动态偏移必须精确等于期望的尾部位置（规范最严形式）。 */
    private static long toExactOffset(BigInteger offWord, long expected, long wordPos) {
        if (offWord.bitLength() > 62) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS, wordPos,
                    "offset too large: " + offWord);
        }
        long off = offWord.longValue();
        if (off % 32 != 0) {
            throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, wordPos,
                    "offset " + off + " is not 32-byte aligned");
        }
        if (off != expected) {
            throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, wordPos,
                    "non-canonical offset " + off + ", expected " + expected);
        }
        return off;
    }

    private static void checkZeroPadding(byte[] data, long pos, long padLen) {
        for (long i = pos; i < pos + padLen; i++) {
            if (data[(int) i] != 0) {
                throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, i,
                        "non-zero padding byte");
            }
        }
    }

    private static String strictUtf8(byte[] raw, long pos) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            throw new AbiException(AbiException.ErrorCode.NON_CANONICAL, pos,
                    "invalid UTF-8 in string value");
        }
    }

    private static byte[] slice(byte[] data, long pos, long len) {
        byte[] out = new byte[(int) len];
        System.arraycopy(data, (int) pos, out, 0, (int) len);
        return out;
    }

    private static List<AbiType> repeatType(AbiType t, long count) {
        if (count > 1_000_000) {
            throw new AbiException(AbiException.ErrorCode.OUT_OF_BOUNDS,
                    "array length " + count + " exceeds sanity limit");
        }
        List<AbiType> types = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            types.add(t);
        }
        return types;
    }

    private static long ceil32(long len) {
        return (len + 31) / 32 * 32;
    }

    private static int paddedLength(int len) {
        return (len + 31) / 32 * 32;
    }

    // ---------------------------------------------------------------- 值转换

    private static BigInteger toBigInteger(Object v) {
        if (v instanceof BigInteger b) return b;
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
            return BigInteger.valueOf(((Number) v).longValue());
        }
        if (v instanceof String s) {
            try {
                // 支持十进制与 0x 十六进制整数字符串
                return s.startsWith("0x") ? new BigInteger(s.substring(2), 16) : new BigInteger(s);
            } catch (NumberFormatException e) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "string value is not a decimal or 0x-hex integer: " + s);
            }
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                "expected integer value, got " + className(v));
    }

    private static BigInteger toTwosComplement256(BigInteger value) {
        BigInteger v = value;
        if (v.signum() < 0) {
            v = TWO.pow(256).add(v);
        }
        return v;
    }

    private static BigInteger toAddressWord(Object v) {
        // 0x 前缀的 40 位十六进制字符串按 20 字节地址处理
        if (v instanceof String s && s.startsWith("0x") && s.length() == 42) {
            return new BigInteger(1, toBytes(s));
        }
        if (v instanceof byte[] b) {
            if (b.length != 20) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "address must be 20 bytes, got " + b.length);
            }
            return new BigInteger(1, b);
        }
        BigInteger value = toBigInteger(v);
        BigInteger max = TWO.pow(160);
        if (value.signum() < 0 || value.compareTo(max) >= 0) {
            throw new AbiException(AbiException.ErrorCode.INTEGER_OVERFLOW,
                    "address out of range for uint160: " + value);
        }
        return value;
    }

    private static byte[] toBytes(Object v) {
        if (v instanceof byte[] b) return b;
        if (v instanceof String s && s.startsWith("0x")) {
            String hex = s.substring(2);
            if (hex.length() % 2 != 0) {
                throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                        "hex string has odd length: " + s);
            }
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                int hi = Character.digit(hex.charAt(i * 2), 16);
                int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
                if (hi < 0 || lo < 0) {
                    throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                            "invalid hex string: " + s);
                }
                out[i] = (byte) (hi * 16 + lo);
            }
            return out;
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                "expected byte[] or 0x-hex string, got " + className(v));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toList(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new AbiException(AbiException.ErrorCode.TYPE_MISMATCH,
                "expected List value, got " + className(v));
    }

    private static String className(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static void writeWord(ByteArrayOutputStream out, BigInteger value) {
        byte[] raw = value.toByteArray();
        int start = Math.max(0, raw.length - 32);
        int pad = 32 - (raw.length - start);
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
        out.write(raw, start, raw.length - start);
    }

    private void checkDepth(int depth) {
        if (depth > maxDepth) {
            throw new AbiException(AbiException.ErrorCode.DEPTH_LIMIT_EXCEEDED,
                    "nesting depth " + depth + " exceeds limit " + maxDepth);
        }
    }
}
