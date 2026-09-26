package com.github.guoyyit.contractabicodecjava.abi;

/**
 * Keccak-256（以太坊选择器使用的原始 Keccak，填充域 0x01，区别于 SHA3-256 的 0x06）。
 * 纯 Java 实现，避免外部依赖。
 */
public final class Keccak256 {

    private static final int RATE_BYTES = 136; // 1088 bits rate for 256-bit output

    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL,
            0x8000000080008000L, 0x000000000000808bL, 0x0000000080000001L,
            0x8000000080008081L, 0x8000000000008009L, 0x000000000000008aL,
            0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
            0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L,
            0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L,
            0x000000000000800aL, 0x800000008000000aL, 0x8000000080008081L,
            0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    private static final int[][] RHO = {
            {0, 36, 3, 41, 18},
            {1, 44, 10, 45, 2},
            {62, 6, 43, 15, 61},
            {28, 55, 25, 21, 56},
            {27, 20, 39, 8, 14}
    };

    private Keccak256() {}

    public static byte[] digest(byte[] input) {
        long[] state = new long[25];
        int offset = 0;
        // 吸收完整分组
        while (offset + RATE_BYTES <= input.length) {
            absorbBlock(state, input, offset, RATE_BYTES);
            keccakF(state);
            offset += RATE_BYTES;
        }
        // 尾部 + Keccak 填充（0x01 ... 0x80）
        int remaining = input.length - offset;
        byte[] pad = new byte[RATE_BYTES];
        System.arraycopy(input, offset, pad, 0, remaining);
        pad[remaining] = 0x01;
        pad[RATE_BYTES - 1] |= (byte) 0x80;
        absorbBlock(state, pad, 0, RATE_BYTES);
        keccakF(state);
        // 挤出前 32 字节
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long lane = state[i];
            for (int j = 0; j < 8; j++) {
                out[i * 8 + j] = (byte) (lane >>> (8 * j));
            }
        }
        return out;
    }

    private static void absorbBlock(long[] state, byte[] block, int offset, int len) {
        for (int i = 0; i < len / 8; i++) {
            long lane = 0;
            for (int j = 0; j < 8; j++) {
                lane |= (block[offset + i * 8 + j] & 0xFFL) << (8 * j);
            }
            state[i] ^= lane;
        }
    }

    private static void keccakF(long[] a) {
        for (long rc : RC) {
            // theta
            long[] c = new long[5];
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
                for (int y = 0; y < 5; y++) {
                    a[x + 5 * y] ^= d;
                }
            }
            // rho + pi
            long[] b = new long[25];
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = Long.rotateLeft(a[x + 5 * y], RHO[x][y]);
                }
            }
            // chi
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    a[x + 5 * y] = b[x + 5 * y] ^ ((~b[(x + 1) % 5 + 5 * y]) & b[(x + 2) % 5 + 5 * y]);
                }
            }
            // iota
            a[0] ^= rc;
        }
    }

    public static String hexDigest(byte[] input) {
        return toHex(digest(input));
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(2 + data.length * 2);
        sb.append("0x");
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
