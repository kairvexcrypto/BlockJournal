package ru.warndev.blockjournal;

import java.util.Objects;
import java.util.UUID;

public record BlockEntry(
        UUID eventId,
        long timestamp,
        UUID actorId,
        String actorName,
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        Action action,
        String before,
        String after) {

    public BlockEntry {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(action);
        actorName = bounded(actorName, 64, "actorName");
        worldName = bounded(worldName, 256, "worldName");
        before = bounded(before, 8192, "before");
        after = bounded(after, 8192, "after");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp");
        }
    }

    private static String bounded(String value, int limit, String field) {
        Objects.requireNonNull(value, field);
        if (value.length() > limit || value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }
}
