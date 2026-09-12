package dev.idebugger.echoreplay.storage;

import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.model.PlayerSkin;
import dev.idebugger.echoreplay.model.Rotation;
import dev.idebugger.echoreplay.model.TimelineEvent;
import dev.idebugger.echoreplay.model.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encode/decode round-trip for every timeline event type. Byte[] payloads
 * defeat record equals(), so stability is asserted as re-encode equality
 * plus spot-checked decoded fields.
 */
class TimelineCodecTest {

    private static final List<String> PALETTE = List.of("minecraft:air", "minecraft:stone");

    private static TimelineEvent roundTrip(TimelineEvent e) throws Exception {
        byte[] body = TimelineCodec.encodeBody(e, PALETTE);
        TimelineEvent back = TimelineCodec.decode(body, TimelineCodec.typeId(e), e.tickMillis(), PALETTE);
        assertArrayEquals(body, TimelineCodec.encodeBody(back, PALETTE),
                "re-encode mismatch for " + e.getClass().getSimpleName());
        return back;
    }

    @Test
    void move() throws Exception {
        var back = (TimelineEvent.Move) roundTrip(new TimelineEvent.Move(
                1234L, 7, new Vec3d(10.5, 64.0, -3.25), new Rotation(12.5f, 90.0f, 91.0f), true));
        assertEquals(7, back.npcId());
        assertEquals(10.5, back.pos().x());
        assertEquals(91.0f, back.rot().headYaw());
        assertTrue(back.onGround());
    }

    @Test
    void playerSpawn() throws Exception {
        var back = (TimelineEvent.PlayerSpawn) roundTrip(new TimelineEvent.PlayerSpawn(
                0L, 1, UUID.fromString("7091fad4-3e43-4c0e-ad4c-05d9a3c4d61d"), "Intellix",
                new PlayerSkin("val", "sig"), new Vec3d(1, 2, 3),
                new Rotation(0f, 180f, 180f), List.of(new byte[]{1, 2}, new byte[0]), new byte[]{9}));
        assertEquals("Intellix", back.name());
        assertEquals(180f, back.rot().yaw());
        assertEquals(2, back.equipment().size());
    }

    @Test
    void blocks() throws Exception {
        roundTrip(new TimelineEvent.BlockSet(10L, new BlockPos(1, -64, 300), 1, new byte[]{5}));
        roundTrip(new TimelineEvent.BlockBreakAnim(11L, new BlockPos(0, 0, 0), 3, 7));
        roundTrip(new TimelineEvent.MultiBlock(12L, List.of(
                new TimelineEvent.BlockSet(12L, new BlockPos(1, 2, 3), 0, null))));
    }

    @Test
    void entities() throws Exception {
        roundTrip(new TimelineEvent.EntitySpawn(5L, 2, UUID.randomUUID(), "minecraft:zombie",
                new Vec3d(0, 0, 0), new Rotation(0f, 0f, 0f), new byte[0]));
        roundTrip(new TimelineEvent.PlayerLeave(6L, 2, 1));
        roundTrip(new TimelineEvent.EntityLeave(6L, 2));
        roundTrip(new TimelineEvent.Velocity(7L, 2, new Vec3d(0.1, -0.5, 0.3)));
        roundTrip(new TimelineEvent.Animation(8L, 2, 1));
        roundTrip(new TimelineEvent.Metadata(9L, 2, new byte[]{1, 2, 3}));
        roundTrip(new TimelineEvent.Equipment(10L, 2, 0, new byte[]{4, 5}));
        roundTrip(new TimelineEvent.Pose(11L, 2, 5));
        roundTrip(new TimelineEvent.Damage(12L, 2, "mob", 3.5, 2));
        roundTrip(new TimelineEvent.Death(13L, 2));
        roundTrip(new TimelineEvent.SneakSprint(14L, 2, 10));
        roundTrip(new TimelineEvent.Mount(15L, 2, 3));
        roundTrip(new TimelineEvent.EntityStatus(16L, 2, (byte) 3));
    }

    @Test
    void worldAndChat() throws Exception {
        roundTrip(new TimelineEvent.Sound(20L, "entity.player.levelup", "players",
                new Vec3d(1, 2, 3), 1f, 0.5f));
        roundTrip(new TimelineEvent.Particle(21L, "minecraft:flame", new Vec3d(1, 2, 3),
                0.1f, 0.2f, 0.3f, 1f, 10));
        var chat = (TimelineEvent.Chat) roundTrip(
                new TimelineEvent.Chat(22L, 3, "{\"text\":\"hi\"}"));
        assertEquals("{\"text\":\"hi\"}", chat.json());
        roundTrip(new TimelineEvent.WorldTime(23L, 6000L, true));
        roundTrip(new TimelineEvent.Weather(24L, 1, 0));
        roundTrip(new TimelineEvent.Explosion(25L, new Vec3d(1, 2, 3), 4f));
        roundTrip(new TimelineEvent.ItemUse(26L, 3, 0, true));
        roundTrip(new TimelineEvent.Teleport(27L, 3, new Vec3d(9, 9, 9),
                new Rotation(0f, 270f, 270f)));
        roundTrip(new TimelineEvent.Effect(28L, 3, true, "minecraft:speed", new byte[]{1}));
        roundTrip(new TimelineEvent.CustomName(29L, 3, "{\"text\":\"bob\"}"));
        roundTrip(new TimelineEvent.Marker(30L, "m1"));
        roundTrip(new TimelineEvent.KeepAlive(31L));
    }

    @Test
    void spectateState() throws Exception {
        var v = (TimelineEvent.PlayerVitals) roundTrip(
                new TimelineEvent.PlayerVitals(40L, 4, 20f, 20, 5f));
        assertEquals(20f, v.health());
        var inv = (TimelineEvent.PlayerInventory) roundTrip(
                new TimelineEvent.PlayerInventory(41L, 4, new byte[][]{new byte[]{1}, new byte[0]}));
        assertEquals(2, inv.slots().length);
        var g = (TimelineEvent.GameMode) roundTrip(new TimelineEvent.GameMode(42L, 4, 1));
        assertEquals(1, g.mode());
        var h = (TimelineEvent.HeldSlot) roundTrip(new TimelineEvent.HeldSlot(43L, 4, 5));
        assertEquals(5, h.slot());
    }

    @Test
    void unknownTypeDecodesNull() throws Exception {
        assertEquals(null, TimelineCodec.decode(new byte[0], 99, 0L, PALETTE));
    }
}
