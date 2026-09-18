package com.distcache.core;

import com.distcache.core.stats.CacheStats;

/**
 * Interface defining the operations supported by the in-memory cache engine.
 */
public interface CacheEngine {

    /**
     * Retrieves value for a given key.
     *
     * @param key binary key
     * @return binary value or null if miss or expired
     */
    byte[] get(byte[] key);

    /**
     * Puts a key-value pair into the cache with default (no) expiration.
     */
    void put(byte[] key, byte[] value);

    /**
     * Puts a key-value pair into the cache with a specified TTL.
     *
     * @param key binary key
     * @param value binary value
     * @param ttlMillis TTL in milliseconds (<= 0 for no expiration)
     */
    void put(byte[] key, byte[] value, long ttlMillis);

    /**
     * Deletes a key from the cache.
     *
     * @param key binary key
     * @return true if key was present and deleted, false otherwise
     */
    boolean delete(byte[] key);

    /**
     * Checks if key exists and is not expired.
     */
    boolean containsKey(byte[] key);

    /**
     * Returns total number of active entries currently held in the cache.
     */
    int size();

    /**
     * Returns the maximum configured capacity of the cache.
     */
    int capacity();

    /**
     * Clears all entries from the cache.
     */
    void clear();

    /**
     * Returns cache statistics.
     */
    CacheStats getStats();
}
