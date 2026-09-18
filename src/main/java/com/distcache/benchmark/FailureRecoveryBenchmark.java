package com.distcache.benchmark;

import com.distcache.cluster.Node;
import com.distcache.consensus.RaftMessage;
import com.distcache.consensus.RaftNode;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Benchmark measuring Distributed System Failure Recovery and Raft Consensus:
 * 1. Leader Crash & Follower Failover Election Time (ms).
 * 2. Dynamic Node Join & Topology Replication under traffic.
 * 3. Health Check Failure Detection (Heartbeat timeout) & Automated Rebalancing.
 */
public class FailureRecoveryBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   BENCHMARK: Distributed Failure Recovery & Raft Dynamic Consensus             ");
        System.out.println("================================================================================");

        // 1. Leader Crash & Failover Election Time
        testLeaderFailoverTime();

        // 2. Dynamic Node Join & Membership Log Replication
        testDynamicNodeJoin();

        // 3. Health Check Heartbeat Failure Detection
        testHealthCheckFailureDetection();

        System.out.println("================================================================================\n");
    }

    public static void testLeaderFailoverTime() throws Exception {
        System.out.println("\n--- 1. Leader Crash & Failover Election Benchmark (3-Node Cluster) ---");

        Map<String, RaftNode> cluster = new ConcurrentHashMap<>();
        AtomicBoolean partitionNodeA = new AtomicBoolean(false);

        RaftNode.RaftTransport inMemoryTransport = (targetNodeId, msg) -> {
            if (partitionNodeA.get() && ("node-A".equals(targetNodeId) || "node-A".equals(msg.getSenderId()))) {
                // Drop messages if node-A is crashed/isolated
                return;
            }
            RaftNode target = cluster.get(targetNodeId);
            if (target != null) {
                CompletableFuture.runAsync(() -> {
                    RaftMessage resp = target.handleMessage(msg);
                    if (resp != null) {
                        RaftNode sender = cluster.get(msg.getSenderId());
                        if (sender != null && (!partitionNodeA.get() || !"node-A".equals(msg.getSenderId()))) {
                            sender.handleMessage(resp);
                        }
                    }
                });
            }
        };

        Node nodeA = new Node("node-A", "127.0.0.1", 8001, 9001);
        Node nodeB = new Node("node-B", "127.0.0.1", 8002, 9002);
        Node nodeC = new Node("node-C", "127.0.0.1", 8003, 9003);

        RaftNode raftA = new RaftNode("node-A", inMemoryTransport, null);
        RaftNode raftB = new RaftNode("node-B", inMemoryTransport, null);
        RaftNode raftC = new RaftNode("node-C", inMemoryTransport, null);

        cluster.put("node-A", raftA);
        cluster.put("node-B", raftB);
        cluster.put("node-C", raftC);

        raftA.addPeer(nodeB);
        raftA.addPeer(nodeC);
        raftB.addPeer(nodeA);
        raftB.addPeer(nodeC);
        raftC.addPeer(nodeA);
        raftC.addPeer(nodeB);

        // Start cluster
        raftB.start();
        raftC.start();
        raftA.becomeLeader(); // Force node-A as initial leader

        Thread.sleep(100);
        System.out.printf("  Initial Leader established: %s (Term %d)\n", raftA.getNodeId(), raftA.getCurrentTerm());

        // Simulate crash of Node A
        System.out.println("  >>> CRASHING LEADER (Node-A)... Measuring Failover Downtime <<<");
        long crashTime = System.currentTimeMillis();
        partitionNodeA.set(true);
        raftA.stop();

        // Await new leader election among remaining nodes (Node B or Node C)
        String newLeader = null;
        long failoverDurationMs = 0;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(25);
            if (raftB.getRole() == RaftNode.Role.LEADER) {
                newLeader = "node-B";
                failoverDurationMs = System.currentTimeMillis() - crashTime;
                break;
            } else if (raftC.getRole() == RaftNode.Role.LEADER) {
                newLeader = "node-C";
                failoverDurationMs = System.currentTimeMillis() - crashTime;
                break;
            }
        }

        System.out.printf("  New Leader Elected: %s | Failover Time: %d ms | New Term: %d\n",
                newLeader, failoverDurationMs,
                "node-B".equals(newLeader) ? raftB.getCurrentTerm() : raftC.getCurrentTerm());

        if (failoverDurationMs < 600) {
            System.out.printf("  >>> SUCCESS: Sub-second leader election failover achieved (%d ms)! <<<\n", failoverDurationMs);
        }

        raftB.stop();
        raftC.stop();
    }

    public static void testDynamicNodeJoin() throws Exception {
        System.out.println("\n--- 2. Dynamic Node Join & Log Replication Benchmark ---");

        Map<String, RaftNode> cluster = new ConcurrentHashMap<>();
        RaftNode.RaftTransport transport = (target, msg) -> {
            RaftNode targetNode = cluster.get(target);
            if (targetNode != null) {
                CompletableFuture.runAsync(() -> {
                    RaftMessage resp = targetNode.handleMessage(msg);
                    if (resp != null) {
                        RaftNode sender = cluster.get(msg.getSenderId());
                        if (sender != null) sender.handleMessage(resp);
                    }
                });
            }
        };

        CountDownLatch membershipAppliedLatch = new CountDownLatch(2);
        RaftNode.ClusterMembershipListener listener = new RaftNode.ClusterMembershipListener() {
            @Override
            public void onNodeJoined(Node node) {
                membershipAppliedLatch.countDown();
            }

            @Override
            public void onNodeLeft(Node node) {}
        };

        RaftNode leader = new RaftNode("leader", transport, listener);
        RaftNode follower = new RaftNode("follower-1", transport, listener);
        cluster.put("leader", leader);
        cluster.put("follower-1", follower);

        Node leaderNode = new Node("leader", "127.0.0.1", 8001, 9001);
        Node f1Node = new Node("follower-1", "127.0.0.1", 8002, 9002);
        leader.addPeer(f1Node);
        follower.addPeer(leaderNode);

        follower.start();
        leader.becomeLeader();

        Thread.sleep(100);

        // Dynamically join Node 3
        Node newNode = new Node("follower-2", "127.0.0.1", 8003, 9003);
        long t0 = System.nanoTime();
        leader.proposeNodeJoin(newNode);

        boolean committed = membershipAppliedLatch.await(2, TimeUnit.SECONDS);
        long durationUs = (System.nanoTime() - t0) / 1000;

        System.out.printf("  Dynamic NODE_JOIN proposal committed & applied across cluster: %b in %,d µs\n",
                committed, durationUs);
        System.out.printf("  Leader Commit Index: %d | Last Log Index: %d\n",
                leader.getLog().getCommitIndex(), leader.getLog().getLastLogIndex());

        leader.stop();
        follower.stop();
    }

    public static void testHealthCheckFailureDetection() throws Exception {
        System.out.println("\n--- 3. Heartbeat Health Check Failure Detection ---");

        CountDownLatch nodeLeftLatch = new CountDownLatch(1);
        RaftNode.ClusterMembershipListener listener = new RaftNode.ClusterMembershipListener() {
            @Override
            public void onNodeJoined(Node node) {}

            @Override
            public void onNodeLeft(Node node) {
                nodeLeftLatch.countDown();
            }
        };

        RaftNode leader = new RaftNode("hc-leader", (t, m) -> {}, listener);
        Node deadNode = new Node("dead-node", "10.0.0.99", 8099, 9099);
        leader.addPeer(deadNode);
        leader.becomeLeader();

        System.out.println("  Registered peer 'dead-node'. Simulating dead node with no heartbeats...");
        // Simulated failure detection logic: heartbeat checker detects >3000ms stale
        long t0 = System.currentTimeMillis();
        // Since heartbeat loop checks periodically, wait for failure trigger
        boolean detected = nodeLeftLatch.await(4, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.printf("  Stale node failure detected and NODE_LEAVE proposed: %b (Detection Time: %d ms)\n",
                detected, elapsed);

        leader.stop();
    }
}
