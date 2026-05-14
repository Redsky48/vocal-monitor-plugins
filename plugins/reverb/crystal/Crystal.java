package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.BlendMode;
import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Crystal — next-generation algorithmic reverb in the Lexicon / EMT /
 * BABY Audio Crystalline lineage, built on Jon Dattorro's 1997 plate-
 * reverb allpass-loop topology (AES J. 45-9, "Effect Design Part 1").
 *
 * Canvas-mode UI with the host auto-sliders suppressed: every control
 * is drawn directly on the canvas (knobs, freeze toggle, dry/wet
 * slider) and the user grabs them with onTouchDown / Move / Up.
 * Parameter changes are pushed back to the audio engine via the
 * PluginHost callback handed in by setHost(). Matches the Crystalline
 * layout — left column REFLECTIONS + DEPTH, centre gradient FFT
 * display, right column CLEAN-UP + SHAPE, bottom OUTPUT row.
 */
public final class Crystal
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    // ─────────────────────────────────────────────────────────────
    //  Parameters
    // ─────────────────────────────────────────────────────────────
    // Parameters mirror BABY Audio Crystalline's full control set:
    //   12 side knobs (REFLECTIONS / DEPTH / CLEAN-UP / SHAPE) +
    //   6 centre controls (START / END / DUCK / REV / FRZ / DRY-WET).
    private float size       = 0.55f;
    private float sparkle    = 0.30f;   // NEW: HF emphasis in loop
    private float width      = 0.90f;
    private float resolution = 0.80f;   // NEW: algorithm complexity
    private float modulation = 0.30f;
    private float shimmer    = 0.0f;
    private float damping    = 0.30f;
    private float sides      = 0.0f;    // NEW: HP on stereo S channel
    private float gateDb     = -80f;
    private float tone       = 0.0f;
    private float smoothing  = 0.0f;    // NEW: notch EQ for resonances
    private float warp       = 0.0f;    // NEW: transient shaper on input (bipolar)
    private float predelay   = 0.05f;   // = START
    private float decay      = 0.65f;   // = END
    private float duck       = 0.0f;
    private float rev        = 0.0f;    // NEW: reverse reverb toggle
    private float freeze     = 0.0f;
    private float mix        = 0.30f;

    @Override public String[] parameterNames() {
        return new String[] {
            "size", "sparkle", "width",
            "resolution", "modulation", "shimmer",
            "damping", "sides", "gate",
            "tone", "smoothing", "warp",
            "predelay", "decay", "duck", "rev", "freeze", "mix"
        };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "predelay": return 0.0f;
            case "tone":     return -1.0f;
            case "warp":     return -1.0f;
            case "gate":     return -80.0f;
            default:         return 0.0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "predelay": return 0.3f;
            case "tone":     return 1.0f;
            case "warp":     return 1.0f;
            case "gate":     return 0.0f;
            default:         return 1.0f;
        }
    }
    @Override public float parameterDefault(String n) {
        switch (n) {
            case "size":       return 0.55f;
            case "sparkle":    return 0.30f;
            case "width":      return 0.90f;
            case "resolution": return 0.80f;
            case "modulation": return 0.30f;
            case "shimmer":    return 0.0f;
            case "damping":    return 0.30f;
            case "sides":      return 0.0f;
            case "gate":       return -80.0f;
            case "tone":       return 0.0f;
            case "smoothing":  return 0.0f;
            case "warp":       return 0.0f;
            case "predelay":   return 0.05f;
            case "decay":      return 0.65f;
            case "duck":       return 0.0f;
            case "rev":        return 0.0f;
            case "freeze":     return 0.0f;
            case "mix":        return 0.30f;
            default:           return 0.0f;
        }
    }
    @Override public String parameterLabel(String n) {
        switch (n) {
            case "size":       return "Size";
            case "sparkle":    return "Sparkle";
            case "width":      return "Width";
            case "resolution": return "Resolution";
            case "modulation": return "Mod";
            case "shimmer":    return "Shimmer";
            case "damping":    return "Damp";
            case "sides":      return "Sides";
            case "gate":       return "Gate (dB)";
            case "tone":       return "Tone";
            case "smoothing":  return "Smoothing";
            case "warp":       return "Warp";
            case "predelay":   return "Pre (s)";
            case "decay":      return "Decay";
            case "duck":       return "Duck";
            case "rev":        return "Reverse";
            case "freeze":     return "Freeze";
            case "mix":        return "Mix";
            default:           return n;
        }
    }
    @Override public void setParameter(String n, float v) {
        switch (n) {
            case "size":       size = v; break;
            case "sparkle":    sparkle = v; break;
            case "width":      width = v; break;
            case "resolution": resolution = v; break;
            case "modulation": modulation = v; break;
            case "shimmer":    shimmer = v; break;
            case "damping":    damping = v; break;
            case "sides":      sides = v; break;
            case "gate":       gateDb = v; break;
            case "tone":       tone = v; break;
            case "smoothing":  smoothing = v; break;
            case "warp":       warp = v; break;
            case "predelay":   predelay = v; break;
            case "decay":      decay = v; break;
            case "duck":       duck = v; break;
            case "rev":        rev = v; break;
            case "freeze":     freeze = v; break;
            case "mix":        mix = v; break;
        }
    }

    private float getParameterValue(String n) {
        switch (n) {
            case "size":       return size;
            case "sparkle":    return sparkle;
            case "width":      return width;
            case "resolution": return resolution;
            case "modulation": return modulation;
            case "shimmer":    return shimmer;
            case "damping":    return damping;
            case "sides":      return sides;
            case "gate":       return gateDb;
            case "tone":       return tone;
            case "smoothing":  return smoothing;
            case "warp":       return warp;
            case "predelay":   return predelay;
            case "decay":      return decay;
            case "duck":       return duck;
            case "rev":        return rev;
            case "freeze":     return freeze;
            case "mix":        return mix;
            default:           return 0f;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Audio state — Dattorro plate topology
    // ─────────────────────────────────────────────────────────────
    private int sampleRate = 44100;
    private float bwLP = 0f, bwHP = 0f;
    private float[] preBuf;
    private int preW = 0;
    private float[] ap1, ap2, ap3, ap4;
    private int ap1w = 0, ap2w = 0, ap3w = 0, ap4w = 0;
    private static final float[] AP_G   = { 0.75f, 0.75f, 0.625f, 0.625f };
    private static final int[]   AP_LEN = { 142, 107, 379, 277 };
    private static final int[] TANK_LENS = {
            672, 4453, 4217, 1800, 3720,
            908, 4217, 3163, 1800, 3720
    };
    private float[] mAp_a, d1_a, ap_a, d2_a;
    private float[] mAp_b, d1_b, ap_b, d2_b;
    private int mAp_a_w = 0, d1_a_w = 0, ap_a_w = 0, d2_a_w = 0;
    private int mAp_b_w = 0, d1_b_w = 0, ap_b_w = 0, d2_b_w = 0;
    private float damp_a = 0f, damp_b = 0f;
    private float fb_a = 0f, fb_b = 0f;
    private float lfoPhase = 0f;
    private float lfoNoiseA = 0f, lfoNoiseB = 0f;
    private long  noiseSeed = 0x9E3779B97F4A7C15L;
    private float[] shimBuf;
    private int shimBufLen;
    private int shimW = 0;
    private float shimReadA = 0f, shimReadB = 0f;
    private int shimGrainLen;
    private int shimGrainPos = 0;
    private float toneLP = 0f, toneHP = 0f;
    private float duckEnv = 0f;
    private static final float DUCK_RC_FAST = 0.005f;
    private static final float DUCK_RC_SLOW = 0.150f;
    private float gateEnv = 0f;
    private float gateGain = 0f;

    // ── NEW Crystalline parameters DSP state ─────────────────────
    // Sparkle: 1-pole HP per tank pulls out HF from feedback so we
    // can mix it back in with positive gain — boosts shimmer-like
    // brightness IN the loop, not just on output.
    private float sparkleHP_a = 0f, sparkleHP_b = 0f;
    // Sides: 1-pole HP on the stereo S channel after M/S split.
    private float sidesHP = 0f;
    // Smoothing: state for a notch biquad at the algorithm's
    // characteristic resonance band (~2.5 kHz).
    private final float[] smoothState = new float[4];   // x1,x2,y1,y2
    // Warp: fast/slow envelope followers for transient detection.
    private float warpFastEnv = 0f, warpSlowEnv = 0f;
    // Reverse: ring buffer of recent wet output to read backwards.
    private float[] revBuf;
    private int revBufLen, revBufW = 0;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        preBuf = new float[Math.max(64, (int)(sr * 0.35f))];
        preW = 0;
        ap1 = new float[scaleLen(AP_LEN[0], sr)];
        ap2 = new float[scaleLen(AP_LEN[1], sr)];
        ap3 = new float[scaleLen(AP_LEN[2], sr)];
        ap4 = new float[scaleLen(AP_LEN[3], sr)];
        ap1w = ap2w = ap3w = ap4w = 0;
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
        shimBufLen = Math.max(2048, sr / 20);
        shimBuf = new float[shimBufLen];
        shimW = 0;
        shimGrainLen = shimBufLen / 2;
        shimReadA = 0f;
        shimReadB = shimGrainLen * 0.5f;
        shimGrainPos = 0;
        lfoPhase = 0f;
        lfoNoiseA = lfoNoiseB = 0f;
        for (int i = 0; i < shimBuf.length; i++) shimBuf[i] = 0f;
        java.util.Arrays.fill(histRing, 0f);
        histRingW = 0;
        sparkleHP_a = sparkleHP_b = 0f;
        sidesHP = 0f;
        java.util.Arrays.fill(smoothState, 0f);
        warpFastEnv = warpSlowEnv = 0f;
        // Reverse ring sized for ~400 ms of wet content — enough for
        // the most dramatic reverse swell without exploding RAM.
        revBufLen = Math.max(1024, (int)(sr * 0.4f));
        revBuf = new float[revBufLen];
        revBufW = 0;
    }

    private static int scaleLen(int dattorroLen, int sr) {
        int n = (int) Math.round(dattorroLen * (sr / 29761.0));
        return n < 8 ? 8 : n;
    }

    private static final int HIST_RING = 4096;
    private final float[] histRing = new float[HIST_RING];
    private int histRingW = 0;

    @Override public void process(float[] input, float[] output) {
        final int n = Math.min(input.length, output.length);
        final int   preLen     = Math.max(1, (int)(predelay * sampleRate));
        final float decayCoef  = 0.25f + 0.7f * decay;
        final float feedbackG  = freeze >= 0.5f ? 1.00f : decayCoef;
        final float dampCutoff = 0.10f + (1f - damping) * 0.80f;
        final float modDepth   = modulation * 32f;
        final float lfoInc     = (float)(2.0 * Math.PI * 0.7f / sampleRate);
        final float shimAmt    = shimmer * 0.45f;
        final float toneTilt   = tone;
        final float duckAmt    = duck;
        final float wetMix     = mix;
        final float dryMix     = 1f - mix;
        final float gateLin    = (float) Math.pow(10.0, gateDb / 20.0);
        final float sizeScale = 0.4f + 0.6f * size;
        // NEW Crystalline params:
        final float sparkleHpCoef = 0.45f;          // ~3 kHz HP for sparkle tap
        final float sparkleAmt    = sparkle * 0.30f;
        // Resolution scales the input-diffusion AP gains.  Higher RES
        // = full strength APs (cleaner / denser). Lower RES = weaker
        // diffusion (more colored).
        final float resScale      = 0.35f + 0.65f * resolution;
        // Sides: HP cutoff on stereo S channel — at 1 the cutoff is
        // around 500 Hz, at 0 essentially flat.
        final float sidesCoef     = 0.005f + sides * 0.18f;
        // Smoothing: peaking biquad at 2.5 kHz with negative gain.
        final float smoothCoefs[] = smoothing > 0.001f
                ? peakingBiquad(2500f, 1.2f, -smoothing * 9f, sampleRate)
                : null;
        // Warp: bipolar transient gain. +warp boosts attacks, -warp
        // softens them.  Computed per-sample below.
        final float warpAmt       = warp;
        final float revOn         = rev >= 0.5f ? 1f : 0f;
        final boolean revActive   = revOn > 0.5f;
        final int d1aLen = (int)(scaleLen(TANK_LENS[1], sampleRate) * sizeScale);
        final int d2aLen = (int)(scaleLen(TANK_LENS[3], sampleRate) * sizeScale);
        final int d1bLen = (int)(scaleLen(TANK_LENS[6], sampleRate) * sizeScale);
        final int d2bLen = (int)(scaleLen(TANK_LENS[8], sampleRate) * sizeScale);
        final float[] _ap1 = ap1, _ap2 = ap2, _ap3 = ap3, _ap4 = ap4;
        final int _ap1L = ap1.length, _ap2L = ap2.length, _ap3L = ap3.length, _ap4L = ap4.length;
        int _ap1w = ap1w, _ap2w = ap2w, _ap3w = ap3w, _ap4w = ap4w;
        final float[] _mAp_a = mAp_a, _d1_a = d1_a, _ap_a = ap_a, _d2_a = d2_a;
        final float[] _mAp_b = mAp_b, _d1_b = d1_b, _ap_b = ap_b, _d2_b = d2_b;
        float _bwLP = bwLP, _bwHP = bwHP;
        float _damp_a = damp_a, _damp_b = damp_b;
        float _fb_a = fb_a, _fb_b = fb_b;
        float _toneLP = toneLP;
        float _duckEnv = duckEnv;
        float _gateGain = gateGain;
        float _lfoPhase = lfoPhase;
        float _lfoNoiseA = lfoNoiseA, _lfoNoiseB = lfoNoiseB;
        float _shimReadA = shimReadA, _shimReadB = shimReadB;
        int _shimW = shimW;
        float _sparkleHP_a = sparkleHP_a, _sparkleHP_b = sparkleHP_b;
        float _sidesHP = sidesHP;
        float _warpFastEnv = warpFastEnv, _warpSlowEnv = warpSlowEnv;
        final float warpFastCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.0007));
        final float warpSlowCoef = 1f - (float) Math.exp(-1.0 / (sampleRate * 0.030));
        for (int i = 0; i < n; i++) {
            final float dry = input[i];
            _bwHP += 0.0007f * (dry - _bwHP);
            float x = dry - _bwHP;
            _bwLP += 0.45f * (x - _bwLP);
            x = _bwLP;

            // ── WARP: transient shaper on the wet-send path.
            // Detect transients via fast/slow envelope ratio. Boost
            // (warp > 0) or duck (warp < 0) attacks before they enter
            // the reverb tank.
            float xAbs = x < 0 ? -x : x;
            _warpFastEnv += warpFastCoef * (xAbs - _warpFastEnv);
            _warpSlowEnv += warpSlowCoef * (xAbs - _warpSlowEnv);
            if (_warpSlowEnv < 1e-6f) _warpSlowEnv = 1e-6f;
            float trans = Math.max(0f, _warpFastEnv / _warpSlowEnv - 1f);
            if (trans > 4f) trans = 4f;
            float warpGain = 1f + warpAmt * Math.min(1f, trans * 0.5f);
            x *= warpGain;

            float dryAbs = dry < 0 ? -dry : dry;
            float rcCoef = dryAbs > _duckEnv ? DUCK_RC_FAST : DUCK_RC_SLOW;
            float duckIIR = 1f - (float) Math.exp(-1.0 / (sampleRate * rcCoef));
            _duckEnv += duckIIR * (dryAbs - _duckEnv);
            preBuf[preW] = x;
            int preR = preW - preLen;
            if (preR < 0) preR += preBuf.length;
            float preOut = preBuf[preR];
            preW++; if (preW >= preBuf.length) preW = 0;
            float shimOut = shimRead(shimBuf, _shimReadA, _shimReadB, shimGrainLen, shimGrainPos);
            float tankIn = preOut + shimOut * shimAmt;
            // ── RESOLUTION: scale the input-diffusion AP gains.  Low
            // RES gives a more "colored / chunky" reverb (the diffusion
            // is incomplete), high RES gives the full Dattorro density.
            tankIn = ap(tankIn, _ap1, _ap1L, _ap1w, AP_G[0] * resScale); _ap1w = (_ap1w + 1) % _ap1L;
            tankIn = ap(tankIn, _ap2, _ap2L, _ap2w, AP_G[1] * resScale); _ap2w = (_ap2w + 1) % _ap2L;
            tankIn = ap(tankIn, _ap3, _ap3L, _ap3w, AP_G[2] * resScale); _ap3w = (_ap3w + 1) % _ap3L;
            tankIn = ap(tankIn, _ap4, _ap4L, _ap4w, AP_G[3] * resScale); _ap4w = (_ap4w + 1) % _ap4L;
            _lfoPhase += lfoInc;
            if (_lfoPhase > 6.283185f) _lfoPhase -= 6.283185f;
            float lfoSinA = (float) Math.sin(_lfoPhase);
            float lfoSinB = (float) Math.sin(_lfoPhase + 1.7f);
            _lfoNoiseA += 0.0008f * (nextNoise() - _lfoNoiseA);
            _lfoNoiseB += 0.0008f * (nextNoise() - _lfoNoiseB);
            float modA = (lfoSinA + _lfoNoiseA * 0.5f) * modDepth;
            float modB = (lfoSinB + _lfoNoiseB * 0.5f) * modDepth;
            // ── SPARKLE: extract HF from the cross-feedback via a
            // 1-pole HP and mix it back amplified — adds extra
            // brightness on every loop iteration without affecting
            // overall decay character.
            _sparkleHP_a += sparkleHpCoef * (_fb_b - _sparkleHP_a);
            float fbBwithSparkle = _fb_b + (_fb_b - _sparkleHP_a) * sparkleAmt;
            float aIn = tankIn + fbBwithSparkle * feedbackG;
            aIn = apMod(aIn, _mAp_a, mAp_a_w, modA, 0.7f);
            mAp_a_w = (mAp_a_w + 1) % _mAp_a.length;
            _d1_a[d1_a_w] = aIn;
            int d1arIdx = d1_a_w - d1aLen;
            if (d1arIdx < 0) d1arIdx += _d1_a.length;
            float aMid = _d1_a[d1arIdx];
            d1_a_w = (d1_a_w + 1) % _d1_a.length;
            _damp_a += dampCutoff * (aMid - _damp_a);
            float aDamped = _damp_a;
            aDamped = ap(aDamped, _ap_a, _ap_a.length, ap_a_w, 0.5f);
            ap_a_w = (ap_a_w + 1) % _ap_a.length;
            _d2_a[d2_a_w] = aDamped;
            int d2arIdx = d2_a_w - d2aLen;
            if (d2arIdx < 0) d2arIdx += _d2_a.length;
            _fb_a = _d2_a[d2arIdx];
            d2_a_w = (d2_a_w + 1) % _d2_a.length;
            _sparkleHP_b += sparkleHpCoef * (_fb_a - _sparkleHP_b);
            float fbAwithSparkle = _fb_a + (_fb_a - _sparkleHP_b) * sparkleAmt;
            float bIn = tankIn + fbAwithSparkle * feedbackG;
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
            float wetL = readTap(_d1_a, d1_a_w, scaleLen(TANK_LENS[2], sampleRate) / 3)
                       + readTap(_ap_a, ap_a_w, scaleLen(TANK_LENS[3], sampleRate) / 4)
                       - readTap(_d2_b, d2_b_w, scaleLen(TANK_LENS[8], sampleRate) / 2);
            float wetR = readTap(_d1_b, d1_b_w, scaleLen(TANK_LENS[7], sampleRate) / 3)
                       + readTap(_ap_b, ap_b_w, scaleLen(TANK_LENS[8], sampleRate) / 4)
                       - readTap(_d2_a, d2_a_w, scaleLen(TANK_LENS[3], sampleRate) / 2);
            wetL *= 0.18f; wetR *= 0.18f;
            shimBuf[_shimW] = (wetL + wetR) * 0.5f;
            _shimW = (_shimW + 1) % shimBufLen;
            _shimReadA += 2.0f; _shimReadB += 2.0f;
            if (_shimReadA >= shimBufLen) _shimReadA -= shimBufLen;
            if (_shimReadB >= shimBufLen) _shimReadB -= shimBufLen;
            shimGrainPos = (shimGrainPos + 1) % shimGrainLen;
            float mid  = (wetL + wetR) * 0.5f;
            float side = (wetL - wetR) * 0.5f;
            // ── SIDES: 1-pole HP on the side channel — strips low-end
            // mud from the stereo width while keeping the mono centre
            // intact. At sides=0 essentially no cut; at sides=1 the HP
            // ramps up past ~500 Hz.
            _sidesHP += sidesCoef * (side - _sidesHP);
            side -= _sidesHP * sides;
            side *= (0.2f + 1.6f * width);
            wetL = mid + side;
            wetR = mid - side;
            float wet = (wetL + wetR) * 0.5f;

            // ── SMOOTHING: peaking biquad with negative gain at the
            // algorithm's characteristic resonance band (~2.5 kHz)
            // tames the metallic ring that algorithmic reverbs can
            // get at high decay times.
            if (smoothCoefs != null) {
                float wetIn = wet;
                wet = smoothCoefs[0] * wetIn
                    + smoothCoefs[1] * smoothState[0]
                    + smoothCoefs[2] * smoothState[1]
                    - smoothCoefs[3] * smoothState[2]
                    - smoothCoefs[4] * smoothState[3];
                smoothState[1] = smoothState[0]; smoothState[0] = wetIn;
                smoothState[3] = smoothState[2]; smoothState[2] = wet;
            }

            _toneLP += 0.10f * (wet - _toneLP);
            float wetHP = wet - _toneLP;
            wet = wet + toneTilt * (wetHP - _toneLP) * 0.5f;

            // ── REVERSE: when ON, read from the wet ring buffer
            // BACKWARDS — produces the classic "pre-attack swell" sound
            // (early Twin Peaks / shoegaze).  Off → pass current sample.
            revBuf[revBufW] = wet;
            if (revActive) {
                int revR = revBufW - revBufLen + 1;
                if (revR < 0) revR += revBufLen;
                wet = revBuf[revR];   // oldest sample = current output
            }
            revBufW++; if (revBufW >= revBufLen) revBufW = 0;

            float duckGain = 1f - duckAmt * Math.min(1f, _duckEnv * 6f);
            wet *= duckGain;
            float wetAbs = wet < 0 ? -wet : wet;
            float gateTarget = wetAbs > gateLin ? 1f : 0f;
            float gateCoef = gateTarget > _gateGain ? 0.05f : 0.0008f;
            _gateGain += gateCoef * (gateTarget - _gateGain);
            wet *= _gateGain;
            output[i] = dry * dryMix + wet * wetMix;
            histRing[histRingW] = wet;
            histRingW++;
            if (histRingW >= HIST_RING) histRingW = 0;
        }
        ap1w = _ap1w; ap2w = _ap2w; ap3w = _ap3w; ap4w = _ap4w;
        bwLP = _bwLP; bwHP = _bwHP;
        damp_a = _damp_a; damp_b = _damp_b;
        fb_a = _fb_a; fb_b = _fb_b;
        toneLP = _toneLP;
        duckEnv = _duckEnv;
        gateGain = _gateGain;
        lfoPhase = _lfoPhase;
        lfoNoiseA = _lfoNoiseA; lfoNoiseB = _lfoNoiseB;
        shimReadA = _shimReadA; shimReadB = _shimReadB;
        shimW = _shimW;
        sparkleHP_a = _sparkleHP_a; sparkleHP_b = _sparkleHP_b;
        sidesHP = _sidesHP;
        warpFastEnv = _warpFastEnv; warpSlowEnv = _warpSlowEnv;
    }

    // RBJ cookbook peaking EQ biquad coefficients (returned as
    // [b0,b1,b2,a1,a2] with a0 normalised out).  Used for SMOOTHING.
    private static float[] peakingBiquad(float fc, float q, float gainDb, int sr) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1.0 + alpha / A;
        return new float[] {
            (float) ((1.0 + alpha * A) / a0),
            (float) (-2.0 * cs / a0),
            (float) ((1.0 - alpha * A) / a0),
            (float) (-2.0 * cs / a0),
            (float) ((1.0 - alpha / A) / a0)
        };
    }

    private static float ap(float x, float[] buf, int len, int w, float g) {
        float delayed = buf[w];
        float y = -g * x + delayed;
        buf[w] = x + g * y;
        return y;
    }
    private static float apMod(float x, float[] buf, int w, float modOffset, float g) {
        int len = buf.length;
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
    private static float readTap(float[] buf, int w, int back) {
        int r = w - back;
        if (r < 0) r += buf.length;
        return buf[r];
    }
    private float shimRead(float[] buf, float ra, float rb, int grainLen, int pos) {
        int ia0 = (int) ra; int ia1 = ia0 + 1; if (ia1 >= buf.length) ia1 = 0;
        int ib0 = (int) rb; int ib1 = ib0 + 1; if (ib1 >= buf.length) ib1 = 0;
        float fa = ra - ia0; float fb = rb - ib0;
        float sa = buf[ia0] * (1f - fa) + buf[ia1] * fa;
        float sb = buf[ib0] * (1f - fb) + buf[ib1] * fb;
        float t = pos / (float) grainLen;
        float wa = t < 0.5f ? (t * 2f) : (1f - (t - 0.5f) * 2f);
        float wb = 1f - wa;
        return sa * wa + sb * wb;
    }
    private float nextNoise() {
        long x = noiseSeed;
        x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
        noiseSeed = x;
        return ((x & 0xFFFF) / 32768f) - 1f;
    }

    // ═════════════════════════════════════════════════════════════════
    //  Canvas-owned UI
    // ═════════════════════════════════════════════════════════════════

    private PluginHost host;
    @Override public void setHost(PluginHost h) { this.host = h; }

    // ── Layout cache. Recomputed when panel size changes — control
    //    hit-rects index into this. Names are stable, positions move. ──
    private static final int N_CTRL = 13;  // 12 params + dry/wet slider drawn separately
    private final ControlRect[] controls = new ControlRect[N_CTRL];
    private float lastW = 0f, lastH = 0f;

    // Centre-column controls (matching Crystalline's stack below the
    // gradient display):
    //   - START slider  → linked to predelay
    //   - END   slider  → linked to decay
    //   - DUCK  slider  → linked to duck
    //   - FRZ   button  → linked to freeze
    //   - DRY/WET slider → linked to mix
    private float displayBoxY1;                // bottom of gradient
    private float startX0, startY0, startX1, startY1;
    private float endX0,   endY0,   endX1,   endY1;
    private float duckX0,  duckY0,  duckX1,  duckY1;
    private float revX0,   revY0,   revX1,   revY1;
    private float frzX0,   frzY0,   frzX1,   frzY1;
    private float sliderX0, sliderY0, sliderX1, sliderY1;  // dry/wet

    // ── Drag state ──
    private int  activeIdx = -1;       // index into `controls`, or -2 for slider, or -1 = idle
    private float touchDownY = 0f;
    private float touchDownX = 0f;
    private float dragStartValue = 0f;

    static class ControlRect {
        final String paramName;
        final String label;
        final int kind;       // 0 = knob, 1 = toggle, 2 = bipolar knob (centred at 0)
        float cx, cy, r;      // knob centre + radius
        float bx0, by0, bx1, by1;  // hit rect (including label)
        ControlRect(String paramName, String label, int kind) {
            this.paramName = paramName; this.label = label; this.kind = kind;
        }
    }

    private void recomputeLayout(float W, float H) {
        if (Math.abs(W - lastW) < 0.5f && Math.abs(H - lastH) < 0.5f
                && controls[0] != null) return;
        lastW = W; lastH = H;

        // Controls match Crystalline's side-panel layout 1:1.
        // Left column: REFLECTIONS row + DEPTH row.
        controls[0]  = new ControlRect("size",       "SIZE",       0);
        controls[1]  = new ControlRect("sparkle",    "SPARKLE",    0);
        controls[2]  = new ControlRect("width",      "WIDTH",      0);
        controls[3]  = new ControlRect("resolution", "RESOLUTION", 0);
        controls[4]  = new ControlRect("modulation", "MOD",        0);
        controls[5]  = new ControlRect("shimmer",    "SHIMMER",    0);
        // Right column: CLEAN-UP row + SHAPE row.
        controls[6]  = new ControlRect("damping",    "DAMP",       0);
        controls[7]  = new ControlRect("sides",      "SIDES",      0);
        controls[8]  = new ControlRect("gate",       "GATE",       0);
        controls[9]  = new ControlRect("tone",       "TONE",       2);  // bipolar
        controls[10] = new ControlRect("smoothing",  "SMOOTHING",  0);
        controls[11] = new ControlRect("warp",       "WARP",       2);  // bipolar
        controls[12] = null;

        // Layout geometry.
        float pad = 12f;
        float headerH = 26f;
        float footerH = 36f;
        float midTop = pad + headerH;
        float midBot = H - pad - footerH;
        float midH = midBot - midTop;

        // 22 / 56 / 22 column split matching Crystalline proportions.
        float colW = W * 0.24f;
        float leftX = pad;
        float rightX = W - pad - colW;
        float centerX0 = leftX + colW + pad;
        float centerX1 = rightX - pad;

        // 2×3 grid for each side column.
        layoutGrid(leftX, midTop, leftX + colW, midBot, 0, 6);
        layoutGrid(rightX, midTop, rightX + colW, midBot, 6, 12);

        // Centre column has 3 horizontal strips stacked vertically:
        //   - Gradient display (top, takes ~60% of the centre height)
        //   - START | END mini sliders row
        //   - OUTPUT row: DUCK · FRZ · DRY/WET
        float centerW = centerX1 - centerX0;
        float displayY0 = midTop;
        displayBoxY1 = midTop + (midBot - midTop) * 0.62f;
        // START / END row.
        float seY0 = displayBoxY1 + 14f;
        float seY1 = seY0 + 16f;
        float seGap = 10f;
        float seW = (centerW - seGap) * 0.5f;
        startX0 = centerX0;       startY0 = seY0;
        startX1 = centerX0 + seW; startY1 = seY1;
        endX0 = startX1 + seGap;  endY0 = seY0;
        endX1 = centerX1;         endY1 = seY1;
        // OUTPUT row: DUCK | REV | FRZ | DRY/WET.
        float outY0 = seY1 + 22f;
        float outY1 = outY0 + 16f;
        float duckW = centerW * 0.26f;
        float dotSize = 16f;
        float dwGap = 8f;
        duckX0 = centerX0;        duckY0 = outY0;
        duckX1 = centerX0 + duckW; duckY1 = outY1;
        revX0 = duckX1 + dwGap;
        revY0 = (outY0 + outY1) * 0.5f - dotSize * 0.5f;
        revX1 = revX0 + dotSize;
        revY1 = revY0 + dotSize;
        frzX0 = revX1 + dwGap;
        frzY0 = revY0;
        frzX1 = frzX0 + dotSize;
        frzY1 = frzY0 + dotSize;
        sliderX0 = frzX1 + dwGap;
        sliderY0 = outY0;
        sliderX1 = centerX1;
        sliderY1 = outY1;
    }

    private void layoutGrid(float x0, float y0, float x1, float y1,
                            int from, int to) {
        // 2 rows × 3 cols.  Each row gets an internal gap so the section
        // card around the top row doesn't bleed into the bottom row's
        // card (no Crystalline-style "gap between cards" otherwise).
        int cols = 3, rows = 2;
        float rowGap = 26f;
        float cw = (x1 - x0) / cols;
        float rh = ((y1 - y0) - rowGap) / rows;
        float radius = Math.min(cw, rh) * 0.30f;
        if (radius < 12f) radius = 12f;
        int n = to - from;
        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            float cellX = x0 + col * cw;
            float cellY = y0 + row * (rh + rowGap);
            ControlRect c = controls[from + i];
            c.cx = cellX + cw * 0.5f;
            c.cy = cellY + rh * 0.42f;          // top-biased so label fits below
            c.r  = radius;
            c.bx0 = cellX; c.by0 = cellY;
            c.bx1 = cellX + cw; c.by1 = cellY + rh;
        }
    }

    // Slider ids beyond the controls[] grid.
    private static final int SLIDER_DRYWET = -2;
    private static final int SLIDER_START  = -3;
    private static final int SLIDER_END    = -4;
    private static final int SLIDER_DUCK   = -5;
    private static final int DOT_FRZ       = -6;
    private static final int DOT_REV       = -7;

    // ── Touch handlers ──
    @Override public void onTouchDown(float x, float y) {
        recomputeLayout(lastW, lastH);
        // Centre-column controls have priority (overlap nothing else).
        if (hits(x, y, startX0, startY0, startX1, startY1)) {
            activeIdx = SLIDER_START;
            commitSliderAt("predelay", x, startX0, startX1,
                    parameterMin("predelay"), parameterMax("predelay"));
            return;
        }
        if (hits(x, y, endX0, endY0, endX1, endY1)) {
            activeIdx = SLIDER_END;
            commitSliderAt("decay", x, endX0, endX1, 0f, 1f);
            return;
        }
        if (hits(x, y, duckX0, duckY0, duckX1, duckY1)) {
            activeIdx = SLIDER_DUCK;
            commitSliderAt("duck", x, duckX0, duckX1, 0f, 1f);
            return;
        }
        if (hits(x, y, revX0 - 4f, revY0 - 4f, revX1 + 4f, revY1 + 4f)) {
            activeIdx = DOT_REV;
            commitParam("rev", rev >= 0.5f ? 0f : 1f);
            return;
        }
        if (hits(x, y, frzX0 - 4f, frzY0 - 4f, frzX1 + 4f, frzY1 + 4f)) {
            activeIdx = DOT_FRZ;
            commitParam("freeze", freeze >= 0.5f ? 0f : 1f);
            return;
        }
        if (hits(x, y, sliderX0, sliderY0, sliderX1, sliderY1)) {
            activeIdx = SLIDER_DRYWET;
            commitSliderAt("mix", x, sliderX0, sliderX1, 0f, 1f);
            return;
        }
        for (int i = 0; i < controls.length; i++) {
            ControlRect c = controls[i];
            if (c == null) continue;
            if (x >= c.bx0 && x <= c.bx1 && y >= c.by0 && y <= c.by1) {
                activeIdx = i;
                touchDownX = x; touchDownY = y;
                dragStartValue = getParameterValue(c.paramName);
                if (c.kind == 1) {
                    // Latching toggle — flip on touch-down, no drag.
                    float now = getParameterValue(c.paramName);
                    commitParam(c.paramName, now >= 0.5f ? 0f : 1f);
                }
                return;
            }
        }
        activeIdx = -1;
    }

    @Override public void onTouchMove(float x, float y) {
        if (activeIdx == -1 || activeIdx == DOT_FRZ || activeIdx == DOT_REV) return;
        switch (activeIdx) {
            case SLIDER_DRYWET:
                commitSliderAt("mix", x, sliderX0, sliderX1, 0f, 1f); return;
            case SLIDER_START:
                commitSliderAt("predelay", x, startX0, startX1,
                        parameterMin("predelay"), parameterMax("predelay")); return;
            case SLIDER_END:
                commitSliderAt("decay", x, endX0, endX1, 0f, 1f); return;
            case SLIDER_DUCK:
                commitSliderAt("duck", x, duckX0, duckX1, 0f, 1f); return;
            default: /* fall through to knob drag */
        }
        if (activeIdx < 0 || activeIdx >= controls.length) return;
        ControlRect c = controls[activeIdx];
        if (c == null || c.kind == 1) return;
        // Knob: vertical drag changes value. 200 dp = full range.
        float dyDp = touchDownY - y;
        float min = parameterMin(c.paramName);
        float max = parameterMax(c.paramName);
        float v = dragStartValue + (max - min) * (dyDp / 200f);
        if (v < min) v = min; else if (v > max) v = max;
        commitParam(c.paramName, v);
    }

    private static boolean hits(float x, float y, float x0, float y0, float x1, float y1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }
    private void commitSliderAt(String name, float xOnPanel,
                                 float x0, float x1, float min, float max) {
        float t = (xOnPanel - x0) / (x1 - x0);
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        commitParam(name, min + (max - min) * t);
    }

    @Override public void onTouchUp(float x, float y) {
        activeIdx = -1;
    }

    private void commitParam(String name, float value) {
        setParameter(name, value);
        if (host != null) host.setParameter(name, value);
    }

    // ─────────────────────────────────────────────────────────────
    //  Visual / FFT history (same as before)
    // ─────────────────────────────────────────────────────────────
    // FFT waterfall — Crystalline-style 3D scrolling spectrogram.
    //   - Every CAPTURE_MS, a fresh FFT slice is pushed onto a ring.
    //   - On every render frame we compute a SMOOTH age value
    //     (= lane index + fraction of the next capture window already
    //     elapsed), so the lanes slide continuously backward instead
    //     of jumping in 33 ms steps.
    //   - Each lane's Y offset grows with age (perspective recede)
    //     and its X offset shifts slightly to the right (parallax).
    //   - Alpha falls off with age — front lane is bright white, back
    //     lanes fade to almost transparent.
    //   - FFT bins are mapped LOGARITHMICALLY to X so the low-frequency
    //     energy doesn't pile up at the left edge.
    private static final int   FFT_SIZE     = 256;
    private static final int   WAVE_LANES   = 22;
    private static final long  CAPTURE_MS   = 50L;   // 20 Hz capture rate
    private final float[][] waveLanes = new float[WAVE_LANES][FFT_SIZE / 2];
    private int waveWritePos = 0;
    private long lastFftCaptureMs = 0L;
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] hann  = new float[FFT_SIZE];
    private boolean fftInit = false;
    // Captured at the start of each render() so per-icon animations
    // can use it without re-plumbing the signature.
    private long lastRenderMs = 0L;

    // Crystalline palette — matched against the BABY Audio reference:
    //   panel grey, WHITE floating cards with stacked soft shadows,
    //   near-flat buttons with subtle drop, four section colours
    //   (red/blue/orange/green), amber-pink-blue gradient display.
    private static final int COLOR_BG          = 0xFFE7E8EB;  // panel grey (darker so white cards pop)
    private static final int COLOR_CARD        = 0xFFFCFCFD;  // WHITE floating card
    private static final int COLOR_CARD_BORDER = 0x18000000;  // very faint
    private static final int COLOR_BUTTON_BG   = 0xFFFAFAFB;  // near-white button face
    private static final int COLOR_BUTTON_HI   = 0xFFFFFFFF;  // top highlight inside button
    private static final int COLOR_BUTTON_LO   = 0xFFEAEAED;  // bottom subtle drop
    private static final int COLOR_SHADOW_1    = 0x10000000;  // outermost soft shadow
    private static final int COLOR_SHADOW_2    = 0x18000000;  // mid shadow
    private static final int COLOR_SHADOW_3    = 0x10000000;  // closest shadow
    private static final int COLOR_INK         = 0xFF1A1A1E;
    private static final int COLOR_INK_DIM     = 0xFF7E7F84;
    private static final int COLOR_INK_FAINT   = 0xFFB0B1B5;
    private static final int COLOR_ACCENT      = 0xFFF5C842;
    private static final int COLOR_REFLECT     = 0xFFE34855;  // red
    private static final int COLOR_DEPTH       = 0xFF4290D8;  // blue
    private static final int COLOR_CLEAN       = 0xFFEE8A2C;  // orange
    private static final int COLOR_SHAPE       = 0xFF4FCB60;  // green
    private static final int GRAD_LEFT   = 0xFFFFB44A;
    private static final int GRAD_MIDDLE = 0xFFF38FB7;
    private static final int GRAD_RIGHT  = 0xFF7AB6E0;

    private PluginPaint bgPaint, cardPaint, buttonPaint,
            iconPaint, labelPaint, headerPaint, sectionLabel,
            displayBg, displayBorder, lanePaint,
            sliderTrack, sliderFill, sliderHandle;
    private PluginPath path1, path2, wavePath;

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
        recomputeLayout(width, height);
        lastRenderMs = timeMs;

        float W = width, H = height;
        if (W < 40 || H < 40) return;

        // Pull current values from host params map (source of truth).
        for (ControlRect c : controls) {
            if (c == null) continue;
            Float v = params != null ? params.get(c.paramName) : null;
            if (v != null) setParameter(c.paramName, v);
        }

        // ── Panel background (light grey, Crystalline-style) ──
        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // ── Header (italic "crystal" wordmark + decay readout) ──
        // Tiny faux-italic via a slight shear isn't supported by the
        // abstract canvas, so use bold + larger size for emphasis.
        headerPaint.setColor(COLOR_INK).setTextSize(22f).setTextAlign(1);
        canvas.drawText("crystal", W * 0.5f, 24f, headerPaint);
        // Side-corner annotations: thin top labels left/right.
        headerPaint.setColor(COLOR_INK_DIM).setTextSize(9f).setTextAlign(0);
        canvas.drawText("VOCAL MONITOR.", 12f, 16f, headerPaint);
        float decaySec = 0.2f + decay * decay * 8f;
        headerPaint.setColor(COLOR_INK_DIM).setTextSize(9f).setTextAlign(2);
        canvas.drawText(String.format("DECAY  %.1f s", decaySec),
                W - 12f, 16f, headerPaint);

        // ── Centre gradient FFT display ──
        // Bottom of the display is `displayBoxY1` (set in
        // recomputeLayout) — below it sit the START/END row and the
        // OUTPUT row.
        float pad = 12f;
        float headerH = 32f;
        float dispX0 = controls[2].bx1 + pad;
        float dispX1 = controls[6].bx0 - pad;
        float dispY0 = pad + headerH;
        drawCentralDisplay(canvas, dispX0, dispY0, dispX1, displayBoxY1, timeMs);

        // ── Section cards + their inner button rows ──
        drawSectionCard(canvas, controls[0].bx0 - 6f, controls[0].by0 - 16f,
                          controls[2].bx1 + 6f, controls[2].by1 + 6f,
                          "REFLECTIONS");
        drawSectionCard(canvas, controls[3].bx0 - 6f, controls[3].by0 - 16f,
                          controls[5].bx1 + 6f, controls[5].by1 + 6f,
                          "DEPTH");
        drawSectionCard(canvas, controls[6].bx0 - 6f, controls[6].by0 - 16f,
                          controls[8].bx1 + 6f, controls[8].by1 + 6f,
                          "CLEAN-UP");
        drawSectionCard(canvas, controls[9].bx0 - 6f, controls[9].by0 - 16f,
                          controls[11].bx1 + 6f, controls[11].by1 + 6f,
                          "SHAPE");

        // ── All control buttons ──
        for (int i = 0; i < controls.length; i++) {
            ControlRect c = controls[i];
            if (c == null) continue;
            drawControl(canvas, c, i == activeIdx);
        }

        // ── START / END mini sliders below the display (Crystalline-
        //    style: START → predelay, END → decay).
        drawCenterSlider(canvas, "START", startX0, startY0, startX1, startY1,
                          predelay / parameterMax("predelay"));
        drawCenterSlider(canvas, "END",   endX0,   endY0,   endX1,   endY1,   decay);

        // ── OUTPUT row: DUCK mini slider + FRZ toggle dot + DRY/WET ──
        // Single OUTPUT section label spans the full row above all
        // three controls, so individual slider labels don't fight it.
        labelPaint.setColor(COLOR_INK_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("OUTPUT", duckX0, duckY0 - 14f, labelPaint);
        drawCenterSlider(canvas, "DUCK", duckX0, duckY0, duckX1, duckY1, duck);
        // REV and FRZ dots side-by-side — matches Crystalline's OUTPUT
        // row exactly (REV = reverse reverb, FRZ = freeze).
        drawDot(canvas, revX0, revY0, revX1, revY1, rev    >= 0.5f, 0xFFE36C9C);
        drawDot(canvas, frzX0, frzY0, frzX1, frzY1, freeze >= 0.5f, 0xFF6DD3E0);
        labelPaint.setColor(COLOR_INK_DIM).setTextSize(7.5f).setTextAlign(1);
        canvas.drawText("REV", (revX0 + revX1) * 0.5f, revY0 - 4f, labelPaint);
        canvas.drawText("FRZ", (frzX0 + frzX1) * 0.5f, frzY0 - 4f, labelPaint);
        drawDryWetSlider(canvas, sliderX0, sliderY0, sliderX1, sliderY1, mix);
    }

    // Mini horizontal slider with name label above + value handle.
    private void drawCenterSlider(PluginCanvas canvas, String label,
            float x0, float y0, float x1, float y1, float value) {
        labelPaint.setColor(COLOR_INK_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText(label, x0, y0 - 3f, labelPaint);
        float midY = (y0 + y1) * 0.5f;
        // Track.
        sliderTrack.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, midY - 3f, x1, midY + 3f, 3f, sliderTrack);
        // Fill.
        float v = Math.max(0f, Math.min(1f, value));
        float vx = x0 + (x1 - x0) * v;
        sliderFill.setColor(COLOR_ACCENT).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, midY - 3f, vx, midY + 3f, 3f, sliderFill);
        // Handle — small white circle.
        sliderHandle.setColor(COLOR_SHADOW_2).setStyle(PluginStyle.FILL);
        canvas.drawCircle(vx + 0.5f, midY + 1.5f, 6f, sliderHandle);
        sliderHandle.setColor(COLOR_BUTTON_HI).setStyle(PluginStyle.FILL);
        canvas.drawCircle(vx, midY, 6f, sliderHandle);
        sliderHandle.setColor(COLOR_INK_DIM).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawCircle(vx, midY, 6f, sliderHandle);
    }

    // Toggle dot — Crystalline-style coloured circle that fills when ON.
    private void drawDot(PluginCanvas canvas, float x0, float y0,
                          float x1, float y1, boolean on, int colourOn) {
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f, r = (x1 - x0) * 0.5f;
        sliderHandle.setColor(COLOR_SHADOW_2).setStyle(PluginStyle.FILL);
        canvas.drawCircle(cx + 0.5f, cy + 1.5f, r, sliderHandle);
        sliderHandle.setColor(on ? colourOn : COLOR_BUTTON_BG).setStyle(PluginStyle.FILL);
        canvas.drawCircle(cx, cy, r, sliderHandle);
        sliderHandle.setColor(on ? colourOn : COLOR_INK_DIM)
                .setStyle(PluginStyle.STROKE).setStrokeWidth(1.2f);
        canvas.drawCircle(cx, cy, r, sliderHandle);
    }

    // ── Section card — pure white floating rectangle with a stacked
    //    soft drop shadow underneath (3 progressively closer + darker
    //    layers approximate a Gaussian blur).  Crystalline's signature
    //    "lifted card" look without needing the host to support a real
    //    shadow / blur primitive. ──
    private void drawSectionCard(PluginCanvas canvas, float x0, float y0,
                                  float x1, float y1, String label) {
        // Stacked soft drop shadow.  Each layer slightly larger and
        // softer than the next, approximating Gaussian blur falloff.
        cardPaint.setColor(COLOR_SHADOW_1).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0 - 1f, y0 + 3f, x1 + 1f, y1 + 5f, 12f, cardPaint);
        cardPaint.setColor(COLOR_SHADOW_2).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0 + 2f, x1, y1 + 4f, 11f, cardPaint);
        cardPaint.setColor(COLOR_SHADOW_3).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0 + 1f, x1, y1 + 2f, 10f, cardPaint);

        // Card body — pure white.
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, x1, y1, 10f, cardPaint);
        // Hair-thin outline for definition against light backgrounds.
        cardPaint.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawRoundRect(x0, y0, x1, y1, 10f, cardPaint);

        // Label centred at the top of the card.
        sectionLabel.setColor(COLOR_INK_DIM).setTextSize(9.5f).setTextAlign(1);
        canvas.drawText(label, (x0 + x1) * 0.5f, y0 + 13f, sectionLabel);
    }

    // ── Single Crystalline-style square control: white rounded
    //    rectangle with a soft drop shadow, large coloured line-art
    //    icon centred inside whose shape encodes the parameter value,
    //    label below.  No external value arcs — the icon itself is
    //    the indicator. Pressed/active state highlights the border. ──
    private void drawControl(PluginCanvas canvas, ControlRect c, boolean active) {
        float val = getParameterValue(c.paramName);
        float min = parameterMin(c.paramName);
        float max = parameterMax(c.paramName);
        float norm = max > min ? (val - min) / (max - min) : 0f;
        if (norm < 0f) norm = 0f; else if (norm > 1f) norm = 1f;

        // Square button bounds — inscribed inside the cell with margin
        // so the section card border isn't crowded.
        float btnSize = Math.min(c.bx1 - c.bx0, (c.by1 - c.by0) - 18f) - 8f;
        if (btnSize < 24f) btnSize = 24f;
        float bx0 = c.cx - btnSize * 0.5f;
        float by0 = c.cy - btnSize * 0.5f;
        float bx1 = c.cx + btnSize * 0.5f;
        float by1 = c.cy + btnSize * 0.5f;

        // Stacked soft drop shadow — same approach as the section
        // card, scaled down.  Two layers for subtler depth.
        buttonPaint.setColor(COLOR_SHADOW_1).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(bx0, by0 + 1.5f, bx1, by1 + 3f, 7.5f, buttonPaint);
        buttonPaint.setColor(COLOR_SHADOW_2).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(bx0, by0 + 1f, bx1, by1 + 1.5f, 7f, buttonPaint);
        // Button face — near-white, almost flat.  The vertical hairline
        // of slightly lighter colour at the top edge gives just enough
        // bevel for the button to read as a button without looking
        // aggressively 3D.
        buttonPaint.setColor(COLOR_BUTTON_BG).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(bx0, by0, bx1, by1, 7f, buttonPaint);
        buttonPaint.setColor(COLOR_BUTTON_HI).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
        canvas.drawLine(bx0 + 4f, by0 + 0.8f, bx1 - 4f, by0 + 0.8f, buttonPaint);
        // Active border (only visible while dragging).
        if (active) {
            buttonPaint.setColor(COLOR_ACCENT).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
            canvas.drawRoundRect(bx0, by0, bx1, by1, 7f, buttonPaint);
        } else {
            buttonPaint.setColor(0x12000000).setStyle(PluginStyle.STROKE).setStrokeWidth(0.8f);
            canvas.drawRoundRect(bx0, by0, bx1, by1, 7f, buttonPaint);
        }

        // Icon — fills ~70% of the button.
        drawIcon(canvas, c.paramName, c.cx, c.cy, btnSize * 0.36f, norm);

        // Label BELOW the button, uppercase, dim grey.
        labelPaint.setColor(COLOR_INK_DIM).setTextSize(9.5f).setTextAlign(1);
        canvas.drawText(c.label, c.cx, c.by1 - 4f, labelPaint);

        // Freeze toggle: extra ON badge inside the button when active.
        if (c.kind == 1 && norm >= 0.5f) {
            buttonPaint.setColor(COLOR_ACCENT).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(c.cx - 12f, by1 - 14f, c.cx + 12f, by1 - 4f, 4f, buttonPaint);
            labelPaint.setColor(0xFF101010).setTextSize(7.5f).setTextAlign(1);
            canvas.drawText("ON", c.cx, by1 - 6f, labelPaint);
        }
    }

    // Per-parameter icon — each parameter gets a large line-art glyph
    // in its section colour, sized big inside its button. The icon's
    // own shape encodes the parameter value (more rings for higher
    // SIZE, taller bars for higher DECAY, etc.) so no external arc /
    // meter is needed.
    //
    // `s` is the icon half-extent — typical line art fits roughly in
    // a box of (2s × 2s) centred at (cx, cy).
    private void drawIcon(PluginCanvas canvas, String param, float cx, float cy,
                           float s, float norm) {
        float sw = Math.max(1.8f, s * 0.10f);   // icon stroke width
        switch (param) {
            case "size": {
                // Crystalline-style SIZE: three horizontal lines
                // compressed toward the centre — like a "wave being
                // squished".  Spacing tightens with smaller rooms,
                // spreads out with bigger.
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                float w = s * 0.85f;
                // Lower value = lines closer together (small room),
                // higher value = lines spread further apart.
                float spacing = s * (0.20f + 0.30f * norm);
                // The TOP line is widest; the bottom narrowest — gives
                // a "fanning" look that reads as room-size growing.
                canvas.drawLine(cx - w * 0.5f, cy - spacing, cx + w * 0.5f, cy - spacing, iconPaint);
                float wMid = w * (0.65f + 0.20f * norm);
                canvas.drawLine(cx - wMid * 0.5f, cy, cx + wMid * 0.5f, cy, iconPaint);
                float wBot = w * (0.35f + 0.30f * norm);
                canvas.drawLine(cx - wBot * 0.5f, cy + spacing, cx + wBot * 0.5f, cy + spacing, iconPaint);
                break;
            }
            case "decay": {
                // (kept for backward compat) — Decay envelope wedge.
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.FILL);
                path1.reset();
                float w = s * 0.95f, h = s * 0.75f * (0.4f + 0.6f * norm);
                path1.moveTo(cx - w * 0.5f, cy + h * 0.4f);
                path1.lineTo(cx - w * 0.5f, cy - h * 0.6f);
                path1.lineTo(cx + w * 0.5f, cy + h * 0.4f);
                path1.close();
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "sparkle": {
                // Crystalline-style sparkle: a vertical "ringing"
                // glyph (3 horizontal red lines stacked) with extra
                // sparkle dots above as value rises.
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                float w = s * 0.7f;
                canvas.drawLine(cx - w * 0.5f, cy + s * 0.4f, cx + w * 0.5f, cy + s * 0.4f, iconPaint);
                canvas.drawLine(cx - w * 0.45f, cy + s * 0.15f, cx + w * 0.45f, cy + s * 0.15f, iconPaint);
                canvas.drawLine(cx - w * 0.4f, cy - s * 0.1f, cx + w * 0.4f, cy - s * 0.1f, iconPaint);
                iconPaint.setStyle(PluginStyle.FILL);
                int dots = (int)(2 + 4 * norm);
                for (int i = 0; i < dots; i++) {
                    double a = i * 1.4 - dots * 0.7;
                    canvas.drawCircle(cx + (float)(a * s * 0.18f),
                                       cy - s * (0.35f + (float)(Math.abs(a) * 0.10f)),
                                       1.5f, iconPaint);
                }
                break;
            }
            case "resolution": {
                // Vertical bars of varying heights — more bars + finer
                // grid as resolution increases.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                int bars = (int)(3 + 5 * norm);
                float spread = s * 1.1f;
                for (int i = 0; i < bars; i++) {
                    float t = bars == 1 ? 0.5f : i / (float)(bars - 1);
                    float bx = cx - spread * 0.5f + t * spread;
                    float bh = s * (0.25f + 0.55f * (float)Math.sin(t * Math.PI));
                    canvas.drawLine(bx, cy - bh, bx, cy + bh, iconPaint);
                }
                break;
            }
            case "sides": {
                // Stereo width arrows pointing outward, with a hi-pass
                // notch shape suggesting "sides HP".
                iconPaint.setColor(COLOR_CLEAN).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                path1.reset();
                float w = s * 1.2f, h = s * 0.4f;
                path1.moveTo(cx - w * 0.5f, cy + h);
                path1.lineTo(cx - w * 0.5f, cy + h * (1f - norm));
                path1.lineTo(cx - w * 0.15f, cy - h * 0.4f);
                path1.lineTo(cx + w * 0.15f, cy - h * 0.4f);
                path1.lineTo(cx + w * 0.5f, cy + h * (1f - norm));
                path1.lineTo(cx + w * 0.5f, cy + h);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "smoothing": {
                // Smooth dome (lopass-ish) curve.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                path1.reset();
                float w = s * 1.4f, h = s * 0.6f;
                path1.moveTo(cx - w * 0.5f, cy + h * 0.4f);
                path1.quadTo(cx, cy - h * (0.3f + 0.5f * norm),
                              cx + w * 0.5f, cy + h * 0.4f);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "warp": {
                // Vertical dashed lines — Crystalline's WARP icon.
                // Spacing tightens / loosens with the bipolar value.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                float h = s * 0.7f;
                int lines = 4;
                float spread = s * 1.0f;
                float bias = norm * 0.4f - 0.2f;  // bipolar widen/narrow
                for (int i = 0; i < lines; i++) {
                    float t = i / (float)(lines - 1);
                    float bx = cx - spread * 0.5f + t * spread + bias * s;
                    canvas.drawLine(bx, cy - h, bx, cy + h, iconPaint);
                }
                break;
            }
            case "width": {
                // Open circle that thickens with value — visual analogue
                // of "wider stereo image".
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.STROKE)
                        .setStrokeWidth(sw + 4f * norm);
                canvas.drawCircle(cx, cy, s * 0.70f, iconPaint);
                break;
            }
            case "modulation": {
                // Sine wave — amplitude grows with norm AND the wave
                // rolls in time, so a non-zero MOD value visibly moves.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                path1.reset();
                float w = s * 1.4f;
                float amp = s * (0.10f + 0.42f * norm);
                // Phase advances with time — faster rolling at higher
                // MOD values, matching Crystalline's lively MOD icon.
                float phase = lastRenderMs * 0.001f * (0.5f + norm * 3.0f);
                int npts = 32;
                for (int i = 0; i <= npts; i++) {
                    float t = i / (float) npts;
                    float px = cx - w * 0.5f + t * w;
                    float py = cy + (float)(Math.sin(t * 2 * Math.PI * 1.5 + phase) * amp);
                    if (i == 0) path1.moveTo(px, py);
                    else path1.lineTo(px, py);
                }
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "shimmer": {
                // Crystalline SHIMMER: 2 rows of 3 dots — outer dots
                // small, centre dot bigger.  Each dot twinkles
                // independently with a phase offset, brightness rising
                // with the shimmer amount.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.FILL);
                float rowSpacing = s * 0.45f;
                float colSpacing = s * 0.45f;
                int[] sizes = { 2, 3, 2, 3, 4, 3 };  // big in middle
                float baseR = s * 0.07f;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 3; c++) {
                        int idx = r * 3 + c;
                        float dx = (c - 1) * colSpacing;
                        float dy = (r * 2 - 1) * rowSpacing * 0.5f;
                        // Twinkle: each dot has its own phase, brightness
                        // pulses subtly to suggest "alive".
                        float twk = (float)(0.7 + 0.3 *
                                Math.sin(lastRenderMs * 0.004 + idx * 1.3));
                        float alpha = 0.4f + 0.6f * norm * twk;
                        if (alpha > 1f) alpha = 1f;
                        int col = ((int)(alpha * 255) << 24) | (COLOR_DEPTH & 0x00FFFFFF);
                        iconPaint.setColor(col);
                        canvas.drawCircle(cx + dx, cy + dy,
                                           baseR * sizes[idx] * 0.4f, iconPaint);
                    }
                }
                break;
            }
            case "predelay": {
                // Arrow + start-pulse: a vertical pulse on the left
                // and an arrow extending rightward, length scaling with
                // pre-delay value.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                canvas.drawLine(cx - s * 0.75f, cy - s * 0.45f,
                                 cx - s * 0.75f, cy + s * 0.45f, iconPaint);
                float arrowLen = s * (0.5f + 0.8f * norm);
                canvas.drawLine(cx - s * 0.55f, cy,
                                 cx - s * 0.55f + arrowLen, cy, iconPaint);
                iconPaint.setStyle(PluginStyle.FILL);
                path1.reset();
                float tipX = cx - s * 0.55f + arrowLen;
                path1.moveTo(tipX + 4f, cy);
                path1.lineTo(tipX - 4f, cy - 5f);
                path1.lineTo(tipX - 4f, cy + 5f).close();
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "damping": {
                // Crystalline DAMPING: a "pacman" / open arch shape —
                // a circle with a wedge cut out on the right.  The cut
                // angle grows with the damping amount so the icon
                // visibly closes as you damp more.
                iconPaint.setColor(COLOR_CLEAN).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                float r = s * 0.55f;
                // Draw the arc from (top-left) sweeping clockwise,
                // stopping early as norm rises (= bigger cut).
                int segs = 36;
                float startA = (float) Math.PI;        // 9 o'clock
                float endA = (float)(Math.PI * 2.0 + Math.PI * 0.5 * (1f - norm));
                // Approximate arc with line segments.
                float pxPrev = cx + r * (float)Math.cos(startA);
                float pyPrev = cy + r * (float)Math.sin(startA);
                for (int i = 1; i <= segs; i++) {
                    float t = i / (float) segs;
                    float a = startA + (endA - startA) * t;
                    float px = cx + r * (float)Math.cos(a);
                    float py = cy + r * (float)Math.sin(a);
                    canvas.drawLine(pxPrev, pyPrev, px, py, iconPaint);
                    pxPrev = px; pyPrev = py;
                }
                break;
            }
            case "gate": {
                // Crystalline GATE: an upward-arched smile shape with a
                // narrow gap in the middle — looks like an opening
                // gate.  The gap width scales with the GATE threshold,
                // wider gap = tighter gate.
                iconPaint.setColor(COLOR_CLEAN).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                // Gap in middle increases with norm.  Threshold knob is
                // in dB: -80 (open) … 0 (closed-most).  Normalise so
                // closed-most → biggest visible gap.
                float r = s * 0.55f;
                float gapAng = (float)(Math.PI * 0.15f + norm * Math.PI * 0.35f);
                // Left half of arc.
                float startA = (float)(Math.PI + 0.15);      // ~9-10 o'clock
                float midA   = (float)(Math.PI * 1.5 - gapAng * 0.5);
                drawArcSegments(canvas, cx, cy, r, startA, midA, iconPaint);
                // Right half of arc.
                float midA2  = (float)(Math.PI * 1.5 + gapAng * 0.5);
                float endA   = (float)(Math.PI * 2.0 - 0.15);
                drawArcSegments(canvas, cx, cy, r, midA2, endA, iconPaint);
                break;
            }
            case "freeze": {
                // Asterisk / snowflake — 8 spokes, colour flips to
                // accent yellow when the toggle is on.
                iconPaint.setColor(norm >= 0.5f ? COLOR_ACCENT : COLOR_CLEAN)
                        .setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    canvas.drawLine(cx, cy,
                            cx + s * 0.70f * (float)Math.cos(a),
                            cy + s * 0.70f * (float)Math.sin(a), iconPaint);
                }
                iconPaint.setStyle(PluginStyle.FILL);
                canvas.drawCircle(cx, cy, s * 0.13f, iconPaint);
                break;
            }
            case "tone": {
                // Tilt: short curved horizontal line whose slope tracks
                // the bipolar tone value. Flat at 0, rising for +tone,
                // falling for -tone.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                path1.reset();
                float w = s * 1.4f, h = s * 0.55f;
                float slope = (norm * 2f - 1f) * h;
                path1.moveTo(cx - w * 0.5f, cy + slope);
                path1.quadTo(cx, cy, cx + w * 0.5f, cy - slope);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "duck": {
                // Ducker dip: flat-flat with a U dip in the middle that
                // deepens with the ducker amount.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                path1.reset();
                float w = s * 1.4f, h = s * 0.6f;
                path1.moveTo(cx - w * 0.5f, cy - h * 0.4f);
                path1.lineTo(cx - w * 0.18f, cy - h * 0.4f);
                path1.quadTo(cx, cy + h * (0.10f + 0.90f * norm),
                              cx + w * 0.18f, cy - h * 0.4f);
                path1.lineTo(cx + w * 0.5f, cy - h * 0.4f);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "mix": {
                // Two overlapping circles — Venn diagram style. Filled
                // proportions track the dry/wet balance.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(sw);
                canvas.drawCircle(cx - s * 0.30f, cy, s * 0.45f, iconPaint);
                canvas.drawCircle(cx + s * 0.30f, cy, s * 0.45f, iconPaint);
                // Filled overlap proportional to mix.
                int wetAlpha = (int)(0x55 + 0x80 * norm);
                int wetCol = (wetAlpha << 24) | (COLOR_SHAPE & 0x00FFFFFF);
                iconPaint.setColor(wetCol).setStyle(PluginStyle.FILL);
                canvas.drawCircle(cx + s * 0.30f, cy, s * 0.45f * norm, iconPaint);
                break;
            }
            default: {
                iconPaint.setColor(COLOR_INK).setStyle(PluginStyle.FILL);
                canvas.drawCircle(cx, cy, 3f, iconPaint);
            }
        }
    }

    private void drawDryWetSlider(PluginCanvas canvas, float x0, float y0,
                                   float x1, float y1, float value) {
        // Slider name (matches START/END/DUCK label style).
        labelPaint.setColor(COLOR_INK_DIM).setTextSize(8.5f).setTextAlign(0);
        canvas.drawText("DRY / WET", x0, y0 - 3f, labelPaint);
        labelPaint.setColor(COLOR_INK).setTextSize(8.5f).setTextAlign(2);
        canvas.drawText(String.format("%.0f%%", value * 100), x1, y0 - 3f, labelPaint);

        float midY = (y0 + y1) * 0.5f;
        // Track — soft grey channel.
        sliderTrack.setColor(COLOR_CARD_BORDER).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, midY - 3.5f, x1, midY + 3.5f, 4f, sliderTrack);
        // Filled portion from x0 to value, accent yellow.
        float vx = x0 + (x1 - x0) * Math.max(0f, Math.min(1f, value));
        sliderFill.setColor(COLOR_ACCENT).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, midY - 3.5f, vx, midY + 3.5f, 4f, sliderFill);
        // Handle — small white circle with thin border + drop shadow.
        sliderHandle.setColor(COLOR_SHADOW_2).setStyle(PluginStyle.FILL);
        canvas.drawCircle(vx + 0.5f, midY + 1.5f, 8.5f, sliderHandle);
        sliderHandle.setColor(COLOR_BUTTON_HI).setStyle(PluginStyle.FILL);
        canvas.drawCircle(vx, midY, 8.5f, sliderHandle);
        sliderHandle.setColor(COLOR_INK_DIM).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        canvas.drawCircle(vx, midY, 8.5f, sliderHandle);
    }

    private void drawCentralDisplay(PluginCanvas canvas, float x0, float y0,
                                     float x1, float y1, long timeMs) {
        if (x1 - x0 < 40f || y1 - y0 < 40f) return;
        // Stacked soft drop shadow matching the section card style.
        displayBg.setColor(COLOR_SHADOW_1).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0 - 1f, y0 + 3f, x1 + 1f, y1 + 5f, 16f, displayBg);
        displayBg.setColor(COLOR_SHADOW_2).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0 + 2f, x1, y1 + 4f, 15f, displayBg);
        displayBg.setColor(COLOR_SHADOW_3).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0 + 1f, x1, y1 + 2f, 14f, displayBg);
        // Gradient fill.
        displayBg.setStyle(PluginStyle.FILL)
                .setLinearGradient(x0, y0, x1, y0,
                        new int[] { GRAD_LEFT, GRAD_MIDDLE, GRAD_RIGHT },
                        new float[] { 0f, 0.5f, 1f });
        canvas.drawRoundRect(x0, y0, x1, y1, 14f, displayBg);

        // Capture a fresh FFT slice at fixed cadence.
        if (lastFftCaptureMs == 0L) lastFftCaptureMs = timeMs;
        while (timeMs - lastFftCaptureMs >= CAPTURE_MS) {
            captureFftSlice();
            lastFftCaptureMs += CAPTURE_MS;
        }
        // Sub-capture fraction (0..1) — how far we've travelled into the
        // *next* capture window since the last slice was pushed.  Used
        // to interpolate lane positions for smooth scrolling instead of
        // 30 Hz step-jumps.
        float subFrac = Math.max(0f,
                Math.min(1f, (timeMs - lastFftCaptureMs) / (float) CAPTURE_MS));

        canvas.save();
        canvas.clipRect(x0 + 6f, y0 + 6f, x1 - 6f, y1 - 6f);

        // Layout: the front line sits below the centre, back lanes
        // recede upward + slightly right (parallax).  Frequency axis
        // is log-mapped so vocal energy reads across the panel.
        float padIn   = 18f;
        float innerW  = (x1 - x0) - padIn * 2f;
        float innerH  = (y1 - y0) - padIn * 2f;
        float frontY  = y0 + innerH * 0.82f;     // newest line near bottom
        float backY   = y0 + innerH * 0.20f;     // oldest line near top
        float shiftPerLane = innerW * 0.012f;    // X parallax per step
        // Draw OLDEST → NEWEST so the new line stacks on top.
        for (int step = WAVE_LANES - 1; step >= 0; step--) {
            float laneAge = step + subFrac;   // continuous age (smooth scroll)
            int idx = (waveWritePos - 1 - step + WAVE_LANES * 4) % WAVE_LANES;
            float[] frame = waveLanes[idx];

            // Perspective: linear interpolation between front and back
            // works well at our resolution; quadratic gives stronger
            // depth but compresses the back lanes too much.
            float t = laneAge / (float) WAVE_LANES;       // 0 = front, 1 = far back
            float laneY  = frontY + (backY - frontY) * t;
            float laneX  = laneAge * shiftPerLane;

            // Alpha: front bright, back nearly transparent.
            float alphaF = (1f - t) * (1f - t);           // square falloff
            int alpha = (int)(230 * alphaF + 25);
            if (alpha > 255) alpha = 255;
            int col = (alpha << 24) | 0x00FFFFFF;

            wavePath.reset();
            int bins = frame.length;
            // Skip the top FFT bin pile-up — start at bin 2.
            int firstBin = 2;
            for (int b = firstBin; b < bins; b++) {
                // Log-x mapping: 1.0 at the front bin, 0.0 at top of
                // the perspective wedge.  Map bin index → fraction
                // logarithmically so low-freq energy spreads out.
                float bt = (float)(Math.log(b - firstBin + 1)
                              / Math.log(bins - firstBin));
                float px = x0 + padIn + laneX + bt * (innerW - laneX - padIn);
                float mag = frame[b];
                // Amplitude falls off with age too so old lines smooth
                // into the background instead of staying jagged.
                float ampScale = (1f - t * 0.5f);
                float py = laneY - mag * innerH * 0.28f * ampScale;
                if (b == firstBin) wavePath.moveTo(px, py);
                else               wavePath.lineTo(px, py);
            }
            lanePaint.setColor(col).setStyle(PluginStyle.STROKE)
                    .setStrokeWidth(step == 0 ? 1.7f : 1.0f);
            canvas.drawPath(wavePath, lanePaint);
        }
        canvas.restore();
        displayBorder.setColor(0x40000000).setStyle(PluginStyle.STROKE).setStrokeWidth(1.0f);
        canvas.drawRoundRect(x0, y0, x1, y1, 14f, displayBorder);
    }

    private void captureFftSlice() {
        int n = FFT_SIZE;
        int start = histRingW - n;
        if (start < 0) start += HIST_RING;
        for (int i = 0; i < n; i++) {
            fftRe[i] = histRing[(start + i) % HIST_RING] * hann[i];
            fftIm[i] = 0f;
        }
        fftRadix2(fftRe, fftIm);
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

    private static void fftRadix2(float[] re, float[] im) {
        int n = re.length;
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

    // Cheap arc renderer — splits the angular range into short line
    // segments. Used by DAMPING and GATE icons.
    private void drawArcSegments(PluginCanvas canvas, float cx, float cy,
                                  float r, float a0, float a1, PluginPaint paint) {
        int segs = Math.max(2, (int) Math.ceil(Math.abs(a1 - a0) * 8));
        float pxPrev = cx + r * (float)Math.cos(a0);
        float pyPrev = cy + r * (float)Math.sin(a0);
        for (int i = 1; i <= segs; i++) {
            float t = i / (float) segs;
            float a = a0 + (a1 - a0) * t;
            float px = cx + r * (float)Math.cos(a);
            float py = cy + r * (float)Math.sin(a);
            canvas.drawLine(pxPrev, pyPrev, px, py, paint);
            pxPrev = px; pyPrev = py;
        }
    }

    private void initPaints(PluginCanvas c) {
        bgPaint       = c.newPaint();
        cardPaint     = c.newPaint();
        buttonPaint   = c.newPaint();
        iconPaint     = c.newPaint();
        labelPaint    = c.newPaint();
        headerPaint   = c.newPaint();
        sectionLabel  = c.newPaint();
        displayBg     = c.newPaint();
        displayBorder = c.newPaint();
        lanePaint     = c.newPaint();
        sliderTrack   = c.newPaint();
        sliderFill    = c.newPaint();
        sliderHandle  = c.newPaint();
        path1         = c.newPath();
        path2         = c.newPath();
        wavePath      = c.newPath();
    }
}
