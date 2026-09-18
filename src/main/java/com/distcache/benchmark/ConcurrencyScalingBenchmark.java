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
import java.util.*;
import java.util.concurrent.*;

/**
 * Benchmark measuring Concurrency Scaling and Deep Tail Latencies (p50 to p99.99):
 * Evaluates performance across 1, 2, 4, 8, 16, 32, and 64 concurrent client threads.
 */
public class ConcurrencyScalingBenchmark {
    private static final int PORT = 18090;
    private static final int OPS_PER_THREAD = 4000;
    private static final int WARMUP_OPS = 2000;

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   BENCHMARK: Concurrency Scaling & Deep Tail Latencies (1 to 64 Threads)       ");
        System.out.println("================================================================================");

        // 1. Start Server
        Node node = new Node("scaling-node", "127.0.0.1", PORT, PORT + 1000);
        SegmentedLruCache cache = new SegmentedLruCache(100_000, 32, 0.25f);
        ConsistentHashRouter router = new ConsistentHashRouter(256);
        NettyMultiplexedClient peerClient = new NettyMultiplexedClient();
        ClusterManager clusterManager = new ClusterManager(node, cache, null, router, peerClient);
        NettyCacheServer server = new NettyCacheServer("127.0.0.1", PORT, clusterManager);

        server.start();
        clusterManager.start(Collections.emptyList());

        NettyMultiplexedClient client = new NettyMultiplexedClient();

        // 2. Prepopulate 2,000 keys
        System.out.println("Prepopulating cache with 2,000 keys...");
        for (int i = 0; i < 2000; i++) {
            byte[] key = ("scale-key-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] val = ("scale-val-" + i).getBytes(StandardCharsets.UTF_8);
            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                    OpCode.PUT, client.nextCorrelationId(), key, val, 0L);
            client.sendSync("127.0.0.1", PORT, req, 5000);
        }

        // 3. JVM Warm-up
        System.out.println("Warming up JVM and Netty event loops...");
        for (int i = 0; i < WARMUP_OPS; i++) {
            byte[] key = ("scale-key-" + (i % 2000)).getBytes(StandardCharsets.UTF_8);
            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                    OpCode.GET, client.nextCorrelationId(), key, null, 0L);
            client.sendSync("127.0.0.1", PORT, req, 5000);
        }

        System.out.println("\n-------------------- CONCURRENCY SCALING & TAIL LATENCIES -------------------");
        System.out.printf("  %-8s | %-14s | %-8s | %-8s | %-8s | %-8s | %-8s\n",
                "Threads", "Throughput", "p50 (ms)", "p90 (ms)", "p99 (ms)", "p99.9", "Max (ms)");
        System.out.println("  " + "-".repeat(78));

        int[] threadLevels = {1, 2, 4, 8, 16, 32, 64};
        for (int threads : threadLevels) {
            ScaleResult result = runConcurrentLoad(client, threads, OPS_PER_THREAD);
            System.out.printf("  %-8d | %,10.0f ops/s | %8.3f | %8.3f | %8.3f | %8.3f | %8.3f\n",
                    threads, result.throughput, result.p50Ms, result.p90Ms, result.p99Ms, result.p999Ms, result.maxMs);
        }
        System.out.println("-----------------------------------------------------------------------------");

        // Clean shutdown
        client.close();
        peerClient.close();
        server.close();
        clusterManager.close();

        System.out.println("=============================================================================\n");
    }

    private static ScaleResult runConcurrentLoad(NettyMultiplexedClient client, int threads, int opsPerThread) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        List<long[]> threadLatencies = Collections.synchronizedList(new ArrayList<>());
        long startTime = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long[] latenciesNs = new long[opsPerThread];
                    ThreadLocalRandom rng = ThreadLocalRandom.current();

                    for (int i = 0; i < opsPerThread; i++) {
                        int keyId = rng.nextInt(2000);
                        byte[] key = ("scale-key-" + keyId).getBytes(StandardCharsets.UTF_8);

                        long t0 = System.nanoTime();
                        if (i % 5 == 0) {
                            byte[] val = ("val-" + keyId).getBytes(StandardCharsets.UTF_8);
                            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                                    OpCode.PUT, client.nextCorrelationId(), key, val, 0L);
                            client.sendSync("127.0.0.1", PORT, req, 5000);
                        } else {
                            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                                    OpCode.GET, client.nextCorrelationId(), key, null, 0L);
                            client.sendSync("127.0.0.1", PORT, req, 5000);
                        }
                        long t1 = System.nanoTime();
                        latenciesNs[i] = t1 - t0;
                    }
                    threadLatencies.add(latenciesNs);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        long totalDurationNs = System.nanoTime() - startTime;
        executor.shutdown();

        int totalOps = threads * opsPerThread;
        long[] all = new long[totalOps];
        int idx = 0;
        for (long[] arr : threadLatencies) {
            System.arraycopy(arr, 0, all, idx, arr.length);
            idx += arr.length;
        }
        Arrays.sort(all);

        double sec = totalDurationNs / 1_000_000_000.0;
        double throughput = totalOps / sec;

        return new ScaleResult(
                throughput,
                all[(int) (totalOps * 0.50)] / 1_000_000.0,
                all[(int) (totalOps * 0.90)] / 1_000_000.0,
                all[(int) (totalOps * 0.99)] / 1_000_000.0,
                all[(int) (totalOps * 0.999)] / 1_000_000.0,
                all[totalOps - 1] / 1_000_000.0
        );
    }

    record ScaleResult(double throughput, double p50Ms, double p90Ms, double p99Ms, double p999Ms, double maxMs) {}
}
