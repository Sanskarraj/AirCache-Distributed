package com.distcache.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ProtocolCodecTest {

    @Test
    public void testEncodeAndDecodeFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyFrameCodec());

        byte[] key = "user:1234".getBytes(StandardCharsets.UTF_8);
        byte[] value = "{\"name\":\"John Doe\",\"age\":30}".getBytes(StandardCharsets.UTF_8);
        long correlationId = 9876543210L;

        BinaryProtocolFrame originalFrame = BinaryProtocolFrame.createRequest(
                OpCode.PUT, correlationId, key, value, 60000L);

        // Encode
        assertTrue(channel.writeOutbound(originalFrame));
        ByteBuf encodedBuf = channel.readOutbound();
        assertNotNull(encodedBuf);

        // Decode
        assertTrue(channel.writeInbound(encodedBuf));
        BinaryProtocolFrame decodedFrame = channel.readInbound();
        assertNotNull(decodedFrame);

        assertEquals(OpCode.PUT, decodedFrame.getOpCode());
        assertEquals(correlationId, decodedFrame.getCorrelationId());
        assertEquals(60000L, decodedFrame.getTtlMillis());
        assertArrayEquals(key, decodedFrame.getKey());
        assertArrayEquals(value, decodedFrame.getValue());
        assertEquals(BinaryProtocolFrame.FLAG_REQUEST, decodedFrame.getFlags());
        assertEquals(StatusCode.SUCCESS, decodedFrame.getStatus());
    }

    @Test
    public void testCrcCorruptionDetection() {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyFrameCodec());

        byte[] key = "secure-key".getBytes(StandardCharsets.UTF_8);
        byte[] val = "sensitive-val".getBytes(StandardCharsets.UTF_8);
        BinaryProtocolFrame frame = BinaryProtocolFrame.createRequest(OpCode.PUT, 42L, key, val, 0L);

        channel.writeOutbound(frame);
        ByteBuf encoded = channel.readOutbound();

        // Corrupt a byte in the payload
        int byteToCorrupt = BinaryProtocolFrame.HEADER_FIXED_SIZE + 2;
        byte originalByte = encoded.getByte(byteToCorrupt);
        encoded.setByte(byteToCorrupt, (byte) (originalByte ^ 0xFF));

        // Expect CorruptedFrameException on decode
        assertThrows(CorruptedFrameException.class, () -> {
            channel.writeInbound(encoded);
        });
    }
}
