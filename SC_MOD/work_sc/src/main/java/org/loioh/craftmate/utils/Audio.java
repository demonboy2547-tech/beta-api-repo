package org.loioh.craftmate.utils;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;


import org.loioh.craftmate.CraftMate;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Audio {

    public enum Mode {
        STOP_CURRENT,
        WAIT_CURRENT
    }

    private static volatile Clip currentClip;
    private static volatile Path currentFile;
    private static final ConcurrentLinkedQueue<Path> queue = new ConcurrentLinkedQueue<>();
    private static volatile Mode mode = Mode.STOP_CURRENT;
    private static final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public static void setMode(Mode newMode) {
        mode = newMode;
    }

    public static void playFromUrl(String url) {
        Schedule.runTaskAsync(() -> {
            try {
                CraftMate.getInstance().log("Audio download: " + url);

                Path downloaded = download(url);
                if (downloaded == null || !Files.exists(downloaded)) {
                    CraftMate.getInstance().log("Audio download failed");
                    return;
                }

                CraftMate.getInstance().log("Audio downloaded: " + downloaded.getFileName());

                // Ensure ModLauncher transformers can resolve shaded classes (jlayer)
                ClassLoader __oldCl = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(Audio.class.getClassLoader());
                    Path converted = convertMp3ToWav(downloaded);
                    if (converted == null || !Files.exists(converted)) {
                        CraftMate.getInstance().log("Audio conversion failed");
                        deleteQuietly(downloaded);
                        return;
                    }

                    CraftMate.getInstance().log("Audio ready to enqueue: " + converted.getFileName());

                    enqueue(converted);
                } finally {
                    Thread.currentThread().setContextClassLoader(__oldCl);
                }

                return;
            } catch (Exception ex) {
                CraftMate.getInstance().log("Audio error: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }

    public static void stopAll() {
        shuttingDown.set(true);

        Clip clip = currentClip;
        if (clip != null) {
            clip.stop();
            clip.close();
        }

        deleteQuietly(currentFile);
        currentClip = null;
        currentFile = null;

        Path file;
        while ((file = queue.poll()) != null) {
            deleteQuietly(file);
        }

        isPlaying.set(false);
        CraftMate.getInstance().log("Audio stopped");
    }

    public static void onClientLogout() {
        stopCurrentOnly();
    }
    private static void stopCurrentOnly() {
        synchronized (LOCK) {
            Clip clip = currentClip;
            if (clip != null) {
                clip.stop();
                clip.close();
            }

            deleteQuietly(currentFile);
            currentClip = null;
            currentFile = null;

            isPlaying.set(false);
        }
    }

    private static void stopCurrent() {
        Clip clip = currentClip;
        if (clip != null) {
            clip.stop();
            clip.close();
        }
        deleteQuietly(currentFile);
        currentClip = null;
        currentFile = null;
        isPlaying.set(false);
    }
    private static final Object LOCK = new Object();

    private static void enqueue(Path file) {
        synchronized (LOCK) {
            if (shuttingDown.get()) {
                deleteQuietly(file);
                return;
            }

            // Если нужно остановить текущий трек
            if (isPlaying.get() && mode == Mode.STOP_CURRENT) {
                stopCurrent();
            }

            queue.offer(file);

            // Если сейчас ничего не играет, запускаем следующий
            if (!isPlaying.get()) {
                playNext();
            } else {
                CraftMate.getInstance().log("Audio queued: " + queue.size());
            }
        }
    }

    private static void playNext() {
        Path next;
        synchronized (LOCK) {
            next = queue.poll();
            if (next == null) {
                isPlaying.set(false);
                return;
            }
            isPlaying.set(true);
            currentFile = next;
        }

        Schedule.runTaskAsync(() -> {
            try {
                AudioInputStream stream = AudioSystem.getAudioInputStream(next.toFile());
                Clip clip = AudioSystem.getClip();
                clip.open(stream);
                currentClip = clip;

                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) {
                        try { clip.close(); } catch (Exception ignored) {}
                        deleteQuietly(next);

                        synchronized (LOCK) {
                            if (currentClip == clip) {
                                currentClip = null;
                                currentFile = null;
                                isPlaying.set(false);
                            }
                            // Запускаем следующий трек
                            if (!queue.isEmpty() && !shuttingDown.get()) {
                                playNext();
                            }
                        }
                    }
                });

                clip.start();
            } catch (Exception ex) {
                deleteQuietly(next);
                isPlaying.set(false);
                CraftMate.getInstance().log("Audio play error: " + ex.getMessage());
            }
        });
    }




















    private static void play(Path file) {
        try {
            AudioInputStream stream = AudioSystem.getAudioInputStream(file.toFile());
            Clip clip = AudioSystem.getClip();
            clip.open(stream);

            currentClip = clip;
            currentFile = file;

            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    try { clip.close(); } catch (Exception ignored) {}
                    deleteQuietly(file);
                    synchronized (LOCK) {
                        if (currentClip == clip) {
                            currentClip = null;
                            currentFile = null;
                            isPlaying.set(false);
                        }
                        playNext();
                    }
                }
            });

            clip.start();
        } catch (Exception ex) {
            deleteQuietly(file);
            isPlaying.set(false);
            CraftMate.getInstance().log("Audio play error: " + ex.getMessage());
        }
    }

    private static Path download(String url) {
        try {
            Path file = Files.createTempFile("audio_", ".mp3");

            // Use HttpClient so we can attach Authorization + HMAC headers
            URI uri = URI.create(url);

            String token = String.valueOf(CraftMate.getFromConfig("apiToken", ""));
String clientId = String.valueOf(CraftMate.getFromConfig("clientId", "craftmate"));
String hmacSecret = SecuritySigner.EMBEDDED_BETA_HMAC_SECRET;
boolean enableHmac = true;
long ts = System.currentTimeMillis() / 1000L;
            String path = uri.getPath();
            String idParam = "";
            String q = uri.getQuery();
            if (q != null && q.contains("id=")) {
                // simple parse (id is UUID, safe)
                for (String part : q.split("&")) {
                    if (part.startsWith("id=")) {
                        idParam = part.substring(3);
                        break;
                    }
                }
            }

            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("Accept", "audio/mpeg")
                    .header("User-Agent", "CraftMateMod/1.0");

            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token.trim());
            }

            if (enableHmac && hmacSecret != null && !hmacSecret.isBlank()) {
                if (clientId == null || clientId.isBlank()) clientId = "craftmate";

        // canonical builder expects timestamp as string
        String canonical = SecuritySigner.canonicalV2GetTts(ts, clientId.trim(), path, idParam);
                String sig = SecuritySigner.hmacSha256Hex(hmacSecret.trim(), canonical);

                b.header("X-CM-Id", clientId.trim());
                b.header("X-CM-Ts", String.valueOf(ts));
                b.header("X-CM-Sig", sig);
            }

            HttpClient client = HttpClient.newBuilder().build();
            HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() != 200) {
                CraftMate.getInstance().log("TTS download failed: status=" + resp.statusCode());
                CraftMate.sendCraftMateError("TTS download failed (status " + resp.statusCode() + ")");
                return null;
            }

            Files.write(file, resp.body(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            return file;

        } catch (Exception ex) {
            CraftMate.getInstance().log("Download error: " + ex.getMessage());
            CraftMate.sendCraftMateError(ex.getMessage() != null ? ex.getMessage() : "Download error");
            ex.printStackTrace();
            return null;
        }
    }

    private static Path convertMp3ToWav(Path mp3File) {
        FileInputStream fis = null;
        Bitstream bitstream = null;
        try {
            CraftMate.getInstance().log("Converting MP3 to WAV");
            long start = System.currentTimeMillis();

            Path wavFile = Files.createTempFile("audio_", ".wav");

            fis = new FileInputStream(mp3File.toFile());
            bitstream = new Bitstream(fis);

            Decoder decoder = null;
            try {
                decoder = new Decoder();
            } catch (Exception e) {
                CraftMate.getInstance().log("Decoder init error, retrying...");
                decoder = new Decoder();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            Header header = null;
            int frameCount = 0;
            int sampleRate = 44100;
            int channels = 1;

            while (true) {
                try {
                    header = bitstream.readFrame();
                    if (header == null) break;
                } catch (Exception e) {
                    break;
                }

                try {
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                    if (frameCount == 0) {
                        sampleRate = header.frequency();
                        channels = (header.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
                        CraftMate.getInstance().log("MP3: " + sampleRate + "Hz, " + channels + "ch");
                    }

                    short[] pcm = output.getBuffer();
                    int bufferLength = output.getBufferLength();

                    for (int i = 0; i < bufferLength; i++) {
                        short sample = pcm[i];
                        baos.write(sample & 0xff);
                        baos.write((sample >> 8) & 0xff);
                    }

                    frameCount++;
                } catch (Exception e) {
                    // Skip bad frame
                } finally {
                    try {
                        bitstream.closeFrame();
                    } catch (Exception ignored) {}
                }
            }

            try { if (bitstream != null) bitstream.close(); } catch (Exception ignored) {}
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}

            byte[] audioData = baos.toByteArray();
            CraftMate.getInstance().log("PCM: " + audioData.length + " bytes");

            if (audioData.length == 0) {
                CraftMate.getInstance().log("No audio data decoded");
                deleteQuietly(mp3File);
                return null;
            }

            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2,
                    sampleRate,
                    false
            );

            ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
            long frameLength = audioData.length / format.getFrameSize();
            AudioInputStream audioStream = new AudioInputStream(bais, format, frameLength);

            AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, wavFile.toFile());
            audioStream.close();

            deleteQuietly(mp3File);

            long end = System.currentTimeMillis();
            CraftMate.getInstance().log("Converted: " + (end - start) + "ms, " + frameCount + " frames");

            return wavFile;

        } catch (Exception ex) {
            CraftMate.getInstance().log("Conversion failed: " + ex.getMessage());
            try { if (bitstream != null) bitstream.close(); } catch (Exception ignored) {}
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            deleteQuietly(mp3File);
            return null;
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {}
    }

    public static void testWav(String url) {
        Schedule.runTaskAsync(() -> {
            try {
                CraftMate.getInstance().log("[TEST] Download WAV: " + url);
                Path file = download(url);
                CraftMate.getInstance().log("[TEST] Playing WAV directly");
                play(file);
            } catch (Exception ex) {
                CraftMate.getInstance().log("[TEST] Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }
    public static void testMP3(String url){

        Schedule.runTaskAsync(() -> {
            try {
                CraftMate.getInstance().log("[TEST] Download MP3: " + url);
                Path file = download(url);
                ClassLoader __oldCl2 = Thread.currentThread().getContextClassLoader();

                try {

                    Thread.currentThread().setContextClassLoader(Audio.class.getClassLoader());

                    file = convertMp3ToWav(file);

                } finally {

                    Thread.currentThread().setContextClassLoader(__oldCl2);

                }
                CraftMate.getInstance().log("[TEST] Playing MP3 directly");
                play(file);
            } catch (Exception ex) {
                CraftMate.getInstance().log("[TEST] Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }
}