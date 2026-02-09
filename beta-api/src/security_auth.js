// security_auth.js
// Bearer token auth for BOTH public + beta.
// - Public: requires Bearer token (optional allowlist), usage meter
// - Beta: requires Bearer token + (recommended) auto-bind player_id + concurrency strikes
//
// KV layout:
// - public:token:<TOKEN>  -> policy JSON
// - beta:token:<TOKEN>    -> policy JSON
// - <prefix>:sess:<TOKEN> -> session heartbeat
// - <prefix>:strike:<TOKEN> -> concurrency strikes
// - <prefix>:usage:<YYYY-MM-DD>:<TOKEN> -> daily usage counter

export function parseBearer(request) {
  const h = request.headers.get("Authorization") || "";
  const m = h.match(/^Bearer\s+(.+)$/i);
  return m ? m[1].trim() : "";
}

export function normalizePlayerId(playerId) {
  return String(playerId || "").trim().toLowerCase();
}

export function ipHash(request) {
  const ip =
    request.headers.get("CF-Connecting-IP") ||
    request.headers.get("X-Forwarded-For")?.split(",")[0]?.trim() ||
    "unknown";
  return simpleHash(ip);
}

function simpleHash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0).toString(16);
}

// Main guard
export async function requireBearerAuth({ request, env, playerId }) {
  const token = parseBearer(request);
  if (!token) return { ok: false, status: 401, error: "Missing Bearer token" };

  const betaMode = String(env.BETA_MODE || "false").toLowerCase() === "true";
  const prefix = betaMode ? "beta" : "public";

  const tokKey = `${prefix}:token:${token}`;
  const raw = await env.CM_KV.get(tokKey);
  if (!raw) return { ok: false, status: 401, error: "Invalid token" };

  let policy;
  try { policy = JSON.parse(raw); } catch { policy = {}; }

  if (policy?.disabled) return { ok: false, status: 403, error: "Token disabled" };

  const pid = normalizePlayerId(playerId);

  // allowlist (ใช้ได้ทั้ง public/beta ถ้าอยากผูก user)
  let allowed = Array.isArray(policy?.allowed_player_ids)
    ? policy.allowed_player_ids.map(normalizePlayerId)
    : [];

  // -----------------------------
  // AUTO-BIND (first use) - Beta only
  // -----------------------------
  // Idea: token can be shipped with allowed_player_ids = []
  // First successful request binds token -> first seen player_id in KV.
  // After that, token works ONLY for that player_id.
  const autoBind =
    String(env.AUTO_BIND_PLAYER_ID || "false").toLowerCase() === "true";

  if (betaMode && autoBind) {
    // Optional: allow manual reset bind via policy flag
    // If you set {"reset_bind": true} in KV, next request will clear allowlist.
    if (policy?.reset_bind === true) {
      policy.allowed_player_ids = [];
      policy.reset_bind = false;
      policy.reset_bind_at = Date.now();
      await env.CM_KV.put(tokKey, JSON.stringify(policy), {
        expirationTtl: 60 * 60 * 24 * 180,
      });
      allowed = [];
    }

    // Bind only if allowlist empty AND we have a real pid
    if (allowed.length === 0 && pid && pid !== "unknown") {
      policy.allowed_player_ids = [pid];
      policy.bound_at = Date.now();
      policy.bound_reason = "first_use_auto_bind";

      await env.CM_KV.put(tokKey, JSON.stringify(policy), {
        expirationTtl: 60 * 60 * 24 * 180, // 180 days
      });

      allowed = [pid];
    }
  }

  // Enforce allowlist
  if (allowed.length > 0 && !allowed.includes(pid)) {
    return { ok: false, status: 403, error: "player_id not allowed for this token" };
  }

  // -----------------------------
  // CONCURRENCY STRIKES (recommended beta)
  // -----------------------------
  const enableConcurrency = String(
    env.ENFORCE_CONCURRENCY || (betaMode ? "true" : "false")
  ).toLowerCase() === "true";

  if (enableConcurrency) {
    const ipH = ipHash(request);
    const windowSec = Number(env.CONCURRENCY_WINDOW_SEC || 90);
    // NOTE: To avoid KV write amplification, we keep short-lived session/strike
    // state in caches.default (cheap) instead of KV.
    const cache = caches.default;
    const sessReq = new Request(`https://cm-auth.local/${prefix}/sess/${encodeURIComponent(token)}`);
    const strikeReq = new Request(`https://cm-auth.local/${prefix}/strike/${encodeURIComponent(token)}`);

    let sess = null;
    try {
      const hit = await cache.match(sessReq);
      if (hit) sess = JSON.parse(await hit.text());
    } catch { sess = null; }

    const now = Math.floor(Date.now() / 1000);
    const prevPid = normalizePlayerId(sess?.player_id || "");
    const prevAt = Number(sess?.ts || 0);

    // If same token used by different player_id within window -> strike
    if (sess && prevPid && prevPid !== pid && (now - prevAt) <= windowSec) {
      let strikes = 0;
      try {
        const sHit = await cache.match(strikeReq);
        if (sHit) strikes = Number(await sHit.text()) || 0;
      } catch {}
      const next = strikes + 1;

      // store strikes in cache (30 days)
      try {
        await cache.put(
          strikeReq,
          new Response(String(next), { headers: { "Cache-Control": `max-age=${60 * 60 * 24 * 30}` } })
        );
      } catch {}

      const maxStrikes = Number(env.MAX_STRIKES || 3);
      const autoBlock = String(env.AUTO_BLOCK || "true").toLowerCase() === "true";

      if (autoBlock && next >= maxStrikes) {
        policy.disabled = true;
        policy.disabled_reason = `Auto-block: concurrency strikes=${next}`;
        policy.disabled_at = Date.now();

        await env.CM_KV.put(tokKey, JSON.stringify(policy), {
          expirationTtl: 60 * 60 * 24 * 180,
        });

        return { ok: false, status: 403, error: "Token auto-blocked (concurrency abuse)" };
      }
    }

    // heartbeat session (cache)
    const now2 = Math.floor(Date.now() / 1000);
    try {
      await cache.put(
        sessReq,
        new Response(JSON.stringify({ player_id: pid, ts: now2, ip: ipH }), {
          headers: { "Cache-Control": `max-age=${Math.max(60, windowSec * 2)}` },
        })
      );
    } catch {}
  }

  // -----------------------------
  // SOFT USAGE METER (optional)
  // -----------------------------
  // This was originally stored in KV per request which can easily exhaust KV daily write quotas.
  // We only keep it if you explicitly enable it OR if you set DAILY_HARD_CAP > 0.
  const hardCap = Number(env.DAILY_HARD_CAP || 0); // 0 = off
  const enableUsage = String(env.ENABLE_USAGE_METER || "").toLowerCase() === "true" || hardCap > 0;

  if (enableUsage) {
    const day = new Date().toISOString().slice(0, 10);
    const usageKey = `${prefix}:usage:${day}:${token}`;
    const u = Number(await env.CM_KV.get(usageKey)) || 0;

    await env.CM_KV.put(usageKey, String(u + 1), {
      expirationTtl: 60 * 60 * 24 * 40,
    });

    if (hardCap > 0 && (u + 1) > hardCap) {
      return { ok: false, status: 429, error: "Daily cap exceeded" };
    }
  }

  return { ok: true, token, policy, mode: prefix };
}