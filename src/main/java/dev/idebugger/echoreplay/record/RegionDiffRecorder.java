package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.select.Cuboid;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically diffs the ENTIRE cuboid against the last seen state so that EVERY
 * block change is recorded, including ones that never trigger a Bukkit event:
 * cancelled/replaced player breaks, explosions, end crystals, plugin writes,
 * obsidian/portal formation, redstone, etc.
 *
 * The scan runs on a dedicated background thread and continuously walks the
 * whole region with no interval gap (full-time diff), comparing live block
 * state against the last seen state and emitting a BlockSet for every
 * difference. Raw block states are read through the server's internal chunk
 * (NMS reflection), which is safe to read off-thread for loaded chunks, then
 * serialized to a Bukkit-compatible "minecraft:..." string via BlockStateParser.
 * Event sinks, palette interning and media clock are all thread-safe.
 *
 * If the reflective access is unavailable for the running server version the
 * scanner simply records nothing (the event-driven recorders still cover the
 * common cases) rather than risking an unsafe main-thread pass.
 */
public final class RegionDiffRecorder {

    private final EchoReplay plugin;
    private final AtomicReference<RecordingSession> current = new AtomicReference<>();
    private final Map<Long, byte[]> lastSeen = new ConcurrentHashMap<>();
    private volatile boolean active = false;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;
    private volatile long passDelayMs = 50L;
    // Resumable sweep cursor: linear cell index into the cuboid volume.
    // Each pass processes cells until the millisecond budget is exhausted,
    // then resumes here next pass, so arbitrarily large regions stream
    // without hogging CPU (previously a full unconstrained scan per pass,
    // which saturated small servers and tanked TPS while recording).
    private int scanCursor = 0;
    private long scanBudgetNanos = 12_000_000L;
    // Set from the main-thread tick recorder: true while any player is inside
    // the cuboid. With no audience the scan drops to ~1 pass/sec (changes are
    // still caught, only later); while the server lags it pauses entirely.
    private volatile boolean audienceNearby = true;
    private int idlePasses = 0;

    /** Main-thread only: whether any tracked player is inside the region. */
    public void setAudienceNearby(boolean b) {
        audienceNearby = b;
    }

    public RegionDiffRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    /**
     * @param intervalTicks schedule a diff pass every N server ticks
     *                      (1 = full-time; clamped to 1..20)
     */
    public void configure(int intervalTicks) {
        int ticks = Math.max(1, Math.min(20, intervalTicks));
        this.passDelayMs = ticks * 50L;
        Nms.logState();
        if (!Nms.available()) {
            try {
                plugin.getLogger().warning("RegionDiff NMS unavailable (" + Nms.describe()
                        + ") — diff scanner disabled, falling back to Bukkit ChunkSnapshot path. "
                        + "Run /er debug nms for details. Event-driven recorders still cover common cases.");
            } catch (Exception e) {
                org.bukkit.Bukkit.getLogger().warning("RegionDiff NMS unavailable — event-only recording.");
            }
        }
    }

    /** One-line NMS diagnostics for /er debug nms (no server internals leaked). */
    public static String describeNms() {
        return Nms.describe();
    }

    public static boolean isNmsAvailable() {
        return Nms.available();
    }

    public boolean isActive() { return active; }

    /** Begin scanning the given session in the background. */
    public void reset(RecordingSession s) {
        current.set(s);
        lastSeen.clear();
        scanCursor = 0;
        audienceNearby = true;
        idlePasses = 0;
        long ms = 12L;
        try {
            ms = plugin.cfg().getLong("recording.scan-ms-per-pass", 12L);
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
        if (ms < 1) ms = 1;
        if (ms > 40) ms = 40;
        scanBudgetNanos = ms * 1_000_000L;
        active = true;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "EchoReplay-RegionDiff");
                t.setDaemon(true);
                return t;
            });
        }
        task = executor.scheduleWithFixedDelay(this::runPassAsync, 0, passDelayMs, TimeUnit.MILLISECONDS);
    }

    /** Stop scanning; must be called from the main recording stop path. */
    public void stop() {
        active = false;
        current.set(null);
        lastSeen.clear();
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void runPassAsync() {
        if (!active) return;
        RecordingSession s = current.get();
        if (s == null) {
            active = false;
            return;
        }
        // Idle: nobody around to see changes — ~1 pass/sec is plenty.
        if (!audienceNearby && (++idlePasses % 20) != 0) return;
        if (audienceNearby) idlePasses = 0;
        // Overload: never make a lagging tick loop worse to catch a block.
        try {
            double[] tps = plugin.getServer().getTPS();
            if (tps != null && tps.length > 0 && tps[0] < 17.0) return;
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
        try {
            scanAsync(s);
        } catch (Throwable t) {
            EchoReplay.getPlugin(EchoReplay.class).getLogger()
                    .warning("RegionDiff async pass error: " + t);
        }
    }

    private void scanAsync(RecordingSession s) {
        Cuboid c = s.cuboid();
        Object handle = Nms.handle(s.world());
        if (handle == null) {
            // NMS unavailable -> stable Paper ChunkSnapshot/Bukkit fallback
            // instead of silently disabling the feature. Slower, but version-proof.
            scheduleBukkitFallbackScan(s);
            return;
        }
        int minX = c.min().x(), minY = c.min().y(), minZ = c.min().z();
        int sx = c.max().x() - minX + 1;
        int sy = c.max().y() - minY + 1;
        int sz = c.max().z() - minZ + 1;
        long vol = (long) sx * sy * sz;
        if (vol <= 0 || vol > Integer.MAX_VALUE) {
            return;
        }
        if (scanCursor < 0 || (long) scanCursor >= vol) scanCursor = 0;
        long deadline = System.nanoTime() + scanBudgetNanos;
        // Chunk cache: cells are visited in x/z-major order so consecutive
        // cells usually share a chunk; re-fetch only on chunk change.
        int curCX = Integer.MIN_VALUE, curCZ = Integer.MIN_VALUE;
        Object chunk = null;
        int n = 0;
        while ((long) scanCursor < vol) {
            int i = scanCursor++;
            int dx = i % sx;
            int dz = (i / sx) % sz;
            int dy = i / (sx * sz);
            int x = minX + dx, y = minY + dy, z = minZ + dz;
            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
            if (cx != curCX || cz != curCZ) {
                chunk = Nms.chunk(handle, cx, cz);
                curCX = cx;
                curCZ = cz;
            }
            if (chunk == null) continue;
            Object state = Nms.blockState(chunk, x & 15, y, z & 15);
            String str = Nms.toStringSafe(state);
           // D-8.9: widened key split to 26/26/12 bits — supports |x|<33M
            // and |z|<33M (full world range) plus y up to 4095 (covers build
            // height + buffer). v1 used 20/20/24-bit split which only safely
            // covered |x|<8.3M and |z|<1M — far-flung bases (anarchy servers,
            // teleport hubs) silently produced key collisions = phantom diffs.
            long key = (((long) (x + 33554432) & 0x3FFFFFFL) << 38)
                     | (((long) (z + 33554432) & 0x3FFFFFFL) << 12)
                     | ((y + 2048) & 0xFFFL);
            byte[] enc = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] prev = lastSeen.get(key);
            if (prev == null) {
                // First observation of this cell -> establish the
                // baseline (this pass) without emitting, so the diff
                // only reports genuine changes from now on.
                lastSeen.put(key, enc);
            } else if (!java.util.Arrays.equals(prev, enc)) {
                lastSeen.put(key, enc);
                enqueueEmit(s, x, y, z, str);
            }
            if ((++n & 1023) == 0 && System.nanoTime() >= deadline) break;
        }
    }

    /**
     * Stable fallback when NMS reflection cannot resolve (Paper updates rename
     * classes/methods frequently). Uses only Bukkit API
     * ({@code World.getBlockAt}) on the main thread — slower than NMS chunk
     * reads, but immune to renames. Budgeted per pass so it cannot stall ticks.
     * Coalesced: at most one fallback scan task is queued at a time.
     */
    private final java.util.concurrent.atomic.AtomicBoolean fallbackScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile int fallbackCursor = 0;

    private void scheduleBukkitFallbackScan(RecordingSession s) {
        if (!fallbackScheduled.compareAndSet(false, true)) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    fallbackScanMainThread(s);
                } finally {
                    fallbackScheduled.set(false);
                }
            });
        } catch (Exception e) {
            fallbackScheduled.set(false);
            plugin.getLogger().log(java.util.logging.Level.FINE,
                    "EchoReplay: fallback diff scheduling failed", e);
        }
    }

    private void fallbackScanMainThread(RecordingSession s) {
        if (!active || s == null) return;
        Cuboid c = s.cuboid();
        World w;
        try {
            w = s.world();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.FINE,
                    "EchoReplay: fallback diff has no world", e);
            return;
        }
        int minX = c.min().x(), minY = c.min().y(), minZ = c.min().z();
        int sx = c.max().x() - minX + 1;
        int sy = c.max().y() - minY + 1;
        int sz = c.max().z() - minZ + 1;
        long vol = (long) sx * sy * sz;
        if (vol <= 0 || vol > Integer.MAX_VALUE) return;
        if (fallbackCursor < 0 || (long) fallbackCursor >= vol) fallbackCursor = 0;
        // Small budget: fallback is correctness net, not full-time scanner.
        long deadline = System.nanoTime() + Math.min(scanBudgetNanos, 4_000_000L);
        int n = 0;
        // Cap cells per pass so huge regions stream without tick spikes.
        int cap = 2048;
        while ((long) fallbackCursor < vol && n < cap) {
            int i = fallbackCursor++;
            int dx = i % sx;
            int dz = (i / sx) % sz;
            int dy = i / (sx * sz);
            int x = minX + dx, y = minY + dy, z = minZ + dz;
            String str;
            try {
                var bd = w.getBlockAt(x, y, z).getBlockData();
                str = bd == null ? "minecraft:air" : bd.getAsString(true);
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.FINE,
                        "EchoReplay: fallback block read failed at " + x + "," + y + "," + z, e);
                continue;
            }
            long key = (((long) (x + 33554432) & 0x3FFFFFFL) << 38)
                     | (((long) (z + 33554432) & 0x3FFFFFFL) << 12)
                     | ((y + 2048) & 0xFFFL);
            byte[] enc = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] prev = lastSeen.get(key);
            if (prev == null) {
                lastSeen.put(key, enc);
            } else if (!java.util.Arrays.equals(prev, enc)) {
                lastSeen.put(key, enc);
                enqueueEmit(s, x, y, z, str);
            }
            n++;
            if ((n & 255) == 0 && System.nanoTime() >= deadline) break;
        }
    }

    /**
     * The diff itself is computed on the background thread, but emitting touches
     * Bukkit (tile NBT reads) which must run on the main thread. We capture the
     * media timestamp at detection time so the scheduled emission stays correctly
     * ordered (events are re-sorted by tickMillis before writing anyway). We
     * batch pending emissions so a burst of changes is one main-thread task.
     */
    private final java.util.concurrent.LinkedBlockingQueue<Pending> pending =
            new java.util.concurrent.LinkedBlockingQueue<>();
    // Coalesces flush scheduling: a burst of N diffs schedules exactly one
    // main-thread task instead of N. Without this, a busy region (flowing
    // water, farms, explosions) queues thousands of sync tasks per 50ms pass
    // and stalls the server tick loop (watchdog hang).
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    record Pending(RecordingSession s, int x, int y, int z, String state) {}

    private void enqueueEmit(RecordingSession s, int x, int y, int z, String state) {
        pending.add(new Pending(s, x, y, z, state));
        // Schedule one main-thread flush for this burst of emissions.
        if (flushScheduled.compareAndSet(false, true)) {
            EchoReplay.getPlugin(EchoReplay.class)
                    .getServer().getScheduler().runTask(plugin, () -> {
                        flushScheduled.set(false);
                        flushPendingMainThread();
                    });
        }
    }

    private void flushPendingMainThread() {
        Pending p;
        while ((p = pending.poll()) != null) {
            try {
                emit(p.s(), p.x(), p.y(), p.z(), p.state());
            } catch (Throwable t) {
                EchoReplay.getPlugin(EchoReplay.class).getLogger()
                        .warning("RegionDiff emit error: " + t);
            }
        }
    }

    private void emit(RecordingSession s, int wx, int wy, int wz, String state) {
        int dx = wx - s.cuboid().min().x();
        int dy = wy - s.cuboid().min().y();
        int dz = wz - s.cuboid().min().z();
        int pi = s.paletteIndex(state);
        byte[] nbt = captureNbt(s.world().getBlockAt(wx, wy, wz), state);
        s.emit(new TimelineEvent.BlockSet(s.mediaMillis(),
                new BlockPos(dx, dy, dz), pi, nbt));
    }

    private static byte[] captureNbt(Block block, String state) {
        if (state == null || state.equals("minecraft:air")) return null;
        try {
            var tile = block.getState(true);
            if (tile == null) return null;
            byte[] nb = dev.idebugger.echoreplay.util.NbtBytes.serializeBlockState(tile);
            return (nb != null && nb.length > 0) ? nb : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reflective access to the server's internal world/level chunk block reads.
     * Resolves candidate class/method names at runtime so different server
     * versions (ServerLevel vs ServerWorld, LevelChunk vs WorldChunk,
     * BlockStateParser vs CommandDispatcher) do not break the plugin; if none
     * resolve, {@link #handle(World)} returns null and scanning is disabled.
     */
    private static final class Nms {
        private static volatile boolean resolved = false;
        private static java.lang.reflect.Method worldGetChunk;
        private static java.lang.reflect.Method chunkGetBlockState;
        private static java.lang.reflect.Method serializeState;
        // Direct block-state serializer (no BlockStateParser dependency).
        // Reproduces vanilla "minecraft:id[prop=val,...]" output using only
        // long-stable public NMS API, so renames like ResourceLocation ->
        // Identifier (1.21.11+) or BlockStateParser signature changes cannot
        // break scanning. Resolution:
        //   state.getBlock() -> Block.builtInRegistryHolder() -> unwrapKey()
        //   -> Optional<ResourceKey> -> location()/identifier() -> id string,
        //   plus state.getProperties()/getValues() and Property.getName().
        private static java.lang.reflect.Method stateGetBlock;
        private static java.lang.reflect.Method blockHolder;
        private static java.lang.reflect.Method holderUnwrapKey;
        private static java.lang.reflect.Method stateGetProperties;
        private static java.lang.reflect.Method stateGetValues;
        private static volatile java.lang.reflect.Method keyLocation;
        private static volatile java.lang.reflect.Method propName;
        private static volatile java.lang.reflect.Method propValueName;

        static {
            resolve();
        }

        private static void resolve() {
            if (resolved) return;
            synchronized (Nms.class) {
                if (resolved) return;
                String[] worldClasses = {
                        "net.minecraft.server.level.ServerWorld",
                        "net.minecraft.server.level.ServerLevel",
                        "net.minecraft.server.level.WorldServer"
                };
                // Chunk accessors tried in order of preference: non-generating,
                // already-loaded variants are safest to call off-thread (they return
                // null if absent without triggering chunk load/generation). Plain
                // getChunk(int,int) is the fallback since the cuboid is loaded.
                String[][] path = {
                        {"getChunkAtIfLoadedImmediately", "II"},
                        {"getChunkIfLoaded", "II"},
                        {"getChunkAtIfLoaded", "II"},
                        {"getChunk", "II"},
                };
                for (String cn : worldClasses) {
                    try {
                        Class<?> wc = Class.forName(cn);
                        for (String[] pp : path) {
                            String name = pp[0];
                            if ("II".equals(pp[1])) {
                                try {
                                    java.lang.reflect.Method m = wc.getMethod(name, int.class, int.class);
                                    worldGetChunk = m;
                                    chunkGetBlockState = m.getReturnType()
                                            .getMethod("getBlockState", int.class, int.class, int.class);
                                    resolved = true;
                                    break;
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                        if (resolved) break;
                    } catch (Throwable ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Throwable", ignored);
                    }
                }
                // BlockStateParser.serialize(BlockState) -> "minecraft:xxx[props]"
                // Fast path only: in newer NMS the parser's contract may change
                // (extra params, non-String return), so only accept methods that
                // still return plain String. The direct serializer below is the
                // robust fallback and works on every version.
                if (resolved) {
                    String[] parserClasses = {
                            "net.minecraft.commands.arguments.blocks.BlockStateParser",
                            "net.minecraft.commands.arguments.blocks.BlockStateParserHelper"
                    };
                    for (String pc : parserClasses) {
                        try {
                            Class<?> cls = Class.forName(pc);
                            java.lang.reflect.Method m = cls.getMethod("serialize",
                                    chunkGetBlockState.getReturnType());
                            if (m.getReturnType() == String.class) {
                                serializeState = m;
                                break;
                            }
                        } catch (Throwable ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Throwable", ignored);
                        }
                    }
                }
                // Direct serializer: same output as vanilla serialize(), but
                // built from stable Block/StateHolder/Property/Holder API only.
                // Immune to BlockStateParser moves and to the 1.21.11+
                // ResourceLocation -> Identifier rename (location() vs
                // identifier() is resolved lazily at first use).
                if (resolved && worldGetChunk != null && chunkGetBlockState != null) {
                    try {
                        Class<?> stateClass = chunkGetBlockState.getReturnType();
                        stateGetBlock = stateClass.getMethod("getBlock");
                        blockHolder = stateGetBlock.getReturnType()
                                .getMethod("builtInRegistryHolder");
                        holderUnwrapKey = blockHolder.getReturnType()
                                .getMethod("unwrapKey");
                        stateGetProperties = stateClass.getMethod("getProperties");
                        stateGetValues = stateClass.getMethod("getValues");
                    } catch (Throwable ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Throwable", ignored);
                        stateGetBlock = null;
                    }
                }
                resolved = true;
            }
        }

        static boolean available() {
            resolve();
            return worldGetChunk != null && chunkGetBlockState != null;
        }

        /** One-line diagnostics for /er debug nms (safe to show ops). */
        static String describe() {
            resolve();
            String world = worldGetChunk != null
                    ? worldGetChunk.getDeclaringClass().getName() + "#" + worldGetChunk.getName() + "(int,int)"
                    : "unresolved";
            String chunk = chunkGetBlockState != null
                    ? chunkGetBlockState.getDeclaringClass().getSimpleName() + "#"
                            + chunkGetBlockState.getName() + "(int,int,int)"
                    : "unresolved";
            String ser = serializeState != null ? "BlockStateParser.serialize"
                    : (stateGetBlock != null ? "direct(Block/StateHolder/Property)"
                    : "toString-fallback");
            String key = "ResourceKey." + (keyLocation != null ? keyLocation.getName() : "location/identifier?");
            return "world=" + world + " chunk=" + chunk + " serialize=" + ser + " " + key
                    + (available() ? " OK" : " UNAVAILABLE(fallback active)");
        }

        /** Log which reflective accessors resolved, for debugging on live setups. */
        static void logState() {
            resolve();
            EchoReplay.getPlugin(EchoReplay.class).getLogger()
                    .info("RegionDiff NMS resolver: world=" + (worldGetChunk != null ? worldGetChunk.getDeclaringClass().getSimpleName()
                            + "." + worldGetChunk.getName() : "none")
                            + " chunkBlockState=" + (chunkGetBlockState != null ? chunkGetBlockState.getName() : "none")
                            + " serialize=" + (serializeState != null ? "parser"
                            : (stateGetBlock != null ? "direct" : "toString-fallback")));
        }

        static Object handle(World world) {
            resolve();
            if (!available()) return null;
            try {
                java.lang.reflect.Method gh = world.getClass().getMethod("getHandle");
                return gh.invoke(world);
            } catch (Throwable t) {
                return null;
            }
        }

        static Object chunk(Object worldHandle, int cx, int cz) {
            try {
                return worldGetChunk.invoke(worldHandle, cx, cz);
            } catch (Throwable t) {
                return null;
            }
        }

        static Object blockState(Object chunk, int lx, int y, int lz) {
            try {
                return chunkGetBlockState.invoke(chunk, lx, y, lz);
            } catch (Throwable t) {
                return null;
            }
        }

        static String toStringSafe(Object state) {
            if (state == null) return "minecraft:air";
            // 1) NMS BlockStateParser fast path (String-returning only).
            try {
                if (serializeState != null) {
                    Object str = serializeState.invoke(null, state);
                    if (str instanceof String s) return s;
                }
            } catch (Throwable ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Throwable", ignored);
            }
            // 2) Direct serializer: byte-identical format to vanilla
            // serialize(), no command-parser dependency.
            try {
                String direct = directSerialize(state);
                if (direct != null) return direct;
            } catch (Throwable ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Throwable", ignored);
            }
            // 3) Last resort: whatever the state prints as.
            try {
                String raw = state.toString();
                return raw != null ? raw : "minecraft:air";
            } catch (Throwable t) {
                return "minecraft:air";
            }
        }

        /**
         * Builds "minecraft:id" or "minecraft:id[prop=val,...]" exactly like
         * vanilla BlockStateParser.serialize(): block id from the block's
         * registry holder key, then StateHolder property entries rendered via
         * Property.getName()/getName(value). Reads immutable state only, so it
         * is safe to call from the background diff thread.
         */
        private static String directSerialize(Object state) throws Exception {
            if (stateGetBlock == null || blockHolder == null || holderUnwrapKey == null
                    || stateGetProperties == null || stateGetValues == null) {
                throw new IllegalStateException("direct serializer unresolved");
            }
            Object block = stateGetBlock.invoke(state);
            Object holder = blockHolder.invoke(block);
            @SuppressWarnings("unchecked")
            java.util.Optional<Object> key =
                    (java.util.Optional<Object>) holderUnwrapKey.invoke(holder);
            String id;
            if (key == null || key.isEmpty()) {
                id = "minecraft:air";
            } else {
                Object rk = key.get();
                java.lang.reflect.Method loc = keyLocation;
                if (loc == null) {
                    // 1.21.5 and earlier: ResourceKey.location();
                    // 1.21.11+: renamed to ResourceKey.identifier().
                    try {
                        loc = rk.getClass().getMethod("location");
                    } catch (NoSuchMethodException e) {
                        loc = rk.getClass().getMethod("identifier");
                    }
                    keyLocation = loc;
                }
                Object idObj = loc.invoke(rk);
                id = idObj != null ? idObj.toString() : "minecraft:air";
            }
            Object propsObj = stateGetProperties.invoke(state);
            if (!(propsObj instanceof java.util.Collection<?> props) || props.isEmpty()) {
                return id;
            }
            Object valuesObj = stateGetValues.invoke(state);
            if (!(valuesObj instanceof java.util.Map<?, ?> values) || values.isEmpty()) {
                return id;
            }
            StringBuilder sb = new StringBuilder(id);
            sb.append('[');
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : values.entrySet()) {
                Object prop = e.getKey();
                Object value = e.getValue();
                java.lang.reflect.Method nameM = propName;
                java.lang.reflect.Method valueNameM = propValueName;
                if (nameM == null) {
                    nameM = prop.getClass().getMethod("getName");
                    valueNameM = prop.getClass().getMethod("getName", Comparable.class);
                    propName = nameM;
                    propValueName = valueNameM;
                }
                if (!first) sb.append(',');
                sb.append(nameM.invoke(prop));
                sb.append('=');
                sb.append(valueNameM.invoke(prop, value));
                first = false;
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
