package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Privacy opt-outs for recording. An exempt player is never written into a
 * take (no spawn/move/equipment/vitals/chat/action/damage events) and never
 * sees the red REC bossbar — but they also may not edit blocks inside an
 * active recording cuboid, since they shouldn't be in the recording at all.
 *
 * <p>Config overrides (config.yml {@code privacy} section):
 * <ul>
 *   <li>{@code privacy.enabled=false} — whole feature off: the command
 *       refuses, everyone is recorded, no build restrictions.</li>
 *   <li>{@code privacy.enforce-recording=true} — admin override: every
 *       stored opt-out is ignored and everyone is recorded anyway.</li>
 * </ul>
 */
public final class PrivacyManager implements Listener {

    private final EchoReplay plugin;
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;
    private volatile boolean enforceRecording = false;
    private volatile File file;

    public PrivacyManager(EchoReplay plugin) {
        this.plugin = plugin;
    }

    public void onEnable(org.bukkit.configuration.file.FileConfiguration config) {
        enabled = config.getBoolean("privacy.enabled", true);
        enforceRecording = config.getBoolean("privacy.enforce-recording", false);
        file = new File(plugin.getDataFolder(), "privacy.yml");
        load();
    }

    /** Master switch from config. */
    public boolean featureEnabled() {
        return enabled;
    }

    /** Admin override from config: record everyone regardless of opt-outs. */
    public boolean enforced() {
        return enforceRecording;
    }

    public boolean isOptedOut(UUID id) {
        return id != null && optedOut.contains(id);
    }

    /** True when this player must be left out of recordings right now. */
    public boolean isExempt(UUID id) {
        return enabled && !enforceRecording && isOptedOut(id);
    }

    public boolean isExempt(Player p) {
        return p != null && isExempt(p.getUniqueId());
    }

    public void setOptedOut(UUID id, boolean out) {
        if (id == null) return;
        if (out) optedOut.add(id);
        else optedOut.remove(id);
        save();
    }

    /** Flips the stored opt-out; returns the new opt-out state. */
    public boolean toggle(UUID id) {
        boolean next = !isOptedOut(id);
        setOptedOut(id, next);
        return next;
    }

    private synchronized void load() {
        if (file == null || !file.exists()) return;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            optedOut.clear();
            for (String s : cfg.getStringList("opted-out")) {
                try {
                    optedOut.add(UUID.fromString(s));
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: bad privacy uuid " + s, e);
                }
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: privacy load failed", e);
        }
    }

    private synchronized void save() {
        if (file == null) return;
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("opted-out", optedOut.stream().map(UUID::toString).sorted().toList());
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: privacy save failed", e);
        }
    }

    // ---- build guard: opt-outs can't edit inside an active recording ----

    private boolean guardApplies(Player p, org.bukkit.block.Block b) {
        if (!enabled || p == null || b == null) return false;
        if (!isExempt(p.getUniqueId())) return false;
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null) return false;
        RecordingSession.State st = s.state();
        if (st != RecordingSession.State.RECORDING && st != RecordingSession.State.SNAPSHOTTING) return false;
        try {
            if (!b.getWorld().getUID().equals(s.world().getUID())) return false;
            return s.cuboid().contains(b.getX(), b.getY(), b.getZ());
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: privacy guard check failed", e);
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (guardApplies(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(dev.idebugger.echoreplay.util.Text.mm(
                    "<red>Privacy opt-out: you can't edit inside an active recording zone.</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (guardApplies(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(dev.idebugger.echoreplay.util.Text.mm(
                    "<red>Privacy opt-out: you can't edit inside an active recording zone.</red>"));
        }
    }
}
