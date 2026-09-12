package dev.idebugger.echoreplay.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional cached listing of recordings in recordings/index.yml. Not required
 * for playback (the reader can always re-read headers), but speeds up /er list.
 */
public final class RecordingIndex {

    private final File file;
    private final ConcurrentHashMap<String, RecordingEntry> entries = new ConcurrentHashMap<>();

    public RecordingIndex(File file) {
        this.file = file;
        load();
    }

    public synchronized void put(RecordingEntry entry) {
        entries.put(entry.name(), entry);
        save();
    }

    public synchronized void remove(String name) {
        entries.remove(name);
        save();
    }

    public synchronized RecordingEntry get(String name) {
        return entries.get(name);
    }

    public synchronized List<RecordingEntry> all() {
        return new ArrayList<>(entries.values());
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            try {
                entries.put(key, new RecordingEntry(
                        key,
                        java.util.UUID.fromString(cfg.getString(key + ".worldUuid")),
                        cfg.getString(key + ".worldName"),
                        cfg.getLong(key + ".durationMillis"),
                        cfg.getLong(key + ".sizeBytes"),
                        cfg.getLong(key + ".epochMillis"),
                        cfg.getInt(key + ".minX"), cfg.getInt(key + ".minY"), cfg.getInt(key + ".minZ"),
                        cfg.getInt(key + ".maxX"), cfg.getInt(key + ".maxY"), cfg.getInt(key + ".maxZ")
                ));
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (RecordingEntry e : entries.values()) {
            String k = e.name();
            cfg.set(k + ".worldUuid", e.worldUuid().toString());
            cfg.set(k + ".worldName", e.worldName());
            cfg.set(k + ".durationMillis", e.durationMillis());
            cfg.set(k + ".sizeBytes", e.sizeBytes());
            cfg.set(k + ".epochMillis", e.epochMillis());
            cfg.set(k + ".minX", e.minX());
            cfg.set(k + ".minY", e.minY());
            cfg.set(k + ".minZ", e.minZ());
            cfg.set(k + ".maxX", e.maxX());
            cfg.set(k + ".maxY", e.maxY());
            cfg.set(k + ".maxZ", e.maxZ());
        }
        try {
            cfg.save(file);
        } catch (IOException ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed IOException", ignored);
        }
    }
}
