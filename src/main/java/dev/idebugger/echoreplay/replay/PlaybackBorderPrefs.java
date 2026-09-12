package dev.idebugger.echoreplay.replay;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player personal preference for border particles (selection/region
 * outlines and playback borders). Stores the set of UUIDs that have
 * *disabled* borders via {@code /er border}. Default is enabled.
 * Persists to {@code border_prefs.yml} in the plugin data folder so the
 * toggle survives restarts and is visible per-player.
 */
public final class PlaybackBorderPrefs {

    private final File file;
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();

    public PlaybackBorderPrefs(File file) {
        this.file = file;
        load();
    }

    public boolean isEnabled(UUID id) {
        return !disabled.contains(id);
    }

    public boolean isEnabled(org.bukkit.entity.Player p) {
        return isEnabled(p.getUniqueId());
    }

    public void setEnabled(UUID id, boolean enabled) {
        if (enabled) disabled.remove(id);
        else disabled.add(id);
        save();
    }

    public boolean toggle(UUID id) {
        boolean currentlyEnabled = isEnabled(id);
        setEnabled(id, !currentlyEnabled);
        return !currentlyEnabled;
    }

    private synchronized void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        disabled.clear();
        // New format: disabled: [uuid, ...]
        List<String> list = cfg.getStringList("disabled");
        if (!list.isEmpty()) {
            for (String s : list) {
                try { disabled.add(UUID.fromString(s)); } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
            }
            return;
        }
        // Legacy fallback: players.<uuid>.borderEnabled = false
        // D-8.10: removed the dead top-level `for (String key : cfg.getKeys(false)) {}`
        // loop — it had an empty body and was inherited from an older migration.
        if (cfg.contains("players")) {
            if (cfg.getConfigurationSection("players") != null) {
                for (String uuid : cfg.getConfigurationSection("players").getKeys(false)) {
                    boolean enabled = cfg.getBoolean("players." + uuid + ".borderEnabled", true);
                    if (!enabled) {
                        try { disabled.add(UUID.fromString(uuid)); } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
                    }
                }
            }
        } else {
            // Flat uuid -> bool
            for (String key : cfg.getKeys(false)) {
                if (key.equals("disabled")) continue;
                try {
                    UUID id = UUID.fromString(key);
                    boolean enabled = cfg.getBoolean(key, true);
                    if (!enabled) disabled.add(id);
                } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        // Prefer list format for compactness
        cfg.set("disabled", disabled.stream().map(UUID::toString).sorted().toList());
        try {
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed IOException", ignored);}
    }
}
