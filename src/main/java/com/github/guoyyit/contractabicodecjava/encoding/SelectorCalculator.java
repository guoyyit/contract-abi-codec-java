package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.crypto.SelectorHasher;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.util.Hex;

/** Computes canonical signature + 0x-prefixed selector for functions. */
public final class SelectorCalculator {

    private final SelectorHasher hasher;

    public SelectorCalculator(SelectorHasher hasher) {
        this.hasher = hasher;
    }

    /** Canonical signature: name(paramType1,paramType2). */
    public String canonicalSignature(FunctionDefinition function) {
        return CanonicalSignature.of(function);
    }

    /** Returns the 0x + 8 lowercase hex character selector. */
    public String selector(FunctionDefinition function) {
        return "0x" + Hex.encode(hasher.selector4(CanonicalSignature.of(function)));
    }
}
