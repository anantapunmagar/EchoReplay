package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;

import java.util.List;

/**
 * Records entity spawn / move / death for the active recording. Player entities
 * are handled by ConnectionRecorder; this handles mobs, items, projectiles.
 */
public final class EntityRecorder implements Listener {

    private final EchoReplay plugin;

    public EntityRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    private RecordingSession session() {
        return plugin.recordingManager().activeSession();
    }

    private boolean inCuboid(RecordingSession s, Entity e) {
        return e.getWorld().getUID().equals(s.world().getUID())
                && s.cuboid().contains(e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(EntitySpawnEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (!inCuboid(s, ent)) return;
        // Emit the spawn only the first time it is seen (the per-tick recorder
        // would otherwise emit a second spawn on its next observation).
        if (!s.markEntitySpawned(ent.getUniqueId())) return;
        int npc = s.npcIdFor(ent.getUniqueId());
        s.emit(new TimelineEvent.EntitySpawn(s.mediaMillis(), npc, ent.getUniqueId(),
                ent.getType().getKey().toString(),
                new Vec3d(ent.getLocation().x(), ent.getLocation().y(), ent.getLocation().z()),
                new Rotation(ent.getLocation().getPitch(), ent.getLocation().getYaw(), ent.getLocation().getYaw()),
                dev.idebugger.echoreplay.model.RecordedMetadata.capture(ent)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Entity ent = e.getEntity();
        // PlayerDeathEvent IS-AN EntityDeathEvent, so this covers players too.
        if (!ent.getWorld().getUID().equals(s.world().getUID())) return;
        if (s.cuboid().contains(ent.getLocation().getBlockX(),
                ent.getLocation().getBlockY(), ent.getLocation().getBlockZ())) {
            int npc = s.npcIdFor(ent.getUniqueId());
            if (ent instanceof Player p && p.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY)) {
                java.util.List<byte[]> equip = List.of(
                        dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(p.getInventory().getItemInMainHand()),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(p.getInventory().getItemInOffHand()),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(p.getInventory().getBoots()),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(p.getInventory().getLeggings()),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(p.getInventory().getChestplate()),
                        dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(p.getInventory().getHelmet())
                );
                s.cachePlayerEquipment(p.getUniqueId(), equip);
            }
            s.emit(new TimelineEvent.Death(s.mediaMillis(), npc));
            s.emit(new TimelineEvent.EntityLeave(s.mediaMillis(), npc));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player) return;
        if (e.getCause() == EntityRemoveEvent.Cause.DEATH) return;
        if (!ent.getWorld().getUID().equals(s.world().getUID())) return;
        int npc = s.npcIdFor(ent.getUniqueId());
        s.emit(new TimelineEvent.EntityLeave(s.mediaMillis(), npc));
    }
}
