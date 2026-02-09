package org.loioh.craftmate.vision;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.loioh.craftmate.Config;
import org.loioh.craftmate.CraftMate;

/**
 * Lightweight in-game visibility for the vision pipeline.
 * Goals:
 *  - No silent failures
 *  - Clear stage / last error / last HTTP
 *  - Optional on-screen overlay
 */
@Mod.EventBusSubscriber(modid = "craftmate", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VisionDebug {

    private VisionDebug() {}

    private static volatile String lastStage = "idle";
    private static volatile String lastError = "none";
    private static volatile String lastHttp = "";
    private static volatile long lastFireMs = 0L;
    private static volatile long lastOkMs = 0L;

    private static volatile long lastHeartbeatMs = 0L;

    public static void setStage(String stage) {
        lastStage = stage == null ? "" : stage;
    }

    public static void setLastHttp(String s) {
        lastHttp = s == null ? "" : s;
    }

    public static void setLastError(String s) {
        lastError = (s == null || s.isBlank()) ? "none" : s;
    }

    public static void markFired() {
        lastFireMs = System.currentTimeMillis();
    }

    public static void markOk() {
        lastOkMs = System.currentTimeMillis();
    }

    public static long getLastFireMs() { return lastFireMs; }

    /**
     * Used for places where we want "no silent failures" but must not crash the client.
     * This will:
     *  - log stacktrace to logfile
     *  - store a short error string for the HUD overlay
     *  - optionally show a red chat message (dev-only via config)
     */
    public static void fatal(String where, Throwable t) {
        try {
            String msg = (where == null ? "" : where) + ": " + (t == null ? "null" : (t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage())));
            setLastError(msg);
            CraftMate.LOGGER.error("[VISION] FATAL {}", where, t);

            if (Config.getVisionDebugToast()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("§c[VISION ERROR] " + msg));
                }
            }
        } catch (Throwable ignored) {
            // don't explode
        }
    }

    public static void heartbeatTick() {
        if (!Config.getVisionDebugEnabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatMs < 10_000L) return;
        lastHeartbeatMs = now;

        // If user expects vision (debug mode) but it hasn't fired in a while, say so.
        if (Config.getVisionEnabled()) {
            long since = (lastFireMs <= 0L) ? -1L : (now - lastFireMs);
            if (since == -1L) {
                CraftMate.vLog("Heartbeat: no captures fired yet.");
            } else if (since > 30_000L) {
                CraftMate.vLog("Heartbeat: last capture was " + (since/1000) + "s ago.");
            }
        }
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post e) {
        if (!Config.getVisionDebugEnabled()) return;
        if (!Config.getVisionDebugOverlay()) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.font == null || e.getGuiGraphics() == null) return;

            long now = System.currentTimeMillis();
            long sinceFire = lastFireMs <= 0L ? -1L : (now - lastFireMs);
            long sinceOk = lastOkMs <= 0L ? -1L : (now - lastOkMs);

            String line1 = "Vision: " + (VisionCapture.isInFlight() ? "IN_FLIGHT" : (VisionCapture.hasPending() ? "PENDING" : "IDLE"));
            String line2 = "Stage: " + lastStage;
            String line3 = "Last fire: " + (sinceFire < 0 ? "never" : (sinceFire/1000) + "s ago");
            String line4 = "Last ok: " + (sinceOk < 0 ? "never" : (sinceOk/1000) + "s ago");
            String line5 = "HTTP: " + (lastHttp.length() > 60 ? lastHttp.substring(0,60) : lastHttp);
            String line6 = "Err: " + (lastError.length() > 60 ? lastError.substring(0,60) : lastError);

            int x = 6, y = 6;
            e.getGuiGraphics().drawString(mc.font, Component.literal(line1), x, y, 0xFFFFFF, true); y += 10;
            e.getGuiGraphics().drawString(mc.font, Component.literal(line2), x, y, 0xFFFFFF, true); y += 10;
            e.getGuiGraphics().drawString(mc.font, Component.literal(line3), x, y, 0xFFFFFF, true); y += 10;
            e.getGuiGraphics().drawString(mc.font, Component.literal(line4), x, y, 0xFFFFFF, true); y += 10;
            e.getGuiGraphics().drawString(mc.font, Component.literal(line5), x, y, 0xFFFFFF, true); y += 10;
            e.getGuiGraphics().drawString(mc.font, Component.literal(line6), x, y, 0xFFFFFF, true);
        } catch (Throwable t) {
            // Never break rendering
        }
    }
}
