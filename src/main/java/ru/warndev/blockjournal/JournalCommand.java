package ru.warndev.blockjournal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class JournalCommand implements CommandExecutor, TabCompleter, Listener {
    private final BlockJournalPlugin plugin;
    private final JournalService journal;
    private final Settings settings;
    private final QueryParser parser;
    private final Set<UUID> inspectors = new HashSet<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> requests = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long sequence;

    public JournalCommand(BlockJournalPlugin plugin, JournalService journal, Settings settings) {
        this.plugin = plugin;
        this.journal = journal;
        this.settings = settings;
        parser = new QueryParser(settings.maxRadius(), settings.defaultHours(), settings.maxDays());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (subcommand) {
                case "inspect" -> inspect(sender);
                case "lookup" -> lookup(sender, Arrays.copyOfRange(args, 1, args.length));
                case "next" -> next(sender);
                case "status" -> {
                    if (permitted(sender, "status")) {
                        Messages.status(sender, journal.status());
                    }
                }
                case "flush" -> flush(sender);
                default -> help(sender);
            }
        } catch (IllegalArgumentException error) {
            Messages.error(sender, error.getMessage());
        }
        return true;
    }

    private void help(CommandSender sender) {
        Messages.info(sender, "/bj inspect — проверка блока инструментом " + settings.inspectTool());
        Messages.info(sender, "/bj lookup r:10 t:2h u:Player a:break,place");
        Messages.info(sender, "u: ищет имя на момент события; uuid: ищет UUID игрока.");
        Messages.info(sender, "Радиус — куб вокруг блока под прицелом или вашей позиции.");
        Messages.info(sender, "/bj next — следующая страница результатов");
        Messages.info(sender, "/bj status — состояние записи; /bj flush — сохранить очередь");
    }

    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission("blockjournal." + permission)) {
            return true;
        }
        Messages.error(sender, "Недостаточно прав: blockjournal." + permission);
        return false;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalArgumentException("Эта команда выполняется в игре.");
    }

    private void inspect(CommandSender sender) {
        if (!permitted(sender, "inspect") || !permitted(sender, "lookup")) {
            return;
        }
        Player player = player(sender);
        if (inspectors.remove(player.getUniqueId())) {
            Messages.info(player, "Проверка блоков выключена.");
        } else {
            inspectors.add(player.getUniqueId());
            Messages.info(player, "Проверка включена. Возьмите " + settings.inspectTool() + " и нажмите на блок.");
        }
    }

    private void lookup(CommandSender sender, String[] args) {
        if (!permitted(sender, "lookup")) {
            return;
        }
        Player player = player(sender);
        Block target = player.getTargetBlockExact(6);
        Location location = target == null ? player.getLocation() : target.getLocation();
        QuerySpec query = parser.parse(args, origin(location), System.currentTimeMillis());
        request(player, query, 0, 0, 1, false);
    }

    private void next(CommandSender sender) {
        if (!permitted(sender, "lookup")) {
            return;
        }
        Player player = player(sender);
        UUID id = player.getUniqueId();
        Session session = sessions.get(id);
        if (session == null || System.nanoTime() > session.expiresAt()) {
            sessions.remove(id);
            Messages.error(player, "Поиск истёк. Выполните /bj lookup ещё раз.");
            return;
        }
        if (!session.page().hasMore()) {
            Messages.info(player, "Это последняя страница.");
            return;
        }
        request(player, session.query(), session.page().nextCursor(), session.page().snapshotId(), session.number() + 1, false);
    }

    private boolean available(Player player, boolean silent) {
        UUID id = player.getUniqueId();
        if (requests.containsKey(id)) {
            if (!silent) {
                Messages.error(player, "Предыдущий запрос ещё выполняется.");
            }
            return false;
        }
        long now = System.nanoTime();
        if (now < cooldowns.getOrDefault(id, Long.MIN_VALUE)) {
            if (!silent) {
                Messages.error(player, "Подождите перед следующим запросом.");
            }
            return false;
        }
        cooldowns.put(id, now + Duration.ofMillis(settings.cooldownMillis()).toNanos());
        return true;
    }

    private void request(Player player, QuerySpec query, long cursor, long snapshot, int pageNumber, boolean silent) {
        if (!available(player, silent)) {
            return;
        }
        UUID id = player.getUniqueId();
        long token = ++sequence;
        requests.put(id, token);
        if (pageNumber == 1) {
            sessions.remove(id);
        }
        journal.query(query, cursor, snapshot).whenComplete((page, failure) -> plugin.dispatch(() -> {
            if (!requests.remove(id, token)) {
                return;
            }
            Player recipient = Bukkit.getPlayer(id);
            if (recipient == null || !recipient.hasPermission("blockjournal.lookup")) {
                sessions.remove(id);
                return;
            }
            if (failure != null) {
                Messages.error(recipient, "Поиск не выполнен: " + failureMessage(failure));
                return;
            }
            long expiry = System.nanoTime() + Duration.ofMinutes(settings.sessionMinutes()).toNanos();
            sessions.put(id, new Session(query, page, pageNumber, expiry));
            Messages.page(recipient, query, page, pageNumber);
        }));
    }

    private String failureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof JournalService.StorageException && cause.getCause() == null) {
            return cause.getMessage();
        }
        return "хранилище недоступно. Подробности в журнале сервера.";
    }

    private void flush(CommandSender sender) {
        if (!permitted(sender, "admin")) {
            return;
        }
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        Messages.info(sender, "Сохранение очереди запрошено.");
        journal.flush().whenComplete((status, failure) -> plugin.dispatch(() -> {
            CommandSender recipient = playerId == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(playerId);
            if (recipient == null || !recipient.hasPermission("blockjournal.admin")) {
                return;
            }
            if (failure != null) {
                Messages.error(recipient, "Сохранение не завершено: " + failureMessage(failure));
            } else {
                Messages.info(recipient, "Очередь сохранена. Состояние хранилища: " + status.health());
            }
        }));
    }

    private QueryParser.Origin origin(Location location) {
        return new QueryParser.Origin(location.getWorld().getUID(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private boolean inspecting(Player player) {
        if (!inspectors.contains(player.getUniqueId())) {
            return false;
        }
        if (!player.hasPermission("blockjournal.inspect") || !player.hasPermission("blockjournal.lookup")) {
            inspectors.remove(player.getUniqueId());
            return false;
        }
        return player.getInventory().getItemInMainHand().getType() == settings.inspectTool();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!inspecting(event.getPlayer()) || event.getClickedBlock() == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        QuerySpec query = parser.parse(new String[0], origin(event.getClickedBlock().getLocation()), System.currentTimeMillis());
        request(event.getPlayer(), query, 0, 0, 1, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inspecting(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        inspectors.remove(id);
        cooldowns.remove(id);
        requests.remove(id);
        sessions.remove(id);
    }

    public void expire() {
        long now = System.nanoTime();
        sessions.values().removeIf(session -> session.expiresAt() < now);
        cooldowns.values().removeIf(deadline -> deadline < now);
    }

    public void clear() {
        inspectors.clear();
        cooldowns.clear();
        requests.clear();
        sessions.clear();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> commands = new ArrayList<>();
            commands.add("help");
            if (sender.hasPermission("blockjournal.lookup")) {
                commands.add("lookup");
                commands.add("next");
            }
            if (sender.hasPermission("blockjournal.inspect")) {
                commands.add("inspect");
            }
            if (sender.hasPermission("blockjournal.status")) {
                commands.add("status");
            }
            if (sender.hasPermission("blockjournal.admin")) {
                commands.add("flush");
            }
            return complete(commands, args[0]);
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("lookup") && sender.hasPermission("blockjournal.lookup")) {
            String current = args[args.length - 1];
            if (current.startsWith("u:")) {
                return complete(Bukkit.getOnlinePlayers().stream().map(player -> "u:" + player.getName()).toList(), current);
            }
            return complete(List.of("r:0", "r:5", "r:10", "t:30m", "t:2h", "t:1d",
                    "u:", "uuid:", "a:place", "a:break", "a:explosion", "a:burn"), current);
        }
        return List.of();
    }

    private List<String> complete(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private record Session(QuerySpec query, QueryPage page, int number, long expiresAt) {
    }
}
