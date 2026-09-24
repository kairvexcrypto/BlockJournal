package ru.warndev.blockjournal;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlockJournalPlugin extends JavaPlugin {
    private final AtomicBoolean running = new AtomicBoolean();
    private JournalService journal;
    private JournalCommand commands;
    private long lastRejected;
    private long lastTruncated;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Settings settings = Settings.load(getConfig());
            journal = new JournalService(settings, getDataFolder().toPath(), getLogger());
            commands = new JournalCommand(this, journal, settings);
            PluginCommand command = Objects.requireNonNull(getCommand("blockjournal"));
            command.setExecutor(commands);
            command.setTabCompleter(commands);
            Bukkit.getPluginManager().registerEvents(new CaptureListener(settings, journal), this);
            Bukkit.getPluginManager().registerEvents(commands, this);
            running.set(true);
            Bukkit.getScheduler().runTaskTimer(this, this::monitor, 1200L, 1200L);
            getLogger().info("BlockJournal enabled: SQLite/WAL, retention " + settings.retentionDays() + " days");
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "BlockJournal could not start", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void monitor() {
        commands.expire();
        JournalMetrics.Snapshot status = journal.status();
        if (status.rejected() > lastRejected) {
            getLogger().severe("Audit gap: " + (status.rejected() - lastRejected) + " events rejected due to storage pressure");
            lastRejected = status.rejected();
        }
        if (status.truncated() > lastTruncated) {
            getLogger().warning("Explosion capture limit skipped " + (status.truncated() - lastTruncated) + " blocks");
            lastTruncated = status.truncated();
        }
    }

    public void dispatch(Runnable action) {
        if (!running.get()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                if (running.get()) {
                    action.run();
                }
            });
        } catch (IllegalPluginAccessException error) {
            if (running.get()) {
                getLogger().log(Level.WARNING, "Could not deliver storage result", error);
            }
        }
    }

    @Override
    public void onDisable() {
        running.set(false);
        Bukkit.getScheduler().cancelTasks(this);
        if (commands != null) {
            commands.clear();
        }
        if (journal != null) {
            journal.close();
        }
    }
}
