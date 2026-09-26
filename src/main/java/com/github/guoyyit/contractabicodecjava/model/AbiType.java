package com.github.guoyyit.contractabicodecjava.model;

import java.util.List;

/**
 * ABI type model. Sealed hierarchy covering scalar, fixed bytes, dynamic bytes/string,
 * static/dynamic arrays and nested tuples.
 */
public sealed interface AbiType
        permits AbiType.UInt, AbiType.Int, AbiType.Address, AbiType.Bool,
                AbiType.FixedBytes, AbiType.DynamicBytes, AbiType.String,
                AbiType.Array, AbiType.FixedArray, AbiType.Tuple {

    /** Canonical ABI type name (the form used in a function signature). */
    java.lang.String canonicalName();

    /** Whether the type is dynamically encoded. */
    boolean isDynamic();

    record UInt(int bits) implements AbiType {
        public UInt {
            if (bits < 8 || bits > 256 || bits % 8 != 0) {
                throw new IllegalArgumentException("invalid uint bits: " + bits);
            }
        }
        @Override public java.lang.String canonicalName() { return "uint" + bits; }
        @Override public boolean isDynamic() { return false; }
    }

    record Int(int bits) implements AbiType {
        public Int {
            if (bits < 8 || bits > 256 || bits % 8 != 0) {
                throw new IllegalArgumentException("invalid int bits: " + bits);
            }
        }
        @Override public java.lang.String canonicalName() { return "int" + bits; }
        @Override public boolean isDynamic() { return false; }
    }

    record Address() implements AbiType {
        @Override public java.lang.String canonicalName() { return "address"; }
        @Override public boolean isDynamic() { return false; }
    }

    record Bool() implements AbiType {
        @Override public java.lang.String canonicalName() { return "bool"; }
        @Override public boolean isDynamic() { return false; }
    }

    record FixedBytes(int length) implements AbiType {
        public FixedBytes {
            if (length < 1 || length > 32) {
                throw new IllegalArgumentException("invalid bytes length: " + length);
            }
        }
        @Override public java.lang.String canonicalName() { return "bytes" + length; }
        @Override public boolean isDynamic() { return false; }
    }

    record DynamicBytes() implements AbiType {
        @Override public java.lang.String canonicalName() { return "bytes"; }
        @Override public boolean isDynamic() { return true; }
    }

    record String() implements AbiType {
        @Override public java.lang.String canonicalName() { return "string"; }
        @Override public boolean isDynamic() { return true; }
    }

    /** Dynamic array: T[] */
    record Array(AbiType element) implements AbiType {
        @Override public java.lang.String canonicalName() { return element.canonicalName() + "[]"; }
        @Override public boolean isDynamic() { return true; }
    }

    /** Fixed length array: T[k] */
    record FixedArray(AbiType element, int length) implements AbiType {
        public FixedArray {
            if (length <= 0) {
                throw new IllegalArgumentException("invalid fixed array length: " + length);
            }
        }
        @Override public java.lang.String canonicalName() { return element.canonicalName() + "[" + length + "]"; }
        @Override public boolean isDynamic() { return element.isDynamic(); }
    }

    record Tuple(List<AbiType> components) implements AbiType {
        public Tuple {
            components = List.copyOf(components);
            if (components.isEmpty()) {
                throw new IllegalArgumentException("tuple must have components");
            }
        }
        @Override public java.lang.String canonicalName() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < components.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(components.get(i).canonicalName());
            }
            return sb.append(')').toString();
        }
        @Override public boolean isDynamic() {
            return components.stream().anyMatch(AbiType::isDynamic);
        }
    }
}
