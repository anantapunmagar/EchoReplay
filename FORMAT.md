# EchoReplay recording file format (`.echoreplay.gz`, v1)

All integers are **little-endian**. Strings are `u16` byte-length + UTF-8 bytes.

## Container

```
offset  size  field
0       4     magic  = "ECHO"
4       2     format = 1
6       2     flags  = 0 (reserved)
```

Then a sequence of **sections** until EOF:

```
u32  section type
u32  body length (bytes)
body
```

Section types:

| type | name | body |
|------|------|------|
| 1 | META | see below |
| 2 | PALETTE | `u32 count` + `count` × string |
| 3 | BLOCKS | `u32 sizeX, u32 sizeY, u32 sizeZ, u8 bits`, `u32 longCount`, `longCount` × u64 |
| 4 | BLOCK_NBT | packed tile-NBT blob |
| 5 | ENTITIES | reserved (currently empty body) |
| 6 | TIMELINE | one or more event entries (see below) |

Unknown section types are skipped (forward compatibility).

### META body

```
string  serverVersion        e.g. "1.21.5"
string  worldUuid            "123e4567-..."
string  worldName            e.g. "world"
i32     minX, i32 minY, i32 minZ, i32 maxX, i32 maxY, i32 maxZ
i64     epochMillis          wall-clock time the recording started
string  recorderUuid
string  recorderName
i64     durationMillis       total recorded media time
string  name                 recording name
```

### BLOCKS body

`sizeX × sizeY × sizeZ` palette indices stored in a dense grid, **row-major
with x fastest**: index `i = (dy * sizeZ + dz) * sizeX + dx`, where
`(dx, dy, dz)` are offsets from the cuboid min corner. Indices are packed
`bits`-per-entry, least significant first, into u64 words (max 32 entries per
word). `bits = ceil(log2(paletteSize))`.

Grid coordinates map to world blocks as
`worldX = minX + dx` (same for Y/Z).

### BLOCK_NBT body

```
u32 sizeX, u32 sizeY, u32 sizeZ     (dimensions, informational)
u32 count
count × {
  i32 relX, i32 relY, i32 relZ      (offsets from cuboid min)
  u32 nbtLen
  u8  nbt[nbtLen]                    (SnbtBytes-encoded BlockState NBT)
}
```

### PALETTE body

`u32 count` + `count` × string, each string a full
`minecraft:<block>[props]` state string (Bukkit `BlockData#getAsString`).
Index 0 is always `minecraft:air`.

### TIMELINE entries

Each entry:

```
u64  tickMillis   media time since recording start
u8   type         event type id (below)
u16  bodyLen
u8   body[bodyLen]
```

The reader tolerates multiple TIMELINE sections (they are concatenated and
sorted by `tickMillis` before playback).

## Event type ids

| id | event | body |
|----|-------|------|
| 0 | KEEP_ALIVE | — |
| 1 | BLOCK_SET | `i16 relX, i16 relY, i16 relZ, i32 paletteIndex, i32 nbtLen, u8 nbt[nbtLen]` |
| 2 | BLOCK_BREAK_ANIM | `i16 relX, i16 relY, i16 relZ, i32 breakerNpcId, u8 stage` |
| 3 | MULTI_BLOCK | `i32 n` + `n` × BLOCK_SET body |
| 4 | PLAYER_SPAWN | `i32 npcId, u64 uuidHi, u64 uuidLo, string name, string skinValue, string skinSig, f64 x,y,z, f32 pitch,yaw,headYaw, i32 eqCount, eqCount × {i32 len, u8 bytes}, i32 mdLen, u8 md[mdLen]` |
| 5 | PLAYER_LEAVE | `i32 npcId, u8 reason` |
| 6 | ENTITY_SPAWN | `i32 npcId, u64 uuidHi, u64 uuidLo, string typeKey ("minecraft:zombie"), f64 x,y,z, f32 pitch,yaw,headYaw, i32 mdLen, u8 md[mdLen]` |
| 7 | ENTITY_LEAVE | `i32 npcId` |
| 8 | MOVE | `i32 npcId, f64 x,y,z, f32 pitch,yaw,headYaw, u8 onGround` |
| 9 | VELOCITY | `i32 npcId, f64 x,y,z` |
| 10 | ANIMATION | `i32 npcId, i32 anim` (0 = swing main, 1 = swing off) |
| 11 | METADATA | `i32 npcId, i32 len, u8 raw[len]` (raw entity-metadata patch; not replayed) |
| 12 | EQUIPMENT | `i32 npcId, i32 slot (0=main .. 5=helmet), i32 len, u8 bytes[len]` (ItemStack NBT) |
| 13 | POSE | `i32 npcId, i32 poseId` (EntityPose ordinal) |
| 14 | DAMAGE | `i32 npcId, string source, f64 amount, i32 animation` |
| 15 | DEATH | `i32 npcId` |
| 16 | SNEAK_SPRINT | `i32 npcId, i32 flags` (entity-flag byte) |
| 17 | MOUNT | `i32 npcId, i32 vehicleNpcId` (recorded; not replayed) |
| 18 | SOUND | `string key, string category, f64 x,y,z, f32 volume, f32 pitch` |
| 19 | PARTICLE | `string particleKey, f64 x,y,z, f32 dx,dy,dz, f32 speed, i32 count` |
| 20 | CHAT | `i32 npcId, string json` |
| 21 | WORLD_TIME | `i64 time, u8 cycling` (emitted at most once per in-game second) |
| 22 | WEATHER | `i32 rainStrength, i32 thunderStrength` (0/1 flags; emitted on change) |
| 23 | EXPLOSION | `f64 x,y,z, f32 power` |
| 24 | ITEM_USE | `i32 npcId, i32 hand, u8 started` |
| 25 | TELEPORT | `i32 npcId, f64 x,y,z, f32 pitch,yaw,headYaw` |
| 26 | EFFECT | `i32 npcId, u8 add, string effectKey, i32 len, u8 data[len]` |
| 27 | CUSTOM_NAME | `i32 npcId, string componentJson` |
| 28 | MARKER | `string name` |
| 29 | ENTITY_STATUS | `i32 npcId, u8 status` |
| 30 | PLAYER_VITALS | `i32 npcId, f32 health, i8 foodLevel, f32 saturation` |
| 31 | PLAYER_INVENTORY | `i32 npcId, u16 slotCount (41)`, `slotCount × {i32 len, u8 bytes[len]}` (ItemStack NBT; empty = air) |
| 32 | GAME_MODE | `i32 npcId, u8 mode` (Bukkit GameMode value: 0 survival, 1 creative, 2 adventure, 3 spectator) |
| 33 | HELD_SLOT | `i32 npcId, u8 slot` (selected hotbar slot 0-8) |

`PLAYER_INVENTORY` slot layout: `[0..35]` main inventory (`getContents`
order, hotbar first), `[36]` boots, `[37]` leggings, `[38]` chestplate,
`[39]` helmet, `[40]` offhand. `PLAYER_VITALS`, `PLAYER_INVENTORY`, `GAME_MODE` and `HELD_SLOT` are
only replayed onto a real player while they are first-person spectating that
recorded player (`/er spectate`); fakes ignore them. Recordings created with
older plugin versions simply do not contain these events (readers skip unknown
type ids, so new recordings still load on old builds minus the new data).

`npcId` values are session-local opaque integers assigned in first-seen order.

### Entity spawn metadata blob (PLAYER_SPAWN / ENTITY_SPAWN `md`)

```
u8 count
count × { u8 index, u8 kind, i32 value }
kind: 1 = INT, 2 = BYTE, 3 = ITEMSTACK (value = i32 len + bytes), 4 = BOOLEAN
```

Currently captured: baby-ness (index 15; BYTE for zombie-family, INT for
other ageables), slime/magma size (index 16, INT), firework item +
shot-at-angle.

## Coordinates

- Entity/player positions, sounds, particles, explosions: **absolute world
  coordinates** (f64).
- Block positions (BLOCK_SET, BLOCK_BREAK_ANIM, BLOCK_NBT): **relative to the
  cuboid min corner** (i16 / i32).
- Rotation: `pitch, yaw, headYaw` in degrees, Minecraft convention.

## Crash-safety checkpoints

While recording, the plugin also writes a **raw (uncompressed)** checkpoint
file `name.echoreplay.gz.partial` with the same header + section framing:

- header sections (META/BLOCKS/BLOCK_NBT/ENTITIES) once,
- repeated PALETTE + TIMELINE sections per flush (the **last** PALETTE wins,
  so every surviving event index stays resolvable).

Because it is not gzipped, a crash mid-write leaves a file whose *complete*
sections are still parseable. On next plugin start the checkpoint is turned
into a normal gzip recording (duration recomputed from the last surviving
event); if the final `.echoreplay.gz` already exists the stale checkpoint is
deleted.
