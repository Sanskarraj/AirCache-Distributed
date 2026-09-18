package com.distcache.cluster;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a physical cache node in the cluster.
 */
public class Node implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Status {
        ONLINE,
        LEAVING,
        OFFLINE
    }

    private final String nodeId;
    private final String host;
    private final int cachePort;
    private final int raftPort;
    private volatile Status status;

    public Node(String nodeId, String host, int cachePort, int raftPort) {
        this.nodeId = nodeId;
        this.host = host;
        this.cachePort = cachePort;
        this.raftPort = raftPort;
        this.status = Status.ONLINE;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getCachePort() {
        return cachePort;
    }

    public int getRaftPort() {
        return raftPort;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getCacheAddress() {
        return host + ":" + cachePort;
    }

    public String getRaftAddress() {
        return host + ":" + raftPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node node)) return false;
        return Objects.equals(nodeId, node.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "Node{" +
                "id='" + nodeId + '\'' +
                ", cacheAddress='" + getCacheAddress() + '\'' +
                ", raftAddress='" + getRaftAddress() + '\'' +
                ", status=" + status +
                '}';
    }
}
