package dev.idebugger.echoreplay.util;

import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Serialize block-entity contents (container inventories, sign text, etc.) to
 * bytes using Paper/Bukkit {@code BukkitObjectOutputStream} so we stay off NMS.
 *
 * This captures the *inventory contents* and simple state of a block state.
 * Full raw NBT is not reachable via pure API; we store what the Bukkit API can
 * hand us and reconstruct on replay.
 */
public final class NbtBytes {

    private NbtBytes() {}

    private static final String ANCHOR = "org.bukkit.block.RespawnAnchor";

    /**
     * Resolved exactly once. {@code Class.forName} must never run per block:
     * this method executes on the server thread for every recorded block, and
     * on Paper/Purpur each lookup passes through the reflection remapper plus
     * a full jar/classpath scan on a miss (the interface does not exist in the
     * Bukkit API - only the BlockData variant does). Per-call misses stall the
     * tick loop into watchdog hangs, so the result is cached forever.
     */
    private static final Class<?> ANCHOR_CLASS = loadAnchorClass();

    private static Class<?> loadAnchorClass() {
        try {
            return Class.forName(ANCHOR);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static boolean isRespawnAnchor(Object state) {
        return state != null && ANCHOR_CLASS != null && ANCHOR_CLASS.isInstance(state);
    }

    private static int getAnchorCharges(Object state) {
        try {
            Object v = state.getClass().getMethod("getCharges").invoke(state);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void setAnchorCharges(Object state, int charges) {
        try {
            state.getClass().getMethod("setCharges", int.class).invoke(state, charges);
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
    }

    public static byte[] serializeBlockState(BlockState state) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            if (state instanceof Container c) {
                Inventory inv = c.getSnapshotInventory();
                int size = inv.getSize();
                out.writeInt(size);
                for (int i = 0; i < size; i++) {
                    out.writeObject(inv.getItem(i));
                }
            } else if (state instanceof org.bukkit.block.Sign s) {
                out.writeInt(0x5349474E); // "SIGN"
                out.writeUTF(s.getLine(0));
                out.writeUTF(s.getLine(1));
                out.writeUTF(s.getLine(2));
                out.writeUTF(s.getLine(3));
            } else if (state instanceof org.bukkit.block.Skull sk) {
                out.writeInt(0x534B554C); // "SKUL"
                var profile = sk.getOwnerProfile();
                if (profile != null && profile.getName() != null) {
                    out.writeUTF(profile.getName());
                } else {
                    out.writeUTF("");
                }
            } else if (isRespawnAnchor(state)) {
                out.writeInt(0x52455341); // "RESA"
                out.writeInt(getAnchorCharges(state));
            } else {
                out.writeInt(0);
            }
        } catch (Exception e) {
            return new byte[0];
        }
        return bos.toByteArray();
    }

    public static void applyBlockState(BlockState state, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int header = in.readInt();
            if (header == 0x5349474E) { // SIGN
                if (state instanceof org.bukkit.block.Sign s) {
                    s.setLine(0, in.readUTF());
                    s.setLine(1, in.readUTF());
                    s.setLine(2, in.readUTF());
                    s.setLine(3, in.readUTF());
                }
            } else if (header == 0x534B554C) { // SKUL
                if (state instanceof org.bukkit.block.Skull sk) {
                    String name = in.readUTF();
                    var profile = org.bukkit.Bukkit.createProfile(name);
                    sk.setOwnerProfile(profile);
                }
            } else if (header == 0x52455341) { // RESA
                int charges = in.readInt();
                if (isRespawnAnchor(state)) setAnchorCharges(state, charges);
            } else if (state instanceof Container c) {
                Inventory inv = c.getSnapshotInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    try {
                        ItemStack item = (ItemStack) in.readObject();
                        inv.setItem(i, item);
                    } catch (Exception e) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
    }
}
