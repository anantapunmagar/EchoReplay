package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

/**
 * Records connection-lifecycle events (join, quit, respawn, teleport) for the
 * active recording.
 */
public final class ConnectionRecorder implements Listener {

    private final EchoReplay plugin;

    public ConnectionRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    private RecordingSession session() {
        return plugin.recordingManager().activeSession();
    }

    private boolean inRegion(RecordingSession s, Player p) {
        return p != null && p.getWorld().getUID().equals(s.world().getUID())
                && s.cuboid().contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (plugin.privacy().isExempt(p)) return;
        if (!inRegion(s, p)) return;
        int npc = s.npcIdFor(p.getUniqueId());
        s.markEntitySpawned(p.getUniqueId());
        s.emit(new TimelineEvent.PlayerSpawn(s.mediaMillis(), npc, p.getUniqueId(), p.getName(),
                skin(p), pos(p), rot(p), equipment(p), null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (plugin.privacy().isExempt(p)) return;
        if (!p.getWorld().getUID().equals(s.world().getUID())) return;
        int npc = s.npcIdFor(p.getUniqueId());
        // D-8.5: emit only PlayerLeave — v1 ALSO emitted EntityLeave for the
        // same npcId, which doubled event volume for churn-y players and
        // confused timeline tooling. PlayerLeave's replay path already despawns
        // the fake (see ReplaySession.applyEvent's PlayerLeave case), so the
        // second EntityLeave was a no-op on playback but a real cost on record.
        s.emit(new TimelineEvent.PlayerLeave(s.mediaMillis(), npc, 1));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (p != null && plugin.privacy().isExempt(p)) {
            int npc = s.npcIdFor(p.getUniqueId());
            s.emit(new TimelineEvent.PlayerLeave(s.mediaMillis(), npc, 1));
            s.emit(new TimelineEvent.EntityLeave(s.mediaMillis(), npc));
            return;
        }
        if (p == null || !p.getWorld().getUID().equals(s.world().getUID())) {            int npc = p == null ? 0 : s.npcIdFor(p.getUniqueId());
            if (p != null) {
                // D-8.5: same dedup — PlayerLeave alone.
                s.emit(new TimelineEvent.PlayerLeave(s.mediaMillis(), npc, 1));
            }
            return;
        }
        if (s.cuboid().contains(e.getRespawnLocation().getBlockX(), e.getRespawnLocation().getBlockY(), e.getRespawnLocation().getBlockZ())) {
            int npc = s.npcIdFor(p.getUniqueId());
            s.markEntitySpawned(p.getUniqueId());
            java.util.List<byte[]> equip = s.takeCachedPlayerEquipment(p.getUniqueId());
            if (equip.isEmpty()) {
                equip = equipment(p);
            }
            s.emit(new TimelineEvent.PlayerSpawn(s.mediaMillis(), npc, p.getUniqueId(), p.getName(),
                    skin(p), pos(e.getRespawnLocation()), rot(e.getRespawnLocation()), equip, null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (p == null) return;
        if (plugin.privacy().isExempt(p)) return;
        var to = e.getTo() != null && e.getTo().getWorld() != null && e.getTo().getWorld().getUID().equals(s.world().getUID());
        int npc = s.npcIdFor(p.getUniqueId());
        if (to) {
            s.emit(new TimelineEvent.Teleport(s.mediaMillis(), npc,
                    new Vec3d(e.getTo().x(), e.getTo().y(), e.getTo().z()),
                    new Rotation(e.getTo().getPitch(), e.getTo().getYaw(), e.getTo().getYaw())));
        }
    }

    static PlayerSkin skin(Player p) {
        try {
            com.github.retrooper.packetevents.protocol.player.User user =
                    com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(p);
            if (user != null) {
                var props = user.getProfile().getTextureProperties();
                for (var prop : props) {
                    if ("textures".equals(prop.getName())) {
                        return new PlayerSkin(prop.getValue(),
                                prop.getSignature() == null ? null : prop.getSignature());
                    }
                }
            }
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
        return new PlayerSkin(null, null);
    }

    static Vec3d pos(Player p) {
        return pos(p.getLocation());
    }

    static Vec3d pos(org.bukkit.Location l) {
        return new Vec3d(l.x(), l.y(), l.z());
    }

    static Rotation rot(Player p) {
        return rot(p.getLocation());
    }

    static Rotation rot(org.bukkit.Location l) {
        return new Rotation(l.getPitch(), l.getYaw(), l.getYaw());
    }

    static List<byte[]> equipment(Player p) {
        var eq = p.getInventory();
        return List.of(
                EquipmentRecorder.serializeItem(eq.getItemInMainHand()),
                EquipmentRecorder.serializeItem(eq.getItemInOffHand()),
                EquipmentRecorder.serializeItem(eq.getBoots()),
                EquipmentRecorder.serializeItem(eq.getLeggings()),
                EquipmentRecorder.serializeItem(eq.getChestplate()),
                EquipmentRecorder.serializeItem(eq.getHelmet())
        );
    }
}
