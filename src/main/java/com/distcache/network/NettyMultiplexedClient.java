package com.distcache.network;

import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.NettyFrameCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking, multiplexed Netty TCP client.
 *
 * Multiplexes hundreds of concurrent requests over single persistent TCP connections
 * using unique correlation IDs and CompletableFutures, keeping p99 latencies low.
 */
public class NettyMultiplexedClient implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(NettyMultiplexedClient.class);

    private final EventLoopGroup eventLoopGroup;
    private final boolean externalEventLoop;
    private final Map<String, Channel> connectionPool = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<BinaryProtocolFrame>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong correlationSequence = new AtomicLong(1);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public NettyMultiplexedClient() {
        this(new NioEventLoopGroup(Runtime.getRuntime().availableProcessors()), false);
    }

    public NettyMultiplexedClient(EventLoopGroup eventLoopGroup, boolean externalEventLoop) {
        this.eventLoopGroup = eventLoopGroup;
        this.externalEventLoop = externalEventLoop;
    }

    public long nextCorrelationId() {
        return correlationSequence.getAndIncrement();
    }

    public CompletableFuture<BinaryProtocolFrame> sendAsync(String host, int port, BinaryProtocolFrame request) {
        CompletableFuture<BinaryProtocolFrame> future = new CompletableFuture<>();
        if (!running.get()) {
            future.completeExceptionally(new IllegalStateException("NettyMultiplexedClient is closed"));
            return future;
        }

        try {
            Channel channel = getOrCreateConnection(host, port);
            pendingRequests.put(request.getCorrelationId(), future);

            channel.writeAndFlush(request).addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    pendingRequests.remove(request.getCorrelationId());
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            pendingRequests.remove(request.getCorrelationId());
            future.completeExceptionally(e);
        }

        return future;
    }

    public BinaryProtocolFrame sendSync(String host, int port, BinaryProtocolFrame request, long timeoutMillis)
            throws Exception {
        return sendAsync(host, port, request).get(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized Channel getOrCreateConnection(String host, int port) throws Exception {
        String key = host + ":" + port;
        Channel channel = connectionPool.get(key);
        if (channel != null && channel.isActive()) {
            return channel;
        }

        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new NettyFrameCodec());
                        p.addLast(new ClientResponseHandler());
                    }
                });

        ChannelFuture connectFuture = b.connect(new InetSocketAddress(host, port)).sync();
        final Channel newChannel = connectFuture.channel();
        connectionPool.put(key, newChannel);

        newChannel.closeFuture().addListener(f -> {
            connectionPool.remove(key, newChannel);
            logger.info("Connection to {} closed", key);
        });

        logger.info("Connected multiplexed TCP channel to {}", key);
        return newChannel;
    }

    private class ClientResponseHandler extends SimpleChannelInboundHandler<BinaryProtocolFrame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BinaryProtocolFrame frame) {
            CompletableFuture<BinaryProtocolFrame> future = pendingRequests.remove(frame.getCorrelationId());
            if (future != null) {
                future.complete(frame);
            } else {
                logger.warn("Received response for unknown correlationId: {}", frame.getCorrelationId());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Client channel error: {}", cause.getMessage());
            ctx.close();
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            for (Channel ch : connectionPool.values()) {
                if (ch.isOpen()) ch.close();
            }
            connectionPool.clear();

            for (CompletableFuture<BinaryProtocolFrame> f : pendingRequests.values()) {
                f.completeExceptionally(new ClosedChannelException());
            }
            pendingRequests.clear();

            if (!externalEventLoop) {
                eventLoopGroup.shutdownGracefully();
            }
            logger.info("NettyMultiplexedClient closed");
        }
    }
}
