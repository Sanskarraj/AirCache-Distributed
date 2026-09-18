package com.distcache.persistence;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

/**
 * Storage abstraction for persisting cache mutation events.
 */
public interface PersistenceEngine extends Closeable {

    /**
     * Appends a PUT operation record to the persistent log.
     */
    void appendPut(byte[] key, byte[] value, long ttlMillis) throws IOException;

    /**
     * Appends a DELETE operation record to the persistent log.
     */
    void appendDelete(byte[] key) throws IOException;

    /**
     * Flushes buffered I/O writes to disk.
     */
    void flush() throws IOException;

    /**
     * Recovers cache state from the persistent log into memory.
     *
     * @return Map of recovered active keys and values
     */
    Map<byte[], byte[]> recover() throws IOException;
}
