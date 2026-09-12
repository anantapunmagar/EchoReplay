# EchoReplay

**Server-side region replay for Paper 1.21+ — "ReplayMod, but it runs on the
server."** No client mod: anyone in the region (or who walks up to it) just
sees the replay happen, in their normal client.

Record a cuboid of world — terrain, entities, players (with skins and
equipment), block updates, chat, sounds, particles, explosions — and play it
back VCR-style: pause, resume, speed 0.25×–16×, seek by time or marker,
fast-forward/rewind. World mode rewrites the real terrain while playing and
restores it afterwards; virtual mode sends viewer-only packets and never
touches the world.

Built on [PacketEvents](https://github.com/retrooper/packetevents) (shaded
into the jar), so fake entities are pure server-to-client packets.

## Building

Requires **Java 21** and Maven. One shared source tree produces one jar per
Minecraft version:

```
mvn -DskipTests clean package
```

| artifact | Paper | PacketEvents |
|----------|-------|--------------|
| `1.21.5/target/echoreplay-1.21.5+1.1.4.jar` | 1.21.5 | 2.8.0 |
| `1.21.11/target/echoreplay-1.21.11+1.1.4.jar` | 1.21.11 | 2.13.0 |

## Commands

`/er` (aliases `/echoreplay`, `/replay`)

**Selection**
- `/er wand` — selection wand (material from `selection.wand-material`); left-click = pos1, right-click = pos2
- `/er pos1 [x y z]`, `/er pos2 [x y z]`
- `/er select <x1> <y1> <z1> <x2> <y2> <z2>`
- `/er expand <amt> [all|vert|up|down|north|south|west|east]`, `/er contract …`
- `/er shift <amt> <dir>`
- `/er selinfo`, `/er clear`
- Optional wireframe preview of your selection: `selection.outline-particles` (off by default)

**Recording**
- `/er record <name>` — snapshots the region, then records. Region is grown by
  `recording.margin-blocks` on every side (clamped to world height).
- `/er resume <name>` — continue a checkpointed take after a crash/restart
  (async incremental autosave every `recording.autosave-seconds` on the IO
  thread — never Netty, so saves can't disconnect players).
- `/er marker [name]` — place a seek target
- `/er stop` (or `/er save`) — finish and save
- `/er cancel` — abort and delete
- `/er status` — progress (sections, duration) while recording
- `/er stats` — recording/playback debug (buffered/committed/dropped events,
  rate limit, palette, checkpoint sizes, diff mode, fake ids, virtual blocks)
- While recording, a **crash-safety checkpoint** (`<name>.echoreplay.gz.partial`)
  is kept on disk; if the server dies, the next start recovers it as a real
  recording (at most `recording.flush-seconds` of events can be lost).

**Playback**
- `/er play <name> [virtual|world]`
- `/er pause`, `/er resume`
- `/er speed <0.25|0.5|1|2|4|8|16>`
- `/er seek <seconds|mm:ss|marker-name>`
- `/er ff [s]`, `/er rewind [s]` (default 10s)
- `/er stopplay`
- `/er control [on|off|toggle|status]` — hotbar VCR controls (pause, resume,
  stop, -10s, +10s, speed cycle, restart, leave, help); hotbar saved, locked
  against move/drop, restored on off, stop, leave, quit or server stop
- `/er watch` — join the running replay (state sync: entity snapshot + past
  block changes in virtual mode); `/er leave` to drop out
- `/er cam <entity-name|off>` — spectator-follow a recorded entity
- **`/er spectate <player-name>`** — become that recorded player in
  **first person**: their fake entity is removed and *you* take its place —
  same position, view, health, hunger and full inventory, driven by the
  recording. Requires a recording made with this version (it records vitals
  + full inventory). Spectate is damage-free (recorded health is applied
  cosmetically, never below 1 HP) and outlives the target's stumbles:
  - **Target dies** — you stay with them (watch the death, no damage) and
    snap to their respawn; their fake re-spawns for everyone else.
  - **Target leaves the region** (or logs out) — you're restored to your
    own position/items and told so; spectate is *paused*, and you're
    re-possessed automatically the moment they re-enter the region.
  - **`/er stopspectate`** — the only hard end: restores you and cancels any
    pending auto re-possess (it also overrides a paused spectate).
  Playback ending, seeking, or you logging out also restores you cleanly.
- Players inside `replay.auto-watch-radius` of the cuboid see the replay
  automatically. Viewers are forced to spectator (`replay.force-spectator`)
  and their gamemode is restored afterwards.

**Management**
- `/er list`, `/er info <name>`
- `/er delete <name>` — **two-step**: run it once, then `/er confirm`
- `/er rename <old> <new>`
- `/er border [on|off|toggle|status]` — per-viewer playback border particles
- `/er fps [on|off|toggle|status]` — FPS saving: trims replay particles
  (~1/4) and sounds (~1/2) just for you, never fully removed
- `/er privacy [on|off|toggle|status]` — opt out of recordings (exempt from
  takes, no REC bar, but no editing inside active recording zones either;
  disable/enforce server-wide via the `privacy` config section)

## Permissions

| node | default | grants |
|------|---------|--------|
| `echoreplay.use` | true | `list`, `info`, `status`, `cam`, `selinfo`, `clear` |
| `echoreplay.wand` | op | `wand` |
| `echoreplay.select` | op | `pos1`, `pos2`, `select`, `expand`, `contract`, `shift` |
| `echoreplay.record` | op | `record`, `stop`, `save`, `cancel`, `marker` |
| `echoreplay.play` | op | `play`, `watch` (also required for auto-watch), `spectate`, `stopspectate` |
| `echoreplay.play.*` | op | play any recording (wildcard parent) |
| `echoreplay.play.<name>` | op | play one recording (lowercase name; `*` = all) |
| `echoreplay.control` | op | `pause`, `resume`, `speed`, `seek`, `ff`, `rewind`, `stopplay`, `leave`, `control` |
| `echoreplay.delete` | op | `delete`, `rename`, `confirm` |
| `echoreplay.border` | true | `border` |
| `echoreplay.fps` | true | `fps` |
| `echoreplay.privacy` | true | `privacy` |
| `echoreplay.bypass-limits` | op | skip selection volume/span limits |

## How recording works

- **Snapshot** — the cuboid's initial state (palette-compressed dense grid +
  tile NBT) is captured across ticks with a 10 ms/time budget (secondary cap
  `recording.snapshot.blocks-per-tick`).
- **Event recorders** — block place/break/fluid/redstone/sign/explosion,
  entity join/leave/move/death, player equipment & sneak/sprint, chat, damage,
  fireworks, world time, teleports, plus per-player **vitals** (health/hunger/
  saturation) and **full-inventory** snapshots (both on change) that power
  first-person spectate.
- **Region diff** — a background thread rescans the *entire* cuboid on NMS
  chunk reads (`recording.scan-interval-ticks`, budget
  `recording.scan-ms-per-pass`) so even eventless changes (end crystals,
  cancelled breaks, plugin writes, portal formation) are captured. If NMS
  reflection cannot resolve on the running version, it degrades gracefully and
  the event recorders still cover the common cases.
- **Packets** — player movement (head vs body yaw are tracked separately),
  outgoing sound/particle packets inside the region (rate-limited by
  `recording.max-particles-per-second`).

Events are buffered and flushed to the checkpoint file every
`recording.flush-seconds`; the final `.echoreplay.gz` is written on an IO
thread via temp-file + atomic rename. See
[FORMAT.md](FORMAT.md) for the binary format.

## How playback works

- The recorded snapshot is streamed into the world over ticks (world mode) —
  never a single-tick write of the whole region. Before that, the *live*
  terrain is captured so stop/auto-end restores it exactly
  (`replay.backup-live-cuboid`).
- Fake entities are packet-only, with runtime ids allocated from a
  high-band (`Integer.MAX_VALUE − 1_000_000` and up) so they cannot collide
  with real entities.
- Every phase (snapshot, capture, seek-catchup, restore) has a per-tick CPU
  budget (`replay.phase-max-ms-per-tick`) — playback never freezes the server
  regardless of region size.
- World mode locks the cuboid against player/entity changes and cancels block
  physics inside it (`replay.physics-frozen`).
- `replay.drive-world-time` replays the recorded day/night cycle in world mode.
- Seeking is a full reset + budgeted fast-apply (silent: no chat/SFX/particle
  spam for the seeked span).
- Late joiners are synced to the current state (entity snapshot with skins and
  equipment, plus past block changes in virtual mode).

## Configuration

All keys are in `plugins/EchoReplay/config.yml` with comments and sane
defaults — selection limits, margin, capture switches, scan tuning, playback
speeds, SFX skip threshold, border style, storage directory.

## Files

- `plugins/EchoReplay/recordings/` — `.echoreplay.gz` files + `index.yml`
  (name → world, duration, bounds). The index is a cache kept in sync by the
  plugin: `/er list`/`/er info` read it, but playback does not need it. If you
  delete files manually, also drop their index entries (or delete `index.yml`)
  so the listings stay accurate.
- `plugins/EchoReplay/border_prefs.yml` — per-player border toggle
- `*.echoreplay.gz.partial` — crash-safety checkpoints (recovered on start)
