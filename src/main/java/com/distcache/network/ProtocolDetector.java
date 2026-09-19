package com.distcache.network;

import com.distcache.cluster.ClusterManager;
import com.distcache.protocol.BinaryProtocolFrame;
import com.distcache.protocol.NettyFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Protocol detector that inspects incoming connection headers and dynamically
 * configures the Netty pipeline for either the custom binary wire protocol
 * or HTTP REST / documentation endpoints.
 */
public class ProtocolDetector extends ByteToMessageDecoder {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolDetector.class);

    private final ClusterManager clusterManager;
    private final String host;
    private final int port;

    public ProtocolDetector(ClusterManager clusterManager, String host, int port) {
        this.clusterManager = clusterManager;
        this.host = host;
        this.port = port;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Need at least 2 bytes to distinguish binary magic (0xCAFE) from HTTP
        if (in.readableBytes() < 2) {
            return;
        }

        int readerIndex = in.readerIndex();
        short magic = in.getShort(readerIndex);

        ChannelPipeline p = ctx.pipeline();

        if (magic == BinaryProtocolFrame.MAGIC) {
            // Configure pipeline for custom binary protocol
            p.addLast("binaryCodec", new NettyFrameCodec());
            p.addLast("binaryHandler", new ServerHandler(clusterManager));
        } else if (isHttp(in, readerIndex)) {
            // Configure pipeline for HTTP / REST / Docs
            p.addLast("httpCodec", new HttpServerCodec());
            p.addLast("httpAggregator", new HttpObjectAggregator(10 * 1024 * 1024)); // 10MB
            p.addLast("httpHandler", new HttpApiHandler(clusterManager, host, port));
        } else {
            // Default fallback to binary protocol codec
            p.addLast("binaryCodec", new NettyFrameCodec());
            p.addLast("binaryHandler", new ServerHandler(clusterManager));
        }

        // Remove this detector handler; subsequent bytes flow directly through configured pipeline
        p.remove(this);
    }

    private static boolean isHttp(ByteBuf in, int index) {
        byte b1 = in.getByte(index);
        byte b2 = in.getByte(index + 1);

        // Check common HTTP method prefixes: GET, POST, PUT, DELETE, HEAD, OPTIONS
        return (b1 == 'G' && b2 == 'E') || // GET
               (b1 == 'P' && (b2 == 'O' || b2 == 'U')) || // POST, PUT
               (b1 == 'D' && b2 == 'E') || // DELETE
               (b1 == 'H' && b2 == 'E') || // HEAD
               (b1 == 'O' && b2 == 'P');   // OPTIONS
    }
}
