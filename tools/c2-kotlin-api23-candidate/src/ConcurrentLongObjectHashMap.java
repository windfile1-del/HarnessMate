package org.jetbrains.kotlin.com.intellij.util.containers;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ConcurrentLongObjectHashMap<V> implements ConcurrentLongObjectMap<V> {
    private final ConcurrentHashMap<Long, V> delegate = new ConcurrentHashMap<>();

    ConcurrentLongObjectHashMap() {
    }

    public int size() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    public V get(long key) {
        return delegate.get(key);
    }

    public V put(long key, V value) {
        return delegate.put(key, value);
    }

    public V remove(long key) {
        return delegate.remove(key);
    }

    public void clear() {
        delegate.clear();
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
        if (!(other instanceof ConcurrentLongObjectMap<?>)) {
            return false;
        }
        ConcurrentLongObjectMap<?> otherMap = (ConcurrentLongObjectMap<?>) other;
        if (delegate.size() != countEntries(otherMap.entries())) {
            return false;
        }
        for (Map.Entry<Long, V> entry : delegate.entrySet()) {
            if (!entry.getValue().equals(otherMap.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public V putIfAbsent(long key, V value) {
        return delegate.putIfAbsent(key, value);
    }

    public boolean remove(long key, Object value) {
        return delegate.remove(key, value);
    }

    public long mappingCount() {
        // Keep this adapter on the API-23 ConcurrentHashMap surface.
        return delegate.size();
    }

    public Iterable<ConcurrentLongObjectMap.LongEntry<V>> entries() {
        return new Iterable<ConcurrentLongObjectMap.LongEntry<V>>() {
            public Iterator<ConcurrentLongObjectMap.LongEntry<V>> iterator() {
                final Iterator<Map.Entry<Long, V>> iterator = delegate.entrySet().iterator();
                return new Iterator<ConcurrentLongObjectMap.LongEntry<V>>() {
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    public ConcurrentLongObjectMap.LongEntry<V> next() {
                        return new LongEntryAdapter<V>(iterator.next());
                    }

                    public void remove() {
                        iterator.remove();
                    }
                };
            }
        };
    }

    private static long countEntries(Iterable<? extends ConcurrentLongObjectMap.LongEntry<?>> entries) {
        long count = 0;
        for (ConcurrentLongObjectMap.LongEntry<?> ignored : entries) {
            count++;
        }
        return count;
    }

    private static final class LongEntryAdapter<V> implements ConcurrentLongObjectMap.LongEntry<V> {
        private final Map.Entry<Long, V> entry;

        private LongEntryAdapter(Map.Entry<Long, V> entry) {
            this.entry = entry;
        }

        public long getKey() {
            return entry.getKey();
        }

        public V getValue() {
            return entry.getValue();
        }
    }
}
