// Headless CLI test harness for the Vocal Monitor plugin registry.
// Reads tools/test-app/test-input.wav (the "default" the GUI app saves
// via its ⭐ button), runs it through any native plugin, and emits:
//
//   • tools/test-app/test-output.wav        — processed audio
//   • tools/test-app/test-original.png      — waveform of input
//   • tools/test-app/test-processed.png     — waveform of output
//   • tools/test-app/test-spec-original.png — spectrogram of input
//   • tools/test-app/test-spec-processed.png — spectrogram of output
//   • tools/test-app/test-stats.json        — numeric stats + click detection
//
// Run from the repo root:
//
//   java tools/test-app/RunTest.java                       # auto-tune, defaults
//   java tools/test-app/RunTest.java --plugin compressor
//   java tools/test-app/RunTest.java --params "preset=2,key=0,scale=1"
//
// Stats include click / discontinuity detection on the processed signal
// — flags any sample where the residual against a 3rd-order linear
// predictor exceeds 10× the median residual. That's the same algorithm
// the De-clicker uses; if Auto-Tune is producing spikes you'll see
// their indices in the report.

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

public class RunTest {

    private static final int SR = 44100;
    private static final int BLOCK = 1024;

    public static void main(String[] args) throws Exception {
        // ---- CLI parsing ----
        String pluginId = "auto-tune";
        Map<String, Float> overrides = new LinkedHashMap<>();
        String inputPath = "tools/test-app/test-input.wav";
        String outDir = "tools/test-app";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--plugin": pluginId = args[++i]; break;
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
                case "--in":  inputPath = args[++i]; break;
                case "--out": outDir = args[++i]; break;
                default:
                    System.err.println("Unknown flag: " + args[i]);
                    System.err.println("Usage: RunTest --plugin <id> --params k=v,k=v --in input.wav --out dir/");
                    System.exit(2);
            }
        }

        Path repoRoot = findRepoRoot();
        Path inputFile = repoRoot.resolve(inputPath);
        if (!Files.exists(inputFile)) {
            System.err.println("Missing input audio: " + inputFile);
            System.err.println("Open TestApp, load or record some audio, then click");
            System.err.println("the ⭐ \"Save as default test input\" button.");
            System.exit(1);
        }

        Path outBase = repoRoot.resolve(outDir);
        Files.createDirectories(outBase);

        // ---- Locate plugin ----
        PluginEntry entry = findPlugin(repoRoot, pluginId);
        if (entry == null) {
            System.err.println("Unknown plugin id: " + pluginId);
            System.exit(1);
        }

        // ---- Compile plugin ----
        Path stubSrc = repoRoot.resolve(
                "scripts/native-stub/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java");
        Path buildDir = repoRoot.resolve("scripts/native-stub/build/runtest-" + pluginId);
        Files.createDirectories(buildDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("No JavaCompiler — install JDK, not just JRE.");
            System.exit(1);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(
                    stubSrc.toFile(), entry.javaSrc.toFile());
            List<String> options = Arrays.asList(
                    "-d", buildDir.toString(),
                    "--release", "8",
                    "-encoding", "utf-8");
            if (!compiler.getTask(null, fm, diagnostics, options, null, units).call()) {
                for (Diagnostic<?> d : diagnostics.getDiagnostics()) System.err.println(d);
                System.exit(1);
            }
        }
        URLClassLoader loader = new URLClassLoader(
                new URL[] { buildDir.toUri().toURL() },
                Thread.currentThread().getContextClassLoader());
        Class<?> cls = loader.loadClass(entry.className);
        Object plugin = cls.getDeclaredConstructor().newInstance();

        // ---- Init + parameters ----
        cls.getMethod("init", int.class).invoke(plugin, SR);
        String[] paramNames = (String[]) cls.getMethod("parameterNames").invoke(plugin);
        Method pSet = cls.getMethod("setParameter", String.class, float.class);
        Method pDef = cls.getMethod("parameterDefault", String.class);
        System.out.println("Plugin: " + pluginId + "  (" + entry.className + ")");
        for (String name : paramNames) {
            float val;
            if (overrides.containsKey(name)) {
                val = overrides.get(name);
                System.out.printf("  %-12s = %.3f  (override)%n", name, val);
            } else {
                val = (float) pDef.invoke(plugin, name);
                System.out.printf("  %-12s = %.3f%n", name, val);
            }
            pSet.invoke(plugin, name, val);
        }

        // ---- Load input WAV ----
        float[] original;
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(inputFile.toFile())) {
            original = decodeToMono44k(ais);
        }
        System.out.printf("Loaded %d samples (%.2f s) from %s%n",
                original.length, original.length / (float) SR, inputPath);

        // ---- Process ----
        Method process = cls.getMethod("process", float[].class, float[].class);
        float[] processed = new float[original.length];
        float[] in = new float[BLOCK];
        float[] out = new float[BLOCK];
        long t0 = System.nanoTime();
        for (int i = 0; i < original.length; i += BLOCK) {
            int len = Math.min(BLOCK, original.length - i);
            if (len < BLOCK) {
                Arrays.fill(in, 0f);
                System.arraycopy(original, i, in, 0, len);
            } else {
                System.arraycopy(original, i, in, 0, BLOCK);
            }
            process.invoke(plugin, in, out);
            System.arraycopy(out, 0, processed, i, len);
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Processed in %d ms (%.1fx realtime)%n",
                elapsedMs, (original.length / (float) SR) / (elapsedMs / 1000f));

        // ---- Write outputs ----
        writeWav(processed, outBase.resolve("test-output.wav").toFile());
        writeWaveformPNG(original, outBase.resolve("test-original.png").toFile(),
                new Color(120, 220, 120));
        writeWaveformPNG(processed, outBase.resolve("test-processed.png").toFile(),
                new Color(120, 180, 220));
        writeSpectrogramPNG(original, outBase.resolve("test-spec-original.png").toFile());
        writeSpectrogramPNG(processed, outBase.resolve("test-spec-processed.png").toFile());

        // ---- Stats + click detection ----
        Stats origStats = analyse(original);
        Stats procStats = analyse(processed);
        List<ClickReport> origClicks = detectClicks(original);
        List<ClickReport> procClicks = detectClicks(processed);
        // "Added" = clicks in processed that weren't already in the original
        // within a small time window (consonant onsets and similar transients
        // produce spikes in both — those don't count as plugin artifacts).
        List<ClickReport> addedClicks = new ArrayList<>();
        int matchWindow = SR / 50;  // 20 ms
        for (ClickReport c : procClicks) {
            boolean nearOriginal = false;
            for (ClickReport o : origClicks) {
                if (Math.abs(o.idx - c.idx) < matchWindow) { nearOriginal = true; break; }
            }
            if (!nearOriginal) addedClicks.add(c);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"plugin\": \"").append(pluginId).append("\",\n");
        json.append("  \"input\": \"").append(inputPath).append("\",\n");
        json.append("  \"sampleRate\": ").append(SR).append(",\n");
        json.append("  \"samples\": ").append(original.length).append(",\n");
        json.append("  \"durationSec\": ").append(fmt("%.3f", original.length / (float) SR)).append(",\n");
        json.append("  \"processMs\": ").append(elapsedMs).append(",\n");
        json.append("  \"original\": ").append(origStats.toJson()).append(",\n");
        json.append("  \"processed\": ").append(procStats.toJson()).append(",\n");
        // Diff
        float maxDiff = 0;
        double sumSq = 0;
        for (int i = 0; i < original.length; i++) {
            float d = processed[i] - original[i];
            float a = d < 0 ? -d : d;
            if (a > maxDiff) maxDiff = a;
            sumSq += d * d;
        }
        float rmsDiff = (float) Math.sqrt(sumSq / original.length);
        json.append("  \"diff\": { \"maxAbs\": ").append(fmt("%.6f", maxDiff));
        json.append(", \"rms\": ").append(fmt("%.6f", rmsDiff)).append(" },\n");
        json.append("  \"clicksOriginal\": ").append(origClicks.size()).append(",\n");
        json.append("  \"clicksProcessed\": ").append(procClicks.size()).append(",\n");
        json.append("  \"clicksAdded\": ").append(addedClicks.size()).append(",\n");
        json.append("  \"clicks\": [\n");
        int shown = Math.min(addedClicks.size(), 50);
        for (int i = 0; i < shown; i++) {
            ClickReport c = addedClicks.get(i);
            json.append("    {\"sample\":").append(c.idx);
            json.append(",\"sec\":").append(fmt("%.4f", c.idx / (float) SR));
            json.append(",\"residual\":").append(fmt("%.4f", c.residual));
            json.append(",\"ratio\":").append(fmt("%.1f", c.ratio));
            json.append("}").append(i < shown - 1 ? "," : "").append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        Files.writeString(outBase.resolve("test-stats.json"), json.toString());

        // ---- Console summary ----
        System.out.println();
        System.out.println("=== Original ===");
        System.out.println(origStats);
        System.out.println("=== Processed ===");
        System.out.println(procStats);
        System.out.printf(Locale.ROOT, "=== Diff === maxAbs=%.4f  rms=%.4f%n", maxDiff, rmsDiff);
        System.out.printf(Locale.ROOT,
                "=== Clicks: original=%d  processed=%d  ADDED=%d ===%n",
                origClicks.size(), procClicks.size(), addedClicks.size());
        for (int i = 0; i < Math.min(addedClicks.size(), 10); i++) {
            ClickReport c = addedClicks.get(i);
            System.out.printf(Locale.ROOT,
                    "  sample %d (%.4fs)  residual %.3f  (%.1fx normal)%n",
                    c.idx, c.idx / (float) SR, c.residual, c.ratio);
        }

        // ---- Dump a window of samples around added clicks ----
        // Lets you compare original vs processed at the suspected glitch
        // sites. If the original is silent or near-silent and the processed
        // jumps abruptly, that's a real plugin-side discontinuity. If both
        // are jittery, the spike likely originated in the source signal.
        // Dump a wider window around the last click to see what input
        // amplitude looks like in the lead-up.
        if (!addedClicks.isEmpty()) {
            ClickReport last = addedClicks.get(addedClicks.size() - 1);
            System.out.println();
            System.out.printf(Locale.ROOT, "=== Wide window around last click (sample %d, %.4fs) ===%n",
                    last.idx, last.idx / (float) SR);
            int from = Math.max(0, last.idx - 3000);
            int to = Math.min(original.length, last.idx + 500);
            int step = (to - from) / 40;
            if (step < 1) step = 1;
            System.out.println("    idx       |orig|       |proc|");
            for (int i = from; i < to; i += step) {
                float o = original[i]; if (o < 0) o = -o;
                float p = processed[i]; if (p < 0) p = -p;
                System.out.printf(Locale.ROOT, "    %-9d %.6f   %.6f%n", i, o, p);
            }
        }

        if (!addedClicks.isEmpty()) {
            int dumpHalfWin = 8;
            System.out.println();
            System.out.println("=== Sample windows around added clicks ===");
            for (int k = 0; k < Math.min(addedClicks.size(), 10); k++) {
                ClickReport c = addedClicks.get(k);
                System.out.printf(Locale.ROOT, "  -- click %d @ sample %d (%.4fs) --%n",
                        k + 1, c.idx, c.idx / (float) SR);
                int from = Math.max(0, c.idx - dumpHalfWin);
                int to = Math.min(original.length, c.idx + dumpHalfWin + 1);
                System.out.println("    idx     orig         proc         diff");
                for (int i = from; i < to; i++) {
                    String mark = (i == c.idx) ? " <-- click" : "";
                    System.out.printf(Locale.ROOT,
                            "    %-7d %+.6f   %+.6f   %+.6f%s%n",
                            i, original[i], processed[i],
                            processed[i] - original[i], mark);
                }
            }
        }

        System.out.println();
        System.out.println("Outputs written to " + outBase);
    }

    private static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }

    // ---------------------------------------------------------------
    //  Plugin discovery
    // ---------------------------------------------------------------
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

    // ---------------------------------------------------------------
    //  Audio I/O
    // ---------------------------------------------------------------
    private static float[] decodeToMono44k(AudioInputStream in) throws Exception {
        AudioFormat src = in.getFormat();
        AudioFormat mono = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                src.getSampleRate(), 16, 1, 2, src.getSampleRate(), false);
        AudioInputStream monoStream = AudioSystem.getAudioInputStream(mono, in);
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                SR, 16, 1, 2, SR, false);
        AudioInputStream resampled = AudioSystem.getAudioInputStream(target, monoStream);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = resampled.read(buf)) > 0) baos.write(buf, 0, n);
        byte[] bytes = baos.toByteArray();
        float[] out = new float[bytes.length / 2];
        for (int i = 0; i < out.length; i++) {
            int lo = bytes[2 * i] & 0xff;
            int hi = bytes[2 * i + 1];
            short s = (short) ((hi << 8) | lo);
            out[i] = s / 32768f;
        }
        return out;
    }
    private static void writeWav(float[] samples, File out) throws IOException {
        AudioFormat fmt = new AudioFormat(SR, 16, 1, true, false);
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            if (Float.isNaN(s) || Float.isInfinite(s)) s = 0f;
            if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
            short v = (short) (s * 32767f);
            bytes[2 * i] = (byte) (v & 0xff);
            bytes[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(bytes), fmt, samples.length);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
    }

    // ---------------------------------------------------------------
    //  Visualisation outputs
    // ---------------------------------------------------------------
    private static void writeWaveformPNG(float[] samples, File out, Color colour) throws IOException {
        int w = 1280, h = 240;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 20, 24)); g.fillRect(0, 0, w, h);
        g.setColor(new Color(60, 60, 70)); g.drawLine(0, h / 2, w, h / 2);
        float peak = 0.001f;
        for (float v : samples) { float a = v < 0 ? -v : v; if (a > peak) peak = a; }
        int spp = Math.max(1, samples.length / w);
        g.setColor(colour);
        for (int x = 0; x < w; x++) {
            int start = x * spp;
            int end = Math.min(samples.length, start + spp);
            float lo = 0, hi = 0;
            for (int i = start; i < end; i++) {
                float v = samples[i];
                if (Float.isNaN(v) || Float.isInfinite(v)) continue;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            int yHi = (int) ((1 - hi / peak) * h / 2);
            int yLo = (int) ((1 - lo / peak) * h / 2);
            g.drawLine(x, yHi, x, yLo);
        }
        g.setColor(new Color(180, 180, 200));
        g.drawString(String.format("peak %.3f", peak), 6, 14);
        g.dispose();
        ImageIO.write(img, "png", out);
    }
    private static void writeSpectrogramPNG(float[] samples, File out) throws IOException {
        if (samples.length < 1024) return;
        int fftSize = 1024;
        int hop = 256;
        int maxBin = 280;  // ~12 kHz at 44.1k
        int frames = (samples.length - fftSize) / hop + 1;
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
                re[i] = idx < samples.length ? samples[idx] * window[i] : 0;
                im[i] = 0;
            }
            fft.transform(re, im);
            for (int b = 0; b < maxBin; b++) {
                double mag = Math.sqrt(re[b] * re[b] + im[b] * im[b]) * invN * 2;
                double dB = 20 * Math.log10(Math.max(1e-9, mag));
                double t = (dB + 80) / 80.0;
                if (t < 0) t = 0; else if (t > 1) t = 1;
                spec.setRGB(f, maxBin - 1 - b, viridis(t));
            }
        }
        // Scale up to 1280x320 for clarity.
        BufferedImage scaled = new BufferedImage(1280, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.drawImage(spec, 0, 0, 1280, 320, null);
        g.setColor(new Color(200, 200, 220, 200));
        g.drawString("0 Hz", 6, 314);
        g.drawString("12 kHz", 6, 14);
        g.dispose();
        ImageIO.write(scaled, "png", out);
    }
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

    // ---------------------------------------------------------------
    //  Stats + click detection
    // ---------------------------------------------------------------
    static class Stats {
        int n;
        float peak, rms, dc;
        int nan, inf, clipped, nearSilent;
        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "Samples %d  Peak %.4f (%+.1f dBFS)  RMS %.4f (%+.1f dBFS)%n"
                  + "DC %+.5f  near-silent %d  clipped %d  NaN %d  Inf %d",
                  n, peak, dB(peak), rms, dB(rms),
                  dc, nearSilent, clipped, nan, inf);
        }
        String toJson() {
            return String.format(Locale.ROOT,
                "{\"samples\":%d,\"peak\":%.6f,\"peakDb\":%.2f,\"rms\":%.6f,\"rmsDb\":%.2f," +
                "\"dc\":%.6f,\"nearSilent\":%d,\"clipped\":%d,\"nan\":%d,\"inf\":%d}",
                n, peak, dB(peak), rms, dB(rms), dc, nearSilent, clipped, nan, inf);
        }
        static double dB(float a) { return 20 * Math.log10(Math.max(1e-9, a)); }
    }
    private static Stats analyse(float[] s) {
        Stats st = new Stats();
        st.n = s.length;
        double sumSq = 0, sum = 0;
        for (float v : s) {
            if (Float.isNaN(v)) { st.nan++; continue; }
            if (Float.isInfinite(v)) { st.inf++; continue; }
            float a = v < 0 ? -v : v;
            if (a > st.peak) st.peak = a;
            if (a >= 0.99f) st.clipped++;
            if (a < 1e-5f) st.nearSilent++;
            sumSq += v * v; sum += v;
        }
        int valid = st.n - st.nan - st.inf;
        st.rms = valid > 0 ? (float) Math.sqrt(sumSq / valid) : 0;
        st.dc  = valid > 0 ? (float) (sum / valid) : 0;
        return st;
    }
    static class ClickReport {
        final int idx; final float residual; final float ratio;
        ClickReport(int idx, float residual, float ratio) {
            this.idx = idx; this.residual = residual; this.ratio = ratio;
        }
    }
    // Flag samples whose 3rd-order linear-predictor residual exceeds
    // 10× the running median residual. That's the click-detector core
    // from the De-clicker plugin, just inverted: instead of repairing
    // we just report.
    private static List<ClickReport> detectClicks(float[] s) {
        List<ClickReport> out = new ArrayList<>();
        if (s.length < 4) return out;
        float p1 = 0, p2 = 0, p3 = 0;
        // Pre-pass to compute the median absolute residual.
        float[] absResiduals = new float[s.length];
        for (int i = 0; i < s.length; i++) {
            float pred = 3f * p1 - 3f * p2 + p3;
            float r = s[i] - pred;
            absResiduals[i] = r < 0 ? -r : r;
            p3 = p2; p2 = p1; p1 = s[i];
        }
        float[] sorted = absResiduals.clone();
        Arrays.sort(sorted);
        float median = sorted[sorted.length / 2];
        if (median < 1e-6f) median = 1e-6f;
        // Report outliers.
        float threshold = median * 10f;
        for (int i = 0; i < s.length; i++) {
            if (absResiduals[i] > threshold && absResiduals[i] > 0.005f) {
                out.add(new ClickReport(i, absResiduals[i], absResiduals[i] / median));
            }
        }
        // Collapse clusters: keep only the loudest click per ~5 ms window.
        List<ClickReport> filtered = new ArrayList<>();
        int minGap = SR / 200;  // 5 ms
        for (ClickReport c : out) {
            if (!filtered.isEmpty()) {
                ClickReport last = filtered.get(filtered.size() - 1);
                if (c.idx - last.idx < minGap) {
                    if (c.residual > last.residual) filtered.set(filtered.size() - 1, c);
                    continue;
                }
            }
            filtered.add(c);
        }
        return filtered;
    }

    // ---------------------------------------------------------------
    //  Tiny radix-2 Cooley-Tukey FFT (same as in TestApp).
    // ---------------------------------------------------------------
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
