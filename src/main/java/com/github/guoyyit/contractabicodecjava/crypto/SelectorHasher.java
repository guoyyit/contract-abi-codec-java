package com.github.guoyyit.contractabicodecjava.crypto;

/**
 * Abstraction over the signature hash so tests can inject a collision-forcing hash.
 */
@FunctionalInterface
public interface SelectorHasher {
    /** Returns the 4-byte selector of a canonical signature. */
    byte[] selector4(String canonicalSignature);

    static SelectorHasher keccak() {
        return sig -> {
            byte[] h = Keccak256.hash(sig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[4];
            System.arraycopy(h, 0, out, 0, 4);
            return out;
        };
    }
}
