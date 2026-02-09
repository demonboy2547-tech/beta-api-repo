// security_hmac.js
// HMAC Signed Request (shared for public + beta)
// Headers required:
// - X-CM-Id: client_id
// - X-CM-Ts: unix seconds
// - X-CM-Sig: hex(hmac_sha256(secret, canonical))

export async function verifySignedRequest({ request, env }) {
  const clientId = (request.headers.get("X-CM-Id") || "").trim();
  const tsStr = (request.headers.get("X-CM-Ts") || "").trim();
  const sig = (request.headers.get("X-CM-Sig") || "").trim();

  if (!clientId || !tsStr || !sig) {
    return { ok: false, status: 401, error: "Missing signed headers" };
  }

  const ts = Number(tsStr);
  if (!Number.isFinite(ts)) return { ok: false, status: 401, error: "Invalid timestamp" };

  const skew = Math.max(10, Number(env.CM_HMAC_MAX_SKEW_SEC || 120));
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - ts) > skew) {
    return { ok: false, status: 401, error: "Signature expired" };
  }

  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method.toUpperCase();

  const secrets = getSecrets(env);
  if (!secrets.length) return { ok: false, status: 500, error: "Missing CM_HMAC_SECRET(S)" };

  // --- canonical v2 (recommended): bind to clientId too ---
  let canonicalV2;
  let canonicalV1; // backward compatible

  if (method === "POST") {
    let bodyHashHex;
    try {
      const rawBody = await request.clone().arrayBuffer();
      bodyHashHex = await sha256Hex(rawBody);
    } catch {
      // Avoid uncaught exceptions → Cloudflare 1101
      return { ok: false, status: 400, error: "Unable to read request body for signature" };
    }

    // v2 (NEW): clientId included + stable ordering
    canonicalV2 = `${tsStr}\n${clientId}\n${method}\n${path}\n${bodyHashHex}`;

    // v1 (OLD): what you used before
    canonicalV1 = `${tsStr}\n${method}\n${path}\n${bodyHashHex}`;
  } else {
    const idParam = (url.searchParams.get("id") || "").trim();
    if (!idParam) return { ok: false, status: 401, error: "Missing id" };

    // normalize id to reduce encoding mismatch
    const idNorm = encodeURIComponent(idParam);

    // v2 (NEW)
    canonicalV2 = `${tsStr}\n${clientId}\n${method}\n${path}\nid=${idNorm}`;

    // v1 (OLD)
    canonicalV1 = `${tsStr}\n${method}\n${path}\nid=${idParam}`;
  }

  // accept if ANY secret matches (key rotation)
  for (const secret of secrets) {
    let expectedV2;
    try {
      expectedV2 = await hmacSha256Hex(secret, canonicalV2);
    } catch {
      return { ok: false, status: 500, error: "HMAC engine error" };
    }
    if (timingSafeEqualHex(expectedV2, sig)) {
      return { ok: true, client_id: clientId, version: "v2" };
    }

    // fallback: accept old signature (so old mod still works)
    let expectedV1;
    try {
      expectedV1 = await hmacSha256Hex(secret, canonicalV1);
    } catch {
      return { ok: false, status: 500, error: "HMAC engine error" };
    }
    if (timingSafeEqualHex(expectedV1, sig)) {
      return { ok: true, client_id: clientId, version: "v1" };
    }
  }

  return { ok: false, status: 401, error: "Bad signature" };
}

function getSecrets(env) {
  const many = String(env.CM_HMAC_SECRETS || "").trim();
  if (many) {
    return many.split(",").map(s => s.trim()).filter(Boolean);
  }
  const one = String(env.CM_HMAC_SECRET || "").trim();
  return one ? [one] : [];
}

async function sha256Hex(buf) {
  const digest = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function hmacSha256Hex(secret, msg) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(msg));
  return [...new Uint8Array(sig)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqualHex(a, b) {
  if (a.length !== b.length) return false;
  let out = 0;
  for (let i = 0; i < a.length; i++) out |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return out === 0;
}