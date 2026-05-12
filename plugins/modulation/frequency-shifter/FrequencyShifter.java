package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

// Frequency Shifter (native port) — direct translation of
// frequency-shifter.js. Bode-style SSB: two 8-section Niemitalo polyphase
// Hilbert allpass cascades produce a 90°-shifted I/Q pair, then a
// quadrature mixer translates every frequency component by N Hz (not by
// ratio — every partial shifts the same number of hertz, which is what
// breaks the harmonic series and gives the effect its metallic flavour).
//
// Per-sample cost: 16 allpass MACs + one sin + one cos. The JS version
// was the single most CPU-intensive plugin in this registry; in JVM
// bytecode the allpass cascade collapses to a tight inner loop the JIT
// can vectorise, and a small ring-buffer LUT can even replace sin/cos
// if needed (currently kept live for accuracy).
public final class FrequencyShifter implements VocalMonitorNativePlugin {

    private static final float[] A_SQ = {
        0.00247361031f, 0.0314082443f,  0.124170815f,   0.319773729f,
        0.554763139f,   0.752066439f,   0.890916286f,   0.961945129f
    };
    private static final float[] B_SQ = {
        0.0103096966f,  0.0671292540f,  0.214825889f,   0.453241851f,
        0.674534273f,   0.842446744f,   0.939413728f,   0.984098284f
    };

    private final float[] xA_p  = new float[8];
    private final float[] xA_pp = new float[8];
    private final float[] yA_p  = new float[8];
    private final float[] yA_pp = new float[8];
    private final float[] xB_p  = new float[8];
    private final float[] xB_pp = new float[8];
    private final float[] yB_p  = new float[8];
    private final float[] yB_pp = new float[8];

    private float phase = 0f;
    private float bInDelay = 0f;
    private int sampleRate = 44100;

    private float shift = 100f, mix = 1f;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        phase = 0f;
        bInDelay = 0f;
        for (int i = 0; i < 8; i++) {
            xA_p[i] = xA_pp[i] = yA_p[i] = yA_pp[i] = 0f;
            xB_p[i] = xB_pp[i] = yB_p[i] = yB_pp[i] = 0f;
        }
    }

    @Override
    public String[] parameterNames() { return new String[] { "shift", "mix" }; }

    @Override
    public float parameterMin(String name) {
        if ("shift".equals(name)) return -1000f;
        return 0f;
    }

    @Override
    public float parameterMax(String name) {
        if ("shift".equals(name)) return 1000f;
        return 1f;
    }

    @Override
    public float parameterDefault(String name) {
        if ("shift".equals(name)) return 100f;
        if ("mix".equals(name))   return 1f;
        return 0f;
    }

    @Override
    public String parameterLabel(String name) {
        if ("shift".equals(name)) return "Shift (Hz)";
        if ("mix".equals(name))   return "Mix";
        return name;
    }

    @Override
    public void setParameter(String name, float value) {
        if ("shift".equals(name)) shift = value;
        else if ("mix".equals(name)) mix = value;
    }

    @Override
    public void process(float[] input, float[] output) {
        final float phaseInc = (float) (2.0 * Math.PI * shift / sampleRate);
        final float mixLocal = mix;
        final float dry = 1f - mixLocal;
        final float twoPi = (float) (2.0 * Math.PI);
        float ph = phase;
        float bDelayLocal = bInDelay;

        final float[] aSq = A_SQ, bSq = B_SQ;
        final float[] xAp = xA_p, xApp = xA_pp, yAp = yA_p, yApp = yA_pp;
        final float[] xBp = xB_p, xBpp = xB_pp, yBp = yB_p, yBpp = yB_pp;

        final int n = input.length;
        for (int i = 0; i < n; i++) {
            final float x = input[i];

            // Branch A — eight 2nd-order allpasses, z⁻² form.
            float vA = x;
            for (int s = 0; s < 8; s++) {
                float y = aSq[s] * (vA + yApp[s]) - xApp[s];
                xApp[s] = xAp[s]; xAp[s] = vA;
                yApp[s] = yAp[s]; yAp[s] = y;
                vA = y;
            }

            // Branch B — same shape, input delayed by one sample.
            float vB = bDelayLocal;
            bDelayLocal = x;
            for (int s = 0; s < 8; s++) {
                float y2 = bSq[s] * (vB + yBpp[s]) - xBpp[s];
                xBpp[s] = xBp[s]; xBp[s] = vB;
                yBpp[s] = yBp[s]; yBp[s] = y2;
                vB = y2;
            }

            // SSB mixer.
            float co = (float) Math.cos(ph);
            float si = (float) Math.sin(ph);
            float wet = vA * co - vB * si;

            output[i] = x * dry + wet * mixLocal;

            ph += phaseInc;
            if (ph > twoPi) ph -= twoPi;
            else if (ph < -twoPi) ph += twoPi;
        }

        phase = ph;
        bInDelay = bDelayLocal;
    }
}
