package com.distcache.config;

import com.distcache.cluster.Node;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Cache node runtime configuration.
 */
public class CacheConfig {
    private String nodeId = "node-1";
    private String host = "0.0.0.0";
    private int cachePort = 8001;
    private int raftPort = 9001;
    private int capacity = 100_000;
    private int virtualNodes = 256;
    private float probationaryRatio = 0.25f;
    private Path dataDir = Paths.get("./data");
    private final List<Node> peers = new ArrayList<>();

    public static CacheConfig fromArgs(String[] args) {
        CacheConfig config = new CacheConfig();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--node-id" -> config.nodeId = args[++i];
                case "--host" -> config.host = args[++i];
                case "--port" -> config.cachePort = Integer.parseInt(args[++i]);
                case "--raft-port" -> config.raftPort = Integer.parseInt(args[++i]);
                case "--capacity" -> config.capacity = Integer.parseInt(args[++i]);
                case "--vnodes" -> config.virtualNodes = Integer.parseInt(args[++i]);
                case "--data-dir" -> config.dataDir = Paths.get(args[++i]);
                case "--peers" -> {
                    // format: nodeId@host:port:raftPort,nodeId2@host2:port2:raftPort2
                    String peersArg = args[++i];
                    String[] peerTokens = peersArg.split(",");
                    for (String token : peerTokens) {
                        token = token.trim();
                        if (!token.isEmpty()) {
                            String[] parts = token.split("@");
                            String peerId = parts[0];
                            String[] addrParts = parts[1].split(":");
                            String pHost = addrParts[0];
                            int pCachePort = Integer.parseInt(addrParts[1]);
                            int pRaftPort = (addrParts.length > 2) ? Integer.parseInt(addrParts[2]) : pCachePort;
                            config.peers.add(new Node(peerId, pHost, pCachePort, pRaftPort));
                        }
                    }
                }
            }
        }
        return config;
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

    public int getCapacity() {
        return capacity;
    }

    public int getVirtualNodes() {
        return virtualNodes;
    }

    public float getProbationaryRatio() {
        return probationaryRatio;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public List<Node> getPeers() {
        return peers;
    }
}
