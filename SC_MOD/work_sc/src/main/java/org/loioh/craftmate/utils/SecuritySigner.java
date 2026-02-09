package org.loioh.craftmate.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Utility for signing requests to CraftMate Cloudflare Worker.
 *
 * Canonical v2:
 *  - POST:
 *    ts\nclientId\nMETHOD\n/path\nsha256Hex(body)
 *  - GET (/craftmate/tts?id=...):
 *    ts\nclientId\nGET\n/craftmate/tts\nid=<urlEncodedId>
 */
public class SecuritySigner {

    // Embedded beta HMAC key (requested). Note: this is still extractable by a determined attacker.
    // For public release you should rotate to a server-issued, per-user key or remove client-side shared secret entirely.
    public static final String EMBEDDED_BETA_HMAC_SECRET = "J9vKp2Lw7xQmT3nR5zHc8uYf1sDa6BgE4tXi0oNvZq1Pr7LmC2sU9aQw5eHd3YkG";

    public static String sha256Hex(byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(body);
        return toHex(digest);
    }

    public static String canonicalV2Post(long ts, String clientId, String method, String path, String bodyHashHex) {
        return ts + "\n" + safe(clientId) + "\n" + method.toUpperCase() + "\n" + path + "\n" + bodyHashHex;
    }

    public static String canonicalV2GetTts(long ts, String clientId, String path, String id) {
        String idNorm = urlEncode(id);
        return ts + "\n" + safe(clientId) + "\nGET\n" + path + "\nid=" + idNorm;
    }

    public static String hmacSha256Hex(String secret, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        return toHex(sig);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String urlEncode(String s) {
        if (s == null) return "";
        // minimal URL encoding for id (matches server encodeURIComponent behavior for unsafe chars)
        // This is enough for UUID-like ids; if you ever change the id format, consider a full encoder.
        String out = s.trim();
        out = out.replace("%", "%25")
                 .replace(" ", "%20")
                 .replace("\n", "%0A")
                 .replace("\r", "%0D")
                 .replace("?", "%3F")
                 .replace("&", "%26")
                 .replace("=", "%3D")
                 .replace("#", "%23")
                 .replace("+", "%2B")
                 .replace("/", "%2F")
                 .replace(":", "%3A");
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
