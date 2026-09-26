package com.github.guoyyit.contractabicodecjava.cache;

import com.github.guoyyit.contractabicodecjava.abi.AbiType;
import com.github.guoyyit.contractabicodecjava.abi.TypeParser;

import java.util.List;
import java.util.Optional;

/**
 * 类型布局与选择器缓存。
 *
 * 所有键都携带注册表版本号：注册表更新后版本号递增，旧版本条目
 * 不再被命中，并随 LRU 淘汰被回收 —— 既保证失效正确性，又保证容量有界。
 */
public class LayoutCache {

    private record Key(String kind, long version, String subject) {}

    private final BoundedLruCache<Key, Object> cache;

    public LayoutCache(int capacity) {
        this.cache = new BoundedLruCache<>(capacity);
    }

    public Optional<byte[]> selector(String canonicalSignature, long registryVersion) {
        Object v = cache.get(new Key("selector", registryVersion, canonicalSignature));
        return v == null ? Optional.empty() : Optional.of(((byte[]) v).clone());
    }

    public void putSelector(String canonicalSignature, long registryVersion, byte[] selector) {
        cache.put(new Key("selector", registryVersion, canonicalSignature), selector.clone());
    }

    /** 类型列表的静态头布局（每个类型的头字节数）。 */
    public Optional<long[]> headLayout(List<AbiType> types, long registryVersion) {
        Object v = cache.get(new Key("layout", registryVersion, layoutSubject(types)));
        return v == null ? Optional.empty() : Optional.of(((long[]) v).clone());
    }

    public void putHeadLayout(List<AbiType> types, long registryVersion, long[] layout) {
        cache.put(new Key("layout", registryVersion, layoutSubject(types)), layout.clone());
    }

    private static String layoutSubject(List<AbiType> types) {
        StringBuilder sb = new StringBuilder();
        for (AbiType t : types) {
            sb.append(TypeParser.canonical(t)).append(';');
        }
        return sb.toString();
    }

    public int size() {
        return cache.size();
    }

    public int capacity() {
        return cache.capacity();
    }
}
