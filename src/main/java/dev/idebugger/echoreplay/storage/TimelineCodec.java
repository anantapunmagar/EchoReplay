package dev.idebugger.echoreplay.storage;

import dev.idebugger.echoreplay.model.*;
import dev.idebugger.echoreplay.util.Io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encodes/decodes TimelineEvent records to binary body bytes (excluding the
 * type id, which is derived from the record class). Used by the writer to
 * serialize and the reader/engine to deserialize.
 */
public final class TimelineCodec {

    private TimelineCodec() {}

    public static int typeId(TimelineEvent e) {
        return switch (e) {
            case TimelineEvent.KeepAlive ignored -> RecordingFormat.EV_KEEP_ALIVE;
            case TimelineEvent.BlockSet ignored -> RecordingFormat.EV_BLOCK_SET;
            case TimelineEvent.BlockBreakAnim ignored -> RecordingFormat.EV_BLOCK_BREAK_ANIM;
            case TimelineEvent.MultiBlock ignored -> RecordingFormat.EV_MULTI_BLOCK;
            case TimelineEvent.PlayerSpawn ignored -> RecordingFormat.EV_PLAYER_SPAWN;
            case TimelineEvent.PlayerLeave ignored -> RecordingFormat.EV_PLAYER_LEAVE;
            case TimelineEvent.EntitySpawn ignored -> RecordingFormat.EV_ENTITY_SPAWN;
            case TimelineEvent.EntityLeave ignored -> RecordingFormat.EV_ENTITY_LEAVE;
            case TimelineEvent.Move ignored -> RecordingFormat.EV_MOVE;
            case TimelineEvent.Velocity ignored -> RecordingFormat.EV_VELOCITY;
            case TimelineEvent.Animation ignored -> RecordingFormat.EV_ANIMATION;
            case TimelineEvent.Metadata ignored -> RecordingFormat.EV_METADATA;
            case TimelineEvent.Equipment ignored -> RecordingFormat.EV_EQUIPMENT;
            case TimelineEvent.Pose ignored -> RecordingFormat.EV_POSE;
            case TimelineEvent.Damage ignored -> RecordingFormat.EV_DAMAGE;
            case TimelineEvent.Death ignored -> RecordingFormat.EV_DEATH;
            case TimelineEvent.SneakSprint ignored -> RecordingFormat.EV_SNEAK_SPRINT;
            case TimelineEvent.Mount ignored -> RecordingFormat.EV_MOUNT;
            case TimelineEvent.Sound ignored -> RecordingFormat.EV_SOUND;
            case TimelineEvent.Particle ignored -> RecordingFormat.EV_PARTICLE;
            case TimelineEvent.Chat ignored -> RecordingFormat.EV_CHAT;
            case TimelineEvent.WorldTime ignored -> RecordingFormat.EV_WORLD_TIME;
            case TimelineEvent.Weather ignored -> RecordingFormat.EV_WEATHER;
            case TimelineEvent.Explosion ignored -> RecordingFormat.EV_EXPLOSION;
            case TimelineEvent.ItemUse ignored -> RecordingFormat.EV_ITEM_USE;
            case TimelineEvent.Teleport ignored -> RecordingFormat.EV_TELEPORT;
            case TimelineEvent.Effect ignored -> RecordingFormat.EV_EFFECT;
            case TimelineEvent.CustomName ignored -> RecordingFormat.EV_CUSTOM_NAME;
            case TimelineEvent.Marker ignored -> RecordingFormat.EV_MARKER;
            case TimelineEvent.EntityStatus ignored -> RecordingFormat.EV_ENTITY_STATUS;
            case TimelineEvent.PlayerVitals ignored -> RecordingFormat.EV_PLAYER_VITALS;
            case TimelineEvent.PlayerInventory ignored -> RecordingFormat.EV_PLAYER_INVENTORY;
            case TimelineEvent.GameMode ignored -> RecordingFormat.EV_PLAYER_GAMEMODE;
            case TimelineEvent.HeldSlot ignored -> RecordingFormat.EV_PLAYER_HELD_SLOT;
        };
    }

    public static byte[] encodeBody(TimelineEvent e, List<String> palette) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut out = Io.leOut(bos);
        switch (e) {
            case TimelineEvent.KeepAlive ignored -> {}
            case TimelineEvent.BlockSet b -> {
                int pi = b.paletteIndex();
                out.writeShort(b.pos().x());
                out.writeShort(b.pos().y());
                out.writeShort(b.pos().z());
                out.writeInt(pi);
                out.writeInt(b.nbt() == null ? 0 : b.nbt().length);
                if (b.nbt() != null) out.write(b.nbt());
            }
            case TimelineEvent.BlockBreakAnim b -> {
                out.writeShort(b.pos().x());
                out.writeShort(b.pos().y());
                out.writeShort(b.pos().z());
                out.writeInt(b.breakerNpcId());
                out.writeByte(b.stage());
            }
            case TimelineEvent.MultiBlock m -> {
                out.writeInt(m.blocks().size());
                for (TimelineEvent.BlockSet b : m.blocks()) {
                    out.writeShort(b.pos().x());
                    out.writeShort(b.pos().y());
                    out.writeShort(b.pos().z());
                    out.writeInt(b.paletteIndex());
                    out.writeInt(b.nbt() == null ? 0 : b.nbt().length);
                    if (b.nbt() != null) out.write(b.nbt());
                }
            }
            case TimelineEvent.PlayerSpawn p -> {
                out.writeInt(p.npcId());
                out.writeLong(p.uuid().getMostSignificantBits());
                out.writeLong(p.uuid().getLeastSignificantBits());
                Io.writeUtf(out, p.name());
                var skin = p.skin();
                Io.writeUtf(out, skin == null || skin.value() == null ? "" : skin.value());
                Io.writeUtf(out, skin == null || skin.signature() == null ? "" : skin.signature());
                out.writeDouble(p.pos().x());
                out.writeDouble(p.pos().y());
                out.writeDouble(p.pos().z());
                out.writeFloat(p.rot().pitch());
                out.writeFloat(p.rot().yaw());
                out.writeFloat(p.rot().headYaw());
                out.writeInt(p.equipment() == null ? 0 : p.equipment().size());
                if (p.equipment() != null) {
                    for (byte[] eq : p.equipment()) {
                        out.writeInt(eq.length);
                        out.write(eq);
                    }
                }
                out.writeInt(p.metadata() == null ? 0 : p.metadata().length);
                if (p.metadata() != null) out.write(p.metadata());
            }
            case TimelineEvent.PlayerLeave p -> {
                out.writeInt(p.npcId());
                out.writeByte(p.reason());
            }
            case TimelineEvent.EntitySpawn s -> {
                out.writeInt(s.npcId());
                out.writeLong(s.uuid().getMostSignificantBits());
                out.writeLong(s.uuid().getLeastSignificantBits());
                Io.writeUtf(out, s.typeKey());
                out.writeDouble(s.pos().x());
                out.writeDouble(s.pos().y());
                out.writeDouble(s.pos().z());
                out.writeFloat(s.rot().pitch());
                out.writeFloat(s.rot().yaw());
                out.writeFloat(s.rot().headYaw());
                out.writeInt(s.metadata() == null ? 0 : s.metadata().length);
                if (s.metadata() != null) out.write(s.metadata());
            }
            case TimelineEvent.EntityLeave l -> out.writeInt(l.npcId());
            case TimelineEvent.Move m -> {
                out.writeInt(m.npcId());
                out.writeDouble(m.pos().x());
                out.writeDouble(m.pos().y());
                out.writeDouble(m.pos().z());
                out.writeFloat(m.rot().pitch());
                out.writeFloat(m.rot().yaw());
                out.writeFloat(m.rot().headYaw());
                out.writeBoolean(m.onGround());
            }
            case TimelineEvent.Velocity v -> {
                out.writeInt(v.npcId());
                out.writeDouble(v.vel().x());
                out.writeDouble(v.vel().y());
                out.writeDouble(v.vel().z());
            }
            case TimelineEvent.Animation a -> {
                out.writeInt(a.npcId());
                out.writeInt(a.anim());
            }
            case TimelineEvent.Metadata m -> {
                out.writeInt(m.npcId());
                out.writeInt(m.raw().length);
                out.write(m.raw());
            }
            case TimelineEvent.Equipment eq -> {
                out.writeInt(eq.npcId());
                out.writeInt(eq.slot());
                out.writeInt(eq.item().length);
                out.write(eq.item());
            }
            case TimelineEvent.Pose p -> {
                out.writeInt(p.npcId());
                out.writeInt(p.pose());
            }
            case TimelineEvent.Damage d -> {
                out.writeInt(d.npcId());
                Io.writeUtf(out, d.source());
                out.writeDouble(d.amount());
                out.writeInt(d.animation());
            }
            case TimelineEvent.Death d -> out.writeInt(d.npcId());
            case TimelineEvent.SneakSprint s -> {
                out.writeInt(s.npcId());
                out.writeInt(s.flags());
            }
            case TimelineEvent.Mount m -> {
                out.writeInt(m.npcId());
                out.writeInt(m.vehicleNpcId());
            }
            case TimelineEvent.Sound s -> {
                Io.writeUtf(out, s.key());
                Io.writeUtf(out, s.category());
                out.writeDouble(s.pos().x());
                out.writeDouble(s.pos().y());
                out.writeDouble(s.pos().z());
                out.writeFloat(s.volume());
                out.writeFloat(s.pitch());
            }
            case TimelineEvent.Particle p -> {
                Io.writeUtf(out, p.particleKey());
                out.writeDouble(p.pos().x());
                out.writeDouble(p.pos().y());
                out.writeDouble(p.pos().z());
                out.writeFloat(p.dx());
                out.writeFloat(p.dy());
                out.writeFloat(p.dz());
                out.writeFloat(p.speed());
                out.writeInt(p.count());
            }
            case TimelineEvent.Chat c -> {
                out.writeInt(c.npcId());
                Io.writeUtf(out, c.json());
            }
            case TimelineEvent.WorldTime w -> {
                out.writeLong(w.time());
                out.writeBoolean(w.cycling());
            }
            case TimelineEvent.Weather w -> {
                out.writeInt(w.rainStrength());
                out.writeInt(w.thunderStrength());
            }
            case TimelineEvent.Explosion x -> {
                out.writeDouble(x.pos().x());
                out.writeDouble(x.pos().y());
                out.writeDouble(x.pos().z());
                out.writeFloat(x.power());
            }
            case TimelineEvent.ItemUse u -> {
                out.writeInt(u.npcId());
                out.writeInt(u.hand());
                out.writeBoolean(u.started());
            }
            case TimelineEvent.Teleport t -> {
                out.writeInt(t.npcId());
                out.writeDouble(t.pos().x());
                out.writeDouble(t.pos().y());
                out.writeDouble(t.pos().z());
                out.writeFloat(t.rot().pitch());
                out.writeFloat(t.rot().yaw());
                out.writeFloat(t.rot().headYaw());
            }
            case TimelineEvent.Effect ef -> {
                out.writeInt(ef.npcId());
                out.writeBoolean(ef.add());
                Io.writeUtf(out, ef.effectKey());
                out.writeInt(ef.data() == null ? 0 : ef.data().length);
                if (ef.data() != null) out.write(ef.data());
            }
            case TimelineEvent.CustomName n -> {
                out.writeInt(n.npcId());
                Io.writeUtf(out, n.componentJson());
            }
            case TimelineEvent.Marker m -> Io.writeUtf(out, m.name());
            case TimelineEvent.EntityStatus s -> {
                out.writeInt(s.npcId());
                out.writeByte(s.status());
            }
            case TimelineEvent.PlayerVitals v -> {
                out.writeInt(v.npcId());
                out.writeFloat(v.health());
                out.writeByte(v.foodLevel());
                out.writeFloat(v.saturation());
            }
            case TimelineEvent.PlayerInventory inv -> {
                out.writeInt(inv.npcId());
                out.writeShort(inv.slots().length);
                for (byte[] slot : inv.slots()) {
                    int len = slot == null ? 0 : slot.length;
                    out.writeInt(len);
                    if (len > 0) out.write(slot);
                }
            }
            case TimelineEvent.GameMode g -> {
                out.writeInt(g.npcId());
                out.writeByte(g.mode());
            }
            case TimelineEvent.HeldSlot h -> {
                out.writeInt(h.npcId());
                out.writeByte(h.slot());
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    public static TimelineEvent decode(byte[] body, int typeId, long tickMillis, List<String> palette) throws IOException {
        Io.LeIn in = Io.leIn(body);
        return switch (typeId) {
            case RecordingFormat.EV_KEEP_ALIVE -> new TimelineEvent.KeepAlive(tickMillis);
            case RecordingFormat.EV_BLOCK_SET -> {
                int x = in.readShort();
                int y = in.readShort();
                int z = in.readShort();
                int pi = in.readInt();
                int nb = in.readInt();
                byte[] nbt = nb > 0 ? new byte[nb] : null;
                if (nbt != null) in.readFully(nbt);
                yield new TimelineEvent.BlockSet(tickMillis, new BlockPos(x, y, z), pi, nbt);
            }
            case RecordingFormat.EV_BLOCK_BREAK_ANIM -> {
                int x = in.readShort();
                int y = in.readShort();
                int z = in.readShort();
                int id = in.readInt();
                int stage = in.readByte() & 0xFF;
                yield new TimelineEvent.BlockBreakAnim(tickMillis, new BlockPos(x, y, z), id, stage);
            }
            case RecordingFormat.EV_MULTI_BLOCK -> {
                int n = in.readInt();
                List<TimelineEvent.BlockSet> blocks = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    int x = in.readShort();
                    int y = in.readShort();
                    int z = in.readShort();
                    int pi = in.readInt();
                    int nb = in.readInt();
                    byte[] nbt = nb > 0 ? new byte[nb] : null;
                    if (nbt != null) in.readFully(nbt);
                    blocks.add(new TimelineEvent.BlockSet(tickMillis, new BlockPos(x, y, z), pi, nbt));
                }
                yield new TimelineEvent.MultiBlock(tickMillis, blocks);
            }
            case RecordingFormat.EV_PLAYER_SPAWN -> {
                int npc = in.readInt();
                UUID uuid = new UUID(in.readLong(), in.readLong());
                String name = Io.readUtf(in);
                String val = Io.readUtf(in);
                String sig = Io.readUtf(in);
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float pitch = in.readFloat();
                float yaw = in.readFloat();
                float head = in.readFloat();
                int eqN = in.readInt();
                List<byte[]> eq = new ArrayList<>(eqN);
                for (int i = 0; i < eqN; i++) {
                    int el = in.readInt();
                    byte[] b = new byte[el];
                    in.readFully(b);
                    eq.add(b);
                }
                int mdL = in.readInt();
                byte[] md = mdL > 0 ? new byte[mdL] : null;
                if (md != null) in.readFully(md);
                yield new TimelineEvent.PlayerSpawn(tickMillis, npc, uuid, name,
                        new PlayerSkin(val.isEmpty() ? null : val, sig.isEmpty() ? null : sig),
                        new Vec3d(x, y, z), new Rotation(pitch, yaw, head), eq, md);
            }
            case RecordingFormat.EV_PLAYER_LEAVE -> {
                int npc = in.readInt();
                int reason = in.readByte();
                yield new TimelineEvent.PlayerLeave(tickMillis, npc, reason);
            }
            case RecordingFormat.EV_ENTITY_SPAWN -> {
                int npc = in.readInt();
                UUID uuid = new UUID(in.readLong(), in.readLong());
                String type = Io.readUtf(in);
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float pitch = in.readFloat();
                float yaw = in.readFloat();
                float head = in.readFloat();
                int mdL = in.readInt();
                byte[] md = mdL > 0 ? new byte[mdL] : null;
                if (md != null) in.readFully(md);
                yield new TimelineEvent.EntitySpawn(tickMillis, npc, uuid, type,
                        new Vec3d(x, y, z), new Rotation(pitch, yaw, head), md);
            }
            case RecordingFormat.EV_ENTITY_LEAVE -> new TimelineEvent.EntityLeave(tickMillis, in.readInt());
            case RecordingFormat.EV_MOVE -> {
                int npc = in.readInt();
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float pitch = in.readFloat();
                float yaw = in.readFloat();
                float head = in.readFloat();
                boolean og = in.readBoolean();
                yield new TimelineEvent.Move(tickMillis, npc, new Vec3d(x, y, z), new Rotation(pitch, yaw, head), og);
            }
            case RecordingFormat.EV_VELOCITY -> {
                int npc = in.readInt();
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                yield new TimelineEvent.Velocity(tickMillis, npc, new Vec3d(x, y, z));
            }
            case RecordingFormat.EV_ANIMATION -> new TimelineEvent.Animation(tickMillis, in.readInt(), in.readInt());
            case RecordingFormat.EV_METADATA -> {
                int npc = in.readInt();
                int l = in.readInt();
                byte[] raw = l > 0 ? new byte[l] : new byte[0];
                in.readFully(raw);
                yield new TimelineEvent.Metadata(tickMillis, npc, raw);
            }
            case RecordingFormat.EV_EQUIPMENT -> {
                int npc = in.readInt();
                int slot = in.readInt();
                int l = in.readInt();
                byte[] item = l > 0 ? new byte[l] : new byte[0];
                in.readFully(item);
                yield new TimelineEvent.Equipment(tickMillis, npc, slot, item);
            }
            case RecordingFormat.EV_POSE -> new TimelineEvent.Pose(tickMillis, in.readInt(), in.readInt());
            case RecordingFormat.EV_DAMAGE -> {
                int npc = in.readInt();
                String src = Io.readUtf(in);
                double amt = in.readDouble();
                int anim = in.readInt();
                yield new TimelineEvent.Damage(tickMillis, npc, src, amt, anim);
            }
            case RecordingFormat.EV_DEATH -> new TimelineEvent.Death(tickMillis, in.readInt());
            case RecordingFormat.EV_SNEAK_SPRINT -> new TimelineEvent.SneakSprint(tickMillis, in.readInt(), in.readInt());
            case RecordingFormat.EV_MOUNT -> new TimelineEvent.Mount(tickMillis, in.readInt(), in.readInt());
            case RecordingFormat.EV_SOUND -> {
                String key = Io.readUtf(in);
                String cat = Io.readUtf(in);
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float vol = in.readFloat();
                float pitch = in.readFloat();
                yield new TimelineEvent.Sound(tickMillis, key, cat, new Vec3d(x, y, z), vol, pitch);
            }
            case RecordingFormat.EV_PARTICLE -> {
                String key = Io.readUtf(in);
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float dx = in.readFloat();
                float dy = in.readFloat();
                float dz = in.readFloat();
                float speed = in.readFloat();
                int count = in.readInt();
                yield new TimelineEvent.Particle(tickMillis, key, new Vec3d(x, y, z), dx, dy, dz, speed, count);
            }
            case RecordingFormat.EV_CHAT -> {
                int npc = in.readInt();
                String json = Io.readUtf(in);
                yield new TimelineEvent.Chat(tickMillis, npc, json);
            }
            case RecordingFormat.EV_WORLD_TIME -> {
                long t = in.readLong();
                boolean cycling = in.readBoolean();
                yield new TimelineEvent.WorldTime(tickMillis, t, cycling);
            }
            case RecordingFormat.EV_WEATHER -> new TimelineEvent.Weather(tickMillis, in.readInt(), in.readInt());
            case RecordingFormat.EV_EXPLOSION -> {
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float power = in.readFloat();
                yield new TimelineEvent.Explosion(tickMillis, new Vec3d(x, y, z), power);
            }
            case RecordingFormat.EV_ITEM_USE -> {
                int npc = in.readInt();
                int hand = in.readInt();
                boolean started = in.readBoolean();
                yield new TimelineEvent.ItemUse(tickMillis, npc, hand, started);
            }
            case RecordingFormat.EV_TELEPORT -> {
                int npc = in.readInt();
                double x = in.readDouble();
                double y = in.readDouble();
                double z = in.readDouble();
                float pitch = in.readFloat();
                float yaw = in.readFloat();
                float head = in.readFloat();
                yield new TimelineEvent.Teleport(tickMillis, npc, new Vec3d(x, y, z), new Rotation(pitch, yaw, head));
            }
            case RecordingFormat.EV_EFFECT -> {
                int npc = in.readInt();
                boolean add = in.readBoolean();
                String key = Io.readUtf(in);
                int l = in.readInt();
                byte[] d = l > 0 ? new byte[l] : null;
                if (d != null) in.readFully(d);
                yield new TimelineEvent.Effect(tickMillis, npc, add, key, d);
            }
            case RecordingFormat.EV_CUSTOM_NAME -> {
                int npc = in.readInt();
                String json = Io.readUtf(in);
                yield new TimelineEvent.CustomName(tickMillis, npc, json);
            }
            case RecordingFormat.EV_MARKER -> new TimelineEvent.Marker(tickMillis, Io.readUtf(in));
            case RecordingFormat.EV_ENTITY_STATUS -> {
                int npc = in.readInt();
                byte st = (byte) in.readByte();
                yield new TimelineEvent.EntityStatus(tickMillis, npc, st);
            }
            case RecordingFormat.EV_PLAYER_VITALS -> {
                int npc = in.readInt();
                float health = in.readFloat();
                int food = in.readByte();
                float sat = in.readFloat();
                yield new TimelineEvent.PlayerVitals(tickMillis, npc, health, food, sat);
            }
            case RecordingFormat.EV_PLAYER_INVENTORY -> {
                int npc = in.readInt();
                int n = in.readUnsignedShort();
                byte[][] slots = new byte[Math.min(n, 256)][];
                for (int i = 0; i < slots.length; i++) {
                    int len = in.readInt();
                    slots[i] = len > 0 ? new byte[len] : new byte[0];
                    if (len > 0) in.readFully(slots[i]);
                }
                yield new TimelineEvent.PlayerInventory(tickMillis, npc, slots);
            }
            case RecordingFormat.EV_PLAYER_GAMEMODE -> {
                int npc = in.readInt();
                int mode = in.readByte() & 0xFF;
                yield new TimelineEvent.GameMode(tickMillis, npc, mode);
            }
            case RecordingFormat.EV_PLAYER_HELD_SLOT -> {
                int npc = in.readInt();
                int slot = in.readByte() & 0xFF;
                yield new TimelineEvent.HeldSlot(tickMillis, npc, slot);
            }
            default -> null;
        };
    }
}
