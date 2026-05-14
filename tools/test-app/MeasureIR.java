// MeasureIR — fires a Dirac impulse at any native reverb plugin
// and captures the impulse response for objective A/B comparison.
//
// Use it to gauge whether tuning changes are actually moving the
// reverb closer to a reference (e.g. BABY Audio Crystalline plots).
// Outputs:
//   tools/test-app/ir.wav                — the IR as audio
//   tools/test-app/ir-spectrogram.png    — STFT spectrogram (log-mag)
//   tools/test-app/ir-decay.png          — per-octave RT60 decay curve
//   tools/test-app/ir-stats.json         — peak, RMS, RT60 per band,
//                                          spectral centroid over time
//
// Run from the repo root (JDK 11+):
//
//   java tools/test-app/MeasureIR.java
//   java tools/test-app/MeasureIR.java --plugin crystal --params "size=0.8,decay=0.9"
//   java tools/test-app/MeasureIR.java --plugin reverb --seconds 6
//
// Designed to run against ANY reverb plugin in the registry — the
// classloader scaffolding is the same as RunTest.java's. Drop in a
// new reverb, point this at it, compare the IR plots against a
// reference rendered with the same setup.

import javax.sound.sampled.*;
import javax.tools.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;

public class MeasureIR {

    private static final int SR = 44100;
    private static final int BLOCK = 1024;

    public static void main(String[] args) throws Exception {
        String pluginId = "crystal";
        Map<String, Float> overrides = new LinkedHashMap<>();
        float seconds = 4f;
        String outDir = "tools/test-app";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--plugin":  pluginId = args[++i]; break;
                case "--seconds": seconds = Float.parseFloat(args[++i]); break;
                case "--out":     outDir = args[++i]; break;
                case "--params": {
                    for (String kv : args[++i].split(",")) {
                        if (kv.isBlank()) continue;
                        int eq = kv.indexOf('=');
                        if (eq < 0) continue;
                        overrides.put(kv.substring(0, eq).trim(),
                                Float.parseFloat(kv.substring(eq + 1).trim()));
                    }
                    break;
                }
                default:
                    System.err.println("Unknown flag: " + args[i]);
                    System.exit(2);
            }
        }

        Path repoRoot = findRepoRoot();
        Path stubDir = repoRoot.resolve("scripts/native-stub/com/vocalmonitor/plugin");
        Path outBase = repoRoot.resolve(outDir);
        Files.createDirectories(outBase);

        // ── Locate + compile + load the plugin ──
        PluginEntry entry = findPlugin(repoRoot, pluginId);
        if (entry == null) {
            System.err.println("Unknown plugin id: " + pluginId);
            System.exit(1);
        }
        Path buildDir = repoRoot.resolve("scripts/native-stub/build/measureir-" + pluginId);
        Files.createDirectories(buildDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<File> sources = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(stubDir, "*.java")) {
            for (Path p : ds) sources.add(p.toFile());
        }
        sources.add(entry.javaSrc.toFile());
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            boolean ok = compiler.getTask(null, fm, null,
                    Arrays.asList("-d", buildDir.toString(), "--release", "8",
                            "-encoding", "utf-8"),
                    null, fm.getJavaFileObjectsFromFiles(sources)).call();
            if (!ok) { System.err.println("compile failed"); System.exit(1); }
        }
        URLClassLoader loader = new URLClassLoader(
                new URL[] { buildDir.toUri().toURL() },
                Thread.currentThread().getContextClassLoader());
        Class<?> cls = loader.loadClass(entry.className);
        Object plugin = cls.getDeclaredConstructor().newInstance();
        cls.getMethod("init", int.class).invoke(plugin, SR);

        // Apply parameter overrides — defaults are whatever the plugin
        // ships with; the --params flag tweaks anything you want
        // before the IR capture.
        Method pSet = cls.getMethod("setParameter", String.class, float.class);
        for (Map.Entry<String, Float> e : overrides.entrySet()) {
            pSet.invoke(plugin, e.getKey(), e.getValue());
        }
        // Force a fully wet, no-duck, no-freeze configuration so the
        // IR is the reverb on its own, not a mix-down.  Plugins that
        // don't have a `mix` param are unaffected.
        try { pSet.invoke(plugin, "mix",    1.0f); } catch (Exception ignored) {}
        try { pSet.invoke(plugin, "duck",   0.0f); } catch (Exception ignored) {}
        try { pSet.invoke(plugin, "freeze", 0.0f); } catch (Exception ignored) {}
        try { pSet.invoke(plugin, "rev",    0.0f); } catch (Exception ignored) {}

        // ── Capture the IR ──
        int total = (int) (seconds * SR);
        float[] ir = new float[total];
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        Method process = cls.getMethod("process", float[].class, float[].class);
        // Dirac impulse: 1.0 at sample 0, zero everywhere else.
        in[0] = 1.0f;
        long t0 = System.nanoTime();
        for (int i = 0; i < total; i += BLOCK) {
            int len = Math.min(BLOCK, total - i);
            process.invoke(plugin, in, out);
            System.arraycopy(out, 0, ir, i, len);
            // After the first block the impulse is gone — feed silence.
            if (i == 0) in[0] = 0f;
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf(Locale.ROOT,
                "Captured %d samples (%.2f s) in %d ms (%.1fx realtime)%n",
                total, total / (float) SR, elapsedMs,
                (total / (float) SR) / Math.max(0.001f, elapsedMs / 1000f));

        // ── Output: IR wav ──
        writeWav(ir, outBase.resolve("ir.wav").toFile());
        // ── Output: spectrogram ──
        writeSpectrogramPNG(ir, outBase.resolve("ir-spectrogram.png").toFile());
        // ── Output: per-octave decay curve (RT60) ──
        float[][] bandDecays = computeBandDecays(ir);
        writeDecayPNG(bandDecays, outBase.resolve("ir-decay.png").toFile());
        // ── Output: stats JSON ──
        writeStatsJson(ir, bandDecays, outBase.resolve("ir-stats.json"));

        System.out.println("IR written to " + outBase);
    }

    // ── Plugin discovery (mirrors RunTest.java's helper) ──
    static class PluginEntry {
        final String id, className;
        final Path javaSrc;
        PluginEntry(String id, Path javaSrc, String className) {
            this.id = id; this.javaSrc = javaSrc; this.className = className;
        }
    }
    private static PluginEntry findPlugin(Path repoRoot, String id) throws IOException {
        Path pluginsDir = repoRoot.resolve("plugins");
        try (var cats = Files.newDirectoryStream(pluginsDir, Files::isDirectory)) {
            for (Path catDir : cats) {
                try (var plugDirs = Files.newDirectoryStream(catDir, Files::isDirectory)) {
                    for (Path plugDir : plugDirs) {
                        if (!plugDir.getFileName().toString().equals(id)) continue;
                        Path metaPath = plugDir.resolve("plugin.json");
                        if (!Files.exists(metaPath)) continue;
                        String meta = Files.readString(metaPath);
                        String className = extractJsonString(meta, "className");
                        if (className == null) continue;
                        String simple = className.substring(className.lastIndexOf('.') + 1);
                        Path src = plugDir.resolve(simple + ".java");
                        if (Files.exists(src)) return new PluginEntry(id, src, className);
                    }
                }
            }
        }
        return null;
    }
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int kIdx = json.indexOf(pattern);
        if (kIdx < 0) return null;
        int colon = json.indexOf(':', kIdx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    // ── WAV writer ──
    private static void writeWav(float[] s, File out) throws IOException {
        AudioFormat fmt = new AudioFormat(SR, 16, 1, true, false);
        byte[] bytes = new byte[s.length * 2];
        // Normalise to peak -3 dBFS so the file is auditioning-loud
        // but not clipping — keeps the wav useful as audio plus
        // mathematically representative (scale only, not shape).
        float peak = 0f;
        for (float v : s) { float a = v < 0 ? -v : v; if (a > peak) peak = a; }
        float scale = peak > 1e-9f ? (0.7f / peak) : 1f;
        for (int i = 0; i < s.length; i++) {
            float v = s[i] * scale;
            if (Float.isNaN(v) || Float.isInfinite(v)) v = 0f;
            if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
            short n = (short) (v * 32767f);
            bytes[2 * i]     = (byte) (n & 0xff);
            bytes[2 * i + 1] = (byte) ((n >> 8) & 0xff);
        }
        AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(bytes), fmt, s.length);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
    }

    // ── Spectrogram PNG (radix-2 STFT, Hann window) ──
    private static void writeSpectrogramPNG(float[] s, File out) throws IOException {
        int fftSize = 1024;
        int hop = 256;
        int maxBin = 380;   // ~16 kHz at 44.1k
        int frames = (s.length - fftSize) / hop + 1;
        BufferedImage spec = new BufferedImage(frames, maxBin, BufferedImage.TYPE_INT_RGB);
        Fft fft = new Fft(fftSize);
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        double[] window = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (fftSize - 1));
        }
        double invN = 1.0 / fftSize;
        for (int f = 0; f < frames; f++) {
            int start = f * hop;
            for (int i = 0; i < fftSize; i++) {
                int idx = start + i;
                re[i] = idx < s.length ? s[idx] * window[i] : 0;
                im[i] = 0;
            }
            fft.transform(re, im);
            for (int b = 0; b < maxBin; b++) {
                double mag = Math.sqrt(re[b] * re[b] + im[b] * im[b]) * invN * 2;
                double dB = 20 * Math.log10(Math.max(1e-9, mag));
                double t = (dB + 90) / 90.0;
                if (t < 0) t = 0; else if (t > 1) t = 1;
                spec.setRGB(f, maxBin - 1 - b, viridis(t));
            }
        }
        BufferedImage scaled = new BufferedImage(1280, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.drawImage(spec, 0, 0, 1280, 480, null);
        g.setColor(new Color(220, 220, 240, 230));
        g.drawString("0 Hz", 6, 474);
        g.drawString("16 kHz", 6, 14);
        g.drawString("0 s", 6, 246);
        g.drawString(String.format(Locale.ROOT, "%.1f s", s.length / (float) SR),
                1230, 246);
        g.dispose();
        ImageIO.write(scaled, "png", out);
    }

    // ── Per-octave decay curve (envelope dB over time, 6 bands) ──
    // Computes the RMS of each band over 50 ms windows and tracks
    // its dB-level. The slope of that curve gives RT60.
    private static float[][] computeBandDecays(float[] s) {
        float[][] band = new float[6][];
        float[] cuts = { 125f, 250f, 500f, 1000f, 2000f, 4000f };
        for (int b = 0; b < 6; b++) {
            float[] filt = octaveBand(s, cuts[b]);
            band[b] = envelopeDb(filt, SR / 20);  // 50 ms windows
        }
        return band;
    }
    // Cheap band-pass approximation — 1st-order HP + LP around fc with
    // an octave of bandwidth. Not surgical, but sufficient to track
    // per-band decay slopes.
    private static float[] octaveBand(float[] s, float fc) {
        float lo = fc / 1.4142f, hi = fc * 1.4142f;
        float hpC = 1f - (float) Math.exp(-1.0 / (SR / (2 * Math.PI * lo)));
        float lpC = 1f - (float) Math.exp(-1.0 / (SR / (2 * Math.PI * hi)));
        float hp = 0f, lp = 0f;
        float[] out = new float[s.length];
        for (int i = 0; i < s.length; i++) {
            hp += hpC * (s[i] - hp);
            float band = s[i] - hp;
            lp += lpC * (band - lp);
            out[i] = lp;
        }
        return out;
    }
    private static float[] envelopeDb(float[] s, int win) {
        int frames = s.length / win;
        float[] env = new float[frames];
        for (int f = 0; f < frames; f++) {
            double sumSq = 0;
            for (int i = 0; i < win; i++) {
                float v = s[f * win + i];
                sumSq += v * v;
            }
            float rms = (float) Math.sqrt(sumSq / win);
            env[f] = (float) (20 * Math.log10(Math.max(1e-9f, rms)));
        }
        return env;
    }

    private static void writeDecayPNG(float[][] band, File out) throws IOException {
        int w = 1280, h = 360;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 20, 25)); g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Grid: dB 0 → -80, time 0 → end
        g.setColor(new Color(50, 50, 60));
        for (int db = 0; db >= -80; db -= 20) {
            int y = (int) (((-db) / 80f) * h);
            g.drawLine(40, y, w, y);
            g.setColor(new Color(180, 180, 200));
            g.drawString(db + " dB", 4, y + 4);
            g.setColor(new Color(50, 50, 60));
        }
        // Per-band traces.
        Color[] colors = {
            new Color(0xE34855), new Color(0xEE8A2C), new Color(0xF5C842),
            new Color(0x4FCB60), new Color(0x4290D8), new Color(0xA060E0),
        };
        String[] labels = { "125", "250", "500", "1k", "2k", "4k" };
        for (int b = 0; b < band.length; b++) {
            g.setColor(colors[b]);
            int frames = band[b].length;
            int prevX = 40, prevY = 0;
            for (int f = 0; f < frames; f++) {
                int x = 40 + (int) ((f / (float) frames) * (w - 50));
                float db = band[b][f];
                if (db < -80f) db = -80f;
                int y = (int) (((-db) / 80f) * h);
                if (f > 0) g.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
            g.drawString(labels[b] + " Hz", w - 80, 24 + b * 16);
        }
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    // ── Stats JSON ──
    private static void writeStatsJson(float[] ir, float[][] bands, Path out) throws IOException {
        float peak = 0f; double sumSq = 0;
        for (float v : ir) {
            if (Float.isNaN(v) || Float.isInfinite(v)) continue;
            float a = v < 0 ? -v : v;
            if (a > peak) peak = a;
            sumSq += v * v;
        }
        float rms = (float) Math.sqrt(sumSq / ir.length);
        // RT60 per band: slope of dB envelope from -5 to -25 → ×3.
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"plugin_ir_seconds\": ").append(ir.length / (float) SR).append(",\n");
        sb.append("  \"peak\": ").append(fmt(peak)).append(",\n");
        sb.append("  \"peak_dBFS\": ").append(fmt(20 * Math.log10(Math.max(1e-9, peak)))).append(",\n");
        sb.append("  \"rms\":  ").append(fmt(rms)).append(",\n");
        sb.append("  \"rms_dBFS\":  ").append(fmt(20 * Math.log10(Math.max(1e-9, rms)))).append(",\n");
        sb.append("  \"rt60_per_band_seconds\": {\n");
        String[] names = { "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz" };
        for (int b = 0; b < bands.length; b++) {
            sb.append("    \"").append(names[b]).append("\": ")
              .append(fmt(rt60(bands[b])))
              .append(b < bands.length - 1 ? "," : "").append("\n");
        }
        sb.append("  }\n}\n");
        Files.writeString(out, sb.toString());
    }
    // Schroeder-style RT60: find dB drops from -5 to -25 (a 20 dB
    // range), multiply by 3 to get the time to drop 60 dB total.
    private static float rt60(float[] env) {
        float peak = -1000f;
        for (float v : env) if (v > peak) peak = v;
        float t5 = -1, t25 = -1;
        for (int i = 0; i < env.length; i++) {
            if (t5  < 0 && env[i] <= peak - 5)  t5  = i / 20f; // 50 ms hops → 20 fps
            if (t25 < 0 && env[i] <= peak - 25) t25 = i / 20f;
        }
        if (t5 < 0 || t25 < 0 || t25 <= t5) return 0f;
        return (t25 - t5) * 3f;
    }
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    // ── viridis colourmap ──
    private static int viridis(double t) {
        double r, g, b;
        if (t < 0.5) {
            double u = t / 0.5;
            r = lerp(0x44, 0x21, u); g = lerp(0x01, 0x91, u); b = lerp(0x54, 0x8c, u);
        } else {
            double u = (t - 0.5) / 0.5;
            r = lerp(0x21, 0xfd, u); g = lerp(0x91, 0xe7, u); b = lerp(0x8c, 0x25, u);
        }
        return (((int) r) << 16) | (((int) g) << 8) | ((int) b);
    }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ── Tiny radix-2 Cooley-Tukey FFT ──
    static class Fft {
        final int n, log2n;
        final double[] cosT, sinT;
        final int[] rev;
        Fft(int n) {
            this.n = n;
            log2n = (int) Math.round(Math.log(n) / Math.log(2));
            cosT = new double[n / 2];
            sinT = new double[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cosT[i] = Math.cos(-2 * Math.PI * i / n);
                sinT[i] = Math.sin(-2 * Math.PI * i / n);
            }
            rev = new int[n];
            for (int i = 0; i < n; i++) {
                int j = 0;
                for (int k = 0; k < log2n; k++) j |= ((i >> k) & 1) << (log2n - 1 - k);
                rev[i] = j;
            }
        }
        void transform(double[] re, double[] im) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (i < j) {
                    double t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int size = 2; size <= n; size *= 2) {
                int half = size / 2;
                int step = n / size;
                for (int i = 0; i < n; i += size) {
                    int idx = 0;
                    for (int j = i; j < i + half; j++) {
                        double c = cosT[idx];
                        double s = sinT[idx];
                        double tre = re[j + half] * c - im[j + half] * s;
                        double tim = re[j + half] * s + im[j + half] * c;
                        re[j + half] = re[j] - tre;
                        im[j + half] = im[j] - tim;
                        re[j] += tre;
                        im[j] += tim;
                        idx += step;
                    }
                }
            }
        }
    }

    private static Path findRepoRoot() throws IOException {
        Path cur = Paths.get("").toAbsolutePath();
        while (cur != null) {
            if (Files.isDirectory(cur.resolve("plugins"))
                && Files.exists(cur.resolve("manifest.json"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IOException("Not inside a vocal-monitor-plugins checkout.");
    }
}
