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
    private float predelay   = 0.05f;
    private float size       = 0.55f;
    private float decay      = 0.65f;
    private float damping    = 0.30f;
    private float modulation = 0.30f;
    private float shimmer    = 0.0f;
    private float width      = 0.90f;
    private float tone       = 0.0f;
    private float duck       = 0.0f;
    private float gateDb     = -80f;
    private float freeze     = 0.0f;
    private float mix        = 0.30f;

    @Override public String[] parameterNames() {
        return new String[] { "predelay", "size", "decay", "damping",
                              "modulation", "shimmer", "width", "tone",
                              "duck", "gate", "freeze", "mix" };
    }
    @Override public float parameterMin(String n) {
        switch (n) {
            case "predelay": return 0.0f;
            case "tone":     return -1.0f;
            case "gate":     return -80.0f;
            default:         return 0.0f;
        }
    }
    @Override public float parameterMax(String n) {
        switch (n) {
            case "predelay": return 0.3f;
            case "tone":     return 1.0f;
            case "gate":     return 0.0f;
            default:         return 1.0f;
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

    private float getParameterValue(String n) {
        switch (n) {
            case "predelay":   return predelay;
            case "size":       return size;
            case "decay":      return decay;
            case "damping":    return damping;
            case "modulation": return modulation;
            case "shimmer":    return shimmer;
            case "width":      return width;
            case "tone":       return tone;
            case "duck":       return duck;
            case "gate":       return gateDb;
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
        for (int i = 0; i < n; i++) {
            final float dry = input[i];
            _bwHP += 0.0007f * (dry - _bwHP);
            float x = dry - _bwHP;
            _bwLP += 0.45f * (x - _bwLP);
            x = _bwLP;
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
            tankIn = ap(tankIn, _ap1, _ap1L, _ap1w, AP_G[0]); _ap1w = (_ap1w + 1) % _ap1L;
            tankIn = ap(tankIn, _ap2, _ap2L, _ap2w, AP_G[1]); _ap2w = (_ap2w + 1) % _ap2L;
            tankIn = ap(tankIn, _ap3, _ap3L, _ap3w, AP_G[2]); _ap3w = (_ap3w + 1) % _ap3L;
            tankIn = ap(tankIn, _ap4, _ap4L, _ap4w, AP_G[3]); _ap4w = (_ap4w + 1) % _ap4L;
            _lfoPhase += lfoInc;
            if (_lfoPhase > 6.283185f) _lfoPhase -= 6.283185f;
            float lfoSinA = (float) Math.sin(_lfoPhase);
            float lfoSinB = (float) Math.sin(_lfoPhase + 1.7f);
            _lfoNoiseA += 0.0008f * (nextNoise() - _lfoNoiseA);
            _lfoNoiseB += 0.0008f * (nextNoise() - _lfoNoiseB);
            float modA = (lfoSinA + _lfoNoiseA * 0.5f) * modDepth;
            float modB = (lfoSinB + _lfoNoiseB * 0.5f) * modDepth;
            float aIn = tankIn + _fb_b * feedbackG;
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
            side *= (0.2f + 1.6f * width);
            wetL = mid + side;
            wetR = mid - side;
            float wet = (wetL + wetR) * 0.5f;
            _toneLP += 0.10f * (wet - _toneLP);
            float wetHP = wet - _toneLP;
            wet = wet + toneTilt * (wetHP - _toneLP) * 0.5f;
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

    // Slider (dry/wet) is special — it's a horizontal bar rather than
    // a knob, has its own rect.
    private float sliderX0, sliderY0, sliderX1, sliderY1;

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

        // Define controls (order matters → hit-test reads back to back).
        // Left column: REFLECTIONS row + DEPTH row.
        controls[0]  = new ControlRect("size",       "SIZE",     0);
        controls[1]  = new ControlRect("decay",      "DECAY",    0);
        controls[2]  = new ControlRect("width",      "WIDTH",    0);
        controls[3]  = new ControlRect("modulation", "MOD",      0);
        controls[4]  = new ControlRect("shimmer",    "SHIMMER",  0);
        controls[5]  = new ControlRect("predelay",   "PRE",      0);
        // Right column: CLEAN-UP row + SHAPE row.
        controls[6]  = new ControlRect("damping",    "DAMP",     0);
        controls[7]  = new ControlRect("gate",       "GATE",     0);
        controls[8]  = new ControlRect("freeze",     "FREEZE",   1);  // toggle
        controls[9]  = new ControlRect("tone",       "TONE",     2);  // bipolar
        controls[10] = new ControlRect("duck",       "DUCK",     0);
        controls[11] = new ControlRect("mix",        "MIX",      0);
        // controls[12] reserved for the bottom dry/wet horiz slider —
        // but we treat that as a separate sliderXX rect, not in the
        // controls array.
        controls[12] = null;

        // Layout geometry.
        float pad = 12f;
        float headerH = 26f;
        float footerH = 36f;
        float midTop = pad + headerH;
        float midBot = H - pad - footerH;
        float midH = midBot - midTop;

        float colW = W * 0.27f;
        float leftX = pad;
        float rightX = W - pad - colW;
        float centerX0 = leftX + colW + pad;
        float centerX1 = rightX - pad;

        // 2×3 grid for each side column.
        layoutGrid(leftX, midTop, leftX + colW, midBot, 0, 6);
        layoutGrid(rightX, midTop, rightX + colW, midBot, 6, 12);

        // Footer slider (dry/wet handled via the mix knob in the grid,
        // plus a wider strip across the centre for fine adjustment).
        sliderX0 = centerX0;
        sliderY0 = midBot + 8f;
        sliderX1 = centerX1;
        sliderY1 = H - pad - 4f;
    }

    private void layoutGrid(float x0, float y0, float x1, float y1,
                            int from, int to) {
        int n = to - from;
        // 2 rows × 3 cols.
        int cols = 3, rows = 2;
        float cw = (x1 - x0) / cols;
        float rh = (y1 - y0) / rows;
        float radius = Math.min(cw, rh) * 0.30f;
        if (radius < 12f) radius = 12f;
        for (int i = 0; i < n; i++) {
            int col = i % cols;
            int row = i / cols;
            float cellX = x0 + col * cw;
            float cellY = y0 + row * rh;
            ControlRect c = controls[from + i];
            c.cx = cellX + cw * 0.5f;
            c.cy = cellY + rh * 0.42f;          // top-biased so label fits below
            c.r  = radius;
            c.bx0 = cellX; c.by0 = cellY;
            c.bx1 = cellX + cw; c.by1 = cellY + rh;
        }
    }

    // ── Touch handlers ──
    @Override public void onTouchDown(float x, float y) {
        recomputeLayout(lastW, lastH);
        // Dry/wet slider has priority — it's the wide strip across the
        // bottom and overlaps no other control.
        if (x >= sliderX0 && x <= sliderX1 && y >= sliderY0 && y <= sliderY1) {
            activeIdx = -2;
            touchDownX = x; touchDownY = y;
            dragStartValue = mix;
            // Tap-to-position: jump the slider straight to the touched x.
            float t = (x - sliderX0) / (sliderX1 - sliderX0);
            if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
            commitParam("mix", t);
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
        if (activeIdx == -1) return;
        if (activeIdx == -2) {
            // Slider: absolute X position drives value.
            float t = (x - sliderX0) / (sliderX1 - sliderX0);
            if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
            commitParam("mix", t);
            return;
        }
        ControlRect c = controls[activeIdx];
        if (c == null || c.kind == 1) return;   // toggle = no drag
        // Knob: vertical drag changes value. 200 dp = full range,
        // shift-modifier-style fine tweak handled by smaller delta-
        // per-pixel implicitly through the 200dp scale.
        float dyDp = touchDownY - y;            // up = positive
        float min = parameterMin(c.paramName);
        float max = parameterMax(c.paramName);
        float v = dragStartValue + (max - min) * (dyDp / 200f);
        if (v < min) v = min; else if (v > max) v = max;
        commitParam(c.paramName, v);
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
    private static final int FFT_SIZE = 256;
    private static final int WAVE_LANES = 20;
    private final float[][] waveLanes = new float[WAVE_LANES][FFT_SIZE / 2];
    private int waveWritePos = 0;
    private long lastFftCaptureMs = 0L;
    private final float[] fftRe = new float[FFT_SIZE];
    private final float[] fftIm = new float[FFT_SIZE];
    private final float[] hann  = new float[FFT_SIZE];
    private boolean fftInit = false;

    private static final int COLOR_BG          = 0xFF0A0A0E;
    private static final int COLOR_CARD        = 0xFFE9E9EE;
    private static final int COLOR_CARD_DARK   = 0xFFC4C4CA;
    private static final int COLOR_BUTTON_BG   = 0xFFF6F6F8;
    private static final int COLOR_INK         = 0xFF1A1A1E;
    private static final int COLOR_INK_DIM     = 0xFF6E6E76;
    private static final int COLOR_INK_INV     = 0xFFE6E6EA;
    private static final int COLOR_ACCENT      = 0xFFF5C842;  // yellow
    private static final int COLOR_ACCENT_DIM  = 0x77F5C842;
    private static final int COLOR_REFLECT     = 0xFFE0606A;  // red (reflections icons)
    private static final int COLOR_DEPTH       = 0xFF6098E0;  // blue (depth icons)
    private static final int COLOR_CLEAN       = 0xFFE09060;  // orange (clean-up icons)
    private static final int COLOR_SHAPE       = 0xFF6FE07A;  // green (shape icons)
    private static final int GRAD_LEFT   = 0xFFFFB44A;
    private static final int GRAD_MIDDLE = 0xFFF38FB7;
    private static final int GRAD_RIGHT  = 0xFF7AB6E0;

    private PluginPaint bgPaint, cardPaint, buttonPaint, valArc, valArcBg,
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

        float W = width, H = height;
        if (W < 40 || H < 40) return;

        // Pull current values from host params map (source of truth).
        // Fall back to local field state if the host didn't supply.
        for (ControlRect c : controls) {
            if (c == null) continue;
            Float v = params != null ? params.get(c.paramName) : null;
            if (v != null) setParameter(c.paramName, v);
        }

        // ── Background ──
        bgPaint.setColor(COLOR_BG);
        canvas.drawRect(0, 0, W, H, bgPaint);

        // ── Header strip ──
        headerPaint.setColor(COLOR_INK_INV).setTextSize(13f).setTextAlign(0);
        canvas.drawText("CRYSTAL", 14f, 18f, headerPaint);
        headerPaint.setColor(COLOR_INK_DIM).setTextSize(11f).setTextAlign(1);
        float decaySec = 0.2f + decay * decay * 8f;
        canvas.drawText(String.format("decay  %.1f s", decaySec), W * 0.5f, 18f, headerPaint);
        headerPaint.setColor(COLOR_INK_DIM).setTextSize(11f).setTextAlign(2);
        canvas.drawText("REVERB", W - 14f, 18f, headerPaint);

        // ── Centre gradient FFT display ──
        // Spans the full gap between the left column (controls 0..5)
        // and the right column (controls 6..11), top down to just
        // above the dry/wet slider.
        float pad = 12f;
        float headerH = 26f;
        float footerH = 36f;
        float dispX0 = controls[2].bx1 + pad;   // right edge of left col
        float dispX1 = controls[6].bx0 - pad;   // left edge of right col
        float dispY0 = pad + headerH;
        float dispY1 = H - pad - footerH;
        drawCentralDisplay(canvas, dispX0, dispY0, dispX1, dispY1, timeMs);

        // ── Section group backgrounds + labels ──
        drawSectionGroup(canvas, controls[0].bx0 - 4f, controls[0].by0 - 4f,
                          controls[2].bx1 + 4f, controls[2].by1 + 4f,
                          "REFLECTIONS", COLOR_REFLECT);
        drawSectionGroup(canvas, controls[3].bx0 - 4f, controls[3].by0 - 4f,
                          controls[5].bx1 + 4f, controls[5].by1 + 4f,
                          "DEPTH", COLOR_DEPTH);
        drawSectionGroup(canvas, controls[6].bx0 - 4f, controls[6].by0 - 4f,
                          controls[8].bx1 + 4f, controls[8].by1 + 4f,
                          "CLEAN-UP", COLOR_CLEAN);
        drawSectionGroup(canvas, controls[9].bx0 - 4f, controls[9].by0 - 4f,
                          controls[11].bx1 + 4f, controls[11].by1 + 4f,
                          "SHAPE", COLOR_SHAPE);

        // ── Draw every control ──
        for (int i = 0; i < controls.length; i++) {
            ControlRect c = controls[i];
            if (c == null) continue;
            drawControl(canvas, c, i == activeIdx);
        }

        // ── Bottom OUTPUT row: DRY/WET slider ──
        drawDryWetSlider(canvas, sliderX0, sliderY0, sliderX1, sliderY1, mix);
    }

    private void drawSectionGroup(PluginCanvas canvas, float x0, float y0,
                                   float x1, float y1, String label, int accent) {
        cardPaint.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, y0, x1, y1, 8f, cardPaint);
        cardPaint.setColor(0x33000000).setStyle(PluginStyle.STROKE).setStrokeWidth(1f);
        canvas.drawRoundRect(x0, y0, x1, y1, 8f, cardPaint);
        sectionLabel.setColor(COLOR_INK_DIM).setTextSize(9f).setTextAlign(1);
        canvas.drawText(label, (x0 + x1) * 0.5f, y0 + 11f, sectionLabel);
    }

    // Draw a single knob/toggle with its label.
    private void drawControl(PluginCanvas canvas, ControlRect c, boolean active) {
        float val = getParameterValue(c.paramName);
        float min = parameterMin(c.paramName);
        float max = parameterMax(c.paramName);
        float norm = max > min ? (val - min) / (max - min) : 0f;
        if (norm < 0f) norm = 0f; else if (norm > 1f) norm = 1f;

        // White button background (Crystalline-style).
        buttonPaint.setColor(COLOR_BUTTON_BG).setStyle(PluginStyle.FILL);
        canvas.drawCircle(c.cx, c.cy, c.r, buttonPaint);
        buttonPaint.setColor(active ? COLOR_ACCENT : 0x33000000)
                .setStyle(PluginStyle.STROKE).setStrokeWidth(active ? 2f : 1f);
        canvas.drawCircle(c.cx, c.cy, c.r, buttonPaint);

        // Value arc around the knob (yellow, like a tiny ring meter).
        // 270° sweep from 7-o'clock to 5-o'clock (Crystalline style).
        drawValueArc(canvas, c.cx, c.cy, c.r + 4f, norm, c.kind == 2);

        // Icon centred on the knob — colourful glyph that suggests
        // what the parameter is doing. Each icon scales subtly with
        // value so the user sees the knob is "alive".
        drawIcon(canvas, c.paramName, c.cx, c.cy, c.r * 0.85f, norm);

        // Label below.
        labelPaint.setColor(COLOR_INK).setTextSize(9.5f).setTextAlign(1);
        canvas.drawText(c.label, c.cx, c.by1 - 6f, labelPaint);

        // Toggle ON-pill for the freeze button.
        if (c.kind == 1 && norm >= 0.5f) {
            buttonPaint.setColor(COLOR_ACCENT).setStyle(PluginStyle.FILL);
            canvas.drawRoundRect(c.cx - 14f, c.by1 - 22f, c.cx + 14f, c.by1 - 10f, 5f, buttonPaint);
            labelPaint.setColor(0xFF101010).setTextSize(8.5f).setTextAlign(1);
            canvas.drawText("ON", c.cx, c.by1 - 13f, labelPaint);
        }
    }

    private void drawValueArc(PluginCanvas canvas, float cx, float cy,
                               float r, float norm, boolean bipolar) {
        // Approximate an arc using a 32-segment polyline. Real APIs
        // would have drawArc; this draws short tangent line segments.
        valArcBg.setColor(0xFFD0D0D6).setStyle(PluginStyle.STROKE).setStrokeWidth(2f);
        valArc.setColor(COLOR_ACCENT).setStyle(PluginStyle.STROKE).setStrokeWidth(2.5f);
        // Sweep from 7-o'clock (225°) to 5-o'clock (-45°) → 270° total.
        // Convert to math radians: 225° = 5π/4, -45° = -π/4
        float startDeg = 225f, sweep = 270f;
        // Background full arc.
        drawArc(canvas, cx, cy, r, startDeg, sweep, valArcBg);
        if (bipolar) {
            // Bipolar (centred at 50% = 0): the arc fills outward from
            // the centre to the current value, so positive values fill
            // right, negative fill left.
            float centreOffset = sweep * 0.5f;
            float fromAngle = startDeg + centreOffset;       // 12 o'clock
            float toAngle = startDeg + sweep * norm;
            float fromA = fromAngle, toA = toAngle;
            if (fromA > toA) { float t = fromA; fromA = toA; toA = t; }
            drawArc(canvas, cx, cy, r, fromA, toA - fromA, valArc);
        } else {
            // Unipolar: fill from start angle up to norm fraction.
            drawArc(canvas, cx, cy, r, startDeg, sweep * norm, valArc);
        }
    }

    private void drawArc(PluginCanvas canvas, float cx, float cy, float r,
                          float startDeg, float sweepDeg, PluginPaint paint) {
        int segs = Math.max(2, (int)(Math.abs(sweepDeg) / 6f));
        float ang0 = (float) Math.toRadians(startDeg);
        float angStep = (float) Math.toRadians(sweepDeg) / segs;
        float px = cx + r * (float) Math.cos(ang0);
        float py = cy + r * (float) Math.sin(ang0);
        for (int s = 1; s <= segs; s++) {
            float a = ang0 + angStep * s;
            float nx = cx + r * (float) Math.cos(a);
            float ny = cy + r * (float) Math.sin(a);
            canvas.drawLine(px, py, nx, ny, paint);
            px = nx; py = ny;
        }
    }

    // Per-parameter icon — each parameter gets a visual glyph in its
    // theme colour, so the user can read the panel without labels.
    private void drawIcon(PluginCanvas canvas, String param, float cx, float cy,
                           float s, float norm) {
        switch (param) {
            case "size": {
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
                // Concentric arcs widening with value.
                for (int i = 0; i < 3; i++) {
                    float r = s * (0.18f + 0.16f * i + 0.10f * norm * i);
                    canvas.drawCircle(cx, cy, r, iconPaint);
                }
                break;
            }
            case "decay": {
                // Falling envelope: 4 vertical bars descending in height
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.FILL);
                for (int i = 0; i < 5; i++) {
                    float bx = cx - s * 0.4f + i * (s * 0.18f);
                    float bh = s * (0.55f - i * 0.10f) * (0.4f + 0.6f * norm);
                    canvas.drawRect(bx - 1.5f, cy - bh, bx + 1.5f, cy + s * 0.15f, iconPaint);
                }
                break;
            }
            case "width": {
                // Circle, fatter when wider.
                iconPaint.setColor(COLOR_REFLECT).setStyle(PluginStyle.STROKE)
                        .setStrokeWidth(1.5f + 2.5f * norm);
                canvas.drawCircle(cx, cy, s * 0.40f, iconPaint);
                break;
            }
            case "modulation": {
                // Sine wave that gets wavier with norm.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
                path1.reset();
                float w = s * 0.7f;
                float amp = s * (0.10f + 0.25f * norm);
                int npts = 24;
                for (int i = 0; i <= npts; i++) {
                    float t = i / (float) npts;
                    float px = cx - w * 0.5f + t * w;
                    float py = cy + (float)(Math.sin(t * 2 * Math.PI * 1.5) * amp);
                    if (i == 0) path1.moveTo(px, py);
                    else path1.lineTo(px, py);
                }
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "shimmer": {
                // Dots in a grid — denser with norm.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.FILL);
                int dots = (int)(3 + 6 * norm);
                for (int i = 0; i < dots; i++) {
                    double a = i * 2.3994f;  // golden angle for nice scatter
                    float rr = s * 0.40f * ((i + 1) / (float) dots);
                    float dx = (float)(rr * Math.cos(a));
                    float dy = (float)(rr * Math.sin(a));
                    canvas.drawCircle(cx + dx, cy + dy, 1.4f, iconPaint);
                }
                break;
            }
            case "predelay": {
                // Horizontal arrow → length scales with norm.
                iconPaint.setColor(COLOR_DEPTH).setStyle(PluginStyle.STROKE).setStrokeWidth(1.8f);
                float arrowLen = s * (0.25f + 0.55f * norm);
                canvas.drawLine(cx - s * 0.40f, cy, cx - s * 0.40f + arrowLen, cy, iconPaint);
                iconPaint.setStyle(PluginStyle.FILL);
                path1.reset();
                float tipX = cx - s * 0.40f + arrowLen;
                path1.moveTo(tipX, cy);
                path1.lineTo(tipX - 5f, cy - 4f);
                path1.lineTo(tipX - 5f, cy + 4f).close();
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "damping": {
                // Hill curve (lowpass-ish).
                iconPaint.setColor(COLOR_CLEAN).setStyle(PluginStyle.STROKE).setStrokeWidth(1.8f);
                path1.reset();
                float w = s * 0.7f, h = s * 0.4f;
                path1.moveTo(cx - w * 0.5f, cy + h * 0.4f);
                path1.quadTo(cx - w * 0.15f, cy - h, cx + w * 0.5f,
                              cy + h * (0.4f - 0.7f * norm));
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "gate": {
                // Gate-shape (down-up-down step).
                iconPaint.setColor(COLOR_CLEAN).setStyle(PluginStyle.STROKE).setStrokeWidth(1.8f);
                path1.reset();
                float w = s * 0.7f, h = s * 0.35f;
                path1.moveTo(cx - w * 0.5f, cy + h);
                path1.lineTo(cx - w * 0.2f, cy + h);
                path1.lineTo(cx - w * 0.2f, cy - h);
                path1.lineTo(cx + w * 0.2f, cy - h);
                path1.lineTo(cx + w * 0.2f, cy + h);
                path1.lineTo(cx + w * 0.5f, cy + h);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "freeze": {
                // Snowflake-ish: 6 spokes from centre.
                iconPaint.setColor(norm >= 0.5f ? COLOR_ACCENT : COLOR_CLEAN)
                        .setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
                for (int i = 0; i < 6; i++) {
                    float a = (float)(i * Math.PI / 3);
                    canvas.drawLine(cx, cy, cx + s * 0.35f * (float)Math.cos(a),
                                            cy + s * 0.35f * (float)Math.sin(a), iconPaint);
                }
                break;
            }
            case "tone": {
                // S-tilt curve (rising line) — slope depends on tone.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.8f);
                path1.reset();
                float w = s * 0.7f, h = s * 0.35f;
                // norm 0..1 → tilt slope -h..+h
                float slope = (norm * 2f - 1f) * h;
                path1.moveTo(cx - w * 0.5f, cy + slope);
                path1.quadTo(cx, cy + slope * 0.3f, cx + w * 0.5f, cy - slope);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "duck": {
                // Ducking arrow → curve dipping with value.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.8f);
                path1.reset();
                float w = s * 0.7f, h = s * 0.35f;
                path1.moveTo(cx - w * 0.5f, cy - h * 0.4f);
                path1.lineTo(cx - w * 0.15f, cy - h * 0.4f);
                path1.quadTo(cx, cy + h * norm, cx + w * 0.15f, cy - h * 0.4f);
                path1.lineTo(cx + w * 0.5f, cy - h * 0.4f);
                canvas.drawPath(path1, iconPaint);
                break;
            }
            case "mix": {
                // Two overlapping circles, opacity per side.
                iconPaint.setColor(COLOR_SHAPE).setStyle(PluginStyle.STROKE).setStrokeWidth(1.6f);
                canvas.drawCircle(cx - s * 0.16f, cy, s * 0.22f, iconPaint);
                iconPaint.setColor(0xCCF5C842);
                canvas.drawCircle(cx + s * 0.16f, cy, s * 0.22f, iconPaint);
                break;
            }
            default: {
                // Fallback dot.
                iconPaint.setColor(COLOR_INK).setStyle(PluginStyle.FILL);
                canvas.drawCircle(cx, cy, 3f, iconPaint);
            }
        }
    }

    private void drawDryWetSlider(PluginCanvas canvas, float x0, float y0,
                                   float x1, float y1, float value) {
        // Track.
        float midY = (y0 + y1) * 0.5f;
        sliderTrack.setColor(COLOR_CARD).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, midY - 4f, x1, midY + 4f, 4f, sliderTrack);
        // Fill up to value.
        float vx = x0 + (x1 - x0) * Math.max(0f, Math.min(1f, value));
        sliderFill.setColor(COLOR_ACCENT).setStyle(PluginStyle.FILL);
        canvas.drawRoundRect(x0, midY - 4f, vx, midY + 4f, 4f, sliderFill);
        // Handle.
        sliderHandle.setColor(COLOR_INK_INV).setStyle(PluginStyle.FILL);
        canvas.drawCircle(vx, midY, 8f, sliderHandle);
        sliderHandle.setColor(COLOR_INK).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
        canvas.drawCircle(vx, midY, 8f, sliderHandle);
        // Label.
        labelPaint.setColor(COLOR_INK_INV).setTextSize(10f).setTextAlign(0);
        canvas.drawText("DRY / WET", x0, y0 - 2f, labelPaint);
        labelPaint.setColor(COLOR_ACCENT).setTextSize(10f).setTextAlign(2);
        canvas.drawText(String.format("%.0f%%", value * 100), x1, y0 - 2f, labelPaint);
    }

    private void drawCentralDisplay(PluginCanvas canvas, float x0, float y0,
                                     float x1, float y1, long timeMs) {
        if (x1 - x0 < 40f || y1 - y0 < 40f) return;
        displayBg.setStyle(PluginStyle.FILL)
                .setLinearGradient(x0, y0, x1, y0,
                        new int[] { GRAD_LEFT, GRAD_MIDDLE, GRAD_RIGHT },
                        new float[] { 0f, 0.5f, 1f });
        canvas.drawRoundRect(x0, y0, x1, y1, 14f, displayBg);

        if (timeMs - lastFftCaptureMs >= 33L) {
            captureFftSlice();
            lastFftCaptureMs = timeMs;
        }

        canvas.save();
        canvas.clipRect(x0 + 6f, y0 + 6f, x1 - 6f, y1 - 6f);
        float innerW = (x1 - x0) - 24f;
        float innerH = (y1 - y0) - 24f;
        float centreY = (y0 + y1) * 0.5f;
        for (int lane = 0; lane < WAVE_LANES; lane++) {
            int age = (WAVE_LANES - 1) - lane;
            int idx = (waveWritePos - age - 1 + WAVE_LANES * 2) % WAVE_LANES;
            float[] frame = waveLanes[idx];
            float laneOffset = -age * (innerH * 0.012f);
            float laneShift  = age * 3f;
            int alpha = (int)(255 * (1f - age / (float)(WAVE_LANES + 4)));
            if (alpha < 32) alpha = 32;
            int col = (alpha << 24) | 0x00FFFFFF;
            wavePath.reset();
            int bins = frame.length;
            for (int b = 0; b < bins; b++) {
                float t = b / (float)(bins - 1);
                float px = x0 + 12f + laneShift + t * (innerW - laneShift);
                float mag = frame[b];
                float py = centreY + laneOffset - mag * innerH * 0.32f;
                if (b == 0) wavePath.moveTo(px, py);
                else        wavePath.lineTo(px, py);
            }
            lanePaint.setColor(col).setStyle(PluginStyle.STROKE)
                    .setStrokeWidth(age == 0 ? 1.7f : 1.0f);
            canvas.drawPath(wavePath, lanePaint);
        }
        canvas.restore();
        displayBorder.setColor(0x66000000).setStyle(PluginStyle.STROKE).setStrokeWidth(1.5f);
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

    private void initPaints(PluginCanvas c) {
        bgPaint       = c.newPaint();
        cardPaint     = c.newPaint();
        buttonPaint   = c.newPaint();
        valArc        = c.newPaint();
        valArcBg      = c.newPaint();
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
