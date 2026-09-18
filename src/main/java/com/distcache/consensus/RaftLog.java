package com.distcache.consensus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replicated in-memory log for Raft consensus.
 */
public class RaftLog {
    private final List<RaftLogEntry> entries = new ArrayList<>();
    private long commitIndex = 0;
    private long lastApplied = 0;

    public RaftLog() {
        // Index 0 is dummy entry
        entries.add(new RaftLogEntry(0, 0, RaftLogEntry.Type.NOOP, null));
    }

    public synchronized long getLastLogIndex() {
        return entries.get(entries.size() - 1).getIndex();
    }

    public synchronized long getLastLogTerm() {
        return entries.get(entries.size() - 1).getTerm();
    }

    public synchronized long appendEntry(long term, RaftLogEntry.Type type, com.distcache.cluster.Node node) {
        long newIndex = getLastLogIndex() + 1;
        RaftLogEntry entry = new RaftLogEntry(term, newIndex, type, node);
        entries.add(entry);
        return newIndex;
    }

    public synchronized RaftLogEntry getEntry(long index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get((int) index);
    }

    public synchronized List<RaftLogEntry> getEntriesFrom(long index) {
        if (index >= entries.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries.subList((int) index, entries.size()));
    }

    public synchronized void truncateAndAppend(long prevIndex, List<RaftLogEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) return;

        int insertPos = (int) (prevIndex + 1);
        while (entries.size() > insertPos) {
            entries.remove(entries.size() - 1);
        }
        entries.addAll(newEntries);
    }

    public synchronized long getCommitIndex() {
        return commitIndex;
    }

    public synchronized void setCommitIndex(long commitIndex) {
        this.commitIndex = Math.min(commitIndex, getLastLogIndex());
    }

    public synchronized long getLastApplied() {
        return lastApplied;
    }

    public synchronized void setLastApplied(long lastApplied) {
        this.lastApplied = lastApplied;
    }
}
