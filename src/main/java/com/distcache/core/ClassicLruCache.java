package com.distcache.core;

import com.distcache.core.stats.CacheStats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Standard single-queue LRU cache used as a baseline benchmark comparison against SLRU.
 * Standard LRU suffers from cache-scan pollution because one-hit sequential keys
 * displace all hot working set items.
 */
public class ClassicLruCache implements CacheEngine {
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<ByteArrayKey, CacheEntry> map;
    private final CacheStats stats = new CacheStats();

    public ClassicLruCache(int capacity) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ByteArrayKey, CacheEntry> eldest) {
                if (size() > ClassicLruCache.this.capacity) {
                    stats.recordProbationaryEviction();
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    public byte[] get(byte[] key) {
        ByteArrayKey bKey = ByteArrayKey.of(key);
        lock.lock();
        try {
            CacheEntry entry = map.get(bKey);
            if (entry == null) {
                stats.recordMiss();
                return null;
            }
            if (entry.isExpired()) {
                map.remove(bKey);
                stats.recordExpiration();
                stats.recordMiss();
                return null;
            }
            stats.recordHit();
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
        ByteArrayKey bKey = ByteArrayKey.of(key);
        lock.lock();
        try {
            CacheEntry entry = new CacheEntry(key, value, bKey.hashCode(), ttlMillis);
            map.put(bKey, entry);
            stats.recordPut();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(byte[] key) {
        ByteArrayKey bKey = ByteArrayKey.of(key);
        lock.lock();
        try {
            CacheEntry removed = map.remove(bKey);
            if (removed != null) {
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
        ByteArrayKey bKey = ByteArrayKey.of(key);
        lock.lock();
        try {
            CacheEntry entry = map.get(bKey);
            return entry != null && !entry.isExpired();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return map.size();
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
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats getStats() {
        return stats;
    }
}
