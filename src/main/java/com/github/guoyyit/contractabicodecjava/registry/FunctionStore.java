package com.github.guoyyit.contractabicodecjava.registry;

import com.github.guoyyit.contractabicodecjava.model.FunctionDefinition;
import java.util.List;

/**
 * Crash-safe persistence for function definitions. Writes go through a temp file
 * followed by an atomic move, so a crash never leaves a half-written/corrupt state.
 */
public interface FunctionStore {
    void persist(List<FunctionDefinition> functions);
    List<FunctionDefinition> load();
}
