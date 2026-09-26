package com.github.guoyyit.contractabicodecjava.model;

import java.util.List;

/**
 * Diagnostics for one selector that maps to more than one distinct signature.
 * Ordering of signatures is stable (sorted lexicographically).
 */
public record SelectorCollision(String selector, List<String> signatures) {
    public SelectorCollision {
        signatures = List.copyOf(signatures);
    }
}
