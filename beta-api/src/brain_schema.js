// brain_schema.js — Phase 1 schema + hard-guard for CraftMate Brain DO
// Goal: DO never stores prose or unknown keys. Only structured, whitelisted state.
// Exports:
//   - DEFAULT_BRAIN()
//   - validateAndNormalizePatch(patch) -> normalized patch or throws {status, error, details?}

const ALLOWED_TOP_KEYS = new Set([
  "meta",
  "dialogue",
  "scene_state",
  "flags",
  "speech_lock_until",
  "counters",
]);

// Keys that must never be stored anywhere in the DO (prose / narration / spoken output).
// We scan recursively and reject if any of these keys appear.
const FORBIDDEN_KEYS = new Set([
  "spoken_line",
  "narrative",
  "summary_text",
  "narration",
  "prose",
  "caption",
  "reply_text",
  "suggested_line",
]);

const ALLOWED_META_KEYS = new Set([
  "last_seen",
  "last_route",
]);

const ALLOWED_FLAGS_KEYS = new Set([
  "speaker",
]);

// Phase 1 structured scene_state (NO interpretation).
const ALLOWED_SCENE_KEYS = new Set([
  "player",
  "environment",
  "entities",
  "updated_at",
  "spatial_notes",
  "danger",
  "confidence",
  "world_confidence",
  "last_vision_ts",
]);

const ALLOWED_SCENE_PLAYER_KEYS = new Set([
  "health",
  "hunger",
  "dimension",
  "position",
]);

const ALLOWED_SCENE_ENV_KEYS = new Set([
  "biome",
  "weather",
]);

const ALLOWED_DANGER_KEYS = new Set([
  "level",
  "why",
]);

const ALLOWED_COUNTERS_KEYS = new Set([
  "vision_calls_60s",
]);

const ALLOWED_VISION_CALLS_60S_KEYS = new Set([
  "chat",
  "tactical",
  "auto",
]);

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function throw400(error, details) {
  const e = { status: 400, error };
  if (details !== undefined) e.details = details;
  throw e;
}

function findForbiddenKeys(obj, path = "") {
  const hits = [];
  if (!isPlainObject(obj) && !Array.isArray(obj)) return hits;

  if (Array.isArray(obj)) {
    for (let i = 0; i < obj.length; i++) {
      hits.push(...findForbiddenKeys(obj[i], `${path}[${i}]`));
    }
    return hits;
  }

  for (const [k, v] of Object.entries(obj)) {
    const p = path ? `${path}.${k}` : k;
    if (FORBIDDEN_KEYS.has(k)) hits.push(p);
    hits.push(...findForbiddenKeys(v, p));
  }
  return hits;
}

function whitelistObjectKeys(obj, allowedSet, where) {
  const unknown = [];
  for (const k of Object.keys(obj)) {
    if (!allowedSet.has(k)) unknown.push(k);
  }
  if (unknown.length) {
    throw400(`Unknown keys in ${where}`, { unknown_keys: unknown });
  }
}

export function DEFAULT_BRAIN() {
  return {
    meta: { last_seen: null, last_route: null },
    dialogue: [],
    scene_state: { player: {}, environment: {}, entities: null, spatial_notes: null, danger: null, confidence: null, world_confidence: null, last_vision_ts: null, updated_at: 0 },
    flags: { speaker: "NONE" },
    speech_lock_until: null,
    counters: {
      vision_calls_60s: { chat: [], tactical: [], auto: [] },
    },
  };
}

function normalizeDialogue(dialogue) {
  if (dialogue === null) return null;
  if (!Array.isArray(dialogue)) throw400("dialogue must be an array");

  const out = [];
  for (let i = 0; i < dialogue.length; i++) {
    const ent = dialogue[i];
    if (!isPlainObject(ent)) throw400("dialogue entries must be objects", { index: i });

    whitelistObjectKeys(ent, new Set(["speaker", "text", "ts"]), `dialogue[${i}]`);

    const speaker = ent.speaker;
    if (speaker !== "player" && speaker !== "system") {
      throw400('dialogue.speaker must be "player" or "system"', { index: i });
    }
    if (typeof ent.text !== "string") throw400("dialogue.text must be a string", { index: i });
    if (typeof ent.ts !== "number") throw400("dialogue.ts must be a number", { index: i });

    out.push({ speaker, text: ent.text, ts: ent.ts });
  }

  // RingBuffer max=10 (Phase 1)
  if (out.length > 10) return out.slice(-10);
  return out;
}

function normalizeSceneState(ss) {
  if (ss === null) return null;
  if (!isPlainObject(ss)) throw400("scene_state must be an object");

  whitelistObjectKeys(ss, ALLOWED_SCENE_KEYS, "scene_state");

  const out = {};
  if ("updated_at" in ss) {
    if (typeof ss.updated_at !== "number") throw400("scene_state.updated_at must be a number");
    out.updated_at = ss.updated_at;
  } else {
    out.updated_at = 0;
  }

  if ("entities" in ss) {
    if (ss.entities !== null && !Array.isArray(ss.entities)) {
      throw400("scene_state.entities must be an array or null");
    }
    if (Array.isArray(ss.entities)) {
      for (let i = 0; i < ss.entities.length; i++) {
        if (typeof ss.entities[i] !== "string") {
          throw400("scene_state.entities entries must be strings", { index: i });
        }
      }
    }
    out.entities = ss.entities;
  }

  if ("player" in ss) {
    const p = ss.player;
    if (p !== null && !isPlainObject(p)) throw400("scene_state.player must be an object or null");
    if (p === null) {
      out.player = null;
    } else {
      whitelistObjectKeys(p, ALLOWED_SCENE_PLAYER_KEYS, "scene_state.player");
      const po = {};
      if ("health" in p) {
        if (p.health !== null && typeof p.health !== "number") throw400("scene_state.player.health must be number or null");
        po.health = p.health;
      }
      if ("hunger" in p) {
        if (p.hunger !== null && typeof p.hunger !== "number") throw400("scene_state.player.hunger must be number or null");
        po.hunger = p.hunger;
      }
      if ("dimension" in p) {
        if (p.dimension !== null && typeof p.dimension !== "string") throw400("scene_state.player.dimension must be string or null");
        po.dimension = p.dimension;
      }
      if ("position" in p) {
        if (p.position !== null && typeof p.position !== "string") throw400("scene_state.player.position must be string or null");
        po.position = p.position;
      }
      out.player = po;
    }
  }

  if ("environment" in ss) {
    const e = ss.environment;
    if (e !== null && !isPlainObject(e)) throw400("scene_state.environment must be an object or null");
    if (e === null) {
      out.environment = null;
    } else {
      whitelistObjectKeys(e, ALLOWED_SCENE_ENV_KEYS, "scene_state.environment");
      const eo = {};
      if ("biome" in e) {
        if (e.biome !== null && typeof e.biome !== "string") throw400("scene_state.environment.biome must be string or null");
        eo.biome = e.biome;
      }
      if ("weather" in e) {
        if (e.weather !== null && typeof e.weather !== "string") throw400("scene_state.environment.weather must be string or null");
        eo.weather = e.weather;
      }
      out.environment = eo;
    }
  }

  if ("last_vision_ts" in ss) {
    if (ss.last_vision_ts !== null && typeof ss.last_vision_ts !== "number") {
      throw400("scene_state.last_vision_ts must be a number or null");
    }
    out.last_vision_ts = ss.last_vision_ts;
  }

  if ("confidence" in ss) {
    if (ss.confidence !== null && typeof ss.confidence !== "number") {
      throw400("scene_state.confidence must be a number or null");
    }
    out.confidence = ss.confidence;
  }

  if ("world_confidence" in ss) {
    if (ss.world_confidence !== null && typeof ss.world_confidence !== "number") {
      throw400("scene_state.world_confidence must be a number or null");
    }
    out.world_confidence = ss.world_confidence;
  }

  if ("spatial_notes" in ss) {
    if (ss.spatial_notes !== null && !Array.isArray(ss.spatial_notes)) {
      throw400("scene_state.spatial_notes must be an array or null");
    }
    if (Array.isArray(ss.spatial_notes)) {
      for (let i = 0; i < ss.spatial_notes.length; i++) {
        if (typeof ss.spatial_notes[i] !== "string") {
          throw400("scene_state.spatial_notes entries must be strings", { index: i });
        }
      }
    }
    out.spatial_notes = ss.spatial_notes;
  }

  if ("danger" in ss) {
    const d = ss.danger;
    if (d !== null && !isPlainObject(d)) throw400("scene_state.danger must be an object or null");
    if (d === null) {
      out.danger = null;
    } else {
      whitelistObjectKeys(d, ALLOWED_DANGER_KEYS, "scene_state.danger");
      const dobj = {};
      if ("level" in d) {
        if (d.level !== null && typeof d.level !== "string") throw400("scene_state.danger.level must be string or null");
        dobj.level = d.level;
      }
      if ("why" in d) {
        if (d.why !== null && !Array.isArray(d.why)) throw400("scene_state.danger.why must be array or null");
        if (Array.isArray(d.why)) {
          for (let i = 0; i < d.why.length; i++) {
            if (typeof d.why[i] !== "string") throw400("scene_state.danger.why entries must be strings", { index: i });
          }
        }
        dobj.why = d.why;
      }
      out.danger = dobj;
    }
  }

  return out;
}

function normalizeFlags(f) {
  if (f === null) return null;
  if (!isPlainObject(f)) throw400("flags must be an object");
  whitelistObjectKeys(f, ALLOWED_FLAGS_KEYS, "flags");
  if ("speaker" in f) {
    const s = f.speaker;
    // Allow legacy "LLM" from Phase 0 infra, but normalize to "GPT"
    if (s !== "NONE" && s !== "GPT" && s !== "VISION" && s !== "LLM") {
      throw400("flags.speaker must be one of NONE|GPT|VISION", { got: s });
    }
    return { speaker: s === "LLM" ? "GPT" : s };
  }
  return {};
}

function normalizeCounters(c) {
  if (c === null) return null;
  if (!isPlainObject(c)) throw400("counters must be an object");
  whitelistObjectKeys(c, ALLOWED_COUNTERS_KEYS, "counters");

  const out = {};
  if ("vision_calls_60s" in c) {
    const vc = c.vision_calls_60s;
    if (!isPlainObject(vc)) throw400("counters.vision_calls_60s must be an object");
    whitelistObjectKeys(vc, ALLOWED_VISION_CALLS_60S_KEYS, "counters.vision_calls_60s");
    const vcOut = {};
    for (const k of Object.keys(vc)) {
      const arr = vc[k];
      if (!Array.isArray(arr)) throw400("counters.vision_calls_60s values must be arrays", { key: k });
      for (let i = 0; i < arr.length; i++) {
        if (typeof arr[i] !== "number") throw400("counters.vision_calls_60s entries must be numbers", { key: k, index: i });
      }
      vcOut[k] = arr;
    }
    out.vision_calls_60s = vcOut;
  }
  return out;
}

function normalizeMeta(m) {
  if (m === null) return null;
  if (!isPlainObject(m)) throw400("meta must be an object");
  whitelistObjectKeys(m, ALLOWED_META_KEYS, "meta");
  const out = {};
  if ("last_seen" in m) {
    if (m.last_seen !== null && typeof m.last_seen !== "number") throw400("meta.last_seen must be a number or null");
    out.last_seen = m.last_seen;
  }
  if ("last_route" in m) {
    const r = m.last_route;
    if (r !== null && r !== "scene" && r !== "chat" && r !== "vision" && r !== "tts") {
      throw400("meta.last_route must be one of scene|chat|vision|tts|null");
    }
    out.last_route = r;
  }
  return out;
}

export function validateAndNormalizePatch(patch) {
  if (!isPlainObject(patch)) throw400("Patch must be an object");
  whitelistObjectKeys(patch, ALLOWED_TOP_KEYS, "root");

  const forbidden = findForbiddenKeys(patch);
  if (forbidden.length) {
    throw400("Patch contains forbidden prose keys", { paths: forbidden });
  }

  const out = {};

  if ("dialogue" in patch) out.dialogue = normalizeDialogue(patch.dialogue);
  if ("scene_state" in patch) out.scene_state = normalizeSceneState(patch.scene_state);
  if ("flags" in patch) out.flags = normalizeFlags(patch.flags);

  if ("speech_lock_until" in patch) {
    const v = patch.speech_lock_until;
    if (v !== null && typeof v !== "number") throw400("speech_lock_until must be a number or null");
    out.speech_lock_until = v;
  }

  if ("counters" in patch) out.counters = normalizeCounters(patch.counters);
  if ("meta" in patch) out.meta = normalizeMeta(patch.meta);

  return out;
}
