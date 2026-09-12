package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketEvents listener capturing player movement during recording.
 * Runs on Netty IO threads, so it touches NO Bukkit API here (player/world
 * lookups and locations are main-thread state): positions come purely from
 * packets, and region membership comes from the main-thread tick recorder.
 * Events are only emitted when the pose changed beyond epsilon, which cuts
 * the 20-40 packets/sec/client firehose down to genuine movement.
 *
 * <p>Head tracking: the recorded {@code yaw} is the smoothed BODY yaw (motion
 * direction, like a real client renders it) and {@code headYaw} is the
 * absolute look direction, so a strafe-turning player's head actually tracks
 * in the replay instead of being welded to the body.
 *
 * No-op when no recording is active.
 */
public final class MovementRecorder extends PacketListenerAbstract {

    // Sub-millimetre / sub-degree deltas are dropped (absolute values are
    // stored, so nothing can drift).
    private static final double POS_EPS = 1e-4;
    private static final float ROT_EPS = 0.1f;
    private static final double MOTION_EPS_SQ = 0.01 * 0.01; // 1cm
    // How quickly the body faces the motion direction (0..1).
    private static final float BODY_SMOOTH = 0.3f;

    private final EchoReplay plugin;
    private final Map<UUID, LastMove> lastSent = new ConcurrentHashMap<>();

    private record LastMove(double x, double y, double z, float yaw, float pitch, float bodyYaw) {}

    public MovementRecorder(EchoReplay plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
    }

    /**
     * Main-thread position anchor (called from the per-tick recorder). 1.0.14
     * sourced rotation-only moves from the live server location; without an
     * anchor, look-around-while-standing-still packets are dropped for lack of
     * a position baseline and the recorded head freezes until the player moves.
     */
    public void seedIfAbsent(UUID uuid, double x, double y, double z, float yaw, float pitch) {
        if (uuid == null) return;
        lastSent.putIfAbsent(uuid, new LastMove(x, y, z, yaw, pitch, yaw));
    }

    private RecordingSession session() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) {
            if (!lastSent.isEmpty()) lastSent.clear();
            return null;
        }
        return s;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        RecordingSession s = session();
        if (s == null) return;
        User user = event.getUser();
        UUID uuid = user == null ? null : user.getUUID();
        if (uuid == null) return;
        // Region membership is maintained on the main thread (no Bukkit here).
        if (!plugin.recordingManager().entityTickRecorder().isInRegion(uuid)) return;
        // Privacy opt-outs are never recorded (belt-and-braces alongside the
        // region-set removal in EntityTickRecorder).
        try {
            if (plugin.privacy().isExempt(uuid)) return;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: privacy check failed", e);
        }

        var pt = event.getPacketType();
        boolean wantPos = pt == PacketType.Play.Client.PLAYER_POSITION
                || pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
        boolean wantRot = pt == PacketType.Play.Client.PLAYER_ROTATION
                || pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
        if (!wantPos && !wantRot) return;

        double x, y, z;
        float yaw, pitch;
        boolean onGround;
        try {
            LastMove prev = lastSent.get(uuid);
            if (pt == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation(event);
                var pos = w.getPosition();
                x = pos.x; y = pos.y; z = pos.z;
                yaw = w.getYaw();
                pitch = w.getPitch();
                onGround = w.isOnGround();
            } else if (pt == PacketType.Play.Client.PLAYER_POSITION) {
                var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition(event);
                var pos = w.getPosition();
                x = pos.x; y = pos.y; z = pos.z;
                // Head keeps its last look; body turns toward the motion.
                yaw = prev != null ? prev.yaw() : 0f;
                pitch = prev != null ? prev.pitch() : 0f;
                onGround = w.isOnGround();
            } else {
                var w = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation(event);
                yaw = w.getYaw();
                pitch = w.getPitch();
                onGround = w.isOnGround();
                if (prev == null) return; // no position baseline yet
                x = prev.x(); y = prev.y(); z = prev.z();
            }
        } catch (Exception e) {
            return; // never touch Bukkit from Netty; drop undecodable packets
        }

        LastMove prev = lastSent.get(uuid);
        float bodyYaw;
        if (prev == null) {
            bodyYaw = yaw;
        } else {
            // Body always resolves: toward motion when moving (strafe turns
            // the torso into the run direction), toward look when stationary
            // (vanilla standing turns). Every branch must set it — leaving a
            // stale value freezes the torso while the head keeps turning.
            double dx = x - prev.x(), dz = z - prev.z();
            if (dx * dx + dz * dz > MOTION_EPS_SQ) {
                // Minecraft yaw convention: 0 = +Z, 90 = -X.
                double motion = Math.toDegrees(Math.atan2(-dx, dz));
                bodyYaw = smoothYaw(prev.bodyYaw(), (float) motion, BODY_SMOOTH);
            } else {
                bodyYaw = yaw;
            }
        }

        if (prev != null
                && Math.abs(prev.x() - x) < POS_EPS
                && Math.abs(prev.y() - y) < POS_EPS
                && Math.abs(prev.z() - z) < POS_EPS
                && Math.abs(prev.yaw() - yaw) < ROT_EPS
                && Math.abs(prev.pitch() - pitch) < ROT_EPS
                && Math.abs(angleDiff(prev.bodyYaw(), bodyYaw)) < 0.5f) {
            return;
        }
        lastSent.put(uuid, new LastMove(x, y, z, yaw, pitch, bodyYaw));
        int npc = s.npcIdFor(uuid);
        s.emit(new TimelineEvent.Move(s.mediaMillis(), npc,
                new Vec3d(x, y, z),
                new Rotation(pitch, bodyYaw, yaw),
                onGround));
    }

    /** Wrap-aware lerp of a yaw angle. */
    private static float smoothYaw(float from, float to, float t) {
        float delta = ((to - from + 540f) % 360f) - 180f;
        return from + delta * t;
    }

    /** Smallest absolute difference between two yaw angles, in degrees. */
    private static float angleDiff(float a, float b) {
        return Math.abs(((a - b + 540f) % 360f) - 180f);
    }
}
