package dev.idebugger.echoreplay.replay;

import dev.idebugger.echoreplay.EchoReplay;
import dev.idebugger.echoreplay.select.Cuboid;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Client-side border particles around the playback cuboid.
 * Uses per-player {@code Player#spawnParticle} which sends a single
 * {@code WrapperPlayServerWorldParticles} packet to that viewer only —
 * pure protocol trick, no world change or server-wide broadcast.
 * <p>
 * The border is rendered as filled faces (whole area of each side) with grid
 * spacing, not just the 12 edges. It is throttled by
 * {@code replay.border.interval-ticks} and adaptive stepping so large regions
 * don't spam packets.
 */
public final class PlaybackBorderRenderer {

    private PlaybackBorderRenderer() {}

    public static void tick(ReplaySession session) {
        EchoReplay plugin = EchoReplay.get();
        if (plugin == null || session == null || !session.started()) return;
        if (!plugin.cfg().getBoolean("replay.border.enabled", true)) return;

        int interval = plugin.cfg().getInt("replay.border.interval-ticks", 4);
        if (interval <= 0) interval = 4;
        // Session owns the counter so each playback throttles independently.
        if (!session.shouldRenderBorder(interval)) return;

        Cuboid c = session.cuboid();
        World w = session.world();
        if (c == null || w == null) return;

        String name = plugin.cfg().getString("replay.border.particle", "END_ROD");
        Particle particle;
        try {
            particle = Particle.valueOf(name);
        } catch (Exception ex) {
            particle = Particle.END_ROD;
        }
        // Dust needs extra data; fall back if someone configured DUST without color
        Object dustData = null;
        if (particle == Particle.DUST) {
            try {
                dustData = new Particle.DustOptions(org.bukkit.Color.fromRGB(0, 255, 255), 1.0f);
            } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);
                particle = Particle.END_ROD;
            }
        }

        double cfgStep = plugin.cfg().getDouble("replay.border.step", 2.0);
        if (cfgStep < 0.5) cfgStep = 0.5;
        int maxPerFrame = plugin.cfg().getInt("replay.border.max-per-frame", 300);
        if (maxPerFrame <= 0) maxPerFrame = 300;

        // Adaptive step to cap packets for huge cuboids (now based on filled faces area).
        int xSize = c.xSize();
        int ySize = c.ySize();
        int zSize = c.zSize();
        double totalArea = 2.0 * (xSize * (double) ySize + xSize * (double) zSize + ySize * (double) zSize);
        double est = totalArea / (cfgStep * cfgStep);
        double step = cfgStep;
        if (est > maxPerFrame) {
            step = Math.sqrt(totalArea / maxPerFrame);
            if (step < 0.5) step = 0.5;
        }

        PlaybackBorderPrefs prefs = plugin.borderPrefs();
        List<Player> viewers = session.liveViewersPublic();
        for (Player p : viewers) {
            if (p == null || !p.isOnline()) continue;
            if (prefs != null && !prefs.isEnabled(p.getUniqueId())) continue;
            // Only show if in same world; liveViewers already filters but double-check
            if (!p.getWorld().getUID().equals(w.getUID())) continue;
            // Distance cull: skip if player is very far (> 128 + max dimension)
            double cx = (c.min().x() + c.max().x()) / 2.0;
            double cy = (c.min().y() + c.max().y()) / 2.0;
            double cz = (c.min().z() + c.max().z()) / 2.0;
            var ploc = p.getLocation();
            double dx = ploc.getX() - cx;
            double dy = ploc.getY() - cy;
            double dz = ploc.getZ() - cz;
            double distSq = dx*dx + dy*dy + dz*dz;
            double maxDist = 128 + Math.max(xSize, Math.max(ySize, zSize));
            if (distSq > maxDist * maxDist) continue;
            renderFor(p, c, particle, dustData, step);
        }
    }

    private static void renderFor(Player p, Cuboid c, Particle particle, Object dustData, double step) {
        double x1 = c.min().x();
        double y1 = c.min().y();
        double z1 = c.min().z();
        double x2 = c.max().x() + 1;
        double y2 = c.max().y() + 1;
        double z2 = c.max().z() + 1;

        // Fill whole area of each of the 6 faces with grid spacing.
        // Bottom (y = y1) and top (y = y2)
        faceY(p, particle, dustData, x1, x2, z1, z2, y1, step);
        faceY(p, particle, dustData, x1, x2, z1, z2, y2, step);
        // North (z = z1) and south (z = z2) - X*Y planes
        faceZ(p, particle, dustData, x1, x2, y1, y2, z1, step);
        faceZ(p, particle, dustData, x1, x2, y1, y2, z2, step);
        // West (x = x1) and east (x = x2) - Y*Z planes
        faceX(p, particle, dustData, y1, y2, z1, z2, x1, step);
        faceX(p, particle, dustData, y1, y2, z1, z2, x2, step);
    }

    private static void faceY(Player p, Particle particle, Object dustData,
                              double x1, double x2, double z1, double z2, double y, double step) {
        for (double x = x1; x <= x2 + 1e-6; x += step) {
            double cx = Math.min(x, x2);
            for (double z = z1; z <= z2 + 1e-6; z += step) {
                double cz = Math.min(z, z2);
                spawn(p, particle, dustData, cx, y, cz);
            }
        }
    }

    private static void faceZ(Player p, Particle particle, Object dustData,
                              double x1, double x2, double y1, double y2, double z, double step) {
        for (double x = x1; x <= x2 + 1e-6; x += step) {
            double cx = Math.min(x, x2);
            for (double y = y1; y <= y2 + 1e-6; y += step) {
                double cy = Math.min(y, y2);
                spawn(p, particle, dustData, cx, cy, z);
            }
        }
    }

    private static void faceX(Player p, Particle particle, Object dustData,
                              double y1, double y2, double z1, double z2, double x, double step) {
        for (double y = y1; y <= y2 + 1e-6; y += step) {
            double cy = Math.min(y, y2);
            for (double z = z1; z <= z2 + 1e-6; z += step) {
                double cz = Math.min(z, z2);
                spawn(p, particle, dustData, x, cy, cz);
            }
        }
    }

    private static void spawn(Player p, Particle particle, Object dustData, double x, double y, double z) {
        try {
            if (particle == Particle.DUST && dustData != null) {
                p.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, dustData);
            } else {
                p.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
            }
        } catch (Exception ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed Exception", ignored);}
    }
}
