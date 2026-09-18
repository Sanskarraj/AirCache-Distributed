package com.distcache.core;

import java.util.Arrays;

/**
 * Immutable wrapper around byte[] for efficient use as HashMap keys.
 */
public final class ByteArrayKey {
    private final byte[] bytes;
    private final int hashCode;

    public ByteArrayKey(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Key bytes cannot be null");
        }
        this.bytes = bytes;
        this.hashCode = Arrays.hashCode(bytes);
    }

    public static ByteArrayKey of(byte[] bytes) {
        return new ByteArrayKey(bytes);
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ByteArrayKey that = (ByteArrayKey) o;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return new String(bytes);
    }
}
