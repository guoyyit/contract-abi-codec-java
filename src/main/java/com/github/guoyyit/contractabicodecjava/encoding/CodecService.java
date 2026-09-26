package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.Parameter;
import com.github.guoyyit.contractabicodecjava.model.RegistrySnapshot;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;
import com.github.guoyyit.contractabicodecjava.util.Hex;

import java.util.Arrays;
import java.util.List;

/**
 * In-process canonical codec facade.
 *
 * <p>Every call resolves the target function against one immutable
 * {@link RegistrySnapshot}, applies the configured input-size / nesting-depth limits
 * (failing fast before touching the data) and delegates to the strict encoder/decoder.
 * One request can never affect another: no shared mutable decode state is kept.
 */
public final class CodecService {

    private static final int SELECTOR_LEN = 4;

    private final FunctionRegistry registry;
    private final AbiEncoder encoder;
    private final AbiDecoder decoder;
    private final TypeLayoutCache layoutCache;
    private final int maxInputBytes;

    public CodecService(FunctionRegistry registry,
                        AbiEncoder encoder,
                        AbiDecoder decoder,
                        TypeLayoutCache layoutCache,
                        int maxInputBytes) {
        if (maxInputBytes <= 0) {
            throw new CodecException(ErrorCode.LAYOUT_INVALID, "maxInputBytes must be positive");
        }
        this.registry = registry;
        this.encoder = encoder;
        this.decoder = decoder;
        this.layoutCache = layoutCache;
        this.maxInputBytes = maxInputBytes;
    }

    /** Resolve via alias / bare name / full signature / selector against a single snapshot. */
    private FunctionDefinition resolve(String ref) {
        RegistrySnapshot snap = registry.snapshot();
        if (snap.aliases().containsKey(ref)) {
            return registry.resolveBySelector(ref);
        }
        if (ref.startsWith("0x") || ref.startsWith("0X")) {
            return registry.resolveBySelector(ref.toLowerCase());
        }
        if (ref.indexOf('(') >= 0) {
            FunctionDefinition f = snap.byName().get(ref);
            if (f == null) {
                throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND,
                        "no function with signature: " + ref);
            }
            return f;
        }
        return registry.resolveByName(ref);
    }

    /** Full calldata: 4-byte selector + canonical arguments. */
    public byte[] encodeCall(String functionNameOrAlias, List<Object> values) {
        FunctionDefinition function = resolve(functionNameOrAlias);
        byte[] args = encodeArgs(function, values);
        byte[] selector = Hex.decode(function.selector());
        byte[] out = new byte[selector.length + args.length];
        System.arraycopy(selector, 0, out, 0, selector.length);
        System.arraycopy(args, 0, out, selector.length, args.length);
        return out;
    }

    public byte[] encodeArgs(FunctionDefinition function, List<Object> values) {
        if (values == null) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "values are required");
        }
        if (values.size() > maxInputBytes / 32) {
            // Cheap pre-check before layout work; definitive check is the encoded length below.
            throw new CodecException(ErrorCode.INPUT_TOO_LARGE,
                    "argument count exceeds configured capacity");
        }
        List<AbiType> types = function.inputs().stream().map(Parameter::type).toList();
        // Touch the bounded layout cache so repeated signatures reuse resolved layouts.
        layoutCache.getOrCompute(types, () -> new com.github.guoyyit.contractabicodecjava.model.TypeLayout(types));
        byte[] encoded = encoder.encode(types, values);
        if (encoded.length > maxInputBytes) {
            throw new CodecException(ErrorCode.INPUT_TOO_LARGE,
                    "encoded input is " + encoded.length + " bytes, limit is " + maxInputBytes);
        }
        return encoded;
    }

    /**
     * Decodes return data (if it does not start with the selector) or full calldata
     * (if it does; the selector must match). Returns decoded output values.
     */
    public List<Object> decodeResult(String functionNameOrAlias, byte[] data) {
        if (data == null) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "data is null");
        }
        if (data.length > maxInputBytes) {
            throw new CodecException(ErrorCode.INPUT_TOO_LARGE,
                    "input is " + data.length + " bytes, limit is " + maxInputBytes);
        }
        FunctionDefinition function = resolve(functionNameOrAlias);
        byte[] payload = data;
        byte[] selector = Hex.decode(function.selector());
        // Calldata (with selector) must carry the selector plus at least one word, so a
        // matching prefix can be distinguished unambiguously from raw return data.
        if (data.length > SELECTOR_LEN
                && Arrays.equals(Arrays.copyOfRange(data, 0, SELECTOR_LEN), selector)) {
            payload = Arrays.copyOfRange(data, SELECTOR_LEN, data.length);
        }
        List<AbiType> types = function.outputs().stream().map(Parameter::type).toList();
        if (types.isEmpty()) {
            if (payload.length != 0) {
                throw new CodecException(ErrorCode.TRAILING_BYTES,
                        "function declares no outputs but " + payload.length + " bytes were supplied");
            }
            return List.of();
        }
        layoutCache.getOrCompute(types, () -> new com.github.guoyyit.contractabicodecjava.model.TypeLayout(types));
        return decoder.decode(types, payload);
    }

    /** Decodes call arguments (no selector) rather than return data. */
    public List<Object> decodeArgs(String functionNameOrAlias, byte[] data) {
        if (data == null) {
            throw new CodecException(ErrorCode.TYPE_MISMATCH, "data is null");
        }
        if (data.length > maxInputBytes) {
            throw new CodecException(ErrorCode.INPUT_TOO_LARGE,
                    "input is " + data.length + " bytes, limit is " + maxInputBytes);
        }
        FunctionDefinition function = resolve(functionNameOrAlias);
        List<AbiType> types = function.inputs().stream().map(Parameter::type).toList();
        layoutCache.getOrCompute(types, () -> new com.github.guoyyit.contractabicodecjava.model.TypeLayout(types));
        return decoder.decode(types, data);
    }

}

