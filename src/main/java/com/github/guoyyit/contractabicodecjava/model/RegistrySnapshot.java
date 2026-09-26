package com.github.guoyyit.contractabicodecjava.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable, point-in-time view of the registry. Encoding/decoding calls hold a
 * reference to one snapshot and can never observe a partially registered state.
 */
public record RegistrySnapshot(
        Map<String, FunctionDefinition> byName,
        Map<String, FunctionDefinition> bySelector,
        Map<String, List<FunctionDefinition>> collisions,
        Map<String, String> aliases,
        List<SelectorCollision> collisionList,
        long version) {

    public static RegistrySnapshot empty(long version) {
        return new RegistrySnapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of(), version);
    }
}
