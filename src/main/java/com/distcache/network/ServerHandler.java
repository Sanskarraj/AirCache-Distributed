package com.distcache.network;

import com.distcache.cluster.ClusterManager;
import com.distcache.cluster.Node;
import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.OpCode;
import com.distcache.protocol.StatusCode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Inbound Netty handler for processing cache protocol frames.
 */
public class ServerHandler extends SimpleChannelInboundHandler<BinaryProtocolFrame> {
    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);

    private final ClusterManager clusterManager;

    public ServerHandler(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryProtocolFrame frame) {
        long correlationId = frame.getCorrelationId();

        switch (frame.getOpCode()) {
            case PING -> {
                BinaryProtocolFrame pong = BinaryProtocolFrame.createResponse(
                        OpCode.PING,
                        correlationId,
                        StatusCode.SUCCESS,
                        frame.getKey(),
                        "PONG".getBytes(StandardCharsets.UTF_8)
                );
                ctx.writeAndFlush(pong);
            }

            case CLUSTER_INFO -> {
                Collection<Node> nodes = clusterManager.getRouter().getAllNodes();
                StringBuilder sb = new StringBuilder();
                sb.append("Cluster Nodes (").append(nodes.size()).append("):\n");
                for (Node n : nodes) {
                    sb.append(" - ").append(n.getNodeId())
                            .append(" (cache=").append(n.getCacheAddress())
                            .append(", status=").append(n.getStatus()).append(")\n");
                }
                sb.append("Raft Leader: ").append(clusterManager.getRaftNode().getCurrentLeader()).append("\n");
                sb.append("Raft Term: ").append(clusterManager.getRaftNode().getCurrentTerm()).append("\n");

                BinaryProtocolFrame response = BinaryProtocolFrame.createResponse(
                        OpCode.CLUSTER_INFO,
                        correlationId,
                        StatusCode.SUCCESS,
                        null,
                        sb.toString().getBytes(StandardCharsets.UTF_8)
                );
                ctx.writeAndFlush(response);
            }

            case RAFT_MESSAGE -> {
                BinaryProtocolFrame response = clusterManager.handleRaftMessage(frame);
                ctx.writeAndFlush(response);
            }

            case GET -> {
                clusterManager.handleGet(frame.getKey(), correlationId, false)
                        .thenAccept(ctx::writeAndFlush)
                        .exceptionally(ex -> {
                            ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                                    OpCode.GET, correlationId, StatusCode.SERVER_ERROR, ex.getMessage()));
                            return null;
                        });
            }

            case FORWARD_GET -> {
                clusterManager.handleGet(frame.getKey(), correlationId, true)
                        .thenAccept(ctx::writeAndFlush)
                        .exceptionally(ex -> {
                            ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                                    OpCode.FORWARD_GET, correlationId, StatusCode.SERVER_ERROR, ex.getMessage()));
                            return null;
                        });
            }

            case PUT -> {
                clusterManager.handlePut(frame.getKey(), frame.getValue(), frame.getTtlMillis(), correlationId, false)
                        .thenAccept(ctx::writeAndFlush)
                        .exceptionally(ex -> {
                            ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                                    OpCode.PUT, correlationId, StatusCode.SERVER_ERROR, ex.getMessage()));
                            return null;
                        });
            }

            case FORWARD_PUT -> {
                clusterManager.handlePut(frame.getKey(), frame.getValue(), frame.getTtlMillis(), correlationId, true)
                        .thenAccept(ctx::writeAndFlush)
                        .exceptionally(ex -> {
                            ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                                    OpCode.FORWARD_PUT, correlationId, StatusCode.SERVER_ERROR, ex.getMessage()));
                            return null;
                        });
            }

            case DELETE -> {
                clusterManager.handleDelete(frame.getKey(), correlationId, false)
                        .thenAccept(ctx::writeAndFlush)
                        .exceptionally(ex -> {
                            ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                                    OpCode.DELETE, correlationId, StatusCode.SERVER_ERROR, ex.getMessage()));
                            return null;
                        });
            }

            case FORWARD_DELETE -> {
                clusterManager.handleDelete(frame.getKey(), correlationId, true)
                        .thenAccept(ctx::writeAndFlush)
                        .exceptionally(ex -> {
                            ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                                    OpCode.FORWARD_DELETE, correlationId, StatusCode.SERVER_ERROR, ex.getMessage()));
                            return null;
                        });
            }

            default -> {
                ctx.writeAndFlush(BinaryProtocolFrame.createErrorResponse(
                        frame.getOpCode(),
                        correlationId,
                        StatusCode.SERVER_ERROR,
                        "Unsupported OpCode"
                ));
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Netty server handler exception: {}", cause.getMessage());
        ctx.close();
    }
}
