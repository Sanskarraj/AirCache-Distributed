package com.distcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

public class SegmentedLruCacheTest {

    private SegmentedLruCache cache;

    @BeforeEach
    public void setUp() {
        // Small capacity to easily test SLRU promotions/evictions
        cache = new SegmentedLruCache(10, 1, 0.3f); // 1 partition, 3 probationary, 7 protected
    }

    @Test
    public void testBasicPutGetDelete() {
        byte[] k1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "val1".getBytes(StandardCharsets.UTF_8);

        cache.put(k1, v1);
        assertArrayEquals(v1, cache.get(k1));
        assertTrue(cache.containsKey(k1));

        assertTrue(cache.delete(k1));
        assertNull(cache.get(k1));
        assertFalse(cache.containsKey(k1));
    }

    @Test
    public void testSlruPromotionAndProtection() {
        // Initially inserted keys land in probationary
        byte[] k1 = "hotKey1".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "hotVal1".getBytes(StandardCharsets.UTF_8);
        cache.put(k1, v1);
        assertEquals(1, cache.getProbationarySize());
        assertEquals(0, cache.getProtectedSize());

        // Accessing k1 a second time promotes it to protected!
        byte[] retrieved = cache.get(k1);
        assertArrayEquals(v1, retrieved);
        assertEquals(0, cache.getProbationarySize());
        assertEquals(1, cache.getProtectedSize());
        assertEquals(1, cache.getStats().getPromotions());

        // Fill up probationary with 3 cold keys
        for (int i = 0; i < 3; i++) {
            cache.put(("cold" + i).getBytes(), ("cval" + i).getBytes());
        }
        assertEquals(3, cache.getProbationarySize());
        assertEquals(1, cache.getProtectedSize());

        // Add 1 more cold key -> evicts cold0 from probationary, but hotKey1 in protected is SAFE!
        cache.put("coldX".getBytes(), "valX".getBytes());
        assertNotNull(cache.get(k1), "Hot key in protected segment must NOT be evicted by cold scans!");
        assertEquals(1, cache.getProtectedSize());
    }

    @Test
    public void testTtlExpiration() throws InterruptedException {
        byte[] k = "expKey".getBytes(StandardCharsets.UTF_8);
        byte[] v = "expVal".getBytes(StandardCharsets.UTF_8);

        cache.put(k, v, 50); // 50ms TTL
        assertNotNull(cache.get(k));

        Thread.sleep(80);
        assertNull(cache.get(k), "Expired key must return null");
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        SegmentedLruCache concurrentCache = new SegmentedLruCache(1000, 16, 0.25f);
        int threads = 8;
        int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        byte[] key = ("conc-key-" + (i % 200)).getBytes();
                        byte[] val = ("val-" + threadId + "-" + i).getBytes();
                        concurrentCache.put(key, val);
                        concurrentCache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertTrue(concurrentCache.size() > 0);
    }
}
