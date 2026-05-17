package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * Monster Voice — soft-clip drive into a 4-pole one-pole LP cascade
 * for the dark / growly tonality, plus an amplitude-modulated 60 Hz
 * sub-oscillator riding underneath that gives it physical "thump"
 * without needing a real pitch-shift DSP.  Cheap to run, sounds
 * convincingly monstrous when the kid roars or growls into it.
 */
public final class MonsterVoice implements VocalMonitorNativePlugin {

    private int sampleRate = 44100;
    private float cutoff = 800f;
    private float drive  = 3f;
    private float rumble = 0.4f;
    private float mix    = 1f;

    private float lp1, lp2, lp3, lp4;
    private double subPhase;
    private float follower;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        lp1 = lp2 = lp3 = lp4 = 0f;
        subPhase = 0.0;
        follower = 0f;
    }

    @Override public String[] parameterNames() {
        return new String[] { "cutoff", "drive", "rumble", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "cutoff": return 200f;
            case "drive":  return 1f;
            default:       return 0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "cutoff": return 3000f;
            case "drive":  return 8f;
            default:       return 1f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "cutoff": return 800f;
            case "drive":  return 3f;
            case "rumble": return 0.4f;
            case "mix":    return 1f;
            default:       return 0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "cutoff": return "Cutoff Hz";
            case "drive":  return "Drive";
            case "rumble": return "Rumble";
            case "mix":    return "Mix";
            default:       return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "cutoff": cutoff = v; break;
            case "drive":  drive  = v; break;
            case "rumble": rumble = v; break;
            case "mix":    mix    = v; break;
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final float alpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * cutoff / sampleRate));
        final double subPhaseInc = 2.0 * Math.PI * 60.0 / sampleRate;
        final double twoPi = 2.0 * Math.PI;
        for (int i = 0; i < input.length; i++) {
            float x = (float) Math.tanh(input[i] * drive);
            lp1 += alpha * (x   - lp1);
            lp2 += alpha * (lp1 - lp2);
            lp3 += alpha * (lp2 - lp3);
            lp4 += alpha * (lp3 - lp4);
            // Envelope-follow the dry signal (~50 ms one-pole) so the sub
            // tracks loudness instead of constantly droning.
            float absx = Math.abs(input[i]);
            follower += 0.0015f * (absx - follower);
            float sub = (float) (Math.sin(subPhase) * follower * 4f * rumble);
            subPhase += subPhaseInc;
            if (subPhase > twoPi) subPhase -= twoPi;
            float wet = lp4 + sub;
            output[i] = wet * mix + input[i] * (1f - mix);
        }
    }
}
