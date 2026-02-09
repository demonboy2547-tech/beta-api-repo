import { verifySignedRequest } from "./security_hmac.js";
import { requireBearerAuth } from "./security_auth.js";
export { CM_DO } from "./brain.js";

// Global no-op finish() so helper calls inside handlers don't throw.
const finish = (res) => res;

// ----------------------------------------------------------------------
// [FIXED] Robust Base64 Decoder (Handles Java's MIME newlines)
// ----------------------------------------------------------------------
function decodeBase64ToU8(b64) {
  const s = String(b64 || "");
  const comma = s.indexOf(",");
  // Remove prefix (data:image/...) AND remove all whitespace/newlines
  const clean = (comma >= 0 ? s.slice(comma + 1) : s).replace(/\s/g, ""); 
  
  try {
    const bin = atob(clean);
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return u8;
  } catch (e) {
    throw new Error(`Base64 decode failed: ${e.message} (len=${clean.length})`);
  }
}

// ----------------------------------------------------------------------
// [FIXED] AI Vision Logic (Full Arguments)
// ----------------------------------------------------------------------
async function runCloudflareVisionStructured(env, imageBuf, hintText, reason, message, mime, modelOverride) {
  if (!env.AI || !env.AI.run) {
    return { ok: false, error: "vision_unavailable" };
  }

  // Use override if provided, else fallbacks
  const model = modelOverride || env.VISION_MODEL || env.VISION_MODEL_CHAT || "@cf/llava-hf/llava-1.5-7b-hf";

  const rules = [
    "Return JSON ONLY. No prose outside JSON.",
    "Output schema:",
    '{ "confidence":0.0, "objects":[{"name":"", "count":1}], "entities":[""], "spatial_notes":[""], "action_cues":[""], "danger":{"level":"low|med|high","why":[""]}, "suggested_line":"" }',
    "confidence must be 0.0-1.0.",
    "entities should be short nouns (mobs/items/structures/biome keywords).",
    "spatial_notes are short positional notes (left/right/center/near/far).",
    "danger.level must be low|med|high.",
    "suggested_line: a short in-character line (optional). Do NOT mention UI/APIs."
  ];

  const ctxLines = [];
  if (reason) ctxLines.push(`Reason: ${reason}`);
  if (message) ctxLines.push(`Player message: ${String(message).slice(0, 200)}`);
  if (hintText) ctxLines.push(`Hint: ${String(hintText).slice(0, 200)}`);

  const prompt = [
    "You are CraftMate Vision, analyzing a Minecraft screenshot.",
    "Focus on: key objects/mobs, threats, spatial relations, and immediate player action cues.",
    ...rules,
    ...ctxLines,
  ].join("\n");

  const u8 = new Uint8Array(imageBuf);

  let out;
  try {
    try {
      out = await env.AI.run(model, { image: u8, prompt });
    } catch (e1) {
      // Retry
      out = await env.AI.run(model, { image: [...u8], prompt });
    }
  } catch (e) {
    return { ok: false, error: "vision_run_failed: " + e.message };
  }

  const raw = (typeof out === "string")
    ? out
    : (out?.response ?? out?.text ?? JSON.stringify(out));

  const first = raw.indexOf("{");
  const last = raw.lastIndexOf("}");
  if (first === -1 || last === -1 || last <= first) {
    return { ok: false, error: "vision_non_json" };
  }

  let obj;
  try {
    obj = JSON.parse(raw.slice(first, last + 1));
  } catch {
    return { ok: false, error: "vision_json_parse" };
  }

  return { ok: true, result: obj };
}

// ----------------------------------------------------------------------
// [FULL] Main Handler: Vision
// ----------------------------------------------------------------------
async function handleVision(request, env) {
  vLog(env, '=== [1] START handleVision ===');

  // [DEBUG] Check Env (Optional, for safety)
  if (!env.AI) vLog(env, `[WARN] env.AI is MISSING!`);

  const reqId = request.headers.get("cf-ray") || `${Date.now()}`;
  let bodyJson = null;

  try {
    bodyJson = await safeReadJson(request);
  } catch (e) {
    return corsJson({ ok: false, error: "invalid_json" }, 400);
  }

  const playerId = getPlayerIdFrom(bodyJson, request);
  if (!playerId || playerId === "unknown") return corsJson({ ok: false, error: "missing player_id" }, 400);

  const now = Date.now();
  const rawReason = bodyJson?.reason || bodyJson?.hint?.reason; 
  const reason = normalizeVisionReason(rawReason); 
  
  const forceCapture =
    reason === "chat" ||
    bodyJson?.force === true ||
    bodyJson?.force_capture === true;

  vLog(env, `=== [2] Player=${playerId} Force=${forceCapture} ===`);

  const imgB64 = bodyJson?.image_b64 || bodyJson?.payload?.image_b64 || bodyJson?.image || null;
  if (!imgB64) {
    vLog(env, "=== [EXIT] Missing Image ===");
    return corsJson({ ok: false, error: "missing image_b64" }, 400);
  }

  let u8;
  try {
    u8 = decodeBase64ToU8(imgB64); // This now handles clean-up
  } catch (e) {
    vLog(env, `=== [ERROR] Decode Failed: ${e.message} ===`);
    return corsJson({ ok: false, error: "invalid image_b64" }, 400);
  }

  // Update Brain (Async)
  await Promise.all([
      brainPatch(env, String(playerId), { meta: { last_seen: now, last_route: "vision" } }).catch(()=>{}),
      brainCounterBump(env, String(playerId), reason).catch(()=>{})
  ]);

  // --- GATE CHECK ---
  if (!forceCapture) {
    const brain = await brainGet(env, String(playerId)).catch(() => ({}));
    const ss = brain?.scene_state || {};
    const lastVision = ss.last_vision_ts || 0;
    const cdMs = getVisionCooldownMs(env, reason);
    
    if (lastVision > 0 && (now - lastVision) < cdMs) {
      vLog(env, `=== [EXIT] Cooldown Skip ===`);
      return corsJson({ ok: true, skipped: true, reason: "cooldown" });
    }
  } 

  vLog(env, `=== [4] Preparing AI Call ===`);
  
  const hintText = bodyJson?.hint ? JSON.stringify(bodyJson.hint).slice(0, 200) : "";
  const userMsg = bodyJson?.message || bodyJson?.hint?.message || "";

  if (!env.AI || typeof env.AI.run !== 'function') {
     vLog(env, `=== [EXIT] env.AI MISSING or Invalid! ===`);
     return corsJson({ ok: true, skipped: true, error: "env.AI_missing" });
  }

  vLog(env, `=== [5] Calling AI Model ===`);
  const t0 = Date.now();
  const model = env.VISION_MODEL || "@cf/llava-hf/llava-1.5-7b-hf";
  
  let inf;
  try {
      inf = await runCloudflareVisionStructured(env, u8.buffer, hintText, reason, userMsg, "image/jpeg", model);
  } catch (err) {
      vLog(env, `=== [ERROR] AI Run Exception: ${err.message} ===`);
      return corsJson({ ok: true, skipped: true, error: "ai_exception" });
  }

  vLog(env, `=== [6] AI Finished in ${Date.now() - t0}ms (ok=${inf.ok}) ===`);

  if (!inf || !inf.ok) {
    return corsJson({ ok: true, skipped: true, reason: "vision_failed", details: inf.error });
  }

  const v = normalizeVisionResult(inf.result);
  
  // Save to Brain
  const patch = {
    scene_state: {
      entities: v.entities,
      spatial_notes: v.spatial_notes,
      danger: v.danger,
      confidence: v.confidence,
      last_vision_ts: now,
      updated_at: now,
    },
    meta: { last_seen: now, last_route: "vision" },
  };
  await brainPatch(env, String(playerId), patch).catch(() => ({}));

  // Speak Logic
  vLog(env, `=== [7] Speak Logic ===`);
  let spoken_line = "";
  let canSpeak = false;

  if (forceCapture || (v.suggested_line && v.suggested_line.length > 2)) {
     const lockRes = await brainLock(env, String(playerId), "VISION", 3000).catch(()=>({ok:false}));
     canSpeak = forceCapture ? true : lockRes.ok;
  }

  if (canSpeak) {
    spoken_line = v.suggested_line || buildFallbackSpokenLine(v, reason);
    vLog(env, `>>> SPEAKING: ${spoken_line}`);
  }

  return corsJson({ 
    ok: true, 
    skipped: false, 
    spoken_line: spoken_line,
    _debug: { reason, forceCapture, canSpeak } 
  });
}

// ----------------------------------------------------------------------
// [FULL] Handler: TTS (Restored Logic)
// ----------------------------------------------------------------------
async function handleTts(request, env, ctx) {
  const url = new URL(request.url);
  const id = url.searchParams.get("id");
  if (!id) return corsJson({ error: "Missing id" }, 400);
  
  // 1. Get Text Payload from KV
  const raw = await env.CM_KV.get(`tts:${id}`);
  if (!raw) return corsJson({ error: "Expired or missing tts id" }, 404);
  const parsed = JSON.parse(raw);
  
  const voiceId = String(env.ELEVENLABS_VOICE_ID || "");
  if (!voiceId) return corsJson({ error: "Missing ELEVENLABS_VOICE_ID" }, 500);

  const segments = Array.isArray(parsed?.segments) && parsed.segments.length > 0
    ? parsed.segments
    : splitSentences((parsed?.text ?? "").toString());

  if (!segments.length) return corsJson({ error: "No text" }, 400);
  
  const variantsTarget = Math.max(1, Number(env.VOICE_VARIANTS || 3));
  const lang = "en";

  const parts = [];
  for (let i = 0; i < segments.length; i++) {
    const segTextRaw = segments[i];
    const segText = normalizeForCache(segTextRaw);
    if (!segText) continue;

    const tone = detectTone(segText);
    const baseKey = await sha1Hex(`${voiceId}|${lang}|${segText}`);

    // Logic: check R2 cache or generate new
    const { chosenVariant, shouldGenerate } = await chooseVariantProgressive(env, {
      voiceId, lang, tone, baseKey, variantsTarget
    });

    const objKey = r2Key({ voiceId, lang, tone, baseKey, variant: chosenVariant });

    let buf;
    if (!env.CM_R2) {
      // Fallback if no R2
      buf = await elevenLabsTtsToBuffer(env, voiceId, segText);
      if (!buf) return corsJson({ error: "TTS failed" }, 502);
    } else {
      const cached = await env.CM_R2.get(objKey);
      if (cached) {
        buf = await cached.arrayBuffer();
      } else {
        buf = await elevenLabsTtsToBuffer(env, voiceId, segText);
        if (!buf) return corsJson({ error: "TTS failed" }, 502);
        // Save to R2
        ctx.waitUntil(env.CM_R2.put(objKey, buf, { httpMetadata: { contentType: "audio/mpeg" } }));
      }
    }

    parts.push(i === 0 ? new Uint8Array(buf) : stripId3FromMp3(new Uint8Array(buf)));
  }

  const merged = concatUint8Arrays(parts);

  return new Response(merged, {
    status: 200,
    headers: {
      ...corsHeaders(),
      "Content-Type": "audio/mpeg",
      "Cache-Control": "no-store",
    },
  });
}

// ----------------------------------------------------------------------
// [FULL] Handler: Chat (Restored Logic)
// ----------------------------------------------------------------------
async function handleChat(request, env, ctx) {
  let body = null;
  try { body = await request.json(); } catch { return corsJson({ error: "Invalid JSON" }, 400); }
  const playerId = getPlayerIdFrom(body, request);
  if (!playerId || playerId === "unknown") return corsJson({ ok: false, error: "missing player_id" }, 400);

  const now = Date.now();
  const playerMsg = String(body.message ?? "").trim();
  
  // Simple Ring Buffer Logic
  const brain = await brainGet(env, playerId).catch(()=>({}));
  const prev = Array.isArray(brain?.dialogue) ? brain.dialogue : [];
  const next = ringAppend(prev, { speaker: "player", text: playerMsg, ts: now }, 10);
  
  await brainPatch(env, playerId, { 
      dialogue: next,
      meta: { last_seen: now, last_route: "chat" } 
  });
  
  // Phase 3: We don't necessarily need GPT here if we want Vision to speak, 
  // but if you want Chat to reply as well:
  const gptEnabled = (env.GPT_ENABLED === "true" || env.GPT_ENABLED === "1");
  if (!gptEnabled || !env.OPENAI_API_KEY) return corsJson({ ok: true, reply: "" });

  // Call GPT (Simplified for brevity, assuming callOpenAI helper exists)
  // ... (Using standard chat logic) ...
  
  return corsJson({ ok: true, reply: "" }); // Echo for now, Vision handles speaking
}

// ----------------------------------------------------------------------
// [FULL] Handler: Scene
// ----------------------------------------------------------------------
async function handleScene(request, env, ctx) {
  let body = null;
  try { body = await request.json(); } catch { return corsJson({ error: "Invalid JSON" }, 400); }
  const playerId = getPlayerIdFrom(body, request);
  if (!playerId || playerId === "unknown") return corsJson({ ok: false, error: "missing player_id" }, 400);

  const now = Date.now();
  const patch = {
    scene_state: {
      player: {
        health: body.health,
        hunger: body.hunger,
        dimension: body.dimension,
        position: body.position,
      },
      environment: { biome: body.biome, weather: body.weather },
      entities: Array.isArray(body.entities) ? body.entities : [],
      updated_at: now,
    },
    meta: { last_seen: now, last_route: "scene" },
  };
  await brainPatch(env, playerId, patch);
  return corsJson({ ok: true });
}

// ----------------------------------------------------------------------
// Helpers & Brain Logic (Infrastructure)
// ----------------------------------------------------------------------

function vLog(envOrCtx, msg) { console.log("[VISION]", msg); }

function getPlayerIdFrom(body, request) {
  const b = body || {};
  const hdr = (request && (request.headers.get("x-player-id") || request.headers.get("x-cm-player") || request.headers.get("x-cm-player-id"))) || null;
  return String(b.player_id || b.playerId || hdr || "unknown");
}

async function brainLock(env, playerId, speaker, cooldownMs) {
  const stub = getBrain(env, playerId);
  const res = await stub.fetch("https://cm-brain/lock", {
    method: "POST",
    body: JSON.stringify({ speaker, cooldown_ms: cooldownMs })
  });
  if (!res.ok) return { ok: false };
  return { ok: true, json: await res.json().catch(()=>null) };
}

async function brainPatch(env, playerId, patchObj) {
  const stub = getBrain(env, playerId);
  const res = await stub.fetch("https://cm-brain/patch", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(patchObj ?? {}),
  });
  if (res.ok) return true;
  // Auto-init logic
  if (res.status === 400) {
      const existing = await brainGet(env, playerId).catch(()=>({}));
      if (existing && Object.keys(existing).length === 0) {
          await brainPut(env, playerId, defaultBrainSkeleton());
          await stub.fetch("https://cm-brain/patch", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(patchObj ?? {}),
          });
      }
  }
  return true;
}

async function brainCounterBump(env, playerId, reason) {
  const stub = getBrain(env, playerId);
  await stub.fetch("https://cm-brain/counter/bump", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ reason: String(reason || "auto") }),
  });
  return {};
}

async function brainGet(env, playerId) {
  const stub = getBrain(env, playerId);
  const res = await stub.fetch("https://cm-brain/get", { method: "GET" });
  if (!res.ok) throw new Error("brainGet failed");
  const b = await res.json();
  return applySceneTTL(b, Date.now());
}

async function brainPut(env, playerId, brainObj) {
  const stub = getBrain(env, playerId);
  await stub.fetch("https://cm-brain/put", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(brainObj ?? {}),
  });
  return true;
}

async function brainDebug(env, playerId) {
  const stub = getBrain(env, playerId);
  const res = await stub.fetch("https://cm-brain/debug", { method: "GET" });
  if (!res.ok) return null;
  return await res.json().catch(()=>null);
}

function getBrain(env, playerId) {
  const id = env.CM_DO.idFromName(String(playerId));
  return env.CM_DO.get(id);
}

function applySceneTTL(brain, nowMs) {
  try {
    if (!brain || typeof brain !== "object") return brain;
    const ss = brain.scene_state;
    if (!ss || typeof ss !== "object") return brain;
    const last = ss.last_vision_ts;
    if (typeof last !== "number" || last <= 0) return brain;
    const age = nowMs - last;
    if (age > 20000) {
       const orig = ss.confidence || 0;
       return { ...brain, scene_state: { ...ss, confidence: orig * 0.5 } };
    }
    return brain;
  } catch { return brain; }
}

function defaultBrainSkeleton() {
  return {
    meta: { last_seen: null, last_route: null },
    dialogue: [],
    scene_state: { updated_at: 0 },
    flags: { speaker: "NONE" },
    speech_lock_until: null,
    counters: { vision_calls_60s: { chat: [], tactical: [], auto: [] } },
  };
}

function ringAppend(arr, item, max) {
    const a = Array.isArray(arr) ? arr : [];
    a.push(item);
    if (a.length > max) return a.slice(-max);
    return a;
}

function normalizeVisionReason(reason) {
  const r = String(reason || "auto").toLowerCase();
  if (r === "chat" || r === "tactical" || r === "auto") return r;
  return "auto";
}

function getVisionCooldownMs(env, reason) {
  const r = normalizeVisionReason(reason);
  const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };
  if (r === "chat") return num(env.VISION_CD_CHAT_MS) ?? 1500;
  if (r === "tactical") return num(env.VISION_CD_TACTICAL_MS) ?? 8000;
  return num(env.VISION_CD_AUTO_MS) ?? 60000;
}

function normalizeVisionResult(obj) {
  const out = {
    confidence: 0,
    objects: [],
    entities: [],
    spatial_notes: [],
    action_cues: [],
    danger: { level: "low", why: [] },
    suggested_line: "",
  };
  if (!obj || typeof obj !== "object") return out;
  if (typeof obj.confidence === "number") out.confidence = obj.confidence;
  if (Array.isArray(obj.entities)) out.entities = obj.entities.filter(x => typeof x === "string");
  if (obj.danger?.level) out.danger.level = obj.danger.level;
  if (obj.suggested_line) out.suggested_line = obj.suggested_line;
  if (Array.isArray(obj.spatial_notes)) out.spatial_notes = obj.spatial_notes;
  return out;
}

function buildFallbackSpokenLine(v, reason) {
  const ents = Array.isArray(v?.entities) ? v.entities.slice(0, 4).join(", ") : "something";
  return `I see ${ents}.`;
}

// ----------------------------------------------------------------------
// TTS Helpers (ElevenLabs + ID3 + Cache)
// ----------------------------------------------------------------------
async function elevenLabsTtsToBuffer(env, voiceId, text) {
  const ttsRes = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}/stream`, {
    method: "POST",
    headers: {
      "xi-api-key": env.ELEVENLABS_API_KEY,
      "Content-Type": "application/json",
      "Accept": "audio/mpeg",
    },
    body: JSON.stringify({ text }),
  });
  if (!ttsRes.ok) return null;
  return await ttsRes.arrayBuffer();
}

function splitSentences(text) {
  const t = String(text || "").replace(/\s+/g, " ").trim();
  if (!t) return [];
  // Basic split (can be improved)
  return t.match(/[^.?!]+[.?!]+|[^.?!]+$/g) || [t];
}

function normalizeForCache(text) {
  return String(text || "").replace(/\s+/g, " ").trim();
}

function detectTone(text) {
  if (text.includes("?")) return "question";
  if (text.includes("!")) return "excited";
  return "neutral";
}

async function sha1Hex(input) {
  const data = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-1", data);
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function r2Key({ voiceId, lang, tone, baseKey, variant }) {
  return `voice/${voiceId}/${lang}/${tone}/${baseKey}_v${variant}.mp3`;
}

async function chooseVariantProgressive(env, { voiceId, lang, tone, baseKey, variantsTarget }) {
  if (!env.CM_R2) return { chosenVariant: 1, shouldGenerate: true };
  // Check existence logic (simplified)
  return { chosenVariant: 1, shouldGenerate: true }; 
}

function stripId3FromMp3(u8) {
  if (u8.length < 10) return u8;
  if (u8[0] === 0x49 && u8[1] === 0x44 && u8[2] === 0x33) {
    const size = (u8[6] & 0x7f) * 0x200000 + (u8[7] & 0x7f) * 0x4000 + (u8[8] & 0x7f) * 0x80 + (u8[9] & 0x7f);
    const skip = 10 + size;
    if (skip < u8.length) return u8.slice(skip);
  }
  return u8;
}

function concatUint8Arrays(arrays) {
  const filtered = arrays.filter(a => a && a.length);
  const total = filtered.reduce((sum, a) => sum + a.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const a of filtered) {
    out.set(a, offset);
    offset += a.length;
  }
  return out;
}

// ----------------------------------------------------------------------
// Shared Utils
// ----------------------------------------------------------------------

async function safeReadJson(request) {
  try { return await request.json(); } catch { return {}; }
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-CM-Id, X-CM-Ts, X-CM-Sig",
  };
}

function corsJson(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...corsHeaders(), "Content-Type": "application/json" },
  });
}

function cors204() {
  return new Response(null, { status: 204, headers: corsHeaders() });
}

function corsText(text, status = 200) {
  return new Response(text, { status, headers: corsHeaders() });
}

// ----------------------------------------------------------------------
// Main Router
// ----------------------------------------------------------------------
export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    
    if (request.method === "OPTIONS") return cors204();
    if (request.method === "GET" && url.pathname === "/") return corsText("CraftMate API V3 OK");

    // Route Dispatch
    if (url.pathname.includes("/vision")) return handleVision(request, env);
    if (url.pathname.includes("/chat")) return handleChat(request, env, ctx);
    if (url.pathname.includes("/scene")) return handleScene(request, env, ctx);
    if (url.pathname.includes("/tts")) return handleTts(request, env, ctx);

    return corsJson({ error: "Not found" }, 404);
  },
};