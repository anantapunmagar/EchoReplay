package dev.idebugger.echoreplay.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Cross-version fallbacks for blocks/items recorded on a newer Minecraft
 * version than the one playing them back (e.g. recorded on 1.21.11, played on
 * 1.21.5).
 *
 * <p>Recording always stores native strings/blobs. Playback resolves through
 * here: unknown ids degrade to an <em>appropriate</em> visible substitute
 * (stone/trident/...) instead of air holes or empty hands, and every
 * substitution is logged at FINE with the original id.
 */
public final class CrossVersion {

    private CrossVersion() {}

    /** Newer block id (lowercase, no properties) -> older equivalent. */
    private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();
    /** Newer material name (upper-case) -> older equivalent. */
    private static final Map<String, String> ITEM_ALIASES = new HashMap<>();

    static {
        // 1.21.11 additions (extend as new versions add content).
        // Spear family (item + any block form) -> trident/stick equivalents.
        ITEM_ALIASES.put("SPEAR", "TRIDENT");
        ITEM_ALIASES.put("COPPER_SPEAR", "TRIDENT");
        ITEM_ALIASES.put("WOODEN_SPEAR", "WOODEN_SWORD");
        ITEM_ALIASES.put("STONE_SPEAR", "STONE_SWORD");
        ITEM_ALIASES.put("IRON_SPEAR", "IRON_SWORD");
        ITEM_ALIASES.put("GOLDEN_SPEAR", "GOLDEN_SWORD");
        ITEM_ALIASES.put("DIAMOND_SPEAR", "DIAMOND_SWORD");
        ITEM_ALIASES.put("NETHERITE_SPEAR", "NETHERITE_SWORD");
        BLOCK_ALIASES.put("minecraft:spear", "minecraft:trident");
        // Copper golem / shelf family -> chest/planks so containers stay visible.
        BLOCK_ALIASES.put("minecraft:copper_chest", "minecraft:chest");
        BLOCK_ALIASES.put("minecraft:copper_golem_statue", "minecraft:armor_stand");
        BLOCK_ALIASES.put("minecraft:shelf", "minecraft:oak_planks");
        BLOCK_ALIASES.put("minecraft:acacia_shelf", "minecraft:oak_planks");
        // Test/unknown technical blocks -> stone (visible, solid).
        BLOCK_ALIASES.put("minecraft:test_block", "minecraft:stone");
        BLOCK_ALIASES.put("minecraft:test_instance_block", "minecraft:structure_block");
    }

    /** Base id of a state string (strips {@code [props]}). */
    public static String baseId(String stateString) {
        if (stateString == null) return "minecraft:air";
        int b = stateString.indexOf('[');
        String id = (b >= 0 ? stateString.substring(0, b) : stateString).trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? "minecraft:air" : id;
    }

    /**
     * Resolve a recorded block state on this server version, substituting an
     * appropriate visible block when unknown. Never throws; returns null only
     * when even air cannot be parsed (practically never).
     */
    public static BlockData blockStateOrFallback(String stateString) {
        if (stateString == null || stateString.isEmpty()) return airData();
        // 1) Native parse.
        try {
            BlockData direct = Bukkit.createBlockData(stateString);
            if (direct != null) return direct;
        } catch (Exception e) {
            logFine("unknown block '" + stateString + "', trying fallback", e);
        }
        String base = baseId(stateString);
        // 2) Known alias (full string, then base id).
        String alias = BLOCK_ALIASES.get(stateString.toLowerCase(Locale.ROOT));
        if (alias == null) alias = BLOCK_ALIASES.get(base);
        if (alias != null) {
            try {
                BlockData d = Bukkit.createBlockData(alias);
                if (d != null) {
                    logFine("block '" + stateString + "' -> '" + alias + "'", null);
                    return d;
                }
            } catch (Exception e) {
                logFine("alias block '" + alias + "' also unknown", e);
            }
        }
        // 3) Strip properties and retry base id (new props on old version).
        if (!base.equals(stateString)) {
            try {
                BlockData d = Bukkit.createBlockData(base);
                if (d != null) {
                    logFine("block '" + stateString + "' -> base '" + base + "'", null);
                    return d;
                }
            } catch (Exception e) {
                logFine("base block '" + base + "' unknown", e);
            }
        }
        // 4) Generic visible substitute by keyword (never air holes).
        String generic = genericBlockFor(base);
        try {
            BlockData d = Bukkit.createBlockData(generic);
            if (d != null) {
                logFine("block '" + stateString + "' -> generic '" + generic + "'", null);
                return d;
            }
        } catch (Exception e) {
            logFine("generic block '" + generic + "' unknown", e);
        }
        return airData();
    }

    /** Keyword-based visible substitute (no air holes for unknown solids). */
    public static String genericBlockFor(String baseId) {
        String b = baseId.toLowerCase(Locale.ROOT);
        if (b.contains("glass") || b.contains("ice") || b.contains("leaves")) return "minecraft:glass";
        if (b.contains("log") || b.contains("wood") || b.contains("plank") || b.contains("shelf")
                || b.contains("chest") || b.contains("barrel") || b.contains("door") || b.contains("fence")
                || b.contains("stairs") || b.contains("slab")) return "minecraft:oak_planks";
        if (b.contains("flower") || b.contains("sapling") || b.contains("grass") || b.contains("fern")
                || b.contains("vine") || b.contains("moss")) return "minecraft:grass_block";
        if (b.contains("lamp") || b.contains("lantern") || b.contains("torch") || b.contains("glow"))
            return "minecraft:torch";
        if (b.contains("ore") || b.contains("stone") || b.contains("brick") || b.contains("concrete")
                || b.contains("copper") || b.contains("iron") || b.contains("gold") || b.contains("diamond")
                || b.contains("test")) return "minecraft:stone";
        return "minecraft:stone";
    }

    /**
     * Fallback item for a blob that failed to deserialize (cross-version NBT).
     * Scans the raw stream for material keywords (Bukkit serialization embeds
     * the enum name) and maps to an appropriate existing item. Returns air only
     * when nothing maps (caller already handles air).
     */
    public static ItemStack fallbackItemForBlob(byte[] blob) {
        if (blob == null || blob.length == 0) return ItemStack.empty();
        String hay;
        try {
            hay = new String(blob, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return ItemStack.empty();
        }
        for (Map.Entry<String, String> e : ITEM_ALIASES.entrySet()) {
            if (hay.contains(e.getKey())) {
                ItemStack mapped = materialOrNull(e.getValue());
                if (mapped != null) {
                    logFine("item blob containing '" + e.getKey() + "' -> '" + e.getValue() + "'", null);
                    return mapped;
                }
            }
        }
        // Generic weapon/tool fallback: keep something visible in hand.
        if (hay.contains("SPEAR")) {
            ItemStack t = materialOrNull("TRIDENT");
            if (t != null) return t;
        }
        if (hay.contains("SWORD") || hay.contains("AXE") || hay.contains("PICKAXE")
                || hay.contains("SHOVEL") || hay.contains("HOE")) {
            ItemStack s = materialOrNull("STICK");
            if (s != null) return s;
        }
        return ItemStack.empty();
    }

    /** Map a material name to an existing stack, via aliases, else null. */
    public static ItemStack materialOrNull(String name) {
        if (name == null) return null;
        String upper = name.toUpperCase(Locale.ROOT);
        String alias = ITEM_ALIASES.get(upper);
        if (alias != null) upper = alias;
        try {
            Material m = Material.matchMaterial(upper);
            if (m != null && m.isItem()) return new ItemStack(m);
        } catch (Exception e) {
            logFine("material '" + name + "' unknown", e);
        }
        return null;
    }

    private static BlockData airData() {
        try {
            return Bukkit.createBlockData("minecraft:air");
        } catch (Exception e) {
            return null;
        }
    }

    private static void logFine(String msg, Throwable t) {
        try {
            if (t != null) {
                java.util.logging.Logger.getLogger("EchoReplay")
                        .log(java.util.logging.Level.FINE, "EchoReplay cross-version: " + msg, t);
            } else {
                java.util.logging.Logger.getLogger("EchoReplay")
                        .log(java.util.logging.Level.FINE, "EchoReplay cross-version: " + msg);
            }
        } catch (Exception ignored) {
            // Logging must never break playback.
            java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
    }
}
