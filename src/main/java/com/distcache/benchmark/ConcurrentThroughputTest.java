package com.distcache.benchmark;

import com.distcache.cluster.ClusterManager;
import com.distcache.cluster.ConsistentHashRouter;
import com.distcache.cluster.Node;
import com.distcache.core.SegmentedLruCache;
import com.distcache.network.NettyCacheServer;
import com.distcache.network.NettyMultiplexedClient;
import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.OpCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Concurrent stress-test measuring throughput (RPS) and p50, p90, p99 latencies
 * of the custom Netty binary multiplexed protocol.
 */
public class ConcurrentThroughputTest {
    private static final int PORT = 18001;
    private static final int CONCURRENCY = 16;
    private static final int OPERATIONS_PER_THREAD = 3000; // 48,000 total operations

    public static void main(String[] args) throws Exception {
        System.out.println("======================================================================");
        System.out.println("  BENCHMARK: Netty Binary Protocol Multiplexing & Latency (p50/p90/p99)");
        System.out.println("======================================================================");

        // 1. Start In-Process Cache Node
        Node node = new Node("test-node", "127.0.0.1", PORT, PORT + 1000);
        SegmentedLruCache cache = new SegmentedLruCache(50000, 32, 0.25f);
        ConsistentHashRouter router = new ConsistentHashRouter(256);
        NettyMultiplexedClient peerClient = new NettyMultiplexedClient();
        ClusterManager clusterManager = new ClusterManager(node, cache, null, router, peerClient);
        NettyCacheServer server = new NettyCacheServer("127.0.0.1", PORT, clusterManager);

        server.start();
        clusterManager.start(Collections.emptyList());

        // 2. Setup Client
        NettyMultiplexedClient client = new NettyMultiplexedClient();
        System.out.println("Prepopulating cache with 2,000 keys...");
        for (int i = 0; i < 2000; i++) {
            byte[] key = ("key-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] val = ("value-payload-" + i).getBytes(StandardCharsets.UTF_8);
            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                    OpCode.PUT, client.nextCorrelationId(), key, val, 0L);
            client.sendSync("127.0.0.1", PORT, req, 5000);
        }

        System.out.printf("Starting stress test: %d threads, %d ops/thread (%d total ops)...\n",
                CONCURRENCY, OPERATIONS_PER_THREAD, CONCURRENCY * OPERATIONS_PER_THREAD);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);
        List<long[]> threadLatencies = Collections.synchronizedList(new ArrayList<>());

        long startTime = System.nanoTime();

        for (int t = 0; t < CONCURRENCY; t++) {
            final int threadId = t;
            executor.submit(() -> {
                long[] latencies = new long[OPERATIONS_PER_THREAD];
                ThreadLocalRandom rng = ThreadLocalRandom.current();

                for (int i = 0; i < OPERATIONS_PER_THREAD; i++) {
                    int keyId = rng.nextInt(2000);
                    byte[] key = ("key-" + keyId).getBytes(StandardCharsets.UTF_8);

                    long opStart = System.nanoTime();
                    try {
                        if (i % 5 == 0) {
                            // 20% writes
                            byte[] val = ("updated-val-" + keyId).getBytes(StandardCharsets.UTF_8);
                            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                                    OpCode.PUT, client.nextCorrelationId(), key, val, 0L);
                            client.sendSync("127.0.0.1", PORT, req, 5000);
                        } else {
                            // 80% reads
                            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                                    OpCode.GET, client.nextCorrelationId(), key, null, 0L);
                            client.sendSync("127.0.0.1", PORT, req, 5000);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    long opEnd = System.nanoTime();
                    latencies[i] = opEnd - opStart;
                }

                threadLatencies.add(latencies);
                latch.countDown();
            });
        }

        latch.await();
        long totalDurationNs = System.nanoTime() - startTime;
        executor.shutdown();

        // Aggregate latencies
        int totalOps = CONCURRENCY * OPERATIONS_PER_THREAD;
        long[] allLatencies = new long[totalOps];
        int idx = 0;
        for (long[] arr : threadLatencies) {
            System.arraycopy(arr, 0, allLatencies, idx, arr.length);
            idx += arr.length;
        }
        Arrays.sort(allLatencies);

        double durationSec = totalDurationNs / 1_000_000_000.0;
        double throughputRps = totalOps / durationSec;

        double p50Ms = allLatencies[(int) (totalOps * 0.50)] / 1_000_000.0;
        double p90Ms = allLatencies[(int) (totalOps * 0.90)] / 1_000_000.0;
        double p99Ms = allLatencies[(int) (totalOps * 0.99)] / 1_000_000.0;
        double minMs = allLatencies[0] / 1_000_000.0;
        double maxMs = allLatencies[totalOps - 1] / 1_000_000.0;

        System.out.println("\n----------------- BENCHMARK THROUGHPUT & LATENCY RESULTS -----------------");
        System.out.printf("  Total Operations Completed: %d ops\n", totalOps);
        System.out.printf("  Total Duration:             %.3f seconds\n", durationSec);
        System.out.printf("  Throughput:                 %,.0f req/sec\n", throughputRps);
        System.out.println("  ------------------------------------------------------------------");
        System.out.printf("  Min Latency:                %6.3f ms\n", minMs);
        System.out.printf("  p50 Latency (median):       %6.3f ms\n", p50Ms);
        System.out.printf("  p90 Latency:                %6.3f ms\n", p90Ms);
        System.out.printf("  p99 Latency:                %6.3f ms\n", p99Ms);
        System.out.printf("  Max Latency:                %6.3f ms\n", maxMs);
        System.out.println("--------------------------------------------------------------------------");

        if (p99Ms < 5.0) {
            System.out.printf(">>> SUCCESS: Kept p99 read/write latencies low (%.3f ms) under concurrent load! <<<\n", p99Ms);
        }
        System.out.println("==========================================================================\n");

        // Clean shutdown
        client.close();
        server.close();
        clusterManager.close();
    }
}
