package ru.warndev.blockjournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JournalService implements AutoCloseable {
    private final Settings settings;
    private final Logger logger;
    private final JournalMetrics metrics = new JournalMetrics();
    private final ArrayBlockingQueue<BlockEntry> queue;
    private final List<BlockEntry> batch = new ArrayList<>();
    private final Semaphore querySlots;
    private final ScheduledThreadPoolExecutor worker;
    private final Object lifecycle = new Object();
    private final Path folder;
    private JournalStore store;
    private SpoolStore spool;
    private volatile boolean accepting;
    private volatile int pendingBatch;
    private long lastWarning;
    private long nextPrune;

    public JournalService(Settings settings, Path folder, Logger logger) throws Exception {
        this.settings = settings;
        this.folder = folder;
        this.logger = logger;
        queue = new ArrayBlockingQueue<>(settings.queueCapacity());
        querySlots = new Semaphore(settings.queryLimit());
        worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "BlockJournal-storage");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        try {
            worker.submit(() -> {
                Files.createDirectories(folder);
                spool = new SpoolStore(folder.resolve("spool"), settings.spoolMaxBytes());
                store = new JournalStore(folder.resolve("journal.db"));
                metrics.ready(spool.bytesUsed());
                return null;
            }).get(20, TimeUnit.SECONDS);
            accepting = true;
            worker.scheduleWithFixedDelay(this::cycle, settings.flushMillis(), settings.flushMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            worker.execute(this::closeStore);
            worker.shutdown();
            throw error;
        }
    }

    public boolean offer(BlockEntry entry) {
        synchronized (lifecycle) {
            if (!accepting || !queue.offer(entry)) {
                metrics.rejected();
                return false;
            }
            metrics.accepted();
            return true;
        }
    }

    public void truncated(int count) {
        metrics.truncated(count);
    }

    public JournalMetrics.Snapshot status() {
        return metrics.snapshot(queue.size() + pendingBatch);
    }

    public CompletableFuture<QueryPage> query(QuerySpec spec, long cursor, long snapshot) {
        return submit(() -> {
            try {
                flushAvailable(1);
                return store.query(spec, cursor, snapshot, settings.pageSize());
            } catch (SQLException error) {
                warn("Lookup failed", error);
                throw new StorageException(error);
            }
        });
    }

    public CompletableFuture<JournalMetrics.Snapshot> flush() {
        return submit(() -> {
            int rounds = 1 + settings.queueCapacity() / settings.batchSize();
            flushAvailable(rounds);
            if (!batch.isEmpty() || !queue.isEmpty()) {
                throw new StorageException("Очередь не удалось полностью сохранить");
            }
            return status();
        });
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (!accepting) {
                result.completeExceptionally(new StorageException("Хранилище останавливается"));
                return result;
            }
            if (!querySlots.tryAcquire()) {
                result.completeExceptionally(new StorageException("Очередь запросов заполнена"));
                return result;
            }
            try {
                worker.execute(() -> {
                    try {
                        result.complete(task.get());
                    } catch (Exception error) {
                        result.completeExceptionally(error);
                    } finally {
                        querySlots.release();
                    }
                });
            } catch (RejectedExecutionException error) {
                querySlots.release();
                result.completeExceptionally(error);
            }
        }
        return result;
    }

    private void cycle() {
        try {
            recover(2);
            flushAvailable(4);
            long now = System.currentTimeMillis();
            if (now >= nextPrune) {
                nextPrune = now + Duration.ofMinutes(10).toMillis();
                long cutoff = now - Duration.ofDays(settings.retentionDays()).toMillis();
                int pruned = store.prune(cutoff, settings.cleanupBatch());
                metrics.pruned(pruned);
                if (pruned == settings.cleanupBatch()) {
                    nextPrune = now + Duration.ofSeconds(10).toMillis();
                }
                store.checkpoint();
            }
        } catch (Exception error) {
            warn("Storage maintenance failed", error);
        }
    }

    private void recover(int count) throws IOException {
        for (Path path : spool.pending(count)) {
            List<BlockEntry> entries;
            try {
                entries = spool.read(path);
            } catch (IOException error) {
                spool.quarantine(path);
                metrics.corrupt();
                logger.log(Level.SEVERE, "Corrupt spool batch preserved: " + path.getFileName(), error);
                continue;
            }
            try {
                int recovered = store.insert(entries);
                spool.remove(path);
                metrics.recovered(recovered, spool.bytesUsed());
            } catch (SQLException error) {
                warn("Cannot replay spool batch", error);
                return;
            }
        }
    }

    private void flushAvailable(int rounds) {
        for (int index = 0; index < rounds; index++) {
            if (batch.isEmpty()) {
                queue.drainTo(batch, settings.batchSize());
                pendingBatch = batch.size();
            }
            if (batch.isEmpty()) {
                return;
            }
            if (!persist()) {
                return;
            }
            batch.clear();
            pendingBatch = 0;
        }
    }

    private boolean persist() {
        try {
            int inserted = store.insert(batch);
            metrics.committed(inserted);
            return true;
        } catch (SQLException error) {
            warn("SQLite write failed; preserving batch in spool", error);
            try {
                spool.append(batch);
                metrics.spooled(batch.size(), spool.bytesUsed());
                return true;
            } catch (IOException spoolError) {
                warn("Spool write failed; keeping batch in memory", spoolError);
                return false;
            }
        }
    }

    private void warn(String message, Exception error) {
        metrics.failed();
        long now = System.currentTimeMillis();
        if (now - lastWarning > 30000) {
            lastWarning = now;
            logger.log(Level.SEVERE, message, error);
        }
    }

    private void closeStore() {
        if (store != null) {
            try {
                store.close();
            } catch (SQLException error) {
                logger.log(Level.SEVERE, "Could not close SQLite connection", error);
            }
        }
    }

    @Override
    public void close() {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (!accepting) {
                return;
            }
            accepting = false;
            worker.execute(() -> {
                try {
                    int rounds = 1 + settings.queueCapacity() / settings.batchSize();
                    flushAvailable(rounds);
                    if (!batch.isEmpty() || !queue.isEmpty()) {
                        logger.severe("Unsaved events at shutdown: " + (batch.size() + queue.size()));
                    }
                    finished.complete(null);
                } catch (Exception error) {
                    finished.completeExceptionally(error);
                } finally {
                    closeStore();
                }
            });
            worker.shutdown();
        }
        try {
            finished.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while draining journal queue");
        } catch (ExecutionException | TimeoutException error) {
            logger.log(Level.SEVERE, "Journal shutdown did not finish within the safety window", error);
        }
    }

    public static final class StorageException extends RuntimeException {
        public StorageException(Throwable cause) {
            super(cause);
        }

        public StorageException(String message) {
            super(message);
        }
    }
}
