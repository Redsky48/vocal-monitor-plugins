package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * Robot Voice — ring-modulator.  Multiplies the input by a sine
 * carrier so every formant gets shifted by ±carrier Hz, producing
 * the classic Daleks / R2-D2 / Cylon timbre.  Carrier on the low
 * end (40-100 Hz) sounds like a deep droid; high end (400-800 Hz)
 * gets you a tinny satellite-bot.  Soft-clip (tanh) on the wet
 * path keeps loud singing from going razor-edged on the speakers.
 */
public final class RobotVoice implements VocalMonitorNativePlugin {

    private int sampleRate = 44100;
    private float carrierHz = 80f;
    private float mix       = 1f;
    private float drive     = 1.5f;
    private double phase = 0.0;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        this.phase = 0.0;
    }

    @Override public String[] parameterNames() {
        return new String[] { "carrier", "drive", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "carrier": return 20f;
            case "drive":   return 1f;
            default:        return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "carrier": return 800f;
            case "drive":   return 4f;
            default:        return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "carrier": return 80f;
            case "drive":   return 1.5f;
            case "mix":     return 1f;
            default:        return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "carrier": return "Carrier Hz";
            case "drive":   return "Drive";
            case "mix":     return "Mix";
            default:        return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "carrier": carrierHz = v; break;
            case "drive":   drive = v; break;
            case "mix":     mix = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final double twoPi    = 2.0 * Math.PI;
        final double phaseInc = twoPi * carrierHz / sampleRate;
        for (int i = 0; i < input.length; i++) {
            float carrier = (float) Math.sin(phase);
            phase += phaseInc;
            if (phase > twoPi) phase -= twoPi;
            float wet = (float) Math.tanh(input[i] * carrier * drive);
            output[i] = wet * mix + input[i] * (1f - mix);
        }
    }
}
