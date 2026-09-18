package com.distcache.protocol;

/**
 * Protocol operation codes for binary frame communication.
 */
public enum OpCode {
    GET((byte) 0x01),
    PUT((byte) 0x02),
    DELETE((byte) 0x03),
    PING((byte) 0x04),
    FORWARD_GET((byte) 0x05),
    FORWARD_PUT((byte) 0x06),
    FORWARD_DELETE((byte) 0x07),
    RAFT_MESSAGE((byte) 0x08),
    CLUSTER_INFO((byte) 0x09);

    private final byte code;

    OpCode(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static OpCode fromCode(byte code) {
        for (OpCode op : values()) {
            if (op.code == code) {
                return op;
            }
        }
        throw new IllegalArgumentException("Unknown OpCode: 0x" + Integer.toHexString(code & 0xFF));
    }
}
