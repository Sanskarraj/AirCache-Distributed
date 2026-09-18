package com.distcache.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * High-performance Append-Only File (AOF) persistence engine.
 * Records cache mutations sequentially with binary framing and CRC32 checksums.
 */
public class AppendOnlyLogPersistence implements PersistenceEngine {
    private static final Logger logger = LoggerFactory.getLogger(AppendOnlyLogPersistence.class);

    private static final byte OP_PUT = 1;
    private static final byte OP_DELETE = 2;

    private final Path logFilePath;
    private final FileChannel fileChannel;
    private final Object writeLock = new Object();

    public AppendOnlyLogPersistence(Path logFilePath) throws IOException {
        this.logFilePath = logFilePath;
        if (logFilePath.getParent() != null) {
            Files.createDirectories(logFilePath.getParent());
        }

        this.fileChannel = FileChannel.open(
                logFilePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        this.fileChannel.position(this.fileChannel.size());
        logger.info("Initialized AppendOnlyLogPersistence at {} (current size: {} bytes)",
                logFilePath, this.fileChannel.size());
    }

    @Override
    public void appendPut(byte[] key, byte[] value, long ttlMillis) throws IOException {
        int keyLen = key.length;
        int valLen = value.length;
        int recordSize = 1 + 2 + 4 + 8 + keyLen + valLen + 4; // type(1) + klen(2) + vlen(4) + ttl(8) + k + v + crc(4)

        ByteBuffer buffer = ByteBuffer.allocate(recordSize);
        buffer.put(OP_PUT);
        buffer.putShort((short) keyLen);
        buffer.putInt(valLen);
        buffer.putLong(ttlMillis);
        buffer.put(key);
        buffer.put(value);

        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, recordSize - 4);
        buffer.putInt((int) crc.getValue());
        buffer.flip();

        synchronized (writeLock) {
            while (buffer.hasRemaining()) {
                fileChannel.write(buffer);
            }
        }
    }

    @Override
    public void appendDelete(byte[] key) throws IOException {
        int keyLen = key.length;
        int recordSize = 1 + 2 + 4 + 8 + keyLen + 4; // type(1) + klen(2) + vlen(0) + ttl(0) + k + crc(4)

        ByteBuffer buffer = ByteBuffer.allocate(recordSize);
        buffer.put(OP_DELETE);
        buffer.putShort((short) keyLen);
        buffer.putInt(0);
        buffer.putLong(0L);
        buffer.put(key);

        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, recordSize - 4);
        buffer.putInt((int) crc.getValue());
        buffer.flip();

        synchronized (writeLock) {
            while (buffer.hasRemaining()) {
                fileChannel.write(buffer);
            }
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (writeLock) {
            fileChannel.force(false);
        }
    }

    @Override
    public Map<byte[], byte[]> recover() throws IOException {
        Map<ByteArrayWrapper, byte[]> recovered = new HashMap<>();
        long originalPosition = fileChannel.position();
        fileChannel.position(0);

        ByteBuffer headerBuf = ByteBuffer.allocate(1 + 2 + 4 + 8); // 15 bytes header
        long fileSize = fileChannel.size();
        long readPos = 0;

        logger.info("Starting WAL replay recovery for file {} (size: {} bytes)", logFilePath, fileSize);

        while (readPos < fileSize) {
            headerBuf.clear();
            int bytesRead = fileChannel.read(headerBuf);
            if (bytesRead < 15) {
                logger.warn("Truncated record header at position {}, stopping recovery", readPos);
                break;
            }
            headerBuf.flip();
            byte opType = headerBuf.get();
            short keyLen = headerBuf.getShort();
            int valLen = headerBuf.getInt();
            long ttlMillis = headerBuf.getLong();

            if (keyLen <= 0 || valLen < 0 || keyLen > 65535 || valLen > 64 * 1024 * 1024) {
                logger.warn("Corrupted record sizes at position {}: kLen={}, vLen={}", readPos, keyLen, valLen);
                break;
            }

            int payloadAndCrcSize = keyLen + valLen + 4;
            ByteBuffer dataBuf = ByteBuffer.allocate(payloadAndCrcSize);
            int dataBytesRead = fileChannel.read(dataBuf);
            if (dataBytesRead < payloadAndCrcSize) {
                logger.warn("Truncated record payload at position {}, stopping recovery", readPos);
                break;
            }
            dataBuf.flip();

            byte[] key = new byte[keyLen];
            dataBuf.get(key);
            byte[] value = new byte[valLen];
            dataBuf.get(value);
            int expectedCrc = dataBuf.getInt();

            // Verify checksum
            int fullRecordWithoutCrcSize = 15 + keyLen + valLen;
            ByteBuffer fullRecord = ByteBuffer.allocate(fullRecordWithoutCrcSize);
            fullRecord.put(opType);
            fullRecord.putShort(keyLen);
            fullRecord.putInt(valLen);
            fullRecord.putLong(ttlMillis);
            fullRecord.put(key);
            fullRecord.put(value);

            CRC32 crc = new CRC32();
            crc.update(fullRecord.array(), 0, fullRecordWithoutCrcSize);
            if ((int) crc.getValue() != expectedCrc) {
                logger.warn("CRC32 mismatch at position {}, halting recovery replay", readPos);
                break;
            }

            // Valid record
            ByteArrayWrapper wrappedKey = new ByteArrayWrapper(key);
            if (opType == OP_PUT) {
                recovered.put(wrappedKey, value);
            } else if (opType == OP_DELETE) {
                recovered.remove(wrappedKey);
            }

            readPos += (15 + payloadAndCrcSize);
        }

        fileChannel.position(originalPosition);

        Map<byte[], byte[]> result = new HashMap<>(recovered.size());
        for (Map.Entry<ByteArrayWrapper, byte[]> entry : recovered.entrySet()) {
            result.put(entry.getKey().bytes, entry.getValue());
        }

        logger.info("Recovery completed. Restored {} active keys from WAL", result.size());
        return result;
    }

    @Override
    public void close() throws IOException {
        flush();
        fileChannel.close();
    }

    private static class ByteArrayWrapper {
        final byte[] bytes;
        final int hash;

        ByteArrayWrapper(byte[] bytes) {
            this.bytes = bytes;
            this.hash = java.util.Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayWrapper that)) return false;
            return java.util.Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
