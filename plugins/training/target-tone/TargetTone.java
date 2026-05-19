package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;
import com.vocalmonitor.plugin.gamekit.audio.NoteName;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;

import java.util.Map;

/**
 * Target Tone — sing-along reference tone with a tuner-style display.
 *
 * Audio: writes a sine at `target` Hz into the output buffer, mixed
 * with the dry input at `mix` (so the user can also hear themselves
 * through the monitor chain).  Volume of the reference tone is set
 * by `toneLevel`.
 *
 * Visual: horizontal needle deflects left/right as cents-error; green
 * stripe in the centre is the "in tune" band (±10 cents).  Pitch
 * detection uses gamekit's {@link PitchTracker} so the algorithm
 * stays in one place — see plugins/training/pitch-arrow/ for the
 * reference usage.
 */
public final class TargetTone implements VocalMonitorVisualPlugin {

    private int sampleRate = 44100;
    private final PitchTracker pitch = new PitchTracker();
    private float smoothedCents = 0f;
    private float targetHz = 440f;
    private float toneLevel = 0.15f;
    private float mix = 1f;          // dry pass-through level
    private double tonePhase = 0.0;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        pitch.setSampleRate(sr).reset();
        smoothedCents = 0f;
        tonePhase = 0.0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "target", "toneLevel", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "target":    return 80f;
            default:          return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "target":    return 800f;
            default:          return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "target":    return 440f;
            case "toneLevel": return 0.15f;
            case "mix":       return 1f;
            default:          return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "target":    return "Target Hz";
            case "toneLevel": return "Tone vol";
            case "mix":       return "Dry mix";
            default:          return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "target":    targetHz = v; break;
            case "toneLevel": toneLevel = v; break;
            case "mix":       mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        // Audio effect: reference tone mixed with the dry signal.
        // Used at save-time / DAW playback.  Pitch detection for the
        // tuner display happens in feedLive() (read from streams in
        // render) since slim's live monitor bypasses this path.
        final double twoPi    = 2.0 * Math.PI;
        final double phaseInc = twoPi * targetHz / sampleRate;
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float tone = (float) (Math.sin(tonePhase) * toneLevel);
            tonePhase += phaseInc;
            if (tonePhase > twoPi) tonePhase -= twoPi;
            output[i] = (float) Math.tanh(tone + input[i] * mix);
        }
    }

    private void feedLive(Map<String, float[]> streams) {
        pitch.feed(streams, 0.016f);
        float cents = pitch.voiced() ? pitch.centsFrom(targetHz) : 0f;
        smoothedCents += 0.35f * (cents - smoothedCents);
    }

    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        feedLive(streams);
        float scale = Math.min(width, height) / 360f;
        PluginPaint bg = canvas.newPaint();
        bg.setColor(0xFF101418);
        canvas.drawRect(0, 0, width, height, bg);

        // Heading.
        PluginPaint titleP = canvas.newPaint();
        titleP.setColor(0xFFFFFFFF);
        titleP.setTextSize(28f * scale);
        titleP.setTextAlign(1);
        canvas.drawText("Target: " + NoteName.of(targetHz) +
            "  (" + Math.round(targetHz) + " Hz)",
            width / 2f, 44f, titleP);

        // Tuner gauge: horizontal band, needle reads ±50 cents full-scale.
        float cx = width / 2f;
        float gaugeY = height * 0.55f;
        float gaugeW = width * 0.82f;
        float left = cx - gaugeW / 2f;
        float right = cx + gaugeW / 2f;
        float thickness = 14f;
        // Background bar.
        PluginPaint bar = canvas.newPaint();
        bar.setColor(0xFF22262C);
        canvas.drawRoundRect(left, gaugeY - thickness, right, gaugeY + thickness, 8f, bar);
        // Green centre band (±10 cents on a ±50 scale → 10/50 = 20% wide).
        PluginPaint centre = canvas.newPaint();
        centre.setColor(0x33AAFFAA);
        float bandHalf = gaugeW * (10f / 100f) * 0.5f;  // ±10 cents of full ±50
        canvas.drawRoundRect(cx - bandHalf, gaugeY - thickness,
            cx + bandHalf, gaugeY + thickness, 8f, centre);

        // Tick marks every 10 cents.
        PluginPaint tick = canvas.newPaint();
        tick.setColor(0x99888888);
        for (int c = -50; c <= 50; c += 10) {
            float tx = cx + gaugeW * (c / 100f);
            float tlen = (c == 0) ? 16f : 8f;
            canvas.drawLine(tx, gaugeY - thickness - 2, tx, gaugeY - thickness - 2 - tlen, tick);
            canvas.drawLine(tx, gaugeY + thickness + 2, tx, gaugeY + thickness + 2 + tlen, tick);
        }
        // ±50 labels.
        PluginPaint lbl = canvas.newPaint();
        lbl.setColor(0xFF888888);
        lbl.setTextSize(11f * scale);
        lbl.setTextAlign(1);
        canvas.drawText("-50¢", left,  gaugeY + thickness + 32, lbl);
        canvas.drawText("0",    cx,    gaugeY + thickness + 32, lbl);
        canvas.drawText("+50¢", right, gaugeY + thickness + 32, lbl);

        boolean voiced = pitch.voiced();
        if (voiced) {
            // Needle position from cents.
            float c = smoothedCents;
            if (c > 60f) c = 60f;
            if (c < -60f) c = -60f;
            float needleX = cx + gaugeW * (c / 100f);
            boolean inTune = Math.abs(smoothedCents) <= 10f;
            int needleColor = inTune ? 0xFF66DD66 :
                (Math.abs(smoothedCents) < 25f ? 0xFFE3B544 : 0xFFE25656);
            PluginPaint needle = canvas.newPaint();
            needle.setColor(needleColor);
            needle.setGlow(needleColor, 14f);
            canvas.drawRoundRect(needleX - 4, gaugeY - thickness - 16,
                needleX + 4, gaugeY + thickness + 16, 4f, needle);

            // Pitch readout.
            PluginPaint readout = canvas.newPaint();
            readout.setColor(needleColor);
            readout.setTextSize(26f * scale);
            readout.setTextAlign(1);
            String label = inTune ? "In tune!" :
                ((smoothedCents > 0 ? "+" : "") + Math.round(smoothedCents) + " cents");
            canvas.drawText(label, cx, height * 0.84f, readout);
            PluginPaint sub = canvas.newPaint();
            sub.setColor(0xFFCFCFCF);
            sub.setTextSize(16f * scale);
            sub.setTextAlign(1);
            canvas.drawText(
                "You: " + Math.round(pitch.hz()) + " Hz · " + NoteName.of(pitch.hz()),
                cx, height * 0.91f, sub);
        } else {
            PluginPaint hint = canvas.newPaint();
            hint.setColor(0xFF888888);
            hint.setTextSize(20f * scale);
            hint.setTextAlign(1);
            canvas.drawText("Sing along with the tone…", cx, height * 0.85f, hint);
        }
    }

}
