package ru.warndev.blockjournal;

import java.util.List;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

public final class CaptureListener implements Listener {
    private final Settings settings;
    private final JournalService journal;

    public CaptureListener(Settings settings, JournalService journal) {
        this.settings = settings;
        this.journal = journal;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!settings.capturePlace() || !event.canBuild() || event instanceof BlockMultiPlaceEvent) {
            return;
        }
        record(event.getBlockPlaced(), event.getPlayer(), Action.PLACE,
                event.getBlockReplacedState().getBlockData().getAsString(),
                event.getBlockPlaced().getBlockData().getAsString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (!settings.capturePlace() || !event.canBuild()) {
            return;
        }
        for (BlockState state : event.getReplacedBlockStates()) {
            record(state.getBlock(), event.getPlayer(), Action.PLACE,
                    state.getBlockData().getAsString(), state.getBlock().getBlockData().getAsString());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (settings.captureBreak()) {
            record(event.getBlock(), event.getPlayer(), Action.BREAK,
                    event.getBlock().getBlockData().getAsString(), "minecraft:air");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (settings.captureFire()) {
            record(event.getBlock(), null, Action.BURN,
                    event.getBlock().getBlockData().getAsString(), "minecraft:air");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        explosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        explosion(event.blockList());
    }

    private void explosion(List<Block> blocks) {
        if (!settings.captureExplosions()) {
            return;
        }
        int limit = Math.min(blocks.size(), settings.maxExplosionBlocks());
        if (limit < blocks.size()) {
            journal.truncated(blocks.size() - limit);
        }
        for (int index = 0; index < limit; index++) {
            Block block = blocks.get(index);
            record(block, null, Action.EXPLOSION, block.getBlockData().getAsString(), "minecraft:air");
        }
    }

    private void record(Block block, Player actor, Action action, String before, String after) {
        if (settings.excludedWorlds().contains(block.getWorld().getName())) {
            return;
        }
        journal.offer(new BlockEntry(UUID.randomUUID(), System.currentTimeMillis(),
                actor == null ? null : actor.getUniqueId(), actor == null ? "environment" : actor.getName(),
                block.getWorld().getUID(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                action, before, after));
    }
}
