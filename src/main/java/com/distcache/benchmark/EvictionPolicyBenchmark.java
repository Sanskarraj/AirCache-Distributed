package com.distcache.benchmark;

import com.distcache.core.CacheEngine;
import com.distcache.core.ClassicLfuCache;
import com.distcache.core.ClassicLruCache;
import com.distcache.core.SegmentedLruCache;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enterprise Eviction Policy Benchmark:
 * Compares Classic LRU, Segmented LRU (SLRU), and O(1) LFU across:
 * 1. Variable Hot-Set Sizes (100 to 1,000 items in a 1,000 capacity cache).
 * 2. Variable Cold-Scan Attack Sizes (100 to 100,000 keys).
 * 3. Mixed Realistic Workloads (80/15/5, 60/20/20, 90/10 skewed traffic).
 */
public class EvictionPolicyBenchmark {
    private static final int CAPACITY = 1000;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   ENTERPRISE BENCHMARK: Multi-Eviction Comparison (Classic LRU vs SLRU vs LFU) ");
        System.out.println("================================================================================");

        // 1. Variable Hot Set Sizes
        testVariableHotSets();

        // 2. Variable Scan Sizes
        testVariableScanSizes();

        // 3. Mixed Realistic Workloads
        testMixedWorkloads();

        System.out.println("================================================================================\n");
    }

    public static void testVariableHotSets() {
        System.out.println("\n--- 1. Hot Set Size Variation (Capacity: 1000, Scan Attack: 10,000 Cold Keys) ---");
        System.out.printf("  %-12s | %-16s | %-16s | %-16s\n",
                "Hot Set Size", "Classic LRU Hit%", "SLRU (25/75) Hit%", "O(1) LFU Hit%");
        System.out.println("  " + "-".repeat(68));

        int[] hotSets = {100, 250, 500, 750, 900, 1000};
        for (int hs : hotSets) {
            double lruHit = runHotSetScanTest(new ClassicLruCache(CAPACITY), hs, 10000);
            double slruHit = runHotSetScanTest(new SegmentedLruCache(CAPACITY, 16, 0.25f), hs, 10000);
            double lfuHit = runHotSetScanTest(new ClassicLfuCache(CAPACITY), hs, 10000);

            System.out.printf("  %-12d | %15.2f%% | %15.2f%% | %15.2f%%\n",
                    hs, lruHit * 100.0, slruHit * 100.0, lfuHit * 100.0);
        }
    }

    public static void testVariableScanSizes() {
        System.out.println("\n--- 2. Cold Scan Attack Size Variation (Capacity: 1000, Hot Set: 750 Keys) ---");
        System.out.printf("  %-14s | %-16s | %-16s | %-16s\n",
                "Scan Key Count", "Classic LRU Hit%", "SLRU (25/75) Hit%", "O(1) LFU Hit%");
        System.out.println("  " + "-".repeat(70));

        int[] scanSizes = {100, 500, 1000, 5000, 10000, 100000};
        for (int scan : scanSizes) {
            double lruHit = runHotSetScanTest(new ClassicLruCache(CAPACITY), 750, scan);
            double slruHit = runHotSetScanTest(new SegmentedLruCache(CAPACITY, 16, 0.25f), 750, scan);
            double lfuHit = runHotSetScanTest(new ClassicLfuCache(CAPACITY), 750, scan);

            System.out.printf("  %-14d | %15.2f%% | %15.2f%% | %15.2f%%\n",
                    scan, lruHit * 100.0, slruHit * 100.0, lfuHit * 100.0);
        }
    }

    public static void testMixedWorkloads() {
        System.out.println("\n--- 3. Realistic Mixed Workload Hit Rates (50,000 Requests) ---");
        System.out.printf("  %-30s | %-14s | %-14s | %-14s\n",
                "Traffic Profile", "Classic LRU", "SLRU (25/75)", "O(1) LFU");
        System.out.println("  " + "-".repeat(78));

        // Profile A: 80% Hot (200 keys), 15% Warm (800 keys), 5% Cold (10,000 keys)
        runMixedTest("80% Hot, 15% Warm, 5% Cold", 200, 800, 10000, 80, 15, 5);

        // Profile B: 60% Hot (300 keys), 20% Warm (700 keys), 20% Cold (10,000 keys)
        runMixedTest("60% Hot, 20% Warm, 20% Cold", 300, 700, 10000, 60, 20, 20);

        // Profile C: 90% Hot (500 keys), 10% Cold Burst Scan (20,000 keys)
        runMixedTest("90% Hot, 10% Cold Scan Burst", 500, 0, 20000, 90, 0, 10);
    }

    private static double runHotSetScanTest(CacheEngine cache, int hotSetSize, int scanSize) {
        // 1. Populate and access hot keys
        for (int i = 0; i < hotSetSize; i++) {
            byte[] key = ("hot-item-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] val = ("hot-val-" + i).getBytes(StandardCharsets.UTF_8);
            cache.put(key, val);
            cache.get(key); // access again
        }

        // 2. Inject Cold Scan
        for (int i = 0; i < scanSize; i++) {
            byte[] key = ("scan-item-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] val = ("scan-val-" + i).getBytes(StandardCharsets.UTF_8);
            cache.put(key, val);
        }

        // 3. Measure Hit Retention on Hot Set
        cache.getStats().reset();
        for (int i = 0; i < hotSetSize; i++) {
            byte[] key = ("hot-item-" + i).getBytes(StandardCharsets.UTF_8);
            cache.get(key);
        }

        return cache.getStats().getHitRate();
    }

    private static void runMixedTest(String name, int hotKeys, int warmKeys, int coldKeys,
                                     int hotPct, int warmPct, int coldPct) {
        ClassicLruCache lru = new ClassicLruCache(CAPACITY);
        SegmentedLruCache slru = new SegmentedLruCache(CAPACITY, 16, 0.25f);
        ClassicLfuCache lfu = new ClassicLfuCache(CAPACITY);

        int totalRequests = 50_000;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int i = 0; i < totalRequests; i++) {
            int roll = rng.nextInt(100);
            byte[] key;
            if (roll < hotPct) {
                key = ("hot-" + rng.nextInt(hotKeys)).getBytes(StandardCharsets.UTF_8);
            } else if (roll < hotPct + warmPct && warmKeys > 0) {
                key = ("warm-" + rng.nextInt(warmKeys)).getBytes(StandardCharsets.UTF_8);
            } else {
                key = ("cold-" + rng.nextInt(coldKeys)).getBytes(StandardCharsets.UTF_8);
            }
            byte[] val = "v".getBytes(StandardCharsets.UTF_8);

            // Access all 3 caches
            accessCache(lru, key, val);
            accessCache(slru, key, val);
            accessCache(lfu, key, val);
        }

        System.out.printf("  %-30s | %13.2f%% | %13.2f%% | %13.2f%%\n",
                name,
                lru.getStats().getHitRate() * 100.0,
                slru.getStats().getHitRate() * 100.0,
                lfu.getStats().getHitRate() * 100.0);
    }

    private static void accessCache(CacheEngine cache, byte[] key, byte[] val) {
        byte[] existing = cache.get(key);
        if (existing == null) {
            cache.put(key, val);
        }
    }
}
