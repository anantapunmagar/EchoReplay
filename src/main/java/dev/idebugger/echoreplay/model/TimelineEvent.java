package dev.idebugger.echoreplay.model;

import java.util.List;
import java.util.UUID;

/**
 * Base interface for every timeline event. Events are timestamped with
 * millisecond-offset from the start of the recording (monotonic, non-decreasing).
 */
public sealed interface TimelineEvent
        permits TimelineEvent.KeepAlive, TimelineEvent.BlockSet, TimelineEvent.BlockBreakAnim,
        TimelineEvent.MultiBlock, TimelineEvent.PlayerSpawn, TimelineEvent.PlayerLeave,
        TimelineEvent.EntitySpawn, TimelineEvent.EntityLeave, TimelineEvent.Move,
        TimelineEvent.Velocity, TimelineEvent.Animation, TimelineEvent.Metadata,
        TimelineEvent.Equipment, TimelineEvent.Pose, TimelineEvent.Damage, TimelineEvent.Death,
        TimelineEvent.SneakSprint, TimelineEvent.Mount, TimelineEvent.Sound, TimelineEvent.Particle,
        TimelineEvent.Chat, TimelineEvent.WorldTime, TimelineEvent.Weather, TimelineEvent.Explosion,
        TimelineEvent.ItemUse, TimelineEvent.Teleport, TimelineEvent.Effect, TimelineEvent.CustomName,
        TimelineEvent.Marker, TimelineEvent.EntityStatus, TimelineEvent.PlayerVitals,
        TimelineEvent.PlayerInventory, TimelineEvent.GameMode, TimelineEvent.HeldSlot {

    long tickMillis();

    record KeepAlive(long tickMillis) implements TimelineEvent {}
    record BlockSet(long tickMillis, BlockPos pos, int paletteIndex, byte[] nbt) implements TimelineEvent {}
    record BlockBreakAnim(long tickMillis, BlockPos pos, int breakerNpcId, int stage) implements TimelineEvent {}
    record MultiBlock(long tickMillis, List<BlockSet> blocks) implements TimelineEvent {}
    record PlayerSpawn(long tickMillis, int npcId, UUID uuid, String name, PlayerSkin skin,
                       Vec3d pos, Rotation rot, List<byte[]> equipment, byte[] metadata) implements TimelineEvent {}
    record PlayerLeave(long tickMillis, int npcId, int reason) implements TimelineEvent {}
    record EntitySpawn(long tickMillis, int npcId, UUID uuid, String typeKey, Vec3d pos,
                       Rotation rot, byte[] metadata) implements TimelineEvent {}
    record EntityLeave(long tickMillis, int npcId) implements TimelineEvent {}
    record Move(long tickMillis, int npcId, Vec3d pos, Rotation rot, boolean onGround) implements TimelineEvent {}
    record Velocity(long tickMillis, int npcId, Vec3d vel) implements TimelineEvent {}
    record Animation(long tickMillis, int npcId, int anim) implements TimelineEvent {}
    record Metadata(long tickMillis, int npcId, byte[] raw) implements TimelineEvent {}
    record Equipment(long tickMillis, int npcId, int slot, byte[] item) implements TimelineEvent {}
    record Pose(long tickMillis, int npcId, int pose) implements TimelineEvent {}
    record Damage(long tickMillis, int npcId, String source, double amount, int animation) implements TimelineEvent {}
    record Death(long tickMillis, int npcId) implements TimelineEvent {}
    record SneakSprint(long tickMillis, int npcId, int flags) implements TimelineEvent {}
    record Mount(long tickMillis, int npcId, int vehicleNpcId) implements TimelineEvent {}
    record Sound(long tickMillis, String key, String category, Vec3d pos, float volume, float pitch) implements TimelineEvent {}
    record Particle(long tickMillis, String particleKey, Vec3d pos, float dx, float dy, float dz,
                    float speed, int count) implements TimelineEvent {}
    record Chat(long tickMillis, int npcId, String json) implements TimelineEvent {}
    record WorldTime(long tickMillis, long time, boolean cycling) implements TimelineEvent {}
    record Weather(long tickMillis, int rainStrength, int thunderStrength) implements TimelineEvent {}
    record Explosion(long tickMillis, Vec3d pos, float power) implements TimelineEvent {}
    // Note: power <= 0 marks a damageless burst (wind charge in old takes):
    // playback renders wind-burst sound+gust instead of generic explode.
    record ItemUse(long tickMillis, int npcId, int hand, boolean started) implements TimelineEvent {}
    record Teleport(long tickMillis, int npcId, Vec3d pos, Rotation rot) implements TimelineEvent {}
    record Effect(long tickMillis, int npcId, boolean add, String effectKey, byte[] data) implements TimelineEvent {}
    record CustomName(long tickMillis, int npcId, String componentJson) implements TimelineEvent {}
    record Marker(long tickMillis, String name) implements TimelineEvent {}
    record EntityStatus(long tickMillis, int npcId, byte status) implements TimelineEvent {}
    /** Recorded player vitals (first-person spectate): health, food level, saturation. */
    record PlayerVitals(long tickMillis, int npcId, float health, int foodLevel, float saturation) implements TimelineEvent {}
    /**
     * Full recorded player inventory for first-person spectate.
     * Slot layout: [0..35] main inventory (getContents order),
     * [36] boots, [37] leggings, [38] chestplate, [39] helmet, [40] offhand.
     * Each entry is an ItemStack NBT blob (empty = air).
     */
    record PlayerInventory(long tickMillis, int npcId, byte[][] slots) implements TimelineEvent {}
    /** Recorded player gamemode (Bukkit GameMode value: 0 survival, 1 creative, 2 adventure, 3 spectator). */
    record GameMode(long tickMillis, int npcId, int mode) implements TimelineEvent {}
    /** Recorded player selected hotbar slot (0-8). */
    record HeldSlot(long tickMillis, int npcId, int slot) implements TimelineEvent {}
}
