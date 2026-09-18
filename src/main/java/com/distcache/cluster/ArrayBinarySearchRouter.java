package com.distcache.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * High-performance, primitive-based Consistent Hash Ring implementation.
 *
 * Employs sorted primitive long[] token arrays and copy-on-write immutable ring snapshots.
 * Lookups require ZERO locks and ZERO boxed object allocation on the critical request path,
 * utilizing binary search with cache-line-friendly sequential memory layout.
 */
public class ArrayBinarySearchRouter {
    private static final Logger logger = LoggerFactory.getLogger(ArrayBinarySearchRouter.class);

    public record TokenRing(long[] tokens, Node[] nodes) {}

    private final int virtualNodes;
    private final Map<String, Node> physicalNodes = new ConcurrentHashMap<>();
    private final ReentrantLock updateLock = new ReentrantLock();
    private volatile TokenRing currentRing = new TokenRing(new long[0], new Node[0]);

    public ArrayBinarySearchRouter() {
        this(256);
    }

    public ArrayBinarySearchRouter(int virtualNodes) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException("virtualNodes must be > 0");
        }
        this.virtualNodes = virtualNodes;
    }

    public ArrayBinarySearchRouter(int virtualNodes, Collection<Node> initialNodes) {
        this(virtualNodes);
        if (initialNodes != null) {
            for (Node node : initialNodes) {
                addNode(node);
            }
        }
    }

    public void addNode(Node node) {
        if (node == null) return;
        updateLock.lock();
        try {
            physicalNodes.put(node.getNodeId(), node);
            rebuildRing();
            logger.info("Added node {} to array hash ring (total physical: {}, tokens: {})",
                    node.getNodeId(), physicalNodes.size(), currentRing.tokens.length);
        } finally {
            updateLock.unlock();
        }
    }

    public void removeNode(String nodeId) {
        if (nodeId == null) return;
        updateLock.lock();
        try {
            if (physicalNodes.remove(nodeId) != null) {
                rebuildRing();
                logger.info("Removed node {} from array hash ring (remaining physical: {}, tokens: {})",
                        nodeId, physicalNodes.size(), currentRing.tokens.length);
            }
        } finally {
            updateLock.unlock();
        }
    }

    private void rebuildRing() {
        List<TokenEntry> entries = new ArrayList<>(physicalNodes.size() * virtualNodes);

        for (Node node : physicalNodes.values()) {
            for (int i = 0; i < virtualNodes; i++) {
                String tokenKey = node.getNodeId() + "#vnode#" + i;
                long hash = ConsistentHashRouter.hash(tokenKey.getBytes(StandardCharsets.UTF_8));
                entries.add(new TokenEntry(hash, node));
            }
        }

        // Sort by hash
        entries.sort(Comparator.comparingLong(e -> e.hash));

        long[] tokens = new long[entries.size()];
        Node[] nodes = new Node[entries.size()];

        for (int i = 0; i < entries.size(); i++) {
            tokens[i] = entries.get(i).hash;
            nodes[i] = entries.get(i).node;
        }

        currentRing = new TokenRing(tokens, nodes);
    }

    /**
     * Lock-free O(log N) binary search routing on primitive token array.
     */
    public Node route(byte[] key) {
        if (key == null) return null;
        TokenRing ring = currentRing;
        if (ring.tokens.length == 0) return null;

        long hash = ConsistentHashRouter.hash(key);
        int idx = Arrays.binarySearch(ring.tokens, hash);

        if (idx >= 0) {
            return ring.nodes[idx];
        }

        int insertionPoint = -idx - 1;
        if (insertionPoint < ring.tokens.length) {
            return ring.nodes[insertionPoint];
        }

        // Wrap around to head of ring
        return ring.nodes[0];
    }

    public int getRingSize() {
        return currentRing.tokens.length;
    }

    public int getPhysicalNodeCount() {
        return physicalNodes.size();
    }

    public int getVirtualNodes() {
        return virtualNodes;
    }

    private record TokenEntry(long hash, Node node) {}
}
