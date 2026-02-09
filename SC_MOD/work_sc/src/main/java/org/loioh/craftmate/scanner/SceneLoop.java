package org.loioh.craftmate.scanner;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.loioh.craftmate.CraftMate;
import org.loioh.craftmate.core.HTTP_Hook;
import org.loioh.craftmate.utils.Schedule;
import org.loioh.craftmate.vision.VisionCapture;
import org.loioh.craftmate.vision.VisionTrigger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SceneLoop:
 * - Called from client tick (CraftMateClientEvents)
 * - Builds lightweight scene snapshot
 * - Decides whether to trigger a vision screenshot (rate-limited)
 *
 * IMPORTANT: actual framebuffer read happens only in VisionCapture via RenderGuiOverlayEvent.Post.
 */
public final class SceneLoop {

    private SceneLoop() {}

    private static final Map<UUID, VisionTrigger.State> VISION_STATE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SCENE_POST_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SCENE_INFLIGHT = new ConcurrentHashMap<>();

    // Hard guards (NOT user-configurable) to avoid cost abuse
    private static final int DEFAULT_VISION_MIN_INTERVAL_TICKS = 20 * 30;      // >= 30s
    private static final int DEFAULT_VISION_DANGER_MIN_INTERVAL_TICKS = 20 * 10; // 10s when in danger

    public static void tick(UUID playerId, boolean enabled, int intervalTicks, int radiusBlocks) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Player p = mc.player;
        if (p == null || mc.level == null) return;

        // Build a lightweight snapshot (deep=false for speed)
        JsonObject scene = SceneScanner.buildSceneSnapshotV2(p, radiusBlocks, false, 30, 20);
        // Provide a monotonic tick for vision triggering (VisionTrigger uses this).
        scene.addProperty("tick", p.tickCount);

        // --- (A) Post scene snapshot to backend (/craftmate/scene)
        // This enables the backend to speak based on what it "sees".
        // Non-blocking + guarded against overlap.
        long nowMs = System.currentTimeMillis();
        long minPostMs = Math.max(500L, intervalTicks * 50L);
        long lastMs = LAST_SCENE_POST_MS.getOrDefault(playerId, 0L);
        boolean inflight = SCENE_INFLIGHT.getOrDefault(playerId, false);
        if (!inflight && (nowMs - lastMs) >= minPostMs) {
            SCENE_INFLIGHT.put(playerId, true);
            LAST_SCENE_POST_MS.put(playerId, nowMs);
            Schedule.runTaskAsync(() -> {
                try {
                    Object[] answer = HTTP_Hook.postScene(playerId, scene);
                    Schedule.runTask(() -> {
                        try {
                            CraftMate.processAnswer(p, answer);
                        } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {
                } finally {
                    SCENE_INFLIGHT.put(playerId, false);
                }
            });
        }

        // Decide capture (use hard minimums so users cannot set it too low)
        int minInterval = Math.max(DEFAULT_VISION_MIN_INTERVAL_TICKS, intervalTicks);
        int dangerMin = DEFAULT_VISION_DANGER_MIN_INTERVAL_TICKS;

        VisionTrigger.State st = VISION_STATE.computeIfAbsent(playerId, k -> new VisionTrigger.State());
        VisionTrigger.Decision dec = VisionTrigger.shouldCapture(st, scene, minInterval, dangerMin);

        if (!dec.shouldCapture) return;

        // Build hint payload for backend (cheap)
        JsonObject hint = new JsonObject();
        hint.addProperty("reason", dec.reason);
        hint.addProperty("activityKey", dec.activityKey);
        hint.addProperty("poiKey", dec.poiKey);
        hint.addProperty("dangerKey", dec.dangerKey);
        hint.addProperty("tick", (int) (mc.level.getGameTime() & 0x7fffffff));
        hint.add("scene", scene);

        // Schedule capture (render thread will pick it up)
        VisionCapture.requestCapture(playerId, hint);
    }
}
