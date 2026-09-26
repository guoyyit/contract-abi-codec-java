package com.github.guoyyit.contractabicodecjava.api;

import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import com.github.guoyyit.contractabicodecjava.model.SelectorCollision;
import java.util.List;

/** Local in-process API surface (also exposed over HTTP). */
public interface CodecApi {
    FunctionDefinition registerFunction(FunctionDefinition function);
    List<FunctionDefinition> registerFunctions(List<FunctionDefinition> functions);
    void assignAlias(String alias, String canonicalSignature);
    FunctionDefinition getFunction(String nameOrAliasOrSelector);
    List<SelectorCollision> getCollisions();
    byte[] encode(String functionNameOrAlias, List<Object> values);
    List<Object> decode(String functionNameOrAlias, byte[] data);
}
