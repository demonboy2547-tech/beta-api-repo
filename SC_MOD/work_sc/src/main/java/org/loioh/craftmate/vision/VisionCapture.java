package org.loioh.craftmate.vision;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.loioh.craftmate.Config;
import org.loioh.craftmate.CraftMate;
import org.loioh.craftmate.core.HTTP_Hook;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vision screenshot pipeline (client-side):
 * - Triggered from tick (SceneLoop) via requestCapture(...)
 * - Actual framebuffer read happens ONLY on RenderGuiOverlayEvent.Post (after HUD)
 * - Encode + upload happens on a worker thread to avoid blocking render thread.
 */
@Mod.EventBusSubscriber(modid = "craftmate", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VisionCapture {

    private VisionCapture() {}

    // Single thread for encoding JPGs so we don't lag the game with heavy IO/Compression
    private static final ExecutorService VISION_EXEC = new java.util.concurrent.ThreadPoolExecutor(
        1, 1,
        0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        new java.util.concurrent.ArrayBlockingQueue<>(1),
        r -> {
            Thread t = new Thread(r, "CraftMate-VisionWorker");
            t.setDaemon(true);
            return t;
        },
        new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    );

    private static final AtomicReference<Request> PENDING = new AtomicReference<>(null);
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    /** Raw pixel frame captured from GPU readback (RGBA, top-left origin). */
    private static final class FrameData {
        final byte[] rgba;
        final int w;
        final int h;
        FrameData(byte[] rgba, int w, int h) { this.rgba = rgba; this.w = w; this.h = h; }
    }

    /**
     * Some setups don't reliably run RenderGuiOverlayEvent (e.g., hidden GUI, overlay conflicts).
     * To avoid "queued forever", every time we enqueue a request we also schedule a render-thread
     * callback that tries to consume the pending request.
     */
    private static void scheduleRenderConsume() {
        try {
            RenderSystem.recordRenderCall(() -> {
                tryConsumePending("renderCall");
            });
        } catch (Throwable ignored) {
            // recordRenderCall may not be available very early in init
        }
    }

    private static void tryConsumePending(String source) {
        // Reuse the same code path as the GUI overlay event handler.
        try {
            onRenderOverlayPost(null);
        } catch (Throwable t) {
            VisionDebug.fatal("consumePending(" + source + ")", t);
            IN_FLIGHT.set(false);
        }
    }

    private static final class Request {
        final UUID playerId;
        final JsonObject hint;
        final int maxDim;
        final long createdMs;

        int attempts = 0;
        // Prevent immediate retry loops within the same frame when capture fails.
        long notBeforeMs = 0L;

        Request(UUID playerId, JsonObject hint, int maxDim) {
            this.playerId = playerId;
            this.hint = hint;
            this.maxDim = maxDim;
            this.createdMs = System.currentTimeMillis();
        }
    }

    /** Called from tick thread: schedule ONE capture; if one is pending/in-flight, ignore. */
    public static boolean requestCapture(UUID playerId, JsonObject hint) {
        if (!Config.getVisionEnabled()) return false;
        try {
            String r = "";
            if (hint != null && hint.has("reason")) r = hint.get("reason").getAsString();
            CraftMate.vLog("requestCapture reason=" + r);
        } catch (Throwable ignored) {}
        
        // Auto capture respects IN_FLIGHT to avoid spam
        if (IN_FLIGHT.get()) return false;

        int maxDim = Config.getVisionMaxDim();
        if (maxDim < 128) maxDim = 128;
        if (maxDim > 1024) maxDim = 1024;

        Request req = new Request(playerId, hint, maxDim);
        boolean ok = PENDING.compareAndSet(null, req);
        if (ok) {
            scheduleRenderConsume();
        }
        return ok;
    }

    /** Render hook: run only after a late GUI overlay post (after HUD). */
    
    // -------------------------
    // Force-capture API (used for chat-triggered vision)
    //  - Bypasses normal pending-guard (overwrites PENDING) to guarantee a screenshot for player intent
    // -------------------------
    private static volatile long lastEnqueuedVisionMs = 0L;
    private static volatile long glReadPixelsDisabledUntilMs = 0L;
    private static volatile long lastOverlayConsumeNanos = 0L;
    private static final long GL_READPIXELS_DISABLE_MS = 60_000L;
    private static volatile String lastSavedVisionPath = "";

    private static volatile String lastEnqueuedVisionReason = "";
    private static volatile String lastEnqueuedVisionMessage = "";

    public static long getLastEnqueuedVisionMs() { return lastEnqueuedVisionMs; }
    public static String getLastEnqueuedVisionReason() { return lastEnqueuedVisionReason; }
    public static String getLastEnqueuedVisionMessage() { return lastEnqueuedVisionMessage; }

    private static String capMsg(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    public static boolean isInFlight() {
        try { return IN_FLIGHT.get(); } catch (Throwable t) { return false; }
    }

    public static boolean hasPending() {
            try { return PENDING.get() != null; } catch (Throwable t) { return false; }
    }


    /** Force a capture ASAP on the next RenderGuiOverlayEvent.Post. */
    public static boolean forceCapture(UUID playerId, JsonObject hint, String reason, String chatMessage) {
        if (!Config.getVisionEnabled()) return false;
        try { CraftMate.vLog("forceCapture reason=" + (reason == null ? "" : reason)); } catch (Throwable ignored) {}
        
        // [FIXED] Commented out IN_FLIGHT check to allow queue jumping for Chat
        // if (IN_FLIGHT.get()) return false;

        int maxDim = Config.getVisionMaxDim();
        if (maxDim < 128) maxDim = 128;
        if (maxDim > 1024) maxDim = 1024;

        // Ensure reason tagging exists
        if (hint == null) hint = new JsonObject();
        try {
            hint.addProperty("reason", reason == null ? "chat" : reason);
            hint.addProperty("source", "player");
            if (chatMessage != null && !chatMessage.isBlank()) hint.addProperty("message", capMsg(chatMessage));
        } catch (Throwable ignored) {}

        // Overwrite any pending auto/scene capture to guarantee the chat screenshot.
        PENDING.set(new Request(playerId, hint, maxDim));

        lastEnqueuedVisionMs = System.currentTimeMillis();
        try { org.loioh.craftmate.vision.VisionDebug.markFired(); org.loioh.craftmate.vision.VisionDebug.setStage("queued"); } catch (Throwable ignored) {}
        CraftMate.vLog("Queued capture reason=" + (reason == null ? "" : reason));
        lastEnqueuedVisionReason = reason == null ? "chat" : reason;
        lastEnqueuedVisionMessage = capMsg(chatMessage);

        scheduleRenderConsume();
        return true;
    }

    @SubscribeEvent
    public static void onRenderOverlayPost(RenderGuiOverlayEvent.Post e) {

        // Optional on-screen debug (only when Config.debugLog=true)
        try {
            if (Config.debugLog && e != null) {
                Minecraft mcDbg = Minecraft.getInstance();
                if (mcDbg != null && mcDbg.font != null && e.getGuiGraphics() != null) {
                    String line1 = HTTP_Hook.getLastVisionDebugLine();
                    String line2 = "Vision state: pending=" + (PENDING.get() != null) + " inFlight=" + IN_FLIGHT.get();
                    String line3 = "Last JPG: " + (lastSavedVisionPath == null ? "" : (lastSavedVisionPath.length() > 80 ? lastSavedVisionPath.substring(lastSavedVisionPath.length()-80) : lastSavedVisionPath));
                    long nowMs = System.currentTimeMillis();
                    String line4 = (nowMs < glReadPixelsDisabledUntilMs) ? ("glReadPixels DISABLED " + ((glReadPixelsDisabledUntilMs-nowMs)/1000) + "s") : "glReadPixels ok";
                    e.getGuiGraphics().drawString(mcDbg.font, line1, 6, 6, 0xFFFFFF, true);
                    e.getGuiGraphics().drawString(mcDbg.font, line2, 6, 16, 0xFFFFFF, true);
                    e.getGuiGraphics().drawString(mcDbg.font, line3, 6, 26, 0xFFFFFF, true);
                    e.getGuiGraphics().drawString(mcDbg.font, line4, 6, 36, 0xFFFFFF, true);
                }
            }
        } catch (Throwable ignored) {}

        if (!Config.getVisionEnabled()) return;

        // Guard: RenderGuiOverlayEvent.Post can fire many times per frame; consume at most once every few ms.
        long nowNanos = System.nanoTime();
        if (nowNanos - lastOverlayConsumeNanos < 5_000_000L) return;
        lastOverlayConsumeNanos = nowNanos;

        Request req = PENDING.get();
        if (req == null) return;

        // Avoid same-frame retry loops when capture fails
        long nowMs0 = System.currentTimeMillis();
        if (req.notBeforeMs > 0L && nowMs0 < req.notBeforeMs) {
            return;
        }

        // 🔒 If this is a chat-triggered capture, wait a tiny bit (80ms) so we don't fight GUI/GL state *within the same frame*.
        String _reason = "";
        try { if (req.hint != null && req.hint.has("reason")) _reason = req.hint.get("reason").getAsString(); } catch (Throwable ignored) {}
        long _ageMs = System.currentTimeMillis() - req.createdMs;
        final String reason = _reason;
        
        // Short delay for stability, but still feels instant to user.
        if ("chat".equalsIgnoreCase(_reason) && _ageMs < 80L) {
            return; // leave PENDING intact; next frame will consume it
        }

        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        PENDING.set(null);

        try {
            VisionDebug.setStage("render");
            CraftMate.vLog("Render capture start");
        } catch (Throwable ignored) {}

        boolean submitted = false;

        NativeImage img = null;
        RenderTarget fb = null;

        try {
            RenderSystem.assertOnRenderThreadOrInit();

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;

            fb = mc.getMainRenderTarget();
            if (fb == null) return;
            
            int w = fb.width;
            int h = fb.height;
            if (w <= 0 || h <= 0) return;

            // Capture using Minecraft's Screenshot path first
            img = tryScreenshotCapture(fb);

            // If Screenshot path is unavailable, try RenderTarget#downloadFromFramebuffer(NativeImage)
            if (img == null) {
                img = tryDownloadFromFramebuffer(fb, w, h);
            }
            // Fallback to direct GL readback
            if (img == null) {
                long now = System.currentTimeMillis();
                if (now >= glReadPixelsDisabledUntilMs) {
                    try {
                        img = captureNativeImage();
                    } catch (Throwable ignored) {
                        img = null;
                    }
                }
            }

            FrameData fd = null;
            if (img == null) {
                long now = System.currentTimeMillis();
                if (now >= glReadPixelsDisabledUntilMs) {
                    try {
                        fd = tryGlReadPixelsFrame(fb, w, h);
                    } catch (Throwable ignored) {
                        fd = null;
                    }
                }
            }


            if (img == null && fd == null) {
                // If this was a chat-triggered capture, retry a few times
                if ("chat".equalsIgnoreCase(reason) && req.attempts < 5) {
                    req.attempts++;
                    req.notBeforeMs = System.currentTimeMillis() + 120L;
                    IN_FLIGHT.set(false);
                    PENDING.set(req);
                    return;
                }
                return;
            }


            try {
                Method dl = null;
                try { dl = fb.getClass().getMethod("downloadFromFramebuffer", NativeImage.class); } catch (Throwable ignored) {}

                if (dl == null) {
                    Class<?> c = fb.getClass();
                    while (c != null && dl == null) {
                        for (Method m : c.getDeclaredMethods()) {
                            Class<?>[] p = m.getParameterTypes();
                            if (m.getName().equals("downloadFromFramebuffer")
                                    && p.length == 1
                                    && p[0] == NativeImage.class
                                    && m.getReturnType() == void.class) {
                                dl = m;
                                break;
                            }
                        }
                        c = c.getSuperclass();
                    }
                }

                if (dl != null) {
                    dl.setAccessible(true);
                    dl.invoke(fb, img);
                }
            } catch (Throwable ignored) {}

            if (img != null) {
                try { img.flipY(); } catch (Throwable ignored) {}
            }

            // Dev proof: for reason=chat, save a PNG immediately
            try {
                if ("chat".equalsIgnoreCase(reason)) {
                    if (img != null) {
                        saveVisionPngToDisk(img, req.createdMs);
                    } else if (fd != null) {
                        saveVisionPngToDisk(fd.rgba, fd.w, fd.h, req.createdMs);
                    }
                }
            } catch (Throwable ignored) {}

            final byte[] rgba = (img != null) ? extractRgba(img) : (fd != null ? fd.rgba : null);
            final int fW = (img != null) ? img.getWidth() : (fd != null ? fd.w : 0);
            final int fH = (img != null) ? img.getHeight() : (fd != null ? fd.h : 0);
            final Request fReq = req;

            if (rgba == null || rgba.length == 0) return;

            VISION_EXEC.submit(() -> {
                try {
                    Encoded enc = encodeJpegFromRgba(rgba, fW, fH, fReq.maxDim);
                    if (enc == null) return;
                    try { org.loioh.craftmate.vision.VisionDebug.setStage("jpeg"); } catch (Throwable ignored) {}
                    try { CraftMate.vLog("JPEG encoded bytes=" + enc.jpegBytes.length + " w=" + enc.w + " h=" + enc.h); } catch (Throwable ignored) {}
                    
                    try {
                        String reason2 = "";
                        if (fReq.hint != null && fReq.hint.has("reason")) reason2 = fReq.hint.get("reason").getAsString();
                        if (Config.getVisionSaveLastAlways()) {
                            saveDebugLastJpegToDisk(enc.jpegBytes);
                        }
                        if ("chat".equalsIgnoreCase(reason2) && Config.getVisionSaveChatAlways()) {
                            saveVisionJpegToDisk(enc.jpegBytes, fReq.createdMs);
                        }
                    } catch (Throwable t) {
                        CraftMate.vFatal("save jpeg failed", t);
                    }
                    try { org.loioh.craftmate.vision.VisionDebug.setStage("http"); } catch (Throwable ignored) {}
                    try { CraftMate.vLog("HTTP POST /vision start sha=" + enc.sha256.substring(0, 8)); } catch (Throwable ignored) {}
                    
                    if (enc.b64 == null || enc.b64.isEmpty()) {
                        System.out.println("[CraftMate] vision encode failed (empty b64); skip postVision");
                        return;
                    }
                    
                    // [FIXED CRITICAL] Pass enc.b64 (String) explicitly, NOT 'enc' object!
                    HTTP_Hook.postVision(fReq.playerId, fReq.hint, enc.b64, "image/jpeg", enc.w, enc.h, enc.sha256);
                    
                } catch (Throwable t) {
                    CraftMate.vFatal("vision worker error", t);
                } finally {
                    IN_FLIGHT.set(false);
                }
            });

            submitted = true;

        } catch (Throwable t) {
            IN_FLIGHT.set(false);
        } finally {
            try { if (img != null) img.close(); } catch (Throwable ignored) {}
            try { if (fb != null) fb.unbindWrite(); } catch (Throwable ignored) {}
            if (!submitted) IN_FLIGHT.set(false);
        }
    }


    private static NativeImage tryScreenshotCapture(RenderTarget fb) {
        try {
            for (String name : new String[]{"takeScreenshot", "grab", "takeScreenshotRaw"}) {
                try {
                    for (Method m : Screenshot.class.getDeclaredMethods()) {
                        if (!m.getName().equals(name)) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && RenderTarget.class.isAssignableFrom(p[0]) && NativeImage.class.isAssignableFrom(m.getReturnType())) {
                            m.setAccessible(true);
                            return (NativeImage) m.invoke(null, fb);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static NativeImage tryDownloadFromFramebuffer(RenderTarget fb, int w, int h) {
        NativeImage img = null;
        try {
            img = new NativeImage(w, h, false);
            Method dl = null;
            Class<?> c = fb.getClass();
            while (c != null && dl == null) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("downloadFromFramebuffer") && p.length == 1 && p[0] == NativeImage.class && m.getReturnType() == void.class) {
                        dl = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }

            if (dl != null) {
                dl.setAccessible(true);
                dl.invoke(fb, img);
                return img;
            }
        } catch (Throwable t) {
            try { if (img != null) img.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void saveVisionPngToDisk(NativeImage img, long createdMs) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            File base = new File(mc.gameDirectory, "screenshots");
            File dir = new File(base, "craftmate");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "vision_" + createdMs + ".png");

            try {
                for (Method m : NativeImage.class.getDeclaredMethods()) {
                    if (!m.getName().equals("writeToFile")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1) {
                        m.setAccessible(true);
                        if (p[0] == File.class) {
                            m.invoke(img, out);
                            lastSavedVisionPath = out.getAbsolutePath();
                            return;
                        }
                        if ("java.nio.file.Path".equals(p[0].getName())) {
                            m.invoke(img, out.toPath());
                            lastSavedVisionPath = out.getAbsolutePath();
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            try {
                BufferedImage bi = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        int rgba = img.getPixelRGBA(x, y);
                        bi.setRGB(x, y, rgba);
                    }
                }
                ImageIO.write(bi, "png", out);
                lastSavedVisionPath = out.getAbsolutePath();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }


    private static void saveVisionPngToDisk(byte[] rgba, int w, int h, long createdMs) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            File base = new File(mc.gameDirectory, "screenshots");
            File dir = new File(base, "craftmate");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "vision_" + createdMs + ".png");

            try {
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int idx = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int r = rgba[idx++] & 0xFF;
                        int g = rgba[idx++] & 0xFF;
                        int b = rgba[idx++] & 0xFF;
                        int a = rgba[idx++] & 0xFF;
                        int argb = (a << 24) | (r << 16) | (g << 8) | b;
                        bi.setRGB(x, y, argb);
                    }
                }
                ImageIO.write(bi, "png", out);
                lastSavedVisionPath = out.getAbsolutePath();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {}
    }




    private static FrameData tryGlReadPixelsFrame(RenderTarget fb, int w, int h) {
        try {
            try {
                fb.bindRead();
            } catch (Throwable t) {
                fb.bindWrite(true);
            }
            try { GL11.glFinish(); } catch (Throwable ignored) {}
            try { GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1); } catch (Throwable ignored) {}

            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);

            int err = 0;
            try { err = GL11.glGetError(); } catch (Throwable ignored) {}
            if (err != 0) {
                glReadPixelsDisabledUntilMs = System.currentTimeMillis() + GL_READPIXELS_DISABLE_MS;
				try {
					org.loioh.craftmate.CraftMate.vLog("glReadPixels error=0x" + Integer.toHexString(err) + " (disabled for " + (GL_READPIXELS_DISABLE_MS/1000) + "s)");
					VisionDebug.setLastError("glReadPixels err=0x" + Integer.toHexString(err));
				} catch (Throwable ignored) {}
                return null;
            }

            byte[] rgba = new byte[w * h * 4];
            int rowStride = w * 4;
            for (int y = 0; y < h; y++) {
                int srcRow = y * rowStride;
                int dstRow = (h - 1 - y) * rowStride;
                for (int x = 0; x < w; x++) {
                    int si = srcRow + x * 4;
                    int di = dstRow + x * 4;
                    byte b = buf.get(si);
                    byte g = buf.get(si + 1);
                    byte r = buf.get(si + 2);
                    byte a = buf.get(si + 3);
                    rgba[di] = r;
                    rgba[di + 1] = g;
                    rgba[di + 2] = b;
                    rgba[di + 3] = a;
                }
            }

            return new FrameData(rgba, w, h);
        } catch (Throwable t) {
            try {
                glReadPixelsDisabledUntilMs = System.currentTimeMillis() + GL_READPIXELS_DISABLE_MS;
            } catch (Throwable ignored) {}
			try {
				org.loioh.craftmate.CraftMate.vLog("tryGlReadPixelsFrame failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
				VisionDebug.setLastError("glReadPixels fail: " + t.getClass().getSimpleName());
			} catch (Throwable ignored) {}
            return null;
        }
    }

    private static NativeImage captureNativeImage() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        RenderTarget fb = mc.getMainRenderTarget();
        if (fb == null) return null;

        try {
            fb.bindRead();
        } catch (Throwable t) {
            fb.bindWrite(true);
        }
        try {
            org.lwjgl.opengl.GL11.glFinish();
            try { org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT, 1); } catch (Throwable ignored2) {}

        } catch (Throwable ignored) {}


        for (String name : new String[]{"takeScreenshot", "grab", "takeScreenshotRaw"}) {
            try {
                for (Method m : Screenshot.class.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && RenderTarget.class.isAssignableFrom(p[0]) && NativeImage.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        return (NativeImage) m.invoke(null, fb);
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            NativeImage img = new NativeImage(fb.width, fb.height, false);
            Method dl = null;
            for (Method m : NativeImage.class.getDeclaredMethods()) {
                if (m.getName().equals("downloadFromFramebuffer")) { dl = m; break; }
            }
            if (dl != null) {
                dl.setAccessible(true);
                Class<?>[] p = dl.getParameterTypes();
                if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == boolean.class) {
                    dl.invoke(img, 0, 0, true);
                    img.flipY();
                    return img;
                }
            }
            try { img.close(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] extractRgba(NativeImage img) throws Exception {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 4];
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = img.getPixelRGBA(x, y);
                int r = (rgba      ) & 0xFF;
                int g = (rgba >> 8 ) & 0xFF;
                int b = (rgba >> 16) & 0xFF;
                int a = (rgba >> 24) & 0xFF;
                out[idx++] = (byte) r;
                out[idx++] = (byte) g;
                out[idx++] = (byte) b;
                out[idx++] = (byte) a;
            }
        }
        return out;
    }

    private static final class Encoded {
        final String b64;
        final String sha256;
        final int w;
        final int h;
        final byte[] jpegBytes;
        Encoded(String b64, String sha256, int w, int h, byte[] jpegBytes) {
            this.b64 = b64;
            this.sha256 = sha256;
            this.w = w;
            this.h = h;
            this.jpegBytes = jpegBytes;
        }
    }

    private static Encoded encodeJpegFromRgba(byte[] rgba, int w, int h, int maxDim) throws Exception {
        if (rgba == null || rgba.length < w * h * 4) return null;

        int maxSide = Math.max(w, h);
        int outW = w;
        int outH = h;
        if (maxSide > maxDim) {
            double scale = (double) maxDim / (double) maxSide;
            outW = Math.max(1, (int) Math.round(w * scale));
            outH = Math.max(1, (int) Math.round(h * scale));
        }

        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < outH; y++) {
            int srcY = (int) ((long) y * h / outH);
            for (int x = 0; x < outW; x++) {
                int srcX = (int) ((long) x * w / outW);
                int si = (srcY * w + srcX) * 4;
                int r = rgba[si] & 0xFF;
                int g = rgba[si + 1] & 0xFF;
                int b = rgba[si + 2] & 0xFF;
                int rgb = (r << 16) | (g << 8) | b;
                img.setRGB(x, y, rgb);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(img, "jpg", baos);
        byte[] jpeg = baos.toByteArray();

        // Ensure we use the standard Base64 encoder and return a String
        String b64 = Base64.getEncoder().encodeToString(jpeg);
        String sha = sha256Hex(jpeg);
        return new Encoded(b64, sha, outW, outH, jpeg);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(bytes);
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void saveVisionJpegToDisk(byte[] jpegBytes, long createdMs) {
        if (jpegBytes == null || jpegBytes.length == 0) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) return;
            File root = new File(mc.gameDirectory, "screenshots");
            File dir = new File(root, "craftmate");
            if (!dir.exists() && !dir.mkdirs()) {
            }

            String name = "vision_" + createdMs + ".jpg";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(jpegBytes);
            }

            lastSavedVisionPath = out.getAbsolutePath();
            if (Config.debugLog) {
                try {
                    CraftMate.getInstance().log("[Vision] saved=" + out.getAbsolutePath());
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unused")
    private static void saveVisionJpegToDisk(byte[] jpegBytes, Long createdMs) {
        saveVisionJpegToDisk(jpegBytes, createdMs == null ? System.currentTimeMillis() : createdMs.longValue());
    }

    private static void saveDebugLastJpegToDisk(byte[] jpegBytes) {
        try {
            if (jpegBytes == null || jpegBytes.length == 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            File base = mc.gameDirectory;
            if (base == null) return;
            File dir = new File(new File(base, "screenshots"), "craftmate");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "debug_last.jpg");
            Files.write(out.toPath(), jpegBytes);
            VisionDebug.setStage("saved debug_last.jpg");
        } catch (Throwable t) {
            VisionDebug.fatal("saveDebugLastJpegToDisk", t);
        }
    }
}