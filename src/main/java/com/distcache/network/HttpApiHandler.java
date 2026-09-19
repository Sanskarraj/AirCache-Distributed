package com.distcache.network;

import com.distcache.cluster.ClusterManager;
import com.distcache.cluster.Node;
import com.distcache.protocol.StatusCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Netty HTTP handler providing RESTful cache endpoints, cluster inspection,
 * and comprehensive documentation (/docs, /api/docs) with content negotiation.
 */
public class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(HttpApiHandler.class);

    private final ClusterManager clusterManager;
    private final String host;
    private final int port;

    public HttpApiHandler(ClusterManager clusterManager, String host, int port) {
        this.clusterManager = clusterManager;
        this.host = host;
        this.port = port;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        String path = decoder.path();
        HttpMethod method = req.method();

        // Handle CORS preflight
        if (HttpMethod.OPTIONS.equals(method)) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK);
            addCorsHeaders(resp);
            ctx.writeAndFlush(resp);
            return;
        }

        // Route: / or /docs or /docs.md or /api/docs
        if ("/".equals(path) || "/docs".equals(path) || "/docs.html".equals(path)) {
            handleDocs(ctx, req, decoder);
            return;
        }

        if ("/api/docs".equals(path)) {
            sendResponse(ctx, OK, "application/json; charset=UTF-8",
                    DocsContentProvider.getJsonDocs(host, port, clusterManager.getLocalNode().getNodeId()));
            return;
        }

        if ("/docs.md".equals(path) || "/docs/markdown".equals(path)) {
            sendResponse(ctx, OK, "text/markdown; charset=UTF-8",
                    DocsContentProvider.getMarkdownDocs(host, port, clusterManager.getLocalNode().getNodeId()));
            return;
        }

        // Route: /cluster/info or /api/cluster
        if ("/cluster/info".equals(path) || "/api/cluster".equals(path)) {
            handleClusterInfo(ctx, req);
            return;
        }

        // Route: /health or /metrics
        if ("/health".equals(path) || "/metrics".equals(path)) {
            handleHealth(ctx);
            return;
        }

        // Route: /cache/{key}
        if (path.startsWith("/cache/")) {
            String rawKey = path.substring("/cache/".length());
            if (rawKey.isEmpty()) {
                sendError(ctx, BAD_REQUEST, "Missing cache key in path. Example: /cache/my-key");
                return;
            }

            String keyStr = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            byte[] keyBytes = keyStr.getBytes(StandardCharsets.UTF_8);
            long correlationId = ThreadLocalRandom.current().nextLong();

            if (HttpMethod.GET.equals(method)) {
                clusterManager.handleGet(keyBytes, correlationId, false)
                        .thenAccept(frame -> {
                            if (frame.getStatus() == StatusCode.SUCCESS && frame.getValue() != null) {
                                ByteBuf buf = Unpooled.wrappedBuffer(frame.getValue());
                                FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK, buf);
                                resp.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");
                                resp.headers().set(CONTENT_LENGTH, frame.getValue().length);
                                resp.headers().set("X-Cache-Status", "HIT");
                                addCorsHeaders(resp);
                                ctx.writeAndFlush(resp);
                            } else {
                                sendError(ctx, NOT_FOUND, "Key not found: " + keyStr);
                            }
                        })
                        .exceptionally(ex -> {
                            sendError(ctx, INTERNAL_SERVER_ERROR, "Error reading cache: " + ex.getMessage());
                            return null;
                        });
                return;
            }

            if (HttpMethod.PUT.equals(method) || HttpMethod.POST.equals(method)) {
                ByteBuf content = req.content();
                byte[] valBytes = new byte[content.readableBytes()];
                content.readBytes(valBytes);

                long parsedTtl = 0L;
                List<String> ttlParams = decoder.parameters().get("ttl");
                if (ttlParams != null && !ttlParams.isEmpty()) {
                    try {
                        parsedTtl = Long.parseLong(ttlParams.get(0));
                    } catch (NumberFormatException ignored) {}
                }
                String ttlHeader = req.headers().get("X-TTL-Millis");
                if (ttlHeader != null && !ttlHeader.isEmpty()) {
                    try {
                        parsedTtl = Long.parseLong(ttlHeader);
                    } catch (NumberFormatException ignored) {}
                }
                final long ttlMillis = parsedTtl;

                clusterManager.handlePut(keyBytes, valBytes, ttlMillis, correlationId, false)
                        .thenAccept(frame -> {
                            if (frame.getStatus() == StatusCode.SUCCESS) {
                                String json = String.format("{\"status\":\"OK\",\"key\":\"%s\",\"bytes\":%d,\"ttlMillis\":%d}",
                                        keyStr, valBytes.length, ttlMillis);
                                sendResponse(ctx, OK, "application/json; charset=UTF-8", json);
                            } else {
                                sendError(ctx, INTERNAL_SERVER_ERROR, "Failed to store key: status=" + frame.getStatus());
                            }
                        })
                        .exceptionally(ex -> {
                            sendError(ctx, INTERNAL_SERVER_ERROR, "Error storing key: " + ex.getMessage());
                            return null;
                        });
                return;
            }

            if (HttpMethod.DELETE.equals(method)) {
                clusterManager.handleDelete(keyBytes, correlationId, false)
                        .thenAccept(frame -> {
                            if (frame.getStatus() == StatusCode.SUCCESS) {
                                String json = String.format("{\"status\":\"DELETED\",\"key\":\"%s\"}", keyStr);
                                sendResponse(ctx, OK, "application/json; charset=UTF-8", json);
                            } else {
                                sendError(ctx, NOT_FOUND, "Key not found for deletion: " + keyStr);
                            }
                        })
                        .exceptionally(ex -> {
                            sendError(ctx, INTERNAL_SERVER_ERROR, "Error deleting key: " + ex.getMessage());
                            return null;
                        });
                return;
            }

            sendError(ctx, METHOD_NOT_ALLOWED, "Method " + method.name() + " not allowed for /cache/{key}");
            return;
        }

        // Unknown endpoint
        sendError(ctx, NOT_FOUND, "Endpoint not found: " + path + ". View documentation at http://" + host + ":" + port + "/docs");
    }

    private void handleDocs(ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder decoder) {
        String nodeId = clusterManager.getLocalNode().getNodeId();
        List<String> formatParam = decoder.parameters().get("format");
        String format = (formatParam != null && !formatParam.isEmpty()) ? formatParam.get(0).toLowerCase() : "";

        String accept = req.headers().get(ACCEPT, "").toLowerCase();
        String userAgent = req.headers().get(USER_AGENT, "").toLowerCase();

        if ("json".equals(format) || (format.isEmpty() && accept.contains("application/json") && !accept.contains("text/html"))) {
            sendResponse(ctx, OK, "application/json; charset=UTF-8", DocsContentProvider.getJsonDocs(host, port, nodeId));
        } else if ("markdown".equals(format) || "md".equals(format) || "text".equals(format)
                || (format.isEmpty() && (accept.contains("text/markdown") || (userAgent.startsWith("curl") && !accept.contains("text/html"))))) {
            sendResponse(ctx, OK, "text/markdown; charset=UTF-8", DocsContentProvider.getMarkdownDocs(host, port, nodeId));
        } else {
            sendResponse(ctx, OK, "text/html; charset=UTF-8", DocsContentProvider.getHtmlDocs(host, port, nodeId));
        }
    }

    private void handleClusterInfo(ChannelHandlerContext ctx, FullHttpRequest req) {
        Collection<Node> nodes = clusterManager.getRouter().getAllNodes();
        String leaderId = clusterManager.getRaftNode().getCurrentLeader();
        long term = clusterManager.getRaftNode().getCurrentTerm();

        String accept = req.headers().get(ACCEPT, "").toLowerCase();
        if (accept.contains("text/plain")) {
            StringBuilder sb = new StringBuilder();
            sb.append("Cluster Nodes (").append(nodes.size()).append("):\n");
            for (Node n : nodes) {
                sb.append(" - ").append(n.getNodeId())
                        .append(" (cache=").append(n.getCacheAddress())
                        .append(", status=").append(n.getStatus()).append(")\n");
            }
            sb.append("Raft Leader: ").append(leaderId).append("\n");
            sb.append("Raft Term: ").append(term).append("\n");
            sendResponse(ctx, OK, "text/plain; charset=UTF-8", sb.toString());
            return;
        }

        // Return JSON
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"localNode\": \"").append(clusterManager.getLocalNode().getNodeId()).append("\",\n");
        json.append("  \"raftLeader\": \"").append(leaderId != null ? leaderId : "NONE").append("\",\n");
        json.append("  \"raftTerm\": ").append(term).append(",\n");
        json.append("  \"totalNodes\": ").append(nodes.size()).append(",\n");
        json.append("  \"nodes\": [\n");
        int idx = 0;
        for (Node n : nodes) {
            json.append(String.format("    {\"id\": \"%s\", \"cacheAddress\": \"%s\", \"raftAddress\": \"%s\", \"status\": \"%s\"}",
                    n.getNodeId(), n.getCacheAddress(), n.getRaftAddress(), n.getStatus()));
            if (++idx < nodes.size()) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n}");

        sendResponse(ctx, OK, "application/json; charset=UTF-8", json.toString());
    }

    private void handleHealth(ChannelHandlerContext ctx) {
        Runtime rt = Runtime.getRuntime();
        long freeMem = rt.freeMemory();
        long totalMem = rt.totalMemory();
        long maxMem = rt.maxMemory();
        long usedMem = totalMem - freeMem;
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        String json = String.format("""
{
  "status": "UP",
  "nodeId": "%s",
  "uptimeMs": %d,
  "processors": %d,
  "memory": {
    "usedMb": %.2f,
    "totalMb": %.2f,
    "maxMb": %.2f
  }
}""",
                clusterManager.getLocalNode().getNodeId(),
                uptimeMs,
                rt.availableProcessors(),
                usedMem / (1024.0 * 1024.0),
                totalMem / (1024.0 * 1024.0),
                maxMem / (1024.0 * 1024.0)
        );

        sendResponse(ctx, OK, "application/json; charset=UTF-8", json);
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, status, buf);
        resp.headers().set(CONTENT_TYPE, contentType);
        resp.headers().set(CONTENT_LENGTH, bytes.length);
        addCorsHeaders(resp);
        ctx.writeAndFlush(resp);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        String json = String.format("{\"error\": true, \"status\": %d, \"message\": \"%s\"}",
                status.code(), message.replace("\"", "\\\""));
        sendResponse(ctx, status, "application/json; charset=UTF-8", json);
    }

    private void addCorsHeaders(FullHttpResponse resp) {
        resp.headers().set("Access-Control-Allow-Origin", "*");
        resp.headers().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.headers().set("Access-Control-Allow-Headers", "Content-Type, X-TTL-Millis, Authorization");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("HTTP handler exception: {}", cause.getMessage());
        ctx.close();
    }
}
