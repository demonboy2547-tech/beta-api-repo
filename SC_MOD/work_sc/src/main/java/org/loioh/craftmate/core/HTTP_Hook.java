package org.loioh.craftmate.core;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.loioh.craftmate.CraftMate;
import org.loioh.craftmate.utils.SecuritySigner;
import org.loioh.craftmate.vision.VisionCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.loioh.craftmate.CraftMate.getFromConfig;

public class HTTP_Hook {

private static void addSignedHeaders(java.net.http.HttpRequest.Builder b, String method, String path, byte[] bodyBytes, String clientId) {
    try {
        long ts = System.currentTimeMillis();
        String secret = String.valueOf(CraftMate.getFromConfig("linkAPIKey"));
        if (secret == null) secret = "";
        String bodyHash = SecuritySigner.sha256Hex(bodyBytes == null ? new byte[0] : bodyBytes);
        String canon = SecuritySigner.canonicalV2Post(ts, clientId, method, path, bodyHash);
        String sig = SecuritySigner.hmacSha256Hex(secret, canon);
        b.header("X-CM-Id", clientId == null ? "" : clientId);
        b.header("X-CM-Ts", String.valueOf(ts));
        b.header("X-CM-Sig", sig);
    } catch (Throwable t) {
        // fall back to legacy headers
        CraftMate.LOGGER.warn("[VISION] sign v2 failed: {}", String.valueOf(t));
    }
}


    private static HttpClient client;
    private static Gson gson;
    // -------------------------
    // Vision debug telemetry (safe for public)
    // Stores last HTTP status + sanitized snippet for troubleshooting.
    // -------------------------
    public static volatile int lastVisionStatus = 0;
    public static volatile String lastVisionBody = "";
    public static volatile String lastVisionError = "";
    public static volatile long lastVisionAtMs = 0L;

    private static String sanitizeDebugSnippet(String s) {
        if (s == null) return "";
        // Normalize whitespace and cap length
        String out = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        out = out.trim();
        if (out.length() > 200) out = out.substring(0, 200);

        // Mask anything that looks like a token/secret-ish string
        // Bearer tokens, API keys, long base64-ish blobs, etc.
        out = out.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer ***");
        out = out.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s\"\']+)", "$1***");
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*)([^\\s\"\']+)", "$1***");
        out = out.replaceAll("([A-Za-z0-9+/]{40,}={0,2})", "***");
        return out;
    }

    public static String getLastVisionDebugLine() {
        long ageMs = (lastVisionAtMs > 0) ? (System.currentTimeMillis() - lastVisionAtMs) : -1;
        String age = (ageMs >= 0) ? (ageMs/1000L + "s ago") : "n/a";
        String status = (lastVisionStatus == 0) ? "n/a" : String.valueOf(lastVisionStatus);
        String err = (lastVisionError == null || lastVisionError.isBlank()) ? "" : (" err=" + sanitizeDebugSnippet(lastVisionError));
        String body = (lastVisionBody == null || lastVisionBody.isBlank()) ? "" : (" body=" + sanitizeDebugSnippet(lastVisionBody));
        return "Vision HTTP status=" + status + " (" + age + ")" + err + body;
    }


    public HTTP_Hook(){
        client = HttpClient.newHttpClient();
        gson = new GsonBuilder().setPrettyPrinting().create();
    }
    /*
    public static String postFormat = """{
        \"player_id\": \"%value1%\",
        \"character_id\": \"%value2%\",
        \"message\": \"%value3%\",
        \"context\": {
            \"health\": %value4%,
            \"hunger\": %value5%,
            \"dimension\": \"%value6%\",
            \"biome\": \"%value7%\",
            \"is_daytime\": %value8%,
            \"recent_events\": %value9%
        }
    }""";

    public static String responseFormat = """{
        \"reply\": \"%value1%\",
        \"mood\": \"%value2%\",
        \"memory_mode\": \"%value3%\",
        \"tts_url\": \"%value4%\"
    }""";
    */



    public static Object[] postData(Object[] data) {
        try {
            JsonObject json = createPostJsonfromData(data);

            // --- Build request body (must match what we sign) ---
            String bodyStr = gson.toJson(json);

            // --- Endpoint from config (allows switching public/beta) ---
            String endpoint = String.valueOf(getFromConfig("linkAPI"));
            URI uri = URI.create(endpoint);

            // --- Security headers ---
            // CraftMate.getFromConfig requires a default value param.
            // Keep sane defaults so the mod still runs even if config is missing new keys.
            String bearer = String.valueOf(getFromConfig("apiToken", ""));
            String clientId = String.valueOf(getFromConfig("clientId", "craftmate"));
            // HMAC is always enabled (embedded secret) to match Cloudflare security layer
            String hmacSecret = SecuritySigner.EMBEDDED_BETA_HMAC_SECRET;
            boolean enableHmac = true;

            long ts = System.currentTimeMillis() / 1000L;
            String tsStr = Long.toString(ts);
            String path = uri.getPath();

            String sig = "";
            if (enableHmac && hmacSecret != null && !hmacSecret.isBlank()) {
                String bodyHash = SecuritySigner.sha256Hex(bodyStr.getBytes(StandardCharsets.UTF_8));
                // Canonical v2: ts\nclientId\nMETHOD\nPATH\nbodyHash
                String canonical = tsStr + "\n" + clientId + "\nPOST\n" + path + "\n" + bodyHash;
                sig = SecuritySigner.hmacSha256Hex(hmacSecret, canonical);
            }

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json");

            if (bearer != null && !bearer.isBlank()) {
                // allow user to paste either raw token or full "Bearer xxx"
                if (bearer.toLowerCase().startsWith("bearer ")) b.header("Authorization", bearer);
                else b.header("Authorization", "Bearer " + bearer);
            }

            if (enableHmac && sig != null && !sig.isBlank()) {
                b.header("X-CM-Id", clientId);
                b.header("X-CM-Ts", tsStr);
                b.header("X-CM-Sig", sig);
            }

            HttpRequest request = b
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            int code = response.statusCode();
            boolean success = (int)(code/100) == 2;
            //CraftMate.getInstance().log("Response code: "+ code +" ; "+success);
            CraftMate.getInstance().log("Body: "+ response.body());

            if(success){
                JsonObject json0 = gson.fromJson(response.body(), JsonObject.class);
                // Phase 3+: backend may request an immediate vision capture (e.g., GPT needs more context).
                try {
                    maybeTriggerVisionFromChatResponse(data, json0);
                } catch (Throwable ignored) {}
                Object[] answer = getResponseDatafromJson(json0);
                return answer;
            }

            // Non-2xx response: return a structured error so caller can show it in-game
            String err = extractErrorMessage(response.body());
            return new Object[]{"__CM_ERROR__", "HTTP " + code, err, null};

        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            return new Object[]{"__CM_ERROR__", "Network error", msg, null};
        }

    }


    /**
     * Send a scene snapshot to the backend. This uses the same Bearer + embedded HMAC security
     * headers as postData(), but posts to linkSceneAPI.
     *
     * Expected backend response shape matches chat: {reply, mood, memory_mode, tts_url}
     */
    public static Object[] postScene(UUID playerId, JsonObject scene) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("player_id", String.valueOf(playerId));
            json.add("scene", scene);

            String bodyStr = gson.toJson(json);

            String endpoint = String.valueOf(getFromConfig("linkSceneAPI", ""));
            if (endpoint == null || endpoint.isBlank()) {
                return new Object[]{"__CM_ERROR__", "Missing config", "linkSceneAPI is empty", null};
            }
            URI uri = URI.create(endpoint);

            String bearer = String.valueOf(getFromConfig("apiToken", ""));
            String clientId = String.valueOf(getFromConfig("clientId", "craftmate"));
            String hmacSecret = SecuritySigner.EMBEDDED_BETA_HMAC_SECRET;
            boolean enableHmac = true;

            long ts = System.currentTimeMillis() / 1000L;
            String tsStr = Long.toString(ts);
            String path = uri.getPath();

            String sig = "";
            if (enableHmac && hmacSecret != null && !hmacSecret.isBlank()) {
                String bodyHash = SecuritySigner.sha256Hex(bodyStr.getBytes(StandardCharsets.UTF_8));
                String canonical = tsStr + "\n" + clientId + "\nPOST\n" + path + "\n" + bodyHash;
                sig = SecuritySigner.hmacSha256Hex(hmacSecret, canonical);
            }

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json");

            if (bearer != null && !bearer.isBlank()) {
                if (bearer.toLowerCase().startsWith("bearer ")) b.header("Authorization", bearer);
                else b.header("Authorization", "Bearer " + bearer);
            }

            if (enableHmac && sig != null && !sig.isBlank()) {
                b.header("X-CM-Id", clientId);
                b.header("X-CM-Ts", tsStr);
                b.header("X-CM-Sig", sig);
            }

            HttpRequest request = b
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            boolean success = (int) (code / 100) == 2;

            if (success) {
                JsonObject json0 = gson.fromJson(response.body(), JsonObject.class);
                return getResponseDatafromJson(json0);
            }

            String err = extractErrorMessage(response.body());
            return new Object[]{"__CM_ERROR__", "HTTP " + code, err, null};

        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            return new Object[]{"__CM_ERROR__", "Network error", msg, null};
        }
    }

    private static String extractErrorMessage(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return null;

        // Try JSON: {"error": "..."} or {"message": "..."}
        try {
            JsonElement el = JsonParser.parseString(trimmed);
            if (el != null && el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("error") && !obj.get("error").isJsonNull()) {
                    return obj.get("error").getAsString();
                }
                if (obj.has("message") && !obj.get("message").isJsonNull()) {
                    return obj.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }

        // Fallback: return first ~200 chars
        if (trimmed.length() > 200) return trimmed.substring(0, 200);
        return trimmed;
    }

    public static JsonObject createPostJsonfromData(Object[] d) {

        JsonObject json = new JsonObject();

        json.addProperty("player_id", d[0].toString());
        json.addProperty("character_id", d[1].toString());
        json.addProperty("message", d[2] == null ? "" : d[2].toString());

// Attach lightweight vision metadata so backend can correlate chat ↔ last screenshot request.
// This does NOT include the image itself (sent separately to /vision).
try {
    JsonObject vision = new JsonObject();
    vision.addProperty("request_ms", VisionCapture.getLastEnqueuedVisionMs());
    vision.addProperty("reason", VisionCapture.getLastEnqueuedVisionReason());
    String vmsg = VisionCapture.getLastEnqueuedVisionMessage();
    if (vmsg != null && !vmsg.isBlank()) vision.addProperty("message", vmsg);
    json.add("vision", vision);
} catch (Throwable ignored) {}


        JsonObject context = new JsonObject();
        context.addProperty("health", (int) d[3]);
        context.addProperty("hunger", (int) d[4]);
        context.addProperty("dimension", d[5].toString());
        context.addProperty("biome", d[6].toString());
        context.addProperty("is_daytime", (boolean) d[7]);

        // recent events — массив строк
        JsonArray eventsArray = new JsonArray();
        String[] events = (String[]) d[8];
        for (String s : events) eventsArray.add(s);
        context.add("recent_events", eventsArray);

        json.add("context", context);

        return json;
    }


    /**
     * Phase 3+: backend may respond to /craftmate/chat with a vision request directive.
     * We treat it as a high-priority capture because the user is actively chatting.
     *
     * Expected response shapes (any):
     * - {"vision_request": true}
     * - {"vision_request": {"reason":"enemy_uncertain", "priority":"high"}}
     */
    private static void maybeTriggerVisionFromChatResponse(Object[] data, JsonObject resp) {
        if (resp == null || data == null || data.length < 3) return;
        if (!resp.has("vision_request") || resp.get("vision_request").isJsonNull()) return;

        boolean want = false;
        String reqReason = "gpt_request";

        try {
            JsonElement vr = resp.get("vision_request");
            if (vr.isJsonPrimitive() && vr.getAsJsonPrimitive().isBoolean()) {
                want = vr.getAsBoolean();
            } else if (vr.isJsonObject()) {
                want = true;
                JsonObject o = vr.getAsJsonObject();
                if (o.has("reason") && !o.get("reason").isJsonNull()) {
                    reqReason = o.get("reason").getAsString();
                }
            }
        } catch (Throwable ignored) {}

        if (!want) return;

        UUID playerId = null;
        try {
            if (data[0] instanceof UUID) playerId = (UUID) data[0];
            else playerId = UUID.fromString(String.valueOf(data[0]));
        } catch (Throwable ignored) {}
        if (playerId == null) return;

        String message = (data[2] == null ? "" : String.valueOf(data[2]));

        // Build a compact hint for backend (structured; safe)
        JsonObject hint = new JsonObject();
        hint.addProperty("reason", "gpt_request");
        hint.addProperty("request_reason", reqReason);
        hint.addProperty("source", "backend");
        if (message != null && !message.isBlank()) hint.addProperty("message", message);

        CraftMate.vLog("[Chat->Vision] backend requested vision; reqReason=" + reqReason);
        try {
            org.loioh.craftmate.vision.VisionCapture.forceCapture(playerId, hint, "tactical", message);
        } catch (Throwable t) {
            CraftMate.LOGGER.warn("[Chat->Vision] forceCapture failed: {}", String.valueOf(t));
        }
    }


    public static Object[] getResponseDatafromJson(JsonObject json) {
        return new Object[]{
                getJsonValue(json, "reply"),
                getJsonValue(json, "mood"),
                getJsonValue(json, "memory_mode"),
                getJsonValue(json, "tts_url")
        };
    }

    private static String getJsonValue(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        if(value.toLowerCase().equals("null") || value.equals("")){
            return  null;
        }
        return value;
    }



    /**
     * Async vision post: sends a small screenshot + compact hint to /craftmate/vision.
     * Best-effort: errors are swallowed to avoid impacting gameplay.
     */
    public static void postVision(UUID playerId, JsonObject hint, String imageB64, String mime, int w, int h, String frameHash) {
        if (imageB64 == null || imageB64.isEmpty()) {
            System.out.println("[CraftMate] postVision: missing imageB64; abort");
            return;
        }

        try {
            JsonObject json = new JsonObject();
            json.addProperty("player_id", String.valueOf(playerId));
            json.addProperty("ts", System.currentTimeMillis() / 1000L);
            if (frameHash != null && !frameHash.isBlank()) json.addProperty("frame_hash", frameHash);
            if (hint != null) json.add("hint", hint);

            JsonObject img = new JsonObject();
            img.addProperty("mime", mime != null ? mime : "image/jpeg");
            img.addProperty("w", w);
            img.addProperty("h", h);
            img.addProperty("image_b64", imageB64 != null ? imageB64 : "");
            json.add("image", img);

            String bodyStr = gson.toJson(json);

            String endpoint = String.valueOf(getFromConfig("linkVisionAPI", ""));
            if (endpoint == null || endpoint.isBlank()) {
                // derive from scene endpoint
                String sceneEp = String.valueOf(getFromConfig("linkSceneAPI", ""));
                if (sceneEp != null && !sceneEp.isBlank()) {
                    endpoint = sceneEp.replace("/craftmate/scene", "/craftmate/vision").replace("/scene", "/vision");
                }
            }
            if (endpoint == null || endpoint.isBlank()) {
                CraftMate.LOGGER.warn("[Vision] vision endpoint empty (linkVisionAPI + linkSceneAPI). Skipping.");
                return;
            }

            URI uri = URI.create(endpoint);

            String bearer = String.valueOf(getFromConfig("apiToken", ""));
            String clientId = String.valueOf(getFromConfig("clientId", "craftmate"));
            String hmacSecret = SecuritySigner.EMBEDDED_BETA_HMAC_SECRET;
            boolean enableHmac = true;

            long ts = System.currentTimeMillis() / 1000L;
            String tsStr = Long.toString(ts);
            String path = uri.getPath();

            String sig = "";
            if (enableHmac && hmacSecret != null && !hmacSecret.isBlank()) {
                String bodyHash = SecuritySigner.sha256Hex(bodyStr.getBytes(StandardCharsets.UTF_8));
                String canonical = tsStr + "\n" + clientId + "\nPOST\n" + path + "\n" + bodyHash;
                sig = SecuritySigner.hmacSha256Hex(hmacSecret, canonical);
            }

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json");

            if (bearer != null && !bearer.isBlank()) {
                if (bearer.toLowerCase().startsWith("bearer ")) b.header("Authorization", bearer);
                else b.header("Authorization", "Bearer " + bearer);
            }

            if (enableHmac && sig != null && !sig.isBlank()) {
                b.header("X-CM-Id", clientId);
                b.header("X-CM-Ts", tsStr);
                b.header("X-CM-Sig", sig);
            }

            // fire-and-forget (but still async-safe)
            HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(bodyStr)).build();
            client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        int sc = res.statusCode();
                        String body = res.body();

                        // Update telemetry for HUD/logging (sanitized)
                        lastVisionStatus = sc;
                        lastVisionBody = (body == null ? "" : body);
                        lastVisionError = "";
                        lastVisionAtMs = System.currentTimeMillis();

                        try { org.loioh.craftmate.vision.VisionDebug.setLastHttp("status=" + sc); } catch (Throwable ignored) {}
                        if (sc / 100 == 2) { try { org.loioh.craftmate.vision.VisionDebug.markOk(); } catch (Throwable ignored) {} }

                        String line = "[Vision] status=" + sc + " body=" + sanitizeDebugSnippet(body);
                        // During dev, also echo to CraftMate.log (respects Config.debugLog)
                        CraftMate.vLog(line);

                        // Phase 3+: if backend returns a one-line spoken_line, surface it immediately.
                        if (sc / 100 == 2 && body != null && !body.isBlank()) {
                            try {
                                JsonElement el = JsonParser.parseString(body);
                                if (el != null && el.isJsonObject()) {
                                    JsonObject obj = el.getAsJsonObject();
                                    if (obj.has("spoken_line") && !obj.get("spoken_line").isJsonNull()) {
                                        String spoken = obj.get("spoken_line").getAsString();
                                        if (spoken != null && !spoken.isBlank()) {
                                            final String spokenFinal = spoken;
	                                            // Post to the client thread safely (no dependency on custom schedulers)
	                                            try {
	                                                Minecraft.getInstance().execute(() -> {
	                                                    try {
	                                                        if (Minecraft.getInstance().player != null) {
	                                                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("§b[Vision] §r" + spokenFinal));
	                                                        }
	                                                    } catch (Throwable ignored) {}
	                                                });
	                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }

                        if (sc / 100 != 2) {
                            CraftMate.LOGGER.warn("{} (uri={})", line, uri);
                        } else {
                            CraftMate.LOGGER.debug("{} (uri={})", line, uri);
                        }

                    })
                    .exceptionally(e -> {
                        lastVisionStatus = 0;
                        lastVisionBody = "";
                        lastVisionError = String.valueOf(e);
                        lastVisionAtMs = System.currentTimeMillis();

                        String line = "[Vision] exception=" + sanitizeDebugSnippet(String.valueOf(e));
                        CraftMate.vLog(line);
                        CraftMate.LOGGER.warn(line);
                        return null;
                    });
        } catch (Throwable e) {
            CraftMate.LOGGER.warn("[Vision] postVision error: {}", String.valueOf(e));
        }
    }

}
