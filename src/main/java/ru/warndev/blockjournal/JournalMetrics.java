package ru.warndev.blockjournal;

import java.util.concurrent.atomic.AtomicLong;

public final class JournalMetrics {
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong spooled = new AtomicLong();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lastCommit = new AtomicLong();
    private final AtomicLong pruned = new AtomicLong();
    private final AtomicLong truncated = new AtomicLong();
    private final AtomicLong corrupt = new AtomicLong();
    private volatile long spoolBytes;
    private volatile String health = "STARTING";

    public void accepted() {
        accepted.incrementAndGet();
    }

    public void rejected() {
        rejected.incrementAndGet();
    }

    public void committed(int count) {
        committed.addAndGet(count);
        lastCommit.set(System.currentTimeMillis());
        health = "OK";
    }

    public void spooled(int count, long bytes) {
        spooled.addAndGet(count);
        spoolBytes = bytes;
        health = "SPOOLING";
    }

    public void recovered(int count, long bytes) {
        recovered.addAndGet(count);
        spoolBytes = bytes;
    }

    public void failed() {
        failures.incrementAndGet();
        health = "DEGRADED";
    }

    public void ready(long bytes) {
        health = "OK";
        spoolBytes = bytes;
    }

    public void pruned(int count) {
        pruned.addAndGet(count);
    }

    public void truncated(long count) {
        truncated.addAndGet(count);
    }

    public void corrupt() {
        corrupt.incrementAndGet();
    }

    public Snapshot snapshot(int queued) {
        return new Snapshot(health, queued, accepted.get(), rejected.get(), committed.get(), spooled.get(),
                recovered.get(), failures.get(), lastCommit.get(), pruned.get(), truncated.get(), corrupt.get(), spoolBytes);
    }

    public record Snapshot(String health, int queued, long accepted, long rejected, long committed,
                           long spooled, long recovered, long failures, long lastCommit, long pruned,
                           long truncated, long corrupt, long spoolBytes) {
    }
}
