package com.distcache.consensus;

import com.distcache.cluster.Node;

import java.io.Serializable;

/**
 * Log entry stored in the replicated Raft log.
 */
public class RaftLogEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        NOOP,
        NODE_JOIN,
        NODE_LEAVE
    }

    private final long term;
    private final long index;
    private final Type type;
    private final Node node;

    public RaftLogEntry(long term, long index, Type type, Node node) {
        this.term = term;
        this.index = index;
        this.type = type;
        this.node = node;
    }

    public long getTerm() {
        return term;
    }

    public long getIndex() {
        return index;
    }

    public Type getType() {
        return type;
    }

    public Node getNode() {
        return node;
    }

    @Override
    public String toString() {
        return "RaftLogEntry{" +
                "term=" + term +
                ", index=" + index +
                ", type=" + type +
                ", node=" + (node != null ? node.getNodeId() : "null") +
                '}';
    }
}
