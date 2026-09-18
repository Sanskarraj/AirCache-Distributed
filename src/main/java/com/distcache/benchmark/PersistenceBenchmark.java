package com.distcache.benchmark;

import com.distcache.core.SegmentedLruCache;
import com.distcache.persistence.AppendOnlyLogPersistence;
import com.distcache.persistence.WriteBehindBuffer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * Benchmark comparing Storage & Persistence Modes:
 * 1. Pure In-Memory (no persistence overhead).
 * 2. Asynchronous Write-Behind WAL Buffer (non-blocking, batched disk I/O).
 * 3. Synchronous Write-Through (blocking fsync on critical request path).
 * 4. WAL Replay Recovery Throughput (entries restored / second).
 */
public class PersistenceBenchmark {
    private static final int OPERATIONS = 50_000;

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   BENCHMARK: Persistence Modes (In-Memory vs Write-Behind vs Write-Through)    ");
        System.out.println("================================================================================");

        Path tempDir = Files.createTempDirectory("distcache-persist-bench");

        try {
            // 1. Pure In-Memory Mode
            PerfResult memResult = benchmarkInMemory(OPERATIONS);

            // 2. Asynchronous Write-Behind WAL Mode
            Path wbWal = tempDir.resolve("write-behind.aof");
            PerfResult wbResult = benchmarkWriteBehind(wbWal, OPERATIONS);

            // 3. Synchronous Write-Through WAL Mode (1,000 ops sample to avoid excessive wait)
            Path wtWal = tempDir.resolve("write-through.aof");
            PerfResult wtResult = benchmarkWriteThrough(wtWal, 2_000);

            System.out.println("\n----------------- PERSISTENCE STORAGE MODE COMPARISON ------------------");
            System.out.printf("  %-25s | %-16s | %-10s | %-10s\n",
                    "Storage Mode", "Throughput", "p50 Latency", "p99 Latency");
            System.out.println("  " + "-".repeat(68));

            printPerf("Pure In-Memory (No I/O)", memResult);
            printPerf("Async Write-Behind Buffer", wbResult);
            printPerf("Sync Write-Through (WAL)", wtResult);
            System.out.println("------------------------------------------------------------------------");

            double asyncAdvantage = (double) wbResult.throughput / wtResult.throughput;
            System.out.printf(">>> Async Write-Behind is %.1fx faster than synchronous write-through! <<<\n", asyncAdvantage);

            // 4. WAL Replay Recovery Speed
            testWalRecoverySpeed(wbWal);

        } finally {
            // Cleanup temp files
            File[] files = tempDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            tempDir.toFile().delete();
        }

        System.out.println("================================================================================\n");
    }

    private static PerfResult benchmarkInMemory(int ops) {
        SegmentedLruCache cache = new SegmentedLruCache(ops, 16, 0.25f);
        long[] latencies = new long[ops];
        byte[] val = "persist-payload-bytes".getBytes(StandardCharsets.UTF_8);

        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            byte[] key = ("mem-key-" + i).getBytes(StandardCharsets.UTF_8);
            long opStart = System.nanoTime();
            cache.put(key, val);
            latencies[i] = System.nanoTime() - opStart;
        }
        long duration = System.nanoTime() - t0;

        return calculateResult(latencies, duration, ops);
    }

    private static PerfResult benchmarkWriteBehind(Path walPath, int ops) throws Exception {
        SegmentedLruCache cache = new SegmentedLruCache(ops, 16, 0.25f);
        AppendOnlyLogPersistence persistence = new AppendOnlyLogPersistence(walPath);
        WriteBehindBuffer writeBehind = new WriteBehindBuffer(persistence, 65536, 1000, 20);

        long[] latencies = new long[ops];
        byte[] val = "persist-payload-bytes".getBytes(StandardCharsets.UTF_8);

        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            byte[] key = ("wb-key-" + i).getBytes(StandardCharsets.UTF_8);
            long opStart = System.nanoTime();
            cache.put(key, val);
            writeBehind.enqueuePut(key, val, -1L);
            latencies[i] = System.nanoTime() - opStart;
        }
        long duration = System.nanoTime() - t0;

        // Drain & close
        writeBehind.close();

        return calculateResult(latencies, duration, ops);
    }

    private static PerfResult benchmarkWriteThrough(Path walPath, int ops) throws Exception {
        SegmentedLruCache cache = new SegmentedLruCache(ops, 16, 0.25f);
        AppendOnlyLogPersistence persistence = new AppendOnlyLogPersistence(walPath);

        long[] latencies = new long[ops];
        byte[] val = "persist-payload-bytes".getBytes(StandardCharsets.UTF_8);

        long t0 = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            byte[] key = ("wt-key-" + i).getBytes(StandardCharsets.UTF_8);
            long opStart = System.nanoTime();
            cache.put(key, val);
            persistence.appendPut(key, val, -1L);
            persistence.flush(); // Synchronous disk flush
            latencies[i] = System.nanoTime() - opStart;
        }
        long duration = System.nanoTime() - t0;

        persistence.close();
        return calculateResult(latencies, duration, ops);
    }

    private static void testWalRecoverySpeed(Path walPath) throws Exception {
        System.out.println("\n--- 4. WAL Replay Recovery Throughput Benchmark ---");
        long fileSize = Files.size(walPath);
        AppendOnlyLogPersistence recoveryPersistence = new AppendOnlyLogPersistence(walPath);

        long t0 = System.nanoTime();
        Map<byte[], byte[]> recovered = recoveryPersistence.recover();
        long durationNs = System.nanoTime() - t0;
        recoveryPersistence.close();

        double durationSec = durationNs / 1_000_000_000.0;
        double replayRate = recovered.size() / durationSec;

        System.out.printf("  WAL File Size:       %,d Bytes (%.2f MB)\n", fileSize, fileSize / (1024.0 * 1024.0));
        System.out.printf("  Restored Entries:    %,d active keys\n", recovered.size());
        System.out.printf("  Recovery Time:       %.2f ms\n", durationNs / 1_000_000.0);
        System.out.printf("  Replay Speed:        %,.0f entries / sec\n", replayRate);
    }

    private static PerfResult calculateResult(long[] latencies, long totalDurationNs, int ops) {
        Arrays.sort(latencies);
        double durationSec = totalDurationNs / 1_000_000_000.0;
        long throughput = (long) (ops / durationSec);
        double p50 = latencies[(int) (ops * 0.50)] / 1000.0; // microseconds
        double p99 = latencies[(int) (ops * 0.99)] / 1000.0; // microseconds
        return new PerfResult(throughput, p50, p99);
    }

    private static void printPerf(String name, PerfResult r) {
        System.out.printf("  %-25s | %,12d ops/s | %8.2f µs | %8.2f µs\n",
                name, r.throughput, r.p50Us, r.p99Us);
    }

    record PerfResult(long throughput, double p50Us, double p99Us) {}
}
