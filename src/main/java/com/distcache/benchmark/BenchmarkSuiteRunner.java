package com.distcache.benchmark;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

/**
 * Master Enterprise Benchmark Suite Runner for Distributed In-Memory Cache.
 *
 * Automatically inspects and logs system environment (Hardware, CPU cores, OS,
 * JDK version, JVM flags, heap boundaries) and orchestrates all 9 benchmark domains.
 */
public class BenchmarkSuiteRunner {

    public static void main(String[] args) {
        printEnvironmentMetadata();

        String target = (args != null && args.length > 0) ? args[0].toLowerCase() : "all";

        try {
            switch (target) {
                case "hash-dist" -> ConsistentHashDistributionTest.main(new String[0]);
                case "hash-lookup" -> ConsistentHashLookupBenchmark.main(new String[0]);
                case "eviction" -> EvictionPolicyBenchmark.main(new String[0]);
                case "protocol" -> ProtocolComparisonBenchmark.main(new String[0]);
                case "concurrency" -> ConcurrencyScalingBenchmark.main(new String[0]);
                case "failure" -> FailureRecoveryBenchmark.main(new String[0]);
                case "memory" -> MemoryUsageBenchmark.main(new String[0]);
                case "ttl" -> TTLExpirationBenchmark.main(new String[0]);
                case "e2e-netty" -> EndToEndNettyBenchmark.main(new String[0]);
                case "raft-partition" -> RaftPartitionConsistencyBenchmark.main(new String[0]);
                case "persistence-crash" -> PersistenceCrashBenchmark.main(new String[0]);
                case "all" -> {
                    System.out.println("\n>>> RUNNING FULL ENTERPRISE BENCHMARK SUITE <<<\n");
                    ConsistentHashDistributionTest.main(new String[0]);
                    ConsistentHashLookupBenchmark.main(new String[0]);
                    EvictionPolicyBenchmark.main(new String[0]);
                    ProtocolComparisonBenchmark.main(new String[0]);
                    EndToEndNettyBenchmark.main(new String[0]);
                    RaftPartitionConsistencyBenchmark.main(new String[0]);
                    FailureRecoveryBenchmark.main(new String[0]);
                    MemoryUsageBenchmark.main(new String[0]);
                    TTLExpirationBenchmark.main(new String[0]);
                    PersistenceCrashBenchmark.main(new String[0]);
                    PersistenceBenchmark.main(new String[0]);
                    ConcurrencyScalingBenchmark.main(new String[0]);
                }
                default -> {
                    System.out.println("Unknown benchmark target: " + target);
                    System.out.println("Available targets: all, hash-dist, hash-lookup, eviction, protocol, e2e-netty, raft-partition, failure, memory, ttl, persistence, persistence-crash, concurrency");
                }
            }
        } catch (Throwable t) {
            System.err.println("Benchmark execution encountered an error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    public static void printEnvironmentMetadata() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Runtime runtime = Runtime.getRuntime();

        System.out.println("================================================================================");
        System.out.println("            ENTERPRISE DISTRIBUTED CACHE BENCHMARK ENVIRONMENT                   ");
        System.out.println("================================================================================");
        System.out.printf("  Operating System:    %s (%s, %s)\n", osBean.getName(), osBean.getVersion(), osBean.getArch());
        System.out.printf("  Available Cores:     %d CPU cores\n", runtime.availableProcessors());
        System.out.printf("  Java Runtime:        %s (build %s)\n", System.getProperty("java.runtime.name"), System.getProperty("java.runtime.version"));
        System.out.printf("  JVM VM:              %s by %s\n", runtimeBean.getVmName(), runtimeBean.getVmVendor());
        System.out.printf("  Max Heap Memory:     %.2f GB\n", runtime.maxMemory() / (1024.0 * 1024.0 * 1024.0));
        System.out.printf("  JVM Input Arguments: %s\n", runtimeBean.getInputArguments());
        System.out.println("================================================================================");
    }
}
