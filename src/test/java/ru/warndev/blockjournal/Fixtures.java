package ru.warndev.blockjournal;

import java.util.Set;
import java.util.UUID;

final class Fixtures {
    static final UUID WORLD = UUID.fromString("c150ba97-0bb9-44ea-ae89-c9a9d8777e34");
    static final UUID ACTOR = UUID.fromString("36e083b4-92ab-455f-a7fc-931eef30c1dd");

    static BlockEntry entry(long time, int x, Action action) {
        return new BlockEntry(UUID.randomUUID(), time, ACTOR, "Player", WORLD, "world",
                x, 64, -20, action, "minecraft:stone", "minecraft:air");
    }

    static QuerySpec query(int x, int radius, long since, long until) {
        return new QuerySpec(WORLD, "world", x, 64, -20, radius, since, until, null, null, Set.of());
    }
}
