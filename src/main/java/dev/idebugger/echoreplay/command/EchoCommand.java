package dev.idebugger.echoreplay.command;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.replay.ReplaySession;
import dev.idebugger.echoreplay.model.BlockPos;
import dev.idebugger.echoreplay.record.RecordingManager;
import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.select.Selection;
import dev.idebugger.echoreplay.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The /echoreplay (/er, /replay) command tree.
 */
public final class EchoCommand implements CommandExecutor, TabCompleter {

    private final EchoReplay plugin;
    private static final NamespacedKey WAND_KEY =
            NamespacedKey.fromString("echoreplay:wand");
    /** Pending two-step delete: sender key -> recording name to delete. */
    private final Map<CommandSender, String> pendingDeletes = new HashMap<>();

    public EchoCommand(EchoReplay plugin) {
        this.plugin = plugin;
    }

    /** Central permission gate. Node per subcommand (see plugin.yml). */
    private boolean checkPerm(CommandSender s, String node) {
        if (s.hasPermission(node)) return true;
        s.sendMessage(Text.mm("<red>No permission (" + node + ").</red>"));
        return false;
    }

    /**
     * Per-recording playback gate. Grants when the sender has any of:
     * <ul>
     *   <li>{@code *} (console / full wildcard via permission plugin)</li>
     *   <li>{@code echoreplay.play.*} (all recordings)</li>
     *   <li>{@code echoreplay.play.<name>} (this recording, lowercase)</li>
     *   <li>{@code echoreplay.play} (legacy: all recordings)</li>
     * </ul>
     */
    static boolean canPlay(CommandSender s, String recordingName) {
        if (s.hasPermission("*")) return true;
        if (s.hasPermission("echoreplay.play.*")) return true;
        if (recordingName != null && !recordingName.isEmpty()) {
            String key = recordingName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
            if (!key.isEmpty() && s.hasPermission("echoreplay.play." + key)) return true;
        }
        return s.hasPermission("echoreplay.play");
    }

    private boolean checkPlayPerm(CommandSender s, String recordingName) {
        if (canPlay(s, recordingName)) return true;
        s.sendMessage(Text.mm("<red>No permission (echoreplay.play." + recordingName.toLowerCase()
                + " / echoreplay.play.*).</red>"));
        return false;
    }

    /** Wand item from {@code selection.wand-material} (default GOLDEN_AXE). */
    private Material wandMaterial() {
        try {
            Material m = Material.matchMaterial(
                    plugin.cfg().getString("selection.wand-material", "GOLDEN_AXE"));
            return (m != null && m.isItem()) ? m : Material.GOLDEN_AXE;
        } catch (Exception e) {
            return Material.GOLDEN_AXE;
        }
    }

    public void register() {
        for (String c : new String[]{"echoreplay", "er", "replay"}) {
            org.bukkit.command.PluginCommand cmd = plugin.getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "wand" -> wand(sender);
            case "pos1" -> pos1(sender, args);
            case "pos2" -> pos2(sender, args);
            case "select" -> select(sender, args);
            case "expand" -> expand(sender, args, true);
            case "contract" -> expand(sender, args, false);
            case "shift" -> shift(sender, args);
            case "selinfo" -> selinfo(sender);
            case "clear" -> clear(sender);
            case "record", "rec" -> record(sender, args);
            case "stop" -> stop(sender);
            case "cancel" -> cancel(sender);
            case "save" -> save(sender);
            case "status" -> status(sender);
            case "stats" -> stats(sender);
            case "play" -> play(sender, args);
            case "pause" -> pause(sender);
            case "resume" -> resume(sender, args);
            case "speed" -> speed(sender, args);
            case "seek" -> seek(sender, args);
            case "ff" -> ff(sender, args);
            case "rewind" -> rewind(sender, args);
            case "stopplay" -> stopplay(sender);
            case "leave" -> leave(sender);
            case "control" -> control(sender, args);
            case "fps" -> fps(sender, args);
            case "privacy" -> privacyCmd(sender, args);
            case "watch" -> watch(sender);
            case "cam" -> cam(sender, args);
            case "spectate" -> spectate(sender, args);
            case "stopspectate" -> stopspectate(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "delete" -> delete(sender, args);
            case "rename" -> rename(sender, args);
            case "confirm" -> confirm(sender);
            case "marker" -> marker(sender, args);
            case "border" -> border(sender, args);
            case "debug" -> debug(sender, args);
            case "version" -> version(sender);
            case "reload" -> reload(sender);
            default -> {
                sender.sendMessage(Text.mm("<red>Unknown subcommand '" + sub + "'. Use /er for help.</red>"));
            }
        }
        return true;
    }

    private void requirePlayer(CommandSender s, java.util.function.Consumer<Player> c) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Text.mm("<red>This command must be run by a player.</red>"));
            return;
        }
        c.accept(p);
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(Text.mm("""
            <gold>EchoReplay — server-side region replay</gold>
            <gray>Selection:</gray> <yellow>/er wand, pos1, pos2, select, expand, contract, shift, selinfo, clear</yellow>
            <gray>Record:</gray> <yellow>/er record <name>, resume <name>, stop, cancel, save, status, stats, marker <name></yellow>
            <gray>Play:</gray> <yellow>/er play <name> [virtual|world], pause, resume, speed <x>, seek <s|mm:ss>, ff [s], rewind [s], stopplay, leave, watch <name>, cam <name></yellow>
            <gray>Controls:</gray> <yellow>/er control [on|off|toggle|status] (hotbar VCR: stop, help, restart, -10s, pause/resume, +10s, speed, spectate menu, leave)</yellow>
            <gray>First-person:</gray> <yellow>/er spectate <player>, stopspectate (become a recorded player: their view, position, health, hunger and inventory)</yellow>
            <gray>Manage:</gray> <yellow>/er list, info <name>, delete <name>, rename <old> <new></yellow>
            <gray>Border:</gray> <yellow>/er border [on|off|toggle|status]</yellow>
            <gray>FPS:</gray> <yellow>/er fps [on|off|toggle|status] (trim replay particles/sounds for you)</yellow>
            <gray>Privacy:</gray> <yellow>/er privacy [on|off|toggle|status] (opt out of recordings)</yellow>
            <gray>Debug:</gray> <yellow>/er debug nms, stats</yellow>
            <gray>System:</gray> <yellow>/er version, reload</yellow>
            <gray>Play permissions:</gray> <yellow>echoreplay.play.* = all, echoreplay.play.&lt;name&gt; = one recording (* = all)</yellow>
            """));
    }

    private void wand(CommandSender s) {
        if (!checkPerm(s, "echoreplay.wand")) return;
        requirePlayer(s, p -> {
            ItemStack wand = new ItemStack(wandMaterial());
            ItemMeta meta = wand.getItemMeta();
            meta.displayName(Text.mm("<gradient:#7af:#fff>Echo Wand</gradient>"));
            meta.getPersistentDataContainer().set(WAND_KEY, PersistentDataType.INTEGER, 1);
            wand.setItemMeta(meta);
            p.getInventory().addItem(wand);
            p.sendMessage(Text.mm("<gray>Here is your Echo Wand. Left=pos1, Right=pos2.</gray>"));
        });
    }

    private void pos1(CommandSender s, String[] args) {
        requirePos(s, args, true);
    }

    private void pos2(CommandSender s, String[] args) {
        requirePos(s, args, false);
    }

    private void requirePos(CommandSender s, String[] args, boolean first) {
        if (!checkPerm(s, "echoreplay.select")) return;
        requirePlayer(s, p -> {
            Selection sel = plugin.selectionManager().get(p);
            BlockPos pos;
            if (args.length >= 4) {
                try {
                    pos = new BlockPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                } catch (NumberFormatException e) {
                    p.sendMessage(Text.mm("<red>Invalid coordinates.</red>"));
                    return;
                }
            } else {
                var loc = p.getLocation();
                pos = new BlockPos(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            }
            if (first) sel.setPos1(pos);
            else sel.setPos2(pos);
            p.sendMessage(Text.mm("<gray>" + (first ? "Pos1" : "Pos2") + " set to (" + pos.x() + ", " + pos.y() + ", " + pos.z() + ").</gray>"));
        });
    }

    private void select(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.select")) return;
        requirePlayer(s, p -> {
            if (args.length < 7) {
                p.sendMessage(Text.mm("<red>Usage: /er select <x1> <y1> <z1> <x2> <y2> <z2></red>"));
                return;
            }
            try {
                int x1 = Integer.parseInt(args[1]), y1 = Integer.parseInt(args[2]), z1 = Integer.parseInt(args[3]);
                int x2 = Integer.parseInt(args[4]), y2 = Integer.parseInt(args[5]), z2 = Integer.parseInt(args[6]);
                Selection sel = plugin.selectionManager().get(p);
                sel.setPos1(new BlockPos(x1, y1, z1));
                sel.setPos2(new BlockPos(x2, y2, z2));
                Cuboid c = sel.cuboid();
                p.sendMessage(Text.mm("<gray>Selection set: " + c.volume() + " blocks.</gray>"));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Invalid coordinates.</red>"));
            }
        });
    }

    private void expand(CommandSender s, String[] args, boolean grow) {
        if (!checkPerm(s, "echoreplay.select")) return;
        requirePlayer(s, p -> {
            if (args.length < 2) {
                p.sendMessage(Text.mm("<red>Usage: /er expand <amount> [dir]</red>"));
                return;
            }
            try {
                int amt = Integer.parseInt(args[1]);
                String dir = args.length > 2 ? args[2].toLowerCase() : "all";
                Selection sel = plugin.selectionManager().get(p);
                if (!sel.isComplete()) {
                    p.sendMessage(Text.mm("<red>Selection incomplete.</red>"));
                    return;
                }
                Cuboid c = sel.cuboid();
                int sign = grow ? 1 : -1;
                BlockPos a = c.min();
                BlockPos b = c.max();
                switch (dir) {
                    case "all", "horiz", "" -> {
                        a = new BlockPos(a.x() - amt, a.y(), a.z() - amt);
                        b = new BlockPos(b.x() + amt, b.y(), b.z() + amt);
                    }
                    case "vert" -> { a = new BlockPos(a.x(), a.y() - amt, a.z()); b = new BlockPos(b.x(), b.y() + amt, b.z()); }
                    case "up" -> b = new BlockPos(b.x(), b.y() + sign * amt, b.z());
                    case "down" -> a = new BlockPos(a.x(), a.y() - sign * amt, a.z());
                    case "north" -> a = new BlockPos(a.x(), a.y(), a.z() - sign * amt);
                    case "south" -> b = new BlockPos(b.x(), b.y(), b.z() + sign * amt);
                    case "west" -> a = new BlockPos(a.x() - sign * amt, a.y(), a.z());
                    case "east" -> b = new BlockPos(b.x() + sign * amt, b.y(), b.z());
                    default -> {
                        p.sendMessage(Text.mm("<red>Unknown direction.</red>"));
                        return;
                    }
                }
                if (grow) {
                    sel.setPos1(new BlockPos(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())));
                    sel.setPos2(new BlockPos(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
                } else {
                    sel.setPos1(a);
                    sel.setPos2(b);
                }
                Cuboid nc = sel.cuboid();
                p.sendMessage(Text.mm("<gray>Selection now " + nc.volume() + " blocks.</gray>"));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Invalid amount.</red>"));
            }
        });
    }

    private void shift(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.select")) return;
        requirePlayer(s, p -> {
            if (args.length < 3) {
                p.sendMessage(Text.mm("<red>Usage: /er shift <amount> <dir></red>"));
                return;
            }
            try {
                int amt = Integer.parseInt(args[1]);
                String dir = args[2].toLowerCase();
                Selection sel = plugin.selectionManager().get(p);
                if (!sel.isComplete()) {
                    p.sendMessage(Text.mm("<red>Selection incomplete.</red>"));
                    return;
                }
                Cuboid c = sel.cuboid();
                int dx = 0, dy = 0, dz = 0;
                switch (dir) {
                    case "up" -> dy = amt;
                    case "down" -> dy = -amt;
                    case "north" -> dz = -amt;
                    case "south" -> dz = amt;
                    case "west" -> dx = -amt;
                    case "east" -> dx = amt;
                    default -> {
                        p.sendMessage(Text.mm("<red>Unknown direction.</red>"));
                        return;
                    }
                }
                sel.setPos1(new BlockPos(c.min().x() + dx, c.min().y() + dy, c.min().z() + dz));
                sel.setPos2(new BlockPos(c.max().x() + dx, c.max().y() + dy, c.max().z() + dz));
                p.sendMessage(Text.mm("<gray>Selection shifted.</gray>"));
            } catch (NumberFormatException e) {
                p.sendMessage(Text.mm("<red>Invalid amount.</red>"));
            }
        });
    }

    private void selinfo(CommandSender s) {
        if (!checkPerm(s, "echoreplay.select")) return;
        requirePlayer(s, p -> {
            Selection sel = plugin.selectionManager().get(p);
            if (!sel.isComplete()) {
                p.sendMessage(Text.mm("<gray>Incomplete selection.</gray>"));
                return;
            }
            Cuboid c = sel.cuboid();
            p.sendMessage(Text.mm("<gray>World: " + sel.world().getName() +
                    "<newline>Pos1: " + c.min() + "<newline>Pos2: " + c.max() +
                    "<newline>Volume: " + c.volume() + "</gray>"));
        });
    }

    private void clear(CommandSender s) {
        if (!checkPerm(s, "echoreplay.select")) return;
        requirePlayer(s, p -> {
            plugin.selectionManager().clear(p);
            p.sendMessage(Text.mm("<gray>Selection cleared.</gray>"));
        });
    }

    private void record(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.record")) return;
        requirePlayer(s, p -> {
            if (args.length < 2) {
                p.sendMessage(Text.mm("<red>Usage: /er record <name></red>"));
                return;
            }
            String msg = plugin.recordingManager().start(p, args[1]);
            if (msg != null) p.sendMessage(Text.mm(msg));
            else p.sendMessage(Text.mm("<yellow>Snapshotting… recording will start once complete.</yellow>"));
        });
    }

    private void resumeRec(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.record")) return;
        requirePlayer(s, p -> {
            if (args.length < 2) {
                p.sendMessage(Text.mm("<red>Usage: /er resume <name></red>"));
                return;
            }
            String msg = plugin.recordingManager().resume(p, args[1]);
            if (msg != null) p.sendMessage(Text.mm(msg));
            else p.sendMessage(Text.mm("<green>Resumed recording '" + args[1] + "' from checkpoint.</green>"));
        });
    }

    private void stop(CommandSender s) {
        if (!checkPerm(s, "echoreplay.record")) return;
        java.util.UUID id = s instanceof Player p ? p.getUniqueId() : null;
        String msg = plugin.recordingManager().stop(id);
        s.sendMessage(Text.mm(msg));
    }

    private void cancel(CommandSender s) {
        if (!checkPerm(s, "echoreplay.record")) return;
        String msg = plugin.recordingManager().cancel();
        s.sendMessage(Text.mm(msg));
    }

    private void save(CommandSender s) {
        if (!checkPerm(s, "echoreplay.record")) return;
        java.util.UUID id = s instanceof Player p ? p.getUniqueId() : null;
        String msg = plugin.recordingManager().stop(id);
        s.sendMessage(Text.mm(msg));
    }

    private void status(CommandSender s) {
        if (!checkPerm(s, "echoreplay.use")) return;
        var sess = plugin.recordingManager().activeSession();
        if (sess == null) {
            s.sendMessage(Text.mm("<gray>No recording in progress.</gray>"));
        } else {
            s.sendMessage(Text.mm("<gray>Recording '" + sess.name() + "' — "
                    + RecordingManager.formatDuration(sess.mediaMillis()) + ", sections " + sess.sectionsDone() + "/" + sess.totalSections() + ".</gray>"));
        }
        var rep = plugin.replayManager().session();
        if (rep != null) {
            s.sendMessage(Text.mm("<gray>Playing '" + rep.name() + "' at " + rep.clock().speed() + "x.</gray>"));
        }
    }

    private void stats(CommandSender s) {
        if (!checkPerm(s, "echoreplay.use")) return;
        var rm = plugin.recordingManager();
        var sess = rm.activeSession();
        StringBuilder sb = new StringBuilder("<gold>EchoReplay stats</gold>");
        if (sess == null) {
            sb.append("<newline><gray>Recording: none</gray>");
        } else {
            java.io.File cp = sess.checkpointFile();
            long cpBytes = cp != null && cp.exists() ? cp.length() : 0;
            long rotBytes = 0;
            int rots = 0;
            for (java.io.File r : sess.rotatedCheckpoints()) {
                rots++;
                try { rotBytes += r.length(); } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: stat rot size failed", e);
                }
            }
            sb.append("<newline><gray>Recording '").append(sess.name()).append("' ")
                    .append(RecordingManager.formatDuration(sess.mediaMillis()))
                    .append(" state=").append(sess.state())
                    .append(" buffered=").append(sess.sink().size())
                    .append(" committed=").append(sess.committedSize())
                    .append(" dropped=").append(sess.sink().getDroppedEvents())
                    .append("<newline> rate=").append(sess.sink().getMaxEventsPerSecond())
                    .append("/s palette=").append(sess.snapshotPalette().size())
                    .append(" checkpoint=").append(cpBytes / 1024).append("KB")
                    .append(" rots=").append(rots).append("+").append(rotBytes / 1024).append("KB")
                    .append(" autosave=").append(rm.getAutosaveSeconds()).append("s")
                    .append(" flush=").append(rm.getFlushSeconds()).append("s")
                    .append(" diff=").append(dev.idebugger.echoreplay.record.RegionDiffRecorder.isNmsAvailable()
                            ? "NMS" : "Bukkit-fallback")
                    .append(sess.state() == dev.idebugger.echoreplay.record.RecordingSession.State.RECORDING
                            ? "" : " (not recording)").append("</gray>");
        }
        var rep = plugin.replayManager().session();
        if (rep == null) {
            sb.append("<newline><gray>Playback: none</gray>");
        } else {
            sb.append("<newline><gray>Playing '").append(rep.name()).append("' ")
                    .append(rep.virtual() ? "virtual" : "world")
                    .append(" ").append(rep.appliedIndex()).append("/").append(rep.timelineSize())
                    .append(" viewers=").append(rep.viewerCount())
                    .append(" speed=").append(rep.clock().speed())
                    .append(rep.clock().paused() ? " paused" : "")
                    .append("<newline> ids: ").append(rep.fakeIdsDescribe())
                    .append(" virtBlocks=").append(rep.virtualSnapshotSize()).append("</gray>");
        }
        s.sendMessage(Text.mm(sb.toString()));
    }

    private void play(CommandSender s, String[] args) {
        if (args.length < 2) {
            if (!checkPerm(s, "echoreplay.play")) return;
            s.sendMessage(Text.mm("<red>Usage: /er play <name> [virtual|world]</red>"));
            return;
        }
        if (!checkPlayPerm(s, args[1])) return;
        boolean virtual = false;
        if (args.length > 2 && args[2].equalsIgnoreCase("virtual")) virtual = true;
        Player player = s instanceof Player p ? p : null;
        String msg = plugin.replayManager().play(player, args[1], virtual);
        if (msg != null) s.sendMessage(Text.mm(msg));
    }

    private void pause(CommandSender s) {
        if (!checkPerm(s, "echoreplay.control")) return;
        s.sendMessage(Text.mm(plugin.replayManager().pause()));
    }

    private void resume(CommandSender s, String[] args) {
        // /er resume <name> = continue a checkpointed recording (record perm).
        // /er resume (no args) = unpause playback (control perm).
        if (args.length > 1) {
            resumeRec(s, args);
            return;
        }
        if (!checkPerm(s, "echoreplay.control")) return;
        s.sendMessage(Text.mm(plugin.replayManager().resume()));
    }

    private void speed(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.control")) return;
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er speed <0.25|0.5|1|2|4|8|16></red>"));
            return;
        }
        try {
            double sp = Double.parseDouble(args[1]);
            s.sendMessage(Text.mm(plugin.replayManager().speed(sp)));
        } catch (NumberFormatException e) {
            s.sendMessage(Text.mm("<red>Invalid speed.</red>"));
        }
    }

    private void seek(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.control")) return;
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er seek <seconds|mm:ss|marker-name></red>"));
            return;
        }
        ReplaySession rep = plugin.replayManager().session();
        if (rep == null) {
            s.sendMessage(Text.mm("<red>No replay playing.</red>"));
            return;
        }
        Double sec = parseTime(args[1]);
        if (sec != null) {
            rep.seekTo(sec * 1000);
            s.sendMessage(Text.mm("<gray>Seeked to " + args[1] + ".</gray>"));
        } else {
            boolean ok = rep.seekToMarker(args[1]);
            if (ok) s.sendMessage(Text.mm("<gray>Seeked to marker '" + args[1] + "'.</gray>"));
            else s.sendMessage(Text.mm("<red>Marker or time not found.</red>"));
        }
    }

    private void ff(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.control")) return;
        double sec = args.length > 1 ? parseOrDefault(args[1], 10) : 10;
        s.sendMessage(Text.mm(plugin.replayManager().forward(sec)));
    }

    private void rewind(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.control")) return;
        double sec = args.length > 1 ? parseOrDefault(args[1], 10) : 10;
        s.sendMessage(Text.mm(plugin.replayManager().rewind(sec)));
    }

    private void stopplay(CommandSender s) {
        if (!checkPerm(s, "echoreplay.control")) return;
        s.sendMessage(Text.mm(plugin.replayManager().stopPlay(false)));
    }

    private void leave(CommandSender s) {
        if (!checkPerm(s, "echoreplay.control")) return;
        requirePlayer(s, p -> s.sendMessage(Text.mm(plugin.replayManager().leave(p))));
    }

    private void control(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.control")) return;
        requirePlayer(s, p -> {
            var controls = plugin.replayManager().controls();
            String mode = args.length > 1 ? args[1].toLowerCase() : "toggle";
            switch (mode) {
                case "on", "enable", "enabled", "true" ->
                        s.sendMessage(Text.mm(controls.setEnabled(p, true)));
                case "off", "disable", "disabled", "false" ->
                        s.sendMessage(Text.mm(controls.setEnabled(p, false)));
                case "toggle" -> s.sendMessage(Text.mm(controls.toggle(p)));
                case "status", "info", "state", "help" ->
                        s.sendMessage(Text.mm(controls.statusText(p)));
                default -> s.sendMessage(Text.mm("<red>Usage: /er control [on|off|toggle|status]</red>"));
            }
        });
    }

    private void fps(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.fps")) return;
        requirePlayer(s, p -> {
            var prefs = plugin.fpsPrefs();
            if (prefs == null) {
                p.sendMessage(Text.mm("<red>FPS preferences not loaded yet.</red>"));
                return;
            }
            if (args.length == 1) {
                boolean next = prefs.toggle(p.getUniqueId());
                p.sendMessage(Text.mm(next
                        ? "<green>FPS saving on — replay particles/sounds trimmed for you.</green>"
                        : "<gray>FPS saving off — full replay quality for you.</gray>"));
                return;
            }
            String arg = args[1].toLowerCase();
            switch (arg) {
                case "on", "enable", "enabled", "true" -> {
                    prefs.setEnabled(p.getUniqueId(), true);
                    p.sendMessage(Text.mm("<green>FPS saving on — replay particles/sounds trimmed for you.</green>"));
                }
                case "off", "disable", "disabled", "false" -> {
                    prefs.setEnabled(p.getUniqueId(), false);
                    p.sendMessage(Text.mm("<gray>FPS saving off — full replay quality for you.</gray>"));
                }
                case "toggle" -> {
                    boolean next = prefs.toggle(p.getUniqueId());
                    p.sendMessage(Text.mm(next
                            ? "<green>FPS saving on — replay particles/sounds trimmed for you.</green>"
                            : "<gray>FPS saving off — full replay quality for you.</gray>"));
                }
                case "status", "info", "state" -> {
                    boolean on = prefs.isEnabled(p.getUniqueId());
                    p.sendMessage(Text.mm(on
                            ? "<gray>FPS saving: <green>on</green> (particles ~1/4, sounds ~1/2).</gray>"
                            : "<gray>FPS saving: <red>off</red> (full quality).</gray>"));
                }
                default -> p.sendMessage(Text.mm("<red>Usage: /er fps [on|off|toggle|status]</red>"));
            }
        });
    }

    private void privacyCmd(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.privacy")) return;
        var privacy = plugin.privacy();
        if (privacy == null || !privacy.featureEnabled()) {
            s.sendMessage(Text.mm("<red>Privacy opt-outs are disabled on this server (config).</red>"));
            return;
        }
        requirePlayer(s, p -> {
            if (args.length == 1) {
                boolean out = privacy.toggle(p.getUniqueId());
                p.sendMessage(Text.mm(privacyMessage(privacy, out)));
                return;
            }
            String arg = args[1].toLowerCase();
            switch (arg) {
                case "on", "enable", "enabled", "true" -> {
                    privacy.setOptedOut(p.getUniqueId(), true);
                    p.sendMessage(Text.mm(privacyMessage(privacy, true)));
                }
                case "off", "disable", "disabled", "false" -> {
                    privacy.setOptedOut(p.getUniqueId(), false);
                    p.sendMessage(Text.mm(privacyMessage(privacy, false)));
                }
                case "toggle" -> {
                    boolean out = privacy.toggle(p.getUniqueId());
                    p.sendMessage(Text.mm(privacyMessage(privacy, out)));
                }
                case "status", "info", "state" -> {
                    boolean out = privacy.isOptedOut(p.getUniqueId());
                    p.sendMessage(Text.mm(privacyMessage(privacy, out)));
                }
                default -> p.sendMessage(Text.mm("<red>Usage: /er privacy [on|off|toggle|status]</red>"));
            }
        });
    }

    private static String privacyMessage(dev.idebugger.echoreplay.record.PrivacyManager privacy, boolean optedOut) {
        String base = optedOut
                ? "<gray>Privacy: <green>on</green> — you are exempt from recordings"
                        + " (no REC bar, but you can't edit inside active recording zones).</gray>"
                : "<gray>Privacy: <red>off</red> — you are recorded normally.</gray>";
        if (optedOut && privacy.enforced()) {
            base += " <yellow>Note: this server enforces recording, so you WILL still be recorded.</yellow>";
        }
        return base;
    }

    private void watch(CommandSender s) {
        if (!checkPerm(s, "echoreplay.watch")) return;
        var rep = plugin.replayManager().session();
        if (rep != null && !checkPlayPerm(s, rep.name())) return;
        requirePlayer(s, p -> s.sendMessage(Text.mm(plugin.replayManager().watch(p))));
    }

    private void cam(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.use")) return;
        ReplaySession rep = plugin.replayManager().session();
        if (rep == null) {
            s.sendMessage(Text.mm("<red>No replay playing.</red>"));
            return;
        }
        if (!checkPlayPerm(s, rep.name())) return;
        requirePlayer(s, p -> {
            // /er cam off  -> stop following
            if (args.length < 2 || args[1].equalsIgnoreCase("off")) {
                if (rep.stopCamera(p)) s.sendMessage(Text.mm("<gray>Camera stopped.</gray>"));
                else s.sendMessage(Text.mm("<gray>You are not following anyone.</gray>"));
                return;
            }
            if (!rep.isViewer(p)) {
                rep.addViewer(p);
            }
            if (rep.startCamera(p, args[1])) {
                s.sendMessage(Text.mm("<green>Following '" + args[1] + "' — type /er cam off to stop.</green>"));
            } else {
                s.sendMessage(Text.mm("<red>Entity '" + args[1] + "' is not in the replay right now.\n"
                        + "<gray>Live: " + rep.liveEntityNames() + "</gray>"));
            }
        });
    }

    private void spectate(CommandSender s, String[] args) {
        ReplaySession rep = plugin.replayManager().session();
        if (rep == null) {
            if (!checkPerm(s, "echoreplay.play")) return;
            s.sendMessage(Text.mm("<red>No replay playing.</red>"));
            return;
        }
        if (!checkPlayPerm(s, rep.name())) return;
        requirePlayer(s, p -> {
            if (args.length < 2) {
                s.sendMessage(Text.mm("<red>Usage: /er spectate <player-name></red>"));
                return;
            }
            if (rep.startSpectate(p, args[1])) {
                s.sendMessage(Text.mm("<green>You are now spectating '<aqua>" + args[1]
                        + "</aqua>' in first person. <gray>Type /er stopspectate to leave.</gray>"));
            } else {
                s.sendMessage(Text.mm("<red>Recorded player '" + args[1]
                        + "' is not alive in the replay right now.</red>"));
            }
        });
    }

    private void stopspectate(CommandSender s) {
        ReplaySession rep = plugin.replayManager().session();
        if (rep == null) {
            if (!checkPerm(s, "echoreplay.play")) return;
            s.sendMessage(Text.mm("<red>No replay playing.</red>"));
            return;
        }
        if (!checkPlayPerm(s, rep.name())) return;
        requirePlayer(s, p -> {
            if (rep.stopSpectate(p)) {
                s.sendMessage(Text.mm("<green>Spectate ended — your previous state was restored.</green>"));
            } else {
                s.sendMessage(Text.mm("<gray>You are not spectating anyone.</gray>"));
            }
        });
    }

    private void list(CommandSender s) {
        if (!checkPerm(s, "echoreplay.use")) return;
        var entries = plugin.recordingIndex().all().stream()
                .filter(e -> canPlay(s, e.name()))
                .toList();
        if (entries.isEmpty()) {
            s.sendMessage(Text.mm("<gray>No recordings (or no permission).</gray>"));
            return;
        }
        List<Component> msgs = new ArrayList<>();
        msgs.add(Text.mm("<gold>Recordings:</gold>"));
        for (var e : entries) {
            msgs.add(Text.mm("<gray>  " + e.name() + " — " + RecordingManager.formatDuration(e.durationMillis())
                    + " (" + (e.sizeBytes() / 1024) + " KB)</gray>"));
        }
        for (Component m : msgs) {
            s.sendMessage(m);
        }
    }

    private void info(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.use")) return;
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er info <name></red>"));
            return;
        }
        if (!checkPlayPerm(s, args[1])) return;
        var e = plugin.recordingIndex().get(args[1]);
        if (e == null) {
            s.sendMessage(Text.mm("<red>No such recording.</red>"));
            return;
        }
        s.sendMessage(Text.mm("<gray>Name: " + e.name() + "<newline>World: " + e.worldName() +
                "<newline>Duration: " + RecordingManager.formatDuration(e.durationMillis()) +
                "<newline>Bounds: " + e.minX() + "," + e.minY() + "," + e.minZ() + " → " + e.maxX() + "," + e.maxY() + "," + e.maxZ() + "</gray>"));
    }

    private void delete(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.delete")) return;
        if (args.length < 2) {
            s.sendMessage(Text.mm("<red>Usage: /er delete <name></red>"));
            return;
        }
        String name = args[1];
        File f = new File(plugin.recordingManager().recordingsDir(), name + ".echoreplay.gz");
        if (!f.exists()) {
            s.sendMessage(Text.mm("<red>No recording named '" + name + "'.</red>"));
            return;
        }
        // Two-step: first call arms the delete, /er confirm performs it.
        if (!pendingDeletes.containsKey(s)) {
            pendingDeletes.put(s, name);
            s.sendMessage(Text.mm("<yellow>Type /er confirm to remove '" + name + "'.</yellow>"));
            return;
        }
        String armed = pendingDeletes.remove(s);
        if (!armed.equals(name)) {
            s.sendMessage(Text.mm("<red>Not the armed delete (armed: '" + armed + "').</red>"));
            return;
        }
        if (f.delete()) {
            plugin.recordingIndex().remove(name);
            s.sendMessage(Text.mm("<green>Deleted '" + name + "'.</green>"));
        } else {
            s.sendMessage(Text.mm("<red>Could not delete the file.</red>"));
        }
    }

    private void rename(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.delete")) return;
        if (args.length < 3) {
            s.sendMessage(Text.mm("<red>Usage: /er rename <old> <new></red>"));
            return;
        }
        File old = new File(plugin.recordingManager().recordingsDir(), args[1] + ".echoreplay.gz");
        File neu = new File(plugin.recordingManager().recordingsDir(), args[2] + ".echoreplay.gz");
        if (old.exists() && old.renameTo(neu)) {
            var e = plugin.recordingIndex().get(args[1]);
            plugin.recordingIndex().remove(args[1]);
            if (e != null) {
                plugin.recordingIndex().put(new dev.idebugger.echoreplay.storage.RecordingEntry(
                        args[2], e.worldUuid(), e.worldName(), e.durationMillis(), e.sizeBytes(),
                        e.epochMillis(), e.minX(), e.minY(), e.minZ(), e.maxX(), e.maxY(), e.maxZ()));
            }
            s.sendMessage(Text.mm("<green>Renamed to '" + args[2] + "'.</green>"));
        } else {
            s.sendMessage(Text.mm("<red>Rename failed.</red>"));
        }
    }

    private void confirm(CommandSender s) {
        if (!checkPerm(s, "echoreplay.delete")) return;
        String name = pendingDeletes.remove(s);
        if (name == null) {
            s.sendMessage(Text.mm("<gray>Nothing pending confirmation.</gray>"));
            return;
        }
        File f = new File(plugin.recordingManager().recordingsDir(), name + ".echoreplay.gz");
        if (f.exists() && f.delete()) {
            plugin.recordingIndex().remove(name);
            s.sendMessage(Text.mm("<green>Deleted '" + name + "'.</green>"));
        } else {
            s.sendMessage(Text.mm("<red>No recording file named '" + name + "' anymore.</red>"));
        }
    }

    private void marker(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.record")) return;
        var sess = plugin.recordingManager().activeSession();
        if (sess == null) {
            s.sendMessage(Text.mm("<red>No recording in progress.</red>"));
            return;
        }
        String name = args.length > 1 ? args[1] : "marker" + sess.mediaMillis();
        sess.emit(new dev.idebugger.echoreplay.model.TimelineEvent.Marker(sess.mediaMillis(), name));
        s.sendMessage(Text.mm("<gray>Marker '" + name + "' placed.</gray>"));
    }

    private void debug(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.use")) return;
        String what = args.length > 1 ? args[1].toLowerCase() : "";
        if (what.equals("nms")) {
            boolean avail = dev.idebugger.echoreplay.record.RegionDiffRecorder.isNmsAvailable();
            String desc = dev.idebugger.echoreplay.record.RegionDiffRecorder.describeNms();
            boolean active = plugin.recordingManager().regionDiffRecorder().isActive();
            s.sendMessage(Text.mm("<gray>RegionDiff NMS: " + (avail ? "<green>available</green>" : "<red>unavailable (Bukkit fallback)</red>")
                    + "<newline>" + desc
                    + "<newline>scanner active: " + active + "</gray>"));
        } else {
            s.sendMessage(Text.mm("<red>Usage: /er debug nms</red>"));
        }
    }

    private void version(CommandSender s) {
        if (!checkPerm(s, "echoreplay.use")) return;
        s.sendMessage(Text.mm("<gold>EchoReplay</gold> <gray>v"
                + plugin.getDescription().getVersion()
                + " (api " + plugin.getDescription().getAPIVersion() + ")</gray>"));
    }

    private void reload(CommandSender s) {
        if (!checkPerm(s, "echoreplay.admin")) return;
        if (plugin.recordingManager().activeSession() != null || plugin.replayManager().session() != null) {
            s.sendMessage(Text.mm("<red>Cannot reload while a recording or replay is active. Stop it first.</red>"));
            return;
        }
        plugin.reloadConfig();
        plugin.recordingManager().onEnable(plugin.getConfig());
        plugin.replayManager().onEnable(plugin.getConfig());
        s.sendMessage(Text.mm("<green>EchoReplay config reloaded.</green>"));
    }

    private void border(CommandSender s, String[] args) {
        if (!checkPerm(s, "echoreplay.border")) return;
        requirePlayer(s, p -> {
            var prefs = plugin.borderPrefs();
            if (prefs == null) {
                p.sendMessage(Text.mm("<red>Border preferences not loaded yet.</red>"));
                return;
            }
            if (args.length == 1) {
                boolean newState = prefs.toggle(p.getUniqueId());
                p.sendMessage(Text.mm(newState
                        ? "<green>Borders enabled for you (selection + playback).</green>"
                        : "<gray>Borders disabled for you (selection + playback).</gray>"));
                return;
            }
            String arg = args[1].toLowerCase();
            switch (arg) {
                case "on", "enable", "enabled", "true" -> {
                    prefs.setEnabled(p.getUniqueId(), true);
                    p.sendMessage(Text.mm("<green>Borders enabled for you (selection + playback).</green>"));
                }
                case "off", "disable", "disabled", "false" -> {
                    prefs.setEnabled(p.getUniqueId(), false);
                    p.sendMessage(Text.mm("<gray>Borders disabled for you (selection + playback).</gray>"));
                }
                case "toggle" -> {
                    boolean newState = prefs.toggle(p.getUniqueId());
                    p.sendMessage(Text.mm(newState
                            ? "<green>Borders enabled for you (selection + playback).</green>"
                            : "<gray>Borders disabled for you (selection + playback).</gray>"));
                }
                case "status", "info", "state" -> {
                    boolean enabled = prefs.isEnabled(p.getUniqueId());
                    p.sendMessage(Text.mm(enabled
                            ? "<gray>Borders (selection + playback): <green>enabled</green>.</gray>"
                            : "<gray>Borders (selection + playback): <red>disabled</red>.</gray>"));
                }
                default -> p.sendMessage(Text.mm("<red>Usage: /er border [on|off|toggle|status]</red>"));
            }
        });
    }

    private Double parseTime(String s) {
        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length != 2) return null;
            try {
                return Double.parseDouble(parts[0]) * 60 + Double.parseDouble(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseOrDefault(String s, double d) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> subs = Arrays.asList("wand", "pos1", "pos2", "select", "expand", "contract", "shift",
                "selinfo", "clear", "record", "resume", "stop", "cancel", "save", "status", "stats", "play", "pause", "resume",
                "speed", "seek", "ff", "rewind", "stopplay", "leave", "control", "fps", "privacy", "watch", "cam", "spectate",
                "stopspectate", "list", "info", "delete", "rename", "confirm", "marker", "border", "debug", "version", "reload");
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : subs) if (s.startsWith(args[0].toLowerCase())) out.add(s);
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("watch"))) {
            return plugin.recordingIndex().all().stream().map(e -> e.name())
                    .filter(n -> n.startsWith(args[1]) && canPlay(sender, n))
                    .collect(java.util.stream.Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            return Arrays.asList("0.25", "0.5", "1", "2", "4", "8", "16");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("border")) {
            return Arrays.asList("on", "off", "toggle", "status").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return Arrays.asList("nms").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("control")) {
            return Arrays.asList("on", "off", "toggle", "status").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("fps") || args[0].equalsIgnoreCase("privacy"))) {
            return Arrays.asList("on", "off", "toggle", "status").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("resume")) {
            // Complete resumable checkpoints (live .partial files).
            try {
                java.io.File dir = plugin.recordingManager().recordingsDir();
                String[] names = dir.list((d, n) -> n.endsWith(".echoreplay.gz.partial"));
                List<String> out = new ArrayList<>();
                if (names != null) for (String f : names) {
                    String rec = f.substring(0, f.length() - ".echoreplay.gz.partial".length());
                    if (rec.startsWith(args[1])) out.add(rec);
                }
                return out;
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: resume tab-complete failed", e);
                return List.of();
            }
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("cam") || args[0].equalsIgnoreCase("spectate"))) {
            var rep = plugin.replayManager().session();
            if (rep != null) {
                List<String> out = new ArrayList<>();
                if (args[0].equalsIgnoreCase("cam") && "off".startsWith(args[1].toLowerCase())) {
                    out.add("off");
                }
                for (String n : rep.liveEntityNames()) {
                    if (n.toLowerCase().startsWith(args[1].toLowerCase())) out.add(n);
                }
                return out;
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rename")) {
            return plugin.recordingIndex().all().stream().map(e -> e.name())
                    .filter(n -> n.startsWith(args[1])).collect(java.util.stream.Collectors.toList());
        }
        return List.of();
    }
}
