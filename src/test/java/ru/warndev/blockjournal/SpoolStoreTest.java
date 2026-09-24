package ru.warndev.blockjournal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripPreservesRecordsAndAccountsSpace() throws Exception {
        SpoolStore spool = new SpoolStore(directory, 1000000);
        List<BlockEntry> records = List.of(Fixtures.entry(100, 1, Action.PLACE), Fixtures.entry(200, 2, Action.BREAK));
        Path file = spool.append(records);
        assertEquals(records, spool.read(file));
        assertEquals(Files.size(file), spool.bytesUsed());
        assertEquals(List.of(file), spool.pending(10));
        spool.remove(file);
        assertEquals(0, spool.bytesUsed());
        assertTrue(spool.pending(10).isEmpty());
    }

    @Test
    void corruptionIsDetectedAndQuarantined() throws Exception {
        SpoolStore spool = new SpoolStore(directory, 1000000);
        Path file = spool.append(List.of(Fixtures.entry(100, 1, Action.BREAK)));
        byte[] data = Files.readAllBytes(file);
        data[data.length - 1] ^= 1;
        Files.write(file, data);
        assertThrows(IOException.class, () -> spool.read(file));
        spool.quarantine(file);
        assertTrue(spool.pending(10).isEmpty());
        assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt")));
    }

    @Test
    void capacityLimitDoesNotCreatePartialBatch() throws Exception {
        SpoolStore spool = new SpoolStore(directory, 10);
        assertThrows(IOException.class, () -> spool.append(List.of(Fixtures.entry(100, 1, Action.BREAK))));
        assertTrue(spool.pending(10).isEmpty());
        assertEquals(0, spool.bytesUsed());
    }

    @Test
    void reopenCountsExistingFilesAndLimitsPendingBatchList() throws Exception {
        SpoolStore spool = new SpoolStore(directory, 1000000);
        spool.append(List.of(Fixtures.entry(100, 1, Action.BREAK)));
        spool.append(List.of(Fixtures.entry(100, 2, Action.BREAK)));
        SpoolStore reopened = new SpoolStore(directory, 1000000);
        assertEquals(spool.bytesUsed(), reopened.bytesUsed());
        assertEquals(1, reopened.pending(1).size());
    }

    @Test
    void replayAfterInterruptedDeleteIsIdempotent() throws Exception {
        SpoolStore spool = new SpoolStore(directory.resolve("spool"), 1000000);
        Path file = spool.append(List.of(Fixtures.entry(100, 1, Action.BREAK)));
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            assertEquals(1, store.insert(spool.read(file)));
            assertEquals(0, store.insert(spool.read(file)));
            spool.remove(file);
            assertEquals(1, store.query(Fixtures.query(1, 0, 0, 1000), 0, 0, 8).rows().size());
        }
    }

    @Test
    void refusesPathsOutsideSpool() throws Exception {
        SpoolStore spool = new SpoolStore(directory.resolve("spool"), 1000000);
        Path file = Files.writeString(directory.resolve("outside"), "not a batch");
        assertThrows(IOException.class, () -> spool.read(file));
    }
}
