package dev.idebugger.echoreplay.storage;

/**
 * Constants for the .echoreplay.gz binary format (v1).
 *
 * Layout (all little-endian): single gzip stream.
 *   u8[4] magic 'E' 'C' 'H' 'O'
 *   u16   format  = 1
 *   u16   flags
 *   then payload sections, each:
 *     u32 type
 *     u32 length
 *     byte[length] body
 */
public final class RecordingFormat {

    private RecordingFormat() {}

    public static final byte[] MAGIC = {'E', 'C', 'H', 'O'};
    public static final int FORMAT = 1;

    public static final int SEC_META = 1;
    public static final int SEC_PALETTE = 2;
    public static final int SEC_BLOCKS = 3;
    public static final int SEC_BLOCK_NBT = 4;
    public static final int SEC_ENTITIES = 5;
    public static final int SEC_TIMELINE = 6;

    // Timeline event type ids (stable — never reorder).
    public static final int EV_KEEP_ALIVE = 0;
    public static final int EV_BLOCK_SET = 1;
    public static final int EV_BLOCK_BREAK_ANIM = 2;
    public static final int EV_MULTI_BLOCK = 3;
    public static final int EV_PLAYER_SPAWN = 4;
    public static final int EV_PLAYER_LEAVE = 5;
    public static final int EV_ENTITY_SPAWN = 6;
    public static final int EV_ENTITY_LEAVE = 7;
    public static final int EV_MOVE = 8;
    public static final int EV_VELOCITY = 9;
    public static final int EV_ANIMATION = 10;
    public static final int EV_METADATA = 11;
    public static final int EV_EQUIPMENT = 12;
    public static final int EV_POSE = 13;
    public static final int EV_DAMAGE = 14;
    public static final int EV_DEATH = 15;
    public static final int EV_SNEAK_SPRINT = 16;
    public static final int EV_MOUNT = 17;
    public static final int EV_SOUND = 18;
    public static final int EV_PARTICLE = 19;
    public static final int EV_CHAT = 20;
    public static final int EV_WORLD_TIME = 21;
    public static final int EV_WEATHER = 22;
    public static final int EV_EXPLOSION = 23;
    public static final int EV_ITEM_USE = 24;
    public static final int EV_TELEPORT = 25;
    public static final int EV_EFFECT = 26;
    public static final int EV_CUSTOM_NAME = 27;
    public static final int EV_MARKER = 28;
    public static final int EV_ENTITY_STATUS = 29;
    public static final int EV_PLAYER_VITALS = 30;
    public static final int EV_PLAYER_INVENTORY = 31;
    public static final int EV_PLAYER_GAMEMODE = 32;
    public static final int EV_PLAYER_HELD_SLOT = 33;
}
