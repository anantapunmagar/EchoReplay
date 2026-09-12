package dev.idebugger.echoreplay.record;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures server->client sound and particle packets that occur inside the
 * recording cuboid. These are pure protocol tricks - the server sends them
 * as packets only to nearby players, we store them as timeline events and
 * re-broadcast per-viewer during replay (including firework launch/blast).
 *
 * <p>Capture switches and the particle rate budget are read from config at
 * registration time (this listener runs on Netty threads, so no live config
 * lookups here); changing them requires a restart.
 */
public final class PacketOutRecorder extends PacketListenerAbstract {

    private final EchoReplay plugin;
    private final Map<String, Long> lastSound = new ConcurrentHashMap<>();
    private final Map<String, Long> lastParticle = new ConcurrentHashMap<>();
    private final boolean captureSounds;
    private final boolean captureParticles;
    private final int maxParticlesPerSecond;
    // Per-media-second particle budget. Media-seconds are used (not wall
    // time) so the budget matches what actually lands in the recording.
    private final AtomicInteger particleWindowCount = new AtomicInteger();
    private volatile long particleWindowSec = -1;

    public PacketOutRecorder(EchoReplay plugin) {
        super(PacketListenerPriority.MONITOR);
        this.plugin = plugin;
        this.captureSounds = plugin.cfg().getBoolean("recording.capture-sounds", true);
        this.captureParticles = plugin.cfg().getBoolean("recording.capture-particles", true);
        this.maxParticlesPerSecond = plugin.cfg().getInt("recording.max-particles-per-second", 400);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        if (!captureSounds && !captureParticles) return;
        if (event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT) {
            if (captureSounds) handleSound(event, s);
        } else if (event.getPacketType() == PacketType.Play.Server.PARTICLE) {
            if (captureParticles) handleParticle(event, s);
        }
    }

    /**
     * @return true when this particle fits within the per-media-second budget.
     * The window reset is racy across Netty threads by design: worst case a
     * few extra particles get through, which is invisible in playback.
     */
    private boolean withinParticleBudget(long mediaMs) {
        if (maxParticlesPerSecond <= 0) return true;
        long sec = mediaMs / 1000;
        if (sec != particleWindowSec) {
            particleWindowSec = sec;
            particleWindowCount.set(0);
        }
        return particleWindowCount.incrementAndGet() <= maxParticlesPerSecond;
    }

    private void handleSound(PacketSendEvent event, RecordingSession s) {
        try {
            var w = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect(event);
            var pos = w.getEffectPosition();
            int x = pos.x, y = pos.y, z = pos.z;
            if (!s.cuboid().contains(x, y, z)) return;
            String key = w.getSound().getSoundId().toString(); // e.g. minecraft:entity.firework_rocket.launch
            String cat = w.getSoundCategory().name();
            float vol = w.getVolume();
            float pitch = w.getPitch();
            long media = s.mediaMillis();
            String dedup = key + "@" + x + "," + y + "," + z;
            Long last = lastSound.get(dedup);
            if (last != null && last == media) return;
            lastSound.put(dedup, media);
            // opportunistic cleanup
            if (lastSound.size() > 1024) lastSound.clear();
            s.emit(new TimelineEvent.Sound(media, key, cat, new Vec3d(x, y, z), vol, pitch));
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
    }

    private void handleParticle(PacketSendEvent event, RecordingSession s) {
        try {
            var w = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle(event);
            var pos = w.getPosition();
            double x = pos.x, y = pos.y, z = pos.z;
            if (!s.cuboid().contains(x, y, z)) return;
            String key = w.getParticle().getType().getName().toString(); // e.g. minecraft:firework
            long media = s.mediaMillis();
            if (!withinParticleBudget(media)) return;
            var off = w.getOffset();
            float dx = off.x, dy = off.y, dz = off.z;
            float speed = w.getMaxSpeed();
            int count = w.getParticleCount();
            String dedup = key + "@" + (int) x + "," + (int) y + "," + (int) z + "#" + media;
            Long last = lastParticle.get(dedup);
            if (last != null && last == media) return;
            lastParticle.put(dedup, media);
            if (lastParticle.size() > 2048) lastParticle.clear();
            s.emit(new TimelineEvent.Particle(media, key, new Vec3d(x, y, z), dx, dy, dz, speed, count));
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
    }
}
