package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;

import java.util.regex.Pattern;

/** Builds canonical signatures such as {@code transfer(address,uint256)}. */
public final class CanonicalSignature {

    /** Solidity identifier: letter/underscore start, then letter/digit/underscore. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private CanonicalSignature() {}

    public static String of(FunctionDefinition function) {
        validateName(function.name());
        StringBuilder sb = new StringBuilder(function.name()).append('(');
        for (int i = 0; i < function.inputs().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(of(function.inputs().get(i).type()));
        }
        return sb.append(')').toString();
    }

    public static String of(AbiType type) {
        return type.canonicalName();
    }

    public static void validateName(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new CodecException(ErrorCode.INVALID_SIGNATURE, "invalid function name: " + name);
        }
    }
}
