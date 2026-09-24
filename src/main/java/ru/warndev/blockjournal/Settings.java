package ru.warndev.blockjournal;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

public record Settings(
        int queueCapacity,
        int batchSize,
        int flushMillis,
        int queryLimit,
        int retentionDays,
        int cleanupBatch,
        long spoolMaxBytes,
        int pageSize,
        int defaultHours,
        int maxDays,
        int maxRadius,
        int cooldownMillis,
        int sessionMinutes,
        Material inspectTool,
        boolean capturePlace,
        boolean captureBreak,
        boolean captureExplosions,
        boolean captureFire,
        int maxExplosionBlocks,
        Set<String> excludedWorlds) {

    public Settings {
        excludedWorlds = Set.copyOf(excludedWorlds);
    }

    public static Settings load(FileConfiguration config) {
        int capacity = number(config, "queue.capacity", 100, 1000000);
        int batch = number(config, "queue.batch-size", 1, 10000);
        if (batch > capacity) {
            throw new IllegalArgumentException("queue.batch-size превышает queue.capacity");
        }
        int retention = number(config, "storage.retention-days", 1, 3650);
        int maxDays = number(config, "lookup.max-days", 1, retention);
        int hours = number(config, "lookup.default-hours", 1, maxDays * 24);
        String toolName = config.getString("inspection.tool", "");
        Material tool = Material.matchMaterial(toolName);
        if (tool == null || tool.isAir() || !tool.isItem()) {
            throw new IllegalArgumentException("inspection.tool: неизвестный предмет");
        }
        return new Settings(
                capacity,
                batch,
                number(config, "queue.flush-millis", 50, 10000),
                number(config, "queue.max-pending-queries", 1, 128),
                retention,
                number(config, "storage.cleanup-batch", 100, 100000),
                number(config, "storage.spool-max-mib", 1, 4096) * 1024L * 1024L,
                number(config, "lookup.page-size", 1, 30),
                hours,
                maxDays,
                number(config, "lookup.max-radius", 0, 128),
                number(config, "lookup.cooldown-millis", 100, 60000),
                number(config, "lookup.session-minutes", 1, 60),
                tool,
                flag(config, "capture.place"),
                flag(config, "capture.break"),
                flag(config, "capture.explosions"),
                flag(config, "capture.fire"),
                number(config, "capture.max-explosion-blocks", 1, 100000),
                new HashSet<>(config.getStringList("capture.excluded-worlds")));
    }

    private static int number(FileConfiguration config, String path, int min, int max) {
        Object value = config.get(path);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + ": требуется целое число");
        }
        long parsed = number.longValue();
        if (number.doubleValue() != parsed || parsed < min || parsed > max) {
            throw new IllegalArgumentException(path + ": допустимо от " + min + " до " + max);
        }
        return (int) parsed;
    }

    private static boolean flag(FileConfiguration config, String path) {
        if (!(config.get(path) instanceof Boolean value)) {
            throw new IllegalArgumentException(path + ": требуется true или false");
        }
        return value;
    }
}
