package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.GzipRecordingWriter;
import dev.idebugger.echoreplay.util.PalettedStorage;
import org.bukkit.World;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents one live recording. Freezes a cuboid, maintains an EventSink, and
 * tracks entity UUID -> stable npcId mapping for the duration of the take.
 *
 * <p>Crash safety: once the snapshot is complete the recording streams event
 * batches into a raw (non-gzip) checkpoint file
 * ({@code name.echoreplay.gz.partial}). If the server dies mid-recording the
 * plugin recovers that file on next start, losing at most one flush window.
 */
public final class RecordingSession {

    private final World world;
    private final Cuboid cuboid;
    private final String name;
    private final UUID recorderUuid;
    private final String recorderName;
    private final long startedMillis = System.currentTimeMillis();
    private final AtomicLong mediaClock = new AtomicLong(0);
    private final EventSink sink = new EventSink();
    private final AtomicInteger npcIdAlloc = new AtomicInteger(1);
    private final Map<UUID, Integer> idMap = new HashMap<>();
    // UUIDs that already had an EntitySpawn emitted (dedupes the event-listener
    // path against the per-tick recorder path so a given entity only ever gets
    // one spawn event).
    private final java.util.Set<UUID> entitySpawned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // global palette over the whole recording
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private final java.util.List<String> paletteList = new java.util.ArrayList<>();

    // last known player equipment before death, keyed by UUID
    private final Map<UUID, java.util.List<byte[]>> lastPlayerEquipment = new HashMap<>();

    // initial snapshot block storage (filled during SNAPSHOTTING on main thread)
    private PalettedStorage snapshotStorage;
    private final Map<String, byte[]> snapshotNbt = new java.util.LinkedHashMap<>();

    // relative positions of every state change are 16-bit; snapshot must fit.
    private volatile State state = State.SNAPSHOTTING;
    private final AtomicInteger sectionsDone = new AtomicInteger(0);
    private int totalSections;

    // ---- crash-safety checkpoint (raw, non-gzip section stream) ----
    private final Object checkpointLock = new Object();
    private File checkpointFile;
    private GzipRecordingWriter checkpointWriter;
    private java.io.OutputStream checkpointStream;
    // Events already written to the checkpoint; re-merged into the final file
    // on stop so the full timeline is contiguous.
    private final List<TimelineEvent> committedEvents = new ArrayList<>();
    // Palette size at the last checkpoint flush (checkpoints only rewrite the
    // palette when it grew — the file stays small).
    private int lastCheckpointPaletteSize = -1;
    // Rotated (sealed) checkpoint generations. When the live .partial exceeds
    // storage.checkpoint-rotate-mb it is sealed to .partial.rot<N> and a fresh
    // .partial is started — the live file stays bounded, recovery merges all
    // generations in order.
    private final List<File> rotatedCheckpoints = new ArrayList<>();
    private int checkpointGeneration = 0;

    // World time of the previous recording tick (for change detection).
    private long lastWorldTime = -1;
    // Last recorded storm state (for change detection; -1 = unknown).
    private int lastStorm = -1;

    public enum State { SNAPSHOTTING, RECORDING, FINALIZING, CANCELLED }

    public RecordingSession(World world, Cuboid cuboid, String name, UUID recorderUuid, String recorderName) {
        this.world = world;
        this.cuboid = cuboid;
        this.name = name;
        this.recorderUuid = recorderUuid;
        this.recorderName = recorderName;
        paletteIndex.put("minecraft:air", 0);
        paletteList.add("minecraft:air");
    }

    public World world() { return world; }
    public Cuboid cuboid() { return cuboid; }
    public String name() { return name; }
    public UUID recorderUuid() { return recorderUuid; }
    public String recorderName() { return recorderName; }
    public EventSink sink() { return sink; }
    public State state() { return state; }
    public long startedMillis() { return startedMillis; }
    public long mediaMillis() { return mediaClock.get(); }
    public int totalSections() { return totalSections; }
    public int sectionsDone() { return sectionsDone.get(); }
    /** P-9: depth of the unflushed event sink, for /er stats. -1 if session not active. */
    public int sinkDepth() { return sink.size(); }

    public void setTotalSections(int t) { this.totalSections = t; }

    public void setRecording() { this.state = State.RECORDING; }
    public void setFinalizing() { this.state = State.FINALIZING; }
    public void setCancelled() { this.state = State.CANCELLED; }
    public void markSection() { sectionsDone.incrementAndGet(); }

    /** Lazily initialize (on main thread) the snapshot storage for this take. */
    public synchronized PalettedStorage snapshotStorage() {
        if (snapshotStorage == null) {
            snapshotStorage = new PalettedStorage(cuboid.xSize(), cuboid.ySize(), cuboid.zSize());
        }
        return snapshotStorage;
    }

    public synchronized void putSnapshotNbt(BlockPos rel, byte[] nbt) {
        snapshotNbt.put(rel.x() + "," + rel.y() + "," + rel.z(), nbt);
    }

    public Map<String, byte[]> snapshotNbt() {
        return snapshotNbt;
    }

    /** Register or look up a stable npcId for a UUID. */
    public synchronized int npcIdFor(UUID uuid) {
        Integer id = idMap.get(uuid);
        if (id == null) {
            id = npcIdAlloc.getAndIncrement();
            idMap.put(uuid, id);
        }
        return id;
    }

    /**
     * Returns true when this is the first EntitySpawn emission for the uuid
     * across all recorders, so a single hot spawn produces exactly one spawn
     * event (and subsequent tick-recordings only emit moves).
     */
    public boolean markEntitySpawned(UUID uuid) {
        return entitySpawned.add(uuid);
    }

    public void unmarkEntitySpawned(UUID uuid) {
        entitySpawned.remove(uuid);
    }

    /** Clear the per-entity spawn bookkeeping when a fresh recording starts. */
    public void clearEntitySpawned() {
        entitySpawned.clear();
    }

    /**
     * Intern a block state string into the recording palette, returning the
     * palette index to reference in BlockSet events.
     */
    public synchronized int paletteIndex(String stateString) {
        Integer idx = paletteIndex.get(stateString);
        if (idx == null) {
            idx = paletteList.size();
            paletteIndex.put(stateString, idx);
            paletteList.add(stateString);
        }
        return idx;
    }

    public synchronized String paletteState(int index) {
        return paletteList.get(index);
    }

    public synchronized List<String> snapshotPalette() {
        return List.copyOf(paletteList);
    }

    /** Emit an event with current media time. */
    public void emit(TimelineEvent e) {
        sink.add(e);
    }

    public synchronized void cachePlayerEquipment(UUID uuid, java.util.List<byte[]> equipment) {
        lastPlayerEquipment.put(uuid, equipment);
    }

    public synchronized java.util.List<byte[]> takeCachedPlayerEquipment(UUID uuid) {
        java.util.List<byte[]> out = lastPlayerEquipment.remove(uuid);
        return out != null ? out : java.util.Collections.emptyList();
    }

    public void advanceClock(long deltaMs) {
        mediaClock.addAndGet(deltaMs);
    }

    /** Restore media time when resuming from an autosave/checkpoint. */
    public void restoreMediaClock(long ms) {
        mediaClock.set(Math.max(0, ms));
    }

    /** Replace the palette with recovered entries (resume path). */
    public synchronized void restorePalette(java.util.List<String> palette) {
        paletteIndex.clear();
        paletteList.clear();
        if (palette == null || palette.isEmpty()) {
            paletteIndex.put("minecraft:air", 0);
            paletteList.add("minecraft:air");
            return;
        }
        for (int i = 0; i < palette.size(); i++) {
            String s = palette.get(i) != null ? palette.get(i) : "minecraft:air";
            paletteIndex.putIfAbsent(s, paletteList.size());
            if (!paletteList.contains(s)) paletteList.add(s);
        }
        // Ensure indices match recovered order even with dupes.
        paletteIndex.clear();
        for (int i = 0; i < paletteList.size(); i++) paletteIndex.put(paletteList.get(i), i);
    }

    /**
     * Restore the initial snapshot grid from recovery (resume path). The
     * palette must already be restored so indices line up.
     */
    public synchronized void restoreSnapshot(int sx, int sy, int sz, int[] data,
                                             Map<String, byte[]> nbt) {
        snapshotStorage = new PalettedStorage(sx, sy, sz);
        // PalettedStorage.ensure("minecraft:air") runs in ctor; align palette.
        for (String s : paletteList) snapshotStorage.ensure(s);
        int[] raw = snapshotStorage.raw();
        if (data != null) System.arraycopy(data, 0, raw, 0, Math.min(data.length, raw.length));
        snapshotNbt.clear();
        if (nbt != null) snapshotNbt.putAll(nbt);
    }

    /** Emit a WorldTime event at most once per in-game second (world time
     *  ticks 20x/sec; per-tick events are pure filesize with no visible
     *  difference for time-of-day playback). */
    public void emitWorldTimeIfChanged() {
        long t = world().getFullTime();
        if (lastWorldTime < 0) {
            lastWorldTime = t;
            return;
        }
        if (t / 20 != lastWorldTime / 20) {
            lastWorldTime = t;
            emit(new TimelineEvent.WorldTime(mediaMillis(), t, !world().isFixedTime()));
        }
    }

    /** Emit a Weather event when the storm state changed (0/1 flags). */
    public void emitWeatherIfChanged() {
        boolean storm;
        boolean thunder;
        try {
            storm = world().hasStorm();
            thunder = world().isThundering();
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            return;
        }
        int code = (storm ? 1 : 0) | (thunder ? 2 : 0);
        if (code != lastStorm) {
            lastStorm = code;
            emit(new TimelineEvent.Weather(mediaMillis(), storm ? 1 : 0, thunder ? 1 : 0));
        }
    }

    // ---- checkpoint accessors (called from the IO thread) ----

    public File checkpointFile() {
        return checkpointFile;
    }

    public void setCheckpointFile(File f) {
        this.checkpointFile = f;
    }

    public Object checkpointLock() {
        return checkpointLock;
    }

    public void openCheckpointWriter(File f) {
        openCheckpointWriter(f, false);
    }

    /**
     * @param append true when resuming: continue the existing raw section
     *               stream instead of truncating it.
     */
    public void openCheckpointWriter(File f, boolean append) {
        synchronized (checkpointLock) {
            if (checkpointWriter != null) return;
            try {
                // Keep the stream OPEN across flushes (one continuous section
                // stream); closeCheckpointWriter finishes it. Never use
                // try-with-resources here — it would close the stream
                // immediately and every later flush would fail with
                // "Stream Closed".
                java.io.OutputStream fos = new FileOutputStream(f, append);
                // Appending raw sections needs no header rewrite: the reader is
                // lenient and concatenates sections. A fresh file still needs
                // its header, written by the caller (see startCheckpointAsync).
                // To keep resume simple we always append after the existing
                // header — recovery merges generations in order.
                checkpointStream = fos;
                if (append && f.exists() && f.length() > 0) {
                    // Raw mode writer writes its own 8-byte magic+flags header
                    // in ctor — for append we must NOT emit a second header.
                    // Use a headerless appender instead.
                    checkpointWriter = GzipRecordingWriter.appendRaw(fos);
                } else {
                    checkpointWriter = new GzipRecordingWriter(fos, false);
                }
            } catch (Exception e) {
                checkpointWriter = null;
                if (checkpointStream != null) {
                    try { checkpointStream.close(); } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
                    checkpointStream = null;
                }
                return;
            }
        }
    }

    /** @return the open checkpoint writer, or null when not (yet) open. */
    public GzipRecordingWriter checkpointWriter() {
        synchronized (checkpointLock) {
            return checkpointWriter;
        }
    }

    public void closeCheckpointWriter() {
        synchronized (checkpointLock) {
            if (checkpointWriter == null) return;
            try {
                checkpointWriter.close();
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
                // A failed close just leaves a shorter-but-still-parseable file.
            }
            checkpointWriter = null;
            if (checkpointStream != null) {
                try { checkpointStream.close(); } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
                checkpointStream = null;
            }
        }
    }

    /** Append a checkpointed batch (IO thread). */
    public synchronized void addCommitted(List<TimelineEvent> batch) {
        committedEvents.addAll(batch);
    }

    /** Take all checkpointed events for the final file (IO thread). */
    public synchronized List<TimelineEvent> takeCommitted() {
        List<TimelineEvent> out = new ArrayList<>(committedEvents);
        committedEvents.clear();
        return out;
    }

    /** Checkpointed (durable) event count — for /er stats. */
    public synchronized int committedSize() {
        return committedEvents.size();
    }

    public synchronized int lastCheckpointPaletteSize() {
        return lastCheckpointPaletteSize;
    }

    public synchronized void setLastCheckpointPaletteSize(int n) {
        lastCheckpointPaletteSize = n;
    }

    public synchronized int checkpointGeneration() {
        return checkpointGeneration;
    }

    public synchronized void noteRotatedCheckpoint(File sealed) {
        rotatedCheckpoints.add(sealed);
        checkpointGeneration++;
        // Fresh file needs a full palette header again.
        lastCheckpointPaletteSize = -1;
    }

    public synchronized List<File> rotatedCheckpoints() {
        return List.copyOf(rotatedCheckpoints);
    }
}
