package dev.idebugger.echoreplay.model;

import org.bukkit.entity.Entity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact, version-independent encoding of a small set of replay-critical
 * entity metadata values captured at spawn time on the recording server and
 * re-applied to fake entities during playback.
 *
 * Only the fields that CANNOT be inferred from the entity type alone are stored
 * (currently baby-age and slime/magma-cube size):
 *   - passive ageable mobs:     metadata index 15, INT var-int, negative = baby
 *   - zombie-family mobs:       metadata index 15, BYTE (1 = baby, 0 = adult)
 *   - slime / magma cube size:  metadata index 16, INT var-int, 1..127 = size
 *
 * The metadata TYPE (INT vs BYTE) matters: zombies crash the client if given an
 * INT at index 15, so each entry records which PacketEvents type to send.
 *
 * Blob layout (DataOutputStream):
 *   byte count, then count x (byte index, byte type, int value),
 *   type: 1 = INT, 2 = BYTE
 */
public final class RecordedMetadata {

    public static final int AGE_INDEX = 15;
    public static final int SLIME_SIZE_INDEX = 16;

    public static final int TYPE_INT = 1;
    public static final int TYPE_BYTE = 2;
    public static final int TYPE_ITEMSTACK = 3;
    public static final int TYPE_BOOLEAN = 4;

    // Firework indices - resolved reflectively, fallback to 8/10
    private static volatile int FIREWORK_ITEM_IDX = -1;
    private static volatile int FIREWORK_SHOT_ANGLE_IDX = -1;

    private static int fireworkItemIndex() {
        if (FIREWORK_ITEM_IDX != -1) return FIREWORK_ITEM_IDX;
        try {
            Class<?> c = Class.forName("net.minecraft.world.entity.projectile.FireworkRocketEntity");
            java.lang.reflect.Field f = c.getDeclaredField("DATA_ID_FIREWORKS_ITEM");
            Object acc = f.get(null);
            java.lang.reflect.Method m = acc.getClass().getMethod("id");
            FIREWORK_ITEM_IDX = (int) m.invoke(acc);
        } catch (Exception ex) {
            FIREWORK_ITEM_IDX = 8;
        }
        return FIREWORK_ITEM_IDX;
    }

    private static int fireworkShotAngleIndex() {
        if (FIREWORK_SHOT_ANGLE_IDX != -1) return FIREWORK_SHOT_ANGLE_IDX;
        try {
            Class<?> c = Class.forName("net.minecraft.world.entity.projectile.FireworkRocketEntity");
            java.lang.reflect.Field f = c.getDeclaredField("DATA_SHOT_AT_ANGLE");
            Object acc = f.get(null);
            java.lang.reflect.Method m = acc.getClass().getMethod("id");
            FIREWORK_SHOT_ANGLE_IDX = (int) m.invoke(acc);
        } catch (Exception ex) {
            FIREWORK_SHOT_ANGLE_IDX = 10;
        }
        return FIREWORK_SHOT_ANGLE_IDX;
    }

    private RecordedMetadata() {}

    /**
     * Captures baby-ness / slime-size / firework item+shotAtAngle from a live
     * entity, or {@code null} when none applies (keeps a normal adult spawn compact).
     */
    public static byte[] capture(Entity e) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        int count = 0;
        try {
            if (e instanceof org.bukkit.entity.Slime slime) {
                out.writeByte(SLIME_SIZE_INDEX);
                out.writeByte(TYPE_INT);
                out.writeInt(slime.getSize());
                count++;
            } else if (e instanceof org.bukkit.entity.Firework fw) {
                // Firework: item stack (colors/ball) and shot-at-angle (crossbow vs hand)
                byte[] itemBytes = dev.idebugger.echoreplay.record.EquipmentRecorder.serializeItem(fw.getItem());
                int itemIdx = fireworkItemIndex();
                out.writeByte(itemIdx);
                out.writeByte(TYPE_ITEMSTACK);
                out.writeInt(itemBytes.length);
                out.write(itemBytes);
                count++;
                int angleIdx = fireworkShotAngleIndex();
                out.writeByte(angleIdx);
                out.writeByte(TYPE_BOOLEAN);
                out.writeInt(fw.isShotAtAngle() ? 1 : 0);
                count++;
            } else if (e instanceof org.bukkit.entity.Ageable a && !a.isAdult()) {
                // Baby-ness: undead ageables (Zombie implements Ageable in
                // paper-api 1.21.4+) store it as a BYTE boolean at index 15 —
                // sending an INT there crashes the client. Other ageables use
                // the negative-INT age convention.
                boolean undead = e instanceof org.bukkit.entity.Zombie;
                out.writeByte(AGE_INDEX);
                out.writeByte(undead ? TYPE_BYTE : TYPE_INT);
                out.writeInt(undead ? 1 : -24000);
                count++;
            }
        } catch (IOException ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed IOException", ignored);
            return null;
        }
        if (count == 0) return null;
        byte[] body = bos.toByteArray();
        byte[] blob = new byte[body.length + 1];
        blob[0] = (byte) count;
        System.arraycopy(body, 0, blob, 1, body.length);
        return blob;
    }

    /** Returns the list of [index, type, value] metadata entries to apply (legacy int-only, for slime/zombie). */
    public static List<int[]> decode(byte[] blob) {
        List<int[]> out = new ArrayList<>();
        if (blob == null || blob.length < 1) return out;
        int count = blob[0] & 0xFF;
        int p = 1;
        for (int i = 0; i < count; i++) {
            if (p + 2 > blob.length) break;
            int index = blob[p] & 0xFF;
            int type = blob[p + 1] & 0xFF;
            if (type == TYPE_ITEMSTACK) {
                if (p + 6 > blob.length) break;
                int len = ((blob[p + 2] & 0xFF) << 24) | ((blob[p + 3] & 0xFF) << 16) | ((blob[p + 4] & 0xFF) << 8) | (blob[p + 5] & 0xFF);
                p += 6 + len;
                continue; // legacy decode skips itemstack
            }
            if (p + 6 > blob.length) break;
            int value = ((blob[p + 2] & 0xFF) << 24)
                    | ((blob[p + 3] & 0xFF) << 16)
                    | ((blob[p + 4] & 0xFF) << 8)
                    | (blob[p + 5] & 0xFF);
            out.add(new int[]{index, type, value});
            p += 6;
        }
        return out;
    }

    public record Entry(int index, int type, int intValue, byte[] itemBytes) {}

    /** Full decode that preserves ITEMSTACK entries. */
    public static List<Entry> decodeEntries(byte[] blob) {
        List<Entry> out = new ArrayList<>();
        if (blob == null || blob.length < 1) return out;
        int count = blob[0] & 0xFF;
        int p = 1;
        for (int i = 0; i < count; i++) {
            if (p + 2 > blob.length) break;
            int index = blob[p] & 0xFF;
            int type = blob[p + 1] & 0xFF;
            if (type == TYPE_ITEMSTACK) {
                if (p + 6 > blob.length) break;
                int len = ((blob[p + 2] & 0xFF) << 24) | ((blob[p + 3] & 0xFF) << 16) | ((blob[p + 4] & 0xFF) << 8) | (blob[p + 5] & 0xFF);
                if (p + 6 + len > blob.length) break;
                byte[] data = new byte[len];
                System.arraycopy(blob, p + 6, data, 0, len);
                out.add(new Entry(index, type, 0, data));
                p += 6 + len;
            } else {
                if (p + 6 > blob.length) break;
                int value = ((blob[p + 2] & 0xFF) << 24)
                        | ((blob[p + 3] & 0xFF) << 16)
                        | ((blob[p + 4] & 0xFF) << 8)
                        | (blob[p + 5] & 0xFF);
                out.add(new Entry(index, type, value, null));
                p += 6;
            }
        }
        return out;
    }
}