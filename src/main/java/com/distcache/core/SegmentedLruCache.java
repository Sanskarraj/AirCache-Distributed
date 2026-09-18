package com.distcache.core;

import com.distcache.core.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance, multi-threaded Segmented LRU (SLRU) cache engine.
 *
 * Employs fine-grained striped concurrency across multiple partitions to eliminate lock contention.
 * Each partition maintains two LRU queues:
 * 1. Probationary segment (default 25% capacity): for newly inserted items.
 * 2. Protected segment (default 75% capacity): for items that have been accessed more than once.
 *
 * This design provides strong protection against cache-scan pollution under high concurrent load,
 * ensuring that sequential scans of cold keys do not evict hot working set items.
 */
public class SegmentedLruCache implements CacheEngine {
    private static final Logger logger = LoggerFactory.getLogger(SegmentedLruCache.class);

    private final int totalCapacity;
    private final float probationaryRatio;
    private final Partition[] partitions;
    private final int partitionMask;
    private final CacheStats stats = new CacheStats();

    public SegmentedLruCache(int totalCapacity) {
        this(totalCapacity, 16, 0.25f);
    }

    public SegmentedLruCache(int totalCapacity, int partitionCount, float probationaryRatio) {
        if (totalCapacity <= 0) {
            throw new IllegalArgumentException("totalCapacity must be > 0");
        }
        if (probationaryRatio <= 0.0f || probationaryRatio >= 1.0f) {
            throw new IllegalArgumentException("probationaryRatio must be between 0 and 1");
        }

        // Ensure partitionCount is a power of 2 for fast bitwise masking
        int pCount = 1;
        while (pCount < partitionCount) {
            pCount <<= 1;
        }

        this.totalCapacity = totalCapacity;
        this.probationaryRatio = probationaryRatio;
        this.partitionMask = pCount - 1;
        this.partitions = new Partition[pCount];

        int perPartitionCapacity = Math.max(1, totalCapacity / pCount);
        for (int i = 0; i < pCount; i++) {
            this.partitions[i] = new Partition(perPartitionCapacity, probationaryRatio, stats);
        }

        logger.info("Initialized SegmentedLruCache with totalCapacity={}, partitions={}, perPartitionCap={}",
                totalCapacity, pCount, perPartitionCapacity);
    }

    private Partition getPartition(byte[] key) {
        int hash = ByteArrayKey.of(key).hashCode();
        return partitions[hash & partitionMask];
    }

    @Override
    public byte[] get(byte[] key) {
        if (key == null) return null;
        return getPartition(key).get(key);
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
        getPartition(key).put(key, value, ttlMillis);
    }

    @Override
    public boolean delete(byte[] key) {
        if (key == null) return false;
        return getPartition(key).delete(key);
    }

    @Override
    public boolean containsKey(byte[] key) {
        if (key == null) return false;
        return getPartition(key).containsKey(key);
    }

    @Override
    public int size() {
        int total = 0;
        for (Partition p : partitions) {
            total += p.size();
        }
        return total;
    }

    @Override
    public int capacity() {
        return totalCapacity;
    }

    @Override
    public void clear() {
        for (Partition p : partitions) {
            p.clear();
        }
    }

    @Override
    public CacheStats getStats() {
        return stats;
    }

    public int getProbationarySize() {
        int total = 0;
        for (Partition p : partitions) {
            total += p.getProbationaryCount();
        }
        return total;
    }

    public int getProtectedSize() {
        int total = 0;
        for (Partition p : partitions) {
            total += p.getProtectedCount();
        }
        return total;
    }

    /**
     * Internal striped partition managing its own locks, maps, and SLRU queues.
     */
    static class Partition {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<ByteArrayKey, CacheEntry> map = new HashMap<>();
        private final DoublyLinkedList probationaryList = new DoublyLinkedList();
        private final DoublyLinkedList protectedList = new DoublyLinkedList();

        private final int probationaryCapacity;
        private final int protectedCapacity;
        private final CacheStats stats;

        Partition(int capacity, float probationaryRatio, CacheStats stats) {
            this.stats = stats;
            int probCap = Math.max(1, Math.round(capacity * probationaryRatio));
            this.probationaryCapacity = probCap;
            this.protectedCapacity = Math.max(1, capacity - probCap);
        }

        byte[] get(byte[] key) {
            ByteArrayKey bKey = ByteArrayKey.of(key);
            lock.lock();
            try {
                CacheEntry entry = map.get(bKey);
                if (entry == null) {
                    stats.recordMiss();
                    return null;
                }

                if (entry.isExpired()) {
                    removeEntry(entry);
                    stats.recordExpiration();
                    stats.recordMiss();
                    return null;
                }

                stats.recordHit();
                entry.recordAccess();

                if (entry.isInProtected()) {
                    // Already in protected: refresh to head
                    protectedList.moveToHead(entry);
                } else {
                    // Hit in probationary: promote to protected segment!
                    probationaryList.remove(entry);
                    entry.setInProtected(true);
                    protectedList.addToHead(entry);
                    stats.recordPromotion();

                    // Check if protected overflows -> demote LRU of protected to probationary
                    if (protectedList.size() > protectedCapacity) {
                        CacheEntry demoted = protectedList.removeTail();
                        if (demoted != null) {
                            demoted.setInProtected(false);
                            probationaryList.addToHead(demoted);
                            stats.recordProtectedDemotion();
                        }
                    }

                    // Check if probationary overflows -> evict LRU of probationary
                    if (probationaryList.size() > probationaryCapacity) {
                        CacheEntry evicted = probationaryList.removeTail();
                        if (evicted != null) {
                            map.remove(ByteArrayKey.of(evicted.getKey()));
                            stats.recordProbationaryEviction();
                        }
                    }
                }

                return entry.getValue();
            } finally {
                lock.unlock();
            }
        }

        void put(byte[] key, byte[] value, long ttlMillis) {
            ByteArrayKey bKey = ByteArrayKey.of(key);
            lock.lock();
            try {
                CacheEntry existing = map.get(bKey);
                if (existing != null) {
                    existing.setValue(value, ttlMillis);
                    existing.recordAccess();

                    if (existing.isInProtected()) {
                        protectedList.moveToHead(existing);
                    } else {
                        // Promote on overwrite/re-access
                        probationaryList.remove(existing);
                        existing.setInProtected(true);
                        protectedList.addToHead(existing);
                        stats.recordPromotion();

                        if (protectedList.size() > protectedCapacity) {
                            CacheEntry demoted = protectedList.removeTail();
                            if (demoted != null) {
                                demoted.setInProtected(false);
                                probationaryList.addToHead(demoted);
                                stats.recordProtectedDemotion();
                            }
                        }

                        if (probationaryList.size() > probationaryCapacity) {
                            CacheEntry evicted = probationaryList.removeTail();
                            if (evicted != null) {
                                map.remove(ByteArrayKey.of(evicted.getKey()));
                                stats.recordProbationaryEviction();
                            }
                        }
                    }
                    stats.recordPut();
                    return;
                }

                // New entry -> starts in probationary
                CacheEntry newEntry = new CacheEntry(key, value, bKey.hashCode(), ttlMillis);
                newEntry.setInProtected(false);
                map.put(bKey, newEntry);
                probationaryList.addToHead(newEntry);
                stats.recordPut();

                // If probationary overflows -> evict its LRU element
                if (probationaryList.size() > probationaryCapacity) {
                    CacheEntry evicted = probationaryList.removeTail();
                    if (evicted != null) {
                        map.remove(ByteArrayKey.of(evicted.getKey()));
                        stats.recordProbationaryEviction();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        boolean delete(byte[] key) {
            ByteArrayKey bKey = ByteArrayKey.of(key);
            lock.lock();
            try {
                CacheEntry entry = map.remove(bKey);
                if (entry != null) {
                    if (entry.isInProtected()) {
                        protectedList.remove(entry);
                    } else {
                        probationaryList.remove(entry);
                    }
                    stats.recordDelete();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        boolean containsKey(byte[] key) {
            ByteArrayKey bKey = ByteArrayKey.of(key);
            lock.lock();
            try {
                CacheEntry entry = map.get(bKey);
                if (entry == null) return false;
                if (entry.isExpired()) {
                    removeEntry(entry);
                    stats.recordExpiration();
                    return false;
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        private void removeEntry(CacheEntry entry) {
            map.remove(ByteArrayKey.of(entry.getKey()));
            if (entry.isInProtected()) {
                protectedList.remove(entry);
            } else {
                probationaryList.remove(entry);
            }
        }

        int size() {
            lock.lock();
            try {
                return map.size();
            } finally {
                lock.unlock();
            }
        }

        int getProbationaryCount() {
            lock.lock();
            try {
                return probationaryList.size();
            } finally {
                lock.unlock();
            }
        }

        int getProtectedCount() {
            lock.lock();
            try {
                return protectedList.size();
            } finally {
                lock.unlock();
            }
        }

        void clear() {
            lock.lock();
            try {
                map.clear();
                probationaryList.clear();
                protectedList.clear();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Intrusive doubly linked list for O(1) head insertion, tail removal, and repositioning.
     */
    static class DoublyLinkedList {
        private final CacheEntry head = new CacheEntry(new byte[0], new byte[0], 0, -1);
        private final CacheEntry tail = new CacheEntry(new byte[0], new byte[0], 0, -1);
        private int count = 0;

        DoublyLinkedList() {
            head.next = tail;
            tail.prev = head;
        }

        void addToHead(CacheEntry entry) {
            entry.next = head.next;
            entry.prev = head;
            head.next.prev = entry;
            head.next = entry;
            count++;
        }

        void remove(CacheEntry entry) {
            if (entry.prev != null && entry.next != null) {
                entry.prev.next = entry.next;
                entry.next.prev = entry.prev;
                entry.prev = null;
                entry.next = null;
                count--;
            }
        }

        void moveToHead(CacheEntry entry) {
            remove(entry);
            addToHead(entry);
        }

        CacheEntry removeTail() {
            if (count == 0 || tail.prev == head) {
                return null;
            }
            CacheEntry toRemove = tail.prev;
            remove(toRemove);
            return toRemove;
        }

        int size() {
            return count;
        }

        void clear() {
            head.next = tail;
            tail.prev = head;
            count = 0;
        }
    }
}
