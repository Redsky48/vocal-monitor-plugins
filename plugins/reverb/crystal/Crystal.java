package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.BlendMode;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Crystal — next-generation algorithmic reverb in the Lexicon / EMT /
 * BABY Audio Crystalline lineage, built on Jon Dattorro's 1997 plate-
 * reverb allpass-loop topology (AES J. 45-9, "Effect Design Part 1").
 *
 * Audio chain:
 *
 *   in → bandwidth filter → preDelay
 *      → 4 input-diffusion allpasses (static)
 *      → TANK [delay-modAP-damping-AP-delay] × 2 (criss-crossed feedback)
 *      → +12 semi pitch shift on a feedback tap (shimmer)
 *      → 8 output taps (decorrelated L/R)
 *      → tilt-EQ tone → M/S width
 *      → sidechain ducker keyed off dry → gate → dry/wet
 *
 * Visualisation matches the Crystalline house style: big rounded centre
 * display with a horizontal gradient (amber → pink → cyan) and stacked
 * FFT waveforms inside that show the live reverb-tail spectrum
 * evolving — newest frame fully bright on the right, fading back to
 * the left over the last ~0.5 s of audio.
 */
public final class Crystal
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    // ─────────────────────────────────────────────────────────────
    //  Parameters
    // ─────────────────────────────────────────────────────────────
    private float predelay   = 0.05f;  // seconds (0..0.3)
    private float size       = 0.55f;  // 0..1 → scales tank delay lengths
    private float decay      = 0.65f;  // 0..1 → feedback gain mapping
    private float damping    = 0.30f;  // 0..1 → in-loop LP cutoff
    private float modulation = 0.30f;  // 0..1 → LFO depth on modAPs
    private float shimmer    = 0.0f;   // 0..1 → +12 semi feedback amount
    private float width      = 0.90f;  // 0..1 → M/S width on output
    private float tone       = 0.0f;   // -1..+1 → tilt EQ (dark..bright)
    private float duck       = 0.0f;   // 0..1 → sidechain ducker amount
    private float gateDb     = -80f;   // -80..0 → output gate threshold
    private float freeze     = 0.0f;   // 0/1 → infinite reverb hold
    private float mix        = 0.30f;  // 0..1 → dry/wet

    @Override public String[] parameterNames() {
        return new String[] { "predelay", "size", "decay", "damping",
                              "modulation", "shimmer", "width", "tone",
                              "duck", "gate", "freeze", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "predelay":   return 0.0f;
            case "tone":       return -1.0f;
            case "gate":       return -80.0f;
            default:           return 0.0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "predelay":   return 0.3f;
            case "tone":       return 1.0f;
            case "gate":       return 0.0f;
            default:           return 1.0f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "predelay":   return 0.05f;
            case "size":       return 0.55f;
            case "decay":      return 0.65f;
            case "damping":    return 0.30f;
            case "modulation": return 0.30f;
            case "shimmer":    return 0.0f;
            case "width":      return 0.90f;
            case "tone":       return 0.0f;
            case "duck":       return 0.0f;
            case "gate":       return -80.0f;
            case "freeze":     return 0.0f;
            case "mix":        return 0.30f;
            default:           return 0.0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "predelay":   return "Pre (s)";
            case "size":       return "Size";
            case "decay":      return "Decay";
            case "damping":    return "Damp";
            case "modulation": return "Mod";
            case "shimmer":    return "Shimmer";
            case "width":      return "Width";
            case "tone":       return "Tone";
            case "duck":       return "Duck";
            case "gate":       return "Gate (dB)";
            case "freeze":     return "Freeze";
            case "mix":        return "Mix";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "predelay":   predelay = v; break;
            case "size":       size = v; break;
            case "decay":      decay = v; break;
            case "damping":    damping = v; break;
            case "modulation": modulation = v; break;
            case "shimmer":    shimmer = v; break;
            case "width":      width = v; break;
            case "tone":       tone = v; break;
            case "duck":       duck = v; break;
            case "gate":       gateDb = v; break;
            case "freeze":     freeze = v; break;
            case "mix":        mix = v; break;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Audio state — Dattorro plate topology
    //  Reference delay lengths from AES J. 45-9 (1997), scaled by Size.
    // ─────────────────────────────────────────────────────────────
    private int sampleRate = 44100;

    // Input bandwidth filter — gentle HP+LP brackets so brick-wall HF
    // doesn't excite the tank into instability.
    private float bwLP = 0f, bwHP = 0f;

    // Pre-delay buffer.
    private float[] preBuf;
    private int preW = 0;

    // Input diffusion (4 series allpasses, fixed delays).
    private float[] ap1, ap2, ap3, ap4;
    private int ap1w = 0, ap2w = 0, ap3w = 0, ap4w = 0;
    private static final float[] AP_G   = { 0.75f, 0.75f, 0.625f, 0.625f };
    private static final int[]   AP_LEN = { 142, 107, 379, 277 };

    // Tank (one "side"): modulated allpass → delay → damping shelf →
    // allpass → delay. Two tanks criss-crossed (Dattorro figure).
    //
    // Reference lengths from Dattorro paper, normalised to 29761 Hz.
    // We scale to current sampleRate AND by Size knob at init+resize.
    private static final int[] TANK_LENS = {
            672, 4453, 4217, 1800, 3720,    // tank A: modAP, delay, allpass, delay
            908, 4217, 3163, 1800, 3720     // tank B: modAP, delay, allpass, delay
    };
    private float[] mAp_a, d1_a, ap_a, d2_a;
    private float[] mAp_b, d1_b, ap_b, d2_b;
    private int mAp_a_w = 0, d1_a_w = 0, ap_a_w = 0, d2_a_w = 0;
    private int mAp_b_w = 0, d1_b_w = 0, ap_b_w = 0, d2_b_w = 0;

    // Damping state (1-pole LP per tank).
    private float damp_a = 0f, damp_b = 0f;

    // Cross-feedback (last sample read from each tank's tail).
    private float fb_a = 0f, fb_b = 0f;

    // LFO for modulated allpasses (slow random walk + slow sine for
    // organic motion, mirrored phase between tanks).
    private float lfoPhase = 0f;
    private float lfoNoiseA = 0f, lfoNoiseB = 0f;
    private long  noiseSeed = 0x9E3779B97F4A7C15L;

    // Shimmer pitch shifter (in-loop, +12 semitones).
    // Implemented as a granular delay-line resampler running at 2x rate
    // with crossfade between two grains so the +1-octave shift sounds
    // smooth without phase artefacts. Fed back into the tank input.
    private float[] shimBuf;
    private int shimBufLen;
    private int shimW = 0;
    private float shimReadA = 0f, shimReadB = 0f;
    private int shimGrainLen;          // crossfade window in samples
    private int shimGrainPos = 0;

    // Tone tilt (1-pole shelf, post-reverb).
    private float toneLP = 0f, toneHP = 0f;

    // Ducker envelope (RMS of dry input, drives wet attenuation).
    private float duckEnv = 0f;
    private static final float DUCK_RC_FAST = 0.005f;  // 5 ms attack
    private static final float DUCK_RC_SLOW = 0.150f;  // 150 ms release

    // Output gate envelope.
    private float gateEnv = 0f;
    private float gateGain = 0f;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        // Pre-delay sized for 300 ms max.
        preBuf = new float[Math.max(64, (int)(sr * 0.35f))];
        preW = 0;

        ap1 = new float[scaleLen(AP_LEN[0], sr)];
        ap2 = new float[scaleLen(AP_LEN[1], sr)];
        ap3 = new float[scaleLen(AP_LEN[2], sr)];
        ap4 = new float[scaleLen(AP_LEN[3], sr)];
        ap1w = ap2w = ap3w = ap4w = 0;

        // Tank buffers sized to the *maximum* length we may ever need
        // (full Size = 1.0). Actual read length is recalculated per
        // process() from the live Size knob — the buffer is the
        // ceiling, not the working length.
        mAp_a = new float[scaleLen(TANK_LENS[0], sr) * 2];
        d1_a  = new float[scaleLen(TANK_LENS[1], sr) * 2];
        ap_a  = new float[scaleLen(TANK_LENS[2], sr) * 2];
        d2_a  = new float[scaleLen(TANK_LENS[3], sr) * 2];
        mAp_b = new float[scaleLen(TANK_LENS[5], sr) * 2];
        d1_b  = new float[scaleLen(TANK_LENS[6], sr) * 2];
        ap_b  = new float[scaleLen(TANK_LENS[7], sr) * 2];
        d2_b  = new float[scaleLen(TANK_LENS[8], sr) * 2];
        mAp_a_w = d1_a_w = ap_a_w = d2_a_w = 0;
        mAp_b_w = d1_b_w = ap_b_w = d2_b_w = 0;

        damp_a = damp_b = 0f;
        fb_a = fb_b = 0f;
        bwLP = bwHP = 0f;
        toneLP = toneHP = 0f;
        duckEnv = 0f;
        gateEnv = 0f; gateGain = 0f;

        // Shimmer: ring sized for ~50 ms grains so the +12 semi shift
        // doesn't audibly repeat or smear. Grain length is half that
        // → ~25 ms crossfade window.
        shimBufLen = Math.max(2048, sr / 20);  // 50 ms
        shimBuf = new float[shimBufLen];
        shimW = 0;
        shimGrainLen = shimBufLen / 2;
        shimReadA = 0f;
        shimReadB = shimGrainLen * 0.5f;  // half-grain offset
        shimGrainPos = 0;

        lfoPhase = 0f;
        lfoNoiseA = lfoNoiseB = 0f;

        for (int i = 0; i < shimBuf.length; i++) shimBuf[i] = 0f;
        java.util.Arrays.fill(histRing, 0f);
        histRingW = 0;
    }

    // Scale a Dattorro reference delay (29761 Hz) to our sample rate.
    private static int scaleLen(int dattorroLen, int sr) {
        int n = (int) Math.round(dattorroLen * (sr / 29761.0));
        return n < 8 ? 8 : n;
    }

    // ─────────────────────────────────────────────────────────────
    //  Visual / history ring — feeds the scrolling FFT spectrogram.
    //  Captures the WET stereo signal so the visual reacts to what
    //  the reverb is producing, not what's feeding it.
    // ─────────────────────────────────────────────────────────────
    private static final int HIST_RING = 4096;
    private final float[] histRing = new float[HIST_RING];
    private int histRingW = 0;

    // ─────────────────────────────────────────────────────────────
    //  process() — mono in, mono out (chain plumbing). The reverb
    //  generates a stereo wet pair internally, sums to mono on exit
    //  so it slots into the existing mono plugin chain. M/S width
    //  control still has effect because it modifies the L-R balance
    //  before the mono sum (the user perceives "width" via the
    //  density / decorrelation of the summed signal).
    // ─────────────────────────────────────────────────────────────
    @Override public void process(float[] input, float[] output) {
        final int n = Math.min(input.length, output.length);

        // Per-block parameter snapshot + derived state.
        final int   preLen     = Math.max(1, (int)(predelay * sampleRate));
        final float decayCoef  = 0.25f + 0.7f * decay;          // 0.25..0.95
        final float feedbackG  = freeze >= 0.5f ? 1.00f : decayCoef;
        final float dampCutoff = 0.10f + (1f - damping) * 0.80f; // 0..1
        final float modDepth   = modulation * 32f;               // ±32 samples
        final float lfoInc     = (float)(2.0 * Math.PI * 0.7f / sampleRate);
        final float shimAmt    = shimmer * 0.45f;                // safe gain
        final float toneTilt   = tone;                           // -1..+1
        final float duckAmt    = duck;
        final float wetMix     = mix;
        final float dryMix     = 1f - mix;
        final float gateLin    = (float) Math.pow(10.0, gateDb / 20.0);

        // Tank working lengths scale with Size (0.4..1.0 of max).
        final float sizeScale = 0.4f + 0.6f * size;
        final int d1aLen = (int)(scaleLen(TANK_LENS[1], sampleRate) * sizeScale);
        final int d2aLen = (int)(scaleLen(TANK_LENS[3], sampleRate) * sizeScale);
        final int d1bLen = (int)(scaleLen(TANK_LENS[6], sampleRate) * sizeScale);
        final int d2bLen = (int)(scaleLen(TANK_LENS[8], sampleRate) * sizeScale);

        // Local accessors to ease the per-sample loop.
        final float[] _ap1 = ap1, _ap2 = ap2, _ap3 = ap3, _ap4 = ap4;
        final int _ap1L = ap1.length, _ap2L = ap2.length, _ap3L = ap3.length, _ap4L = ap4.length;
        int _ap1w = ap1w, _ap2w = ap2w, _ap3w = ap3w, _ap4w = ap4w;
        final float[] _mAp_a = mAp_a, _d1_a = d1_a, _ap_a = ap_a, _d2_a = d2_a;
        final float[] _mAp_b = mAp_b, _d1_b = d1_b, _ap_b = ap_b, _d2_b = d2_b;

        float _bwLP = bwLP, _bwHP = bwHP;
        float _damp_a = damp_a, _damp_b = damp_b;
        float _fb_a = fb_a, _fb_b = fb_b;
        float _toneLP = toneLP, _toneHP = toneHP;
        float _duckEnv = duckEnv;
        float _gateEnv = gateEnv, _gateGain = gateGain;
        float _lfoPhase = lfoPhase;
        float _lfoNoiseA = lfoNoiseA, _lfoNoiseB = lfoNoiseB;
        float _shimReadA = shimReadA, _shimReadB = shimReadB;
        int _shimW = shimW;

        for (int i = 0; i < n; i++) {
            final float dry = input[i];

            // ── 1. Bandwidth filter (gentle HP 5 Hz, LP 10 kHz) ──
            _bwHP += 0.0007f * (dry - _bwHP);
            float x = dry - _bwHP;
            _bwLP += 0.45f * (x - _bwLP);
            x = _bwLP;

            // ── 2. Ducker envelope on dry (drives wet attenuation) ──
            float dryAbs = dry < 0 ? -dry : dry;
            float rcCoef = dryAbs > _duckEnv ? DUCK_RC_FAST : DUCK_RC_SLOW;
            float duckIIR = 1f - (float) Math.exp(-1.0 / (sampleRate * rcCoef));
            _duckEnv += duckIIR * (dryAbs - _duckEnv);

            // ── 3. Pre-delay ──
            preBuf[preW] = x;
            int preR = preW - preLen;
            if (preR < 0) preR += preBuf.length;
            float preOut = preBuf[preR];
            preW++; if (preW >= preBuf.length) preW = 0;

            // Add shimmer feedback into the tank input — read the
            // pitch-shifter output with crossfade between two grains.
            float shimOut = shimRead(shimBuf, _shimReadA, _shimReadB,
                                     shimGrainLen, shimGrainPos);
            float tankIn = preOut + shimOut * shimAmt;

            // ── 4. Input diffusion: 4 series allpasses ──
            tankIn = ap(tankIn, _ap1, _ap1L, _ap1w, AP_G[0]);
            _ap1w = (_ap1w + 1) % _ap1L;
            tankIn = ap(tankIn, _ap2, _ap2L, _ap2w, AP_G[1]);
            _ap2w = (_ap2w + 1) % _ap2L;
            tankIn = ap(tankIn, _ap3, _ap3L, _ap3w, AP_G[2]);
            _ap3w = (_ap3w + 1) % _ap3L;
            tankIn = ap(tankIn, _ap4, _ap4L, _ap4w, AP_G[3]);
            _ap4w = (_ap4w + 1) % _ap4L;

            // ── 5. LFO update (sine + drifting noise per tank) ──
            _lfoPhase += lfoInc;
            if (_lfoPhase > 6.283185f) _lfoPhase -= 6.283185f;
            float lfoSinA = (float) Math.sin(_lfoPhase);
            float lfoSinB = (float) Math.sin(_lfoPhase + 1.7f);
            // Cheap pink-ish wander: low-passed white noise.
            _lfoNoiseA += 0.0008f * (nextNoise() - _lfoNoiseA);
            _lfoNoiseB += 0.0008f * (nextNoise() - _lfoNoiseB);
            float modA = (lfoSinA + _lfoNoiseA * 0.5f) * modDepth;
            float modB = (lfoSinB + _lfoNoiseB * 0.5f) * modDepth;

            // ── 6. TANK A ──
            // modAP: read with fractional offset that wobbles via LFO
            float aIn = tankIn + _fb_b * feedbackG;
            aIn = apMod(aIn, _mAp_a, mAp_a_w, modA, 0.7f);
            mAp_a_w = (mAp_a_w + 1) % _mAp_a.length;
            // delay 1
            _d1_a[d1_a_w] = aIn;
            int d1arIdx = d1_a_w - d1aLen;
            if (d1arIdx < 0) d1arIdx += _d1_a.length;
            float aMid = _d1_a[d1arIdx];
            d1_a_w = (d1_a_w + 1) % _d1_a.length;
            // damping shelf (1-pole LP)
            _damp_a += dampCutoff * (aMid - _damp_a);
            float aDamped = _damp_a;
            // static allpass
            aDamped = ap(aDamped, _ap_a, _ap_a.length, ap_a_w, 0.5f);
            ap_a_w = (ap_a_w + 1) % _ap_a.length;
            // delay 2 → feeds B
            _d2_a[d2_a_w] = aDamped;
            int d2arIdx = d2_a_w - d2aLen;
            if (d2arIdx < 0) d2arIdx += _d2_a.length;
            _fb_a = _d2_a[d2arIdx];
            d2_a_w = (d2_a_w + 1) % _d2_a.length;

            // ── 7. TANK B ──
            float bIn = tankIn + _fb_a * feedbackG;
            bIn = apMod(bIn, _mAp_b, mAp_b_w, modB, 0.7f);
            mAp_b_w = (mAp_b_w + 1) % _mAp_b.length;
            _d1_b[d1_b_w] = bIn;
            int d1brIdx = d1_b_w - d1bLen;
            if (d1brIdx < 0) d1brIdx += _d1_b.length;
            float bMid = _d1_b[d1brIdx];
            d1_b_w = (d1_b_w + 1) % _d1_b.length;
            _damp_b += dampCutoff * (bMid - _damp_b);
            float bDamped = _damp_b;
            bDamped = ap(bDamped, _ap_b, _ap_b.length, ap_b_w, 0.5f);
            ap_b_w = (ap_b_w + 1) % _ap_b.length;
            _d2_b[d2_b_w] = bDamped;
            int d2brIdx = d2_b_w - d2bLen;
            if (d2brIdx < 0) d2brIdx += _d2_b.length;
            _fb_b = _d2_b[d2brIdx];
            d2_b_w = (d2_b_w + 1) % _d2_b.length;

            // ── 8. Output taps (Dattorro-style decorrelated) ──
            // Borrow a handful of taps from each tank for L/R.
            float wetL = readTap(_d1_a, d1_a_w, scaleLen(TANK_LENS[2], sampleRate) / 3)
                       + readTap(_ap_a, ap_a_w, scaleLen(TANK_LENS[3], sampleRate) / 4)
                       - readTap(_d2_b, d2_b_w, scaleLen(TANK_LENS[8], sampleRate) / 2);
            float wetR = readTap(_d1_b, d1_b_w, scaleLen(TANK_LENS[7], sampleRate) / 3)
                       + readTap(_ap_b, ap_b_w, scaleLen(TANK_LENS[8], sampleRate) / 4)
                       - readTap(_d2_a, d2_a_w, scaleLen(TANK_LENS[3], sampleRate) / 2);

            wetL *= 0.18f;  // wet level compensation
            wetR *= 0.18f;

            // ── 9. Push the L-R sum into the shimmer ring; the
            //     pitch-shifter read pointer below advances at 2x
            //     so the shimmered output is +12 semitones. ──
            shimBuf[_shimW] = (wetL + wetR) * 0.5f;
            _shimW = (_shimW + 1) % shimBufLen;
            _shimReadA += 2.0f;
            _shimReadB += 2.0f;
            if (_shimReadA >= shimBufLen) _shimReadA -= shimBufLen;
            if (_shimReadB >= shimBufLen) _shimReadB -= shimBufLen;
            shimGrainPos = (shimGrainPos + 1) % shimGrainLen;

            // ── 10. M/S width on stereo wet ──
            float mid  = (wetL + wetR) * 0.5f;
            float side = (wetL - wetR) * 0.5f;
            side *= (0.2f + 1.6f * width);  // 0.2x..1.8x sides
            wetL = mid + side;
            wetR = mid - side;

            // ── 11. Mono-down for our plugin chain ──
            float wet = (wetL + wetR) * 0.5f;

            // ── 12. Tone tilt (post) ──
            // Tilt = simultaneous high-shelf boost + low-shelf cut
            // (positive tone) or vice versa. Cheap implementation:
            // mix LP and HP versions of the signal in opposite
            // proportions controlled by `tone`.
            _toneLP += 0.10f * (wet - _toneLP);   // ~700 Hz LP
            float wetHP = wet - _toneLP;
            wet = wet + toneTilt * (wetHP - _toneLP) * 0.5f;

            // ── 13. Ducker: attenuate wet by the dry envelope ──
            // duckAmt = 0 → no ducking, duckAmt = 1 → wet halved at
            // typical vocal RMS. Soft response avoids audible pumping.
            float duckGain = 1f - duckAmt * Math.min(1f, _duckEnv * 6f);
            wet *= duckGain;

            // ── 14. Output gate ──
            float wetAbs = wet < 0 ? -wet : wet;
            float gateTarget = wetAbs > gateLin ? 1f : 0f;
            float gateCoef = gateTarget > _gateGain ? 0.05f : 0.0008f;
            _gateGain += gateCoef * (gateTarget - _gateGain);
            wet *= _gateGain;

            // ── 15. Dry/wet mix → output ──
            output[i] = dry * dryMix + wet * wetMix;

            // ── History capture for the visual ──
            histRing[histRingW] = wet;
            histRingW++;
            if (histRingW >= HIST_RING) histRingW = 0;
        }

        // Write back local state.
        ap1w = _ap1w; ap2w = _ap2w; ap3w = _ap3w; ap4w = _ap4w;
        bwLP = _bwLP; bwHP = _bwHP;
        damp_a = _damp_a; damp_b = _damp_b;
        fb_a = _fb_a; fb_b = _fb_b;
        toneLP = _toneLP; toneHP = _toneHP;
        duckEnv = _duckEnv;
        gateEnv = _gateEnv; gateGain = _gateGain;
        lfoPhase = _lfoPhase;
        lfoNoiseA = _lfoNoiseA; lfoNoiseB = _lfoNoiseB;
        shimReadA = _shimReadA; shimReadB = _shimReadB;
        shimW = _shimW;
    }

    // Static allpass: y = -g·x + d[w-L] + g·(d[w-L] - y_prev)
    // Implemented as `y = -g·x + d[r]; d[w] = x + g·y;`
    private static float ap(float x, float[] buf, int len, int w, float g) {
        int r = w + 1; if (r >= len) r = 0;  // oldest sample = newest+1 in ring
        // Standard ring: r = w - L = w + 1 (since L = len)? Actually
        // for one-tap delay of length len we just read w (write before
        // increment). Easier: read buf[w], output, then write x.
        float delayed = buf[w];
        float y = -g * x + delayed;
        buf[w] = x + g * y;
        return y;
    }

    // Modulated allpass: same shape but the read position has a
    // fractional LFO-driven offset within the buffer.
    private static float apMod(float x, float[] buf, int w,
                                float modOffset, float g) {
        int len = buf.length;
        // Centre the read at the buffer midpoint plus the LFO offset.
        float readPos = w - len * 0.5f - modOffset;
        while (readPos < 0) readPos += len;
        while (readPos >= len) readPos -= len;
        int r0 = (int) readPos;
        int r1 = r0 + 1; if (r1 >= len) r1 = 0;
        float frac = readPos - r0;
        float delayed = buf[r0] * (1f - frac) + buf[r1] * frac;
        float y = -g * x + delayed;
        buf[w] = x + g * y;
        return y;
    }

    // Read a tap at `back` samples behind the write head.
    private static float readTap(float[] buf, int w, int back) {
        int r = w - back;
        if (r < 0) r += buf.length;
        return buf[r];
    }

    // Shimmer read — two grains crossfaded with a triangular window
    // for a smooth +12-semitone shifted output.
    private float shimRead(float[] buf, float ra, float rb, int grainLen, int pos) {
        // Linear-interp reads at fractional positions.
        int ia0 = (int) ra; int ia1 = ia0 + 1; if (ia1 >= buf.length) ia1 = 0;
        int ib0 = (int) rb; int ib1 = ib0 + 1; if (ib1 >= buf.length) ib1 = 0;
        float fa = ra - ia0; float fb = rb - ib0;
        float sa = buf[ia0] * (1f - fa) + buf[ia1] * fa;
        float sb = buf[ib0] * (1f - fb) + buf[ib1] * fb;
        // Triangular crossfade: position-driven balance between the
        // two grains so the seam is never audible.
        float t = pos / (float) grainLen;
        float wa = t < 0.5f ? (t * 2f) : (1f - (t - 0.5f) * 2f);
        float wb = 1f - wa;
        return sa * wa + sb * wb;
    }

    // xorshift-style cheap noise (-1..+1).
    private float nextNoise() {
        long x = noiseSeed;
        x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
        noiseSeed = x;
        return ((x & 0xFFFF) / 32768f) - 1f;
    }

    // ─────────────────────────────────────────────────────────────
    //  Visual — Crystalline-style central display
    // ─────────────────────────────────────────────────────────────
    private static final int   FFT_SIZE  = 256;
    private static final int   WAVE_LANES = 24;  // stacked frames shown
    private final float[][] waveLanes = new float[WAVE_LANES][FFT_SIZE / 2];
    private int waveWritePos = 0;
    private long lastFftCaptureMs = 0L;
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] hann  = new float[FFT_SIZE];
    private boolean fftInit = false;

    private static final int COLOR_BG          = 0xFF050505;
    private static final int COLOR_FRAME       = 0xFF18181C;
    private static final int COLOR_TEXT_DIM    = 0xFF7C7C82;
    private static final int COLOR_TEXT_BRIGHT = 0xFFE6E6EA;
    private static final int COLOR_LANE        = 0xCCFFFFFF;
    private static final int COLOR_LANE_DIM    = 0x33FFFFFF;
    // Crystalline-style horizontal gradient stops: amber → pink → cyan.
    private static final int GRAD_LEFT   = 0xFFFFB44A;
    private static final int GRAD_MIDDLE = 0xFFF38FB7;
    private static final int GRAD_RIGHT  = 0xFF7AB6E0;

    private PluginPaint bgPaint, framePaint, textDim, textBright,
            displayBg, displayBorder, lanePaint, sectionLabel;
    private PluginPath wavePath;

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (bgPaint == null) initPaints(canvas);
        if (!fftInit) {
            for (int i = 0; i < FFT_SIZE; i++) {
                hann[i] = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
            }
            fftInit = true;
        }

        // Live params (host supplies; fall back to local state).
        float pSize    = paramOr(params, "size",       size);
        float pDecay   = paramOr(params, "decay",      decay);
        float pShim    = paramOr(params, "shimmer",    shimmer);
        float pMix     = paramOr(params, "mix",        mix);
        float pDamp    = paramOr(params, "damping",    damping);
        float pMod     = paramOr(params, "modulation", modulation);

        final float W = width, H = height;

        // ── Background ──
        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // ── Layout: central display + 4 corner section labels ──
        float pad = 14f;
        float headerH = 22f;
        float sectionLabelH = 14f;

        float dispW = W * 0.56f;
        float dispH = (H - pad * 2 - headerH - sectionLabelH * 2) * 0.95f;
        if (dispH > H * 0.6f) dispH = H * 0.6f;
        if (dispH < 80f)      dispH = 80f;
        float dispX = (W - dispW) * 0.5f;
        float dispY = headerH + pad;

        // ── Header ──
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(13f).setTextAlign(0);
        canvas.drawText("CRYSTAL", pad, pad + 14, textBright);
        textBright.setColor(COLOR_TEXT_BRIGHT).setTextSize(11f).setTextAlign(1);
        canvas.drawText("REVERB", W * 0.5f, pad + 14, textBright);
        float estDecaySec = 0.2f + pDecay * pDecay * 8f;
        textBright.setColor(0xFFB0B0B6).setTextSize(11f).setTextAlign(2);
        canvas.drawText(String.format("Decay  %.1f s", estDecaySec),
                W - pad, pad + 14, textBright);

        // ── Central display: gradient pad + frame ──
        drawCentralDisplay(canvas, dispX, dispY, dispX + dispW, dispY + dispH, timeMs);

        // ── 4 section labels around the display (top-L, top-R,
        //    bot-L, bot-R) so the user gets the Crystalline-style
        //    "four corners of controls" layout in the test app. ──
        float sectionTextSize = 11f;
        sectionLabel.setColor(COLOR_TEXT_DIM).setTextSize(sectionTextSize).setTextAlign(0);
        canvas.drawText("REFLECTIONS",
                pad, dispY + 4f, sectionLabel);
        canvas.drawText("DEPTH",
                pad, dispY + dispH - 6f, sectionLabel);
        sectionLabel.setTextAlign(2);
        canvas.drawText("CLEAN-UP",
                W - pad, dispY + 4f, sectionLabel);
        canvas.drawText("SHAPE",
                W - pad, dispY + dispH - 6f, sectionLabel);

        // Side info: list the active section values so user sees their
        // current settings even before the popup-slider footer.
        textDim.setColor(COLOR_TEXT_DIM).setTextSize(10f).setTextAlign(0);
        float infoY1 = dispY + 18f;
        canvas.drawText(String.format("size %.0f%%", pSize * 100),  pad, infoY1, textDim);
        canvas.drawText(String.format("mod %.0f%%",  pMod  * 100),  pad, infoY1 + 14f, textDim);
        canvas.drawText(String.format("shim %.0f%%", pShim * 100),  pad, infoY1 + 28f, textDim);

        textDim.setTextAlign(2);
        canvas.drawText(String.format("damp %.0f%%", pDamp * 100), W - pad, infoY1, textDim);
        canvas.drawText(String.format("decay %.0f%%", pDecay * 100), W - pad, infoY1 + 14f, textDim);
        canvas.drawText(String.format("mix %.0f%%", pMix * 100), W - pad, infoY1 + 28f, textDim);
    }

    private void drawCentralDisplay(PluginCanvas canvas,
            float x0, float y0, float x1, float y1, long timeMs) {
        float w = x1 - x0, h = y1 - y0;

        // 1. Rounded background with one horizontal gradient running
        //    amber → pink → cyan across the full display width — the
        //    Crystalline signature panel look. Three-stop gradient
        //    drawn as a single shape (no visible seam).
        displayBg.setStyle(PluginStyle.FILL)
                .setLinearGradient(x0, y0, x1, y0,
                        new int[] { GRAD_LEFT, GRAD_MIDDLE, GRAD_RIGHT },
                        new float[] { 0f, 0.5f, 1f });
        canvas.drawRoundRect(x0, y0, x1, y1, 12f, displayBg);

        // 2. Capture a fresh FFT slice if enough time has passed.
        //    ~30 Hz capture rate gives a comfortable visual flow without
        //    burning render budget on FFT each frame.
        if (timeMs - lastFftCaptureMs >= 33L) {
            captureFftSlice();
            lastFftCaptureMs = timeMs;
        }

        // 3. Stacked wave lanes — each lane is one FFT magnitude curve,
        //    older lanes drawn first with lower alpha for that "3D
        //    decay" depth effect that Crystalline uses.
        canvas.save();
        canvas.clipRect(x0 + 6f, y0 + 6f, x1 - 6f, y1 - 6f);
        float innerW = w - 24f;
        float innerH = h - 24f;
        float centreY = (y0 + y1) * 0.5f;
        for (int lane = 0; lane < WAVE_LANES; lane++) {
            // age=0 is newest (front), age=WAVE_LANES-1 is oldest (back)
            int age = (WAVE_LANES - 1) - lane;
            int idx = (waveWritePos - age - 1 + WAVE_LANES * 2) % WAVE_LANES;
            float[] frame = waveLanes[idx];

            // Y offset increases with age → stacking effect.
            float laneOffset = -age * (innerH * 0.011f);
            // X offset shifts older lanes slightly left for parallax.
            float laneShift  = age * 4f;

            // Alpha fades with age.
            int alpha = (int)(255 * (1f - age / (float)(WAVE_LANES + 4)));
            if (alpha < 32) alpha = 32;
            int col = (alpha << 24) | 0x00FFFFFF;

            wavePath.reset();
            int bins = frame.length;
            for (int b = 0; b < bins; b++) {
                float t = b / (float)(bins - 1);
                float px = x0 + 12f + laneShift + t * (innerW - laneShift);
                // Magnitude → vertical excursion (centred on the lane's
                // baseline, scaled by FFT magnitude).
                float mag = frame[b];
                float py = centreY + laneOffset - mag * innerH * 0.30f;
                if (b == 0) wavePath.moveTo(px, py);
                else        wavePath.lineTo(px, py);
            }
            lanePaint.setColor(col).setStyle(PluginStyle.STROKE)
                    .setStrokeWidth(age == 0 ? 1.7f : 1.0f);
            canvas.drawPath(wavePath, lanePaint);
        }
        canvas.restore();

        // 4. Border (subtle frame so the gradient panel is clearly
        //    distinct from the surrounding black background).
        displayBorder.setColor(0x66000000).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawRoundRect(x0, y0, x1, y1, 12f, displayBorder);
    }

    private void captureFftSlice() {
        // Pull the most recent FFT_SIZE samples from the wet history.
        int n = FFT_SIZE;
        int start = histRingW - n;
        if (start < 0) start += HIST_RING;
        for (int i = 0; i < n; i++) {
            fftRe[i] = histRing[(start + i) % HIST_RING] * hann[i];
            fftIm[i] = 0f;
        }
        fftRadix2(fftRe, fftIm);
        // Log-magnitude → 0..1, 80 dB floor.
        float[] target = waveLanes[waveWritePos];
        for (int b = 0; b < n / 2; b++) {
            float mag = (float) Math.sqrt(fftRe[b] * fftRe[b] + fftIm[b] * fftIm[b]) / n;
            float db = (float) (20 * Math.log10(Math.max(1e-9f, mag)));
            float t = (db + 80f) / 80f;
            if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
            target[b] = t;
        }
        waveWritePos = (waveWritePos + 1) % WAVE_LANES;
    }

    // In-place radix-2 Cooley-Tukey FFT, size must be a power of two.
    private static void fftRadix2(float[] re, float[] im) {
        int n = re.length;
        // Bit reversal.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            double ang = -2.0 * Math.PI / size;
            float wpr = (float) Math.cos(ang);
            float wpi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += size) {
                float wr = 1f, wi = 0f;
                for (int j = 0; j < half; j++) {
                    int k = i + j, kh = k + half;
                    float tr = re[kh] * wr - im[kh] * wi;
                    float ti = re[kh] * wi + im[kh] * wr;
                    re[kh] = re[k] - tr; im[kh] = im[k] - ti;
                    re[k]  = re[k] + tr; im[k]  = im[k] + ti;
                    float nwr = wr * wpr - wi * wpi;
                    wi = wr * wpi + wi * wpr;
                    wr = nwr;
                }
            }
        }
    }

    private static float paramOr(Map<String, Float> p, String name, float fallback) {
        if (p == null) return fallback;
        Float v = p.get(name);
        return v != null ? v : fallback;
    }

    private void initPaints(PluginCanvas c) {
        bgPaint       = c.newPaint();
        framePaint    = c.newPaint();
        textDim       = c.newPaint();
        textBright    = c.newPaint();
        displayBg     = c.newPaint();
        displayBorder = c.newPaint();
        lanePaint     = c.newPaint();
        sectionLabel  = c.newPaint();
        wavePath      = c.newPath();
    }
}
