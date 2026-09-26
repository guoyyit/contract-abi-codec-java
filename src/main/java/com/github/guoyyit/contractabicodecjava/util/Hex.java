package com.github.guoyyit.contractabicodecjava.util;

/** Lowercase 0x-prefixed hex helpers used for selectors and calldata I/O. */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    public static String encode(byte[] bytes) {
        if (bytes == null) {
            return "0x";
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = DIGITS[v >>> 4];
            out[i * 2 + 1] = DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    /** Accepts an optional 0x prefix; rejects odd length and non-hex characters. */
    public static byte[] decode(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex string is null");
        }
        String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("odd-length hex string: " + hex);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = digit(s.charAt(i * 2), hex);
            int lo = digit(s.charAt(i * 2 + 1), hex);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String encode0x(byte[] bytes) {
        return "0x" + encode(bytes);
    }

    private static int digit(char c, String original) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("invalid hex character '" + c + "' in: " + original);
    }
}
