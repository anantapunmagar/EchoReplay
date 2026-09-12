package dev.idebugger.echoreplay.select;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player in-memory selections. Persists across server restarts only if we
 * choose to; for now it is memory-only (per spec "persistent in memory,
 * optional PDC").
 */
public final class SelectionManager {

    private final Map<UUID, Selection> byPlayer = new ConcurrentHashMap<>();

    public Selection get(Player p) {
        return byPlayer.computeIfAbsent(p.getUniqueId(), id -> new Selection(p.getWorld()));
    }

    /** Existing selection or null — without creating one. */
    public Selection getIfExists(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public void clear(Player p) {
        byPlayer.remove(p.getUniqueId());
    }
}
