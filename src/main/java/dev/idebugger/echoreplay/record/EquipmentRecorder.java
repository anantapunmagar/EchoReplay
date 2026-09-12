package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.TimelineCodec;
import dev.idebugger.echoreplay.util.Io;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Captures outgoing equipment packets (via Bukkit event hooks) for players in
 * the recording region. Uses a periodic main-thread tick to snapshot per-player
 * equipment, emitting only changes to avoid duplicate spam.
 */
public final class EquipmentRecorder implements Listener {

    private final EchoReplay plugin;
    private final Map<Integer, Map<Integer, String>> lastEquipmentKey = new HashMap<>();
    /** Last seen ItemStack per npc/slot for semantic (isSimilar) comparison. */
    private final Map<Integer, Map<Integer, org.bukkit.inventory.ItemStack>> lastEquipmentItem = new HashMap<>();

    public EquipmentRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    /**
     * Called from RecordingManager.onTick() on main thread. Checks all players
     * in the region for equipment changes and emits EQUIPMENT events.
     */
    public void tick() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Cuboid c = s.cuboid();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.privacy().isExempt(p)) continue;
            if (!p.getWorld().getUID().equals(s.world().getUID())) continue;
            if (!c.contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) continue;
            int npc = s.npcIdFor(p.getUniqueId());
            org.bukkit.inventory.ItemStack[] armor = p.getInventory().getArmorContents();
            org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
            org.bukkit.inventory.ItemStack offhand = p.getInventory().getItemInOffHand();
            emitIfChanged(s, npc, 0, hand);
            emitIfChanged(s, npc, 1, offhand);
            for (int i = 0; i < armor.length; i++) {
                emitIfChanged(s, npc, i + 2, armor[i]);
            }
        }
    }

    private void emitIfChanged(RecordingSession s, int npcId, int slot, org.bukkit.inventory.ItemStack item) {
        Map<Integer, String> playerKeys = lastEquipmentKey.computeIfAbsent(npcId, k -> new HashMap<>());
        Map<Integer, org.bukkit.inventory.ItemStack> playerItems =
                lastEquipmentItem.computeIfAbsent(npcId, k -> new HashMap<>());
        org.bukkit.inventory.ItemStack prev = playerItems.get(slot);
        if (itemsSemanticallyEqual(prev, item)) return;
        playerItems.put(slot, item == null ? null : item.clone());
        String key = item == null || item.getType() == org.bukkit.Material.AIR ? "AIR" : item.getType().name() + ":" + item.getAmount();
        playerKeys.put(slot, key);
        byte[] bytes = serializeItem(item);
        s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, slot, bytes));
    }

    /**
     * Semantic item equality (ignores NBT key ordering and other
     * serialization noise): same material + amount + {@link
     * org.bukkit.inventory.ItemStack#isSimilar} meta. Null and air are
     * treated as equal empties.
     */
    public static boolean itemsSemanticallyEqual(org.bukkit.inventory.ItemStack a,
                                                org.bukkit.inventory.ItemStack b) {
        boolean aEmpty = a == null || a.getType() == org.bukkit.Material.AIR;
        boolean bEmpty = b == null || b.getType() == org.bukkit.Material.AIR;
        if (aEmpty && bEmpty) return true;
        if (aEmpty != bEmpty) return false;
        if (a.getType() != b.getType()) return false;
        if (a.getAmount() != b.getAmount()) return false;
        try {
            return a.isSimilar(b);
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.FINE,
                    "EchoReplay: equipment similarity check failed, treating as changed", e);
            return false;
        }
    }

    /** Byte-blob semantic equality for replay-side dedup (deserializes both). */
    public static boolean itemBytesSemanticallyEqual(byte[] a, byte[] b) {
        if (a == b) return true;
        if (a == null || b == null) {
            return (a == null || a.length == 0) && (b == null || b.length == 0);
        }
        if (a.length == 0 && b.length == 0) return true;
        if (java.util.Arrays.equals(a, b)) return true;
        org.bukkit.inventory.ItemStack ia = deserializeItem(a);
        org.bukkit.inventory.ItemStack ib = deserializeItem(b);
        return itemsSemanticallyEqual(ia, ib);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        Player p = e.getPlayer();
        if (!p.getWorld().getUID().equals(s.world().getUID())) return;
        if (!s.cuboid().contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) return;
        int npc = s.npcIdFor(p.getUniqueId());
        emitFullEquipment(s, npc, p);
    }

    public void emitFullEquipment(RecordingSession s, int npcId, Player p) {
        org.bukkit.inventory.ItemStack[] armor = p.getInventory().getArmorContents();
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        org.bukkit.inventory.ItemStack offhand = p.getInventory().getItemInOffHand();
        s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, 0, serializeItem(hand)));
        s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, 1, serializeItem(offhand)));
        for (int i = 0; i < armor.length; i++) {
            s.emit(new TimelineEvent.Equipment(s.mediaMillis(), npcId, i + 2, serializeItem(armor[i])));
        }
    }

    public static byte[] serializeItem(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType() == org.bukkit.Material.AIR) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            org.bukkit.util.io.BukkitObjectOutputStream out = new org.bukkit.util.io.BukkitObjectOutputStream(bos);
            out.writeObject(item);
            out.flush();
        } catch (IOException e) {
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.FINE,
                    "EchoReplay: failed to serialize item " + item.getType() + ", storing as air", e);
            return new byte[0];
        }
        return bos.toByteArray();
    }

    /**
     * Version-tolerant deserialization: Bukkit NBT is not guaranteed stable
     * across Minecraft versions (e.g. 1.21.5 vs 1.21.11 component changes), so
     * unknown/corrupt blobs gracefully downgrade to air instead of throwing.
     * Callers must treat air as "unknown item on this version".
     */
    public static org.bukkit.inventory.ItemStack deserializeItem(byte[] data) {
        if (data == null || data.length == 0) {
            return org.bukkit.inventory.ItemStack.empty();
        }
        try {
            org.bukkit.util.io.BukkitObjectInputStream in =
                    new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(data));
            Object obj = in.readObject();
            if (obj instanceof org.bukkit.inventory.ItemStack item) {
                if (!isValidDeserialized(item)) {
                    org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.FINE,
                            "EchoReplay: deserialized item failed validation, downgrading to air");
                    return org.bukkit.inventory.ItemStack.empty();
                }
                return item;
            }
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().log(java.util.logging.Level.FINE,
                    "EchoReplay: could not deserialize item blob (" + data.length
                            + " bytes, likely cross-version NBT), downgrading to air", e);
            // Cross-version: a 1.21.11 spear (or other new item) has no
            // Material on 1.21.5 — substitute an appropriate visible item
            // (spear -> trident) instead of an empty hand.
            try {
                org.bukkit.inventory.ItemStack fb =
                        dev.idebugger.echoreplay.util.CrossVersion.fallbackItemForBlob(data);
                if (fb != null && !fb.getType().isAir()) return fb;
            } catch (Exception ex) {
                java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE,
                        "EchoReplay: cross-version item fallback failed", ex);
            }
        }
        return org.bukkit.inventory.ItemStack.empty();
    }

    /** Validate a deserialized stack (null type / bad amount = corrupt). */
    public static boolean isValidDeserialized(org.bukkit.inventory.ItemStack item) {
        if (item == null) return false;
        try {
            if (item.getType() == null) return false;
            if (item.getType() == org.bukkit.Material.AIR) return true;
            int amt = item.getAmount();
            return amt > 0 && amt <= 99;
        } catch (Exception e) {
            return false;
        }
    }
}
