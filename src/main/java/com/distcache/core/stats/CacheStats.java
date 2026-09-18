package com.distcache.core.stats;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe statistics tracking for the cache engine.
 */
public class CacheStats {
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong puts = new AtomicLong(0);
    private final AtomicLong deletes = new AtomicLong(0);
    private final AtomicLong probationaryEvictions = new AtomicLong(0);
    private final AtomicLong protectedDemotions = new AtomicLong(0);
    private final AtomicLong promotions = new AtomicLong(0);
    private final AtomicLong expirations = new AtomicLong(0);

    public void recordHit() {
        hits.incrementAndGet();
    }

    public void recordMiss() {
        misses.incrementAndGet();
    }

    public void recordPut() {
        puts.incrementAndGet();
    }

    public void recordDelete() {
        deletes.incrementAndGet();
    }

    public void recordProbationaryEviction() {
        probationaryEvictions.incrementAndGet();
    }

    public void recordProtectedDemotion() {
        protectedDemotions.incrementAndGet();
    }

    public void recordPromotion() {
        promotions.incrementAndGet();
    }

    public void recordExpiration() {
        expirations.incrementAndGet();
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getPuts() {
        return puts.get();
    }

    public long getDeletes() {
        return deletes.get();
    }

    public long getProbationaryEvictions() {
        return probationaryEvictions.get();
    }

    public long getProtectedDemotions() {
        return protectedDemotions.get();
    }

    public long getPromotions() {
        return promotions.get();
    }

    public long getExpirations() {
        return expirations.get();
    }

    public double getHitRate() {
        long total = hits.get() + misses.get();
        if (total == 0) return 0.0;
        return (double) hits.get() / total;
    }

    public void reset() {
        hits.set(0);
        misses.set(0);
        puts.set(0);
        deletes.set(0);
        probationaryEvictions.set(0);
        protectedDemotions.set(0);
        promotions.set(0);
        expirations.set(0);
    }

    @Override
    public String toString() {
        return String.format(
            "CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, puts=%d, deletes=%d, " +
            "promotions=%d, demotions=%d, evictions=%d, expirations=%d}",
            hits.get(), misses.get(), getHitRate() * 100.0, puts.get(), deletes.get(),
            promotions.get(), protectedDemotions.get(), probationaryEvictions.get(), expirations.get()
        );
    }
}
