package com.distcache.benchmark;

import com.distcache.cluster.ClusterManager;
import com.distcache.cluster.ConsistentHashRouter;
import com.distcache.cluster.Node;
import com.distcache.core.SegmentedLruCache;
import com.distcache.network.NettyCacheServer;
import com.distcache.network.NettyMultiplexedClient;
import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.JsonFrameCodec;
import com.distcache.protocol.OpCode;
import com.sun.management.OperatingSystemMXBean;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enterprise End-to-End Netty Socket Benchmark:
 * Measures actual wire-to-wire client -> TCP socket -> Netty Server -> Cache -> Response latency:
 * Compares Custom Binary Protocol vs. JSON over TCP with p99.9 and CPU load sampling.
 */
public class EndToEndNettyBenchmark {
    private static final int BINARY_PORT = 18101;
    private static final int JSON_PORT = 18102;
    private static final int CONCURRENCY = 8;
    private static final int OPS_PER_THREAD = 2500; // 20,000 total ops per protocol
    private static final OperatingSystemMXBean osMxBean =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   ENTERPRISE BENCHMARK: End-to-End Socket Latency (Binary vs. JSON over TCP)   ");
        System.out.println("   (Real Client -> Loopback TCP Socket -> Netty Server Pipeline -> Response)    ");
        System.out.println("================================================================================");

        SegmentedLruCache cache = new SegmentedLruCache(50_000, 16, 0.25f);

        // Prepopulate cache
        for (int i = 0; i < 2000; i++) {
            byte[] k = ("k-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] v = ("v-payload-" + i).getBytes(StandardCharsets.UTF_8);
            cache.put(k, v);
        }

        // 1. Start Binary Protocol Server
        Node node = new Node("bin-node", "127.0.0.1", BINARY_PORT, BINARY_PORT + 1000);
        ConsistentHashRouter router = new ConsistentHashRouter(256);
        NettyMultiplexedClient peerClient = new NettyMultiplexedClient();
        ClusterManager clusterManager = new ClusterManager(node, cache, null, router, peerClient);
        NettyCacheServer binaryServer = new NettyCacheServer("127.0.0.1", BINARY_PORT, clusterManager);
        binaryServer.start();
        clusterManager.start(Collections.emptyList());

        // 2. Start JSON Protocol Server
        EventLoopGroup jsonBoss = new NioEventLoopGroup(1);
        EventLoopGroup jsonWorkers = new NioEventLoopGroup(4);
        ServerBootstrap jsonBootstrap = new ServerBootstrap()
                .group(jsonBoss, jsonWorkers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new JsonFrameCodec());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<JsonFrameCodec.JsonPayload>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, JsonFrameCodec.JsonPayload msg) {
                                byte[] k = msg.key().getBytes(StandardCharsets.UTF_8);
                                if ("GET".equals(msg.op())) {
                                    byte[] val = cache.get(k);
                                    String vStr = val != null ? new String(val, StandardCharsets.UTF_8) : null;
                                    ctx.writeAndFlush(new JsonFrameCodec.JsonPayload("RESP", msg.correlationId(), msg.key(), vStr, 0, val != null ? 0 : 1));
                                } else {
                                    byte[] v = msg.value() != null ? msg.value().getBytes(StandardCharsets.UTF_8) : new byte[0];
                                    cache.put(k, v);
                                    ctx.writeAndFlush(new JsonFrameCodec.JsonPayload("RESP", msg.correlationId(), msg.key(), null, 0, 0));
                                }
                            }
                        });
                    }
                });
        Channel jsonChannel = jsonBootstrap.bind("127.0.0.1", JSON_PORT).sync().channel();

        // 3. Benchmark Custom Binary TCP
        System.out.printf("\nRunning End-to-End Custom Binary Protocol Benchmark (%,d Ops)...\n", CONCURRENCY * OPS_PER_THREAD);
        BenchResult binaryResult = benchmarkBinaryTcp(BINARY_PORT);

        // 4. Benchmark JSON over TCP
        System.out.printf("Running End-to-End JSON-Over-TCP Protocol Benchmark (%,d Ops)...\n", CONCURRENCY * OPS_PER_THREAD);
        BenchResult jsonResult = benchmarkJsonTcp(JSON_PORT);

        // Print Comparison Table
        System.out.println("\n---------------- END-TO-END WIRE-LEVEL LATENCY COMPARISON ----------------");
        System.out.printf("  %-24s | %-13s | %-7s | %-7s | %-7s | %-7s | %-7s | %-8s\n",
                "Wire Protocol", "Throughput", "p50(ms)", "p90(ms)", "p99(ms)", "p99.9", "Max(ms)", "CPU Load");
        System.out.println("  " + "-".repeat(95));

        printResult("Custom Binary TCP", binaryResult);
        printResult("JSON-Over-TCP (Jackson)", jsonResult);
        System.out.println("  " + "-".repeat(95));

        double tputGain = (binaryResult.throughput / jsonResult.throughput);
        double latencyReduction = (1.0 - binaryResult.p50Ms / jsonResult.p50Ms) * 100.0;
        System.out.printf(">>> Custom Binary Protocol achieves %.2fx higher end-to-end socket throughput\n", tputGain);
        System.out.printf("    with %.1f%% lower p50 wire-to-wire round-trip latency! <<<\n", latencyReduction);
        System.out.println("================================================================================");

        // Teardown
        peerClient.close();
        binaryServer.close();
        clusterManager.close();
        jsonChannel.close();
        jsonBoss.shutdownGracefully();
        jsonWorkers.shutdownGracefully();
    }

    private static BenchResult benchmarkBinaryTcp(int port) throws Exception {
        NettyMultiplexedClient client = new NettyMultiplexedClient();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);
        List<long[]> threadTimes = Collections.synchronizedList(new ArrayList<>());

        double cpuBefore = osMxBean.getProcessCpuLoad();
        long startNs = System.nanoTime();

        for (int t = 0; t < CONCURRENCY; t++) {
            executor.submit(() -> {
                long[] times = new long[OPS_PER_THREAD];
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int kId = rng.nextInt(2000);
                    byte[] key = ("k-" + kId).getBytes(StandardCharsets.UTF_8);

                    long t0 = System.nanoTime();
                    try {
                        if (i % 5 == 0) {
                            byte[] val = ("v-upd-" + i).getBytes(StandardCharsets.UTF_8);
                            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                                    OpCode.PUT, client.nextCorrelationId(), key, val, 0L);
                            client.sendSync("127.0.0.1", port, req, 5000);
                        } else {
                            BinaryProtocolFrame req = BinaryProtocolFrame.createRequest(
                                    OpCode.GET, client.nextCorrelationId(), key, null, 0L);
                            client.sendSync("127.0.0.1", port, req, 5000);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    times[i] = System.nanoTime() - t0;
                }
                threadTimes.add(times);
                latch.countDown();
            });
        }

        latch.await();
        long durationNs = System.nanoTime() - startNs;
        double cpuAfter = osMxBean.getProcessCpuLoad();
        double avgCpu = Math.max(0.0, (cpuBefore + cpuAfter) / 2.0) * 100.0;
        executor.shutdown();
        client.close();

        return computeMetrics(threadTimes, durationNs, avgCpu);
    }

    private static BenchResult benchmarkJsonTcp(int port) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(2);
        ConcurrentHashMap<Long, CompletableFuture<JsonFrameCodec.JsonPayload>> pending = new ConcurrentHashMap<>();
        AtomicLong corrGen = new AtomicLong(1);

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new JsonFrameCodec());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<JsonFrameCodec.JsonPayload>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, JsonFrameCodec.JsonPayload msg) {
                                CompletableFuture<JsonFrameCodec.JsonPayload> f = pending.remove(msg.correlationId());
                                if (f != null) f.complete(msg);
                            }
                        });
                    }
                });

        Channel channel = b.connect("127.0.0.1", port).sync().channel();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch latch = new CountDownLatch(CONCURRENCY);
        List<long[]> threadTimes = Collections.synchronizedList(new ArrayList<>());

        double cpuBefore = osMxBean.getProcessCpuLoad();
        long startNs = System.nanoTime();

        for (int t = 0; t < CONCURRENCY; t++) {
            executor.submit(() -> {
                long[] times = new long[OPS_PER_THREAD];
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int kId = rng.nextInt(2000);
                    String key = "k-" + kId;
                    long corr = corrGen.incrementAndGet();

                    long t0 = System.nanoTime();
                    CompletableFuture<JsonFrameCodec.JsonPayload> f = new CompletableFuture<>();
                    pending.put(corr, f);

                    if (i % 5 == 0) {
                        channel.writeAndFlush(new JsonFrameCodec.JsonPayload("PUT", corr, key, "v-upd-" + i, 0, 0));
                    } else {
                        channel.writeAndFlush(new JsonFrameCodec.JsonPayload("GET", corr, key, null, 0, 0));
                    }

                    try {
                        f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    times[i] = System.nanoTime() - t0;
                }
                threadTimes.add(times);
                latch.countDown();
            });
        }

        latch.await();
        long durationNs = System.nanoTime() - startNs;
        double cpuAfter = osMxBean.getProcessCpuLoad();
        double avgCpu = Math.max(0.0, (cpuBefore + cpuAfter) / 2.0) * 100.0;
        executor.shutdown();
        channel.close();
        group.shutdownGracefully();

        return computeMetrics(threadTimes, durationNs, avgCpu);
    }

    private static BenchResult computeMetrics(List<long[]> threadTimes, long durationNs, double cpuLoad) {
        int totalOps = CONCURRENCY * OPS_PER_THREAD;
        long[] all = new long[totalOps];
        int idx = 0;
        for (long[] arr : threadTimes) {
            System.arraycopy(arr, 0, all, idx, arr.length);
            idx += arr.length;
        }
        Arrays.sort(all);

        double durationSec = durationNs / 1_000_000_000.0;
        double throughput = totalOps / durationSec;

        return new BenchResult(
                throughput,
                all[(int) (totalOps * 0.50)] / 1_000_000.0,
                all[(int) (totalOps * 0.90)] / 1_000_000.0,
                all[(int) (totalOps * 0.99)] / 1_000_000.0,
                all[(int) (totalOps * 0.999)] / 1_000_000.0,
                all[totalOps - 1] / 1_000_000.0,
                cpuLoad
        );
    }

    private static void printResult(String name, BenchResult r) {
        System.out.printf("  %-24s | %,10.0f ops/s | %7.3f | %7.3f | %7.3f | %7.3f | %7.3f | %6.1f%%\n",
                name, r.throughput, r.p50Ms, r.p90Ms, r.p99Ms, r.p999Ms, r.maxMs, r.cpuPct);
    }

    record BenchResult(double throughput, double p50Ms, double p90Ms, double p99Ms, double p999Ms, double maxMs, double cpuPct) {}
}
