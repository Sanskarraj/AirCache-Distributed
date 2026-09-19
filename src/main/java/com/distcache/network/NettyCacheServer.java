package com.distcache.network;

import com.distcache.cluster.ClusterManager;
import com.distcache.protocol.NettyFrameCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking TCP Cache Server powered by Netty event loops.
 */
public class NettyCacheServer implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(NettyCacheServer.class);

    private final String host;
    private final int port;
    private final ClusterManager clusterManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NettyCacheServer(String host, int port, ClusterManager clusterManager) {
        this.host = host;
        this.port = port;
        this.clusterManager = clusterManager;
    }

    public synchronized void start() throws Exception {
        if (running.compareAndSet(false, true)) {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new ProtocolDetector(clusterManager, host, port));
                        }
                    });

            ChannelFuture f = b.bind(new InetSocketAddress(host, port)).sync();
            serverChannel = f.channel();
            logger.info(">>> NettyCacheServer listening on {}:{} <<<", host, port);
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    @Override
    public synchronized void close() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping NettyCacheServer on port {}...", port);
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            logger.info("NettyCacheServer stopped successfully");
        }
    }
}
