package ru.warndev.blockjournal;

import java.util.Set;
import java.util.UUID;

public record QuerySpec(
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        int radius,
        long since,
        long until,
        String actorName,
        UUID actorId,
        Set<Action> actions) {

    public QuerySpec {
        if (worldId == null || worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Мир не задан");
        }
        if (radius < 0 || radius > 128) {
            throw new IllegalArgumentException("Недопустимый радиус");
        }
        if (since < 0 || until < since) {
            throw new IllegalArgumentException("Недопустимый интервал времени");
        }
        if (actorName != null && !actorName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Некорректное имя игрока");
        }
        actions = Set.copyOf(actions);
    }
}
