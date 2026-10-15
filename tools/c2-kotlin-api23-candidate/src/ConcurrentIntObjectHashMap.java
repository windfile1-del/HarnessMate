package org.jetbrains.kotlin.com.intellij.util.containers;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ConcurrentIntObjectHashMap<V> implements ConcurrentIntObjectMap<V> {
    private final ConcurrentHashMap<Integer, V> delegate = new ConcurrentHashMap<>();

    ConcurrentIntObjectHashMap() {
    }

    public int size() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    public V get(int key) {
        return delegate.get(key);
    }

    public boolean containsValue(Object value) {
        return delegate.containsValue(value);
    }

    public V put(int key, V value) {
        return delegate.put(key, value);
    }

    public V remove(int key) {
        return delegate.remove(key);
    }

    public void clear() {
        delegate.clear();
    }

    public Collection<V> values() {
        return delegate.values();
    }

    public Set<IntObjectMap.Entry<V>> entrySet() {
        return new AbstractSet<IntObjectMap.Entry<V>>() {
            public Iterator<IntObjectMap.Entry<V>> iterator() {
                final Iterator<Map.Entry<Integer, V>> iterator = delegate.entrySet().iterator();
                return new Iterator<IntObjectMap.Entry<V>>() {
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    public IntObjectMap.Entry<V> next() {
                        return new IntEntryAdapter<V>(iterator.next());
                    }

                    public void remove() {
                        iterator.remove();
                    }
                };
            }

            public int size() {
                return delegate.size();
            }

            public void clear() {
                delegate.clear();
            }
        };
    }

    public int hashCode() {
        return delegate.hashCode();
    }

    public String toString() {
        return delegate.toString();
    }

    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof IntObjectMap<?>)) {
            return false;
        }
        IntObjectMap<?> otherMap = (IntObjectMap<?>) other;
        if (delegate.size() != otherMap.entrySet().size()) {
            return false;
        }
        for (Map.Entry<Integer, V> entry : delegate.entrySet()) {
            if (!Objects.equals(entry.getValue(), otherMap.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public V putIfAbsent(int key, V value) {
        return delegate.putIfAbsent(key, value);
    }

    public boolean remove(int key, Object value) {
        return delegate.remove(key, value);
    }

    public boolean replace(int key, V oldValue, V newValue) {
        return delegate.replace(key, oldValue, newValue);
    }

    public Enumeration<V> elements() {
        return Collections.enumeration(delegate.values());
    }

    public long mappingCount() {
        // Keep this adapter on the API-23 ConcurrentHashMap surface.
        return delegate.size();
    }

    public V cacheOrGet(int key, V value) {
        V existing = delegate.putIfAbsent(key, value);
        return existing == null ? value : existing;
    }

    private static final class IntEntryAdapter<V> implements IntObjectMap.Entry<V> {
        private final Map.Entry<Integer, V> entry;

        private IntEntryAdapter(Map.Entry<Integer, V> entry) {
            this.entry = entry;
        }

        public int getKey() {
            return entry.getKey();
        }

        public V getValue() {
            return entry.getValue();
        }

        public boolean equals(Object other) {
            if (!(other instanceof IntObjectMap.Entry<?>)) {
                return false;
            }
            IntObjectMap.Entry<?> otherEntry = (IntObjectMap.Entry<?>) other;
            return entry.getKey().intValue() == otherEntry.getKey()
                && Objects.equals(entry.getValue(), otherEntry.getValue());
        }

        public int hashCode() {
            return Integer.valueOf(entry.getKey()).hashCode() ^ Objects.hashCode(entry.getValue());
        }
    }
}
