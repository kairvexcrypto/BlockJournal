package ru.warndev.blockjournal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

public final class Messages {
    private static final Component PREFIX = Component.text("BlockJournal ", NamedTextColor.AQUA)
            .append(Component.text("› ", NamedTextColor.DARK_GRAY));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneOffset.UTC);

    private Messages() {
    }

    public static void info(CommandSender sender, String text) {
        sender.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GRAY)));
    }

    public static void error(CommandSender sender, String text) {
        sender.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.RED)));
    }

    public static void page(CommandSender sender, QuerySpec query, QueryPage page, int number) {
        info(sender, "Страница " + number + " · " + query.worldName() + " · "
                + query.x() + " " + query.y() + " " + query.z() + " · r:" + query.radius());
        if (page.rows().isEmpty()) {
            info(sender, "В этом диапазоне записей нет.");
            return;
        }
        for (QueryPage.Row row : page.rows()) {
            sender.sendMessage(render(row));
        }
        if (page.hasMore()) {
            sender.sendMessage(Component.text("Следующая страница →", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/bj next")));
        }
    }

    private static Component render(QueryPage.Row row) {
        BlockEntry event = row.entry();
        NamedTextColor color = switch (event.action()) {
            case PLACE -> NamedTextColor.GREEN;
            case BREAK -> NamedTextColor.RED;
            case EXPLOSION -> NamedTextColor.GOLD;
            case BURN -> NamedTextColor.YELLOW;
        };
        String position = event.x() + " " + event.y() + " " + event.z();
        Component details = Component.text("Запись #" + row.id() + "\n", NamedTextColor.WHITE)
                .append(Component.text(event.worldName() + " · " + position + "\n"))
                .append(Component.text("UUID: " + (event.actorId() == null ? "environment" : event.actorId()) + "\n"))
                .append(Component.text("До: " + event.before() + "\n", NamedTextColor.GRAY))
                .append(Component.text("После: " + event.after(), NamedTextColor.GRAY));
        String material = event.action() == Action.PLACE ? event.after() : event.before();
        int properties = material.indexOf('[');
        if (properties >= 0) {
            material = material.substring(0, properties);
        }
        material = material.replace("minecraft:", "");
        return Component.text(TIME.format(Instant.ofEpochMilli(event.timestamp())) + " UTC ", NamedTextColor.DARK_GRAY)
                .append(Component.text(event.actorName() + " ", NamedTextColor.WHITE))
                .append(Component.text(event.action().name() + " ", color))
                .append(Component.text(material + " · " + position, NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(details));
    }

    public static void status(CommandSender sender, JournalMetrics.Snapshot status) {
        info(sender, "Хранилище: " + status.health() + " · в очереди: " + status.queued());
        info(sender, "Принято: " + status.accepted() + " · записано: " + status.committed()
                + " · отклонено: " + status.rejected());
        info(sender, "Резерв: " + status.spooled() + " · восстановлено: " + status.recovered()
                + " · размер: " + status.spoolBytes() + " байт");
        info(sender, "Ошибки: " + status.failures() + " · повреждённых пакетов: " + status.corrupt()
                + " · пропущено из-за лимита взрыва: " + status.truncated());
        info(sender, "Удалено по сроку: " + status.pruned() + " · последняя запись: "
                + (status.lastCommit() == 0 ? "—" : TIME.format(Instant.ofEpochMilli(status.lastCommit())) + " UTC"));
    }
}
