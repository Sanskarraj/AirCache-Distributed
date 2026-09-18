package com.distcache.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Netty Length-Prepended JSON Codec for TCP Socket Communication.
 * Format: [4B Length Prefix][UTF-8 JSON Payload]
 */
public class JsonFrameCodec extends ByteToMessageCodec<JsonFrameCodec.JsonPayload> {
    private static final ObjectMapper mapper = new ObjectMapper();

    public record JsonPayload(
            String op,
            long correlationId,
            String key,
            String value,
            long ttl,
            int status
    ) {}

    @Override
    protected void encode(ChannelHandlerContext ctx, JsonPayload msg, ByteBuf out) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(msg);
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 4) return;

        in.markReaderIndex();
        int length = in.readInt();
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        JsonPayload payload = mapper.readValue(bytes, JsonPayload.class);
        out.add(payload);
    }
}
