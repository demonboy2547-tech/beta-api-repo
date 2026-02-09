package org.loioh.craftmate;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {

    // ------------------------------------------------------------------
    // Legacy static fields (kept for compatibility with milestone-3 code)
    // Many classes import these statically (e.g. import static Config.entityName)
    // ------------------------------------------------------------------
    public static boolean debugLog = false;

    public static String entityName = "null";
    public static String entityLocation = "0.5; 0; 0.5";
    public static int entityID = 9993;
    public static boolean entityUseTrueInvisible = false;



    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ---- General / gameplay ----
    public static final ForgeConfigSpec.BooleanValue DISABLE_ON_SERVER;
    public static final ForgeConfigSpec.ConfigValue<String> AI_CHAT_PREFIX;
    public static final ForgeConfigSpec.BooleanValue POST_EVERY_NEW_EVENT;
    public static final ForgeConfigSpec.IntValue POST_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_EVENTS_CACHE_AMOUNT;

    public static final ForgeConfigSpec.BooleanValue STOP_CURRENT_AUDIO_IF_RECEIVE_NEW_ONE;
    // Entity settings are kept internal (not exposed in common config)

    // ---- Backend ----
    public static final ForgeConfigSpec.ConfigValue<String> LINK_API;      // base endpoint
    public static final ForgeConfigSpec.ConfigValue<String> LINK_SCENE_API; // scene snapshot endpoint
    public static final ForgeConfigSpec.ConfigValue<String> API_TOKEN;     // Bearer
    public static final ForgeConfigSpec.ConfigValue<String> CLIENT_ID;     // X-CM-Id

    // ---- Scene scanner (realtime perception) ----
    public static final ForgeConfigSpec.BooleanValue SCENE_SCANNER_ENABLED;
    public static final ForgeConfigSpec.IntValue SCENE_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue SCENE_RADIUS_BLOCKS;

    // v2 scanner tuning (quality > size)
    public static final ForgeConfigSpec.IntValue SCENE_MAX_ENTITIES;
    public static final ForgeConfigSpec.IntValue SCENE_MAX_POIS;

    // ---- Vision (optional async perception via screenshot) ----
    public static final ForgeConfigSpec.BooleanValue VISION_ENABLED;
    public static final ForgeConfigSpec.IntValue VISION_MAX_DIM; // resize max dimension (e.g., 512)
    public static final ForgeConfigSpec.IntValue VISION_MIN_INTERVAL_TICKS; // cooldown between captures
    public static final ForgeConfigSpec.IntValue VISION_DANGER_MIN_INTERVAL_TICKS; // shorter cooldown when in danger
    public static final ForgeConfigSpec.ConfigValue<String> LINK_VISION_API;

    // ---- Vision Debug ----
    public static final ForgeConfigSpec.BooleanValue VISION_DEBUG_ENABLED;
    public static final ForgeConfigSpec.BooleanValue VISION_DEBUG_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue VISION_DEBUG_TOAST;
    public static final ForgeConfigSpec.BooleanValue VISION_SAVE_LAST_ALWAYS;
    public static final ForgeConfigSpec.BooleanValue VISION_SAVE_CHAT_ALWAYS;
    public static final ForgeConfigSpec.BooleanValue VISION_ENABLE_F9_KEY;

    // General debug log (existing flag, but configurable)
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_ENABLED;
 // /craftmate/vision endpoint


    // HMAC is embedded for beta build; config does not expose it.
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("craftmate");

        DISABLE_ON_SERVER = BUILDER
                .comment("If true, disables CraftMate features when running on a dedicated server.")
                .define("disableOnServer", true);

        AI_CHAT_PREFIX = BUILDER
                .comment("Prefix used for CraftMate chat replies.")
                .define("aiChatPrefix", "&3&l[AI]: &r");

        POST_EVERY_NEW_EVENT = BUILDER
                .comment("If true, send events to backend each time a new event happens.")
                .define("postEveryNewEvent", false);

        // Restored from original mod: controls periodic/automatic posting.
        // NOTE: Set to -1 to disable. Any positive value means "send once every N ticks".
        POST_INTERVAL_TICKS = BUILDER
                .comment("How many ticks between automatic posting. Set to -1 to disable.")
                .defineInRange("postIntervalTicks", -1, -1, 2_000_000);

        MAX_EVENTS_CACHE_AMOUNT = BUILDER
                .comment("How many recent events can be sent in one request.")
                .defineInRange("maxEventsCacheAmount", 20, 1, 200);

        STOP_CURRENT_AUDIO_IF_RECEIVE_NEW_ONE = BUILDER
                .comment("If true, stop current audio and play new audio immediately. If false, wait until current audio ends.")
                .define("stopCurrentAudioIfReceiveNewOne", true);

        LINK_API = BUILDER
                .comment("CraftMate API base URL. Example: https://api.craftmate.workers.dev/craftmate/chat")
                .define("linkAPI", "https://api.craftmate.workers.dev/craftmate/chat");

        LINK_SCENE_API = BUILDER
                .comment("CraftMate Scene endpoint. Example: https://api.craftmate.workers.dev/craftmate/scene")
                .define("linkSceneAPI", "https://api.craftmate.workers.dev/craftmate/scene");

        API_TOKEN = BUILDER
                .comment("CraftMate API token (Bearer). Leave blank for no auth.")
                .define("apiToken", "");

        CLIENT_ID = BUILDER
                .comment("Client ID header (X-CM-Id).")
                .define("clientId", "craftmate");

        SCENE_SCANNER_ENABLED = BUILDER
                .comment("Enable realtime scene scanner (sends snapshots to backend).")
                .define("sceneScannerEnabled", true);

        SCENE_INTERVAL_TICKS = BUILDER
                .comment("How many ticks between scene snapshots (20 ticks = ~1s).")
                .defineInRange("sceneIntervalTicks", 20, 5, 400);

        SCENE_RADIUS_BLOCKS = BUILDER
                .comment("Radius (in blocks) used to collect nearby entities for the scene snapshot.")
                .defineInRange("sceneRadiusBlocks", 18, 6, 48);

        SCENE_MAX_ENTITIES = BUILDER
                .comment("Max number of nearby entities included in one scene snapshot.")
                .defineInRange("sceneMaxEntities", 28, 5, 120);

        SCENE_MAX_POIS = BUILDER
                .comment("Max number of visible POI blocks included in deep snapshots.")
                .defineInRange("sceneMaxPois", 24, 0, 120);

        // ---- Vision (async screenshot -> /craftmate/vision) ----
        // Defaults are tuned for "budget" usage: only capture on meaningful changes,
        // and keep the image reasonably small. You can tweak these in config.
        VISION_ENABLED = BUILDER
                .comment("Enable periodic/triggered screenshots to /craftmate/vision.")
                .define("visionEnabled", true);

        VISION_MAX_DIM = BUILDER
                .comment("Max image dimension after resize (e.g., 512).")
                .defineInRange("visionMaxDim", 512, 128, 2048);

        VISION_MIN_INTERVAL_TICKS = BUILDER
                .comment("Minimum ticks between vision captures in normal play (20 ticks = 1s).")
                .defineInRange("visionMinIntervalTicks", 200, 20, 20 * 60 * 10);

        VISION_DANGER_MIN_INTERVAL_TICKS = BUILDER
                .comment("Minimum ticks between vision captures when danger is detected.")
                .defineInRange("visionDangerMinIntervalTicks", 80, 5, 20 * 60 * 10);

        LINK_VISION_API = BUILDER
                .comment("Vision endpoint (e.g., https://.../craftmate/vision).")
                .define("linkVisionApi", "https://api.craftmate.workers.dev/craftmate/vision");


VISION_DEBUG_ENABLED = BUILDER
        .comment("Enable extra vision debug logging + error popups (recommended while developing).")
        .define("visionDebugEnabled", true);

VISION_DEBUG_OVERLAY = BUILDER
        .comment("Show a small on-screen vision overlay (top-left) with pipeline status.")
        .define("visionDebugOverlay", true);

VISION_DEBUG_TOAST = BUILDER
        .comment("Show in-game red chat messages when vision throws exceptions.")
        .define("visionDebugToast", true);

	DEBUG_LOG_ENABLED = BUILDER
	        .comment("Enable general debug logging (kept for backward compatibility with older call sites).")
	        .define("debugLogEnabled", true);

VISION_SAVE_LAST_ALWAYS = BUILDER
        .comment("Always save the last encoded vision JPEG to .minecraft/craftmate/debug_last.jpg")
        .define("visionSaveLastAlways", true);

VISION_SAVE_CHAT_ALWAYS = BUILDER
        .comment("Save a timestamped vision JPEG under .minecraft/screenshots/craftmate/ for reason=chat.")
        .define("visionSaveChatAlways", true);

VISION_ENABLE_F9_KEY = BUILDER
        .comment("Enable F9 manual vision capture keybind (debug).")
        .define("visionEnableF9Key", true);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * Keep old call sites working. Called from mod init.
     * Nothing special is required here, but we keep it for compatibility.
     */
    public static void _init() {
        // Intentionally no syncing here. Sensitive/internal settings are embedded.
    }

    /** Read a config value by key. */
    public static Object getFromConfig(String key, Object def) {
        try {
            switch (key) {
                case "disableOnServer": return DISABLE_ON_SERVER.get();
                case "aiChatPrefix": return AI_CHAT_PREFIX.get();
                case "postEveryNewEvent": return POST_EVERY_NEW_EVENT.get();
                case "postIntervalTicks": return POST_INTERVAL_TICKS.get();
                case "maxEventsCacheAmount": return MAX_EVENTS_CACHE_AMOUNT.get();
                case "stopCurrentAudioIfReceiveNewOne": return STOP_CURRENT_AUDIO_IF_RECEIVE_NEW_ONE.get();
                case "linkAPI": return LINK_API.get();
                case "linkSceneAPI": return LINK_SCENE_API.get();
                case "apiToken": return API_TOKEN.get();
                case "clientId": return CLIENT_ID.get();
                case "sceneScannerEnabled": return SCENE_SCANNER_ENABLED.get();
                case "sceneIntervalTicks": return SCENE_INTERVAL_TICKS.get();
                case "sceneRadiusBlocks": return SCENE_RADIUS_BLOCKS.get();
                case "sceneMaxEntities": return SCENE_MAX_ENTITIES.get();
                case "sceneMaxPois": return SCENE_MAX_POIS.get();
                case "visionEnabled": return VISION_ENABLED.get();
                case "visionMaxDim": return VISION_MAX_DIM.get();
                case "visionMinIntervalTicks": return VISION_MIN_INTERVAL_TICKS.get();
                case "visionDangerMinIntervalTicks": return VISION_DANGER_MIN_INTERVAL_TICKS.get();
                case "linkVisionAPI": return LINK_VISION_API.get();
                default: return def;
            }
        } catch (Throwable t) {
            return def;
        }
    }

    public static Object getFromConfig(String key) {
        return getFromConfig(key, "");
    }
    // --- Vision guards (hard-coded to avoid cost abuse) ---
    public static boolean getVisionEnabled() {
        try { return VISION_ENABLED.get(); } catch (Throwable t) { return true; }
    }

    public static int getVisionMaxDim() {
        try { return VISION_MAX_DIM.get(); } catch (Throwable t) { return 512; }
    }



// --- Vision debug (dev tooling) ---
public static boolean getVisionDebugEnabled() {
    try { return VISION_DEBUG_ENABLED.get(); } catch (Throwable t) { return false; }
}

public static boolean getVisionDebugOverlay() {
    try { return VISION_DEBUG_OVERLAY.get(); } catch (Throwable t) { return false; }
}

public static boolean getVisionDebugToast() {
    try { return VISION_DEBUG_TOAST.get(); } catch (Throwable t) { return false; }
}

public static boolean getVisionSaveLastAlways() {
    try { return VISION_SAVE_LAST_ALWAYS.get(); } catch (Throwable t) { return false; }
}

public static boolean getVisionSaveChatAlways() {
    try { return VISION_SAVE_CHAT_ALWAYS.get(); } catch (Throwable t) { return false; }
}

public static boolean getVisionEnableF9Key() {
    try { return VISION_ENABLE_F9_KEY.get(); } catch (Throwable t) { return false; }
}

public static boolean getDebugLog() {
	    // Backward compat: previous code used Config.debugLog
	    try {
	        return DEBUG_LOG_ENABLED.get() || debugLog || getVisionDebugEnabled();
	    } catch (Throwable t) {
	        return debugLog;
	    }
}

}