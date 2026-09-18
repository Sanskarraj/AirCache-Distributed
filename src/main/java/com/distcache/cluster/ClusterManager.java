package com.distcache.cluster;

import com.distcache.consensus.RaftMessage;
import com.distcache.consensus.RaftNode;
import com.distcache.core.CacheEngine;
import com.distcache.network.NettyMultiplexedClient;
import com.distcache.persistence.WriteBehindBuffer;
import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.OpCode;
import com.distcache.protocol.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * Cluster manager coordinating local cache operations, consistent hash routing,
 * write-behind persistence, peer proxying, and Raft discovery.
 */
public class ClusterManager implements RaftNode.ClusterMembershipListener, RaftNode.RaftTransport, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);

    private final Node localNode;
    private final CacheEngine cacheEngine;
    private final WriteBehindBuffer writeBehindBuffer;
    private final ConsistentHashRouter router;
    private final NettyMultiplexedClient peerClient;
    private final RaftNode raftNode;

    public ClusterManager(
            Node localNode,
            CacheEngine cacheEngine,
            WriteBehindBuffer writeBehindBuffer,
            ConsistentHashRouter router,
            NettyMultiplexedClient peerClient
    ) {
        this.localNode = localNode;
        this.cacheEngine = cacheEngine;
        this.writeBehindBuffer = writeBehindBuffer;
        this.router = router;
        this.peerClient = peerClient;

        this.router.addNode(localNode);
        this.raftNode = new RaftNode(localNode.getNodeId(), this, this);
    }

    public void start(Collection<Node> seedNodes) {
        if (seedNodes != null) {
            for (Node peer : seedNodes) {
                if (!peer.getNodeId().equals(localNode.getNodeId())) {
                    raftNode.addPeer(peer);
                    router.addNode(peer);
                }
            }
        }
        raftNode.start();
        logger.info("ClusterManager started for localNode: {}", localNode);
    }

    public CompletableFuture<BinaryProtocolFrame> handleGet(byte[] key, long correlationId, boolean isForwarded) {
        if (isForwarded || router.isLocalKey(key, localNode.getNodeId())) {
            byte[] value = cacheEngine.get(key);
            if (value != null) {
                return CompletableFuture.completedFuture(
                        BinaryProtocolFrame.createResponse(OpCode.GET, correlationId, StatusCode.SUCCESS, key, value)
                );
            } else {
                return CompletableFuture.completedFuture(
                        BinaryProtocolFrame.createResponse(OpCode.GET, correlationId, StatusCode.KEY_NOT_FOUND, key, null)
                );
            }
        }

        // Remote key -> route to owner
        Node owner = router.route(key);
        if (owner == null || owner.getNodeId().equals(localNode.getNodeId())) {
            byte[] value = cacheEngine.get(key);
            StatusCode status = (value != null) ? StatusCode.SUCCESS : StatusCode.KEY_NOT_FOUND;
            return CompletableFuture.completedFuture(
                    BinaryProtocolFrame.createResponse(OpCode.GET, correlationId, status, key, value)
            );
        }

        // Forward to remote peer over multiplexed Netty client
        BinaryProtocolFrame forwardReq = BinaryProtocolFrame.createRequest(
                OpCode.FORWARD_GET,
                peerClient.nextCorrelationId(),
                key,
                null,
                0L
        );

        return peerClient.sendAsync(owner.getHost(), owner.getCachePort(), forwardReq)
                .thenApply(resp -> BinaryProtocolFrame.createResponse(
                        OpCode.GET,
                        correlationId,
                        resp.getStatus(),
                        resp.getKey(),
                        resp.getValue()
                ));
    }

    public CompletableFuture<BinaryProtocolFrame> handlePut(
            byte[] key,
            byte[] value,
            long ttlMillis,
            long correlationId,
            boolean isForwarded
    ) {
        if (isForwarded || router.isLocalKey(key, localNode.getNodeId())) {
            cacheEngine.put(key, value, ttlMillis);
            if (writeBehindBuffer != null) {
                writeBehindBuffer.enqueuePut(key, value, ttlMillis);
            }
            return CompletableFuture.completedFuture(
                    BinaryProtocolFrame.createResponse(OpCode.PUT, correlationId, StatusCode.SUCCESS, key, null)
            );
        }

        Node owner = router.route(key);
        if (owner == null || owner.getNodeId().equals(localNode.getNodeId())) {
            cacheEngine.put(key, value, ttlMillis);
            if (writeBehindBuffer != null) {
                writeBehindBuffer.enqueuePut(key, value, ttlMillis);
            }
            return CompletableFuture.completedFuture(
                    BinaryProtocolFrame.createResponse(OpCode.PUT, correlationId, StatusCode.SUCCESS, key, null)
            );
        }

        BinaryProtocolFrame forwardReq = BinaryProtocolFrame.createRequest(
                OpCode.FORWARD_PUT,
                peerClient.nextCorrelationId(),
                key,
                value,
                ttlMillis
        );

        return peerClient.sendAsync(owner.getHost(), owner.getCachePort(), forwardReq)
                .thenApply(resp -> BinaryProtocolFrame.createResponse(
                        OpCode.PUT,
                        correlationId,
                        resp.getStatus(),
                        key,
                        null
                ));
    }

    public CompletableFuture<BinaryProtocolFrame> handleDelete(byte[] key, long correlationId, boolean isForwarded) {
        if (isForwarded || router.isLocalKey(key, localNode.getNodeId())) {
            boolean deleted = cacheEngine.delete(key);
            if (writeBehindBuffer != null) {
                writeBehindBuffer.enqueueDelete(key);
            }
            StatusCode status = deleted ? StatusCode.SUCCESS : StatusCode.KEY_NOT_FOUND;
            return CompletableFuture.completedFuture(
                    BinaryProtocolFrame.createResponse(OpCode.DELETE, correlationId, status, key, null)
            );
        }

        Node owner = router.route(key);
        if (owner == null || owner.getNodeId().equals(localNode.getNodeId())) {
            boolean deleted = cacheEngine.delete(key);
            if (writeBehindBuffer != null) {
                writeBehindBuffer.enqueueDelete(key);
            }
            StatusCode status = deleted ? StatusCode.SUCCESS : StatusCode.KEY_NOT_FOUND;
            return CompletableFuture.completedFuture(
                    BinaryProtocolFrame.createResponse(OpCode.DELETE, correlationId, status, key, null)
            );
        }

        BinaryProtocolFrame forwardReq = BinaryProtocolFrame.createRequest(
                OpCode.FORWARD_DELETE,
                peerClient.nextCorrelationId(),
                key,
                null,
                0L
        );

        return peerClient.sendAsync(owner.getHost(), owner.getCachePort(), forwardReq)
                .thenApply(resp -> BinaryProtocolFrame.createResponse(
                        OpCode.DELETE,
                        correlationId,
                        resp.getStatus(),
                        key,
                        null
                ));
    }

    public BinaryProtocolFrame handleRaftMessage(BinaryProtocolFrame frame) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(frame.getValue());
            ObjectInputStream ois = new ObjectInputStream(bais);
            RaftMessage msg = (RaftMessage) ois.readObject();

            RaftMessage reply = raftNode.handleMessage(msg);
            if (reply != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos);
                oos.writeObject(reply);
                oos.flush();
                return BinaryProtocolFrame.createResponse(
                        OpCode.RAFT_MESSAGE,
                        frame.getCorrelationId(),
                        StatusCode.SUCCESS,
                        null,
                        baos.toByteArray()
                );
            } else {
                return BinaryProtocolFrame.createResponse(
                        OpCode.RAFT_MESSAGE,
                        frame.getCorrelationId(),
                        StatusCode.SUCCESS,
                        null,
                        new byte[0]
                );
            }
        } catch (Exception e) {
            logger.error("Failed to process Raft message", e);
            return BinaryProtocolFrame.createErrorResponse(
                    OpCode.RAFT_MESSAGE,
                    frame.getCorrelationId(),
                    StatusCode.SERVER_ERROR,
                    e.getMessage()
            );
        }
    }

    @Override
    public void send(String targetNodeId, RaftMessage message) {
        Node target = router.getNode(targetNodeId);
        if (target == null) {
            target = router.getAllNodes().stream()
                    .filter(n -> n.getNodeId().equals(targetNodeId))
                    .findFirst()
                    .orElse(null);
        }

        if (target == null) {
            logger.debug("Cannot send Raft message to unknown target: {}", targetNodeId);
            return;
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(message);
            oos.flush();

            long correlationId = peerClient.nextCorrelationId();
            BinaryProtocolFrame frame = BinaryProtocolFrame.createRequest(
                    OpCode.RAFT_MESSAGE,
                    correlationId,
                    null,
                    baos.toByteArray(),
                    0L
            );

            peerClient.sendAsync(target.getHost(), target.getCachePort(), frame)
                    .thenAccept(resp -> {
                        if (resp.getValue() != null && resp.getValue().length > 0) {
                            try {
                                ByteArrayInputStream bais = new ByteArrayInputStream(resp.getValue());
                                ObjectInputStream ois = new ObjectInputStream(bais);
                                RaftMessage replyMsg = (RaftMessage) ois.readObject();
                                raftNode.handleMessage(replyMsg);
                            } catch (Exception ex) {
                                logger.error("Failed to parse Raft response from {}", targetNodeId, ex);
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        logger.debug("Failed sending Raft message to {}: {}", targetNodeId, ex.getMessage());
                        return null;
                    });
        } catch (IOException e) {
            logger.error("Error serializing Raft message", e);
        }
    }

    @Override
    public void onNodeJoined(Node node) {
        logger.info("Cluster membership event: Node joined -> {}", node);
        router.addNode(node);
        raftNode.addPeer(node);
    }

    @Override
    public void onNodeLeft(Node node) {
        logger.info("Cluster membership event: Node left -> {}", node);
        router.removeNode(node.getNodeId());
        raftNode.removePeer(node.getNodeId());
    }

    public Node getLocalNode() {
        return localNode;
    }

    public CacheEngine getCacheEngine() {
        return cacheEngine;
    }

    public ConsistentHashRouter getRouter() {
        return router;
    }

    public RaftNode getRaftNode() {
        return raftNode;
    }

    @Override
    public void close() throws IOException {
        raftNode.stop();
        if (writeBehindBuffer != null) {
            writeBehindBuffer.close();
        }
        peerClient.close();
    }
}
