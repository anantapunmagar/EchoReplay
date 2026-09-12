package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.TimelineEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Records damage dealt to players and mobs inside the recording region, so a
 * playback can show the client-side hurt red-flash animation at the right time.
 */
public final class DamageRecorder implements Listener {

    private final EchoReplay plugin;

    public DamageRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (e.isCancelled() || e.getDamage() <= 0) return;
        Entity ent = e.getEntity();
        if (ent instanceof Player pv && plugin.privacy().isExempt(pv)) return;
        if (!ent.getWorld().getUID().equals(s.world().getUID())) return;
        if (!s.cuboid().contains(ent.getLocation().getBlockX(),
                ent.getLocation().getBlockY(), ent.getLocation().getBlockZ())) return;
        int npc = s.npcIdFor(ent.getUniqueId());
        int anim = ent instanceof Player ? 0 : 1;
        // Source label is cosmetic only (not replayed); keep it static to stay
        // version-agnostic across Paper 1.21.5 / 1.21.11 damage-source APIs.
        s.emit(new TimelineEvent.Damage(s.mediaMillis(), npc, "generic", e.getDamage(), anim));
    }
}