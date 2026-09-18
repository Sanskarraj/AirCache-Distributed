package com.distcache.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous write-behind buffer.
 *
 * Buffers cache updates in memory and asynchronously flushes them to persistent storage
 * in background batches. This decouples client write latency from disk I/O,
 * significantly improving p99 write latency under heavy concurrent loads.
 */
public class WriteBehindBuffer implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(WriteBehindBuffer.class);

    public enum MutationType {
        PUT,
        DELETE
    }

    public record Mutation(
            MutationType type,
            byte[] key,
            byte[] value,
            long ttlMillis,
            long timestampMillis
    ) {}

    private final BlockingQueue<Mutation> queue;
    private final PersistenceEngine persistenceEngine;
    private final int maxBatchSize;
    private final long flushIntervalMillis;
    private final Thread workerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Metrics
    private final AtomicLong enqueuedCount = new AtomicLong(0);
    private final AtomicLong flushedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    public WriteBehindBuffer(PersistenceEngine persistenceEngine) {
        this(persistenceEngine, 65536, 1000, 50);
    }

    public WriteBehindBuffer(
            PersistenceEngine persistenceEngine,
            int bufferCapacity,
            int maxBatchSize,
            long flushIntervalMillis
    ) {
        this.persistenceEngine = persistenceEngine;
        this.queue = new ArrayBlockingQueue<>(bufferCapacity);
        this.maxBatchSize = maxBatchSize;
        this.flushIntervalMillis = flushIntervalMillis;

        this.workerThread = new Thread(this::flushLoop, "write-behind-flusher");
        this.workerThread.setDaemon(false);
        this.workerThread.start();

        logger.info("Initialized WriteBehindBuffer with capacity={}, maxBatchSize={}, flushInterval={}ms",
                bufferCapacity, maxBatchSize, flushIntervalMillis);
    }

    /**
     * Enqueues a PUT mutation asynchronously.
     */
    public boolean enqueuePut(byte[] key, byte[] value, long ttlMillis) {
        if (!running.get()) return false;
        Mutation mutation = new Mutation(MutationType.PUT, key, value, ttlMillis, System.currentTimeMillis());
        boolean accepted = queue.offer(mutation);
        if (accepted) {
            enqueuedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            logger.warn("Write-behind buffer is full! Dropped PUT mutation");
        }
        return accepted;
    }

    /**
     * Enqueues a DELETE mutation asynchronously.
     */
    public boolean enqueueDelete(byte[] key) {
        if (!running.get()) return false;
        Mutation mutation = new Mutation(MutationType.DELETE, key, null, 0L, System.currentTimeMillis());
        boolean accepted = queue.offer(mutation);
        if (accepted) {
            enqueuedCount.incrementAndGet();
        } else {
            droppedCount.incrementAndGet();
            logger.warn("Write-behind buffer is full! Dropped DELETE mutation");
        }
        return accepted;
    }

    private static final Mutation POISON_PILL = new Mutation(null, null, null, 0L, 0L);

    private void flushLoop() {
        List<Mutation> batch = new ArrayList<>(maxBatchSize);

        while (running.get() || !queue.isEmpty()) {
            try {
                // Wait for the first item up to flushIntervalMillis
                Mutation first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    // Drain up to maxBatchSize items
                    queue.drainTo(batch, maxBatchSize - 1);
                }

                if (!batch.isEmpty()) {
                    flushBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                // thread interrupted or exiting
            } catch (Exception e) {
                logger.error("Error in write-behind flusher loop", e);
            }
        }

        // Drain any remaining items after shutdown
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            flushBatch(batch);
            batch.clear();
        }
    }

    private void flushBatch(List<Mutation> batch) {
        try {
            int written = 0;
            for (Mutation mutation : batch) {
                if (mutation == null || mutation.type == null) continue;
                if (mutation.type == MutationType.PUT) {
                    persistenceEngine.appendPut(mutation.key, mutation.value, mutation.ttlMillis);
                    written++;
                } else if (mutation.type == MutationType.DELETE) {
                    persistenceEngine.appendDelete(mutation.key);
                    written++;
                }
            }
            persistenceEngine.flush();
            flushedCount.addAndGet(written);
        } catch (IOException e) {
            logger.error("Failed to flush batch of mutations to persistence engine", e);
        }
    }

    public long getEnqueuedCount() {
        return enqueuedCount.get();
    }

    public long getFlushedCount() {
        return flushedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public int getPendingCount() {
        return queue.size();
    }

    @Override
    public void close() throws IOException {
        if (running.compareAndSet(true, false)) {
            logger.info("Shutting down WriteBehindBuffer, draining {} remaining mutations...", queue.size());
            queue.offer(POISON_PILL);
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            persistenceEngine.close();
            logger.info("WriteBehindBuffer closed cleanly. Total flushed: {}", flushedCount.get());
        }
    }
}
