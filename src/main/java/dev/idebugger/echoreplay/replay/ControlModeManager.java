package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visible VCR controls: replaces the player's hotbar (slots 0-8) with
 * right-clickable control items (pause, resume, stop, rewind, fast-forward,
 * speed, restart, leave, help).
 *
 * <p>Modal and locked: while enabled the hotbar cannot be rearranged or
 * dropped (inventory clicks/drags and control-item drops are cancelled, death
 * drops are filtered). The previous hotbar is saved and restored when control
 * mode ends: explicit off, playback stop/end, replay leave, quit, respawn
 * handling, or server shutdown.
 */
public final class ControlModeManager implements Listener {

    public static final String ACTION_PAUSE = "pause";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_TOGGLE = "toggle";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_REWIND = "rewind";
    public static final String ACTION_FF = "ff";
    public static final String ACTION_SPEED = "speed";
    public static final String ACTION_RESTART = "restart";
    public static final String ACTION_SPECTATE_MENU = "spectate_menu";
    public static final String ACTION_LEAVE = "leave";
    public static final String ACTION_STATUS = "status";

    private static final double[] SPEEDS = {1.0, 2.0, 4.0, 8.0, 16.0, 0.25, 0.5};

    private final EchoReplay plugin;
    private final ReplayManager replays;
    private final NamespacedKey controlKey;
    private final NamespacedKey spectateKey;

    private record SavedHotbar(ItemStack[] hotbar, int heldSlot) {}

    private final Map<UUID, SavedHotbar> saved = new HashMap<>();

    public ControlModeManager(EchoReplay plugin, ReplayManager replays) {
        this.plugin = plugin;
        this.replays = replays;
        this.controlKey = new NamespacedKey(plugin, "control");
        this.spectateKey = new NamespacedKey(plugin, "spectate");
    }

    public boolean isEnabled(Player p) {
        return p != null && saved.containsKey(p.getUniqueId());
    }

    public boolean hasAny() {
        return !saved.isEmpty();
    }

    /** Per-tick safety net: no session left means no controls. */
    public void tick() {
        if (!saved.isEmpty() && replays.session() == null) clearAll();
    }

    public String toggle(Player p) {
        return isEnabled(p) ? setEnabled(p, false) : setEnabled(p, true);
    }

    /**
     * @return MiniMessage result for the command sender.
     */
    public String setEnabled(Player p, boolean on) {
        if (p == null) return "<red>No player.</red>";
        if (on) {
            if (isEnabled(p)) return "<gray>Controls already on.</gray>";
            if (replays.session() == null) return "<red>No replay playing.</red>";
            ItemStack[] hot = new ItemStack[9];
            for (int i = 0; i < 9; i++) {
                try {
                    ItemStack it = p.getInventory().getItem(i);
                    hot[i] = it == null ? null : it.clone();
                } catch (Exception e) {
                    java.util.logging.Logger.getLogger("EchoReplay").log(
                            java.util.logging.Level.FINE, "EchoReplay: control save failed slot " + i, e);
                    hot[i] = null;
                }
            }
            int held = 0;
            try {
                held = p.getInventory().getHeldItemSlot();
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: control save held slot failed", e);
            }
            saved.put(p.getUniqueId(), new SavedHotbar(hot, held));
            giveControls(p);
            return "<green>Controls on — right-click the hotbar items. <gray>/er control off to restore.</gray>";
        } else {
            if (!isEnabled(p)) return "<gray>Controls already off.</gray>";
            restore(p);
            return "<gray>Controls off — hotbar restored.</gray>";
        }
    }

    public String statusText(Player p) {
        StringBuilder sb = new StringBuilder("<gold>Controls: ")
                .append(isEnabled(p) ? "<green>ON</green>" : "<gray>OFF</gray>")
                .append("</gold><newline><gray>0 Stop | 1 Help | 2 Restart | 3 -10s | <white>4 Pause/Resume</white>")
                .append("<newline>5 +10s | 6 Speed | 7 Spectate | 8 Leave")
                .append("<newline>Hotbar is locked while on; stopping playback, leaving,")
                .append(" quitting or server stop restores it.</gray>");
        return sb.toString();
    }

    /** Restore everyone (playback stop/end, server shutdown). */
    public void clearAll() {
        if (saved.isEmpty()) return;
        for (UUID id : new ArrayList<>(saved.keySet())) {
            try {
                Player p = org.bukkit.Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    restore(p);
                } else {
                    saved.remove(id);
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: control clearAll failed", e);
                saved.remove(id);
            }
        }
    }

    /** Silent disable for one player (leave/quit paths). */
    public void disable(Player p) {
        if (p == null) return;
        if (!saved.containsKey(p.getUniqueId())) return;
        restore(p);
    }

    private void restore(Player p) {
        SavedHotbar s = saved.remove(p.getUniqueId());
        if (s == null) return;
        try {
            for (int i = 0; i < 9; i++) {
                ItemStack it = s.hotbar()[i];
                p.getInventory().setItem(i, it == null ? null : it.clone());
            }
            int held = Math.max(0, Math.min(8, s.heldSlot()));
            p.getInventory().setHeldItemSlot(held);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control restore failed for " + p.getName(), e);
        }
    }

    private void giveControls(Player p) {
        // Symmetric VCR bar around a center pause/resume toggle:
        // 0 Stop | 1 Help | 2 Restart | 3 Rewind | 4 CENTER Pause/Resume |
        // 5 FF | 6 Speed | 7 Spectate menu | 8 Leave
        p.getInventory().setItem(0, controlItem(Material.BARRIER, ACTION_STOP,
                "<red>⏹ Stop</red>", "Right-click: stop playback"));
        p.getInventory().setItem(1, controlItem(Material.BOOK, ACTION_STATUS,
                "<white>Help</white>", "Right-click: show controls (chat)"));
        p.getInventory().setItem(2, controlItem(Material.RECOVERY_COMPASS, ACTION_RESTART,
                "<gold>⏮ Restart</gold>", "Right-click: seek to start"));
        p.getInventory().setItem(3, controlItem(Material.ARROW, ACTION_REWIND,
                "<yellow>⏪ Rewind 10s</yellow>", "Right-click: back 10 seconds"));
        p.getInventory().setItem(4, toggleItem(p));
        p.getInventory().setItem(5, controlItem(Material.SPECTRAL_ARROW, ACTION_FF,
                "<yellow>Forward 10s ⏩</yellow>", "Right-click: forward 10 seconds"));
        p.getInventory().setItem(6, controlItem(Material.SUGAR, ACTION_SPEED,
                "<aqua>Speed</aqua>", "Right-click: cycle 1-2-4-8-16-0.25-0.5x"));
        p.getInventory().setItem(7, controlItem(Material.PLAYER_HEAD, ACTION_SPECTATE_MENU,
                "<aqua>Spectate</aqua>", "Right-click: choose a player"));
        p.getInventory().setItem(8, controlItem(Material.OAK_DOOR, ACTION_LEAVE,
                "<gray>Leave</gray>", "Right-click: leave the replay"));
        try {
            p.getInventory().setHeldItemSlot(4);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control center select failed", e);
        }
    }

    /** Center pause/resume toggle, reflecting the live paused state. */
    private ItemStack toggleItem(Player p) {
        boolean paused = false;
        try {
            ReplaySession s = replays.session();
            paused = s != null && s.clock().paused();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control toggle state read failed", e);
        }
        ItemStack it = paused
                ? controlItem(Material.EMERALD_BLOCK, ACTION_TOGGLE,
                        "<green>Resume ▶</green>", "Right-click: resume playback")
                : controlItem(Material.REDSTONE_BLOCK, ACTION_TOGGLE,
                        "<red>Pause ⏸</red>", "Right-click: pause playback");
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                it.setItemMeta(meta);
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control center glint failed", e);
        }
        return it;
    }

    /** Refresh the center toggle after a pause/resume so it never lies. */
    private void refreshCenterButton(Player p) {
        if (!isEnabled(p)) return;
        try {
            p.getInventory().setItem(4, toggleItem(p));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control center refresh failed", e);
        }
    }

    /** Re-apply controls without re-saving (respawn path). */
    private void ensureControls(Player p) {
        if (!isEnabled(p)) return;
        try {
            ItemStack cur = p.getInventory().getItem(0);
            if (isControlItem(cur)) return;
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control ensure check failed", e);
        }
        giveControls(p);
    }

    private ItemStack controlItem(Material mat, String action, String nameMm, String loreMm) {
        ItemStack it = new ItemStack(mat);
        it.setAmount(1);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm(nameMm));
            meta.lore(List.of(Text.mm("<gray>" + loreMm + "</gray>")));
            meta.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, action);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean isControlItem(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return false;
            String v = meta.getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
            return v != null && !v.isEmpty();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control tag check failed", e);
            return false;
        }
    }

    private String actionOf(ItemStack it) {
        if (it == null) return null;
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return null;
            return meta.getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control action read failed", e);
            return null;
        }
    }

    /** Hotbar-click feedback goes to the action bar (above the hotbar) for
     *  convenience — the player's eyes are already there. Command outputs
     *  (typed /er control ...) stay in chat. */
    private void feedback(Player p, String miniMessage) {
        try {
            p.sendActionBar(Text.mm(miniMessage));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control feedback failed", e);
        }
    }

    private void runAction(Player p, String action) {
        if (action == null) return;
        try {
            switch (action) {
                case ACTION_TOGGLE -> {
                    ReplaySession s = replays.session();
                    if (s == null) {
                        feedback(p, "<red>No replay playing.</red>");
                    } else if (s.clock().paused()) {
                        feedback(p, replays.resume());
                    } else {
                        feedback(p, replays.pause());
                    }
                    refreshCenterButton(p);
                }
                case ACTION_PAUSE -> {
                    feedback(p, replays.pause());
                    refreshCenterButton(p);
                }
                case ACTION_RESUME -> {
                    feedback(p, replays.resume());
                    refreshCenterButton(p);
                }
                case ACTION_STOP -> {
                    feedback(p, replays.stopPlay(false));
                    disable(p);
                }
                case ACTION_REWIND -> feedback(p, replays.rewind(10));
                case ACTION_FF -> feedback(p, replays.forward(10));
                case ACTION_SPEED -> {
                    ReplaySession s = replays.session();
                    double cur = s != null ? s.clock().speed() : 1.0;
                    double next = SPEEDS[0];
                    for (double v : SPEEDS) {
                        if (v > cur + 1e-9) {
                            next = v;
                            break;
                        }
                    }
                    feedback(p, replays.speed(next));
                }
                case ACTION_RESTART -> {
                    ReplaySession s = replays.session();
                    if (s == null) {
                        feedback(p, "<red>No replay playing.</red>");
                    } else {
                        s.seekTo(0);
                        feedback(p, "<gray>Seeked to start.</gray>");
                    }
                }
                case ACTION_LEAVE -> {
                    feedback(p, replays.leave(p));
                    disable(p);
                }
                case ACTION_SPECTATE_MENU -> openSpectateMenu(p);
                case ACTION_STATUS -> p.sendMessage(Text.mm(statusText(p)));
                default -> { /* unknown tag: ignore */ }
            }
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control action failed " + action, e);
        }
    }

    // ---- spectate picker (double-chest GUI with player heads) ----

    static final class SpectateMenuHolder implements org.bukkit.inventory.InventoryHolder {
        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return null;
        }
    }

    private void openSpectateMenu(Player p) {
        ReplaySession s = replays.session();
        if (s == null) {
            feedback(p, "<red>No replay playing.</red>");
            return;
        }
        List<ReplaySession.SpectatablePlayer> players;
        try {
            players = s.spectatablePlayers();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: spectate menu list failed", e);
            feedback(p, "<red>Could not list players.</red>");
            return;
        }
        if (players.isEmpty()) {
            feedback(p, "<red>No recorded players alive right now.</red>");
            return;
        }
        org.bukkit.inventory.Inventory menu;
        try {
            menu = org.bukkit.Bukkit.createInventory(new SpectateMenuHolder(), 54,
                    Text.mm("<gold>Spectate — choose a player</gold>"));
        } catch (Exception e) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: spectate menu open failed", e);
            feedback(p, "<red>Could not open menu.</red>");
            return;
        }
        int i = 0;
        for (ReplaySession.SpectatablePlayer sp : players) {
            if (i >= 54) break;
            menu.setItem(i++, spectateHead(sp));
        }
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.displayName(Text.mm(" "));
            filler.setItemMeta(fm);
        }
        while (i < 54) menu.setItem(i++, filler.clone());
        p.openInventory(menu);
    }

    private ItemStack spectateHead(ReplaySession.SpectatablePlayer sp) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                java.util.UUID id = sp.uuid() != null ? sp.uuid() : UUID.randomUUID();
                com.destroystokyo.paper.profile.PlayerProfile profile =
                        (com.destroystokyo.paper.profile.PlayerProfile)
                                org.bukkit.Bukkit.createProfile(id, sp.name());
                dev.idebugger.echoreplay.model.PlayerSkin skin = sp.skin();
                if (skin != null && skin.hasValue()) {
                    profile.getProperties().add(new com.destroystokyo.paper.profile.ProfileProperty(
                            "textures", skin.value(),
                            skin.signature() != null ? skin.signature() : ""));
                }
                meta.setOwnerProfile(profile);
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: spectate head skin failed for " + sp.name(), e);
            }
            meta.displayName(Text.mm("<aqua>" + sp.name() + "</aqua>"));
            meta.lore(List.of(Text.mm("<gray>Click to spectate</gray>")));
            try {
                meta.getPersistentDataContainer().set(spectateKey, PersistentDataType.STRING, sp.name());
            } catch (Exception e) {
                java.util.logging.Logger.getLogger("EchoReplay").log(
                        java.util.logging.Level.FINE, "EchoReplay: spectate head tag failed", e);
            }
            head.setItemMeta(meta);
        }
        return head;
    }

    // ---- events: locked hotbar ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isEnabled(p)) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK
                && a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;
        String action = actionOf(e.getItem());
        if (action == null) {
            // Holding a non-control item while flagged should not happen
            // (hotbar is locked), but never hijack foreign items.
            return;
        }
        e.setCancelled(true);
        runAction(p, action);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        // Spectate picker first: clicks inside it choose a player (heads are
        // never takeable), clicks into the player inventory stay cancelled
        // while control mode is on.
        try {
            if (e.getInventory().getHolder() instanceof SpectateMenuHolder) {
                e.setCancelled(true);
                if (e.getClickedInventory() != null
                        && e.getClickedInventory().getHolder() instanceof SpectateMenuHolder) {
                    ItemStack cur = e.getCurrentItem();
                    String target = null;
                    try {
                        if (cur != null && cur.getItemMeta() != null) {
                            target = cur.getItemMeta().getPersistentDataContainer()
                                    .get(spectateKey, PersistentDataType.STRING);
                        }
                    } catch (Exception ex) {
                        java.util.logging.Logger.getLogger("EchoReplay").log(
                                java.util.logging.Level.FINE, "EchoReplay: spectate pick read failed", ex);
                    }
                    if (target != null && !target.isEmpty()) {
                        p.closeInventory();
                        ReplaySession s = replays.session();
                        if (s == null) {
                            feedback(p, "<red>No replay playing.</red>");
                        } else if (s.startSpectate(p, target)) {
                            feedback(p, "<green>Spectating '<aqua>" + target + "</aqua>'.</green>");
                        } else {
                            feedback(p, "<red>'" + target + "' is not alive right now.</red>");
                        }
                    }
                }
                return;
            }
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: spectate menu click failed", ex);
        }
        if (isEnabled(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        try {
            if (e.getInventory().getHolder() instanceof SpectateMenuHolder) {
                e.setCancelled(true);
                return;
            }
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: spectate menu drag failed", ex);
        }
        if (isEnabled(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isControlItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (isEnabled(p) || isControlItem(e.getMainHandItem()) || isControlItem(e.getOffHandItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        // Controls must never scatter on the ground; saved hotbar stays stored
        // and is restored on exit/respawn, so no loss and no duplication.
        try {
            e.getDrops().removeIf(this::isControlItem);
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control death filter failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!saved.containsKey(p.getUniqueId())) return;
        if (replays.session() == null) {
            disable(p);
            return;
        }
        // Inventory is empty/cleared after a non-keep death: re-apply controls
        // without touching the saved hotbar.
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> ensureControls(p));
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger("EchoReplay").log(
                    java.util.logging.Level.FINE, "EchoReplay: control respawn failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // Player object is still usable during quit for inventory restore
        // (teleport is a no-op then, same as the spectate path).
        disable(e.getPlayer());
    }
}
