package ru.warndev.blockjournal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalStoreTest {
    @TempDir
    Path directory;

    @Test
    void retriesDoNotDuplicateEvents() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            BlockEntry entry = Fixtures.entry(1000, 10, Action.BREAK);
            assertEquals(1, store.insert(List.of(entry)));
            assertEquals(0, store.insert(List.of(entry)));
            assertEquals(1, store.query(Fixtures.query(10, 0, 0, 2000), 0, 0, 10).rows().size());
        }
    }

    @Test
    void filtersByLocationWorldTimeActorAndAction() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            BlockEntry correct = Fixtures.entry(1000, 10, Action.BREAK);
            store.insert(List.of(correct, Fixtures.entry(1001, 100, Action.BREAK),
                    Fixtures.entry(9000, 10, Action.BREAK), Fixtures.entry(1002, 10, Action.PLACE),
                    new BlockEntry(UUID.randomUUID(), 1000, Fixtures.ACTOR, "Player", UUID.randomUUID(),
                            "other", 10, 64, -20, Action.BREAK, "minecraft:stone", "minecraft:air")));
            QuerySpec query = new QuerySpec(Fixtures.WORLD, "world", 10, 64, -20, 0, 500, 2000,
                    "player", null, Set.of(Action.BREAK));
            QueryPage page = store.query(query, 0, 0, 8);
            assertEquals(1, page.rows().size());
            assertEquals(correct.eventId(), page.rows().getFirst().entry().eventId());
        }
    }

    @Test
    void paginationDoesNotShiftAfterNewInserts() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            List<BlockEntry> input = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                input.add(Fixtures.entry(i * 100, 10, Action.PLACE));
            }
            store.insert(input);
            QuerySpec query = Fixtures.query(10, 0, 0, 10000);
            QueryPage first = store.query(query, 0, 0, 3);
            assertTrue(first.hasMore());
            store.insert(List.of(Fixtures.entry(500, 10, Action.BREAK)));
            QueryPage second = store.query(query, first.nextCursor(), first.snapshotId(), 3);
            QueryPage third = store.query(query, second.nextCursor(), first.snapshotId(), 3);
            assertEquals(List.of(7L, 6L, 5L), first.rows().stream().map(QueryPage.Row::id).toList());
            assertEquals(List.of(4L, 3L, 2L), second.rows().stream().map(QueryPage.Row::id).toList());
            assertEquals(List.of(1L), third.rows().stream().map(QueryPage.Row::id).toList());
            assertFalse(third.hasMore());
        }
    }

    @Test
    void radiusWorksAtNegativeAndLargeCoordinates() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            store.insert(List.of(Fixtures.entry(100, Integer.MAX_VALUE, Action.BREAK)));
            QueryPage page = store.query(Fixtures.query(Integer.MAX_VALUE, 10, 0, 1000), 0, 0, 8);
            assertEquals(1, page.rows().size());
        }
    }

    @Test
    void retentionDeletesOnlyOldRowsWithinBatch() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            store.insert(List.of(Fixtures.entry(10, 1, Action.BREAK), Fixtures.entry(20, 1, Action.BREAK),
                    Fixtures.entry(1000, 1, Action.BREAK)));
            assertEquals(1, store.prune(500, 1));
            assertEquals(1, store.prune(500, 1));
            assertEquals(0, store.prune(500, 1));
            assertEquals(1, store.query(Fixtures.query(1, 0, 0, 2000), 0, 0, 8).rows().size());
        }
    }

    @Test
    void recordsSurviveCloseAndReopen() throws Exception {
        Path path = directory.resolve("events.db");
        BlockEntry event = Fixtures.entry(100, 1, Action.PLACE);
        try (JournalStore store = new JournalStore(path)) {
            store.insert(List.of(event));
        }
        try (JournalStore store = new JournalStore(path)) {
            assertEquals(event, store.query(Fixtures.query(1, 0, 0, 200), 0, 0, 8).rows().getFirst().entry());
        }
    }

    @Test
    void newSchemaIsNotSilentlyDowngraded() throws Exception {
        Path path = directory.resolve("future.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var sql = connection.createStatement()) {
            sql.execute("PRAGMA user_version=99");
        }
        assertThrows(java.sql.SQLException.class, () -> new JournalStore(path));
    }

    @Test
    void systemEventsKeepNullActor() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            BlockEntry event = new BlockEntry(UUID.randomUUID(), 1, null, "environment", Fixtures.WORLD,
                    "world", 0, 64, -20, Action.EXPLOSION, "minecraft:stone", "minecraft:air");
            store.insert(List.of(event));
            assertNull(store.query(Fixtures.query(0, 0, 0, 1000), 0, 0, 8).rows().getFirst().entry().actorId());
        }
    }

    @Test
    void uuidFilterDoesNotMatchDifferentPlayerWithSameName() throws Exception {
        try (JournalStore store = new JournalStore(directory.resolve("events.db"))) {
            store.insert(List.of(Fixtures.entry(100, 1, Action.PLACE)));
            QuerySpec query = new QuerySpec(Fixtures.WORLD, "world", 1, 64, -20, 0, 0, 1000,
                    null, UUID.randomUUID(), Set.of());
            assertTrue(store.query(query, 0, 0, 8).rows().isEmpty());
        }
    }
}
