package com.distcache.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.CorruptedFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.zip.CRC32;

/**
 * Netty codec for encoding and decoding custom binary protocol frames with CRC32 integrity verification.
 */
public class NettyFrameCodec extends ByteToMessageCodec<BinaryProtocolFrame> {
    private static final Logger logger = LoggerFactory.getLogger(NettyFrameCodec.class);
    private static final int MAX_FRAME_SIZE = 64 * 1024 * 1024; // 64 MB max frame size

    public static void encodeFrame(BinaryProtocolFrame frame, ByteBuf out) {
        int startIndex = out.writerIndex();

        out.writeShort(frame.getMagic());
        out.writeByte(frame.getVersion());
        out.writeByte(frame.getOpCode().getCode());
        out.writeByte(frame.getFlags());
        out.writeByte(frame.getStatus().getCode());
        out.writeLong(frame.getCorrelationId());
        out.writeLong(frame.getTtlMillis());

        byte[] key = frame.getKey();
        byte[] value = frame.getValue();
        out.writeShort(key.length);
        out.writeInt(value.length);

        if (key.length > 0) {
            out.writeBytes(key);
        }
        if (value.length > 0) {
            out.writeBytes(value);
        }

        // Calculate CRC32 over the encoded payload
        int lengthBeforeCrc = out.writerIndex() - startIndex;
        byte[] bytesForCrc = new byte[lengthBeforeCrc];
        out.getBytes(startIndex, bytesForCrc);

        CRC32 crc = new CRC32();
        crc.update(bytesForCrc);
        int checksum = (int) crc.getValue();
        out.writeInt(checksum);
    }

    public static BinaryProtocolFrame decodeFrame(ByteBuf in) {
        // Need at least header fixed size (28 bytes)
        if (in.readableBytes() < BinaryProtocolFrame.HEADER_FIXED_SIZE) {
            return null;
        }

        in.markReaderIndex();
        short magic = in.readShort();
        if (magic != BinaryProtocolFrame.MAGIC) {
            in.resetReaderIndex();
            // Discard invalid byte to scan for next magic byte
            in.readByte();
            throw new CorruptedFrameException(
                    "Invalid magic byte: 0x" + Integer.toHexString(magic & 0xFFFF)
            );
        }

        byte version = in.readByte();
        byte opCodeByte = in.readByte();
        byte flags = in.readByte();
        byte statusByte = in.readByte();
        long correlationId = in.readLong();
        long ttlMillis = in.readLong();
        int keyLength = in.readUnsignedShort();
        int valLength = in.readInt();

        if (keyLength < 0 || valLength < 0 || (keyLength + valLength) > MAX_FRAME_SIZE) {
            throw new CorruptedFrameException("Frame payload exceeds max allowable size: " + (keyLength + valLength));
        }

        int remainingExpected = keyLength + valLength + 4; // payload + CRC32
        if (in.readableBytes() < remainingExpected) {
            in.resetReaderIndex();
            return null;
        }

        // Reset to read frame for CRC verification
        in.resetReaderIndex();
        int totalPayloadBytes = BinaryProtocolFrame.HEADER_FIXED_SIZE + keyLength + valLength;
        byte[] frameBytes = new byte[totalPayloadBytes];
        in.readBytes(frameBytes);
        int receivedCrc = in.readInt();

        CRC32 crc = new CRC32();
        crc.update(frameBytes);
        int calculatedCrc = (int) crc.getValue();

        if (calculatedCrc != receivedCrc) {
            logger.warn("CRC32 mismatch on correlationId {}: calculated=0x{}, received=0x{}",
                    correlationId, Integer.toHexString(calculatedCrc), Integer.toHexString(receivedCrc));
            throw new CorruptedFrameException("CRC32 mismatch! Frame corrupted in transit.");
        }

        // Extract key and value from frameBytes
        // Offset for key: 28 (HEADER_FIXED_SIZE)
        byte[] key = new byte[keyLength];
        System.arraycopy(frameBytes, BinaryProtocolFrame.HEADER_FIXED_SIZE, key, 0, keyLength);

        byte[] value = new byte[valLength];
        System.arraycopy(frameBytes, BinaryProtocolFrame.HEADER_FIXED_SIZE + keyLength, value, 0, valLength);

        OpCode opCode = OpCode.fromCode(opCodeByte);
        StatusCode status = StatusCode.fromCode(statusByte);

        BinaryProtocolFrame frame = new BinaryProtocolFrame(
                magic,
                version,
                opCode,
                flags,
                status,
                correlationId,
                ttlMillis,
                key,
                value
        );
        frame.setChecksum(receivedCrc);
        return frame;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BinaryProtocolFrame frame, ByteBuf out) {
        encodeFrame(frame, out);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        BinaryProtocolFrame frame = decodeFrame(in);
        if (frame != null) {
            out.add(frame);
        }
    }
}
