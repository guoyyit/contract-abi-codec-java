package com.github.guoyyit.contractabicodecjava.model;

import java.util.List;

/**
 * Immutable definition of a contract function. The selector is derived from
 * {@code name(canonicalParamTypes)} and is set by the registry during registration.
 */
public final class FunctionDefinition {

    private final String name;
    private final List<Parameter> inputs;
    private final List<Parameter> outputs;
    private final String selector; // 0x + 8 hex chars, null until computed

    public FunctionDefinition(String name, List<Parameter> inputs, List<Parameter> outputs) {
        this(name, inputs, outputs, null);
    }

    private FunctionDefinition(String name, List<Parameter> inputs, List<Parameter> outputs, String selector) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("function name is required");
        }
        this.name = name;
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
        this.outputs = outputs == null ? List.of() : List.copyOf(outputs);
        this.selector = selector;
    }

    public FunctionDefinition withSelector(String selector) {
        return new FunctionDefinition(name, inputs, outputs, selector);
    }

    public String name() { return name; }
    public List<Parameter> inputs() { return inputs; }
    public List<Parameter> outputs() { return outputs; }
    /** Selector assigned by the registry; {@code null} before registration. */
    public String selector() { return selector; }
}
