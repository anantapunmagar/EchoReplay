package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.TimelineEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Records player actions: animation/swing, item use start/stop, sneak/sprint,
 * pose-ish flags. Movement handled in MovementRecorder (packets).
 */
public final class ActionRecorder implements Listener {

    private final EchoReplay plugin;

    public ActionRecorder(EchoReplay plugin) {
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
    public void onAnimation(PlayerAnimationEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (plugin.privacy().isExempt(p)) return;
        if (!inRegion(s, p)) return;
        int npc = s.npcIdFor(p.getUniqueId());
        int anim = switch (e.getAnimationType()) {
            case ARM_SWING -> 0;
            case OFF_ARM_SWING -> 1;
        };
        s.emit(new TimelineEvent.Animation(s.mediaMillis(), npc, anim));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (plugin.privacy().isExempt(p)) return;
        if (!inRegion(s, p)) return;
        int npc = s.npcIdFor(p.getUniqueId());
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (right) {
            int hand = e.getHand() == EquipmentSlot.OFF_HAND ? 1 : 0;
            s.emit(new TimelineEvent.ItemUse(s.mediaMillis(), npc, hand, true));
        }
        // Left-click arm swings arrive via PlayerAnimationEvent (onAnimation);
        // emitting them here too produced double swings in the replay.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (plugin.privacy().isExempt(p)) return;
        if (!inRegion(s, p)) return;
        int npc = s.npcIdFor(p.getUniqueId());
        s.emit(new TimelineEvent.ItemUse(s.mediaMillis(), npc, 0, false));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (plugin.privacy().isExempt(e.getPlayer())) return;
        if (!inRegion(s, e.getPlayer())) return;
        int npc = s.npcIdFor(e.getPlayer().getUniqueId());
        int flags = (e.isSneaking() ? 0x02 : 0) | (e.getPlayer().isSprinting() ? 0x08 : 0);
        s.emit(new TimelineEvent.SneakSprint(s.mediaMillis(), npc, flags));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprint(PlayerToggleSprintEvent e) {
        RecordingSession s = session();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (plugin.privacy().isExempt(e.getPlayer())) return;
        if (!inRegion(s, e.getPlayer())) return;
        int npc = s.npcIdFor(e.getPlayer().getUniqueId());
        int flags = (e.getPlayer().isSneaking() ? 0x02 : 0) | (e.isSprinting() ? 0x08 : 0);
        s.emit(new TimelineEvent.SneakSprint(s.mediaMillis(), npc, flags));
    }
}
