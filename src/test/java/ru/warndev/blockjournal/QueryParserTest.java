package ru.warndev.blockjournal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryParserTest {
    private final QueryParser parser = new QueryParser(64, 24, 30);
    private final QueryParser.Origin origin = new QueryParser.Origin(Fixtures.WORLD, "world", 10, 64, -20);
    private final long now = Duration.ofDays(100).toMillis();

    @Test
    void parsesCombinedFilters() {
        QuerySpec query = parser.parse(new String[]{"r:10", "t:2h", "u:Player", "a:break,place"}, origin, now);
        assertEquals(10, query.radius());
        assertEquals(now - Duration.ofHours(2).toMillis(), query.since());
        assertEquals("Player", query.actorName());
        assertEquals(Set.of(Action.BREAK, Action.PLACE), query.actions());
    }

    @Test
    void rejectsUnknownDuplicateAndEmptyFilters() {
        for (String[] args : new String[][]{{"r:1", "r:2"}, {"foo:1"}, {"t:"}, {"a:break,"}, {"u:"}}) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(args, origin, now));
        }
    }

    @Test
    void rejectsExcessiveDurationAndRadius() {
        for (String value : new String[]{"r:65", "r:-1", "r:99999999999999", "t:31d", "t:0h", "t:1s"}) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(new String[]{value}, origin, now));
        }
    }

    @Test
    void acceptsBoundaryAndMinuteDuration() {
        QuerySpec query = parser.parse(new String[]{"r:64", "t:30m"}, origin, now);
        assertEquals(64, query.radius());
        assertEquals(now - 1800000, query.since());
    }

    @Test
    void rejectsInjectedPlayerNames() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new String[]{"u:'OR1=1"}, origin, now));
    }

    @Test
    void validatesCanonicalUuidAndExclusiveIdentityFilters() {
        QuerySpec query = parser.parse(new String[]{"uuid:" + Fixtures.ACTOR}, origin, now);
        assertEquals(Fixtures.ACTOR, query.actorId());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new String[]{"uuid:1-1-1-1-1"}, origin, now));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new String[]{"uuid:" + Fixtures.ACTOR, "u:Player"}, origin, now));
    }

    @Test
    void defaultsToSingleBlockAndDay() {
        QuerySpec query = parser.parse(new String[0], origin, now);
        assertEquals(0, query.radius());
        assertEquals(now - Duration.ofDays(1).toMillis(), query.since());
        assertTrue(query.actions().isEmpty());
    }
}
