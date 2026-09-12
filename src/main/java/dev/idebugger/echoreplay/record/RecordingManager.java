package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.GzipRecordingReader;
import dev.idebugger.echoreplay.storage.GzipRecordingWriter;
import dev.idebugger.echoreplay.storage.MetaParser;
import dev.idebugger.echoreplay.storage.RecordingEntry;
import dev.idebugger.echoreplay.storage.TimelineCodec;
import dev.idebugger.echoreplay.util.NbtBytes;
import dev.idebugger.echoreplay.util.PalettedStorage;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Manages the single active recording: budgeted snapshotting, live event
 * buffering, crash-safety checkpoints, and async gzip finalization on stop.
 */
public final class RecordingManager {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-]{1,32}");
    private static final String PARTIAL_SUFFIX = ".partial";

    private final EchoReplay plugin;
    private RecordingSession session;
    private File recordingsDir;
    private int marginBlocks = 8;
    private int flushSeconds = 15;
    private int blocksPerTick = 8000;
    private boolean captureTime = true;
    private int maxDurationMinutes = 30;
    private int maxAgeDays = 0;
    private boolean recIndicator = true;
    /** Cap for high-volume events per second (0 = unlimited). See EventSink. */
    private int maxEventsPerSecond = 8000;
    /** Rotate the crash checkpoint once it exceeds this many MB (0 = never). */
    private int checkpointRotateMb = 256;
    /** Incremental autosave interval seconds (0 = off). IO thread only. */
    private int autosaveSeconds = 60;
    // Players currently shown the red REC bossbar (shown while inside the
    // recording cuboid, hidden on leave/stop).
    private final Map<java.util.UUID, org.bukkit.boss.BossBar> recBars = new java.util.HashMap<>();

    private Cuboid.Section[] pendingSections;
    private int pendingIndex = 0;
    private long flushCounterMs = 0;
    private long autosaveCounterMs = 0;
    private final EquipmentRecorder equipmentRecorder;
    private final EntityTickRecorder entityTickRecorder;
    private final RegionDiffRecorder regionDiffRecorder;

    public RecordingManager(EchoReplay plugin) {
        this.plugin = plugin;
        this.equipmentRecorder = new EquipmentRecorder(plugin);
        this.entityTickRecorder = new EntityTickRecorder(plugin);
        this.regionDiffRecorder = new RegionDiffRecorder(plugin);
    }

    public void onEnable(FileConfiguration config) {
        marginBlocks = config.getInt("recording.margin-blocks", 8);
        flushSeconds = config.getInt("recording.flush-seconds", 15);
        blocksPerTick = config.getInt("recording.snapshot.blocks-per-tick", 8000);
        captureTime = config.getBoolean("recording.capture-time", true);
        maxDurationMinutes = config.getInt("recording.max-duration-minutes", 30);
        maxAgeDays = config.getInt("storage.max-age-days", 0);
        recIndicator = config.getBoolean("recording.rec-indicator", true);
        maxEventsPerSecond = config.getInt("recording.max-events-per-second", 8000);
        checkpointRotateMb = config.getInt("storage.checkpoint-rotate-mb", 256);
        autosaveSeconds = config.getInt("recording.autosave-seconds", 60);
        recordingsDir = new File(plugin.getDataFolder(), config.getString("storage.directory", "recordings"));
        recordingsDir.mkdirs();
        regionDiffRecorder.configure(config.getInt("recording.scan-interval-ticks", 1));
        recoverCrashedCheckpoints();
        pruneOldRecordings();
    }

    public void registerListeners(EchoReplay p) {
        p.getServer().getPluginManager().registerEvents(new WorldRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ConnectionRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new EntityRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ActionRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new ChatRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new FireworkRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(new DamageRecorder(p), p);
        p.getServer().getPluginManager().registerEvents(equipmentRecorder, p);
        p.getServer().getPluginManager().registerEvents(p.privacy(), p);
    }

    public void onDisable() {
        if (session != null) {
            forceStopAndSave();
        }
        hideRecBars();
    }

    public RecordingSession activeSession() {
        return session;
    }

    public File recordingsDir() { return recordingsDir; }

    public EntityTickRecorder entityTickRecorder() { return entityTickRecorder; }

    public RegionDiffRecorder regionDiffRecorder() { return regionDiffRecorder; }

    public EquipmentRecorder equipmentRecorder() { return equipmentRecorder; }

    public int getMaxEventsPerSecond() { return maxEventsPerSecond; }
    public int getCheckpointRotateMb() { return checkpointRotateMb; }
    public int getAutosaveSeconds() { return autosaveSeconds; }
    public int getFlushSeconds() { return flushSeconds; }

    public void onTick() {
        if (session == null) {
            if (!recBars.isEmpty()) hideRecBars();
            return;
        }
        switch (session.state()) {
            case SNAPSHOTTING -> tickSnapshot();
            case RECORDING -> tickRecording();
            default -> {}
        }
        tickRecIndicator();
    }

    /**
     * Red REC bossbar for everyone inside the recording cuboid while a take
     * runs. Shown on enter, hidden on leave/stop. Disabled entirely with
     * recording.rec-indicator=false.
     */
    private void tickRecIndicator() {
        if (!recIndicator || session == null) {
            hideRecBars();
            return;
        }
        RecordingSession.State st = session.state();
        if (st != RecordingSession.State.SNAPSHOTTING && st != RecordingSession.State.RECORDING) {
            hideRecBars();
            return;
        }
        Cuboid cuboid = session.cuboid();
        World world = session.world();
        if (cuboid == null || world == null) {
            hideRecBars();
            return;
        }
        java.util.Set<java.util.UUID> inside = new java.util.HashSet<>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            try {
                // Privacy opt-outs never see the REC bar either.
                if (plugin.privacy().isExempt(p)) continue;
                if (!p.getWorld().getUID().equals(world.getUID())) continue;
                if (!cuboid.contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ())) continue;
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
                continue;
            }
            inside.add(p.getUniqueId());
            org.bukkit.boss.BossBar bar = recBars.get(p.getUniqueId());
            if (bar == null) {
                bar = org.bukkit.Bukkit.createBossBar(
                        org.bukkit.ChatColor.RED + "\u25CF REC",
                        org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SOLID);
                recBars.put(p.getUniqueId(), bar);
            }
            try {
                if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            }
        }
        java.util.Iterator<java.util.Map.Entry<java.util.UUID, org.bukkit.boss.BossBar>> it =
                recBars.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<java.util.UUID, org.bukkit.boss.BossBar> en = it.next();
            if (inside.contains(en.getKey())) continue;
            try {
                Player p = org.bukkit.Bukkit.getPlayer(en.getKey());
                if (p != null) en.getValue().removePlayer(p);
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            }
            it.remove();
        }
    }

    private void hideRecBars() {
        for (java.util.Map.Entry<java.util.UUID, org.bukkit.boss.BossBar> en : recBars.entrySet()) {
            try {
                Player p = org.bukkit.Bukkit.getPlayer(en.getKey());
                if (p != null) en.getValue().removePlayer(p);
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            }
        }
        recBars.clear();
    }

    /**
     * Delete recordings (plus leftover checkpoints) older than
     * storage.max-age-days. 0 = keep forever.
     */
    private void pruneOldRecordings() {
        if (maxAgeDays <= 0 || recordingsDir == null) return;
        File[] files = recordingsDir.listFiles((d, n) -> n.endsWith(".echoreplay.gz"));
        if (files == null || files.length == 0) return;
        long cutoff = System.currentTimeMillis() - (long) maxAgeDays * 24L * 3600L * 1000L;
        int n = 0;
        for (File f : files) {
            try {
                if (f.lastModified() >= cutoff) continue;
                String name = f.getName().substring(0, f.getName().length() - ".echoreplay.gz".length());
                new File(recordingsDir, f.getName() + PARTIAL_SUFFIX).delete();
                if (f.delete()) {
                    n++;
                    try {
                        plugin.recordingIndex().remove(name);
                    } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
                    }
                }
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            }
        }
        if (n > 0) plugin.getLogger().info("Pruned " + n + " recordings older than " + maxAgeDays + " days.");
    }

    private int snapCursor = 0;

    private void tickSnapshot() {
        Cuboid c = session.cuboid();
        int sx = c.xSize(), sy = c.ySize(), sz = c.zSize();
        long vol = (long) sx * sy * sz;
        if (vol <= 0) {
            finishSnapshotAndRecord();
            return;
        }
        if (snapCursor < 0 || (long) snapCursor >= vol) snapCursor = 0;
        // Time-boxed capture: the old fixed 8000-blocks/tick budget cost
        // ~200ms+ per tick (getBlockData + getState per block) and halved TPS
        // while snapshotting. blocksPerTick remains as a secondary cap.
        long deadline = System.nanoTime() + 10_000_000L;
        int budget = blocksPerTick;
        int count = 0;
        World world = session.world();
        PalettedStorage storage = session.snapshotStorage();
        int minX = c.min().x(), minY = c.min().y(), minZ = c.min().z();
        while ((long) snapCursor < vol && count < budget) {
            int i = snapCursor++;
            int dx = i % sx;
            int dz = (i / sx) % sz;
            int dy = i / (sx * sz);
            int x = minX + dx, y = minY + dy, z = minZ + dz;
            Block block = world.getBlockAt(x, y, z);
            String state = block.getBlockData() == null ? "minecraft:air" : block.getBlockData().getAsString(true);
            int pi = session.paletteIndex(state);
            storage.set(dx, dy, dz, pi);
            BlockState bs = block.getState();
            // D-5: use the BlockState-aware overload — catches BEACON, SPAWNER,
            // BANNER, BEEHIVE, CONDUIT, ITEM_FRAME, etc. via TileState check,
            // not just the explicit Material list.
            if (bs != null && Snapshotter.needsNbt(bs)) {
                byte[] nb = NbtBytes.serializeBlockState(bs);
                if (nb != null && nb.length > 0) {
                    session.putSnapshotNbt(BlockPos.of(dx, dy, dz), nb);
                }
            }
            count++;
            if ((count & 255) == 0 && System.nanoTime() >= deadline) break;
        }
        // Keep section progress monotonic for any status display.
        if (pendingSections != null && pendingSections.length > 0) {
            int target = (int) ((long) pendingSections.length * snapCursor / Math.max(1L, vol));
            while (pendingIndex < target && pendingIndex < pendingSections.length) {
                pendingIndex++;
                session.markSection();
            }
        }
        if ((long) snapCursor >= vol) {
            finishSnapshotAndRecord();
        }
    }

    private void finishSnapshotAndRecord() {
        entityTickRecorder.reset();
        session.clearEntitySpawned();
        regionDiffRecorder.reset(session);
        snapshotExistingEntities();
        session.setRecording();
        startCheckpointAsync(session);
    }

    private void snapshotExistingEntities() {
        RecordingSession s = session;
        if (s == null) return;
        Cuboid c = s.cuboid();
        World world = s.world();
        for (Player p : world.getPlayers()) {
            if (plugin.privacy().isExempt(p)) continue;
            if (p.getWorld().getUID().equals(world.getUID())
                    && c.contains(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) {
                int npc = s.npcIdFor(p.getUniqueId());
                s.emit(new TimelineEvent.PlayerSpawn(s.mediaMillis(), npc, p.getUniqueId(), p.getName(),
                        skin(p), pos(p), rot(p), equipment(p), null));
            }
        }
    }

    private static PlayerSkin skin(Player p) {
        try {
            com.github.retrooper.packetevents.protocol.player.User user =
                    com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(p);
            if (user != null) {
                var props = user.getProfile().getTextureProperties();
                for (var prop : props) {
                    if ("textures".equals(prop.getName())) {
                        return new PlayerSkin(prop.getValue(),
                                prop.getSignature() == null ? null : prop.getSignature());
                    }
                }
            }
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
        return new PlayerSkin(null, null);
    }

    private static Vec3d pos(Player p) {
        return new Vec3d(p.getLocation().x(), p.getLocation().y(), p.getLocation().z());
    }

    private static Rotation rot(Player p) {
        return new Rotation(p.getLocation().getPitch(), p.getLocation().getYaw(), p.getLocation().getYaw());
    }

    private static List<byte[]> equipment(Player p) {
        var eq = p.getInventory();
        return List.of(
                EquipmentRecorder.serializeItem(eq.getItemInMainHand()),
                EquipmentRecorder.serializeItem(eq.getItemInOffHand()),
                EquipmentRecorder.serializeItem(eq.getBoots()),
                EquipmentRecorder.serializeItem(eq.getLeggings()),
                EquipmentRecorder.serializeItem(eq.getChestplate()),
                EquipmentRecorder.serializeItem(eq.getHelmet())
        );
    }

    private void tickRecording() {
        session.advanceClock(50);
        equipmentRecorder.tick();
        entityTickRecorder.tick();
        if (captureTime) {
            session.emitWorldTimeIfChanged();
            session.emitWeatherIfChanged();
        }
        flushCounterMs += 50;
        if (flushCounterMs >= flushSeconds * 1000L) {
            flushCounterMs = 0;
            autoFlush();
        }
        if (autosaveSeconds > 0) {
            autosaveCounterMs += 50;
            if (autosaveCounterMs >= autosaveSeconds * 1000L) {
                autosaveCounterMs = 0;
                autoSave();
            }
        }
        if (session.mediaMillis() / 1000 > maxDurationMinutes * 60L) {
            // Auto-stop at max duration: notify the original recorder so they
            // see the same Saving... action-bar progress as a manual /er stop.
            java.util.UUID who = null;
            try {
                who = session.recorderUuid();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: recorder id unavailable", e);
            }
            stop(who);
        }
    }

    /**
     * Crash safety: move buffered events out of RAM into the raw checkpoint
     * file so a server death mid-recording loses at most one flush window.
     * Runs on the IO thread.
     */
    private void autoFlush() {
        RecordingSession s = session;
        if (s == null) return;
        plugin.ioExecutor().execute(() -> {
            if (s.state() == RecordingSession.State.RECORDING) {
                flushCheckpoint(s);
            }
        });
    }

    /**
     * Incremental autosave: fsyncs the checkpoint (via flushCheckpoint) and
     * writes a tiny {@code name.autosave.json} manifest so {@code /er resume}
     * can continue the take after a crash/restart.
     *
     * <p>Must run on the dedicated IO executor — never the main thread (would
     * stall ticks) and never a Netty event loop (blocking Netty disconnects
     * players and drops packets). The tick loop only schedules; all file IO
     * happens below.
     */
    private void autoSave() {
        RecordingSession s = session;
        if (s == null) return;
        plugin.ioExecutor().execute(() -> {
            if (s.state() != RecordingSession.State.RECORDING) return;
            try {
                flushCheckpoint(s);
                File manifest = new File(recordingsDir, s.name() + ".autosave.json");
                long buffered = s.sink().size();
                // Committed count is only mutated on the IO thread (here) and
                // read on stop — no extra sync needed beyond takeCommitted's.
                String json = "{\"name\":" + jsonStr(s.name())
                        + ",\"mediaMillis\":" + s.mediaMillis()
                        + ",\"paletteSize\":" + s.snapshotPalette().size()
                        + ",\"checkpoint\":\"" + s.checkpointFile().getName() + "\""
                        + ",\"buffered\":" + buffered
                        + ",\"at\":" + System.currentTimeMillis() + "}";
                java.nio.file.Files.writeString(manifest.toPath(), json,
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                plugin.getLogger().fine("Autosaved recording '" + s.name()
                        + "' at " + formatDuration(s.mediaMillis()));
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.FINE,
                        "EchoReplay: autosave failed for '" + s.name() + "'", e);
            }
        });
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Resume a recording from its checkpoint/autosave after a crash or restart.
     * Rebuilds palette, snapshot grid, media clock and committed events, then
     * reopens the checkpoint in append mode so history is preserved.
     *
     * @return null on success (session is live), else a MiniMessage error.
     */
    public String resume(org.bukkit.entity.Player player, String name) {
        if (session != null) {
            return "<red>Already recording '" + session.name() + "'. Use /er stop first.</red>";
        }
        if (!NAME.matcher(name).matches()) {
            return "<red>Name must match [a-zA-Z0-9_\\-]{1,32}.</red>";
        }
        File out = new File(recordingsDir, name + ".echoreplay.gz");
        if (out.exists()) {
            return "<red>A finished recording named '" + name + "' already exists.</red>";
        }
        File partial = new File(recordingsDir, name + ".echoreplay.gz" + PARTIAL_SUFFIX);
        if (!partial.exists()) {
            return "<red>No checkpoint to resume for '" + name + "'.</red>";
        }
        try {
            // Merge live + rotated generations, oldest first.
            List<File> gens = new ArrayList<>();
            gens.add(partial);
            File[] rots = recordingsDir.listFiles((d, n) -> n.startsWith(partial.getName() + ".rot"));
            if (rots != null) {
                java.util.Arrays.sort(rots, Comparator.comparing(File::getName));
                gens.addAll(java.util.Arrays.asList(rots));
            }
            List<TimelineEvent> events = new ArrayList<>();
            GzipRecordingReader first = null;
            for (File g : gens) {
                GzipRecordingReader r = GzipRecordingReader.readLenient(
                        new java.io.BufferedInputStream(new java.io.FileInputStream(g)));
                if (r == null || r.meta() == null) continue;
                if (first == null) first = r;
                else {
                    events.addAll(r.timeline());
                    r.releaseFragments();
                    continue;
                }
                events.addAll(r.timeline());
            }
            if (first == null || first.meta() == null) {
                return "<red>Checkpoint for '" + name + "' has no header — cannot resume.</red>";
            }
            MetaParser.Parsed meta = MetaParser.parse(first.meta());
            List<String> palette = first.palette() != null ? first.palette() : List.of("minecraft:air");
            int[] blockData = first.blockData() != null ? first.blockData() : new int[0];
            byte[] blockNbtBytes = first.blockNbt();
            int sx = first.blockSizeX(), sy = first.blockSizeY(), sz = first.blockSizeZ();
            first.releaseFragments();
            events.sort(Comparator.comparingLong(TimelineEvent::tickMillis));
            long duration = events.isEmpty() ? 0 : events.get(events.size() - 1).tickMillis();

            World world = plugin.getServer().getWorld(meta.worldUuid());
            if (world == null) world = plugin.getServer().getWorld(meta.worldName());
            if (world == null) {
                return "<red>World for '" + name + "' is not loaded.</red>";
            }
            if (plugin.replayManager().isPlayingIn(world.getUID(), meta.cuboid())) {
                return "<red>A replay is active in this region — stop it first.</red>";
            }
            RecordingSession s = new RecordingSession(world, meta.cuboid(), name,
                    player.getUniqueId(), player.getName());
            s.restorePalette(palette);
            s.restoreSnapshot(sx, sy, sz, blockData, decodeSnapNbt(blockNbtBytes));
            s.restoreMediaClock(duration);
            s.addCommitted(events);
            s.sink().setMaxEventsPerSecond(maxEventsPerSecond);
            s.setTotalSections(1);
            s.markSection();
            s.setRecording();
            // Pre-register existing rotations so a clean stop deletes them.
            if (rots != null) for (File r : rots) s.noteRotatedCheckpoint(r);
            s.setLastCheckpointPaletteSize(palette.size());
            session = s;
            pendingSections = new Cuboid.Section[0];
            pendingIndex = 0;
            flushCounterMs = 0;
            autosaveCounterMs = 0;
            entityTickRecorder.reset();
            regionDiffRecorder.reset(s);
            s.setCheckpointFile(partial);
            s.openCheckpointWriter(partial, true);
            if (s.checkpointWriter() == null) {
                session = null;
                return "<red>Could not reopen checkpoint for '" + name + "'.</red>";
            }
            plugin.getLogger().info("Resumed recording '" + name + "' at "
                    + formatDuration(duration) + " (" + events.size() + " events).");
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("Resume failed for '" + name + "': " + e);
            return "<red>Resume failed: " + e.getMessage() + "</red>";
        }
    }

    /** Decode packSnapNbt bytes back to the relative-pos map (resume path). */
    private static Map<String, byte[]> decodeSnapNbt(byte[] packed) {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        if (packed == null || packed.length < 16) return out;
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(packed))) {
            in.readInt(); // sx
            in.readInt(); // sy
            in.readInt(); // sz
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                int len = in.readInt();
                byte[] b = new byte[len];
                in.readFully(b);
                out.put(x + "," + y + "," + z, b);
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE,
                    "EchoReplay: snapshot NBT decode failed on resume", e);
        }
        return out;
    }

    private void flushCheckpoint(RecordingSession s) {
        List<TimelineEvent> batch = s.sink().drainAll();
        if (batch.isEmpty()) return;
        long dropped = s.sink().getDroppedEvents();
        if (dropped > 0) {
            plugin.getLogger().fine("EchoReplay: rate limiter dropped " + dropped
                    + " non-critical events for '" + s.name() + "' (max-events-per-second="
                    + s.sink().getMaxEventsPerSecond() + ")");
        }
        s.addCommitted(batch);
        GzipRecordingWriter w = s.checkpointWriter();
        if (w == null) return; // snapshot not complete yet — nothing to anchor
        synchronized (s.checkpointLock()) {
            try {
                // The palette only grows; checkpoints rewrite it only when it
                // grew since the last flush (last section wins on recovery).
                // Rewriting the full palette every flush is what made .partial
                // files many times bigger than the final recording.
                List<String> palette = s.snapshotPalette();
                if (palette.size() != s.lastCheckpointPaletteSize()) {
                    w.writePalette(palette);
                    s.setLastCheckpointPaletteSize(palette.size());
                }
                for (TimelineEvent ev : batch) {
                    // S-4: skip a single oversized event (>64KB u16 limit)
                    // instead of losing the whole batch to the outer catch.
                    try {
                        byte[] body = TimelineCodec.encodeBody(ev, palette);
                        w.appendTimelineEvent(ev.tickMillis(), body, (byte) TimelineCodec.typeId(ev));
                    } catch (IOException oversized) {
                        plugin.getLogger().warning("Skipping oversized event "
                                + ev.getClass().getSimpleName() + " at " + ev.tickMillis()
                                + "ms: " + oversized.getMessage());
                    }
                }
                w.flush();
            } catch (Exception e) {
                plugin.getLogger().warning("Checkpoint flush failed: " + e);
                return;
            }
        }
        rotateCheckpointIfNeeded(s);
    }

    /**
     * Seal the live checkpoint once it exceeds
     * {@code storage.checkpoint-rotate-mb} and start a fresh one, so a long
     * dynamic recording cannot grow a multi-GB .partial file. The sealed
     * generation is kept for crash recovery (merged in order) and deleted on
     * clean stop/cancel. Runs on the IO thread after each flush.
     */
    private void rotateCheckpointIfNeeded(RecordingSession s) {
        if (checkpointRotateMb <= 0) return;
        File live = s.checkpointFile();
        if (live == null || !live.exists()) return;
        long limit = (long) checkpointRotateMb * 1024L * 1024L;
        if (live.length() < limit) return;
        synchronized (s.checkpointLock()) {
            try {
                s.closeCheckpointWriter();
                int gen = s.checkpointGeneration() + 1;
                File sealed = new File(live.getParentFile(), live.getName() + ".rot" + gen);
                if (!live.renameTo(sealed)) {
                    // Could not seal — reopen live and keep going (bounded risk).
                    s.openCheckpointWriter(live);
                    return;
                }
                s.noteRotatedCheckpoint(sealed);
                plugin.getLogger().info("Rotated checkpoint '" + s.name() + "' to "
                        + sealed.getName() + " (" + (live.length() / 1024 / 1024) + " MB sealed).");
                // Fresh live file with the immutable header so it is
                // independently recoverable.
                s.setCheckpointFile(live);
                s.openCheckpointWriter(live);
                GzipRecordingWriter w = s.checkpointWriter();
                if (w != null) {
                    Cuboid c = s.cuboid();
                    w.writeMeta(plugin.getServer().getMinecraftVersion(), s.world().getUID(), s.world().getName(),
                            c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z(),
                            s.startedMillis(), s.recorderUuid(), s.recorderName(), 0L, s.name());
                    PalettedStorage st = s.snapshotStorage();
                    w.writeBlocks(st.sizeX(), st.sizeY(), st.sizeZ(), st.raw(), bitsFor(s.snapshotPalette().size()));
                    w.writeBlockNbt(packSnapNbt(st, s.snapshotNbt()));
                    w.writeEntities(new byte[0]);
                    w.writePalette(s.snapshotPalette());
                    s.setLastCheckpointPaletteSize(s.snapshotPalette().size());
                    w.flush();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Checkpoint rotation failed: " + e);
            }
        }
    }

    /** Open the checkpoint file and write the immutable header sections. */
    private void startCheckpointAsync(RecordingSession s) {
        plugin.ioExecutor().execute(() -> {
            try {
                File f = new File(recordingsDir, s.name() + ".echoreplay.gz" + PARTIAL_SUFFIX);
                s.setCheckpointFile(f);
                s.openCheckpointWriter(f);
                synchronized (s.checkpointLock()) {
                    GzipRecordingWriter w = s.checkpointWriter();
                    if (w == null) return;
                    Cuboid c = s.cuboid();
                    w.writeMeta(plugin.getServer().getMinecraftVersion(), s.world().getUID(), s.world().getName(),
                            c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z(),
                            s.startedMillis(), s.recorderUuid(), s.recorderName(), 0L, s.name());
                    PalettedStorage st = s.snapshotStorage();
                    w.writeBlocks(st.sizeX(), st.sizeY(), st.sizeZ(), st.raw(), bitsFor(s.snapshotPalette().size()));
                    w.writeBlockNbt(packSnapNbt(st, s.snapshotNbt()));
                    w.writeEntities(new byte[0]);
                    w.flush();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not open crash-safety checkpoint: " + e);
            }
        });
    }

    public String start(Player player, String name) {
        if (session != null) {
            return "<red>Already recording '" + session.name() + "'. Use /er stop first.</red>";
        }
        if (!NAME.matcher(name).matches()) {
            return "<red>Name must match [a-zA-Z0-9_\\-]{1,32}.</red>";
        }
        var selection = plugin.selectionManager().get(player);
        if (!selection.isComplete()) {
            return "<red>You need a complete selection (pos1 + pos2) first.</red>";
        }
        if (selection.world() == null || !selection.world().getUID().equals(player.getWorld().getUID())) {
            return "<red>Selection must be in your current world.</red>";
        }
        Cuboid cuboid = selection.cuboid();
        // Grow the recorded region so edge activity is not clipped.
        if (marginBlocks > 0) {
            World w = player.getWorld();
            int m = marginBlocks;
            int minY = Math.max(w.getMinHeight(), cuboid.min().y() - m);
            int maxY = Math.min(w.getMaxHeight() - 1, cuboid.max().y() + m);
            cuboid = new Cuboid(new BlockPos(cuboid.min().x() - m, minY, cuboid.min().z() - m),
                    new BlockPos(cuboid.max().x() + m, maxY, cuboid.max().z() + m));
        }
        long volume = cuboid.volume();
        int maxVolume = plugin.cfg().getInt("selection.max-volume", 300000);
        int maxSpan = plugin.cfg().getInt("selection.max-horizontal-span", 256);
        boolean bypass = player.hasPermission("echoreplay.bypass-limits");
        if (!bypass && volume > maxVolume) {
            return "<red>Selection volume " + volume + " exceeds limit " + maxVolume + ".</red>";
        }
        if (!bypass && (cuboid.xSize() > maxSpan || cuboid.zSize() > maxSpan)) {
            return "<red>Horizontal span exceeds " + maxSpan + ".</red>";
        }
        File out = new File(recordingsDir, name + ".echoreplay.gz");
        if (out.exists()) {
            return "<red>A recording named '" + name + "' already exists.</red>";
        }
        if (plugin.replayManager().isPlayingIn(player.getWorld().getUID(), cuboid)) {
            return "<red>A replay is active in this region — stop it first.</red>";
        }

        session = new RecordingSession(player.getWorld(), cuboid, name, player.getUniqueId(), player.getName());
        session.setTotalSections(cuboid.sections().size());
        session.sink().setMaxEventsPerSecond(maxEventsPerSecond);
        pendingSections = cuboid.sections().toArray(new Cuboid.Section[0]);
        pendingIndex = 0;
        snapCursor = 0;
        return null;
    }

    public String stop() {
        return stop(null);
    }

    /** Stop with save-progress action bars to one player (null = no UI). */
    public String stop(java.util.UUID notifyId) {
        if (session == null) return "<red>Nothing is recording.</red>";
        return finalizeAndSave(notifyId);
    }

    public String cancel() {
        if (session == null) return "<red>Nothing is recording.</red>";
        RecordingSession s = session;
        s.setCancelled();
        s.sink().close();
        regionDiffRecorder.stop();
        session = null;
        s.closeCheckpointWriter();
        File cp = s.checkpointFile();
        if (cp != null) cp.delete();
        for (File rot : s.rotatedCheckpoints()) {
            try { rot.delete(); } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.FINE,
                        "EchoReplay: could not delete rotated checkpoint " + rot.getName(), e);
            }
        }
        try {
            new File(recordingsDir, s.name() + ".autosave.json").delete();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.FINE, "EchoReplay: autosave cleanup failed", e);
        }
        return "<green>Cancelled recording '" + s.name() + "'.</green>";
    }

    public String forceStopAndSave() {
        if (session == null) return null;
        return finalizeAndSave(null);
    }

    private String finalizeAndSave() {
        return finalizeAndSave(null);
    }

    /**
     * Stop and save fully async: the main thread only freezes producers and
     * snapshots cheap state; draining, sorting, encoding and gzip all run on
     * the dedicated IO executor (never main, never Netty). Progress goes to
     * the notifying player's action bar (above the hotbar) as
     * {@code Saving... N%}, with the final result as chat + action bar.
     */
    private String finalizeAndSave(java.util.UUID notifyId) {
        RecordingSession s = session;
        if (s == null) return "<red>Nothing recording.</red>";
        s.setFinalizing();
        // Stop producers first so the event stream ends here.
        regionDiffRecorder.stop();
        s.sink().close();
        // Cheap main-thread captures only. Draining + sorting millions of
        // events on the tick thread froze the server for seconds (TPS 5 on
        // stop); that work now runs on the IO thread with the file write.
        final long fd = s.mediaMillis();
        final List<String> fpal = s.snapshotPalette();
        final PalettedStorage fs = s.snapshotStorage();
        final Map<String, byte[]> fnbt = s.snapshotNbt();
        final Cuboid fc = s.cuboid();
        final long fsTime = s.startedMillis();
        final String fname = s.name();
        final String fworldName = s.world().getName();
        final java.util.UUID fworldUuid = s.world().getUID();
        final UUID frecorderUuid = s.recorderUuid();
        final String frecorderName = s.recorderName();
        final int fsizeX = fs.sizeX(), fsizeY = fs.sizeY(), fsizeZ = fs.sizeZ();

        session = null;

        sendActionBar(notifyId, "<yellow>Saving '<white>" + fname + "</white>'... 0%</yellow>");
        plugin.ioExecutor().execute(() -> {
            File out = new File(recordingsDir, fname + ".echoreplay.gz");
            try {
                // Final timeline = checkpointed batches + whatever is still buffered.
                sendActionBar(notifyId, "<yellow>Saving '<white>" + fname + "</white>'... preparing</yellow>");
                List<TimelineEvent> fev = new ArrayList<>(s.takeCommitted());
                fev.addAll(s.sink().drainAll());
                fev.sort(Comparator.comparingLong(TimelineEvent::tickMillis));
                writeRecordingFileWithProgress(out, plugin.getServer().getMinecraftVersion(),
                        fworldUuid, fworldName, fc, fd, fsTime, frecorderUuid, frecorderName,
                        fname, fpal, fsizeX, fsizeY, fsizeZ, fs.raw(), packSnapNbt(fs, fnbt),
                        fev, notifyId);
                plugin.recordingIndex().put(new RecordingEntry(fname, fworldUuid, fworldName, fd, out.length(),
                        fsTime, fc.min().x(), fc.min().y(), fc.min().z(), fc.max().x(), fc.max().y(), fc.max().z()));
                pruneOldRecordings();
                boolean keep = plugin.cfg().getBoolean("storage.keep-partial-on-crash", false);
                s.closeCheckpointWriter();
                File cp = s.checkpointFile();
                if (!keep && cp != null) cp.delete();
                if (!keep) {
                    for (File rot : s.rotatedCheckpoints()) {
                        try { rot.delete(); } catch (Exception ex) {
                            plugin.getLogger().log(java.util.logging.Level.FINE,
                                    "EchoReplay: could not delete rotated checkpoint " + rot.getName(), ex);
                        }
                    }
                }
                try {
                    new File(recordingsDir, fname + ".autosave.json").delete();
                } catch (Exception ex) {
                    plugin.getLogger().log(java.util.logging.Level.FINE,
                            "EchoReplay: autosave cleanup failed", ex);
                }
                sendActionBar(notifyId, "<green>Saved '<white>" + fname + "</white>' ("
                        + RecordingManager.formatDuration(fd) + ")</green>");
                notifyChat(notifyId, "<green>Saved recording '" + fname + "' ("
                        + RecordingManager.formatDuration(fd) + ").</green>");
            } catch (IOException e) {
                // Keep the checkpoint as a safety net; it is recovered on next start.
                plugin.getLogger().warning("Failed to write recording '" + fname + "': " + e.getMessage()
                        + " — checkpoint kept for recovery on next start.");
                sendActionBar(notifyId, "<red>Save failed — checkpoint kept</red>");
                notifyChat(notifyId, "<red>Save failed for '" + fname + "': " + e.getMessage()
                        + " — checkpoint kept for recovery.</red>");
            }
        });
        return "<yellow>Saving '<white>" + fname + "</white>'... watch the bar above your hotbar.</yellow>";
    }

    /** Action bar (above the hotbar) update on the main thread; no-op when id null/offline. */
    private void sendActionBar(java.util.UUID id, String miniMessage) {
        if (id == null) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        p.sendActionBar(dev.idebugger.echoreplay.util.Text.mm(miniMessage));
                    }
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: action bar failed", e);
                }
            });
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: action bar schedule failed", e);
        }
    }

    /** Chat follow-up on the main thread; no-op when id null/offline. */
    private void notifyChat(java.util.UUID id, String miniMessage) {
        if (id == null) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        p.sendMessage(dev.idebugger.echoreplay.util.Text.mm(miniMessage));
                    }
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: notify chat failed", e);
                }
            });
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: notify schedule failed", e);
        }
    }

    /**
     * Write the final gzip recording via a temp file + atomic rename (with a
     * cross-device copy fallback that actually runs).
     */
    private void writeRecordingFile(File out, String serverVersion, UUID worldUuid, String worldName, Cuboid c,
                                    long duration, long epoch, UUID recUuid, String recName, String name,
                                    List<String> palette, int sizeX, int sizeY, int sizeZ, int[] blocks,
                                    byte[] blockNbt, List<TimelineEvent> events) throws IOException {
        writeRecordingFileWithProgress(out, serverVersion, worldUuid, worldName, c, duration, epoch,
                recUuid, recName, name, palette, sizeX, sizeY, sizeZ, blocks, blockNbt, events, null);
    }

    /**
     * Chunked variant of {@link #writeRecordingFile} with action-bar progress.
     * Encoding + gzip stay on the calling (IO) thread; only the tiny UI
     * updates hop to the main thread. Progress every ~5% and every 2000 events
     * so the bar moves on both huge and tiny saves without spamming packets.
     */
    private void writeRecordingFileWithProgress(File out, String serverVersion, UUID worldUuid, String worldName,
                                                Cuboid c, long duration, long epoch, UUID recUuid, String recName,
                                                String name, List<String> palette, int sizeX, int sizeY, int sizeZ,
                                                int[] blocks, byte[] blockNbt, List<TimelineEvent> events,
                                                java.util.UUID notifyId) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        int total = events.size();
        int step = Math.max(1, Math.min(2000, Math.max(1, total / 20)));
        int lastPct = -1;
        try (GzipRecordingWriter w = new GzipRecordingWriter(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            w.writeMeta(serverVersion, worldUuid, worldName,
                    c.min().x(), c.min().y(), c.min().z(), c.max().x(), c.max().y(), c.max().z(),
                    epoch, recUuid, recName, duration, name);
            w.writePalette(palette);
            w.writeBlocks(sizeX, sizeY, sizeZ, blocks, bitsFor(Math.max(1, palette.size())));
            w.writeBlockNbt(blockNbt);
            w.writeEntities(new byte[0]);
            if (total == 0) {
                sendActionBar(notifyId, "<yellow>Saving '<white>" + name + "</white>'... 100%</yellow>");
            }
            for (int i = 0; i < total; i++) {
                TimelineEvent ev = events.get(i);
                // S-4: skip the single oversized event (>64KB u16 limit)
                // instead of failing the entire recording. PlayerSpawn with
                // shulker-box-of-shulker-boxes (~2MB NBT) is the most common
                // culprit; GzipRecordingWriter throws for oversized bodies.
                try {
                    byte[] body = TimelineCodec.encodeBody(ev, palette);
                    w.appendTimelineEvent(ev.tickMillis(), body, (byte) TimelineCodec.typeId(ev));
                } catch (IOException oversized) {
                    plugin.getLogger().warning("Skipping oversized event "
                            + ev.getClass().getSimpleName() + " at " + ev.tickMillis()
                            + "ms in '" + name + "': " + oversized.getMessage());
                }
                if ((i + 1) % step == 0 || i + 1 == total) {
                    int pct = (int) ((i + 1) * 100L / Math.max(1, total));
                    if (pct != lastPct) {
                        lastPct = pct;
                        sendActionBar(notifyId, "<yellow>Saving '<white>" + name + "</white>'... "
                                + pct + "%</yellow>");
                    }
                    // Periodic flush bounds memory and makes the tmp file grow
                    // visibly; cheap relative to per-event encode cost.
                    if ((i + 1) % 5000 == 0) w.flush();
                }
            }
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            java.nio.file.Files.copy(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.delete();
    }

    private byte[] packSnapNbt(PalettedStorage storage, Map<String, byte[]> snapNbt) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bos)) {
            out.writeInt(storage.sizeX());
            out.writeInt(storage.sizeY());
            out.writeInt(storage.sizeZ());
            out.writeInt(snapNbt.size());
            for (Map.Entry<String, byte[]> e : snapNbt.entrySet()) {
                String[] parts = e.getKey().split(",");
                out.writeInt(Integer.parseInt(parts[0]));
                out.writeInt(Integer.parseInt(parts[1]));
                out.writeInt(Integer.parseInt(parts[2]));
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
        } catch (IOException ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed IOException", ignored);
        }
        return bos.toByteArray();
    }

    private static int bitsFor(int paletteSize) {
        int bits = 1;
        while ((1 << bits) < paletteSize && bits < 64) {
            bits++;
        }
        return bits;
    }

    /**
     * Crash recovery: on start, turn leftover checkpoint files into real
     * recordings (duration recomputed from the last surviving event), or clean
     * up stale ones whose final file already exists. Rotated generations
     * ({@code .partial.rot<N>}) are merged in order after the live
     * {@code .partial}. Runs on the IO thread.
     */
    public void recoverCrashedCheckpoints() {
        File dir = recordingsDir;
        File[] partials = dir.listFiles((d, n) -> n.endsWith(PARTIAL_SUFFIX));
        if (partials == null || partials.length == 0) return;
        plugin.ioExecutor().execute(() -> {
            for (File partial : partials) {
                File finalFile = new File(dir,
                        partial.getName().substring(0, partial.getName().length() - PARTIAL_SUFFIX.length()));
                try {
                    if (finalFile.exists()) {
                        partial.delete(); // final recording already saved — stale checkpoint
                        // Also drop stale rotations for this recording.
                        File[] stale = dir.listFiles((d, n) -> n.startsWith(partial.getName() + ".rot"));
                        if (stale != null) for (File s : stale) s.delete();
                        continue;
                    }
                    // Live + rotated generations, oldest first.
                    List<File> gens = new ArrayList<>();
                    gens.add(partial);
                    File[] rots = dir.listFiles((d, n) -> n.startsWith(partial.getName() + ".rot"));
                    if (rots != null) {
                        java.util.Arrays.sort(rots, Comparator.comparing(File::getName));
                        gens.addAll(java.util.Arrays.asList(rots));
                    }
                    List<TimelineEvent> events = new ArrayList<>();
                    GzipRecordingReader first = null;
                    for (File g : gens) {
                        try {
                            GzipRecordingReader r = GzipRecordingReader.readLenient(
                                    new BufferedInputStream(new FileInputStream(g)));
                            if (r == null || r.meta() == null) {
                                plugin.getLogger().warning("Checkpoint " + g.getName()
                                        + " has no complete header — skipping generation.");
                                continue;
                            }
                            if (first == null) first = r;
                            else {
                                // Later generations reuse the same snapshot; only
                                // their timeline tails matter.
                                events.addAll(r.timeline());
                                r.releaseFragments();
                                continue;
                            }
                            events.addAll(r.timeline());
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Checkpoint generation " + g.getName()
                                    + " unreadable, skipping: " + ex);
                        }
                    }
                    if (first == null || first.meta() == null) {
                        plugin.getLogger().warning("Checkpoint " + partial.getName()
                                + " has no complete header — keeping for manual recovery.");
                        continue;
                    }
                    GzipRecordingReader r = first;
                    MetaParser.Parsed meta = MetaParser.parse(r.meta());
                    r.releaseFragments();
                    events.sort(Comparator.comparingLong(TimelineEvent::tickMillis));
                    long duration = events.isEmpty() ? 0 : events.get(events.size() - 1).tickMillis();
                    String name = meta.name() != null && !meta.name().isEmpty()
                            ? meta.name() : finalFile.getName().replace(".echoreplay.gz", "");
                    writeRecordingFile(finalFile, meta.serverVersion(), meta.worldUuid(), meta.worldName(),
                            meta.cuboid(), duration, meta.epochMillis(), meta.recorderUuid(), meta.recorderName(),
                            name, r.palette() != null ? r.palette() : List.of(),
                            r.blockSizeX(), r.blockSizeY(), r.blockSizeZ(),
                            r.blockData() != null ? r.blockData() : new int[0], r.blockNbt(), events);
                    plugin.recordingIndex().put(new RecordingEntry(name, meta.worldUuid(), meta.worldName(),
                            duration, finalFile.length(), meta.epochMillis(),
                            meta.cuboid().min().x(), meta.cuboid().min().y(), meta.cuboid().min().z(),
                            meta.cuboid().max().x(), meta.cuboid().max().y(), meta.cuboid().max().z()));
                    if (!plugin.cfg().getBoolean("storage.keep-partial-on-crash", true)) {
                        partial.delete();
                        File[] rotsCleanup = dir.listFiles((d, n) -> n.startsWith(partial.getName() + ".rot"));
                        if (rotsCleanup != null) for (File s : rotsCleanup) s.delete();
                    }
                    plugin.getLogger().info("Recovered crashed recording '" + name + "' ("
                            + events.size() + " events, " + formatDuration(duration) + ").");
                } catch (Exception e) {
                    plugin.getLogger().warning("Checkpoint recovery failed for " + partial.getName() + ": " + e);
                }
            }
        });
    }

    public static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
