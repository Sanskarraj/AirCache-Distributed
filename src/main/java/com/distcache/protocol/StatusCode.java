package com.distcache.protocol;

/**
 * Status codes returned in binary protocol response frames.
 */
public enum StatusCode {
    SUCCESS((byte) 0x00),
    KEY_NOT_FOUND((byte) 0x01),
    SERVER_ERROR((byte) 0x02),
    CORRUPTED_FRAME((byte) 0x03),
    REDIRECT((byte) 0x04);

    private final byte code;

    StatusCode(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static StatusCode fromCode(byte code) {
        for (StatusCode sc : values()) {
            if (sc.code == code) {
                return sc;
            }
        }
        throw new IllegalArgumentException("Unknown StatusCode: 0x" + Integer.toHexString(code & 0xFF));
    }
}
