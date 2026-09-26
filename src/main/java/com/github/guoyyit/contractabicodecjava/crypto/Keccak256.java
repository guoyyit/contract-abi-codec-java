package com.github.guoyyit.contractabicodecjava.crypto;

/**
 * Pure-Java Keccak-256 (rate 1088, capacity 512, pad byte 0x01).
 * This is the pre-standard Keccak variant used by Ethereum for function selectors,
 * not NIST SHA3-256 (which pads with 0x06).
 */
public final class Keccak256 {

    private static final int RATE = 136; // bytes for Keccak-256

    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };

    private static final int[] ROT = {
            1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
            27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    };

    private static final int[] PIL = {
            10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
            15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    };

    private Keccak256() {}

    public static byte[] hash(byte[] input) {
        return hash(input, 0, input == null ? 0 : input.length);
    }

    public static byte[] hash(byte[] input, int offset, int length) {
        if (input == null) {
            throw new IllegalArgumentException("input is null");
        }
        long[] state = new long[25];

        int fullBlocks = length / RATE;
        for (int block = 0; block < fullBlocks; block++) {
            absorbBlock(state, input, offset + block * RATE);
            keccakF(state);
        }

        byte[] last = new byte[RATE];
        int remaining = length - fullBlocks * RATE;
        System.arraycopy(input, offset + fullBlocks * RATE, last, 0, remaining);
        last[remaining] = 0x01; // Keccak domain-separation padding
        last[RATE - 1] |= (byte) 0x80;
        absorbBlock(state, last, 0);
        keccakF(state);

        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long lane = state[i];
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (lane >>> (b * 8));
            }
        }
        return out;
    }

    private static void absorbBlock(long[] state, byte[] block, int off) {
        for (int i = 0; i < RATE / 8; i++) {
            long lane = 0;
            for (int b = 0; b < 8; b++) {
                lane |= (block[off + i * 8 + b] & 0xFFL) << (b * 8);
            }
            state[i] ^= lane;
        }
    }

    private static void keccakF(long[] a) {
        for (int round = 0; round < 24; round++) {
            // Theta
            long[] c = new long[5];
            long[] d = new long[5];
            for (int x = 0; x < 5; x++) {
                c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
            }
            for (int x = 0; x < 5; x++) {
                d[x] = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
            }
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    a[x + 5 * y] ^= d[x];
                }
            }

            // Rho + Pi: rotate and permute lanes
            long current = a[1];
            for (int i = 0; i < 24; i++) {
                int j = PIL[i];
                long temp = a[j];
                a[j] = Long.rotateLeft(current, ROT[i]);
                current = temp;
            }

            // Chi
            for (int y = 0; y < 5; y++) {
                int base = 5 * y;
                long t0 = a[base];
                long t1 = a[base + 1];
                long t2 = a[base + 2];
                long t3 = a[base + 3];
                long t4 = a[base + 4];
                a[base] = t0 ^ ((~t1) & t2);
                a[base + 1] = t1 ^ ((~t2) & t3);
                a[base + 2] = t2 ^ ((~t3) & t4);
                a[base + 3] = t3 ^ ((~t4) & t0);
                a[base + 4] = t4 ^ ((~t0) & t1);
            }

            // Iota
            a[0] ^= RC[round];
        }
    }
}
