package com.github.guoyyit.contractabicodecjava.encoding;

import com.github.guoyyit.contractabicodecjava.error.CodecException;
import com.github.guoyyit.contractabicodecjava.error.ErrorCode;
import com.github.guoyyit.contractabicodecjava.model.AbiType;
import com.github.guoyyit.contractabicodecjava.model.TypeLayout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounded, thread-safe LRU cache for resolved {@link TypeLayout}s.
 * The cache never grows beyond {@code capacity}; {@link #invalidate()} clears all
 * entries and is called by the registry whenever any function definition changes.
 */
public final class TypeLayoutCache {

    private final int capacity;
    private final LinkedHashMap<String, TypeLayout> map;

    public TypeLayoutCache(int capacity) {
        if (capacity <= 0) {
            throw new CodecException(ErrorCode.LAYOUT_INVALID, "cache capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TypeLayout> eldest) {
                return size() > TypeLayoutCache.this.capacity;
            }
        };
    }

    public TypeLayout getOrCompute(List<AbiType> types, Supplier<TypeLayout> loader) {
        String key = key(types);
        synchronized (map) {
            TypeLayout cached = map.get(key);
            if (cached != null) {
                return cached;
            }
        }
        // Compute outside the lock; layouts are deterministic and safe to compute concurrently.
        TypeLayout layout = loader.get();
        synchronized (map) {
            map.putIfAbsent(key, layout);
            return map.get(key);
        }
    }

    public void invalidate() {
        synchronized (map) {
            map.clear();
        }
    }

    public int size() {
        synchronized (map) {
            return map.size();
        }
    }

    public Map<String, TypeLayout> snapshot() {
        synchronized (map) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        }
    }

    private static String key(List<AbiType> types) {
        StringBuilder sb = new StringBuilder();
        for (AbiType t : types) {
            sb.append(t.canonicalName()).append(';');
        }
        return sb.toString();
    }
}
