package io.siggi.morganabot.util;

import java.util.HashMap;
import java.util.Map;

public class MapBuilder<K,V> {
    private final Map<K,V> map = new HashMap<>();
    public MapBuilder() {
    }
    public MapBuilder<K,V> put(K key, V value) {
        map.put(key, value);
        return this;
    }
    public MapBuilder<K,V> putAll(Map<K,V> map) {
        this.map.putAll(map);
        return this;
    }
    public Map<K,V> build() {
        return map;
    }
}
