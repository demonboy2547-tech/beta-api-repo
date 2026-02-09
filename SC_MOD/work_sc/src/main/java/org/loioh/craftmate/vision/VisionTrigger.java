package org.loioh.craftmate.vision;

import com.google.gson.JsonObject;

/**
 * Cheap decision engine for when to take a screenshot for vision.
 * Goal: "เหมือนนั่งดูจอผู้เล่นจริง" แต่ประหยัด (ไม่ยิงทุก tick)
 */
public class VisionTrigger {

    public static class State {
        public int lastCaptureTick = -999999;
        public String lastActivityKey = "";
        public String lastPoiKey = "";
        public String lastDangerKey = "";
        public int stableTicks = 0;
    }

    public static class Decision {
        public final boolean shouldCapture;
        public final String reason;
        public final String activityKey;
        public final String poiKey;
        public final String dangerKey;

        public Decision(boolean shouldCapture, String reason, String activityKey, String poiKey, String dangerKey) {
            this.shouldCapture = shouldCapture;
            this.reason = reason == null ? "" : reason;
            this.activityKey = activityKey == null ? "" : activityKey;
            this.poiKey = poiKey == null ? "" : poiKey;
            this.dangerKey = dangerKey == null ? "" : dangerKey;
        }
    }

    public static Decision shouldCapture(State st, JsonObject scene, int minIntervalTicks, int dangerMinIntervalTicks) {
        int tick = getInt(scene, "tick");
        String activityKey = getStr(scene, "activityKey");
        String poiKey = getStr(scene, "poiKey");
        String dangerKey = getStr(scene, "dangerKey");

        boolean activityChanged = !activityKey.equals(st.lastActivityKey);
        boolean poiChanged = !poiKey.equals(st.lastPoiKey);
        boolean dangerChanged = !dangerKey.equals(st.lastDangerKey);

        // danger urgency
        boolean isDanger = dangerKey != null && !dangerKey.isEmpty() && !dangerKey.equals("none") && !dangerKey.equals("safe");
        int minTicks = isDanger ? dangerMinIntervalTicks : minIntervalTicks;

        // stability counter
        if (!activityChanged && !poiChanged && !dangerChanged) st.stableTicks++;
        else st.stableTicks = 0;

        boolean intervalOk = (tick - st.lastCaptureTick) >= minTicks;

        boolean should = false;
        String reason = "";

        // Capture on important changes (activity/poi/danger)
        if (intervalOk && (dangerChanged && isDanger)) { should = true; reason = "danger"; }
        else if (intervalOk && activityChanged) { should = true; reason = "activity"; }
        else if (intervalOk && poiChanged) { should = true; reason = "poi"; }
        // Also capture occasionally when stable (to refresh context)
        else if (intervalOk && st.stableTicks >= (minIntervalTicks * 3)) { should = true; reason = "idle_refresh"; }

        if (should) {
            st.lastCaptureTick = tick;
            st.lastActivityKey = activityKey;
            st.lastPoiKey = poiKey;
            st.lastDangerKey = dangerKey;
            st.stableTicks = 0;
        }

        return new Decision(should, reason, activityKey, poiKey, dangerKey);
    }

    private static String getStr(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return "";
        try { return o.get(k).getAsString(); } catch (Exception e) { return ""; }
    }

    private static int getInt(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) return 0;
        try { return o.get(k).getAsInt(); } catch (Exception e) { return 0; }
    }
}
