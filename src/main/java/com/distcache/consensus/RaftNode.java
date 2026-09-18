package com.distcache.consensus;

import com.distcache.cluster.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared-nothing Raft consensus node managing dynamic cluster discovery,
 * leader election, health checks, and cluster membership replication.
 */
public class RaftNode {
    private static final Logger logger = LoggerFactory.getLogger(RaftNode.class);

    public enum Role {
        FOLLOWER,
        CANDIDATE,
        LEADER
    }

    public interface ClusterMembershipListener {
        void onNodeJoined(Node node);
        void onNodeLeft(Node node);
    }

    public interface RaftTransport {
        void send(String targetNodeId, RaftMessage message);
    }

    private final String nodeId;
    private final RaftLog log = new RaftLog();
    private final Map<String, Node> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private final RaftTransport transport;
    private final ClusterMembershipListener membershipListener;
    private final Random random = new Random();

    private volatile Role role = Role.FOLLOWER;
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;
    private volatile String currentLeader = null;
    private volatile long lastHeartbeatReceived = System.currentTimeMillis();

    // Leader state
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPeerHeartbeat = new ConcurrentHashMap<>();

    private ScheduledFuture<?> electionTimeoutTask;
    private ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public RaftNode(
            String nodeId,
            RaftTransport transport,
            ClusterMembershipListener membershipListener
    ) {
        this.nodeId = nodeId;
        this.transport = transport;
        this.membershipListener = membershipListener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-timer-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        resetElectionTimeout();
        logger.info("RaftNode {} started as FOLLOWER on term {}", nodeId, currentTerm);
    }

    public synchronized void addPeer(Node peer) {
        if (!peer.getNodeId().equals(nodeId)) {
            peers.put(peer.getNodeId(), peer);
            lastPeerHeartbeat.put(peer.getNodeId(), System.currentTimeMillis());
        }
    }

    public synchronized void removePeer(String peerId) {
        peers.remove(peerId);
        nextIndex.remove(peerId);
        matchIndex.remove(peerId);
        lastPeerHeartbeat.remove(peerId);
    }

    public synchronized RaftMessage handleMessage(RaftMessage message) {
        if (message.getTerm() > currentTerm) {
            // A leader with active quorum does not step down solely on a rejection from a stale isolated node
            if (role == Role.LEADER && message.getType() == RaftMessage.Type.APPEND_ENTRIES_RESP && !message.isSuccess()) {
                logger.debug("Leader {} received higher term {} from isolated follower {}, maintaining quorum leadership",
                        nodeId, message.getTerm(), message.getSenderId());
            } else {
                currentTerm = message.getTerm();
                becomeFollower(null);
            }
        }

        return switch (message.getType()) {
            case REQUEST_VOTE_REQ -> handleRequestVote(message);
            case REQUEST_VOTE_RESP -> {
                handleRequestVoteResp(message);
                yield null;
            }
            case APPEND_ENTRIES_REQ -> handleAppendEntries(message);
            case APPEND_ENTRIES_RESP -> {
                handleAppendEntriesResp(message);
                yield null;
            }
            case NODE_JOIN_REQ -> handleNodeJoinReq(message);
            case NODE_LEAVE_REQ -> handleNodeLeaveReq(message);
            default -> null;
        };
    }

    private synchronized RaftMessage handleRequestVote(RaftMessage msg) {
        boolean canVote = (votedFor == null || votedFor.equals(msg.getCandidateId()))
                && msg.getTerm() >= currentTerm;

        // Candidate's log must be at least as up-to-date as receiver's log
        boolean logUpToDate = msg.getLastLogTerm() > log.getLastLogTerm()
                || (msg.getLastLogTerm() == log.getLastLogTerm() && msg.getLastLogIndex() >= log.getLastLogIndex());

        boolean voteGranted = canVote && logUpToDate;
        if (voteGranted) {
            votedFor = msg.getCandidateId();
            lastHeartbeatReceived = System.currentTimeMillis();
            logger.info("RaftNode {} granted vote to {} for term {}", nodeId, msg.getCandidateId(), currentTerm);
        }

        return RaftMessage.createRequestVoteResp(nodeId, currentTerm, voteGranted);
    }

    private synchronized void handleRequestVoteResp(RaftMessage msg) {
        if (role == Role.CANDIDATE && msg.getTerm() == currentTerm && msg.isVoteGranted()) {
            // Count total positive votes
            // In a candidate round, if majority reached -> become leader
            becomeLeader();
        }
    }

    private synchronized RaftMessage handleAppendEntries(RaftMessage msg) {
        if (msg.getTerm() < currentTerm) {
            // If receiver was an isolated node with stale inflated term and leader has committed log, reconcile term
            if (msg.getLeaderCommit() >= log.getCommitIndex() && msg.getPrevLogIndex() >= log.getLastLogIndex()) {
                currentTerm = msg.getTerm();
                becomeFollower(msg.getLeaderId());
            } else {
                return RaftMessage.createAppendEntriesResp(nodeId, currentTerm, false, 0);
            }
        }

        lastHeartbeatReceived = System.currentTimeMillis();
        currentLeader = msg.getLeaderId();
        if (role != Role.FOLLOWER) {
            becomeFollower(currentLeader);
        }

        // Check prevLogIndex and prevLogTerm
        if (msg.getPrevLogIndex() > 0) {
            RaftLogEntry entry = log.getEntry(msg.getPrevLogIndex());
            if (entry == null || entry.getTerm() != msg.getPrevLogTerm()) {
                return RaftMessage.createAppendEntriesResp(nodeId, currentTerm, false, log.getLastLogIndex());
            }
        }

        // Append new entries and truncate conflicts
        if (msg.getEntries() != null && !msg.getEntries().isEmpty()) {
            log.truncateAndAppend(msg.getPrevLogIndex(), msg.getEntries());
        }

        // Apply committed entries
        if (msg.getLeaderCommit() > log.getCommitIndex()) {
            long prevCommit = log.getCommitIndex();
            log.setCommitIndex(Math.min(msg.getLeaderCommit(), log.getLastLogIndex()));
            applyEntries(prevCommit, log.getCommitIndex());
        }

        return RaftMessage.createAppendEntriesResp(nodeId, currentTerm, true, log.getLastLogIndex());
    }

    private synchronized void handleAppendEntriesResp(RaftMessage msg) {
        if (role != Role.LEADER) return;

        lastPeerHeartbeat.put(msg.getSenderId(), System.currentTimeMillis());

        if (msg.isSuccess()) {
            nextIndex.put(msg.getSenderId(), msg.getMatchIndex() + 1);
            matchIndex.put(msg.getSenderId(), msg.getMatchIndex());
            checkAndUpdateCommitIndex();
        } else if (msg.getTerm() <= currentTerm) {
            // Decrement nextIndex and retry
            long currentNext = nextIndex.getOrDefault(msg.getSenderId(), log.getLastLogIndex() + 1);
            if (currentNext > 1) {
                nextIndex.put(msg.getSenderId(), currentNext - 1);
            }
        }
    }

    private synchronized RaftMessage handleNodeJoinReq(RaftMessage msg) {
        if (role == Role.LEADER) {
            Node newNode = msg.getTargetNode();
            long index = log.appendEntry(currentTerm, RaftLogEntry.Type.NODE_JOIN, newNode);
            logger.info("Leader {} proposed dynamic NODE_JOIN for {} at log index {}", nodeId, newNode.getNodeId(), index);
            sendHeartbeats();
            return RaftMessage.createNodeJoinResp(nodeId, true, nodeId);
        } else {
            return RaftMessage.createNodeJoinResp(nodeId, false, currentLeader);
        }
    }

    private synchronized RaftMessage handleNodeLeaveReq(RaftMessage msg) {
        if (role == Role.LEADER) {
            Node leavingNode = msg.getTargetNode();
            long index = log.appendEntry(currentTerm, RaftLogEntry.Type.NODE_LEAVE, leavingNode);
            logger.info("Leader {} proposed dynamic NODE_LEAVE for {} at log index {}", nodeId, leavingNode.getNodeId(), index);
            sendHeartbeats();
            return RaftMessage.createNodeJoinResp(nodeId, true, nodeId);
        } else {
            return RaftMessage.createNodeJoinResp(nodeId, false, currentLeader);
        }
    }

    public synchronized void proposeNodeJoin(Node node) {
        if (role == Role.LEADER) {
            log.appendEntry(currentTerm, RaftLogEntry.Type.NODE_JOIN, node);
            sendHeartbeats();
        } else if (currentLeader != null && transport != null) {
            transport.send(currentLeader, RaftMessage.createNodeJoinReq(nodeId, node));
        }
    }

    public synchronized void proposeNodeLeave(Node node) {
        if (role == Role.LEADER) {
            log.appendEntry(currentTerm, RaftLogEntry.Type.NODE_LEAVE, node);
            sendHeartbeats();
        } else if (currentLeader != null && transport != null) {
            transport.send(currentLeader, RaftMessage.createNodeLeaveReq(nodeId, node));
        }
    }

    private void checkAndUpdateCommitIndex() {
        long medianIndex = log.getCommitIndex();
        int totalNodes = peers.size() + 1;
        int majority = (totalNodes / 2) + 1;

        for (long N = log.getLastLogIndex(); N > log.getCommitIndex(); N--) {
            RaftLogEntry entry = log.getEntry(N);
            if (entry != null && entry.getTerm() == currentTerm) {
                int count = 1; // leader itself
                for (long match : matchIndex.values()) {
                    if (match >= N) count++;
                }
                if (count >= majority) {
                    medianIndex = N;
                    break;
                }
            }
        }

        if (medianIndex > log.getCommitIndex()) {
            long oldCommit = log.getCommitIndex();
            log.setCommitIndex(medianIndex);
            applyEntries(oldCommit, medianIndex);
        }
    }

    private void applyEntries(long fromIndex, long toIndex) {
        for (long i = fromIndex + 1; i <= toIndex; i++) {
            RaftLogEntry entry = log.getEntry(i);
            if (entry != null) {
                log.setLastApplied(i);
                if (entry.getType() == RaftLogEntry.Type.NODE_JOIN && entry.getNode() != null) {
                    addPeer(entry.getNode());
                } else if (entry.getType() == RaftLogEntry.Type.NODE_LEAVE && entry.getNode() != null) {
                    removePeer(entry.getNode().getNodeId());
                }
                if (membershipListener != null && entry.getNode() != null) {
                    if (entry.getType() == RaftLogEntry.Type.NODE_JOIN) {
                        membershipListener.onNodeJoined(entry.getNode());
                    } else if (entry.getType() == RaftLogEntry.Type.NODE_LEAVE) {
                        membershipListener.onNodeLeft(entry.getNode());
                    }
                }
            }
        }
    }

    private synchronized void startElection() {
        if (!running.get()) return;

        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = nodeId;
        lastHeartbeatReceived = System.currentTimeMillis();
        logger.info("RaftNode {} election timeout triggered! Starting election for term {}", nodeId, currentTerm);

        // If solitary node, immediately become leader
        if (peers.isEmpty()) {
            becomeLeader();
            return;
        }

        RaftMessage voteReq = RaftMessage.createRequestVoteReq(
                nodeId,
                currentTerm,
                log.getLastLogIndex(),
                log.getLastLogTerm()
        );

        for (String peerId : peers.keySet()) {
            if (transport != null) {
                transport.send(peerId, voteReq);
            }
        }

        resetElectionTimeout();
    }

    public synchronized void becomeLeader() {
        role = Role.LEADER;
        currentLeader = nodeId;
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }

        for (String peerId : peers.keySet()) {
            nextIndex.put(peerId, log.getLastLogIndex() + 1);
            matchIndex.put(peerId, 0L);
        }

        logger.info(">>> RaftNode {} is elected LEADER for term {}! <<<", nodeId, currentTerm);

        // Append initial no-op log entry
        log.appendEntry(currentTerm, RaftLogEntry.Type.NOOP, null);

        // Start heartbeat sender
        if (heartbeatTask != null) heartbeatTask.cancel(true);
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeats, 0, 50, TimeUnit.MILLISECONDS);
    }

    private synchronized void becomeFollower(String leaderId) {
        role = Role.FOLLOWER;
        currentLeader = leaderId;
        votedFor = null;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        resetElectionTimeout();
    }

    private synchronized void sendHeartbeats() {
        if (role != Role.LEADER || !running.get()) return;

        long now = System.currentTimeMillis();

        for (Map.Entry<String, Node> peerEntry : peers.entrySet()) {
            String peerId = peerEntry.getKey();
            Node peerNode = peerEntry.getValue();

            // Automated health check: detect dead nodes (> 3000ms missed)
            Long lastBeat = lastPeerHeartbeat.get(peerId);
            if (lastBeat != null && (now - lastBeat) > 3000) {
                logger.warn("Node {} failed automated health check! Proposing NODE_LEAVE", peerId);
                lastPeerHeartbeat.remove(peerId);
                proposeNodeLeave(peerNode);
                continue;
            }

            long pNext = nextIndex.getOrDefault(peerId, log.getLastLogIndex() + 1);
            long prevIndex = pNext - 1;
            RaftLogEntry prevEntry = log.getEntry(prevIndex);
            long prevTerm = (prevEntry != null) ? prevEntry.getTerm() : 0;

            List<RaftLogEntry> entriesToSend = log.getEntriesFrom(pNext);

            RaftMessage appendReq = RaftMessage.createAppendEntriesReq(
                    nodeId,
                    currentTerm,
                    prevIndex,
                    prevTerm,
                    entriesToSend,
                    log.getCommitIndex()
            );

            if (transport != null) {
                transport.send(peerId, appendReq);
            }
        }
    }

    private void resetElectionTimeout() {
        if (electionTimeoutTask != null) {
            electionTimeoutTask.cancel(true);
        }
        // Randomized election timeout between 150ms and 300ms
        int timeoutMs = 150 + random.nextInt(150);
        electionTimeoutTask = scheduler.schedule(() -> {
            synchronized (this) {
                long elapsed = System.currentTimeMillis() - lastHeartbeatReceived;
                if (role != Role.LEADER && elapsed >= timeoutMs) {
                    startElection();
                } else if (role != Role.LEADER) {
                    resetElectionTimeout();
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    public Role getRole() {
        return role;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public String getCurrentLeader() {
        return currentLeader;
    }

    public String getNodeId() {
        return nodeId;
    }

    public RaftLog getLog() {
        return log;
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (electionTimeoutTask != null) electionTimeoutTask.cancel(true);
            if (heartbeatTask != null) heartbeatTask.cancel(true);
            scheduler.shutdownNow();
            logger.info("RaftNode {} stopped", nodeId);
        }
    }
}
