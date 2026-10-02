package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;

import java.math.BigInteger;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict canonical ABI decoder.
 *
 * <p>Every encoding is validated against the single canonical layout:
 * <ul>
 *   <li>integers use canonical sign/zero padding (non-canonical tail bytes rejected);</li>
 *   <li>bool words are exactly 0 or 1;</li>
 *   <li>dynamic tails are sequential and tightly packed; overlapping/backward/gap
 *       offsets are rejected as non-canonical;</li>
 *   <li>reads outside the buffer are {@link ErrorCode#OUT_OF_BOUNDS_READ};</li>
 *   <li>any unconsumed byte is {@link ErrorCode#TRAILING_BYTES}.</li>
 * </ul>
 * On failure an exception is thrown before any result is returned — callers never
 * observe partial output.
 */
public final class AbiDecoder {

    private static final int WORD = 32;
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private final int maxDepth;

    public AbiDecoder(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public List<Object> decode(List<AbiType> types, byte[] data) {
        if (data == null) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "data is null");
        }
        Reader r = new Reader(data, maxDepth);
        Cursor c = new Cursor(0, data.length);
        // Function arguments always form one encoding block, even with one parameter, so a
        // dynamic argument is referenced by a head offset word.
        List<Object> values = r.readTuple(types, c, 0, "$", 0);
        if (c.pos != data.length) {
            throw new CodecException(ErrorCode.TRAILING_BYTES,
                    "trailing bytes: consumed " + c.pos + " of " + data.length);
        }
        return values;
    }

    /**
     * Decodes a single bare value, as used for a {@code bytes}/{@code string} return value
     * that is not wrapped in a parameter block.
     */
    public Object decodeValue(AbiType type, byte[] data) {
        if (data == null) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "data is null");
        }
        Reader r = new Reader(data, maxDepth);
        Cursor c = new Cursor(0, data.length);
        Object value = r.readValue(type, c, 0, "$[0]");
        if (c.pos != data.length) {
            throw new CodecException(ErrorCode.TRAILING_BYTES,
                    "trailing bytes: consumed " + c.pos + " of " + data.length);
        }
        return value;
    }

    /** Mutable read position relative to a single backing buffer. */
    private static final class Cursor {
        int pos;
        final int end;

        Cursor(int pos, int end) {
            this.pos = pos;
            this.end = end;
        }
    }

    private static final class Reader {
        final byte[] data;
        final int maxDepth;

        Reader(byte[] data, int maxDepth) {
            this.data = data;
            this.maxDepth = maxDepth;
            this.limit = data.length;
        }

        /** Current inclusive-exclusive read bound of the innermost block being decoded. */
        private int limit;

        private void checkDepth(int depth, String path) {
            if (depth > maxDepth) {
                throw new CodecException(ErrorCode.DEPTH_EXCEEDED, path,
                        "nesting depth " + depth + " exceeds limit " + maxDepth);
            }
        }

        /** Reads exactly {@code len} bytes starting at absolute offset, bounded by {@link #limit}. */
        private byte[] readBytes(int offset, int len, String path) {
            if (offset < 0 || len < 0 || (long) offset + len > limit) {
                throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, path,
                        "read [" + offset + ",+" + len + ") exceeds block end " + limit
                                + " (input is " + data.length + " bytes)");
            }
            byte[] out = new byte[len];
            System.arraycopy(data, offset, out, 0, len);
            return out;
        }

        private byte[] readWord(int offset, String path) {
            return readBytes(offset, WORD, path);
        }

        /**
         * Reads a tuple/array-element block at the cursor. Heads are read in place first;
         * tails must then appear sequentially with no gaps or overlap, advancing the
         * cursor to the first byte after the tuple.
         */
        /**
         * Reads one encoding block: a parameter list, a tuple value or an array element
         * area. {@code offsetBase} is the absolute position relative to which dynamic
         * offsets are expressed; {@code blockEnd} bounds the whole block.
         */
        List<Object> readTuple(List<AbiType> types, Cursor c, int depth, String path, int offsetBase) {
            checkDepth(depth, path);
            int n = types.size();
            int headStart = c.pos;
            int[] headSizes = new int[n];
            long nominalHead = 0;
            int[] offsets = new int[n];
            boolean[] dynamic = new boolean[n];

            for (int i = 0; i < n; i++) {
                AbiType t = types.get(i);
                dynamic[i] = t.isDynamic();
                headSizes[i] = dynamic[i] ? WORD : AbiEncoder.staticSize(t);
                nominalHead += headSizes[i];
                if (nominalHead > Integer.MAX_VALUE) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, path, "block head too large");
                }
            }

            int dynamicCount = 0;
            int[] dynIndexes = new int[n];
            for (int i = 0; i < n; i++) {
                if (dynamic[i]) {
                    dynIndexes[dynamicCount++] = i;
                }
            }

            // 1) Every offset word must be readable; offsets anchor the head/tail boundary.
            for (int i = 0; i < n; i++) {
                if (dynamic[i]) {
                    int slot = headStart + prefixSize(headSizes, i);
                    String childPath = path + "[" + i + "]";
                    if ((long) slot + WORD > data.length) {
                        throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, childPath,
                                "offset word is outside the buffer");
                    }
                    BigInteger rel = readUnsignedWord(slot, childPath);
                    if (rel.compareTo(BigInteger.valueOf(Integer.MAX_VALUE - offsetBase)) > 0) {
                        throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, childPath,
                                "offset out of range: " + rel);
                    }
                    offsets[i] = offsetBase + rel.intValueExact();
                }
            }

            // 2) The head section is exactly nominalHead bytes (every member, static or
            //    dynamic, occupies its head slot there). Dynamic tails must begin at or
            //    after it. Individual slot reads below are bounds-checked on their own so a
            //    maliciously huge declared array length cannot mask a truncation as layout.
            int tailBoundary = headStart + (int) nominalHead;
            for (int i = 0; i < n; i++) {
                int slotStart = headStart + prefixSize(headSizes, i);
                String childPath = path + "[" + i + "]";
                if ((long) slotStart + headSizes[i] > data.length) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, childPath,
                            "head slot [" + slotStart + ",+" + headSizes[i]
                                    + ") is outside the buffer");
                }
            }
            for (int k = 0; k < dynamicCount; k++) {
                int i = dynIndexes[k];
                String childPath = path + "[" + i + "]";
                // Bounds first: a truncated inner block makes the (absolute) offset land
                // past its own end even though it appears inside an outer head section,
                // and that must stay OUT_OF_BOUNDS_READ at every nesting depth.
                if (offsets[i] > c.end) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, childPath,
                            "dynamic value at offset " + offsets[i]
                                    + " exceeds block end " + c.end
                                    + " (only " + data.length + " input bytes available)");
                }
                if (offsets[i] < tailBoundary) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, childPath,
                            "dynamic offset " + (offsets[i] - offsetBase)
                                    + " points into the head section (boundary "
                                    + (tailBoundary - offsetBase) + ")");
                }
            }
            // Strictly increasing offsets.
            for (int k = 1; k < dynamicCount; k++) {
                int prev = dynIndexes[k - 1];
                int cur = dynIndexes[k];
                if (offsets[cur] <= offsets[prev]) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, path + "[" + cur + "]",
                            "non-sequential dynamic offset " + (offsets[cur] - offsetBase)
                                    + " (previous " + (offsets[prev] - offsetBase) + ")");
                }
            }

            // 3) Static heads.
            Object[] values = new Object[n];
            Cursor headCursor = new Cursor(headStart, data.length);
            for (int i = 0; i < n; i++) {
                if (!dynamic[i]) {
                    headCursor.pos = headStart + prefixSize(headSizes, i);
                    values[i] = readValue(types.get(i), headCursor, depth + 1, path + "[" + i + "]");
                    if (headCursor.pos != headStart + prefixSize(headSizes, i) + headSizes[i]) {
                        throw new CodecException(ErrorCode.LAYOUT_INVALID, path + "[" + i + "]",
                                "static value did not consume its exact head slot");
                    }
                }
            }

            // 4) Dynamic tails, sequentially and tightly packed.
            int expected = tailBoundary;
            for (int k = 0; k < dynamicCount; k++) {
                int i = dynIndexes[k];
                String childPath = path + "[" + i + "]";
                if (offsets[i] != expected) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, childPath,
                            "dynamic tail at " + (offsets[i] - offsetBase)
                                    + " but expected tightly-packed offset " + (expected - offsetBase));
                }
                int memberEnd = (k + 1 < dynamicCount) ? offsets[dynIndexes[k + 1]] : c.end;
                Cursor tailCursor = new Cursor(offsets[i], memberEnd);
                int savedLimit = limit;
                limit = memberEnd;
                try {
                    values[i] = readValue(types.get(i), tailCursor, depth + 1, childPath);
                } finally {
                    limit = savedLimit;
                }
                if (tailCursor.pos > memberEnd) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, childPath,
                            "dynamic value reads past its block boundary");
                }
                expected = tailCursor.pos;
            }
            c.pos = expected;
            return List.of(values);
        }

        private static int prefixSize(int[] headSizes, int i) {
            int s = 0;
            for (int j = 0; j < i; j++) {
                s += headSizes[j];
            }
            return s;
        }

        /** Reads one value starting at the cursor and advances it past the value. */
        private Object readValue(AbiType type, Cursor c, int depth, String path) {
            checkDepth(depth, path);
            if (type instanceof AbiType.UInt u) {
                byte[] w = readWord(c.pos, path);
                c.pos += WORD;
                return readCanonicalUnsigned(w, u.bits(), path);
            }
            if (type instanceof AbiType.Int i) {
                byte[] w = readWord(c.pos, path);
                c.pos += WORD;
                return readCanonicalSigned(w, i.bits(), path);
            }
            if (type instanceof AbiType.Address) {
                byte[] w = readWord(c.pos, path);
                if (!isZero(w, 0, 12)) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, path,
                            "address high 12 bytes must be zero");
                }
                c.pos += WORD;
                byte[] addr = new byte[20];
                System.arraycopy(w, 12, addr, 0, 20);
                return addr;
            }
            if (type instanceof AbiType.Bool) {
                byte[] w = readWord(c.pos, path);
                c.pos += WORD;
                if (!isZero(w, 0, 31)) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, path,
                            "bool padding bytes must be zero");
                }
                if (w[31] != 0 && w[31] != 1) {
                    throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                            "bool value must be 0 or 1, got " + (w[31] & 0xFF));
                }
                return w[31] == 1;
            }
            if (type instanceof AbiType.FixedBytes fb) {
                byte[] block = readBytes(c.pos, WORD, path);
                c.pos += WORD;
                if (!isZero(block, fb.length(), WORD - fb.length())) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, path,
                            "bytes" + fb.length() + " trailing bytes must be zero");
                }
                byte[] raw = new byte[fb.length()];
                System.arraycopy(block, 0, raw, 0, fb.length());
                return raw;
            }
            if (type instanceof AbiType.DynamicBytes || type instanceof AbiType.String) {
                if ((long) c.pos + WORD > c.end) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, path,
                            "length word at offset " + c.pos + " needs " + WORD
                                    + " bytes but the block ends at " + c.end
                                    + " (input is " + data.length + " bytes)");
                }
                BigInteger lenBi = readUnsignedWord(c.pos, path);
                if (lenBi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, path,
                            "length prefix out of range: " + lenBi);
                }
                int len = lenBi.intValueExact();
                int pad = (WORD - len % WORD) % WORD;
                int dataStart = c.pos + WORD;
                // Bounds relative to THIS value's block end: a short encoding must surface
                // as OUT_OF_BOUNDS_READ, never as a padding/canonicality error.
                if ((long) dataStart + len + pad > c.end) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, path,
                            "dynamic payload at offset " + dataStart + " declares "
                                    + len + " data byte(s) plus " + pad
                                    + " padding byte(s) ending at " + (dataStart + len + pad)
                                    + ", but the block ends at " + c.end
                                    + " (input is " + data.length + " bytes, "
                                    + Math.max(0, data.length - dataStart)
                                    + " byte(s) remain after the offset)");
                }
                byte[] raw = readBytes(dataStart, len, path);
                byte[] tail = readBytes(dataStart + len, pad, path);
                if (!isZero(tail, 0, tail.length)) {
                    throw new CodecException(ErrorCode.NON_CANONICAL_ENCODING, path,
                            "dynamic payload padding bytes must be zero");
                }
                c.pos = dataStart + len + pad;
                if (type instanceof AbiType.String) {
                    return decodeUtf8(raw, path);
                }
                return raw;
            }
            if (type instanceof AbiType.Tuple tup) {
                return readTuple(tup.components(), c, depth, path, c.pos);
            }
            if (type instanceof AbiType.FixedArray fa) {
                return readArray(fa.element(), fa.length(), c, depth, path, c.pos);
            }
            if (type instanceof AbiType.Array arr) {
                BigInteger lenBi = readUnsignedWord(c.pos, path);
                if (lenBi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                    throw new CodecException(ErrorCode.OUT_OF_BOUNDS_READ, path,
                            "array length out of range: " + lenBi);
                }
                int len = lenBi.intValueExact();
                c.pos += WORD;
                return readArray(arr.element(), len, c, depth, path, c.pos);
            }
            throw new CodecException(ErrorCode.LAYOUT_INVALID, path, "unknown type: " + type);
        }

        /** Reads {@code len} homogeneous elements laid out as a tuple (no length word). */
        private List<Object> readArray(AbiType element, int len, Cursor c, int depth, String path, int offsetBase) {
            List<AbiType> types = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                types.add(element);
            }
            return readTuple(types, c, depth, path, offsetBase);
        }

        private BigInteger readUnsignedWord(int offset, String path) {
            byte[] w = readWord(offset, path);
            return new BigInteger(1, w);
        }

        private BigInteger readCanonicalUnsigned(byte[] w, int bits, String path) {
            int unused = 32 - bits / 8;
            if (!isZero(w, 0, unused)) {
                throw new CodecException(ErrorCode.INTEGER_TRUNCATION, path,
                        "uint" + bits + " has non-zero high padding bytes");
            }
            return new BigInteger(1, w);
        }

        private BigInteger readCanonicalSigned(byte[] w, int bits, String path) {
            int bytes = bits / 8;
            int pad = 32 - bytes;
            byte signFill = (w[pad] & 0x80) != 0 ? (byte) 0xFF : 0x00;
            for (int i = 0; i < pad; i++) {
                if (w[i] != signFill) {
                    throw new CodecException(ErrorCode.INTEGER_TRUNCATION, path,
                            "int" + bits + " padding is not a canonical sign extension");
                }
            }
            byte[] mag = new byte[bytes];
            System.arraycopy(w, pad, mag, 0, bytes);
            return new BigInteger(mag);
        }

        private String decodeUtf8(byte[] raw, String path) {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException e) {
                throw new CodecException(ErrorCode.TYPE_MISMATCH, path,
                        "string is not valid UTF-8");
            }
        }

        private boolean isZero(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                if (b[i] != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
