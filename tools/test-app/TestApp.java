// Desktop test harness for the Vocal Monitor plugin registry.
//
// Compiles the selected plugin's .java source on the fly via the
// in-process JavaCompiler API, loads it through a URLClassLoader, and
// drives it from a small Swing GUI. Lets you load a WAV file or record
// from the default microphone, dial the plugin's parameters, hear the
// original vs the processed audio back-to-back, and optionally export
// the result to a new WAV.
//
// Run from the repo root with JDK 11+:
//
//   java tools/test-app/TestApp.java
//
// (JEP 330 single-file source-code launch — no separate compile step.)
//
// The app scans plugins/ for any folder whose plugin.json declares
// engine == "native" and adds it to the plugin dropdown. Switching
// plugin recompiles the chosen .java source and rebuilds the slider
// row from its parameterNames / parameterMin/Max/Default.

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.sound.sampled.*;
import javax.tools.*;
import java.awt.*;
import java.awt.event.*;
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
            throw new IOException("Cannot find " + stubSrc + " — run this from the repo root.");
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
        final String id;
        final String name;
        final Path folder;
        final Path javaSrc;
        final String className;
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
                        // Find the .java file: same folder, name = last segment of className.
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
        // Cheap regex-ish extract — fine for the simple plugin.json schemas.
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
        setSize(620, 540);

        JPanel main = new JPanel(new BorderLayout(8, 8));
        main.setBorder(new EmptyBorder(10, 10, 10, 10));

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

        sliderPanel.setLayout(new BoxLayout(sliderPanel, BoxLayout.Y_AXIS));
        sliderPanel.setBorder(BorderFactory.createTitledBorder("Parameters"));
        main.add(new JScrollPane(sliderPanel), BorderLayout.CENTER);

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
            int slMin = 0, slMax = 1000;
            int slVal = Math.round((def - min) / (max - min) * slMax);
            JSlider slider = new JSlider(slMin, slMax, slVal);
            slider.setMajorTickSpacing(250);
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
            // Apply the default so the plugin's runtime state matches the slider.
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
            setStatus(String.format("Loaded %.2f s of audio (%d samples).",
                    originalAudio.length / (float) SR, originalAudio.length));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Load failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // Decodes anything javax.sound can read into mono 44.1 kHz float samples.
    private static float[] decodeToMono44k(AudioInputStream in) throws Exception {
        AudioFormat src = in.getFormat();
        // First, force PCM_SIGNED 16-bit mono at original rate.
        AudioFormat mono = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                src.getSampleRate(), 16, 1, 2, src.getSampleRate(), false);
        AudioInputStream monoStream;
        if (src.getChannels() > 1) {
            monoStream = AudioSystem.getAudioInputStream(mono, in);
        } else {
            monoStream = AudioSystem.getAudioInputStream(mono, in);
        }
        // Then resample to 44.1 kHz.
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                SR, 16, 1, 2, SR, false);
        AudioInputStream resampled = AudioSystem.getAudioInputStream(target, monoStream);
        // Read all bytes.
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
            // Re-init so each render starts from a known state.
            plugin.getClass().getMethod("init", int.class).invoke(plugin, SR);
            // Re-apply every slider's current value so init defaults don't bury them.
            Method pSet = plugin.getClass().getMethod("setParameter", String.class, float.class);
            for (Map.Entry<String, JSlider> en : sliders.entrySet()) {
                String name = en.getKey();
                JSlider s = en.getValue();
                Method pMin = plugin.getClass().getMethod("parameterMin", String.class);
                Method pMax = plugin.getClass().getMethod("parameterMax", String.class);
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
}
