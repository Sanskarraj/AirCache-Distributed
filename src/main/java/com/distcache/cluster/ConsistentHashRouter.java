package com.distcache.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent Hash Ring implementation with virtual nodes.
 *
 * Distributes keys uniformly across physical nodes using 64-bit MurmurHash3.
 * By default, assigns 256 virtual nodes per physical node to maintain an even
 * distribution and limit key remapping to under 5% when scaling nodes in a cluster.
 */
public class ConsistentHashRouter {
    private static final Logger logger = LoggerFactory.getLogger(ConsistentHashRouter.class);

    private final int virtualNodes;
    private final NavigableMap<Long, Node> ring = new TreeMap<>();
    private final Map<String, Node> physicalNodes = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();

    public ConsistentHashRouter() {
        this(256);
    }

    public ConsistentHashRouter(int virtualNodes) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException("virtualNodes must be > 0");
        }
        this.virtualNodes = virtualNodes;
    }

    public ConsistentHashRouter(int virtualNodes, Collection<Node> initialNodes) {
        this(virtualNodes);
        if (initialNodes != null) {
            for (Node node : initialNodes) {
                addNode(node);
            }
        }
    }

    /**
     * Adds a physical node to the ring, allocating virtual nodes.
     */
    public void addNode(Node node) {
        if (node == null) return;
        rwl.writeLock().lock();
        try {
            physicalNodes.put(node.getNodeId(), node);
            for (int i = 0; i < virtualNodes; i++) {
                String vnodeToken = node.getNodeId() + "#vnode#" + i;
                long hash = hash(vnodeToken.getBytes(StandardCharsets.UTF_8));
                ring.put(hash, node);
            }
            logger.info("Added node {} to consistent hash ring (total physical: {}, ring tokens: {})",
                    node.getNodeId(), physicalNodes.size(), ring.size());
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Removes a physical node and its virtual nodes from the ring.
     */
    public void removeNode(String nodeId) {
        if (nodeId == null) return;
        rwl.writeLock().lock();
        try {
            Node removed = physicalNodes.remove(nodeId);
            if (removed != null) {
                for (int i = 0; i < virtualNodes; i++) {
                    String vnodeToken = nodeId + "#vnode#" + i;
                    long hash = hash(vnodeToken.getBytes(StandardCharsets.UTF_8));
                    ring.remove(hash);
                }
                logger.info("Removed node {} from consistent hash ring (remaining physical: {}, ring tokens: {})",
                        nodeId, physicalNodes.size(), ring.size());
            }
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Routes a key to its owning physical node.
     *
     * @param key binary key
     * @return owning Node or null if ring is empty
     */
    public Node route(byte[] key) {
        if (key == null) return null;
        rwl.readLock().lock();
        try {
            if (ring.isEmpty()) {
                return null;
            }
            long hash = hash(key);
            Map.Entry<Long, Node> entry = ring.ceilingEntry(hash);
            if (entry == null) {
                // Wrap around to the start of the ring
                entry = ring.firstEntry();
            }
            return entry.getValue();
        } finally {
            rwl.readLock().unlock();
        }
    }

    public boolean isLocalKey(byte[] key, String localNodeId) {
        Node owner = route(key);
        return owner != null && owner.getNodeId().equals(localNodeId);
    }

    public Collection<Node> getAllNodes() {
        return Collections.unmodifiableCollection(physicalNodes.values());
    }

    public Node getNode(String nodeId) {
        return physicalNodes.get(nodeId);
    }

    public int getPhysicalNodeCount() {
        return physicalNodes.size();
    }

    public int getRingSize() {
        rwl.readLock().lock();
        try {
            return ring.size();
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int getVirtualNodes() {
        return virtualNodes;
    }

    /**
     * 64-bit MurmurHash3 implementation for high uniformity and speed.
     */
    public static long hash(byte[] data) {
        int length = data.length;
        int nblocks = length >> 3; // length / 8
        long h1 = 0x12345678L;
        long c1 = 0x87c37b91114253d5L;
        long c2 = 0x4cf5ad432745937fL;

        // Process 8-byte blocks
        for (int i = 0; i < nblocks; i++) {
            int idx = i << 3;
            long k1 = ((long) data[idx] & 0xff)
                    | (((long) data[idx + 1] & 0xff) << 8)
                    | (((long) data[idx + 2] & 0xff) << 16)
                    | (((long) data[idx + 3] & 0xff) << 24)
                    | (((long) data[idx + 4] & 0xff) << 32)
                    | (((long) data[idx + 5] & 0xff) << 40)
                    | (((long) data[idx + 6] & 0xff) << 48)
                    | (((long) data[idx + 7] & 0xff) << 56);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;

            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 = h1 * 5 + 0x52dce729L;
        }

        // Process tail bytes
        int tailStart = nblocks << 3;
        long k1 = 0;
        switch (length & 7) {
            case 7:
                k1 ^= ((long) data[tailStart + 6] & 0xff) << 48;
            case 6:
                k1 ^= ((long) data[tailStart + 5] & 0xff) << 40;
            case 5:
                k1 ^= ((long) data[tailStart + 4] & 0xff) << 32;
            case 4:
                k1 ^= ((long) data[tailStart + 3] & 0xff) << 24;
            case 3:
                k1 ^= ((long) data[tailStart + 2] & 0xff) << 16;
            case 2:
                k1 ^= ((long) data[tailStart + 1] & 0xff) << 8;
            case 1:
                k1 ^= ((long) data[tailStart] & 0xff);
                k1 *= c1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= c2;
                h1 ^= k1;
        }

        // Finalization
        h1 ^= length;
        h1 ^= h1 >>> 33;
        h1 *= 0xff51afd7ed558ccdL;
        h1 ^= h1 >>> 33;
        h1 *= 0xc4ceb9fe1a85ec53L;
        h1 ^= h1 >>> 33;

        return h1;
    }
}
