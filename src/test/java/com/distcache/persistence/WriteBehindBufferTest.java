package com.distcache.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class WriteBehindBufferTest {

    @Test
    public void testWriteBehindAndRecovery(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path walPath = tempDir.resolve("test-wal.aof");
        AppendOnlyLogPersistence persistence = new AppendOnlyLogPersistence(walPath);

        WriteBehindBuffer buffer = new WriteBehindBuffer(persistence, 1000, 10, 20);

        for (int i = 0; i < 50; i++) {
            byte[] k = ("wal-key-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] v = ("wal-val-" + i).getBytes(StandardCharsets.UTF_8);
            buffer.enqueuePut(k, v, 0L);
        }

        // Delete 10 keys
        for (int i = 0; i < 10; i++) {
            byte[] k = ("wal-key-" + i).getBytes(StandardCharsets.UTF_8);
            buffer.enqueueDelete(k);
        }

        // Close flushes all remaining items to disk
        buffer.close();

        // Now recover from WAL
        AppendOnlyLogPersistence recoveryEngine = new AppendOnlyLogPersistence(walPath);
        Map<byte[], byte[]> recovered = recoveryEngine.recover();
        recoveryEngine.close();

        assertEquals(40, recovered.size());
    }
}
