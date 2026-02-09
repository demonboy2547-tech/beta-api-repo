// brain.js — Durable Object implementation for CraftMate
// Phase 0 / Step 1: Brain skeleton only (no AI, no business logic)

// Notes:
// - Stored keys in DO storage:
//   - "brain"  -> the persisted brain object
//   - "__meta" -> internal metadata (last_seen)
// - /patch deep-merges with schema validation (Phase 0 hard guard).

import { DEFAULT_BRAIN, validateAndNormalizePatch } from "./brain_schema.js";

const STORAGE_KEY_BRAIN = "brain";
const STORAGE_KEY_META = "__meta";

function nowMs() {
  return Date.now();
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-CM-Id, X-CM-Ts, X-CM-Sig",
  };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...corsHeaders(), "Content-Type": "application/json" },
  });
}

async function readJson(req) {
  // Safe JSON parsing with clear 400 on invalid JSON
  try {
    return { ok: true, value: await req.json() };
  } catch {
    try {
      const text = await req.text();
      if (!text) return { ok: true, value: null };
      return { ok: true, value: JSON.parse(text) };
    } catch {
      return { ok: false, error: "Invalid JSON" };
    }
  }
}

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function deepMerge(target, patch) {
  // Mutates target, returns target
  for (const [k, pv] of Object.entries(patch || {})) {
    const tv = target[k];
    if (isPlainObject(tv) && isPlainObject(pv)) {
      deepMerge(tv, pv);
    } else {
      target[k] = pv;
    }
  }
  return target;
}

function findUnknownKeys(existing, patch, path = "") {
  // Reject keys that do not exist in the current stored brain.
  // For nested objects, only allow keys that exist at the same path.
  const unknown = [];
  if (!isPlainObject(patch)) return unknown;

  for (const [k, pv] of Object.entries(patch)) {
    const p = path ? `${path}.${k}` : k;
    if (!existing || !(k in existing)) {
      unknown.push(p);
      continue;
    }
    const ev = existing[k];
    if (isPlainObject(ev) && isPlainObject(pv)) {
      unknown.push(...findUnknownKeys(ev, pv, p));
    }
  }
  return unknown;
}



function normalizeSpeakerValue(v) {
  // Legacy compatibility: older builds stored LLM; Phase 1 uses GPT.
  if (v === "LLM") return "GPT";
  if (v === "GPT" || v === "VISION" || v === "NONE") return v;
  return "NONE";
}

function safeNormalizeExistingBrain(existingStored) {
  // We must be able to migrate older/invalid stored brains without bricking /patch.
  // Strategy:
  // 1) If existingStored is a plain object, attempt to normalize/validate it.
  // 2) If validation fails (unknown keys, bad enums, etc.), fall back to DEFAULT_BRAIN().
  // 3) Always return a fully-populated brain (DEFAULT_BRAIN() + normalizedPatch).
  if (!isPlainObject(existingStored)) return structuredClone(DEFAULT_BRAIN());
  try {
    // validateAndNormalizePatch can act as a sanitizer for a full brain object
    // because it enforces allowed keys and normalizes known fields.
    const normalized = validateAndNormalizePatch(existingStored);

    // Ensure flags.speaker is normalized even if legacy values exist.
    if (normalized.flags && "speaker" in normalized.flags) {
      normalized.flags.speaker = normalizeSpeakerValue(normalized.flags.speaker);
    }

    return deepMerge(structuredClone(DEFAULT_BRAIN()), normalized);
  } catch {
    return structuredClone(DEFAULT_BRAIN());
  }
}
function pruneTimestamps(tsArr, cutoffMs) {
  if (!Array.isArray(tsArr)) return [];
  // Keep timestamps >= cutoffMs
  const out = [];
  for (const ts of tsArr) {
    if (typeof ts === "number" && ts >= cutoffMs) out.push(ts);
  }
  return out;
}

export class CraftMateBrain {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);

    // CORS preflight
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });

    // Minimal smoke test (kept for backwards compatibility with existing worker debug route)
    if (request.method === "GET" && url.pathname === "/ping") {
      return new Response("pong", { status: 200, headers: corsHeaders() });
    }

    const now = nowMs();
    // Update last_seen for *any* successful route handling.
    // (We update it early; even if a handler returns 4xx, last_seen reflects contact.)
    const meta = (await this.state.storage.get(STORAGE_KEY_META)) || {};
    meta.last_seen = now;
    await this.state.storage.put(STORAGE_KEY_META, meta);

    if (request.method === "GET" && url.pathname === "/get") {
      const stored = (await this.state.storage.get(STORAGE_KEY_BRAIN)) || null;
      const brain = safeNormalizeExistingBrain(stored);
      return json(brain, 200);
    }

    
    if (request.method === "POST" && url.pathname === "/put") {
      const parsed = await readJson(request);
      if (!parsed.ok) return json({ error: parsed.error }, 400);

      const body = parsed.value;
      if (body === null) return json({ error: "Body must be a JSON object" }, 400);
      if (!isPlainObject(body)) return json({ error: "Brain must be a JSON object" }, 400);

      try {
        // Treat /put as "replace", but we still normalize against the Phase 0 schema.
        // Missing keys are filled from DEFAULT_BRAIN() so subsequent /patch is predictable.
        const normalized = validateAndNormalizePatch(body);
        const full = deepMerge(structuredClone(DEFAULT_BRAIN()), normalized);
        await this.state.storage.put(STORAGE_KEY_BRAIN, full);
        await this.state.storage.put(STORAGE_KEY_META, { last_seen: nowMs() });
        return json({ ok: true }, 200);
      } catch (e) {
        return json({ error: e?.error || "Invalid brain", details: e?.details }, e?.status || 400);
      }
    }

    
    if (request.method === "POST" && url.pathname === "/patch") {
      const parsed = await readJson(request);
      if (!parsed.ok) return json({ error: parsed.error }, 400);

      const patchRaw = parsed.value;
      if (patchRaw === null) return json({ error: "Body must be a JSON object" }, 400);
      if (!isPlainObject(patchRaw)) return json({ error: "Patch must be a JSON object" }, 400);

      const existingStored = (await this.state.storage.get(STORAGE_KEY_BRAIN)) || null;
      const existing = safeNormalizeExistingBrain(existingStored);

      let patch;
      try {
        patch = validateAndNormalizePatch(patchRaw);
      } catch (e) {
        return json({ error: e?.error || "Invalid patch", details: e?.details }, e?.status || 400);
      }

      const merged = deepMerge(structuredClone(existing), patch);

      // Re-validate the merged result to ensure DO never stores invalid content.
      // (e.g., if a patch deletes a nested object incorrectly)
      try {
        const normalizedMerged = validateAndNormalizePatch(merged);
        const full = deepMerge(structuredClone(DEFAULT_BRAIN()), normalizedMerged);
        await this.state.storage.put(STORAGE_KEY_BRAIN, full);
        return json({ ok: true }, 200);
      } catch (e) {
        return json({ error: e?.error || "Invalid merged brain", details: e?.details }, e?.status || 400);
      }
    }

    
    if (request.method === "POST" && url.pathname === "/lock") {
      const parsed = await readJson(request);
      if (!parsed.ok) return json({ error: parsed.error }, 400);

      const body = parsed.value;
      if (body === null) return json({ error: "Body must be a JSON object" }, 400);
      if (!isPlainObject(body)) return json({ error: "Body must be a JSON object" }, 400);

      // Server-side time only: accept cooldown_ms (duration) and compute speech_lock_until from nowMs()
      const speaker = (body.speaker ?? body.flags?.speaker) ?? null;
      const cooldown_ms = body.cooldown_ms ?? body.lock_ms ?? null;

      if (speaker !== "LLM" && speaker !== "GPT" && speaker !== "VISION" && speaker !== "NONE") {
        return json({ error: "speaker must be one of GPT|LLM|VISION|NONE" }, 400);
      }
      if (cooldown_ms !== null && typeof cooldown_ms !== "number") {
        return json({ error: "cooldown_ms must be a number or null" }, 400);
      }
      if (cooldown_ms !== null && cooldown_ms < 0) {
        return json({ error: "cooldown_ms must be >= 0" }, 400);
      }

      const until = cooldown_ms === null ? null : nowMs() + Math.floor(cooldown_ms);

      return await this.state.blockConcurrencyWhile(async () => {
        const existingStored = (await this.state.storage.get(STORAGE_KEY_BRAIN)) || null;
      const existing = safeNormalizeExistingBrain(existingStored);

        const patchRaw = { flags: { speaker: normalizeSpeakerValue(speaker) }, speech_lock_until: until, meta: { last_seen: nowMs() } };

        let patch;
        try {
          patch = validateAndNormalizePatch(patchRaw);
        } catch (e) {
          return json({ error: e?.error || "Invalid lock patch", details: e?.details }, e?.status || 400);
        }

        const merged = deepMerge(structuredClone(existing), patch);
        try {
          const normalizedMerged = validateAndNormalizePatch(merged);
          const full = deepMerge(structuredClone(DEFAULT_BRAIN()), normalizedMerged);
          await this.state.storage.put(STORAGE_KEY_BRAIN, full);
          await this.state.storage.put(STORAGE_KEY_META, { last_seen: nowMs() });
          return json({ ok: true, speaker, speech_lock_until: until }, 200);
        } catch (e) {
          return json({ error: e?.error || "Invalid merged brain", details: e?.details }, e?.status || 400);
        }
      });
    }



    if (request.method === "POST" && url.pathname === "/counter/bump") {
      const parsed = await readJson(request);
      if (!parsed.ok) return json({ error: parsed.error }, 400);

      const body = parsed.value;
      if (body === null) return json({ error: "Body must be a JSON object" }, 400);
      if (!isPlainObject(body)) return json({ error: "Body must be a JSON object" }, 400);

      const reason = body.reason ?? null;
      if (reason !== "chat" && reason !== "tactical" && reason !== "auto") {
        return json({ error: "reason must be one of chat|tactical|auto" }, 400);
      }

      return await this.state.blockConcurrencyWhile(async () => {
        const existingStored = (await this.state.storage.get(STORAGE_KEY_BRAIN)) || null;
      const existing = safeNormalizeExistingBrain(existingStored);

        const now2 = nowMs();
        const cutoff = now2 - 60_000;

        const vc = existing?.counters?.vision_calls_60s ?? {};
        const pruned = {
          chat: pruneTimestamps(vc.chat, cutoff),
          tactical: pruneTimestamps(vc.tactical, cutoff),
          auto: pruneTimestamps(vc.auto, cutoff),
        };

        pruned[reason].push(now2);

        const patchRaw = { counters: { vision_calls_60s: pruned }, meta: { last_seen: now2 } };

        let patch;
        try {
          patch = validateAndNormalizePatch(patchRaw);
        } catch (e) {
          return json({ error: e?.error || "Invalid counter patch", details: e?.details }, e?.status || 400);
        }

        const merged = deepMerge(structuredClone(existing), patch);
        try {
          const normalizedMerged = validateAndNormalizePatch(merged);
          const full = deepMerge(structuredClone(DEFAULT_BRAIN()), normalizedMerged);
          await this.state.storage.put(STORAGE_KEY_BRAIN, full);
          await this.state.storage.put(STORAGE_KEY_META, { last_seen: now2 });
          return json({ ok: true, count_60s: pruned[reason].length }, 200);
        } catch (e) {
          return json({ error: e?.error || "Invalid merged brain", details: e?.details }, e?.status || 400);
        }
      });
    }

if (request.method === "GET" && url.pathname === "/debug") {
      const stored = (await this.state.storage.get(STORAGE_KEY_BRAIN)) || null;
      const brain = safeNormalizeExistingBrain(stored);
      const meta2 = (await this.state.storage.get(STORAGE_KEY_META)) || {};

      const speaker = brain?.flags?.speaker ?? null;
      const speech_lock_until = brain?.speech_lock_until ?? null;
      const counters = brain?.counters ?? null;
      const last_seen = meta2?.last_seen ?? null;

      return json({ speaker, speech_lock_until, counters, last_seen }, 200);
    }

    return json({ error: "Not found" }, 404);
  }
}

// Backwards-compatible export name used by older wrangler bindings / worker exports.
export { CraftMateBrain as CM_DO };
