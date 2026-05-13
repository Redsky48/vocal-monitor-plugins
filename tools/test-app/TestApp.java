// Desktop test harness for the Vocal Monitor plugin registry.
//
// Compiles the selected plugin's .java source on the fly via the
// in-process JavaCompiler API, loads it through a URLClassLoader, and
// drives it from a small Swing GUI. Lets you load a WAV file or record
// from the default microphone, dial the plugin's parameters, hear the
// original vs the processed audio back-to-back, eyeball the
// waveform / spectrogram / numerical stats of each, and optionally
// export the processed result to a new WAV.
//
// Run from the repo root with JDK 11+:
//
//   java tools/test-app/TestApp.java
//
// (JEP 330 single-file source-code launch — no separate compile step.)

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.sound.sampled.*;
import javax.tools.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestApp extends JFrame {

    private static final int SR = 44100;
    private static final int BLOCK = 1024;

    private final Path repoRoot;
    private final Path stubSrc;

    private final JComboBox<PluginEntry> pluginCombo = new JComboBox<>();
    private final JPanel sliderPanel = new JPanel();
    private final JLabel statusLabel = new JLabel("Load a WAV or record from the mic to begin.");
    private final JButton loadBtn  = new JButton("Load WAV…");
    private final JButton micBtn   = new JButton("Record 5 s from mic");
    private final JButton playOrig = new JButton("Play original");
    private final JButton process  = new JButton("Process");
    private final JButton playProc = new JButton("Play processed");
    private final JButton stopBtn  = new JButton("Stop");
    private final JButton saveBtn  = new JButton("Save processed WAV…");

    // Visualisation panels.
    private final WaveformPanel waveOrig = new WaveformPanel(new Color(120, 220, 120));
    private final WaveformPanel waveProc = new WaveformPanel(new Color(120, 180, 220));
    private final SpectrogramPanel specOrig = new SpectrogramPanel();
    private final SpectrogramPanel specProc = new SpectrogramPanel();
    private final JTextArea statsArea = new JTextArea();

    private float[] originalAudio;
    private float[] processedAudio;
    private Object  plugin;
    private String[] paramNames;
    private final Map<String, JSlider> sliders = new LinkedHashMap<>();
    private final Map<String, JLabel> sliderLabels = new LinkedHashMap<>();

    private SourceDataLine playLine;
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private Thread playThread;

    public TestApp() throws Exception {
        super("Vocal Monitor Plugin Test App");
        repoRoot = findRepoRoot();
        stubSrc = repoRoot.resolve("scripts/native-stub/com/vocalmonitor/plugin/VocalMonitorNativePlugin.java");
        if (!Files.exists(stubSrc)) {
            throw new IOException("Cannot find " + stubSrc + " — run from the repo root.");
        }
        scanPlugins();
        buildUI();
        if (pluginCombo.getItemCount() > 0) {
            selectPlugin((PluginEntry) pluginCombo.getSelectedItem());
        }
    }

    // ---------------------------------------------------------------
    //  Plugin discovery + compile
    // ---------------------------------------------------------------
    private static class PluginEntry {
        final String id, name, className;
        final Path folder, javaSrc;
        PluginEntry(String id, String name, Path folder, Path javaSrc, String className) {
            this.id = id; this.name = name; this.folder = folder;
            this.javaSrc = javaSrc; this.className = className;
        }
        @Override public String toString() { return name + "  (" + id + ")"; }
    }

    private void scanPlugins() throws IOException {
        Path pluginsDir = repoRoot.resolve("plugins");
        List<PluginEntry> entries = new ArrayList<>();
        try (var cats = Files.newDirectoryStream(pluginsDir, Files::isDirectory)) {
            for (Path catDir : cats) {
                try (var plugDirs = Files.newDirectoryStream(catDir, Files::isDirectory)) {
                    for (Path plugDir : plugDirs) {
                        Path metaPath = plugDir.resolve("plugin.json");
                        if (!Files.exists(metaPath)) continue;
                        String meta = Files.readString(metaPath);
                        if (!meta.contains("\"engine\"") || !meta.contains("\"native\"")) continue;
                        String id = extractJsonString(meta, "id");
                        String name = extractJsonString(meta, "name");
                        String className = extractJsonString(meta, "className");
                        if (id == null || className == null) continue;
                        String simple = className.substring(className.lastIndexOf('.') + 1);
                        Path javaSrc = plugDir.resolve(simple + ".java");
                        if (!Files.exists(javaSrc)) continue;
                        entries.add(new PluginEntry(id, name != null ? name : id, plugDir, javaSrc, className));
                    }
                }
            }
        }
        entries.sort(Comparator.comparing(e -> e.name));
        for (PluginEntry e : entries) pluginCombo.addItem(e);
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

    private Object loadPlugin(PluginEntry entry) throws Exception {
        Path buildDir = repoRoot.resolve("scripts/native-stub/build/testapp-" + entry.id);
        Files.createDirectories(buildDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("No JavaCompiler available — need JDK, not just JRE.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(
                    stubSrc.toFile(), entry.javaSrc.toFile());
            List<String> options = Arrays.asList(
                    "-d", buildDir.toString(),
                    "--release", "8",
                    "-encoding", "utf-8");
            boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            if (!ok) {
                StringBuilder err = new StringBuilder("Compile failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    err.append(d).append('\n');
                }
                throw new RuntimeException(err.toString());
            }
        }
        URLClassLoader loader = new URLClassLoader(
                new URL[] { buildDir.toUri().toURL() },
                Thread.currentThread().getContextClassLoader());
        Class<?> cls = loader.loadClass(entry.className);
        return cls.getDeclaredConstructor().newInstance();
    }

    // ---------------------------------------------------------------
    //  UI
    // ---------------------------------------------------------------
    private void buildUI() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 760);

        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(new EmptyBorder(10, 10, 10, 10));

        // --- North: plugin selector + top buttons ---
        JPanel north = new JPanel(new GridLayout(2, 1, 4, 4));
        JPanel pluginRow = new JPanel(new BorderLayout(6, 0));
        pluginRow.add(new JLabel("Plugin:"), BorderLayout.WEST);
        pluginRow.add(pluginCombo, BorderLayout.CENTER);
        pluginCombo.addActionListener(e -> {
            PluginEntry sel = (PluginEntry) pluginCombo.getSelectedItem();
            if (sel != null) try { selectPlugin(sel); } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(),
                        "Plugin load error", JOptionPane.ERROR_MESSAGE);
            }
        });
        north.add(pluginRow);

        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        topButtons.add(loadBtn); topButtons.add(micBtn);
        north.add(topButtons);
        main.add(north, BorderLayout.NORTH);

        // --- West: parameter sliders (in a scroll pane) ---
        sliderPanel.setLayout(new BoxLayout(sliderPanel, BoxLayout.Y_AXIS));
        sliderPanel.setBorder(BorderFactory.createTitledBorder("Parameters"));
        JScrollPane sliderScroll = new JScrollPane(sliderPanel);
        sliderScroll.setPreferredSize(new Dimension(380, 0));

        // --- Center: tabbed visualisations ---
        JTabbedPane tabs = new JTabbedPane();
        // Waveform tab: stacked original + processed.
        JPanel wavePane = new JPanel(new GridLayout(2, 1, 6, 6));
        wavePane.add(framed("Original — waveform", waveOrig));
        wavePane.add(framed("Processed — waveform", waveProc));
        tabs.addTab("Waveform", wavePane);
        // Spectrogram tab: stacked original + processed.
        JPanel specPane = new JPanel(new GridLayout(2, 1, 6, 6));
        specPane.add(framed("Original — spectrogram (0-12 kHz, log mag, -80 dB floor)", specOrig));
        specPane.add(framed("Processed — spectrogram", specProc));
        tabs.addTab("Spectrogram", specPane);
        // Stats tab.
        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        statsArea.setText("(load audio + Process to see stats)");
        tabs.addTab("Stats", new JScrollPane(statsArea));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sliderScroll, tabs);
        split.setDividerLocation(380);
        split.setResizeWeight(0.0);
        main.add(split, BorderLayout.CENTER);

        // --- South: action buttons + status ---
        JPanel south = new JPanel(new BorderLayout());
        JPanel ctlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ctlRow.add(playOrig); ctlRow.add(process); ctlRow.add(playProc);
        ctlRow.add(stopBtn); ctlRow.add(saveBtn);
        south.add(ctlRow, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.SOUTH);
        main.add(south, BorderLayout.SOUTH);

        loadBtn.addActionListener(e -> loadWav());
        micBtn.addActionListener(e -> recordMic());
        playOrig.addActionListener(e -> playAudio(originalAudio));
        process.addActionListener(e -> processAudio());
        playProc.addActionListener(e -> playAudio(processedAudio));
        stopBtn.addActionListener(e -> stopPlayback());
        saveBtn.addActionListener(e -> saveProcessed());

        playOrig.setEnabled(false);
        process.setEnabled(false);
        playProc.setEnabled(false);
        saveBtn.setEnabled(false);

        setContentPane(main);
        setLocationRelativeTo(null);
    }

    private static JComponent framed(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private void selectPlugin(PluginEntry entry) throws Exception {
        setStatus("Compiling " + entry.id + "…");
        Object loaded = loadPlugin(entry);
        Method init = loaded.getClass().getMethod("init", int.class);
        init.invoke(loaded, SR);
        plugin = loaded;
        paramNames = (String[]) loaded.getClass().getMethod("parameterNames").invoke(loaded);
        rebuildSliders();
        setStatus("Loaded " + entry.id + " (" + paramNames.length + " parameters).");
    }

    private void rebuildSliders() throws Exception {
        sliderPanel.removeAll();
        sliders.clear();
        sliderLabels.clear();
        if (plugin == null) return;
        Method pMin = plugin.getClass().getMethod("parameterMin", String.class);
        Method pMax = plugin.getClass().getMethod("parameterMax", String.class);
        Method pDef = plugin.getClass().getMethod("parameterDefault", String.class);
        Method pLab = plugin.getClass().getMethod("parameterLabel", String.class);
        Method pSet = plugin.getClass().getMethod("setParameter", String.class, float.class);
        for (String name : paramNames) {
            float min = (float) pMin.invoke(plugin, name);
            float max = (float) pMax.invoke(plugin, name);
            float def = (float) pDef.invoke(plugin, name);
            String label = (String) pLab.invoke(plugin, name);

            JPanel row = new JPanel(new BorderLayout(6, 0));
            JLabel valueLabel = new JLabel();
            JLabel nameLabel = new JLabel(label + "  ");
            nameLabel.setPreferredSize(new Dimension(140, 22));
            row.add(nameLabel, BorderLayout.WEST);
            int slMax = 1000;
            int slVal = Math.round((def - min) / (max - min) * slMax);
            JSlider slider = new JSlider(0, slMax, slVal);
            slider.addChangeListener(ev -> {
                float v = min + (max - min) * slider.getValue() / (float) slMax;
                valueLabel.setText(String.format("%.3f", v));
                try { pSet.invoke(plugin, name, v); } catch (Exception ignored) {}
            });
            valueLabel.setText(String.format("%.3f", def));
            valueLabel.setPreferredSize(new Dimension(70, 22));
            row.add(slider, BorderLayout.CENTER);
            row.add(valueLabel, BorderLayout.EAST);
            sliderPanel.add(row);
            sliders.put(name, slider);
            sliderLabels.put(name, valueLabel);
            pSet.invoke(plugin, name, def);
        }
        sliderPanel.revalidate();
        sliderPanel.repaint();
    }

    // ---------------------------------------------------------------
    //  Audio I/O
    // ---------------------------------------------------------------
    private void loadWav() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("WAV files", "wav"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (var ais = AudioSystem.getAudioInputStream(fc.getSelectedFile())) {
            originalAudio = decodeToMono44k(ais);
            processedAudio = null;
            playOrig.setEnabled(true);
            process.setEnabled(true);
            playProc.setEnabled(false);
            saveBtn.setEnabled(false);
            waveOrig.setSamples(originalAudio);
            specOrig.setSamples(originalAudio, SR);
            waveProc.setSamples(null);
            specProc.setSamples(null, SR);
            refreshStats();
            setStatus(String.format("Loaded %.2f s of audio (%d samples).",
                    originalAudio.length / (float) SR, originalAudio.length));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Load failed", JOptionPane.ERROR_MESSAGE);
        }
    }

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

    private void recordMic() {
        AudioFormat fmt = new AudioFormat(SR, 16, 1, true, false);
        try {
            TargetDataLine line = AudioSystem.getTargetDataLine(fmt);
            line.open(fmt);
            line.start();
            setStatus("Recording 5 s — speak / sing into the mic…");
            new Thread(() -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                long endMs = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < endMs) {
                    int n = line.read(buf, 0, buf.length);
                    if (n > 0) out.write(buf, 0, n);
                }
                line.stop(); line.close();
                byte[] bytes = out.toByteArray();
                float[] samples = new float[bytes.length / 2];
                for (int i = 0; i < samples.length; i++) {
                    int lo = bytes[2 * i] & 0xff;
                    int hi = bytes[2 * i + 1];
                    short s = (short) ((hi << 8) | lo);
                    samples[i] = s / 32768f;
                }
                originalAudio = samples;
                processedAudio = null;
                SwingUtilities.invokeLater(() -> {
                    playOrig.setEnabled(true);
                    process.setEnabled(true);
                    playProc.setEnabled(false);
                    saveBtn.setEnabled(false);
                    waveOrig.setSamples(originalAudio);
                    specOrig.setSamples(originalAudio, SR);
                    waveProc.setSamples(null);
                    specProc.setSamples(null, SR);
                    refreshStats();
                    setStatus(String.format("Recorded %.2f s.", samples.length / (float) SR));
                });
            }, "mic-recorder").start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Mic error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void processAudio() {
        if (originalAudio == null || plugin == null) return;
        try {
            plugin.getClass().getMethod("init", int.class).invoke(plugin, SR);
            Method pSet = plugin.getClass().getMethod("setParameter", String.class, float.class);
            Method pMin = plugin.getClass().getMethod("parameterMin", String.class);
            Method pMax = plugin.getClass().getMethod("parameterMax", String.class);
            for (Map.Entry<String, JSlider> en : sliders.entrySet()) {
                String name = en.getKey();
                JSlider s = en.getValue();
                float min = (float) pMin.invoke(plugin, name);
                float max = (float) pMax.invoke(plugin, name);
                float v = min + (max - min) * s.getValue() / 1000f;
                pSet.invoke(plugin, name, v);
            }
            Method process = plugin.getClass().getMethod("process", float[].class, float[].class);
            float[] in = new float[BLOCK];
            float[] out = new float[BLOCK];
            float[] result = new float[originalAudio.length];
            int n = originalAudio.length;
            for (int i = 0; i < n; i += BLOCK) {
                int len = Math.min(BLOCK, n - i);
                if (len < BLOCK) {
                    java.util.Arrays.fill(in, 0f);
                    System.arraycopy(originalAudio, i, in, 0, len);
                } else {
                    System.arraycopy(originalAudio, i, in, 0, BLOCK);
                }
                process.invoke(plugin, in, out);
                System.arraycopy(out, 0, result, i, len);
            }
            processedAudio = result;
            playProc.setEnabled(true);
            saveBtn.setEnabled(true);
            waveProc.setSamples(processedAudio);
            specProc.setSamples(processedAudio, SR);
            refreshStats();
            setStatus("Processed.");
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Process error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void playAudio(float[] samples) {
        if (samples == null) return;
        stopPlayback();
        playing.set(true);
        playThread = new Thread(() -> {
            AudioFormat fmt = new AudioFormat(SR, 16, 1, true, false);
            try {
                playLine = AudioSystem.getSourceDataLine(fmt);
                playLine.open(fmt, 4096);
                playLine.start();
                byte[] buf = new byte[BLOCK * 2];
                for (int i = 0; i < samples.length && playing.get(); i += BLOCK) {
                    int len = Math.min(BLOCK, samples.length - i);
                    for (int j = 0; j < len; j++) {
                        float s = samples[i + j];
                        if (Float.isNaN(s) || Float.isInfinite(s)) s = 0f;
                        if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
                        short v = (short) (s * 32767f);
                        buf[2 * j] = (byte) (v & 0xff);
                        buf[2 * j + 1] = (byte) ((v >> 8) & 0xff);
                    }
                    playLine.write(buf, 0, len * 2);
                }
                playLine.drain();
                playLine.stop(); playLine.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                playing.set(false);
                playLine = null;
            }
        }, "audio-playback");
        playThread.start();
    }

    private void stopPlayback() {
        playing.set(false);
        if (playLine != null) try { playLine.stop(); playLine.flush(); } catch (Exception ignored) {}
        if (playThread != null) try { playThread.join(200); } catch (InterruptedException ignored) {}
    }

    private void saveProcessed() {
        if (processedAudio == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("processed.wav"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        try {
            AudioFormat fmt = new AudioFormat(SR, 16, 1, true, false);
            byte[] bytes = new byte[processedAudio.length * 2];
            for (int i = 0; i < processedAudio.length; i++) {
                float s = processedAudio[i];
                if (Float.isNaN(s) || Float.isInfinite(s)) s = 0f;
                if (s > 1f) s = 1f; else if (s < -1f) s = -1f;
                short v = (short) (s * 32767f);
                bytes[2 * i] = (byte) (v & 0xff);
                bytes[2 * i + 1] = (byte) ((v >> 8) & 0xff);
            }
            AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(bytes), fmt, processedAudio.length);
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
            setStatus("Saved to " + out.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setStatus(String msg) { statusLabel.setText(msg); }

    // ---------------------------------------------------------------
    //  Stats
    // ---------------------------------------------------------------
    private void refreshStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ORIGINAL ===\n").append(statsFor(originalAudio)).append('\n');
        sb.append("=== PROCESSED ===\n").append(statsFor(processedAudio)).append('\n');
        if (originalAudio != null && processedAudio != null
                && originalAudio.length == processedAudio.length) {
            sb.append("=== DIFFERENCE (processed − original) ===\n");
            float maxDiff = 0f;
            double sumSq = 0;
            int n = originalAudio.length;
            for (int i = 0; i < n; i++) {
                float d = processedAudio[i] - originalAudio[i];
                float a = d < 0 ? -d : d;
                if (a > maxDiff) maxDiff = a;
                sumSq += d * d;
            }
            float rmsDiff = (float) Math.sqrt(sumSq / n);
            sb.append(String.format("Max abs diff : %.4f  (%+.1f dB)%n", maxDiff,
                    20 * Math.log10(Math.max(1e-9, maxDiff))));
            sb.append(String.format("RMS diff     : %.4f  (%+.1f dB)%n", rmsDiff,
                    20 * Math.log10(Math.max(1e-9, rmsDiff))));
        }
        statsArea.setText(sb.toString());
        statsArea.setCaretPosition(0);
    }

    private static String statsFor(float[] s) {
        if (s == null) return "(no audio)\n";
        int n = s.length;
        float peak = 0f;
        double sumSq = 0;
        double sum = 0;
        int nan = 0, inf = 0, clip = 0, zeros = 0;
        for (int i = 0; i < n; i++) {
            float v = s[i];
            if (Float.isNaN(v)) { nan++; continue; }
            if (Float.isInfinite(v)) { inf++; continue; }
            float a = v < 0 ? -v : v;
            if (a > peak) peak = a;
            if (a >= 0.99f) clip++;
            if (a < 1e-5f) zeros++;
            sumSq += v * v;
            sum += v;
        }
        int valid = n - nan - inf;
        float rms = valid > 0 ? (float) Math.sqrt(sumSq / valid) : 0;
        float dc  = valid > 0 ? (float) (sum / valid) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Samples      : %d%n", n));
        sb.append(String.format("Duration     : %.3f s%n", n / (double) SR));
        sb.append(String.format("Peak         : %.4f  (%+.1f dBFS)%n", peak,
                20 * Math.log10(Math.max(1e-9, peak))));
        sb.append(String.format("RMS          : %.4f  (%+.1f dBFS)%n", rms,
                20 * Math.log10(Math.max(1e-9, rms))));
        sb.append(String.format("DC bias      : %+.5f%n", dc));
        sb.append(String.format("Near-silent  : %d samples (%.1f%%)%n", zeros, 100.0 * zeros / n));
        sb.append(String.format("Clipped (≥0.99): %d samples%n", clip));
        sb.append(String.format("NaN          : %d  %s%n", nan, nan > 0 ? "  ← BAD" : ""));
        sb.append(String.format("Inf          : %d  %s%n", inf, inf > 0 ? "  ← BAD" : ""));
        return sb.toString();
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new TestApp().setVisible(true);
            } catch (Throwable t) {
                t.printStackTrace();
                JOptionPane.showMessageDialog(null, t.getMessage(),
                        "Startup error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // ===============================================================
    //  Visualisation primitives — waveform / spectrogram / FFT
    // ===============================================================

    /** Min/max-per-pixel waveform renderer. */
    static class WaveformPanel extends JPanel {
        private float[] samples;
        private final Color colour;
        WaveformPanel(Color colour) {
            this.colour = colour;
            setBackground(new Color(20, 20, 24));
            setPreferredSize(new Dimension(800, 140));
        }
        void setSamples(float[] s) { this.samples = s; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(60, 60, 70));
            g.drawLine(0, h / 2, w, h / 2);
            if (samples == null || samples.length == 0) {
                g.setColor(Color.GRAY);
                g.drawString("(no audio)", 8, 16);
                return;
            }
            // Find peak for vertical scale.
            float peak = 0.001f;
            for (float v : samples) { float a = v < 0 ? -v : v; if (a > peak) peak = a; }
            // Plot min/max per column.
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
            // Peak text.
            g.setColor(new Color(180, 180, 200));
            g.drawString(String.format("peak %.3f", peak), 6, 14);
        }
    }

    /** Time-x-Frequency spectrogram via overlapping Hann-windowed FFTs. */
    static class SpectrogramPanel extends JPanel {
        private BufferedImage image;
        private static final int FFT_SIZE = 1024;
        private static final int HOP = 256;
        private static final int MAX_BIN = 280;  // ~12 kHz at 44.1k
        SpectrogramPanel() {
            setBackground(new Color(10, 10, 14));
            setPreferredSize(new Dimension(800, 200));
        }
        void setSamples(float[] s, int sr) {
            if (s == null || s.length < FFT_SIZE) { image = null; repaint(); return; }
            int frames = (s.length - FFT_SIZE) / HOP + 1;
            int bins = MAX_BIN;
            BufferedImage img = new BufferedImage(frames, bins, BufferedImage.TYPE_INT_RGB);
            Fft fft = new Fft(FFT_SIZE);
            double[] re = new double[FFT_SIZE];
            double[] im = new double[FFT_SIZE];
            double[] window = new double[FFT_SIZE];
            for (int i = 0; i < FFT_SIZE; i++) {
                window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
            }
            double invN = 1.0 / FFT_SIZE;
            for (int f = 0; f < frames; f++) {
                int start = f * HOP;
                for (int i = 0; i < FFT_SIZE; i++) {
                    int idx = start + i;
                    re[i] = idx < s.length ? s[idx] * window[i] : 0;
                    im[i] = 0;
                }
                fft.transform(re, im);
                for (int b = 0; b < bins; b++) {
                    double mag = Math.sqrt(re[b] * re[b] + im[b] * im[b]) * invN * 2;
                    double dB = 20 * Math.log10(Math.max(1e-9, mag));
                    double t = (dB + 80) / 80.0;
                    if (t < 0) t = 0; else if (t > 1) t = 1;
                    img.setRGB(f, bins - 1 - b, viridis(t));
                }
            }
            image = img;
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight();
            if (image == null) {
                g.setColor(Color.GRAY);
                g.drawString("(no audio)", 8, 16);
                return;
            }
            ((Graphics2D) g).drawImage(image, 0, 0, w, h, null);
            // Axis hints.
            g.setColor(new Color(200, 200, 220, 160));
            g.drawString("0 Hz", 4, h - 4);
            g.drawString("12 kHz", 4, 14);
        }
        // Viridis-ish colormap: dark purple → green → yellow.
        private static int viridis(double t) {
            // Two-stop palette: 0=#440154, 0.5=#21918c, 1=#fde725
            double r, g, b;
            if (t < 0.5) {
                double u = t / 0.5;
                r = lerp(0x44, 0x21, u);
                g = lerp(0x01, 0x91, u);
                b = lerp(0x54, 0x8c, u);
            } else {
                double u = (t - 0.5) / 0.5;
                r = lerp(0x21, 0xfd, u);
                g = lerp(0x91, 0xe7, u);
                b = lerp(0x8c, 0x25, u);
            }
            return (((int) r) << 16) | (((int) g) << 8) | ((int) b);
        }
        private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    }

    /** Tiny radix-2 Cooley-Tukey FFT with pre-computed twiddle + bit-reverse tables. */
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
}
