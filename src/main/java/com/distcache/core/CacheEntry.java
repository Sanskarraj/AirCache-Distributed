package com.distcache.core;

import java.util.Arrays;

/**
 * Cache entry representing a key-value pair, access frequency, TTL,
 * and doubly-linked list pointers for O(1) LRU list operations.
 */
public class CacheEntry {
    private final byte[] key;
    private byte[] value;
    private final int keyHash;
    private long expireAtMillis; // -1 if no expiration
    private int accessCount;
    private boolean inProtected; // true if in protected segment, false if in probationary

    // Doubly linked list pointers within its segment
    CacheEntry prev;
    CacheEntry next;

    public CacheEntry(byte[] key, byte[] value, int keyHash, long ttlMillis) {
        this.key = key;
        this.value = value;
        this.keyHash = keyHash;
        this.expireAtMillis = (ttlMillis > 0) ? (System.currentTimeMillis() + ttlMillis) : -1L;
        this.accessCount = 1;
        this.inProtected = false;
    }

    public byte[] getKey() {
        return key;
    }

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value, long ttlMillis) {
        this.value = value;
        this.expireAtMillis = (ttlMillis > 0) ? (System.currentTimeMillis() + ttlMillis) : -1L;
    }

    public int getKeyHash() {
        return keyHash;
    }

    public boolean isExpired() {
        return expireAtMillis > 0 && System.currentTimeMillis() > expireAtMillis;
    }

    public long getExpireAtMillis() {
        return expireAtMillis;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public void recordAccess() {
        this.accessCount++;
    }

    public boolean isInProtected() {
        return inProtected;
    }

    public void setInProtected(boolean inProtected) {
        this.inProtected = inProtected;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheEntry that = (CacheEntry) o;
        return Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return keyHash;
    }
}
