package com.distcache.consensus;

import com.distcache.cluster.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RaftConsensusTest {

    @Test
    public void testRaftLogAppendAndCommit() {
        RaftLog log = new RaftLog();
        assertEquals(0, log.getLastLogIndex());
        assertEquals(0, log.getCommitIndex());

        Node node1 = new Node("node1", "127.0.0.1", 8001, 9001);
        long idx1 = log.appendEntry(1, RaftLogEntry.Type.NODE_JOIN, node1);
        assertEquals(1, idx1);
        assertEquals(1, log.getLastLogIndex());
        assertEquals(1, log.getLastLogTerm());

        log.setCommitIndex(1);
        assertEquals(1, log.getCommitIndex());
    }

    @Test
    public void testLeaderDynamicMembershipCallback() {
        AtomicInteger joinEvents = new AtomicInteger(0);
        AtomicInteger leaveEvents = new AtomicInteger(0);

        RaftNode.ClusterMembershipListener listener = new RaftNode.ClusterMembershipListener() {
            @Override
            public void onNodeJoined(Node node) {
                joinEvents.incrementAndGet();
            }

            @Override
            public void onNodeLeft(Node node) {
                leaveEvents.incrementAndGet();
            }
        };

        RaftNode leader = new RaftNode("leader", (target, msg) -> {}, listener);
        leader.becomeLeader();
        assertEquals(RaftNode.Role.LEADER, leader.getRole());

        // Propose dynamic node join
        Node newNode = new Node("node-new", "127.0.0.1", 8005, 9005);
        leader.proposeNodeJoin(newNode);

        // Since leader has no peers in this standalone test, it commits immediately
        leader.getLog().setCommitIndex(leader.getLog().getLastLogIndex());

        // In a peer environment, append entries commit invokes callback
        RaftMessage appendReq = RaftMessage.createAppendEntriesReq(
                "leader",
                leader.getCurrentTerm(),
                0,
                0,
                leader.getLog().getEntriesFrom(1),
                leader.getLog().getLastLogIndex()
        );

        // A follower processing this will apply the committed log entry
        AtomicInteger followerJoined = new AtomicInteger(0);
        RaftNode follower = new RaftNode("follower", (target, msg) -> {}, new RaftNode.ClusterMembershipListener() {
            @Override
            public void onNodeJoined(Node node) {
                followerJoined.incrementAndGet();
            }

            @Override
            public void onNodeLeft(Node node) {}
        });

        RaftMessage resp = follower.handleMessage(appendReq);
        assertTrue(resp.isSuccess());
        assertEquals(1, followerJoined.get(), "Follower must have applied committed NODE_JOIN entry to membership listener");

        leader.stop();
        follower.stop();
    }
}
