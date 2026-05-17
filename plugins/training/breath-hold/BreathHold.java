package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Breath Hold — sustain-a-tone stopwatch + top-3 leaderboard.
 *
 * Detection runs on the audio thread: an envelope-follower (fast
 * attack / slow release) drives a hysteretic "is voicing" flag.
 * Voicing starts → currentRun resets to 0 and counts up in seconds.
 * Voicing stops for >0.5 s → currentRun finalises and gets inserted
 * into a small descending top-3 list.
 *
 * Bar across the bottom shows live RMS so the user knows the
 * detector is hearing them.  Triangle/dot at the threshold marks
 * the "stops counting" floor.
 */
public final class BreathHold implements VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private float envelope = 0f;
    private boolean voicing = false;
    private float silenceTime = 0f;     // seconds of silence since last voicing
    private float runTime = 0f;         // seconds of current run
    private float lastFinalRun = 0f;
    private final float[] topThree = new float[] { 0f, 0f, 0f };
    private float threshold = 0.012f;   // RMS-ish trigger
    private long lastRenderMs = -1L;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        envelope = 0f;
        voicing = false;
        silenceTime = 0f;
        runTime = 0f;
        lastFinalRun = 0f;
        topThree[0] = topThree[1] = topThree[2] = 0f;
        lastRenderMs = -1L;
    }

    @Override public String[] parameterNames() {
        return new String[] { "threshold" };
    }
    @Override public float parameterMin(String n) { return 0.002f; }
    @Override public float parameterMax(String n) { return 0.10f; }
    @Override public float parameterDefault(String n) { return 0.012f; }
    @Override public String parameterLabel(String n) { return "Threshold"; }
    @Override public void setParameter(String n, float v) {
        if ("threshold".equals(n)) threshold = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        // Passthrough — slim's live monitor doesn't drive visual state
        // through here; see feedLive() called from render().
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    /** Live-mic feed: update envelope + timer using wall-clock dt
     *  computed from `timeMs`.  Called from render() at ~60 Hz. */
    private void feedLive(Map<String, float[]> streams, long timeMs) {
        float dt = (lastRenderMs < 0) ? 0.016f
            : Math.min(0.10f, (timeMs - lastRenderMs) / 1000f);
        lastRenderMs = timeMs;
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave != null && wave.length > 0) {
            double sumSq = 0.0;
            for (int i = 0; i < wave.length; i++) sumSq += wave[i] * wave[i];
            float rms = (float) Math.sqrt(sumSq / wave.length);
            float att = 0.5f, rel = 0.05f;
            if (rms > envelope) envelope += att * (rms - envelope);
            else                envelope += rel * (rms - envelope);
        } else {
            envelope *= 0.95f;
        }
        boolean loud = envelope > threshold;
        if (loud) {
            if (!voicing) {
                voicing = true;
                runTime = 0f;
                silenceTime = 0f;
            } else {
                runTime += dt;
                silenceTime = 0f;
            }
        } else {
            if (voicing) {
                silenceTime += dt;
                if (silenceTime > 0.5f) {
                    voicing = false;
                    lastFinalRun = runTime;
                    insertTopThree(runTime);
                    runTime = 0f;
                }
            }
        }
    }

    private void insertTopThree(float t) {
        if (t < 0.5f) return;          // ignore micro-noises
        if (t > topThree[0])      { topThree[2] = topThree[1]; topThree[1] = topThree[0]; topThree[0] = t; }
        else if (t > topThree[1]) { topThree[2] = topThree[1]; topThree[1] = t; }
        else if (t > topThree[2]) { topThree[2] = t; }
    }

    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        feedLive(streams, timeMs);
        PluginPaint bg = canvas.newPaint();
        bg.setColor(0xFF101418);
        canvas.drawRect(0, 0, width, height, bg);

        // Heading.
        PluginPaint title = canvas.newPaint();
        title.setColor(0xFFCFCFCF);
        title.setTextSize(20f);
        title.setTextAlign(1);
        canvas.drawText("BREATH HOLD", width / 2f, 36f, title);

        // Giant stopwatch — shows currentRun while voicing, otherwise
        // the most recent finalised run.
        float displayTime = voicing ? runTime : lastFinalRun;
        PluginPaint clock = canvas.newPaint();
        clock.setColor(voicing ? 0xFF66DD66 : 0xFFFFFFFF);
        clock.setGlow(voicing ? 0x66DD66 : 0, voicing ? 14f : 0f);
        clock.setTextSize(96f);
        clock.setTextAlign(1);
        canvas.drawText(formatTime(displayTime), width / 2f, height * 0.50f, clock);

        // Sub-label.
        PluginPaint sub = canvas.newPaint();
        sub.setColor(0xFFAAAAAA);
        sub.setTextSize(16f);
        sub.setTextAlign(1);
        canvas.drawText(
            voicing ? "Hold the note…" :
                (lastFinalRun > 0f ? "Last hold" : "Sing to start"),
            width / 2f, height * 0.50f + 36, sub);

        // Top-3 leaderboard.
        PluginPaint hdr = canvas.newPaint();
        hdr.setColor(0xFFCFCFCF);
        hdr.setTextSize(14f);
        hdr.setTextAlign(0);
        canvas.drawText("BEST", 24f, height * 0.50f - 20, hdr);
        String[] medals = { "1st", "2nd", "3rd" };
        int[] medalColors = { 0xFFFFD66B, 0xFFCFCFCF, 0xFFCB965A };
        for (int i = 0; i < 3; i++) {
            PluginPaint p = canvas.newPaint();
            p.setColor(medalColors[i]);
            p.setTextSize(18f);
            p.setTextAlign(0);
            canvas.drawText(
                medals[i] + "  " + formatTime(topThree[i]),
                24f, height * 0.50f + 10 + i * 26, p);
        }

        // Live signal bar across the bottom.
        float barY = height - 36;
        float pad = 24f;
        PluginPaint barBg = canvas.newPaint();
        barBg.setColor(0xFF222630);
        canvas.drawRoundRect(pad, barY - 8, width - pad, barY + 8, 6f, barBg);
        float scaled = Math.min(1f, envelope * 6f);
        int barColor = voicing ? 0xFF66DD66 : 0xFFAAAAAA;
        PluginPaint barFg = canvas.newPaint();
        barFg.setColor(barColor);
        canvas.drawRoundRect(pad, barY - 8,
            pad + (width - pad * 2) * scaled, barY + 8, 6f, barFg);
        // Threshold marker.
        float threshX = pad + (width - pad * 2) * Math.min(1f, threshold * 6f);
        PluginPaint th = canvas.newPaint();
        th.setColor(0xFFFFAA44);
        canvas.drawLine(threshX, barY - 14, threshX, barY + 14, th);
    }

    private static String formatTime(float seconds) {
        int whole = (int) Math.floor(seconds);
        int tenths = Math.min(9, Math.max(0, (int) ((seconds - whole) * 10f)));
        return whole + "." + tenths + "s";
    }
}
