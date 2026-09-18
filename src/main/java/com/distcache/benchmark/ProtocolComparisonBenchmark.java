package com.distcache.benchmark;

import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.NettyFrameCodec;
import com.distcache.protocol.OpCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Benchmark comparing Custom Binary Wire Protocol vs. JSON over TCP:
 * 1. Wire Framing Overhead & Payload Size (bytes per message).
 * 2. Serialization & Deserialization CPU Time (p50/p90/p99 latency).
 * 3. Serialization Throughput (messages/sec).
 */
public class ProtocolComparisonBenchmark {
    private static final int ITERATIONS = 500_000;
    private static final int WARMUP = 50_000;
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    public static class JsonMessage {
        public String op;
        public long correlationId;
        public String key;
        public String value;
        public long ttl;
        public int status;

        public JsonMessage() {}

        public JsonMessage(String op, long correlationId, String key, String value, long ttl, int status) {
            this.op = op;
            this.correlationId = correlationId;
            this.key = key;
            this.value = value;
            this.ttl = ttl;
            this.status = status;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   BENCHMARK: Custom Binary Framing vs. JSON-Over-TCP Protocol Comparison       ");
        System.out.println("================================================================================");

        byte[] sampleKey = "user:session:98412".getBytes(StandardCharsets.UTF_8);
        byte[] sampleVal = "{\"userId\":98412,\"roles\":[\"admin\",\"read\"],\"token\":\"abc123xyz\"}".getBytes(StandardCharsets.UTF_8);

        // 1. Frame Size Comparison
        BinaryProtocolFrame binaryFrame = BinaryProtocolFrame.createRequest(OpCode.PUT, 1001L, sampleKey, sampleVal, 60000L);
        ByteBuf buf = Unpooled.buffer();
        NettyFrameCodec.encodeFrame(binaryFrame, buf);
        byte[] binaryWireBytes = new byte[buf.readableBytes()];
        buf.readBytes(binaryWireBytes);
        buf.release();

        JsonMessage jsonMsg = new JsonMessage("PUT", 1001L,
                new String(sampleKey, StandardCharsets.UTF_8),
                Base64.getEncoder().encodeToString(sampleVal),
                60000L, 0);
        byte[] jsonWireBytes = jsonMapper.writeValueAsBytes(jsonMsg);

        System.out.println("\n--- 1. Wire Overhead & Frame Size ---");
        System.out.printf("  Custom Binary Frame Size: %d bytes (19-byte compact header + CRC32)\n", binaryWireBytes.length);
        System.out.printf("  JSON-over-TCP Frame Size: %d bytes (Text framing + Base64 encoding)\n", jsonWireBytes.length);
        System.out.printf("  >>> Wire Size Reduction:  %.1f%% smaller payloads with Custom Binary! <<<\n",
                (1.0 - (double) binaryWireBytes.length / jsonWireBytes.length) * 100.0);

        // Warmup
        for (int i = 0; i < WARMUP; i++) {
            benchmarkBinaryRoundTrip(binaryFrame);
            benchmarkJsonRoundTrip(jsonMsg);
        }

        // 2. Serialization & Deserialization Benchmark
        System.out.printf("\n--- 2. Serialization & Deserialization CPU Performance (%,d Ops) ---\n", ITERATIONS);

        long[] binaryTimesNs = new long[ITERATIONS];
        long binaryStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            benchmarkBinaryRoundTrip(binaryFrame);
            binaryTimesNs[i] = System.nanoTime() - t0;
        }
        long binaryDuration = System.nanoTime() - binaryStart;
        Arrays.sort(binaryTimesNs);

        long[] jsonTimesNs = new long[ITERATIONS];
        long jsonStart = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            benchmarkJsonRoundTrip(jsonMsg);
            jsonTimesNs[i] = System.nanoTime() - t0;
        }
        long jsonDuration = System.nanoTime() - jsonStart;
        Arrays.sort(jsonTimesNs);

        double binDurationSec = binaryDuration / 1_000_000_000.0;
        double jsonDurationSec = jsonDuration / 1_000_000_000.0;

        long binThroughput = (long) (ITERATIONS / binDurationSec);
        long jsonThroughput = (long) (ITERATIONS / jsonDurationSec);

        System.out.printf("  %-25s | %-16s | %-8s | %-8s | %-8s | %-8s\n",
                "Protocol", "Throughput", "p50 (ns)", "p90 (ns)", "p99 (ns)", "Max (ns)");
        System.out.println("  " + "-".repeat(78));

        System.out.printf("  %-25s | %,12d ops/s | %,8d | %,8d | %,8d | %,8d\n",
                "Custom Binary Protocol", binThroughput,
                binaryTimesNs[(int) (ITERATIONS * 0.50)],
                binaryTimesNs[(int) (ITERATIONS * 0.90)],
                binaryTimesNs[(int) (ITERATIONS * 0.99)],
                binaryTimesNs[ITERATIONS - 1]);

        System.out.printf("  %-25s | %,12d ops/s | %,8d | %,8d | %,8d | %,8d\n",
                "JSON-over-TCP (Jackson)", jsonThroughput,
                jsonTimesNs[(int) (ITERATIONS * 0.50)],
                jsonTimesNs[(int) (ITERATIONS * 0.90)],
                jsonTimesNs[(int) (ITERATIONS * 0.99)],
                jsonTimesNs[ITERATIONS - 1]);

        System.out.println("  " + "-".repeat(78));
        System.out.printf(">>> Custom Binary Protocol is %.1fx faster with %.1f%% lower CPU serialization cost! <<<\n",
                (double) binThroughput / jsonThroughput,
                (1.0 - (double) binaryTimesNs[(int) (ITERATIONS * 0.50)] / jsonTimesNs[(int) (ITERATIONS * 0.50)]) * 100.0);
        System.out.println("================================================================================\n");
    }

    private static void benchmarkBinaryRoundTrip(BinaryProtocolFrame frame) {
        ByteBuf buf = Unpooled.buffer(128);
        NettyFrameCodec.encodeFrame(frame, buf);
        BinaryProtocolFrame decoded = NettyFrameCodec.decodeFrame(buf);
        if (decoded == null) throw new IllegalStateException();
        buf.release();
    }

    private static void benchmarkJsonRoundTrip(JsonMessage msg) throws Exception {
        byte[] bytes = jsonMapper.writeValueAsBytes(msg);
        JsonMessage decoded = jsonMapper.readValue(bytes, JsonMessage.class);
        if (decoded == null) throw new IllegalStateException();
    }
}
