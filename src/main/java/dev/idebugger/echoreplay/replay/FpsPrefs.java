package dev.idebugger.echoreplay.replay;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player FPS-saver preference for replay playback. When enabled for a
 * viewer, recorded particles are thinned to ~1/4 and sounds to ~1/2 — never
 * fully removed, just much less. Default is off (full quality). Persists to
 * {@code fps_prefs.yml} so low-end clients keep it across restarts.
 */
public final class FpsPrefs {

    private final File file;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public FpsPrefs(File file) {
        this.file = file;
        load();
    }

    public boolean isEnabled(UUID id) {
        return id != null && enabled.contains(id);
    }

    public void setEnabled(UUID id, boolean on) {
        if (id == null) return;
        if (on) enabled.add(id);
        else enabled.remove(id);
        save();
    }

    public boolean toggle(UUID id) {
        boolean next = !isEnabled(id);
        setEnabled(id, next);
        return next;
    }

    private synchronized void load() {
        if (!file.exists()) return;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            enabled.clear();
            for (String s : cfg.getStringList("enabled")) {
                try {
                    enabled.add(UUID.fromString(s));
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: bad fps pref uuid " + s, e);
                }
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: fps prefs load failed", e);
        }
    }

    private synchronized void save() {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            List<String> list = enabled.stream().map(UUID::toString).sorted().toList();
            cfg.set("enabled", list);
            file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: fps prefs save failed", e);
        }
    }
}
