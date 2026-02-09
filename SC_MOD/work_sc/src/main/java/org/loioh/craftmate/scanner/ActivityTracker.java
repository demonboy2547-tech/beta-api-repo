package org.loioh.craftmate.scanner;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, client-side activity & intention tracker.
 *
 * Goals:
 *  - Provide higher-level signals (activity, focus streak, look jitter, counters)
 *  - Avoid wallhack: only uses what the client already knows.
 *  - Keep CPU low: only O(1) per tick + bounded deques.
 */
public final class ActivityTracker {

    private ActivityTracker() {}

    private static final class State {
        Vec3 lastPos = null;
        float lastYaw = 0f;
        float lastPitch = 0f;
        long lastTickMs = 0L;

        String lastFocusKey = "";
        int focusStreakTicks = 0;

        // Rolling counters (timestamps in ms)
        final Deque<Long> swings = new ArrayDeque<>();
        final Deque<Long> swingMisses = new ArrayDeque<>();
        final Deque<Long> blockRightClicks = new ArrayDeque<>();
        final Deque<Long> blockLeftClicks = new ArrayDeque<>();
        final Deque<Long> attacks = new ArrayDeque<>();
        final Deque<Long> jumps = new ArrayDeque<>();
        final Deque<Long> damageTaken = new ArrayDeque<>();

        // Simple continuous metrics
        double lookDeltaAccum = 0.0; // degrees accumulated in the last window
        long lookDeltaWindowStartMs = 0L;
        double moveDeltaAccum = 0.0; // blocks accumulated
        long moveDeltaWindowStartMs = 0L;
    }

    private static final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static State st(UUID id) {
        return states.computeIfAbsent(id, k -> new State());
    }

    /** Called every client tick (safe to call often). */
    public static void onTick(Player player, String focusKey) {
        if (player == null) return;
        UUID id = player.getUUID();
        State s = st(id);

        long now = System.currentTimeMillis();
        if (s.lastTickMs == 0L) {
            s.lastTickMs = now;
            s.lastPos = player.position();
            s.lastYaw = player.getYRot();
            s.lastPitch = player.getXRot();
            s.lookDeltaWindowStartMs = now;
            s.moveDeltaWindowStartMs = now;
        }

        // focus streak
        if (focusKey == null) focusKey = "";
        if (focusKey.equals(s.lastFocusKey) && !focusKey.isBlank()) {
            s.focusStreakTicks++;
        } else {
            s.focusStreakTicks = 0;
            s.lastFocusKey = focusKey;
        }

        // look delta (degrees)
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float dy = Mth.wrapDegrees(yaw - s.lastYaw);
        float dp = pitch - s.lastPitch;
        s.lookDeltaAccum += (Math.abs(dy) + Math.abs(dp));
        s.lastYaw = yaw;
        s.lastPitch = pitch;

        // movement delta (blocks)
        Vec3 pos = player.position();
        if (s.lastPos != null) {
            s.moveDeltaAccum += pos.distanceTo(s.lastPos);
        }
        s.lastPos = pos;

        // periodically trim windows (keep CPU low)
        trim(s, now);
        s.lastTickMs = now;
    }

    private static void trim(State s, long now) {
        // rolling counter windows (we compute for 3s and 5s)
        trimDeque(s.swings, now, 6000);
        trimDeque(s.swingMisses, now, 6000);
        trimDeque(s.blockRightClicks, now, 6000);
        trimDeque(s.blockLeftClicks, now, 6000);
        trimDeque(s.attacks, now, 6000);
        trimDeque(s.jumps, now, 6000);
        trimDeque(s.damageTaken, now, 6000);

        // reset accum windows every ~3s
        if (s.lookDeltaWindowStartMs == 0L) s.lookDeltaWindowStartMs = now;
        if (now - s.lookDeltaWindowStartMs > 3000) {
            s.lookDeltaAccum = 0.0;
            s.lookDeltaWindowStartMs = now;
        }
        if (s.moveDeltaWindowStartMs == 0L) s.moveDeltaWindowStartMs = now;
        if (now - s.moveDeltaWindowStartMs > 3000) {
            s.moveDeltaAccum = 0.0;
            s.moveDeltaWindowStartMs = now;
        }
    }

    private static void trimDeque(Deque<Long> dq, long now, long maxAgeMs) {
        while (!dq.isEmpty()) {
            long t = dq.peekFirst();
            if (now - t <= maxAgeMs) break;
            dq.pollFirst();
        }
    }

    // ------------------------------------------------------------------
    // Signals from input/events
    // ------------------------------------------------------------------
    public static void noteSwing(Player player) {
        if (player == null) return;
        st(player.getUUID()).swings.addLast(System.currentTimeMillis());
    }

    public static void noteSwingMiss(Player player) {
        if (player == null) return;
        st(player.getUUID()).swingMisses.addLast(System.currentTimeMillis());
    }

    public static void noteRightClickBlock(Player player) {
        if (player == null) return;
        st(player.getUUID()).blockRightClicks.addLast(System.currentTimeMillis());
    }

    public static void noteLeftClickBlock(Player player) {
        if (player == null) return;
        st(player.getUUID()).blockLeftClicks.addLast(System.currentTimeMillis());
    }

    public static void noteAttackEntity(Player player) {
        if (player == null) return;
        st(player.getUUID()).attacks.addLast(System.currentTimeMillis());
    }

    public static void noteJump(Player player) {
        if (player == null) return;
        st(player.getUUID()).jumps.addLast(System.currentTimeMillis());
    }

    public static void noteDamageTaken(Player player) {
        if (player == null) return;
        st(player.getUUID()).damageTaken.addLast(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Build JSON fragments
    // ------------------------------------------------------------------
    public static JsonObject buildActivityJson(Player player) {
        JsonObject a = new JsonObject();
        if (player == null) return a;
        State s = st(player.getUUID());
        long now = System.currentTimeMillis();

        // counts in last 3 seconds (approx)
        int swings3 = countSince(s.swings, now, 3000);
        int miss3 = countSince(s.swingMisses, now, 3000);
        int rc3 = countSince(s.blockRightClicks, now, 3000);
        int lc3 = countSince(s.blockLeftClicks, now, 3000);
        int atk3 = countSince(s.attacks, now, 3000);
        int jump3 = countSince(s.jumps, now, 3000);
        int dmg5 = countSince(s.damageTaken, now, 5000);

        a.addProperty("focus_streak_sec", round1(s.focusStreakTicks / 20.0));
        a.addProperty("look_delta_3s", round1(s.lookDeltaAccum));
        a.addProperty("move_dist_3s", round1(s.moveDeltaAccum));

        JsonObject ctr = new JsonObject();
        ctr.addProperty("swings_3s", swings3);
        ctr.addProperty("swing_miss_3s", miss3);
        ctr.addProperty("right_click_block_3s", rc3);
        ctr.addProperty("left_click_block_3s", lc3);
        ctr.addProperty("attacks_3s", atk3);
        ctr.addProperty("jumps_3s", jump3);
        ctr.addProperty("damage_taken_5s", dmg5);
        a.add("counters", ctr);

        // Simple activity classification
        String activity = classify(player, swings3, miss3, rc3, lc3, atk3, dmg5);
        a.addProperty("activity", activity);
        a.addProperty("activity_confidence", confidence(activity, swings3, rc3, lc3, atk3, dmg5));

        // intention hints
        a.addProperty("intent", inferIntent(player, activity));

        // held items (for speech)
        try {
            ResourceLocation main = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
            if (main != null) a.addProperty("held_mainhand", main.toString());
            ResourceLocation off = BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem());
            if (off != null) a.addProperty("held_offhand", off.toString());
        } catch (Throwable ignored) {}

        return a;
    }

    private static int countSince(Deque<Long> dq, long now, long windowMs) {
        int c = 0;
        for (Long t : dq) {
            if (now - t <= windowMs) c++;
        }
        return c;
    }

    private static String classify(Player player, int swings3, int miss3, int rc3, int lc3, int atk3, int dmg5) {
        // prioritize danger/fight signals
        if (dmg5 > 0 || atk3 > 0) return "fighting";
        if (player.isSprinting()) return "running";
        if (player.isShiftKeyDown()) return "sneaking";
        if (lc3 >= 2 && swings3 >= 2) return "mining";
        if (rc3 >= 2) {
            // right-click spam could be building/using
            return "building";
        }
        if (swings3 >= 2 && miss3 >= 2) return "fighting"; // flailing
        // movement-based
        if (player.getDeltaMovement().horizontalDistanceSqr() > 0.002) return "exploring"; // tolerate slow flight
        return "idle";
    }

    private static double confidence(String activity, int swings3, int rc3, int lc3, int atk3, int dmg5) {
        switch (activity) {
            case "fighting": return Math.min(1.0, 0.4 + 0.15 * (atk3 + dmg5 + swings3));
            case "mining": return Math.min(1.0, 0.35 + 0.2 * (lc3));
            case "building": return Math.min(1.0, 0.3 + 0.2 * (rc3));
            case "running": return 0.6;
            case "exploring": return 0.55;
            case "sneaking": return 0.5;
            default: return 0.35;
        }
    }

    private static String inferIntent(Player player, String activity) {
        if (player == null) return "";
        if (player.isUnderWater()) return "survive";
        if ("fighting".equals(activity)) return "survive";
        if ("mining".equals(activity)) return "gather";
        if ("building".equals(activity)) return "build";
        if ("exploring".equals(activity)) return "explore";
        return "";
    }

    public static String focusKeyFromFocusJson(JsonObject focus) {
        if (focus == null) return "";
        try {
            String type = focus.has("type") ? focus.get("type").getAsString() : "";
            String id = focus.has("id") ? focus.get("id").getAsString() : "";
            if (type.isBlank() && id.isBlank()) return "";
            return type + ":" + id;
        } catch (Throwable t) {
            return "";
        }
    }

    public static JsonObject entityHint(Entity e) {
        JsonObject jo = new JsonObject();
        if (e == null) return jo;
        try {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (id != null) jo.addProperty("id", id.toString());
            jo.addProperty("name", e.getName().getString());
            if (e instanceof Mob mob) {
                ResourceLocation held = BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem());
                if (held != null) jo.addProperty("held_item", held.toString());
            }
        } catch (Throwable ignored) {}
        return jo;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
