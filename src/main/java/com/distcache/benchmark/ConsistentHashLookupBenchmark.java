package com.distcache.benchmark;

import com.distcache.cluster.ArrayBinarySearchRouter;
import com.distcache.cluster.ConsistentHashRouter;
import com.distcache.cluster.Node;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Benchmark measuring critical-path consistent hash ring lookup throughput and tail latency:
 * TreeMap ceilingEntry() vs. Primitive Array Binary Search (ArrayBinarySearchRouter).
 */
public class ConsistentHashLookupBenchmark {
    private static final int NODES = 100;
    private static final int VNODES = 256;
    private static final int LOOKUP_COUNT = 2_000_000;
    private static final int WARMUP_COUNT = 200_000;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("   BENCHMARK: Consistent Hash Ring Lookup Latency & Throughput Comparison       ");
        System.out.println("   (100 Nodes, 256 Vnodes = 25,600 Tokens | 2,000,000 Critical-Path Lookups)   ");
        System.out.println("================================================================================");

        // Pre-generate keys
        byte[][] keys = new byte[LOOKUP_COUNT][];
        for (int i = 0; i < LOOKUP_COUNT; i++) {
            keys[i] = ("cache-lookup-benchmark-key-" + i).getBytes(StandardCharsets.UTF_8);
        }

        // 1. Setup TreeMap Router
        ConsistentHashRouter treeMapRouter = new ConsistentHashRouter(VNODES);
        for (int i = 1; i <= NODES; i++) {
            treeMapRouter.addNode(new Node("node-" + i, "10.0.0." + i, 8000 + i, 9000 + i));
        }

        // 2. Setup Array Binary Search Router
        ArrayBinarySearchRouter arrayRouter = new ArrayBinarySearchRouter(VNODES);
        for (int i = 1; i <= NODES; i++) {
            arrayRouter.addNode(new Node("node-" + i, "10.0.0." + i, 8000 + i, 9000 + i));
        }

        // Warmup JVM
        for (int i = 0; i < WARMUP_COUNT; i++) {
            treeMapRouter.route(keys[i % keys.length]);
            arrayRouter.route(keys[i % keys.length]);
        }

        // Benchmark TreeMap Router
        LookupResult treeMapResult = runLookupTest(keys, k -> treeMapRouter.route(k));

        // Benchmark Array Binary Search Router
        LookupResult arrayResult = runLookupTest(keys, k -> arrayRouter.route(k));

        System.out.println("\n----------------- ROUTING LOOKUP PERFORMANCE COMPARISON ----------------");
        System.out.printf("  %-25s | %-16s | %-8s | %-8s | %-8s | %-8s\n",
                "Implementation", "Throughput", "p50 (ns)", "p90 (ns)", "p99 (ns)", "p99.9 (ns)");
        System.out.println("  " + "-".repeat(78));

        printRow("TreeMap ceilingEntry()", treeMapResult);
        printRow("Array Binary Search", arrayResult);
        System.out.println("------------------------------------------------------------------------");

        double speedup = (double) arrayResult.throughput / treeMapResult.throughput;
        System.out.printf(">>> Array Binary Search achieved %.2fx higher routing throughput! <<<\n", speedup);
        System.out.println("========================================================================\n");
    }

    private static LookupResult runLookupTest(byte[][] keys, RouterFunc func) {
        long[] latenciesNs = new long[keys.length];
        long totalStart = System.nanoTime();

        for (int i = 0; i < keys.length; i++) {
            long t0 = System.nanoTime();
            Node node = func.route(keys[i]);
            long t1 = System.nanoTime();
            latenciesNs[i] = t1 - t0;
            if (node == null) throw new IllegalStateException();
        }

        long totalDurationNs = System.nanoTime() - totalStart;
        Arrays.sort(latenciesNs);

        double durationSec = totalDurationNs / 1_000_000_000.0;
        long throughput = (long) (keys.length / durationSec);

        return new LookupResult(
                throughput,
                latenciesNs[(int) (keys.length * 0.50)],
                latenciesNs[(int) (keys.length * 0.90)],
                latenciesNs[(int) (keys.length * 0.99)],
                latenciesNs[(int) (keys.length * 0.999)]
        );
    }

    private static void printRow(String name, LookupResult r) {
        System.out.printf("  %-25s | %,12d ops/s | %,8d | %,8d | %,8d | %,8d\n",
                name, r.throughput, r.p50Ns, r.p90Ns, r.p99Ns, r.p999Ns);
    }

    @FunctionalInterface
    interface RouterFunc {
        Node route(byte[] key);
    }

    record LookupResult(long throughput, long p50Ns, long p90Ns, long p99Ns, long p999Ns) {}
}
