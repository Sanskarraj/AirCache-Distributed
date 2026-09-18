package com.distcache.core;

import com.distcache.core.stats.CacheStats;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance O(1) Least Frequently Used (LFU) cache engine.
 *
 * Employs frequency buckets and a minimum frequency pointer to achieve O(1)
 * get, put, and eviction operations. Used as an eviction benchmark baseline
 * alongside LRU and Segmented LRU (SLRU).
 */
public class ClassicLfuCache implements CacheEngine {
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<ByteArrayKey, CacheEntry> cache = new HashMap<>();
    private final Map<ByteArrayKey, Integer> counts = new HashMap<>();
    private final Map<Integer, LinkedHashSet<ByteArrayKey>> frequencyBuckets = new HashMap<>();
    private int minFrequency = -1;

    private final CacheStats stats = new CacheStats();

    public ClassicLfuCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }
        this.capacity = capacity;
    }

    @Override
    public byte[] get(byte[] key) {
        if (key == null) return null;
        ByteArrayKey bKey = ByteArrayKey.of(key);

        lock.lock();
        try {
            CacheEntry entry = cache.get(bKey);
            if (entry == null) {
                stats.recordMiss();
                return null;
            }

            if (entry.isExpired()) {
                removeEntry(bKey);
                stats.recordExpiration();
                stats.recordMiss();
                return null;
            }

            stats.recordHit();
            incrementFrequency(bKey);
            return entry.getValue();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, -1L);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("Key and value cannot be null");
        }
        ByteArrayKey bKey = ByteArrayKey.of(key);

        lock.lock();
        try {
            CacheEntry existing = cache.get(bKey);
            if (existing != null) {
                existing.setValue(value, ttlMillis);
                incrementFrequency(bKey);
                stats.recordPut();
                return;
            }

            // Evict LFU element if at capacity
            if (cache.size() >= capacity) {
                evictLfu();
            }

            // Insert new entry with frequency = 1
            CacheEntry newEntry = new CacheEntry(key, value, bKey.hashCode(), ttlMillis);
            cache.put(bKey, newEntry);
            counts.put(bKey, 1);
            frequencyBuckets.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(bKey);
            minFrequency = 1;

            stats.recordPut();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(byte[] key) {
        if (key == null) return false;
        ByteArrayKey bKey = ByteArrayKey.of(key);

        lock.lock();
        try {
            if (cache.containsKey(bKey)) {
                removeEntry(bKey);
                stats.recordDelete();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean containsKey(byte[] key) {
        if (key == null) return false;
        ByteArrayKey bKey = ByteArrayKey.of(key);

        lock.lock();
        try {
            CacheEntry entry = cache.get(bKey);
            if (entry == null) return false;
            if (entry.isExpired()) {
                removeEntry(bKey);
                stats.recordExpiration();
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            cache.clear();
            counts.clear();
            frequencyBuckets.clear();
            minFrequency = -1;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats getStats() {
        return stats;
    }

    private void incrementFrequency(ByteArrayKey bKey) {
        int count = counts.getOrDefault(bKey, 1);
        counts.put(bKey, count + 1);

        LinkedHashSet<ByteArrayKey> currentBucket = frequencyBuckets.get(count);
        if (currentBucket != null) {
            currentBucket.remove(bKey);
            if (currentBucket.isEmpty()) {
                frequencyBuckets.remove(count);
                if (minFrequency == count) {
                    minFrequency++;
                }
            }
        }

        frequencyBuckets.computeIfAbsent(count + 1, k -> new LinkedHashSet<>()).add(bKey);
    }

    private void evictLfu() {
        LinkedHashSet<ByteArrayKey> minBucket = frequencyBuckets.get(minFrequency);
        if (minBucket == null || minBucket.isEmpty()) {
            return;
        }

        ByteArrayKey evictKey = minBucket.iterator().next();
        minBucket.remove(evictKey);
        if (minBucket.isEmpty()) {
            frequencyBuckets.remove(minFrequency);
        }

        counts.remove(evictKey);
        cache.remove(evictKey);
        stats.recordProbationaryEviction();
    }

    private void removeEntry(ByteArrayKey bKey) {
        cache.remove(bKey);
        Integer count = counts.remove(bKey);
        if (count != null) {
            LinkedHashSet<ByteArrayKey> bucket = frequencyBuckets.get(count);
            if (bucket != null) {
                bucket.remove(bKey);
                if (bucket.isEmpty()) {
                    frequencyBuckets.remove(count);
                }
            }
        }
    }
}
