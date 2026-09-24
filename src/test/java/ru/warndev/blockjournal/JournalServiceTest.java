package ru.warndev.blockjournal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalServiceTest {
    @TempDir
    Path directory;

    private Settings settings(int capacity) {
        return new Settings(capacity, 2, 10000, 4, 30, 100, 1048576, 8, 24, 30, 64, 1000, 5,
                Material.BLAZE_ROD, true, true, true, true, 100, Set.of());
    }

    @Test
    void queuePressureIsVisibleAndShutdownDrainsAcceptedRecords() throws Exception {
        JournalService service = new JournalService(settings(3), directory, Logger.getAnonymousLogger());
        try {
            assertTrue(service.offer(Fixtures.entry(100, 1, Action.BREAK)));
            assertTrue(service.offer(Fixtures.entry(101, 1, Action.BREAK)));
            assertTrue(service.offer(Fixtures.entry(102, 1, Action.BREAK)));
            assertFalse(service.offer(Fixtures.entry(103, 1, Action.BREAK)));
            assertEquals(1, service.status().rejected());
        } finally {
            service.close();
        }
        assertFalse(service.offer(Fixtures.entry(104, 1, Action.BREAK)));
        try (JournalStore store = new JournalStore(directory.resolve("journal.db"))) {
            assertEquals(3, store.query(Fixtures.query(1, 0, 0, 1000), 0, 0, 8).rows().size());
        }
    }

    @Test
    void explicitFlushCompletesAfterDataIsPersisted() throws Exception {
        try (JournalService service = new JournalService(settings(10), directory, Logger.getAnonymousLogger())) {
            service.offer(Fixtures.entry(100, 1, Action.PLACE));
            service.flush().get(5, TimeUnit.SECONDS);
            QueryPage page = service.query(Fixtures.query(1, 0, 0, 1000), 0, 0).get(5, TimeUnit.SECONDS);
            assertEquals(1, page.rows().size());
            assertEquals(0, service.status().queued());
        }
    }
}
