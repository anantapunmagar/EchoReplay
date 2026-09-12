package dev.idebugger.echoreplay.record;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import dev.idebugger.echoreplay.select.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Main-thread per-tick recorder that captures the position of every qualifying
 * non-player entity inside (or near) the cuboid. This covers mobs, items,
 * projectiles, vehicles, etc. regardless of how they got there:
 *  - an entity already present records its spawn once (so anything that walked
 *    or was dropped into the zone mid-recording is captured even if no
 *    EntitySpawnEvent fired inside the cuboid),
 *  - subsequent per-tick movement emits MOVE events so mobs move in the replay.
 *
 * It also captures the pose / stance flags (swimming, gliding, sneaking,
 * sprinting) of every entity including players, emitted as POSE and
 * SNEAK_SPRINT events whenever they change, so those states display in replay.
 */
public final class EntityTickRecorder {

    // Metadata index-0 entity flags (protocol bitmask). With real world values:
    // 0x01 = on fire, 0x02 = sneaking, 0x08 = sprinting, 0x10 = swimming.
    // We must never set 0x01 (fire) here, otherwise sneaking replays burn.
    private static final int FLAG_CROUCHED = 0x02;
    private static final int FLAG_SPRINTING = 0x08;
    private static final int FLAG_SWIMMING = 0x10;

    private static final double POS_EPS = 1e-4;

    private final EchoReplay plugin;
    private final Map<java.util.UUID, EntityPose> lastKnown = new HashMap<>();
    private final Map<UUID, Integer> lastPose = new HashMap<>();
    private final Map<UUID, Integer> lastFlags = new HashMap<>();
    private final Map<UUID, org.bukkit.util.Vector> lastVel = new HashMap<>();
    private final Map<java.util.UUID, EntityPose> lastPlayerSeen = new HashMap<>();
    // UUIDs currently inside the recording cuboid, maintained on the main
    // thread. Read lock-free from Netty threads (movement dedup gate).
    private final java.util.Set<UUID> inRegion =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EntityTickRecorder(EchoReplay plugin) {
        this.plugin = plugin;
    }

    /** Lock-free membership test for packet threads (no Bukkit calls). */
    public boolean isInRegion(UUID uuid) {
        return uuid != null && inRegion.contains(uuid);
    }

    record EntityPose(Vec3d pos, Rotation rot) {}

    /**
     * Called once per server tick from the recording tick loop. All on main thread.
     */
    public void tick() {
        RecordingSession s = plugin.recordingManager().activeSession();
        if (s == null || s.state() != RecordingSession.State.RECORDING) return;
        World world = s.world();
        Cuboid c = s.cuboid();

        // Book-keeping set of mobs we observed this tick, so we can emit a
        // LEAVE for mobs that were tracked but vanished from the region.
        Map<java.util.UUID, EntityPose> observed = new HashMap<>();
        boolean seenPlayer = false;

        // Centered box with exact half-extents: the old call passed full
        // sizes as radii from the min corner, scanning 8x the volume and
        // recording entities far outside the cuboid.
        double cx = (c.min().x() + c.max().x()) / 2.0;
        double cy = (c.min().y() + c.max().y()) / 2.0;
        double cz = (c.min().z() + c.max().z()) / 2.0;
        for (Entity e : world.getNearbyEntities(
                new Location(world, cx, cy, cz),
                c.xSize() / 2.0 + 1, c.ySize() / 2.0 + 1, c.zSize() / 2.0 + 1)) {
            boolean isPlayer = e instanceof Player;
            UUID uuid = e.getUniqueId();
            Location loc = e.getLocation();
            if (!c.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) continue;
            // Privacy opt-outs are never recorded: not observed, not tracked,
            // not added to the region set (which also gates their movement
            // packets). Vanishing mid-take emits a normal LEAVE below.
            if (isPlayer && plugin.privacy().isExempt(uuid)) {
                inRegion.remove(uuid);
                continue;
            }
            inRegion.add(uuid);
            if (isPlayer) seenPlayer = true;

            // Pose / stance capture applies to everyone (players + mobs).
            captureStance(s, uuid, e);

            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();
            float yaw = loc.getYaw();
            float pitch = loc.getPitch();
            Vec3d pos = new Vec3d(x, y, z);
            Rotation rot = new Rotation(pitch, yaw, yaw);
            observed.put(uuid, new EntityPose(pos, rot));

            if (isPlayer) {
                // Anchor the packet-thread movement baseline so rotation-only
                // packets (look-around while standing still) record immediately
                // instead of being dropped for lack of a position baseline.
                dev.idebugger.echoreplay.record.MovementRecorder mr = plugin.movementRecorder();
                if (mr != null) mr.seedIfAbsent(uuid, x, y, z, yaw, pitch);
                EntityPose prevPlayer = lastPlayerSeen.get(uuid);
                boolean first = prevPlayer == null;
                if (first) {
                    // First observation — a fresh joiner (deduped against
                    // ConnectionRecorder's join event) or a player re-entering
                    // the region after walking out. Carry real skin + current
                    // equipment so re-entry does not spawn a skinless,
                    // unarmed fake player.
                    if (s.markEntitySpawned(uuid)) {
                        int npc = s.npcIdFor(uuid);
                        Player pl = (Player) e;
                        s.emit(new TimelineEvent.PlayerSpawn(s.mediaMillis(), npc, uuid,
                                pl.getName(), ConnectionRecorder.skin(pl), pos, rot,
                                ConnectionRecorder.equipment(pl), null));
                    }
                }
                lastPlayerSeen.put(uuid, new EntityPose(pos, rot));
                // First-person spectate data: vitals + full inventory.
                capturePlayerState(s, (Player) e, uuid, first);
                continue;
            }

            int npc = s.npcIdFor(uuid);
            EntityPose prev = lastKnown.get(uuid);
            if (prev == null) {
                // First observation -> treat as a spawn into the zone, but only
                // emit if the event listener did not already spawn this entity.
                if (s.markEntitySpawned(uuid)) {
                    s.emit(new TimelineEvent.EntitySpawn(s.mediaMillis(), npc, uuid,
                            e.getType().getKey().toString(), pos, rot, dev.idebugger.echoreplay.model.RecordedMetadata.capture(e)));
                }
                lastKnown.put(uuid, new EntityPose(pos, rot));
            } else {
                // Emit a MOVE when the position/rotation changed meaningfully.
                if (!prev.pos().equals(pos) || !sameRot(prev.rot(), rot)) {
                    s.emit(new TimelineEvent.Move(s.mediaMillis(), npc, pos, rot, true));
                    lastKnown.put(uuid, new EntityPose(pos, rot));
                }
            }
            // Velocity for projectiles (firework rockets, arrows, etc.) so
            // crossbow-launched flight direction and speed replay correctly.
            if (e instanceof org.bukkit.entity.Projectile) {
                org.bukkit.util.Vector vel = e.getVelocity();
                org.bukkit.util.Vector prevVel = lastVel.get(uuid);
                double eps = 0.001;
                boolean changed = prevVel == null
                        || Math.abs(prevVel.getX() - vel.getX()) > eps
                        || Math.abs(prevVel.getY() - vel.getY()) > eps
                        || Math.abs(prevVel.getZ() - vel.getZ()) > eps;
                if (changed) {
                    s.emit(new TimelineEvent.Velocity(s.mediaMillis(), npc,
                            new Vec3d(vel.getX(), vel.getY(), vel.getZ())));
                    lastVel.put(uuid, vel.clone());
                }
            }
        }

        // Emit LEAVE for tracked mobs no longer present in the region.
        if (!lastKnown.isEmpty()) {
            java.util.Iterator<Map.Entry<java.util.UUID, EntityPose>> it = lastKnown.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<java.util.UUID, EntityPose> en = it.next();
                if (!observed.containsKey(en.getKey())) {
                    int npc = s.npcIdFor(en.getKey());
                    long t = s.mediaMillis();
                    s.emit(new TimelineEvent.EntityLeave(t, npc));
                    it.remove();
                    inRegion.remove(en.getKey());
                    lastVel.remove(en.getKey());
                    lastPose.remove(en.getKey());
                    lastFlags.remove(en.getKey());
                }
            }
        }

        // Emit LEAVE for tracked players no longer present in the region.
        if (!lastPlayerSeen.isEmpty()) {
            java.util.Iterator<Map.Entry<java.util.UUID, EntityPose>> it = lastPlayerSeen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<java.util.UUID, EntityPose> en = it.next();
                if (!observed.containsKey(en.getKey())) {
                    int npc = s.npcIdFor(en.getKey());
                    long t = s.mediaMillis();
                    s.emit(new TimelineEvent.PlayerLeave(t, npc, 1));
                    s.unmarkEntitySpawned(en.getKey());
                    forgetVitals(en.getKey());
                    it.remove();
                    inRegion.remove(en.getKey());
                }
            }
        }
        // Tell the background diff scanner whether anyone is around to see
        // changes (idle scans drop to ~1/sec; paused entirely while lagging).
        try {
            plugin.recordingManager().regionDiffRecorder().setAudienceNearby(seenPlayer);
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
    }

    /** Detect and emit POSE / SNEAK_SPRINT for the entity's current stance. */
    private void captureStance(RecordingSession s, UUID uuid, Entity e) {
        // Only living entities have pose / flag metadata accessors. Non-living
        // entities (items, projectiles, vehicles) must be excluded, otherwise
        // sending pose metadata to them on replay causes Network Protocol Errors.
        if (!(e instanceof org.bukkit.entity.LivingEntity le)) return;
        int pose = 0; // STANDING
        int flags = 0;
        if (le.isGliding()) {
            pose = 1; // FALL_FLYING
        } else if (le.isSwimming() || e.isInWater()) {
            pose = 3; // SWIMMING
        }
        if (le.isSneaking()) {
            flags |= FLAG_CROUCHED;
            if (pose == 0) pose = 5; // CROUCHING
        }
        if (le.isInWater() || le.isSwimming()) {
            flags |= FLAG_SWIMMING;
        }
        if (e instanceof Player p && p.isSprinting()) {
            flags |= FLAG_SPRINTING;
        }

        int npc = s.npcIdFor(uuid);
        Integer prevPose = lastPose.get(uuid);
        if (prevPose == null || prevPose != pose) {
            lastPose.put(uuid, pose);
            s.emit(new TimelineEvent.Pose(s.mediaMillis(), npc, pose));
        }
        Integer prevFlags = lastFlags.get(uuid);
        if (prevFlags == null || prevFlags != flags) {
            lastFlags.put(uuid, flags);
            s.emit(new TimelineEvent.SneakSprint(s.mediaMillis(), npc, flags));
        }
    }

    private static boolean sameRot(Rotation a, Rotation b) {
        return Math.abs(a.yaw() - b.yaw()) < 0.5f && Math.abs(a.pitch() - b.pitch()) < 0.5f;
    }

    // --- First-person spectate capture (vitals + full inventory) -----------

    private record Vitals(float health, int food, float saturation) {}
    private final Map<UUID, Vitals> lastVitals = new HashMap<>();
    private final Map<UUID, byte[][]> lastInventoryBytes = new HashMap<>();
    private final Map<UUID, Integer> lastGameMode = new HashMap<>();
    private final Map<UUID, Integer> lastHeldSlot = new HashMap<>();

    /**
     * Records a tracked player's vitals, gamemode, held hotbar slot (on
     * change) and full inventory (on change, or once as a baseline at first
     * observation) so playback can spectate them in first person with the
     * same health/hunger/items/mode/held-item.
     */
    private void capturePlayerState(RecordingSession s, Player p, UUID uuid, boolean baseline) {
        float health = (float) p.getHealth();
        int food = p.getFoodLevel();
        float sat = p.getSaturation();
        Vitals prev = lastVitals.get(uuid);
        if (prev == null || prev.health() != health || prev.food() != food || prev.saturation() != sat) {
            lastVitals.put(uuid, new Vitals(health, food, sat));
            s.emit(new TimelineEvent.PlayerVitals(s.mediaMillis(), s.npcIdFor(uuid), health, food, sat));
        }
        int mode;
        try {
            mode = p.getGameMode().getValue();
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
            mode = 0;
        }
        Integer prevMode = lastGameMode.get(uuid);
        if (prevMode == null || prevMode != mode) {
            lastGameMode.put(uuid, mode);
            s.emit(new TimelineEvent.GameMode(s.mediaMillis(), s.npcIdFor(uuid), mode));
        }
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int held = 0;
        try {
            held = inv.getHeldItemSlot();
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
        }
        Integer prevHeld = lastHeldSlot.get(uuid);
        if (prevHeld == null || prevHeld != held) {
            lastHeldSlot.put(uuid, held);
            s.emit(new TimelineEvent.HeldSlot(s.mediaMillis(), s.npcIdFor(uuid), held));
        }
        byte[][] current = serializeInventory(inv);
        if (baseline || inventoryChanged(uuid, current)) {
            s.emit(new TimelineEvent.PlayerInventory(s.mediaMillis(), s.npcIdFor(uuid),
                    current));
            lastInventoryBytes.put(uuid, current);
        }
    }

    private boolean inventoryChanged(UUID uuid, byte[][] current) {
        byte[][] prev = lastInventoryBytes.get(uuid);
        if (prev == null) return true;
        if (prev.length != current.length) return true;
        for (int i = 0; i < current.length; i++) {
            if (!java.util.Arrays.equals(prev[i], current[i])) return true;
        }
        return false;
    }

    /**
     * 41-slot layout: [0..35] main inventory ({@code getContents} order),
     * [36] boots, [37] leggings, [38] chestplate, [39] helmet, [40] offhand.
     * Each slot is an ItemStack NBT blob (empty = air).
     */
    public static byte[][] serializeInventory(org.bukkit.inventory.PlayerInventory inv) {
        byte[][] out = new byte[41][];
        org.bukkit.inventory.ItemStack[] main = inv.getContents();
        if (main != null) {
            for (int i = 0; i < 36 && i < main.length; i++) {
                out[i] = EquipmentRecorder.serializeItem(main[i]);
            }
        }
        org.bukkit.inventory.ItemStack[] armor = inv.getArmorContents();
        if (armor != null) {
            for (int i = 0; i < 4 && i < armor.length; i++) {
                out[36 + i] = EquipmentRecorder.serializeItem(armor[i]);
            }
        }
        out[40] = EquipmentRecorder.serializeItem(inv.getItemInOffHand());
        return out;
    }

    /** Drop a tracked player's vitals baseline when they leave the region. */
    public void forgetVitals(UUID uuid) {
        lastVitals.remove(uuid);
        lastInventoryBytes.remove(uuid);
        lastGameMode.remove(uuid);
        lastHeldSlot.remove(uuid);
    }

    /** Clear per-entity state when a recording starts. */
    public void reset() {
        lastKnown.clear();
        lastPose.clear();
        lastFlags.clear();
        lastVel.clear();
        lastPlayerSeen.clear();
        lastVitals.clear();
        lastInventoryBytes.clear();
        lastGameMode.clear();
        lastHeldSlot.clear();
        inRegion.clear();
    }
}