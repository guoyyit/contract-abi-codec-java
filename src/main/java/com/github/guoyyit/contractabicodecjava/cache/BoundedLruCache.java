package com.github.guoyyit.contractabicodecjava.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有容量上限的线程安全 LRU 缓存：超出容量时淘汰最久未使用项，
 * 长期运行不会无界增长。
 */
public class BoundedLruCache<K, V> {

    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public BoundedLruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(16, 0.75f, true);
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
        // LinkedHashMap(accessOrder=true) 迭代顺序即访问顺序，头部是最久未使用项
        while (map.size() > capacity) {
            var it = map.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }
}
