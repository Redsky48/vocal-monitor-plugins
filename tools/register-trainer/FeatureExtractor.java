package com.vocalmonitor.plugin.community;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline feature extractor for the Register Detector training set.
 *
 * Walks a VocalSet directory, decodes every WAV, and runs the EXACT same
 * {@link RegisterFeatures} code the phone uses on overlapping FFT_N
 * windows. Each voiced frame becomes one CSV row:
 *
 *   file,singer,technique,sr,f0,h1h2,h1a3,hrf,spr,oq
 *
 * Crucial: this links against the plugin's own RegisterFeatures.java
 * (compiled together — see README), so the feature vector here is
 * byte-for-byte what {@code RegisterDetector} computes live. Label
 * assignment is intentionally NOT done here — it's a global, per-singer
 * decision handled in train.py, which only needs the singer/technique/f0
 * columns this emits.
 *
 * Usage:
 *   java -cp build com.vocalmonitor.plugin.community.FeatureExtractor \
 *        <vocalset-root> <out.csv> [hop] [maxFramesPerFile]
 *
 * hop defaults to 1024 (50% overlap at FFT_N=2048); maxFramesPerFile
 * defaults to 0 (no cap).
 */
public final class FeatureExtractor {

    // Technique tokens that appear in VocalSet filenames / paths. Lower-
    // cased substring match. Order matters only for disambiguation —
    // longer / more specific tokens first.
    private static final String[] TECHNIQUES = {
        "vocal_fry", "lip_trill", "fast_forte", "fast_piano", "slow_forte",
        "slow_piano", "long_tones", "messa", "arpeggios", "scales",
        "vibrato", "straight", "breathy", "belt", "trillo", "trill",
        "inhaled", "spoken", "forte", "piano",
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: FeatureExtractor <vocalset-root> <out.csv> [hop] [maxFramesPerFile]");
            System.exit(2);
        }
        File root = new File(args[0]);
        File outFile = new File(args[1]);
        int hop = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
        int maxF = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        if (!root.isDirectory()) {
            System.err.println("not a directory: " + root);
            System.exit(2);
        }

        List<File> wavs = new ArrayList<>();
        collectWavs(root, wavs);
        System.out.println("found " + wavs.size() + " wav files under " + root);

        RegisterFeatures feat = new RegisterFeatures();
        int FFT_N = RegisterFeatures.FFT_N;
        float[] frame = new float[FFT_N];
        long totalRows = 0;
        int fileNo = 0;

        try (BufferedWriter w = new BufferedWriter(new FileWriter(outFile))) {
            w.write("file,singer,technique,sr,f0,h1h2,h1a3,hrf,spr,oq\n");
            for (File wav : wavs) {
                fileNo++;
                float[] mono;
                int sr;
                try {
                    Decoded d = decodeMono(wav);
                    mono = d.samples;
                    sr = d.sampleRate;
                } catch (Exception e) {
                    System.err.println("  skip (decode failed): " + wav + " — " + e.getMessage());
                    continue;
                }
                if (mono.length < FFT_N) continue;

                String singer = parseSinger(wav, root);
                String technique = parseTechnique(wav);
                String fileTag = wav.getName().replace(",", "_");

                int framesThisFile = 0;
                for (int start = 0; start + FFT_N <= mono.length; start += hop) {
                    System.arraycopy(mono, start, frame, 0, FFT_N);
                    if (!feat.analyse(frame, sr)) continue;
                    w.write(fileTag); w.write(',');
                    w.write(singer); w.write(',');
                    w.write(technique); w.write(',');
                    w.write(Integer.toString(sr)); w.write(',');
                    w.write(fmt(feat.f0));   w.write(',');
                    w.write(fmt(feat.h1h2)); w.write(',');
                    w.write(fmt(feat.h1a3)); w.write(',');
                    w.write(fmt(feat.hrf));  w.write(',');
                    w.write(fmt(feat.spr));  w.write(',');
                    w.write(fmt(feat.oq));   w.write('\n');
                    totalRows++;
                    framesThisFile++;
                    if (maxF > 0 && framesThisFile >= maxF) break;
                }
                if (fileNo % 50 == 0) {
                    System.out.printf(Locale.US, "  %d/%d files, %d rows%n",
                            fileNo, wavs.size(), totalRows);
                }
            }
        }
        System.out.printf(Locale.US, "done: %d rows from %d files → %s%n",
                totalRows, wavs.size(), outFile);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.5g", v);
    }

    private static void collectWavs(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) collectWavs(f, out);
            else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".wav")) out.add(f);
            }
        }
    }

    // VocalSet singer ids look like m1..m11 / f1..f9 and appear as a path
    // component or filename prefix. Grab the first [mf]<digits> token from
    // the path relative to the root; fall back to "unknown".
    private static String parseSinger(File wav, File root) {
        String rel = wav.getAbsolutePath()
                .substring(Math.min(root.getAbsolutePath().length(), wav.getAbsolutePath().length()))
                .toLowerCase(Locale.US)
                .replace('\\', '/');
        for (String part : rel.split("[/_.\\- ]")) {
            if (part.matches("[mf][0-9]{1,2}")) return part;
        }
        return "unknown";
    }

    private static String parseTechnique(File wav) {
        String hay = wav.getAbsolutePath().toLowerCase(Locale.US).replace('\\', '/');
        for (String t : TECHNIQUES) {
            if (hay.contains(t)) return t;
        }
        return "other";
    }

    // ── Minimal WAV decode → mono float[] in [-1, 1] at native SR ──
    private static final class Decoded {
        final float[] samples;
        final int sampleRate;
        Decoded(float[] s, int sr) { this.samples = s; this.sampleRate = sr; }
    }

    private static Decoded decodeMono(File wav) throws IOException, Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat fmt = in.getFormat();
            // Convert anything to 16-bit signed PCM at the source SR so the
            // byte unpacking below is uniform.
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    fmt.getSampleRate(),
                    16,
                    fmt.getChannels(),
                    fmt.getChannels() * 2,
                    fmt.getSampleRate(),
                    false);                       // little-endian
            AudioInputStream pcm = AudioSystem.isConversionSupported(target, fmt)
                    ? AudioSystem.getAudioInputStream(target, in)
                    : in;
            AudioFormat af = pcm.getFormat();
            int channels = af.getChannels();
            int sr = (int) af.getSampleRate();
            byte[] bytes = readAll(pcm);
            int frames = bytes.length / (2 * channels);
            float[] mono = new float[frames];
            int bi = 0;
            for (int f = 0; f < frames; f++) {
                int acc = 0;
                for (int c = 0; c < channels; c++) {
                    int lo = bytes[bi++] & 0xFF;
                    int hi = bytes[bi++];          // signed high byte
                    acc += (short) ((hi << 8) | lo);
                }
                mono[f] = (acc / (float) channels) / 32768f;
            }
            return new Decoded(mono, sr);
        }
    }

    private static byte[] readAll(AudioInputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[1 << 16];
        int r;
        while ((r = in.read(tmp)) > 0) bos.write(tmp, 0, r);
        return bos.toByteArray();
    }
}
