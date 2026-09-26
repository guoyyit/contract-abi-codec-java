package com.github.guoyyit.contractabicodecjava.model;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;

/** Static (head) encoded size of non-dynamic ABI types in bytes. */
public final class AbiStaticSize {

    private static final int WORD = 32;

    private AbiStaticSize() {}

    public static int of(AbiType type) {
        if (type.isDynamic()) {
            throw new CodecException(ErrorCode.LAYOUT_INVALID,
                    "static size requested for dynamic type " + type.canonicalName());
        }
        if (type instanceof AbiType.FixedArray fa) {
            return fa.length() * of(fa.element());
        }
        if (type instanceof AbiType.Tuple tup) {
            int size = 0;
            for (AbiType c : tup.components()) {
                size += of(c);
            }
            return size;
        }
        return WORD;
    }
}
