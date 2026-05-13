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
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestApp extends JFrame {

    private static final int SR = 44100;
    private static final int BLOCK = 1024;

    private final Path repoRoot;
    private final Path stubSrc;
    private final Path stubDir;  // dir containing all native-stub .java files

    // Plugin browser: categorised tree + search box on the left sidebar.
    // Far easier to find a plugin in a 60+ catalogue than a flat dropdown.
    private final JTextField searchField = new JTextField();
    private final javax.swing.tree.DefaultMutableTreeNode treeRoot =
            new javax.swing.tree.DefaultMutableTreeNode("Plugins");
    private final JTree pluginTree = new JTree(treeRoot);
    private final JPanel sliderPanel = new JPanel();
    private final JLabel statusLabel = new JLabel("Pick a plugin on the left, then load a WAV or record from the mic.");
    private final JButton loadBtn  = new JButton("Load WAV...");
    private final JButton micBtn   = new JButton("Record 5 s from mic");
    private final JButton playOrig = new JButton("Play original");
    private final JButton process  = new JButton("Process");
    private final JButton playProc = new JButton("Play processed");
    private final JButton stopBtn  = new JButton("Stop");
    private final JButton saveBtn  = new JButton("Save processed WAV...");
    private final JButton saveDefBtn = new JButton("Save as default test input");
    // Keep the full unfiltered list so the search field can rebuild from it
    // without re-scanning the disk.
    private final List<PluginEntry> allPlugins = new ArrayList<>();

    // Visualisation panels.
    private final WaveformPanel waveOrig = new WaveformPanel(new Color(120, 220, 120));
    private final WaveformPanel waveProc = new WaveformPanel(new Color(120, 180, 220));
    private final SpectrogramPanel specOrig = new SpectrogramPanel();
    private final SpectrogramPanel specProc = new SpectrogramPanel();
    private final JTextArea statsArea = new JTextArea();

    private float[] originalAudio;
    private float[] processedAudio;
    private Object  plugin;
    private ClassLoader pluginLoader;     // for Proxy adapter construction
    private boolean isVisualPlugin;
    private String[] paramNames;
    private final Map<String, JSlider> sliders = new LinkedHashMap<>();
    private final Map<String, JLabel> sliderLabels = new LinkedHashMap<>();
    private PluginWindow pluginWindow;
    private final JButton openUiBtn = new JButton("Open Plugin UI");

    private SourceDataLine playLine;
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private Thread playThread;

    public TestApp() throws Exception {
        super("Vocal Monitor Plugin Test App");
        repoRoot = findRepoRoot();
        stubDir = repoRoot.resolve("scripts/native-stub/com/vocalmonitor/plugin");
        stubSrc = stubDir.resolve("VocalMonitorNativePlugin.java");
        if (!Files.exists(stubSrc)) {
            throw new IOException("Cannot find " + stubSrc + " — run from the repo root.");
        }
        scanPlugins();
        buildUI();
        // Default selection: pick the first plugin in the tree so the
        // UI isn't empty on startup.
        if (!allPlugins.isEmpty()) {
            selectPlugin(allPlugins.get(0));
        }
    }

    // ---------------------------------------------------------------
    //  Plugin discovery + compile
    // ---------------------------------------------------------------
    private static class PluginEntry {
        final String id, name, category, className;
        final Path folder, javaSrc;
        PluginEntry(String id, String name, String category, Path folder, Path javaSrc, String className) {
            this.id = id; this.name = name; this.category = category;
            this.folder = folder; this.javaSrc = javaSrc; this.className = className;
        }
        @Override public String toString() { return name; }
    }

    private void scanPlugins() throws IOException {
        Path pluginsDir = repoRoot.resolve("plugins");
        try (var cats = Files.newDirectoryStream(pluginsDir, Files::isDirectory)) {
            for (Path catDir : cats) {
                String catName = catDir.getFileName().toString();
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
                        allPlugins.add(new PluginEntry(id, name != null ? name : id, catName,
                                plugDir, javaSrc, className));
                    }
                }
            }
        }
        allPlugins.sort(Comparator
                .comparing((PluginEntry p) -> p.category)
                .thenComparing(p -> p.name));
        rebuildTree("");
    }

    // Build the JTree from the in-memory plugin list, filtering by the
    // search query. Empty query = all categories shown.
    private void rebuildTree(String query) {
        treeRoot.removeAllChildren();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        Map<String, javax.swing.tree.DefaultMutableTreeNode> catNodes = new LinkedHashMap<>();
        int matches = 0;
        for (PluginEntry e : allPlugins) {
            if (!q.isEmpty()
                    && !e.name.toLowerCase(Locale.ROOT).contains(q)
                    && !e.id.toLowerCase(Locale.ROOT).contains(q)
                    && !e.category.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            var catNode = catNodes.computeIfAbsent(e.category, k -> {
                String label = k.substring(0, 1).toUpperCase(Locale.ROOT) + k.substring(1);
                return new javax.swing.tree.DefaultMutableTreeNode(label);
            });
            catNode.add(new javax.swing.tree.DefaultMutableTreeNode(e));
            matches++;
        }
        for (var cat : catNodes.values()) treeRoot.add(cat);
        ((javax.swing.tree.DefaultTreeModel) pluginTree.getModel()).reload();
        // Expand all category nodes so plugins are visible by default.
        for (int i = 0; i < pluginTree.getRowCount(); i++) pluginTree.expandRow(i);
        if (!q.isEmpty()) setStatus(matches + " match(es) for \"" + query + "\"");
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
        // Compile EVERY stub .java in the package alongside the plugin
        // source so canvas-mode plugins (which import PluginCanvas etc.)
        // resolve their visual interfaces too.
        List<File> sources = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(stubDir, "*.java")) {
            for (Path p : ds) sources.add(p.toFile());
        }
        sources.add(entry.javaSrc.toFile());
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
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
        pluginLoader = loader;
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

        // --- North: top action buttons only (Load / Record) ---
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        topButtons.add(loadBtn); topButtons.add(micBtn);
        main.add(topButtons, BorderLayout.NORTH);

        // --- West sidebar: search + categorised plugin tree + sliders ---
        JPanel sidebar = new JPanel(new BorderLayout(0, 6));
        sidebar.setPreferredSize(new Dimension(320, 0));

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel("Filter:"), BorderLayout.WEST);
        searchRow.add(searchField, BorderLayout.CENTER);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { rebuildTree(searchField.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { rebuildTree(searchField.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildTree(searchField.getText()); }
        });

        pluginTree.setRootVisible(false);
        pluginTree.setShowsRootHandles(true);
        pluginTree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        pluginTree.addTreeSelectionListener(e -> {
            javax.swing.tree.DefaultMutableTreeNode node =
                    (javax.swing.tree.DefaultMutableTreeNode) pluginTree.getLastSelectedPathComponent();
            if (node == null || !node.isLeaf()) return;
            Object obj = node.getUserObject();
            if (obj instanceof PluginEntry) {
                try { selectPlugin((PluginEntry) obj); }
                catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                            "Plugin load error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        JScrollPane treeScroll = new JScrollPane(pluginTree);

        sliderPanel.setLayout(new BoxLayout(sliderPanel, BoxLayout.Y_AXIS));
        sliderPanel.setBorder(BorderFactory.createTitledBorder("Parameters"));
        JScrollPane sliderScroll = new JScrollPane(sliderPanel);

        JSplitPane sideSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, sliderScroll);
        sideSplit.setResizeWeight(0.55);   // tree on top, sliders below
        sideSplit.setDividerLocation(420);

        JPanel browserHeader = new JPanel(new BorderLayout(0, 4));
        browserHeader.add(searchRow, BorderLayout.NORTH);
        browserHeader.add(new JLabel(" Plugins"), BorderLayout.SOUTH);
        sidebar.add(browserHeader, BorderLayout.NORTH);
        sidebar.add(sideSplit, BorderLayout.CENTER);

        // --- Center: tabbed visualisations ---
        JTabbedPane tabs = new JTabbedPane();
        JPanel wavePane = new JPanel(new GridLayout(2, 1, 6, 6));
        wavePane.add(framed("Original - waveform", waveOrig));
        wavePane.add(framed("Processed - waveform", waveProc));
        tabs.addTab("Waveform", wavePane);
        JPanel specPane = new JPanel(new GridLayout(2, 1, 6, 6));
        specPane.add(framed("Original - spectrogram (0-12 kHz, log mag, -80 dB floor)", specOrig));
        specPane.add(framed("Processed - spectrogram", specProc));
        tabs.addTab("Spectrogram", specPane);
        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        statsArea.setText("(load audio + Process to see stats)");
        tabs.addTab("Stats", new JScrollPane(statsArea));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, tabs);
        split.setDividerLocation(320);
        split.setResizeWeight(0.0);
        main.add(split, BorderLayout.CENTER);

        // --- South: action buttons + status ---
        JPanel south = new JPanel(new BorderLayout());
        JPanel ctlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ctlRow.add(playOrig); ctlRow.add(process); ctlRow.add(playProc);
        ctlRow.add(stopBtn); ctlRow.add(saveBtn); ctlRow.add(saveDefBtn);
        ctlRow.add(openUiBtn);
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
        saveDefBtn.addActionListener(e -> saveAsDefault());
        openUiBtn.addActionListener(e -> openPluginWindow());

        playOrig.setEnabled(false);
        process.setEnabled(false);
        playProc.setEnabled(false);
        saveBtn.setEnabled(false);
        saveDefBtn.setEnabled(false);
        openUiBtn.setEnabled(false);

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
        setStatus("Compiling " + entry.id + "...");
        // Close any open plugin window from the previous selection — its
        // canvas adapter holds references into the previous classloader.
        if (pluginWindow != null) { pluginWindow.dispose(); pluginWindow = null; }
        Object loaded = loadPlugin(entry);
        Method init = loaded.getClass().getMethod("init", int.class);
        init.invoke(loaded, SR);
        plugin = loaded;
        paramNames = (String[]) loaded.getClass().getMethod("parameterNames").invoke(loaded);
        // Visual-mode detection: plugins opt in by implementing
        // VocalMonitorVisualPlugin from the per-plugin classloader's
        // copy of the interface.
        try {
            Class<?> visualIface = pluginLoader.loadClass(
                    "com.vocalmonitor.plugin.VocalMonitorVisualPlugin");
            isVisualPlugin = visualIface.isInstance(loaded);
        } catch (ClassNotFoundException e) {
            isVisualPlugin = false;
        }
        openUiBtn.setEnabled(isVisualPlugin);
        rebuildSliders();
        setStatus("Loaded " + entry.id + " (" + paramNames.length + " parameters"
                + (isVisualPlugin ? ", custom UI" : "") + ").");
        // DAW-style: a plugin with its own UI pops open automatically on
        // selection so the user immediately sees the panel that ships
        // with it. Closed windows stay closed if the user re-selects
        // the same plugin (handled by the auto-close-on-reselect above).
        if (isVisualPlugin) {
            SwingUtilities.invokeLater(this::openPluginWindow);
        }
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
            saveDefBtn.setEnabled(true);
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
            setStatus("Recording 5 s - speak / sing into the mic...");
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
                    saveDefBtn.setEnabled(true);
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
        try {
            writeWav(processedAudio, fc.getSelectedFile());
            setStatus("Saved to " + fc.getSelectedFile().getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveAsDefault() {
        if (originalAudio == null) return;
        // Write to a fixed path inside the repo. The CLI test harness
        // (tools/test-app/RunTest.java) reads from exactly this path,
        // so anything saved here becomes the "test input" for headless
        // plugin debugging runs.
        File out = repoRoot.resolve("tools/test-app/test-input.wav").toFile();
        try {
            writeWav(originalAudio, out);
            setStatus("Saved default test input → " + out.getName()
                    + "  (run RunTest.java from CLI to debug)");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Save error", JOptionPane.ERROR_MESSAGE);
        }
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

    private void setStatus(String msg) { statusLabel.setText(msg); }

    // ---------------------------------------------------------------
    //  Plugin custom-UI popup (visual-plugin host)
    // ---------------------------------------------------------------
    private void openPluginWindow() {
        if (!isVisualPlugin || plugin == null) return;
        if (pluginWindow != null) {
            pluginWindow.toFront();
            return;
        }
        try {
            pluginWindow = new PluginWindow(this, plugin, pluginLoader,
                    paramNames, sliders, originalAudio, SR);
            pluginWindow.setVisible(true);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    "Plugin UI error", JOptionPane.ERROR_MESSAGE);
        }
    }

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

    // ===============================================================
    //  Plugin custom UI — draggable popup, DAW-style
    // ===============================================================
    //
    // PluginWindow opens a small undecorated frame for plugins that
    // implement VocalMonitorVisualPlugin. The window has a fake DAW
    // title bar (drag handle + close button), a canvas where the
    // plugin's own render() draws into Java2D via Proxy adapters, and
    // a footer row of parameter sliders that stay in sync with the
    // main app's sliders.
    //
    // Because each plugin is loaded into its own URLClassLoader, the
    // plugin's PluginCanvas / PluginPaint / PluginPath are Class
    // objects distinct from any that TestApp could import. The
    // adapter sidesteps that by reflecting against the plugin's
    // classloader and creating java.lang.reflect.Proxy instances that
    // satisfy the plugin's interface types at runtime.
    //
    // A 60 Hz Swing Timer drives repaint. A background audio thread
    // keeps feeding the plugin's process() so any internal envelope /
    // analyser state stays live whether or not the user is playing
    // audio back, so the visual is always animated.
    static class PluginWindow extends JFrame {
        private final Object plugin;
        private final ClassLoader loader;
        private final String[] paramNames;
        private final Map<String, JSlider> mainSliders;  // mirror back into main
        private final float[] sourceAudio;
        private final int sampleRate;

        private final Class<?> visualIface;
        private final Class<?> canvasIface;
        private final Class<?> paintIface;
        private final Class<?> pathIface;
        private final Class<?> styleEnum;
        private final Class<?> blendEnum;
        private final Method renderM;
        private final Method processM;
        private final Method setParamM;
        private final Method getParamMinM;
        private final Method getParamMaxM;

        private final long startMs = System.currentTimeMillis();
        private final DrawPanel draw;
        private final Map<String, JSlider> popupSliders = new LinkedHashMap<>();
        private final Map<String, JLabel>  popupValueLabels = new LinkedHashMap<>();
        private final Map<String, Float>   paramSnapshot = new LinkedHashMap<>();
        private final Map<String, float[]> streamSnapshot = new LinkedHashMap<>();

        // Audio driver state (loops sourceAudio through plugin.process()
        // so internal envelope-followers stay alive even when the user
        // isn't playing back).
        private final Thread driver;
        private final AtomicBoolean driving = new AtomicBoolean(true);

        PluginWindow(JFrame owner, Object plugin, ClassLoader loader,
                     String[] paramNames, Map<String, JSlider> mainSliders,
                     float[] sourceAudio, int sampleRate) throws Exception {
            super("Plugin UI");
            setUndecorated(true);
            this.plugin = plugin;
            this.loader = loader;
            this.paramNames = paramNames;
            this.mainSliders = mainSliders;
            this.sourceAudio = sourceAudio;
            this.sampleRate = sampleRate;

            visualIface = loader.loadClass("com.vocalmonitor.plugin.VocalMonitorVisualPlugin");
            canvasIface = loader.loadClass("com.vocalmonitor.plugin.PluginCanvas");
            paintIface  = loader.loadClass("com.vocalmonitor.plugin.PluginPaint");
            pathIface   = loader.loadClass("com.vocalmonitor.plugin.PluginPath");
            styleEnum   = loader.loadClass("com.vocalmonitor.plugin.PluginStyle");
            blendEnum   = loader.loadClass("com.vocalmonitor.plugin.BlendMode");
            renderM = visualIface.getMethod("render",
                    canvasIface, int.class, int.class, long.class, Map.class, Map.class);
            processM    = plugin.getClass().getMethod("process", float[].class, float[].class);
            setParamM   = plugin.getClass().getMethod("setParameter", String.class, float.class);
            getParamMinM = plugin.getClass().getMethod("parameterMin", String.class);
            getParamMaxM = plugin.getClass().getMethod("parameterMax", String.class);

            // --- Build UI ---
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(new Color(18, 18, 22));
            root.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 70), 1));

            // Fake title bar with drag handle + close button.
            TitleBar bar = new TitleBar(this, getPluginDisplayName(plugin));
            root.add(bar, BorderLayout.NORTH);

            // Centre: canvas.
            draw = new DrawPanel();
            draw.setPreferredSize(new Dimension(560, 320));
            root.add(draw, BorderLayout.CENTER);

            // Footer: parameter sliders (if any).
            if (paramNames != null && paramNames.length > 0) {
                JPanel footer = buildSliderFooter();
                root.add(footer, BorderLayout.SOUTH);
            }

            setContentPane(root);
            pack();
            setLocationRelativeTo(owner);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) { shutdown(); }
            });

            // 60 Hz repaint.
            javax.swing.Timer t = new javax.swing.Timer(16, e -> draw.repaint());
            t.start();

            // Audio driver thread — keeps plugin envelope etc. alive.
            driver = new Thread(this::driveAudio, "plugin-driver-" + getPluginDisplayName(plugin));
            driver.setDaemon(true);
            driver.start();
        }

        private static String getPluginDisplayName(Object plugin) {
            String n = plugin.getClass().getSimpleName();
            // CamelCase → spaced words for a friendlier title.
            return n.replaceAll("([a-z])([A-Z])", "$1 $2");
        }

        private JPanel buildSliderFooter() {
            JPanel footer = new JPanel();
            footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
            footer.setBackground(new Color(24, 24, 28));
            footer.setBorder(new EmptyBorder(8, 12, 10, 12));
            for (String name : paramNames) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                JLabel nameLab = new JLabel(name);
                nameLab.setForeground(new Color(200, 200, 220));
                nameLab.setPreferredSize(new Dimension(100, 22));
                JSlider s = new JSlider(0, 1000);
                s.setOpaque(false);
                JSlider main = mainSliders.get(name);
                if (main != null) s.setValue(main.getValue());
                JLabel val = new JLabel("");
                val.setForeground(new Color(160, 200, 240));
                val.setPreferredSize(new Dimension(60, 22));
                s.addChangeListener(ev -> {
                    try {
                        float min = (float) getParamMinM.invoke(plugin, name);
                        float max = (float) getParamMaxM.invoke(plugin, name);
                        float v = min + (max - min) * s.getValue() / 1000f;
                        setParamM.invoke(plugin, name, v);
                        val.setText(String.format(Locale.ROOT, "%.3f", v));
                        // Mirror back into the main app's slider so
                        // batch-Process picks it up too.
                        if (main != null && main.getValue() != s.getValue()) {
                            main.setValue(s.getValue());
                        }
                    } catch (Exception ex) { /* ignore */ }
                });
                row.add(nameLab, BorderLayout.WEST);
                row.add(s, BorderLayout.CENTER);
                row.add(val, BorderLayout.EAST);
                footer.add(row);
                popupSliders.put(name, s);
                popupValueLabels.put(name, val);
                // Fire once to populate the value label.
                s.setValue(s.getValue());
            }
            return footer;
        }

        private void shutdown() {
            driving.set(false);
            if (driver != null) driver.interrupt();
        }

        // Pump audio blocks through plugin.process() in a loop so any
        // envelope / RMS / FFT state stays alive. If the user has no
        // source audio loaded, feeds a tiny noise floor so meters
        // still settle near zero instead of holding stale state.
        private void driveAudio() {
            final int block = 1024;
            float[] in  = new float[block];
            float[] out = new float[block];
            int srcLen = sourceAudio != null ? sourceAudio.length : 0;
            int pos = 0;
            long blockNs = 1_000_000_000L * block / sampleRate;
            while (driving.get()) {
                long t0 = System.nanoTime();
                if (srcLen > 0) {
                    for (int i = 0; i < block; i++) {
                        in[i] = sourceAudio[(pos + i) % srcLen];
                    }
                    pos = (pos + block) % srcLen;
                } else {
                    // Tiny silence so plugins that compute their own
                    // envelope on input don't crash on empty arrays.
                    Arrays.fill(in, 0f);
                }
                try {
                    processM.invoke(plugin, in, out);
                } catch (Exception e) { /* swallow — visual still shows last frame */ }
                // Capture host streams from the OUTPUT (what the user
                // would actually hear) for spec-mode plugins that read
                // peak/rms/etc. Computed once per block, freshest data.
                updateStreams(out);
                long elapsed = System.nanoTime() - t0;
                long sleep = blockNs - elapsed;
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000L, (int)(sleep % 1_000_000L)); }
                    catch (InterruptedException e) { return; }
                }
            }
        }

        private final float[] fftRe = new float[1024];
        private final float[] fftIm = new float[1024];
        private final Fft fft = new Fft(1024);
        private final float[] fftOut = new float[256];
        private final float[] wavBuf = new float[2048];
        private int wavBufPos = 0;
        private synchronized void updateStreams(float[] out) {
            double sumSq = 0; float peak = 0;
            for (float v : out) {
                if (Float.isNaN(v) || Float.isInfinite(v)) continue;
                sumSq += v * v;
                float a = v < 0 ? -v : v;
                if (a > peak) peak = a;
            }
            float rms = (float) Math.sqrt(sumSq / out.length);
            float peakDb = (float) (20 * Math.log10(Math.max(1e-9, peak)));
            float rmsDb  = (float) (20 * Math.log10(Math.max(1e-9, rms)));
            streamSnapshot.put("peak", new float[] { peakDb });
            streamSnapshot.put("rms",  new float[] { rmsDb });
            // Rolling waveform of the most recent 2048 samples.
            int n = out.length;
            for (int i = 0; i < n; i++) {
                wavBuf[(wavBufPos + i) % 2048] = out[i];
            }
            wavBufPos = (wavBufPos + n) % 2048;
            float[] wav = new float[2048];
            for (int i = 0; i < 2048; i++) wav[i] = wavBuf[(wavBufPos + i) % 2048];
            streamSnapshot.put("waveform", wav);
            // 1024-pt FFT magnitude → 256 log-mapped bins.
            double[] re = new double[1024]; double[] im = new double[1024];
            for (int i = 0; i < 1024; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 1023);
                re[i] = wav[wav.length - 1024 + i] * w;
            }
            fft.transform(re, im);
            for (int b = 0; b < 256; b++) {
                int srcBin = (int) Math.pow(2, b * 10.0 / 256.0);  // log map 1..512
                if (srcBin >= 512) srcBin = 511;
                fftOut[b] = (float) Math.sqrt(re[srcBin] * re[srcBin] + im[srcBin] * im[srcBin]) / 512f;
            }
            streamSnapshot.put("fft", fftOut.clone());
        }

        // The actual drawing surface — calls plugin.render() each paint.
        class DrawPanel extends JPanel {
            DrawPanel() { setBackground(new Color(8, 8, 12)); }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                try {
                    // Snapshot params (plugin may have parameterNames but
                    // no parameterValue getter — read via slider state).
                    paramSnapshot.clear();
                    for (Map.Entry<String, JSlider> e : popupSliders.entrySet()) {
                        float min = (float) getParamMinM.invoke(plugin, e.getKey());
                        float max = (float) getParamMaxM.invoke(plugin, e.getKey());
                        paramSnapshot.put(e.getKey(),
                                min + (max - min) * e.getValue().getValue() / 1000f);
                    }
                    Object canvasProxy = java.lang.reflect.Proxy.newProxyInstance(loader,
                            new Class<?>[] { canvasIface },
                            new CanvasHandler(g, loader, canvasIface, paintIface, pathIface,
                                    styleEnum, blendEnum));
                    long t = System.currentTimeMillis() - startMs;
                    renderM.invoke(plugin, canvasProxy, getWidth(), getHeight(), t,
                            paramSnapshot, streamSnapshot);
                } catch (Throwable ex) {
                    g.setColor(new Color(220, 80, 80));
                    String msg = ex.getCause() != null ? ex.getCause().toString() : ex.toString();
                    g.drawString("render() error: " + msg, 12, 24);
                    ex.printStackTrace();
                } finally {
                    g.dispose();
                }
            }
        }

        // Custom title bar — provides the drag affordance an undecorated
        // window normally lacks, plus a flat close button.
        static class TitleBar extends JPanel {
            private Point dragStart;
            TitleBar(JFrame win, String title) {
                setLayout(new BorderLayout());
                setBackground(new Color(34, 34, 40));
                setBorder(new EmptyBorder(6, 12, 6, 6));
                JLabel name = new JLabel(title);
                name.setForeground(new Color(220, 220, 230));
                name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
                add(name, BorderLayout.WEST);
                JButton close = new JButton("X");
                close.setForeground(new Color(220, 220, 230));
                close.setBackground(new Color(50, 50, 60));
                close.setBorder(new EmptyBorder(2, 8, 2, 8));
                close.setFocusPainted(false);
                close.addActionListener(e -> win.dispose());
                add(close, BorderLayout.EAST);
                MouseAdapter dr = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { dragStart = e.getPoint(); }
                    @Override public void mouseDragged(MouseEvent e) {
                        if (dragStart == null) return;
                        Point screen = e.getLocationOnScreen();
                        win.setLocation(screen.x - dragStart.x, screen.y - dragStart.y);
                    }
                };
                addMouseListener(dr);
                addMouseMotionListener(dr);
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
        }
    }

    // ===============================================================
    //  PluginCanvas → Graphics2D adapter (Proxy-based)
    // ===============================================================
    //
    // All three plugin-side interfaces (PluginCanvas / PluginPaint /
    // PluginPath) are loaded into the per-plugin classloader, so TestApp
    // can never `implements PluginCanvas` directly — its own classloader
    // doesn't see those types.  Workaround: java.lang.reflect.Proxy.
    // Each handler implements InvocationHandler and dispatches on
    // method name.
    static class CanvasHandler implements InvocationHandler {
        final Graphics2D g;
        final ClassLoader loader;
        final Class<?> canvasIface, paintIface, pathIface, styleEnum, blendEnum;
        final Deque<AffineTransform> txStack = new ArrayDeque<>();
        final Deque<Shape> clipStack = new ArrayDeque<>();

        CanvasHandler(Graphics2D g, ClassLoader loader,
                      Class<?> canvasIface, Class<?> paintIface, Class<?> pathIface,
                      Class<?> styleEnum, Class<?> blendEnum) {
            this.g = g; this.loader = loader;
            this.canvasIface = canvasIface; this.paintIface = paintIface;
            this.pathIface = pathIface; this.styleEnum = styleEnum; this.blendEnum = blendEnum;
        }

        @Override public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            switch (n) {
                case "drawRect": {
                    PaintHandler p = paintOf(args[4]);
                    Rectangle2D.Float r = new Rectangle2D.Float(
                            (float) args[0], (float) args[1],
                            (float) args[2] - (float) args[0],
                            (float) args[3] - (float) args[1]);
                    p.apply(g, r);
                    return null;
                }
                case "drawRoundRect": {
                    PaintHandler p = paintOf(args[5]);
                    RoundRectangle2D.Float r = new RoundRectangle2D.Float(
                            (float) args[0], (float) args[1],
                            (float) args[2] - (float) args[0],
                            (float) args[3] - (float) args[1],
                            (float) args[4] * 2, (float) args[4] * 2);
                    p.apply(g, r);
                    return null;
                }
                case "drawCircle": {
                    PaintHandler p = paintOf(args[3]);
                    float cx = (float) args[0], cy = (float) args[1], rad = (float) args[2];
                    Ellipse2D.Float e = new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2);
                    p.apply(g, e);
                    return null;
                }
                case "drawLine": {
                    PaintHandler p = paintOf(args[4]);
                    Line2D.Float ln = new Line2D.Float(
                            (float) args[0], (float) args[1],
                            (float) args[2], (float) args[3]);
                    p.apply(g, ln);
                    return null;
                }
                case "drawPath": {
                    PathHandler ph = pathOf(args[0]);
                    PaintHandler p = paintOf(args[1]);
                    p.apply(g, ph.path);
                    return null;
                }
                case "drawText": {
                    PaintHandler p = paintOf(args[3]);
                    p.applyText(g, (String) args[0], (float) args[1], (float) args[2]);
                    return null;
                }
                case "save":
                    txStack.push(new AffineTransform(g.getTransform()));
                    clipStack.push(g.getClip());
                    return null;
                case "restore":
                    if (!txStack.isEmpty()) g.setTransform(txStack.pop());
                    if (!clipStack.isEmpty()) g.setClip(clipStack.pop());
                    return null;
                case "translate":
                    g.translate((double) (float) args[0], (double) (float) args[1]);
                    return null;
                case "scale":
                    g.scale((double) (float) args[0], (double) (float) args[1]);
                    return null;
                case "rotate":
                    // PluginCanvas docs: "Counter-clockwise rotation in
                    // degrees around the origin". Java2D rotates
                    // clockwise for positive radians on a top-down y
                    // axis — flip the sign to match the contract.
                    g.rotate(-Math.toRadians((float) args[0]));
                    return null;
                case "clipRect":
                    g.clip(new Rectangle2D.Float(
                            (float) args[0], (float) args[1],
                            (float) args[2] - (float) args[0],
                            (float) args[3] - (float) args[1]));
                    return null;
                case "newPath":
                    return java.lang.reflect.Proxy.newProxyInstance(loader,
                            new Class<?>[] { pathIface }, new PathHandler());
                case "newPaint":
                    return java.lang.reflect.Proxy.newProxyInstance(loader,
                            new Class<?>[] { paintIface }, new PaintHandler());
                // Object methods Proxy may receive.
                case "toString": return "CanvasHandler";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals":   return proxy == args[0];
            }
            return null;
        }

        private PaintHandler paintOf(Object proxy) {
            return (PaintHandler) java.lang.reflect.Proxy.getInvocationHandler(proxy);
        }
        private PathHandler pathOf(Object proxy) {
            return (PathHandler) java.lang.reflect.Proxy.getInvocationHandler(proxy);
        }
    }

    static class PaintHandler implements InvocationHandler {
        int color = 0xFF000000;
        float strokeWidth = 1f;
        String style = "FILL";  // FILL / STROKE / FILL_AND_STROKE
        boolean antialias = true;
        java.awt.Paint shader = null;
        int glowColor = 0;
        float glowRadius = 0f;
        float shadowDx = 0, shadowDy = 0, shadowRadius = 0; int shadowColor = 0;
        String blendMode = "SRC_OVER";
        float textSize = 14f;
        int textAlign = 0;  // 0 left, 1 center, 2 right

        @Override public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            switch (n) {
                case "setColor":        color = (int) args[0]; shader = null; return proxy;
                case "setStrokeWidth":  strokeWidth = (float) args[0]; return proxy;
                case "setStyle":        style = ((Enum<?>) args[0]).name(); return proxy;
                case "setAntialias":    antialias = (boolean) args[0]; return proxy;
                case "setLinearGradient": {
                    int[] cols = (int[]) args[4]; float[] stops = (float[]) args[5];
                    Color[] cs = new Color[cols.length];
                    for (int i = 0; i < cols.length; i++) cs[i] = argb(cols[i]);
                    shader = new LinearGradientPaint(
                            (float) args[0], (float) args[1], (float) args[2], (float) args[3],
                            stops, cs);
                    return proxy;
                }
                case "setRadialGradient": {
                    int[] cols = (int[]) args[3]; float[] stops = (float[]) args[4];
                    Color[] cs = new Color[cols.length];
                    for (int i = 0; i < cols.length; i++) cs[i] = argb(cols[i]);
                    float rad = (float) args[2]; if (rad < 1f) rad = 1f;
                    shader = new RadialGradientPaint(
                            (float) args[0], (float) args[1], rad, stops, cs);
                    return proxy;
                }
                case "clearShader":     shader = null; return proxy;
                case "setGlow":         glowColor = (int) args[0]; glowRadius = (float) args[1]; return proxy;
                case "setShadow":
                    shadowDx = (float) args[0]; shadowDy = (float) args[1];
                    shadowRadius = (float) args[2]; shadowColor = (int) args[3];
                    return proxy;
                case "setBlendMode":    blendMode = ((Enum<?>) args[0]).name(); return proxy;
                case "setTextSize":     textSize = (float) args[0]; return proxy;
                case "setTextAlign":    textAlign = (int) args[0]; return proxy;
                case "toString":        return "PaintHandler(color=" + Integer.toHexString(color) + ")";
                case "hashCode":        return System.identityHashCode(proxy);
                case "equals":          return proxy == args[0];
            }
            return proxy;
        }

        // Apply paint state to g2d for a fill/stroke of the given shape.
        // The glow is approximated by overlay-blurring a buffered copy of
        // the shape — close enough to Skia's BlurMaskFilter for preview.
        void apply(Graphics2D g, Shape shape) {
            Graphics2D gg = (Graphics2D) g.create();
            try {
                gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        antialias ? RenderingHints.VALUE_ANTIALIAS_ON
                                  : RenderingHints.VALUE_ANTIALIAS_OFF);
                applyComposite(gg);
                if (glowRadius > 0.1f && glowColor != 0) {
                    paintGlow(gg, shape);
                }
                if (shadowRadius > 0.1f && shadowColor != 0) {
                    paintShadow(gg, shape);
                }
                gg.setPaint(shader != null ? shader : argb(color));
                gg.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if ("FILL".equals(style) || "FILL_AND_STROKE".equals(style)) gg.fill(shape);
                if ("STROKE".equals(style) || "FILL_AND_STROKE".equals(style)) gg.draw(shape);
            } finally {
                gg.dispose();
            }
        }
        void applyText(Graphics2D g, String text, float x, float y) {
            Graphics2D gg = (Graphics2D) g.create();
            try {
                gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        antialias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                                  : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                applyComposite(gg);
                Font f = gg.getFont().deriveFont(textSize);
                gg.setFont(f);
                gg.setPaint(shader != null ? shader : argb(color));
                FontMetrics fm = gg.getFontMetrics(f);
                float dx = 0;
                if (textAlign == 1) dx = -fm.stringWidth(text) / 2f;
                else if (textAlign == 2) dx = -fm.stringWidth(text);
                gg.drawString(text, x + dx, y);
            } finally {
                gg.dispose();
            }
        }

        private void applyComposite(Graphics2D g) {
            // Java2D's AlphaComposite doesn't cover every Skia blend, but
            // the common ones for glow effects map close enough:
            // ADD / SCREEN / COLOR_DODGE → roughly additive — clamp alpha
            //   high so layered draws brighten. Use SrcOver with high
            //   alpha (close enough for preview; not pixel-accurate).
            switch (blendMode) {
                case "ADD": case "SCREEN": case "COLOR_DODGE":
                    g.setComposite(AlphaComposite.SrcOver.derive(0.9f));
                    return;
                case "MULTIPLY": case "DARKEN": case "COLOR_BURN":
                    g.setComposite(AlphaComposite.SrcOver.derive(0.7f));
                    return;
                default:
                    g.setComposite(AlphaComposite.SrcOver);
            }
        }

        private void paintGlow(Graphics2D g, Shape shape) {
            Rectangle b = shape.getBounds();
            int pad = (int) Math.ceil(glowRadius * 2 + strokeWidth);
            int w = Math.max(4, b.width  + pad * 2);
            int h = Math.max(4, b.height + pad * 2);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.translate(-b.x + pad, -b.y + pad);
            ig.setPaint(argb(glowColor));
            ig.setStroke(new BasicStroke(strokeWidth + glowRadius * 0.5f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Fill or stroke the glow source matching the paint style.
            if ("FILL".equals(style) || "FILL_AND_STROKE".equals(style)) ig.fill(shape);
            else ig.draw(shape);
            ig.dispose();
            // Cheap box blur — fast and "good enough" for visual preview.
            int r = (int) Math.ceil(glowRadius);
            if (r > 16) r = 16;  // cap so we don't stall on huge glows
            BufferedImage blurred = boxBlur(img, r);
            g.drawImage(blurred, b.x - pad, b.y - pad, null);
        }

        private void paintShadow(Graphics2D g, Shape shape) {
            Rectangle b = shape.getBounds();
            int pad = (int) Math.ceil(shadowRadius * 2 + strokeWidth);
            int w = Math.max(4, b.width  + pad * 2);
            int h = Math.max(4, b.height + pad * 2);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.translate(-b.x + pad, -b.y + pad);
            ig.setPaint(argb(shadowColor));
            ig.fill(shape);
            ig.dispose();
            int r = (int) Math.ceil(shadowRadius);
            if (r > 16) r = 16;
            BufferedImage blurred = boxBlur(img, r);
            g.drawImage(blurred, (int) (b.x - pad + shadowDx),
                                 (int) (b.y - pad + shadowDy), null);
        }

        private static BufferedImage boxBlur(BufferedImage src, int radius) {
            if (radius < 1) return src;
            int size = radius * 2 + 1;
            float w = 1f / (size * size);
            float[] data = new float[size * size];
            Arrays.fill(data, w);
            Kernel k = new Kernel(size, size, data);
            return new ConvolveOp(k, ConvolveOp.EDGE_NO_OP, null).filter(src, null);
        }

        private static Color argb(int v) {
            return new Color(
                    (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, (v >>> 24) & 0xFF);
        }
    }

    static class PathHandler implements InvocationHandler {
        final Path2D.Float path = new Path2D.Float();

        @Override public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            switch (n) {
                case "moveTo":  path.moveTo((float) args[0], (float) args[1]); return proxy;
                case "lineTo":  path.lineTo((float) args[0], (float) args[1]); return proxy;
                case "quadTo":
                    path.quadTo((float) args[0], (float) args[1],
                                (float) args[2], (float) args[3]); return proxy;
                case "cubicTo":
                    path.curveTo((float) args[0], (float) args[1],
                                 (float) args[2], (float) args[3],
                                 (float) args[4], (float) args[5]); return proxy;
                case "close":   path.closePath(); return proxy;
                case "reset":   path.reset(); return proxy;
                case "toString": return "PathHandler";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals":   return proxy == args[0];
            }
            return proxy;
        }
    }
}
