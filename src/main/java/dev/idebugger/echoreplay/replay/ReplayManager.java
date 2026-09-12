package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.storage.GzipRecordingReader;
import dev.idebugger.echoreplay.storage.MetaParser;
import dev.idebugger.echoreplay.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Manages the currently playing replay. Loads recordings async, owns the
 * session, drives the per-tick loop, and applies physics-freezing while a
 * world-mode replay is active.
 */
public final class ReplayManager implements Listener {

    private final EchoReplay plugin;
    private ReplaySession session;
    private boolean physicsFrozen = false;
    private final ControlModeManager controls;

    /**
     * S-3: atomic loading guard. Without this, two rapid /er play commands
     * both pass the {@code session == null} check, both schedule decode on
     * the IO thread, and the second one to land on the main thread orphans
     * the first session permanently — leaving a snapshot-wiped region with
     * no ticking session to ever restore it.
     */
    private final java.util.concurrent.atomic.AtomicBoolean loading =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public ReplayManager(EchoReplay plugin) {
        this.plugin = plugin;
        this.controls = new ControlModeManager(plugin, this);
    }

    public void onEnable(org.bukkit.configuration.file.FileConfiguration config) {
        physicsFrozen = config.getBoolean("replay.physics-frozen", true);
    }

    public void registerListeners(EchoReplay p) {
        p.getServer().getPluginManager().registerEvents(this, p);
        p.getServer().getPluginManager().registerEvents(controls, p);
    }

    public ControlModeManager controls() { return controls; }

    public void onDisable() {
        try {
            controls.clearAll();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control restore on disable failed", e);
        }
        if (session != null) {
            session.stop();
            // Drain any pending terrain restore synchronously: at shutdown
            // there is no tick loop left, and abandoning it would permanently
            // leave snapshot blocks where the live terrain was. Bounded by
            // wall clock (shutdown can be force-killed at any moment) so a
            // huge restore cannot hang the server shutdown forever.
            long deadline = System.currentTimeMillis() + 60_000L;
            int guard = 0;
            while (!session.tick() && guard++ < 100_000 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            session = null;
        }
    }

    public void onTick() {
        if (session != null) {
            // tick() streams load/restore phases and advances playback; it
            // returns true only when fully done (session may linger a few
            // ticks after stop() while terrain restores).
            boolean done = session.tick();
            if (!session.isStopping()) {
                // Playback border: client-side wireframe around the region, per-viewer toggle.
                PlaybackBorderRenderer.tick(session);
            }
            if (done) {
                session = null;
            }
        }
        try {
            controls.tick();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control tick failed", e);
        }
    }

    public ReplaySession session() {
        return session;
    }

    public boolean isPlayingIn(UUID worldUuid, Cuboid cuboid) {
        return session != null && session.world().getUID().equals(worldUuid)
                && session.cuboid().volume() > 0 && cuboid != null && intersects(session.cuboid(), cuboid);
    }

    private static boolean intersects(Cuboid a, Cuboid b) {
        return a.min().x() <= b.max().x() && a.max().x() >= b.min().x()
                && a.min().y() <= b.max().y() && a.max().y() >= b.min().y()
                && a.min().z() <= b.max().z() && a.max().z() >= b.min().z();
    }

    public String play(Player sender, String name, boolean forceVirtual) {
        if (session != null) {
            return "<red>A replay is already playing. Stop it first with /er stopplay.</red>";
        }
        // S-3: atomic end-to-end guard. compareAndSet returns false if another
        // play() is already in flight, which is the exact race we need to block.
        if (!loading.compareAndSet(false, true)) {
            return "<gray>A replay is already loading — one moment.</gray>";
        }
        File file = new File(plugin.recordingManager().recordingsDir(), name + ".echoreplay.gz");
        if (!file.exists()) {
            loading.set(false);
            return "<red>No recording named '" + name + "'.</red>";
        }
        final String fName = name;
        final boolean fVirtual = forceVirtual;
        final Player fSender = sender;
        // Everything heavy (gzip inflate, timeline decode + sort, header
        // parse) runs on the IO thread. The main thread only does cheap
        // session assembly. Decoding the full timeline on the main thread
        // froze ticks for 10s+ and OOMed on large recordings.
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                GzipRecordingReader reader;
                try (java.io.InputStream fis = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
                    reader = GzipRecordingReader.read(fis);
                }
                if (reader == null) return null;
                java.util.List<dev.idebugger.echoreplay.model.TimelineEvent> timeline = reader.timeline();
                reader.releaseFragments();
                MetaParser.Parsed meta = MetaParser.parse(reader.meta());
                return new DecodedRecording(reader.palette(), reader.blockData(),
                        reader.blockSizeX(), reader.blockSizeY(), reader.blockSizeZ(),
                        reader.blockNbt(), timeline, meta);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to read recording '" + fName + "': " + e);
                return null;
            }
        }, plugin.ioExecutor()).thenAccept(decoded -> {
            if (decoded == null || decoded.meta() == null) {
                loading.set(false);
                if (fSender != null && fSender.isOnline())
                    fSender.sendMessage(Text.mm("<red>Failed to read recording '" + fName + "'.</red>"));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    MetaParser.Parsed meta = decoded.meta();
                    World world = plugin.getServer().getWorld(meta.worldUuid());
                    if (world == null) world = plugin.getServer().getWorld(meta.worldName());
                    if (world == null) {
                        if (fSender != null && fSender.isOnline())
                            fSender.sendMessage(Text.mm("<red>World for this recording is not loaded.</red>"));
                        return;
                    }
                    boolean virtual = fVirtual || plugin.cfg().getBoolean("replay.virtual-packets-only", false);
                    session = new ReplaySession(plugin, fName, world, virtual, decoded);
                    // record snapshot restore (world mode) is applied within session
                    // Only the player who ran /er play becomes a viewer here.
                    // Bystanders are never force-added (and thus never gamemode-
                    // switched): they opt in with /er watch.
                    if (fSender != null && fSender.isOnline()) {
                        session.addViewer(fSender);
                    }
                    session.play();
                    if (fSender != null && fSender.isOnline())
                        fSender.sendMessage(Text.mm("<green>Playing '" + fName + "' in " + world.getName() + ".</green>"));
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to start replay '" + fName + "': " + t);
                    if (fSender != null && fSender.isOnline())
                        fSender.sendMessage(Text.mm("<red>Failed to start replay: " + t.getMessage() + "</red>"));
                } finally {
                    // S-3: ALWAYS clear the loading guard, on every path —
                    // success, world-not-found, or exception. A stuck guard
                    // would block all future /er play commands.
                    loading.set(false);
                }
            });
        });
        return null;
    }

    public String stopPlay(boolean restoreLive) {
        if (session == null) return "<red>Nothing playing.</red>";
        // Begins the async stop (fakes destroyed now, terrain restores over
        // the next ticks); the session clears itself when done.
        session.stop();
        try {
            controls.clearAll();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control clear on stop failed", e);
        }
        return "<green>Stopped playback.</green>";
    }

    /** Refuse control commands while a session is draining its stop. */
    private String stoppingGuard() {
        if (session != null && session.isStopping()) return "<gray>Playback is stopping…</gray>";
        return null;
    }

    public String pause() {
        if (session == null) return "<red>Nothing playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        session.setPaused(true);
        return "<gray>Paused.</gray>";
    }

    public String resume() {
        if (session == null) return "<red>No replay is playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        session.setPaused(false);
        return "<gray>Resumed.</gray>";
    }

    public String speed(double s) {
        if (session == null) return "<red>Nothing playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        try {
            session.setSpeed(s);
        } catch (IllegalArgumentException e) {
            return "<red>" + e.getMessage() + "</red>";
        }
        return "<gray>Speed set to " + session.clock().speed() + "x.</gray>";
    }

    public String seek(double seconds) {
        if (session == null) return "<red>Nothing playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        session.seekTo(seconds * 1000);
        return "<gray>Seeked to " + RecordingManagerTime.format(seconds) + ".</gray>";
    }

    public String rewind(double seconds) {
        if (session == null) return "<red>Nothing playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        double target = Math.max(0, session.clock().mediaTime() - seconds * 1000);
        session.seekTo(target);
        return "<gray>Rewound to " + RecordingManagerTime.format(target / 1000) + ".</gray>";
    }

    public String forward(double seconds) {
        if (session == null) return "<red>Nothing playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        double target = Math.min(session.durationMs(), session.clock().mediaTime() + seconds * 1000);
        session.seekTo(target);
        return "<gray>Fast-forwarded to " + RecordingManagerTime.format(target / 1000) + ".</gray>";
    }

    public String watch(Player viewer) {
        if (session == null) return "<red>No replay is playing.</red>";
        String g = stoppingGuard();
        if (g != null) return g;
        if (session.isViewer(viewer)) return "<gray>You are already watching.</gray>";
        // addViewer() queues a full state sync (entity snapshot + past block
        // changes in virtual mode) that the session drains over the next
        // few ticks.
        session.addViewer(viewer);
        return "<green>You are now watching the replay.</green>";
    }

    public String leave(Player p) {
        if (session == null) return "<red>No replay is playing.</red>";
        session.removeViewer(p);
        try {
            controls.disable(p);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control clear on leave failed", e);
        }
        return "<gray>You left the replay.</gray>";
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysics(BlockPhysicsEvent e) {
        if (physicsFrozen && session != null && !session.virtual()) {
            if (e.getBlock().getWorld().getUID().equals(session.world().getUID())
                    && session.cuboid().contains(e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ())) {
                e.setCancelled(true);
            }
        }
    }

    // --- Spectator immunity: a first-person spectator's real body walks
    //     through the live world, where outside mobs and hazards can hit it.
    //     Damage and hunger drain are cancelled outright, and the per-tick
    //     driver pins health/hunger to the recording — hits do nothing. ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectateDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Player p)) return;
        ReplaySession s = session;
        if (s != null && s.isSpectating(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectateFood(org.bukkit.event.entity.FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Player p)) return;
        ReplaySession s = session;
        if (s != null && s.isSpectating(p)) e.setCancelled(true);
    }

    /** True while a world-mode replay is locking this block's location. */
    private boolean isLocked(org.bukkit.block.Block b) {
        return session != null && !session.virtual()
                && b.getWorld().getUID().equals(session.world().getUID())
                && session.cuboid().contains(b.getX(), b.getY(), b.getZ());
    }

    private boolean isLocked(World w, int x, int y, int z) {
        return session != null && !session.virtual()
                && w.getUID().equals(session.world().getUID())
                && session.cuboid().contains(x, y, z);
    }

    // --- Region lock: block ANY player/live change inside the cuboid while a
    //     world-mode replay runs, so the playback cannot be manipulated. The
    //     region is unlocked automatically on stopplay / auto-end. ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (isLocked(e.getBlock()) || isLocked(e.getBlockAgainst())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockMultiPlace(org.bukkit.event.block.BlockMultiPlaceEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFromTo(org.bukkit.event.block.BlockFromToEvent e) {
        if (isLocked(e.getBlock()) || isLocked(e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(org.bukkit.event.block.BlockSpreadEvent e) {
        if (isLocked(e.getBlock()) || isLocked(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFade(org.bukkit.event.block.BlockFadeEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBurn(org.bukkit.event.block.BlockBurnEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(org.bukkit.event.block.BlockIgniteEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        e.blockList().removeIf(b -> isLocked(b));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        e.blockList().removeIf(b -> isLocked(b));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidLevelChange(org.bukkit.event.block.FluidLevelChangeEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDispense(org.bukkit.event.block.BlockDispenseEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockGrow(org.bukkit.event.block.BlockGrowEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent e) {
        if (isLocked(e.getBlock()) || e.getBlocks().stream().anyMatch(this::isLocked)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent e) {
        if (isLocked(e.getBlock()) || e.getBlocks().stream().anyMatch(this::isLocked)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(org.bukkit.event.block.EntityBlockFormEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquidToBlock(org.bukkit.event.block.BlockFormEvent e) {
        if (isLocked(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreventPhysics(org.bukkit.event.block.BlockCanBuildEvent e) {
        if (isLocked(e.getBlock())) e.setBuildable(false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (session != null) {
            session.removeViewer(e.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        if (session != null) {
            session.hideSpectatorsFrom(e.getPlayer());
        }
    }

    static final class RecordingManagerTime {
        static String format(double sec) {
            long s = (long) sec;
            return String.format("%d:%02d", s / 60, s % 60);
        }
    }
}
