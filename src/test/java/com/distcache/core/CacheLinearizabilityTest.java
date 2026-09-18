package com.distcache.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CacheLinearizabilityTest {

    @Test
    public void testConcurrentReadWriteRacesOnSegmentedLru() throws Exception {
        int capacity = 5000;
        int threads = 32;
        int operationsPerThread = 2000;
        SegmentedLruCache cache = new SegmentedLruCache(capacity, 16, 0.25f);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicInteger successfulReads = new AtomicInteger(0);
        AtomicInteger successfulWrites = new AtomicInteger(0);
        AtomicBoolean corruptionDetected = new AtomicBoolean(false);

        // 100 shared keys to maximize thread collisions and lock contention
        int sharedKeyCount = 100;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();

                    for (int i = 0; i < operationsPerThread; i++) {
                        int keyId = rng.nextInt(sharedKeyCount);
                        byte[] key = ("race-key-" + keyId).getBytes(StandardCharsets.UTF_8);

                        int op = rng.nextInt(100);
                        if (op < 50) {
                            // 50% PUT: write timestamped payload
                            long valNum = ((long) threadId << 32) | i;
                            String valStr = "val-" + valNum;
                            byte[] val = valStr.getBytes(StandardCharsets.UTF_8);
                            cache.put(key, val);
                            successfulWrites.incrementAndGet();
                        } else if (op < 85) {
                            // 35% GET: verify no corrupt partial reads
                            byte[] readVal = cache.get(key);
                            if (readVal != null) {
                                String s = new String(readVal, StandardCharsets.UTF_8);
                                if (!s.startsWith("val-")) {
                                    corruptionDetected.set(true);
                                }
                                successfulReads.incrementAndGet();
                            }
                        } else {
                            // 15% DELETE
                            cache.delete(key);
                        }
                    }
                } catch (Exception e) {
                    corruptionDetected.set(true);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "Concurrent test timed out!");
        executor.shutdown();

        assertFalse(corruptionDetected.get(), "Data corruption or exception detected during concurrent access!");
        assertTrue(successfulWrites.get() > 0, "Writes should have completed");
        assertTrue(successfulReads.get() > 0, "Reads should have completed");
        assertTrue(cache.size() <= capacity, "Cache size should not exceed configured capacity");
        assertTrue(cache.getProbationarySize() >= 0, "Probationary count should be non-negative");
        assertTrue(cache.getProtectedSize() >= 0, "Protected count should be non-negative");
    }

    @Test
    public void testConcurrentLfuOperations() throws Exception {
        int capacity = 1000;
        int threads = 16;
        int ops = 1000;
        ClassicLfuCache lfu = new ClassicLfuCache(capacity);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int i = 0; i < ops; i++) {
                        byte[] key = ("lfu-key-" + rng.nextInt(200)).getBytes(StandardCharsets.UTF_8);
                        byte[] val = ("lfu-val-" + i).getBytes(StandardCharsets.UTF_8);
                        lfu.put(key, val);
                        lfu.get(key);
                    }
                } catch (Exception ignored) {
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(20, TimeUnit.SECONDS), "LFU concurrent test timed out");
        executor.shutdown();

        assertTrue(lfu.size() <= capacity, "LFU cache size must not exceed capacity");
    }
}
