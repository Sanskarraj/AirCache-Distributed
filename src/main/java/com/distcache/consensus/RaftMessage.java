package com.distcache.consensus;

import com.distcache.cluster.Node;

import java.io.Serializable;
import java.util.List;

/**
 * Message object exchanged between nodes participating in the Raft consensus cluster.
 */
public class RaftMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        REQUEST_VOTE_REQ,
        REQUEST_VOTE_RESP,
        APPEND_ENTRIES_REQ,
        APPEND_ENTRIES_RESP,
        NODE_JOIN_REQ,
        NODE_JOIN_RESP,
        NODE_LEAVE_REQ,
        NODE_LEAVE_RESP
    }

    private final Type type;
    private final String senderId;
    private final long term;

    // RequestVote fields
    private String candidateId;
    private long lastLogIndex;
    private long lastLogTerm;
    private boolean voteGranted;

    // AppendEntries fields
    private String leaderId;
    private long prevLogIndex;
    private long prevLogTerm;
    private List<RaftLogEntry> entries;
    private long leaderCommit;
    private boolean success;
    private long matchIndex;

    // Dynamic membership payload
    private Node targetNode;

    public RaftMessage(Type type, String senderId, long term) {
        this.type = type;
        this.senderId = senderId;
        this.term = term;
    }

    public static RaftMessage createRequestVoteReq(
            String senderId,
            long term,
            long lastLogIndex,
            long lastLogTerm
    ) {
        RaftMessage msg = new RaftMessage(Type.REQUEST_VOTE_REQ, senderId, term);
        msg.candidateId = senderId;
        msg.lastLogIndex = lastLogIndex;
        msg.lastLogTerm = lastLogTerm;
        return msg;
    }

    public static RaftMessage createRequestVoteResp(
            String senderId,
            long term,
            boolean voteGranted
    ) {
        RaftMessage msg = new RaftMessage(Type.REQUEST_VOTE_RESP, senderId, term);
        msg.voteGranted = voteGranted;
        return msg;
    }

    public static RaftMessage createAppendEntriesReq(
            String senderId,
            long term,
            long prevLogIndex,
            long prevLogTerm,
            List<RaftLogEntry> entries,
            long leaderCommit
    ) {
        RaftMessage msg = new RaftMessage(Type.APPEND_ENTRIES_REQ, senderId, term);
        msg.leaderId = senderId;
        msg.prevLogIndex = prevLogIndex;
        msg.prevLogTerm = prevLogTerm;
        msg.entries = entries;
        msg.leaderCommit = leaderCommit;
        return msg;
    }

    public static RaftMessage createAppendEntriesResp(
            String senderId,
            long term,
            boolean success,
            long matchIndex
    ) {
        RaftMessage msg = new RaftMessage(Type.APPEND_ENTRIES_RESP, senderId, term);
        msg.success = success;
        msg.matchIndex = matchIndex;
        return msg;
    }

    public static RaftMessage createNodeJoinReq(String senderId, Node node) {
        RaftMessage msg = new RaftMessage(Type.NODE_JOIN_REQ, senderId, 0);
        msg.targetNode = node;
        return msg;
    }

    public static RaftMessage createNodeJoinResp(String senderId, boolean success, String leaderId) {
        RaftMessage msg = new RaftMessage(Type.NODE_JOIN_RESP, senderId, 0);
        msg.success = success;
        msg.leaderId = leaderId;
        return msg;
    }

    public static RaftMessage createNodeLeaveReq(String senderId, Node node) {
        RaftMessage msg = new RaftMessage(Type.NODE_LEAVE_REQ, senderId, 0);
        msg.targetNode = node;
        return msg;
    }

    public Type getType() {
        return type;
    }

    public String getSenderId() {
        return senderId;
    }

    public long getTerm() {
        return term;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public long getLastLogTerm() {
        return lastLogTerm;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public long getPrevLogIndex() {
        return prevLogIndex;
    }

    public long getPrevLogTerm() {
        return prevLogTerm;
    }

    public List<RaftLogEntry> getEntries() {
        return entries;
    }

    public long getLeaderCommit() {
        return leaderCommit;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getMatchIndex() {
        return matchIndex;
    }

    public Node getTargetNode() {
        return targetNode;
    }

    @Override
    public String toString() {
        return "RaftMessage{" +
                "type=" + type +
                ", senderId='" + senderId + '\'' +
                ", term=" + term +
                ", success=" + success +
                '}';
    }
}
