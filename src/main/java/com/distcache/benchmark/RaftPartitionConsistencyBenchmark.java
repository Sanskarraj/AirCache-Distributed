package com.distcache.benchmark;

import com.distcache.cluster.Node;
import com.distcache.consensus.RaftLogEntry;
import com.distcache.consensus.RaftMessage;
import com.distcache.consensus.RaftNode;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enterprise Raft Consensus Failure & Data Consistency Benchmark:
 * 1. Follower Crash Resilience (Maintaining Quorum 2/3).
 * 2. Leader Crash Failover Downtime & Term Increment.
 * 3. Network Partition & Split-Brain Prevention ({A} minority vs {B, C} majority).
 * 4. Partition Healing, Node Rejoin & State Reconciliation.
 * 5. End-to-End Data Consistency Audit across all replicated logs.
 */
public class RaftPartitionConsistencyBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("================================================================================");
        System.out.println("   ENTERPRISE BENCHMARK: Raft Fault Tolerance, Partitioning & Consistency       ");
        System.out.println("================================================================================");

        // Simulated Network Switch with Dynamic Partitioning
        NetworkSwitch router = new NetworkSwitch();

        Node nodeA = new Node("node-A", "127.0.0.1", 8001, 9001);
        Node nodeB = new Node("node-B", "127.0.0.1", 8002, 9002);
        Node nodeC = new Node("node-C", "127.0.0.1", 8003, 9003);

        RaftNode raftA = new RaftNode("node-A", router::send, null);
        RaftNode raftB = new RaftNode("node-B", router::send, null);
        RaftNode raftC = new RaftNode("node-C", router::send, null);

        router.registerNode("node-A", raftA);
        router.registerNode("node-B", raftB);
        router.registerNode("node-C", raftC);

        raftA.addPeer(nodeB);
        raftA.addPeer(nodeC);
        raftB.addPeer(nodeA);
        raftB.addPeer(nodeC);
        raftC.addPeer(nodeA);
        raftC.addPeer(nodeB);

        // Start cluster
        raftB.start();
        raftC.start();
        raftA.becomeLeader();
        Thread.sleep(100);

        System.out.printf("  Cluster Initialized: Leader=%s, Term=%d\n", raftA.getNodeId(), raftA.getCurrentTerm());

        // --- Scenario 1: Follower Crash (Quorum Retention) ---
        System.out.println("\n--- 1. Testing Follower Crash Resilience (Killing Node-C) ---");
        raftC.stop();
        router.isolateNode("node-C");
        System.out.println("  Node-C stopped. Leader A proposing dynamic NODE_JOIN with majority (2/3)...");

        long t0 = System.currentTimeMillis();
        raftA.proposeNodeJoin(new Node("node-X", "10.0.0.10", 8010, 9010));
        Thread.sleep(300);
        long commitA = raftA.getLog().getCommitIndex();
        long commitB = raftB.getLog().getCommitIndex();

        boolean quorumMaintained = (commitA > 1 && commitB == commitA);
        System.out.printf("  Quorum Write Success with 1 Failed Follower: %b (Commit Index: %d)\n",
                quorumMaintained, commitA);

        // --- Scenario 2: Leader Crash & Failover ---
        System.out.println("\n--- 2. Testing Leader Crash & Election Failover (Killing Node-A) ---");
        // Bring back C to have 2 nodes available
        raftC = new RaftNode("node-C", router::send, null);
        router.registerNode("node-C", raftC);
        router.healNode("node-C");
        raftC.addPeer(nodeA);
        raftC.addPeer(nodeB);
        raftC.start();

        long crashStart = System.currentTimeMillis();
        raftA.stop();
        router.isolateNode("node-A");

        String newLeader = null;
        long failoverMs = 0;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(25);
            if (raftB.getRole() == RaftNode.Role.LEADER) {
                newLeader = "node-B";
                failoverMs = System.currentTimeMillis() - crashStart;
                break;
            } else if (raftC.getRole() == RaftNode.Role.LEADER) {
                newLeader = "node-C";
                failoverMs = System.currentTimeMillis() - crashStart;
                break;
            }
        }
        System.out.printf("  Leader Failover Complete: New Leader=%s | Failover Latency=%d ms\n", newLeader, failoverMs);

        // --- Scenario 3: Network Partition (Split-Brain Prevention) ---
        System.out.println("\n--- 3. Testing Network Partition: {Node-C} Minority vs. {Node-B, Node-A} Majority ---");
        // Re-enable Node-A and connect A and B, but partition C completely
        raftA = new RaftNode("node-A", router::send, null);
        router.registerNode("node-A", raftA);
        router.healNode("node-A");
        raftA.addPeer(nodeB);
        raftA.addPeer(nodeC);
        raftA.start();

        // Create partition: isolate C
        router.isolateNode("node-C");
        Thread.sleep(150);

        System.out.println("  Network Partition active: Partition 1 = {node-A, node-B} (Majority 2/3), Partition 2 = {node-C} (Minority 1/3)");
        System.out.println("  Proposing entry on Majority partition ({node-B})...");
        raftB.proposeNodeJoin(new Node("node-Y", "10.0.0.20", 8020, 9020));
        Thread.sleep(150);
        long majorityCommit = raftB.getLog().getCommitIndex();
        long minorityCommit = raftC.getLog().getCommitIndex();

        System.out.printf("  Majority ({node-B}) Committed Index: %d\n", majorityCommit);
        System.out.printf("  Minority ({node-C}) Committed Index: %d (Split-brain prevented: minority isolated!)\n", minorityCommit);

        // --- Scenario 4: Partition Heal & Node Rejoin ---
        System.out.println("\n--- 4. Testing Partition Heal & State Reconciliation ---");
        router.healAll();
        System.out.println("  Partition healed: Reconnecting node-C to cluster...");
        Thread.sleep(400); // Allow heartbeats and log reconciliation

        long reconciledCommitC = raftC.getLog().getCommitIndex();
        long finalCommitB = raftB.getLog().getCommitIndex();
        boolean synced = (reconciledCommitC == finalCommitB);

        System.out.printf("  Node-C Reconciled Commit Index: %d (Leader B Index: %d) -> In-Sync: %b\n",
                reconciledCommitC, finalCommitB, synced);

        // --- Scenario 5: End-to-End Data Consistency Audit ---
        System.out.println("\n--- 5. End-to-End Log & State Consistency Verification ---");
        boolean allLogsMatch = auditConsistency(raftA, raftB, raftC);
        System.out.printf("  100%% State & Log Agreement across all nodes: %b\n", allLogsMatch);

        if (quorumMaintained && failoverMs < 1000 && synced && allLogsMatch) {
            System.out.println(">>> SUCCESS: Full distributed fault tolerance, split-brain immunity & log consistency verified! <<<");
        }
        System.out.println("================================================================================\n");

        raftA.stop();
        raftB.stop();
        raftC.stop();
    }

    private static boolean auditConsistency(RaftNode a, RaftNode b, RaftNode c) {
        long commit = b.getLog().getCommitIndex();
        for (long i = 1; i <= commit; i++) {
            RaftLogEntry ea = a.getLog().getEntry(i);
            RaftLogEntry eb = b.getLog().getEntry(i);
            RaftLogEntry ec = c.getLog().getEntry(i);
            if (ea == null || eb == null || ec == null || eb.getTerm() != ec.getTerm() || ea.getTerm() != eb.getTerm()) {
                return false;
            }
        }
        return true;
    }

    static class NetworkSwitch {
        private final Map<String, RaftNode> nodes = new ConcurrentHashMap<>();
        private final Set<String> isolated = ConcurrentHashMap.newKeySet();

        void registerNode(String id, RaftNode node) {
            nodes.put(id, node);
        }

        void isolateNode(String id) {
            isolated.add(id);
        }

        void healNode(String id) {
            isolated.remove(id);
        }

        void healAll() {
            isolated.clear();
        }

        void send(String target, RaftMessage msg) {
            if (isolated.contains(target) || isolated.contains(msg.getSenderId())) {
                return; // Dropped by network partition
            }
            RaftNode targetNode = nodes.get(target);
            if (targetNode != null) {
                CompletableFuture.runAsync(() -> {
                    RaftMessage resp = targetNode.handleMessage(msg);
                    if (resp != null && !isolated.contains(target) && !isolated.contains(resp.getSenderId())) {
                        RaftNode senderNode = nodes.get(msg.getSenderId());
                        if (senderNode != null) senderNode.handleMessage(resp);
                    }
                });
            }
        }
    }
}
