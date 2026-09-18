package com.distcache.protocol;

import java.util.Arrays;

/**
 * Binary protocol frame representation.
 *
 * Wire format layout:
 * [MAGIC: 2B (0xCAFE)]
 * [VERSION: 1B]
 * [OPCODE: 1B]
 * [FLAGS: 1B]
 * [STATUS: 1B]
 * [CORRELATION_ID: 8B]
 * [TTL_MILLIS: 8B]
 * [KEY_LENGTH: 2B]
 * [VAL_LENGTH: 4B]
 * [KEY: var bytes]
 * [VAL: var bytes]
 * [CRC32: 4B]
 */
public class BinaryProtocolFrame {
    public static final short MAGIC = (short) 0xCAFE;
    public static final byte VERSION = (byte) 0x01;
    public static final int HEADER_FIXED_SIZE = 2 + 1 + 1 + 1 + 1 + 8 + 8 + 2 + 4; // 28 bytes

    public static final byte FLAG_REQUEST = 0x00;
    public static final byte FLAG_RESPONSE = 0x01;
    public static final byte FLAG_ERROR = 0x02;

    private final short magic;
    private final byte version;
    private final OpCode opCode;
    private final byte flags;
    private final StatusCode status;
    private final long correlationId;
    private final long ttlMillis;
    private final byte[] key;
    private final byte[] value;
    private int checksum;

    public BinaryProtocolFrame(
            short magic,
            byte version,
            OpCode opCode,
            byte flags,
            StatusCode status,
            long correlationId,
            long ttlMillis,
            byte[] key,
            byte[] value
    ) {
        this.magic = magic;
        this.version = version;
        this.opCode = opCode;
        this.flags = flags;
        this.status = status;
        this.correlationId = correlationId;
        this.ttlMillis = ttlMillis;
        this.key = (key != null) ? key : new byte[0];
        this.value = (value != null) ? value : new byte[0];
    }

    public static BinaryProtocolFrame createRequest(
            OpCode opCode,
            long correlationId,
            byte[] key,
            byte[] value,
            long ttlMillis
    ) {
        return new BinaryProtocolFrame(
                MAGIC,
                VERSION,
                opCode,
                FLAG_REQUEST,
                StatusCode.SUCCESS,
                correlationId,
                ttlMillis,
                key,
                value
        );
    }

    public static BinaryProtocolFrame createResponse(
            OpCode opCode,
            long correlationId,
            StatusCode status,
            byte[] key,
            byte[] value
    ) {
        return new BinaryProtocolFrame(
                MAGIC,
                VERSION,
                opCode,
                FLAG_RESPONSE,
                status,
                correlationId,
                0L,
                key,
                value
        );
    }

    public static BinaryProtocolFrame createErrorResponse(
            OpCode opCode,
            long correlationId,
            StatusCode status,
            String errorMessage
    ) {
        byte[] errBytes = (errorMessage != null) ? errorMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];
        return new BinaryProtocolFrame(
                MAGIC,
                VERSION,
                opCode,
                (byte) (FLAG_RESPONSE | FLAG_ERROR),
                status,
                correlationId,
                0L,
                new byte[0],
                errBytes
        );
    }

    public short getMagic() {
        return magic;
    }

    public byte getVersion() {
        return version;
    }

    public OpCode getOpCode() {
        return opCode;
    }

    public byte getFlags() {
        return flags;
    }

    public StatusCode getStatus() {
        return status;
    }

    public long getCorrelationId() {
        return correlationId;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public byte[] getKey() {
        return key;
    }

    public byte[] getValue() {
        return value;
    }

    public int getChecksum() {
        return checksum;
    }

    public void setChecksum(int checksum) {
        this.checksum = checksum;
    }

    public boolean isResponse() {
        return (flags & FLAG_RESPONSE) != 0;
    }

    public boolean isError() {
        return (flags & FLAG_ERROR) != 0;
    }

    @Override
    public String toString() {
        return "BinaryProtocolFrame{" +
                "opCode=" + opCode +
                ", flags=" + flags +
                ", status=" + status +
                ", correlationId=" + correlationId +
                ", keyLen=" + key.length +
                ", valLen=" + value.length +
                ", ttl=" + ttlMillis +
                '}';
    }
}
