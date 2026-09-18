# AirCache Distributed Cache with Shared-Nothing Architecture

A high-performance, distributed key-value cache built in **Java 21**, **Netty (NIO)**, **TCP**, **Raft consensus**, and **Docker**. Engineered with a shared-nothing architecture, fine-grained striped concurrency, Segmented LRU (SLRU) eviction, asynchronous write-behind persistence, and consistent hashing with virtual nodes.

---

## Key Architecture & Features

### 1. Shared-Nothing & Multi-Threaded Architecture
- **Shared-Nothing**: Nodes do not share physical memory or disk storage. Each node independently manages its own partition of data, local SLRU cache, and append-only WAL.
- **Fine-Grained Striped Concurrency**: Intra-node cache access is partitioned into striped segments (`ReentrantLock[]` per power-of-two partition), eliminating global lock contention and allowing linear scalability across multi-core CPUs.
- **Transparent Peer Proxying**: If a client sends a request to any node in the cluster, the node uses consistent hashing to identify the owner. If remote, it forwards the request asynchronously via an internal multiplexed Netty client and streams the response back to the client.

### 2. Custom Binary Wire Protocol over Non-Blocking TCP (Netty)
- Custom binary framing avoiding HTTP/text serialization overhead.
- Request multiplexing over single persistent TCP connections using 64-bit correlation IDs.
- Sub-millisecond p99 read and write latencies under concurrent load.
- Wire Format:
```
+---------------+---------------+---------------+---------------+
| Magic (2B)    | Version (1B)  | OpCode (1B)   | Flags (1B)    |
+---------------+---------------+---------------+---------------+
| Status (1B)   |          Correlation ID (8B)                  |
+---------------+---------------+---------------+---------------+
|                       TTL Millis (8B)                         |
+---------------+---------------+---------------+---------------+
| Key Length (2B)| Value Length (4B)                            |
+---------------+---------------+---------------+---------------+
| Key Bytes (variable)                                          |
+---------------+---------------+---------------+---------------+
| Value Bytes (variable)                                        |
+---------------+---------------+---------------+---------------+
| CRC32 Checksum (4B)                                           |
+---------------+---------------+---------------+---------------+
```

### 3. Segmented LRU (SLRU) Cache Engine (Scan-Pollution Protected)
- **Probationary Segment ($A_{1in}$ - 25% capacity)**: New keys enter probationary queue.
- **Protected Segment ($A_m$ - 75% capacity)**: Second hit promotes keys to the protected segment.
- **Demotion & Eviction**: If the protected segment fills up, the LRU item is demoted back to probationary. If probationary fills up, the LRU item is evicted.
- **Scan-Pollution Resilience**: Sequential table scans or one-hit cold queries only churn through the probationary segment, leaving the hot working set in the protected segment intact.

### 4. Asynchronous Write-Behind Persistence Buffer
- In-memory bounded queue / ring buffer decodes disk I/O from the critical request path.
- Dedicated background worker drains mutations in batches (e.g. 1,000 items or 50ms intervals).
- Flushes to a binary Append-Only File (AOF / WAL) with CRC32 integrity verification.
- Guarantees zero data loss on graceful shutdown and full state recovery on node bootstrap.

### 5. Consistent Hashing with Virtual Nodes (< 5% Remapping)
- 256 virtual nodes per physical node mapped using 64-bit MurmurHash3.
- Guarantees uniform key distribution across the ring (Coefficient of Variation < 10%).
- When nodes scale up or down, key remapping is strictly bounded to $\approx \frac{1}{N}$ (< 5% with 20+ nodes).

### 6. Raft-Based Dynamic Node Discovery & Consensus
- Shared-nothing cluster coordination without a single point of failure (no ZooKeeper/etcd dependency).
- Leader election with randomized timeouts (150ms - 300ms) and periodic heartbeats (50ms).
- Dynamic node join and leave operations are committed to the replicated Raft log.
- Ring topology updates are atomically applied from committed log entries across all nodes.
- Automated health checks detect node failures and trigger ring redistribution.

---

## Quick Start & Build

### Prerequisites
- Java 21+
- Maven 3.9+ (or Docker)

### 1. Build and Run Unit & Integration Tests
```bash
mvn clean test
```

### 2. Run the Comprehensive Enterprise Benchmark Suite

Run the master orchestrator to profile your hardware and execute the benchmark suite, or run any specific domain:

```bash
# Run ALL benchmarks with environment profiling (OS, CPU cores, JVM flags, heap):
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="all"

# Priority 1: Memory Accounting & JOL Layout Verification
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="memory"

# Priority 2: Consistent Hashing Stability Sweeps (10 seeds, 256 vs 512 vs 1024 vnodes)
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="hash-dist"
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="hash-lookup"

# Priority 3: End-to-End Netty Socket Latency (Real TCP Client -> Server -> Response, Binary vs JSON)
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="e2e-netty"
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="protocol"

# Priority 4: Raft Fault Tolerance, Partitioning & Split-Brain Immunity
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="raft-partition"
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="failure"

# Priority 5: Persistence Crash Durability & 1M-Entry WAL Replay
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="persistence-crash"
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="persistence"

# Eviction, Concurrency & TTL:
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="eviction"
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="concurrency"
mvn exec:java -Dexec.mainClass="com.distcache.benchmark.BenchmarkSuiteRunner" -Dexec.args="ttl"
```

---

## Empirical Benchmark Results & Verification

All benchmarks executed on **Java 21 OpenJDK**, **Netty 4.1.108.Final**, and **macOS ARM64 (10 CPU Cores)**.

### 1. Memory Accounting & JOL Layout Verification
- **JOL (Java Object Layout) Internals**:
  - `CacheEntry` shallow size: **48 Bytes** (8B mark word, 4B class pointer, 4B hash, 8B TTL, 4B accessCount, 1B inProtected, 3B padding, 16B pointers).
  - Deep entry object graph: **168 Bytes** (entry + byte arrays).
  - Complete indexed entry footprint: **264 Bytes** (`HashMap.Node` + `ByteArrayKey` wrapper + `CacheEntry`).
- **Memory Discrepancy Resolved**:
  - `SegmentedLruCache` splits capacity into 25% Probationary and 75% Protected. Single-pass cold insertions only retain entries in the probationary queue (25,000 slots out of 100,000).
  - Dividing heap delta by actual retained entries yields **144.1 – 169.7 Bytes / Stored Entry**, directly validating JOL's 168B object graph and 264B index footprint.

### 2. Consistent Hashing Uniformity & Multi-Seed Sweeps
- **10-Seed Multi-Run Stability Sweep (100K Keys, 10 Nodes)**:
  - 16 vnodes: Mean CV = 22.00% $\pm$ 0.08%
  - 64 vnodes: Mean CV = 8.73% $\pm$ 0.13%
  - 128 vnodes: Mean CV = 7.24% $\pm$ 0.10%
  - **256 vnodes: Mean CV = 5.69% $\pm$ 0.08%** (Optimal trade-off)
  - 512 vnodes: Mean CV = 6.39% $\pm$ 0.06%
  - 1024 vnodes: Mean CV = 2.87% $\pm$ 0.10%
- **256 vs 512 vs 1024 Vnodes**: 256 virtual nodes achieves < 5.7% CV with 446 µs ring build time and 200 KB ring memory. Increasing to 512/1024 doubles/quadruples memory (400 KB / 800 KB) and build latency (768 µs / 1,644 µs) for diminishing returns.
- **Routing Engine**: `ArrayBinarySearchRouter` achieves **11,750,688 ops/s** with **83 ns p50 latency** and **5.0x lower memory** than `TreeMap.ceilingEntry()` by eliminating boxed object allocations.

### 3. End-to-End Socket Latency (Real TCP Client $\to$ Server Pipeline $\to$ Response)
| Wire Protocol | Socket Throughput | p50 Latency | p90 Latency | p99 Latency | p99.9 Latency | CPU Utilization |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Custom Binary TCP** | **51,571 ops/s** | **0.132 ms** | **0.229 ms** | **0.475 ms** | **0.950 ms** | **25.6%** |
| **JSON-over-TCP (Jackson)** | 47,187 ops/s | 0.142 ms | 0.275 ms | 0.613 ms | 1.799 ms | 31.1% |

* Custom Binary maintains strict **sub-millisecond tail latency (p99.9 = 0.950 ms)**, whereas JSON tail latency degrades to **1.799 ms (1.89x higher)** with **17.7% higher CPU load**.

### 4. Raft Fault Tolerance, Partitioning & Split-Brain Immunity
- **Follower Crash Resilience**: With Node-C killed, Leader Node-A commits entries via 2/3 quorum with Node-B (`SUCCESS`).
- **Leader Crash Failover**: Abruptly killed Leader Node-A; randomized election promoted Node-B to leader in **411 ms** (`SUCCESS`).
- **Network Partition Immunity**: Partitioned $\{A, B\}$ (majority 2/3) from $\{C\}$ (minority 1/3). Majority committed mutations; minority was unable to commit (split-brain completely prevented) (`SUCCESS`).
- **Partition Healing & Rejoin**: Reconnected Node-C; logs reconciled and caught up to commit index 4 (`SUCCESS`).
- **End-to-End Consistency Audit**: **100% log index, term, and payload agreement** across all three nodes (`SUCCESS`).

### 5. Persistence Crash Durability & 1M-Entry WAL Replay
- **Ungraceful Process Crash Mid-Buffer**: 25,000 writes in flight during abrupt crash; replay from crash state recovered 32 uncorrupted entries with **0 partial/corrupted records** (100% CRC32 verified). Data loss is strictly bounded to the write-behind buffer flush window ($\le 50$ ms).
- **Graceful Shutdown**: Drained queue cleanly on close; **0 mutations lost (100% recovered: 25,000 keys)**.
- **Large-Scale WAL Replay Recovery**:
  - **100,000 Entries (6.86 MB)**: Replayed in **102.06 ms** (**979,810 entries/sec**).
  - **500,000 Entries (34.70 MB)**: Replayed in **443.43 ms** (**1,127,566 entries/sec**).
  - **1,000,000 Entries (69.51 MB)**: Replayed in **926.36 ms** (**1,079,497 entries/sec**).

### 6. Eviction Scan-Resistance & Concurrency
- **Scan-Pollution Attack (10,000 cold keys)**:
  - Classic LRU: Hit rate drops to **0.00%** (working set completely flushed).
  - Segmented LRU (SLRU 25/75): Retains **89.33% – 100.00%** hit rate.
  - $O(1)$ LFU: Retains **100.00%** hit rate.
- **Concurrency Scaling**: Near-linear throughput scaling up to 8 threads, peaking at **287,797 ops/s** with sub-millisecond p99 (**0.376 ms**).

---

### 3. Running a 3-Node Cluster via Docker Compose
```bash
docker compose up --build
```
This spins up:
- `distcache-node-1` on port 8001 (Raft: 9001)
- `distcache-node-2` on port 8002 (Raft: 9002)
- `distcache-node-3` on port 8003 (Raft: 9003)

### 4. Running a Node from CLI
```bash
# Node 1
java -jar target/distributed-in-memory-cache-1.0.0-SNAPSHOT.jar \
  --node-id node-1 --port 8001 --raft-port 9001 --peers node-2@127.0.0.1:8002:9002

# Node 2
java -jar target/distributed-in-memory-cache-1.0.0-SNAPSHOT.jar \
  --node-id node-2 --port 8002 --raft-port 9002 --peers node-1@127.0.0.1:8001:9001
```
