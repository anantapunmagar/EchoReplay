package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.util.NbtBytes;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Records world-mutating block events within the active recording's cuboid.
 * Each results in a BLOCK_SET (and for explosions, the resulting block updates).
 */
public final class WorldRecorder implements Listener {

    private final EchoReplay plugin;

    public WorldRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    private RecordingSession session() {
        return plugin.recordingManager().activeSession();
    }

    private boolean recordBlock(org.bukkit.Location loc) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return false;
        if (!loc.getWorld().getUID().equals(s.world().getUID())) return false;
        if (!s.cuboid().contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) return false;
        Cuboid c = s.cuboid();
        var block = loc.getBlock();
        String state = block.getBlockData() == null ? "minecraft:air" : block.getBlockData().getAsString(true);
        int pi = s.paletteIndex(state);
        byte[] nbt = null;
        try {
            var tile = block.getState(true);
            if (tile != null) nbt = NbtBytes.serializeBlockState(tile);
            if (nbt != null && nbt.length == 0) nbt = null;
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            nbt = null;
        }
        s.emit(new TimelineEvent.BlockSet(s.mediaMillis(),
                new BlockPos(loc.getBlockX() - c.min().x(), loc.getBlockY() - c.min().y(), loc.getBlockZ() - c.min().z()),
                pi, nbt));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakAnim(org.bukkit.event.block.BlockDamageEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (!e.getBlock().getWorld().getUID().equals(s.world().getUID())) return;
        Cuboid c = s.cuboid();
        var loc = e.getBlock().getLocation();
        if (c.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            int npc = e.getPlayer() == null ? 0 : s.npcIdFor(e.getPlayer().getUniqueId());
            s.emit(new TimelineEvent.BlockBreakAnim(s.mediaMillis(),
                    new BlockPos(loc.getBlockX() - c.min().x(), loc.getBlockY() - c.min().y(), loc.getBlockZ() - c.min().z()),
                    npc, 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent e) {
        recordBlock(e.getToBlock().getLocation());
        recordBlock(e.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(EntityBlockFormEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) { recordBlock(e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (!e.getLocation().getWorld().getUID().equals(s.world().getUID())) return;
        // Wind-charge bursts travel through the explosion pipeline (zero block
        // damage) but sound nothing like an explosion — their real audio is
        // captured separately as a sound packet. Recording them here replayed
        // every wind charge as entity.generic.explode.
        try {
            if (e.getEntity() instanceof org.bukkit.entity.WindCharge) return;
            if (e.getYield() <= 0 && e.blockList().isEmpty()) return;
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: explosion filter check failed", ex);
        }
        s.emit(new TimelineEvent.Explosion(s.mediaMillis(),
                new dev.idebugger.echoreplay.model.Vec3d(e.getLocation().x(), e.getLocation().y(), e.getLocation().z()),
                e.getYield()));
        for (var b : e.blockList()) {
            recordBlock(b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (!e.getBlock().getWorld().getUID().equals(s.world().getUID())) return;
        s.emit(new TimelineEvent.Explosion(s.mediaMillis(),
                new dev.idebugger.echoreplay.model.Vec3d(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ()),
                e.getYield()));
        for (var b : e.blockList()) {
            recordBlock(b.getLocation());
        }
    }
}
