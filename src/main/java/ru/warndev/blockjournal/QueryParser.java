package ru.warndev.blockjournal;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QueryParser {
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]{0,5})(m|h|d)");
    private final int maxRadius;
    private final int defaultHours;
    private final int maxDays;

    public QueryParser(int maxRadius, int defaultHours, int maxDays) {
        this.maxRadius = maxRadius;
        this.defaultHours = defaultHours;
        this.maxDays = maxDays;
    }

    public QuerySpec parse(String[] args, Origin origin, long now) {
        int radius = 0;
        long period = Duration.ofHours(defaultHours).toMillis();
        String actor = null;
        UUID actorId = null;
        Set<Action> actions = EnumSet.noneOf(Action.class);
        Set<String> seen = new HashSet<>();
        for (String argument : args) {
            int separator = argument.indexOf(':');
            if (separator <= 0 || separator == argument.length() - 1) {
                throw new IllegalArgumentException("Формат фильтра: r:10 t:2h u:Player a:break");
            }
            String key = argument.substring(0, separator);
            String value = argument.substring(separator + 1);
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Повторный фильтр: " + key);
            }
            switch (key) {
                case "r" -> radius = radius(value);
                case "t" -> period = duration(value);
                case "u" -> actor = value;
                case "uuid" -> actorId = uuid(value);
                case "a" -> {
                    for (String part : value.split(",", -1)) {
                        actions.add(Action.parse(part));
                    }
                }
                default -> throw new IllegalArgumentException("Неизвестный фильтр: " + key);
            }
        }
        if (actor != null && actorId != null) {
            throw new IllegalArgumentException("Используйте u: или uuid:");
        }
        return new QuerySpec(origin.worldId(), origin.worldName(), origin.x(), origin.y(), origin.z(),
                radius, Math.max(0, now - period), now, actor, actorId, actions);
    }

    private int radius(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > maxRadius) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Радиус должен быть от 0 до " + maxRadius);
        }
    }

    private long duration(String value) {
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Интервал: 30m, 2h или 7d");
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration duration = switch (matcher.group(2)) {
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
        if (duration.compareTo(Duration.ofDays(maxDays)) > 0) {
            throw new IllegalArgumentException("Максимальный интервал: " + maxDays + " дней");
        }
        return duration.toMillis();
    }

    private UUID uuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
            return uuid;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Некорректный UUID");
        }
    }

    public record Origin(UUID worldId, String worldName, int x, int y, int z) {
    }
}
