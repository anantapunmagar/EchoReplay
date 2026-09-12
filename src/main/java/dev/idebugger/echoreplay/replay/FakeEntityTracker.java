package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.Vec3d;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Allocates entity ids in a high, unused band and spawns/updates/destroys fake
 * entities (packet-only) for viewers. Packets are sent via PacketEvents to each
 * viewer's connection.
 */
public final class FakeEntityTracker {

    /**
     * Fake entity ids allocate downward from just below MAX_VALUE, far above
     * any id a real server assigns (those start at 1 and grow by 1). The band
     * is 1,000,000 ids wide; freed ids are recycled via {@link #forget(int)}
     * so long recordings with churn (respawns, projectiles, crystals) cannot
     * exhaust the band mid-replay.
     */
    public static final int ID_START = Integer.MAX_VALUE - 1_000_000;
    public static final int ID_BAND_SIZE = 1_000_000;
    public static final int ID_MIN = ID_START - ID_BAND_SIZE;
    private final AtomicInteger nextId = new AtomicInteger(ID_START);
    /** Recycled ids returned by {@link #forget(int)}, reused first. */
    private final java.util.Deque<Integer> freeIds = new java.util.ArrayDeque<>();

    /**
     * Fake tab-list UUID per runtime id. Each spawnPlayer creates a fresh
     * random UUID (to avoid client conflicts when the viewer is also the
     * actor); destroy() uses this map to send the matching REMOVE_PLAYER —
     * without it the fake lingers in viewers' tab lists until they relog.
     */
    private final Map<Integer, UUID> playerUuidByRuntime = new java.util.concurrent.ConcurrentHashMap<>();

    public FakeEntityTracker() {
    }

    /** Allocate an id, preferring recycled ids before consuming fresh band. */
    public synchronized int allocateId() {
        Integer reused = freeIds.pollFirst();
        if (reused != null) return reused;
        return nextId.getAndDecrement();
    }

    /**
     * Release an id back to the pool for reuse. Called when a fake entity is
     * destroyed; safe to call twice (second call is a no-op for move state,
     * but the id is only recycled once per destroy path — callers must call
     * once per allocate).
     */
    public synchronized void release(int runtimeId) {
        // Only recycle ids from our own band; never recycle real entity ids.
        if (runtimeId > ID_START || runtimeId < ID_MIN) return;
        if (!freeIds.contains(runtimeId)) freeIds.addLast(runtimeId);
    }

    /**
     * Last absolute position sent per fake runtime id. All viewers share the
     * same packet stream for a runtime, so one entry covers everyone (spawns
     * seed it, destroys clear it). Relative move packets interpolate on the
     * client while absolute snaps do not — moves go relative, teleports stay
     * absolute.
     */
    private final java.util.Map<Integer, double[]> lastPos = new java.util.HashMap<>();

    /** Drop all move state (playback stop / entity reset). */
    public void clear() {
        lastPos.clear();
        playerUuidByRuntime.clear();
    }

    /** Drop move state for one fake (called once per destroyFor, not per viewer). */
    public synchronized void forget(int runtimeId) {
        lastPos.remove(runtimeId);
        playerUuidByRuntime.remove(runtimeId);
        release(runtimeId);
    }

    private void seed(int runtimeId, Vec3d pos) {
        lastPos.put(runtimeId, new double[]{pos.x(), pos.y(), pos.z()});
    }

    /** True once the high id band is used up and no recycled ids remain. */
    public synchronized boolean isExhausted() {
        return freeIds.isEmpty() && nextId.get() < ID_MIN;
    }

    /** Number of recycled ids currently available for reuse (debug). */
    public synchronized int recycledAvailable() {
        return freeIds.size();
    }

    /** Human-readable allocator state for /er debug nms-style diagnostics. */
    public synchronized String describe() {
        return "ids next=" + nextId.get() + " recycled=" + freeIds.size()
                + " band=[" + ID_MIN + ".." + ID_START + "]"
                + (isExhausted() ? " EXHAUSTED" : "");
    }

    private static void send(Player viewer, com.github.retrooper.packetevents.wrapper.PacketWrapper<?> wrapper) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, wrapper);
    }

    public void spawnPlayer(Player viewer, int runtimeId, UUID uuid, String name, PlayerSkin skin,
                            Vec3d pos, Rotation rot) {
        // Use a fresh random UUID rather than the recorded player's UUID. This
        // avoids client-side conflicts when the viewer is also the actor (same
        // UUID already in the local tab list), which otherwise leaves the fake
        // player invisible / non-responsive to move packets.
        UUID fakeUuid = UUID.randomUUID();
        UserProfile profile = new UserProfile(fakeUuid, name);
        if (skin != null && skin.hasValue()) {
            String sig = (skin.signature() != null && !skin.signature().isEmpty()) ? skin.signature() : null;
            profile.setTextureProperties(java.util.List.of(new TextureProperty("textures", skin.value(),
                    sig == null ? "" : sig)));
        }
        WrapperPlayServerPlayerInfoUpdate info = new WrapperPlayServerPlayerInfoUpdate(
                WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, true, 0, GameMode.SURVIVAL, null, null));
        send(viewer, info);

        playerUuidByRuntime.put(runtimeId, fakeUuid);
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                runtimeId, fakeUuid, EntityTypes.PLAYER,
                new com.github.retrooper.packetevents.protocol.world.Location(pos.x(), pos.y(), pos.z(),
                        rot.yaw(), rot.pitch()),
                0f, 0, null);
        send(viewer, spawn);
        seed(runtimeId, pos);
    }

    public void spawnMob(Player viewer, int runtimeId, UUID uuid, EntityType type, Vec3d pos, Rotation rot) {
        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                runtimeId, uuid, type,
                new com.github.retrooper.packetevents.protocol.world.Location(pos.x(), pos.y(), pos.z(),
                        rot.yaw(), rot.pitch()),
                0f, 0, null);
        send(viewer, spawn);
        seed(runtimeId, pos);
    }

    public void destroy(Player viewer, int runtimeId) {
        send(viewer, new WrapperPlayServerDestroyEntities(runtimeId));
        // Also remove the fake tab entry that spawnPlayer created. Without
        // this, the fake player lingers in the viewer's tab list until they
        // relog. One REMOVE_PLAYER per viewer, per fake spawn — matches the
        // ADD_PLAYER sent at spawn time. (Mapping is dropped by forget(),
        // which runs once per fake — not here, since destroy() runs per viewer.)
        UUID fakeUuid = playerUuidByRuntime.get(runtimeId);
        if (fakeUuid != null) {
            send(viewer, new WrapperPlayServerPlayerInfoRemove(java.util.List.of(fakeUuid)));
        }
    }

    public void positionSync(Player viewer, int runtimeId, Vec3d pos, Rotation rot, boolean onGround) {
        EntityPositionData data = new EntityPositionData(
                new com.github.retrooper.packetevents.util.Vector3d(pos.x(), pos.y(), pos.z()),
                com.github.retrooper.packetevents.util.Vector3d.zero(),
                rot.yaw(), rot.pitch());
        send(viewer, new WrapperPlayServerEntityPositionSync(runtimeId, data, onGround));
        seed(runtimeId, pos);
    }

    /**
     * Smooth per-tick motion: small deltas go out as relative move packets
     * (the client interpolates these), large jumps as absolute teleports.
     * Deltas are quantized to the wire unit (1/4096 block) before advancing
     * state so sub-quantum jitter accumulates instead of drifting the render.
     */
    public void move(Player viewer, int runtimeId, Vec3d pos, float yaw, float pitch, boolean onGround) {
        double[] prev = lastPos.get(runtimeId);
        if (prev == null) {
            teleport(viewer, runtimeId, pos, yaw, pitch, onGround);
            return;
        }
        double dx = pos.x() - prev[0];
        double dy = pos.y() - prev[1];
        double dz = pos.z() - prev[2];
        if (dx == 0 && dy == 0 && dz == 0) {
            send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation(
                    runtimeId, yaw, pitch, onGround));
            return;
        }
        if (Math.abs(dx) >= 8 || Math.abs(dy) >= 8 || Math.abs(dz) >= 8) {
            teleport(viewer, runtimeId, pos, yaw, pitch, onGround);
            return;
        }
        long qx = Math.round(dx * 4096.0);
        long qy = Math.round(dy * 4096.0);
        long qz = Math.round(dz * 4096.0);
        if (qx == 0 && qy == 0 && qz == 0) return; // hold for accumulation
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation(
                runtimeId, qx / 4096.0, qy / 4096.0, qz / 4096.0, yaw, pitch, onGround));
        prev[0] += qx / 4096.0;
        prev[1] += qy / 4096.0;
        prev[2] += qz / 4096.0;
    }

    /** Absolute re-anchor (teleports, respawns, late-join syncs). Seeds move state. */
    public void teleport(Player viewer, int runtimeId, Vec3d pos, float yaw, float pitch, boolean onGround) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport(
                runtimeId,
                new com.github.retrooper.packetevents.protocol.world.Location(
                        pos.x(), pos.y(), pos.z(), yaw, pitch),
                onGround));
        seed(runtimeId, pos);
    }

    public void headLook(Player viewer, int runtimeId, float headYaw) {
        send(viewer, new WrapperPlayServerEntityHeadLook(runtimeId, headYaw));
    }

    /** Send an entity status/event (e.g. status 2 = hurt, 3 = death animation). */
    public void entityStatus(Player viewer, int runtimeId, int status) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus(
                runtimeId, status));
    }

    /** Send an entity-metadata patch (stance flags / pose / eye height) to a viewer. */
    public void setMetadata(Player viewer, int runtimeId,
                             java.util.List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> data) {
        if (data.isEmpty()) return;
        send(viewer, new WrapperPlayServerEntityMetadata(runtimeId, data));
    }

    public void velocity(Player viewer, int runtimeId, dev.idebugger.echoreplay.model.Vec3d vel) {
        send(viewer, new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity(
                runtimeId, new com.github.retrooper.packetevents.util.Vector3d(vel.x(), vel.y(), vel.z())));
    }
}
