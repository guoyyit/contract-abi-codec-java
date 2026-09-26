package com.github.guoyyit.contractabicodecjava.api;

import com.github.guoyyit.contractabicodecjava.encoding.CodecService;
import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.SelectorCollision;
import com.github.guoyyit.contractabicodecjava.registry.FunctionRegistry;

import java.util.List;

/**
 * Default local in-process {@link CodecApi}. Resolutions and encodings run against a
 * single registry snapshot for the duration of the call.
 */
public final class InProcessCodecApi implements CodecApi {

    private final FunctionRegistry registry;
    private final CodecService codecService;

    public InProcessCodecApi(FunctionRegistry registry, CodecService codecService) {
        this.registry = registry;
        this.codecService = codecService;
    }

    @Override
    public FunctionDefinition registerFunction(FunctionDefinition function) {
        return registry.register(function);
    }

    @Override
    public List<FunctionDefinition> registerFunctions(List<FunctionDefinition> functions) {
        return registry.registerAll(functions);
    }

    @Override
    public void assignAlias(String alias, String canonicalSignature) {
        registry.assignAlias(alias, canonicalSignature);
    }

    @Override
    public FunctionDefinition getFunction(String nameOrAliasOrSelector) {
        if (nameOrAliasOrSelector == null) {
            throw new CodecException(ErrorCode.FUNCTION_NOT_FOUND, "null function reference");
        }
        if (nameOrAliasOrSelector.startsWith("0x") || nameOrAliasOrSelector.startsWith("0X")) {
            return registry.resolveBySelector(nameOrAliasOrSelector);
        }
        return registry.resolveByName(nameOrAliasOrSelector);
    }

    @Override
    public List<SelectorCollision> getCollisions() {
        return registry.collisions();
    }

    @Override
    public byte[] encode(String functionNameOrAlias, List<Object> values) {
        return codecService.encodeCall(functionNameOrAlias, values);
    }

    @Override
    public List<Object> decode(String functionNameOrAlias, byte[] data) {
        return codecService.decodeResult(functionNameOrAlias, data);
    }
}
