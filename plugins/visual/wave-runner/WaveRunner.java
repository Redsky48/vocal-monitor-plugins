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
 * Wave Runner — faithful native port of wave-engine.js.
 *
 * Each layer is rendered with the same math as the JS engine
 * (bin sampling → asymmetric smoothing → shapeMagnitude(contrast,
 * tilt) → response curve → height clamp → paint), and the colour
 * modes / paint styles / path construction all mirror the JS
 * implementation 1:1. Multi-layer composites apply each layer's
 * blend mode + opacity via PluginPaint.setBlendMode.
 *
 * Known limitations vs. the JS engine:
 *   • No `fxFade` trail — would require an offscreen bitmap, which
 *     the PluginCanvas API doesn't expose. Layers with fxFade > 0
 *     simply render as if fxFade == 0.
 *   • No `fxGlow` / `fxBlur` post-effects yet (PluginPaint has
 *     setGlow but the per-bar overhead would dominate the test).
 *
 * The hardcoded test wave below mirrors a typical 2-layer build
 * (bars + line, frequency colours, center align, spline + mirror
 * on the line) so the user can A/B against the same config in
 * the JS Waveform Builder.
 */
public final class WaveRunner
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    // ─── Audio analysis ──────────────────────────────────────────────
    private static final int BINS = 64;
    private static final int WINDOW = 2048;
    private static final float MIN_HZ = 40f;
    private static final float MAX_HZ = 16_000f;

    private int sampleRate = 44100;

    private final float[] bandFreq  = new float[BINS];
    private final float[] bandCoeff = new float[BINS];
    private final float[] bandLevel = new float[BINS];

    private final float[] ring    = new float[WINDOW];
    private int           ringW   = 0;
    private final float[] scratch = new float[WINDOW];
    private final float[] hann    = new float[WINDOW];

    // ─── Wave config ────────────────────────────────────────────────
    private Wave activeWave;
    // Per-layer per-bar smoothing state, paint scratch, etc. lives
    // on the Layer itself so multiple layers of the same type don't
    // share buffers.

    // ─── Parameters ──────────────────────────────────────────────────
    // `style` picks which hardcoded wave preset to render. Slider in
    // the host's plugin panel; the host coerces float values, so we
    // floor to an int and clamp to PRESET_COUNT - 1.
    private static final String PARAM_STYLE = "style";
    private static final int    PRESET_COUNT = 4;
    private int styleIdx = 0;

    // ─── Pluggable paint cache ───────────────────────────────────────
    private PluginPaint paintFill;
    private PluginPaint paintStroke;
    private PluginPaint paintArea;

    @Override public void init(int sr) {
        this.sampleRate = sr;
        double minLn = Math.log(MIN_HZ);
        double maxLn = Math.log(MAX_HZ);
        for (int b = 0; b < BINS; b++) {
            double t = b / (double) (BINS - 1);
            float f = (float) Math.exp(minLn + (maxLn - minLn) * t);
            bandFreq[b]  = f;
            double omega = 2.0 * Math.PI * f / sr;
            bandCoeff[b] = (float) (2.0 * Math.cos(omega));
            bandLevel[b] = 0f;
        }
        for (int i = 0; i < WINDOW; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WINDOW - 1)));
            ring[i] = 0f;
        }
        ringW = 0;
        activeWave = buildPreset(styleIdx);
    }

    @Override public String[] parameterNames() {
        return new String[]{ PARAM_STYLE };
    }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n) {
        return PARAM_STYLE.equals(n) ? (float)(PRESET_COUNT - 1) : 1f;
    }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) {
        // Pretty-print preset names on the slider so the user sees
        // what they're picking. The host shows label next to the
        // numeric value, so this maps "0..3 → name".
        if (PARAM_STYLE.equals(n)) return "Style";
        return n;
    }
    @Override public void setParameter(String n, float v) {
        if (PARAM_STYLE.equals(n)) {
            int idx = (int) Math.floor(v);
            if (idx < 0) idx = 0;
            if (idx > PRESET_COUNT - 1) idx = PRESET_COUNT - 1;
            if (idx != styleIdx) {
                styleIdx = idx;
                // Build the new active wave + reset per-layer state so
                // smoothing buffers from the previous preset don't
                // leak. Layer count / type may differ.
                activeWave = buildPreset(idx);
            }
        }
    }

    @Override public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            float s = input[i];
            output[i] = s;
            ring[ringW] = s;
            ringW++;
            if (ringW >= WINDOW) ringW = 0;
        }
    }

    // ─── Visual ──────────────────────────────────────────────────────

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        if (paintFill == null) {
            paintFill   = canvas.newPaint().setStyle(PluginStyle.FILL).setAntialias(true);
            paintStroke = canvas.newPaint().setStyle(PluginStyle.STROKE).setAntialias(true);
            paintArea   = canvas.newPaint().setStyle(PluginStyle.FILL).setAntialias(true);
        }
        // 1. Pull audio + compute spectrum.
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 64) {
            int w = ringW;
            for (int i = 0; i < WINDOW; i++) scratch[i] = ring[(w + i) % WINDOW];
            wave = scratch;
        }
        float peak = 0f;
        for (int i = 0; i < wave.length; i++) {
            float a = wave[i] < 0 ? -wave[i] : wave[i];
            if (a > peak) peak = a;
        }
        if (peak > 1e-5f) analyseBands(wave);

        // 2. Energy used by 'energy' colour mode + pulse animation.
        float energy = 0f;
        for (int i = 0; i < BINS; i++) energy += bandLevel[i];
        energy /= BINS;

        // 3. Composite layers.
        if (activeWave == null) return;
        final float W = width, H = height;
        final float tSec = timeMs / 1000f;
        for (int li = 0; li < activeWave.layers.length; li++) {
            Layer L = activeWave.layers[li];
            if (!L.visible) continue;
            // Compose paint with this layer's blend mode + opacity.
            // PluginPaint.setBlendMode persists until cleared, so
            // restore to SRC_OVER at the end of every layer pass.
            BlendMode bm = mapBlend(L.fxBlend);
            paintFill.setBlendMode(bm);
            paintStroke.setBlendMode(bm);
            paintArea.setBlendMode(bm);
            if (L.type == LayerType.BARS) {
                renderBars(canvas, W, H, L, energy, tSec);
            } else if (L.type == LayerType.LINE) {
                renderLine(canvas, W, H, L, energy, tSec);
            }
        }
        paintFill.setBlendMode(BlendMode.SRC_OVER);
        paintStroke.setBlendMode(BlendMode.SRC_OVER);
        paintArea.setBlendMode(BlendMode.SRC_OVER);
    }

    // ─── Bars renderer ───────────────────────────────────────────────

    private void renderBars(
            PluginCanvas canvas, float W, float H,
            Layer L, float energy, float tSec
    ) {
        int n = Math.max(2, L.count);
        // Per-layer smoothed buffer.
        if (L._smoothed == null || L._smoothed.length != n) L._smoothed = new float[n];
        float[] sm = L._smoothed;

        float slot = W / n;
        float widW = slot * (Math.max(1, Math.min(100, L.width))) / 100f;
        float gap  = Math.max(0, L.gap);
        float barW = Math.max(1f, widW - gap);
        float scale = L.scale;
        float minH  = Math.max(0, L.minHeight);
        float round = Math.max(0, L.round);

        // Asym smoothing factors — see shapeMagnitude comment.
        float attack  = clamp(L.smoothAttack  >= 0 ? L.smoothAttack  : L.smooth, 0, 99) / 100f;
        float release = clamp(L.smoothRelease >= 0 ? L.smoothRelease : L.smooth, 0, 99) / 100f;

        int opacityPct = (L.fxOpacity < 0) ? 100 : L.fxOpacity;
        float alphaMul = opacityPct / 100f;

        for (int i = 0; i < n; i++) {
            int idx = (int) Math.floor((i / (float) n) * BINS);
            if (idx < 0) idx = 0; else if (idx >= BINS) idx = BINS - 1;
            float raw = bandLevel[idx];
            float k = raw > sm[i] ? attack : release;
            sm[i] = sm[i] * k + raw * (1f - k);
            float fi = i / (float) Math.max(1, n - 1);
            float cv = curveAt(L.respCurve, fi);
            float m = shapeMagnitude(sm[i], fi, L.animContrast, L.animTilt) * cv;
            float bh = Math.min(H, Math.max(minH, m * H * scale));
            if (bh <= 0f) continue;
            float x = i * slot + (slot - barW) * 0.5f;
            float y = alignY(L.align, H, bh);
            int color = applyAlpha(barColor(L, fi, m, energy), alphaMul);
            paintBar(canvas, L, x, y, barW, bh, round, color);
        }
    }

    private void paintBar(
            PluginCanvas canvas, Layer L,
            float x, float y, float bw, float bh, float round, int color
    ) {
        if (bh <= 0 || bw <= 0) return;
        String style = L.fxStyle == null ? "fill" : L.fxStyle;
        if (style.equals("outline")) {
            float sw = L.fxStrokeW > 0 ? L.fxStrokeW : 2f;
            paintStroke.setColor(color).setStrokeWidth(sw).clearShader();
            if (round > 0f) {
                canvas.drawRoundRect(x, y, x + bw, y + bh, Math.min(round, Math.min(bw, bh) / 2f), paintStroke);
            } else {
                canvas.drawRect(x, y, x + bw, y + bh, paintStroke);
            }
        } else {
            paintFill.setColor(color).clearShader();
            if (round > 0f) {
                canvas.drawRoundRect(x, y, x + bw, y + bh, Math.min(round, Math.min(bw, bh) / 2f), paintFill);
            } else {
                canvas.drawRect(x, y, x + bw, y + bh, paintFill);
            }
        }
    }

    // ─── Line renderer ───────────────────────────────────────────────

    private void renderLine(
            PluginCanvas canvas, float W, float H,
            Layer L, float energy, float tSec
    ) {
        int n = Math.max(2, L.count);
        if (L._smoothed == null || L._smoothed.length != n) L._smoothed = new float[n];
        float[] sm = L._smoothed;

        float scale = L.scale;
        float minH = Math.max(0, L.minHeight);
        float strokeW = Math.max(0.5f, L.strokeW);
        float attack  = clamp(L.smoothAttack  >= 0 ? L.smoothAttack  : L.smooth, 0, 99) / 100f;
        float release = clamp(L.smoothRelease >= 0 ? L.smoothRelease : L.smooth, 0, 99) / 100f;

        int opacityPct = (L.fxOpacity < 0) ? 100 : L.fxOpacity;
        float alphaMul = opacityPct / 100f;

        boolean elastic = L.elastic;
        float elasticT = elastic ? tSec * 2f : 0f;

        float baseline =
            L.align.equals("top")    ? strokeW * 0.5f :
            L.align.equals("center") ? H * 0.5f :
                                       H - strokeW * 0.5f;
        float dir = L.align.equals("top") ? +1f : -1f;
        float ampMul = L.align.equals("center") ? 0.5f : 1f;
        float maxAmp = H - strokeW;

        float[] xs   = scratchX(n);
        float[] ys   = scratchY(n);
        float[] mags = scratchM(n);

        for (int i = 0; i < n; i++) {
            int idx = (int) Math.floor((i / (float) n) * BINS);
            if (idx < 0) idx = 0; else if (idx >= BINS) idx = BINS - 1;
            float raw = bandLevel[idx];
            float k = raw > sm[i] ? attack : release;
            sm[i] = sm[i] * k + raw * (1f - k);
            float fi = i / (float) Math.max(1, n - 1);
            float cv = curveAt(L.respCurve, fi);
            float m = shapeMagnitude(sm[i], fi, L.animContrast, L.animTilt) * cv;
            mags[i] = m;
            float amp = Math.min(maxAmp, Math.max(minH, m * H * scale * ampMul));
            float sign = elastic ? (float) Math.sin(i * 0.55f + elasticT) : 1f;
            xs[i] = (i / (float) Math.max(1, n - 1)) * W;
            ys[i] = baseline + dir * sign * amp;
        }

        // Optional fill below the line.
        if (L.fillBelow && n > 1) {
            int fillOp = (L.fillOpacity < 0) ? 30 : L.fillOpacity;
            float fillAlpha = (fillOp / 100f) * alphaMul;
            PluginPath path = canvas.newPath();
            buildLinePath(path, xs, ys, n, L.spline);
            path.lineTo(xs[n - 1], baseline);
            path.lineTo(xs[0], baseline);
            path.close();
            if (L.colorMode.equals("gradient")) {
                int c0 = applyAlpha(parseHex(L.color,  0xFFFFFFFF), fillAlpha);
                int c1 = applyAlpha(parseHex(L.color2, 0xFF5E7EFF), fillAlpha);
                paintArea.setLinearGradient(0, 0, W, 0, new int[]{c0, c1}, new float[]{0f, 1f});
            } else {
                int c = applyAlpha(parseHex(L.color, 0xFFFFFFFF), fillAlpha);
                paintArea.clearShader().setColor(c);
            }
            canvas.drawPath(path, paintArea);
        }

        // Stroke the line.
        if (L.colorMode.equals("solid")) {
            int c = applyAlpha(parseHex(L.color, 0xFFFFFFFF), alphaMul);
            paintStroke.setColor(c).clearShader().setStrokeWidth(strokeW);
            PluginPath path = canvas.newPath();
            buildLinePath(path, xs, ys, n, L.spline);
            canvas.drawPath(path, paintStroke);
        } else if (L.colorMode.equals("gradient")) {
            int c0 = applyAlpha(parseHex(L.color,  0xFFFFFFFF), alphaMul);
            int c1 = applyAlpha(parseHex(L.color2, 0xFF5E7EFF), alphaMul);
            paintStroke.setLinearGradient(0, 0, W, 0, new int[]{c0, c1}, new float[]{0f, 1f})
                       .setStrokeWidth(strokeW);
            PluginPath path = canvas.newPath();
            buildLinePath(path, xs, ys, n, L.spline);
            canvas.drawPath(path, paintStroke);
        } else {
            // Per-segment colour. Straight segments only — matches the
            // JS engine when spline + per-segment colour are combined
            // (the bezier helper there still uses control points but
            // strokes each segment individually, so visual is the
            // same as joined straight strokes at thumb scale).
            for (int i = 0; i < n - 1; i++) {
                float fi = i / (float) Math.max(1, n - 1);
                float mAvg = (mags[i] + mags[i + 1]) * 0.5f;
                int c = applyAlpha(barColor(L, fi, mAvg, energy), alphaMul);
                paintStroke.setColor(c).clearShader().setStrokeWidth(strokeW);
                canvas.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], paintStroke);
            }
        }

        // Mirror — re-stroke a vertically-flipped copy.
        if (L.mirror) {
            float[] mys = scratchY2(n);
            for (int i = 0; i < n; i++) mys[i] = 2f * baseline - ys[i];
            if (L.colorMode.equals("solid") || L.colorMode.equals("gradient")) {
                if (L.colorMode.equals("solid")) {
                    int c = applyAlpha(parseHex(L.color, 0xFFFFFFFF), alphaMul);
                    paintStroke.setColor(c).clearShader().setStrokeWidth(strokeW);
                } else {
                    int c0 = applyAlpha(parseHex(L.color,  0xFFFFFFFF), alphaMul);
                    int c1 = applyAlpha(parseHex(L.color2, 0xFF5E7EFF), alphaMul);
                    paintStroke.setLinearGradient(0, 0, W, 0, new int[]{c0, c1}, new float[]{0f, 1f})
                               .setStrokeWidth(strokeW);
                }
                PluginPath path = canvas.newPath();
                buildLinePath(path, xs, mys, n, L.spline);
                canvas.drawPath(path, paintStroke);
            } else {
                for (int i = 0; i < n - 1; i++) {
                    float fi = i / (float) Math.max(1, n - 1);
                    float mAvg = (mags[i] + mags[i + 1]) * 0.5f;
                    int c = applyAlpha(barColor(L, fi, mAvg, energy), alphaMul);
                    paintStroke.setColor(c).clearShader().setStrokeWidth(strokeW);
                    canvas.drawLine(xs[i], mys[i], xs[i + 1], mys[i + 1], paintStroke);
                }
            }
        }
    }

    private void buildLinePath(PluginPath path, float[] xs, float[] ys, int n, boolean spline) {
        path.moveTo(xs[0], ys[0]);
        if (!spline || n < 3) {
            for (int i = 1; i < n; i++) path.lineTo(xs[i], ys[i]);
            return;
        }
        // Catmull-Rom → cubic bezier. Same coefficients as the JS port.
        for (int i = 0; i < n - 1; i++) {
            int i0 = Math.max(0, i - 1);
            int i1 = i;
            int i2 = i + 1;
            int i3 = Math.min(n - 1, i + 2);
            float c1x = xs[i1] + (xs[i2] - xs[i0]) / 6f;
            float c1y = ys[i1] + (ys[i2] - ys[i0]) / 6f;
            float c2x = xs[i2] - (xs[i3] - xs[i1]) / 6f;
            float c2y = ys[i2] - (ys[i3] - ys[i1]) / 6f;
            path.cubicTo(c1x, c1y, c2x, c2y, xs[i2], ys[i2]);
        }
    }

    // ─── Math helpers ────────────────────────────────────────────────

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float alignY(String align, float h, float bh) {
        if ("top".equals(align))    return 0f;
        if ("center".equals(align)) return (h - bh) * 0.5f;
        return h - bh;
    }

    private static float shapeMagnitude(float value, float posFrac, float contrastPct, float tiltPct) {
        float v = value;
        float tilt = tiltPct / 100f;
        if (tilt != 0f) {
            float mult = 1f + tilt * (posFrac * 2f - 1f);
            v = Math.max(0f, v * mult);
        }
        float gamma = Math.max(0.1f, contrastPct / 100f);
        if (gamma != 1f) {
            float clamped = Math.max(0f, Math.min(1f, v));
            v = (float) Math.pow(clamped, gamma);
        }
        return v;
    }

    private static float curveAt(float[] curve, float t) {
        if (curve == null || curve.length == 0) return 1f;
        if (t <= 0f) return curve[0];
        if (t >= 1f) return curve[curve.length - 1];
        float p = t * (curve.length - 1);
        int i = (int) Math.floor(p);
        float f = p - i;
        int j = Math.min(curve.length - 1, i + 1);
        return curve[i] + (curve[j] - curve[i]) * f;
    }

    // Same colour modes as wave-engine.js barColor().
    private static int barColor(Layer L, float posFrac, float magnitude, float energy) {
        float hueShift = L.hueShift;
        float p = clamp(posFrac, 0f, 1f);
        String mode = L.colorMode == null ? "solid" : L.colorMode;
        switch (mode) {
            case "frequency":
                return hslToArgb(hueShift + p * 300f, 0.95f, 0.6f);
            case "power":
                return hslToArgb(hueShift + 240f - magnitude * 240f, 0.95f, 0.6f);
            case "energy":
                return hslToArgb(hueShift + 240f - energy * 240f, 0.95f, 0.6f);
            case "gradient":
                return hexLerp(parseHex(L.color, 0xFFFFFFFF), parseHex(L.color2, 0xFF5E7EFF), p);
            default:
                return parseHex(L.color, 0xFFFFFFFF);
        }
    }

    private static int hexLerp(int a, int b, float t) {
        float tt = clamp(t, 0f, 1f);
        int aA = (a >>> 24) & 0xFF, aR = (a >>> 16) & 0xFF, aG = (a >>> 8) & 0xFF, aB = a & 0xFF;
        int bA = (b >>> 24) & 0xFF, bR = (b >>> 16) & 0xFF, bG = (b >>> 8) & 0xFF, bB = b & 0xFF;
        int rA = Math.round(aA + (bA - aA) * tt);
        int rR = Math.round(aR + (bR - aR) * tt);
        int rG = Math.round(aG + (bG - aG) * tt);
        int rB = Math.round(aB + (bB - aB) * tt);
        return (rA << 24) | (rR << 16) | (rG << 8) | rB;
    }

    private static int parseHex(String hex, int fallback) {
        if (hex == null || hex.length() < 7 || hex.charAt(0) != '#') return fallback;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return (0xFF << 24) | (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int applyAlpha(int argb, float alphaMul) {
        if (alphaMul >= 1f) return argb;
        int a0 = (argb >>> 24) & 0xFF;
        int a  = Math.round(a0 * Math.max(0f, alphaMul));
        if (a < 0) a = 0; else if (a > 255) a = 255;
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int hslToArgb(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = l - c / 2f;
        float r1, g1, b1;
        if      (h < 60f)  { r1 = c; g1 = x; b1 = 0f; }
        else if (h < 120f) { r1 = x; g1 = c; b1 = 0f; }
        else if (h < 180f) { r1 = 0f; g1 = c; b1 = x; }
        else if (h < 240f) { r1 = 0f; g1 = x; b1 = c; }
        else if (h < 300f) { r1 = x; g1 = 0f; b1 = c; }
        else               { r1 = c; g1 = 0f; b1 = x; }
        int r = Math.round((r1 + m) * 255f);
        int g = Math.round((g1 + m) * 255f);
        int bI = Math.round((b1 + m) * 255f);
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (bI < 0) bI = 0; else if (bI > 255) bI = 255;
        return (0xFF << 24) | (r << 16) | (g << 8) | bI;
    }

    private static BlendMode mapBlend(String b) {
        // PluginCanvas exposes the Porter-Duff core set + the common
        // photographic modes; modes the host doesn't carry (difference,
        // hard-light) fall back to the closest supported neighbour
        // rather than dropping to SRC_OVER, so the visual stays close
        // to what the JS engine produces.
        if (b == null) return BlendMode.SRC_OVER;
        switch (b) {
            case "screen":      return BlendMode.SCREEN;
            case "lighten":     return BlendMode.LIGHTEN;
            case "multiply":    return BlendMode.MULTIPLY;
            case "overlay":     return BlendMode.OVERLAY;
            case "color-dodge": return BlendMode.COLOR_DODGE;
            // No DIFFERENCE / HARD_LIGHT in PluginCanvas — substitute
            // OVERLAY which preserves the same "punch contrast"
            // character without going negative-pixels-weird.
            case "difference":  return BlendMode.OVERLAY;
            case "hard-light":  return BlendMode.OVERLAY;
            default:            return BlendMode.SRC_OVER;
        }
    }

    // ─── Spectrum analysis ───────────────────────────────────────────

    private void analyseBands(float[] wave) {
        int n = Math.min(wave.length, WINDOW);
        for (int i = 0; i < n; i++) scratch[i] = wave[i] * hann[i];
        float norm = 40f / n;
        for (int b = 0; b < BINS; b++) {
            float coeff = bandCoeff[b];
            float s1 = 0f, s2 = 0f;
            for (int i = 0; i < n; i++) {
                float s0 = coeff * s1 - s2 + scratch[i];
                s2 = s1; s1 = s0;
            }
            float mag = (float) Math.sqrt(
                Math.max(0f, s1 * s1 + s2 * s2 - coeff * s1 * s2)
            );
            float pinkBoost = (float) Math.sqrt(bandFreq[b] / 1000f);
            if (pinkBoost < 0.3f) pinkBoost = 0.3f;
            float lvl = mag * norm * pinkBoost;
            lvl = (float) Math.tanh(lvl * 2.0f);
            // Raw bin values feed the renderer; smoothing happens
            // per-layer inside renderBars / renderLine so each layer
            // gets its own attack/release setting.
            bandLevel[b] = lvl;
        }
    }

    // ─── Scratch buffers ─────────────────────────────────────────────
    private float[] _sX, _sY, _sY2, _sM;
    private float[] scratchX(int n)  { if (_sX  == null || _sX.length  < n) _sX  = new float[n]; return _sX; }
    private float[] scratchY(int n)  { if (_sY  == null || _sY.length  < n) _sY  = new float[n]; return _sY; }
    private float[] scratchY2(int n) { if (_sY2 == null || _sY2.length < n) _sY2 = new float[n]; return _sY2; }
    private float[] scratchM(int n)  { if (_sM  == null || _sM.length  < n) _sM  = new float[n]; return _sM; }

    // ─── Wave config — hardcoded for now ────────────────────────────
    // Mirrors a typical user wave: 1 bars layer (frequency colours,
    // center align, asym smooth fast attack / slow release) and 1 line
    // layer below with mirror + spline + gradient + fill-below. Edit
    // [buildDefaultWave] to test different configs.

    private enum LayerType { BARS, LINE }

    private static final class Layer {
        LayerType type;
        boolean   visible = true;
        // shared
        int     count       = 48;
        int     width       = 70;   // % of slot (bars)
        int     gap         = 1;    // px between bars
        float   scale       = 1.0f;
        int     minHeight   = 2;
        int     round       = 0;
        String  align       = "center";
        int     smooth      = 30;
        int     smoothAttack  = 0;
        int     smoothRelease = 80;
        // colour
        String  colorMode   = "frequency";
        String  color       = "#ffffff";
        String  color2      = "#5e7eff";
        float   hueShift    = 0f;
        // animation shaping
        float   animContrast = 100f;
        float   animTilt     = 0f;
        float[] respCurve    = null;
        // line-specific
        float   strokeW     = 2f;
        boolean spline      = true;
        boolean mirror      = false;
        boolean elastic     = false;
        boolean fillBelow   = false;
        int     fillOpacity = 30;
        // visual effects
        String  fxStyle     = "fill";
        float   fxStrokeW   = 2f;
        int     fxOpacity   = 100;
        String  fxBlend     = "source-over";
        // per-layer scratch (allocated lazily in the renderer)
        float[] _smoothed;
    }

    private static final class Wave {
        Layer[] layers;
    }

    // Build a preset by index. Add new entries here + bump
    // [PRESET_COUNT] above — the slider range auto-adapts.
    //
    //   0 — Bars only (frequency colours, classic spectrum look)
    //   1 — Bars + mirrored gradient line on screen blend
    //   2 — Bars + amber fill-below area, no mirror
    //   3 — Pure mirrored gradient line (no bars)
    private static Wave buildPreset(int idx) {
        switch (idx) {
            case 1:  return buildBarsPlusMirrorLine();
            case 2:  return buildBarsPlusFillArea();
            case 3:  return buildMirrorLineOnly();
            case 0:
            default: return buildBarsOnly();
        }
    }

    private static Wave buildBarsOnly() {
        Layer bars = new Layer();
        bars.type = LayerType.BARS;
        bars.count = 48;
        bars.width = 70;
        bars.gap = 1;
        bars.scale = 1.0f;
        bars.minHeight = 2;
        bars.align = "center";
        bars.colorMode = "frequency";
        bars.smoothAttack = 0;
        bars.smoothRelease = 80;
        bars.round = 0;
        Wave w = new Wave();
        w.layers = new Layer[]{ bars };
        return w;
    }

    private static Wave buildBarsPlusMirrorLine() {
        Layer bars = new Layer();
        bars.type = LayerType.BARS;
        bars.count = 48;
        bars.width = 70;
        bars.gap = 1;
        bars.scale = 0.9f;
        bars.minHeight = 2;
        bars.align = "center";
        bars.colorMode = "frequency";
        bars.smoothAttack = 0;
        bars.smoothRelease = 80;

        Layer line = new Layer();
        line.type = LayerType.LINE;
        line.count = 64;
        line.scale = 1.0f;
        line.align = "center";
        line.strokeW = 2f;
        line.spline = true;
        line.mirror = true;
        line.colorMode = "gradient";
        line.color = "#ffffff";
        line.color2 = "#fbbf24";
        line.fxOpacity = 80;
        line.fxBlend = "screen";

        Wave w = new Wave();
        w.layers = new Layer[]{ bars, line };
        return w;
    }

    private static Wave buildBarsPlusFillArea() {
        Layer bars = new Layer();
        bars.type = LayerType.BARS;
        bars.count = 48;
        bars.width = 70;
        bars.gap = 1;
        bars.scale = 0.9f;
        bars.minHeight = 2;
        bars.align = "bottom";
        bars.colorMode = "frequency";
        bars.smoothAttack = 0;
        bars.smoothRelease = 75;

        Layer line = new Layer();
        line.type = LayerType.LINE;
        line.count = 64;
        line.scale = 1.0f;
        line.align = "bottom";
        line.strokeW = 1.5f;
        line.spline = true;
        line.mirror = false;
        line.colorMode = "gradient";
        line.color = "#fbbf24";
        line.color2 = "#f97316";
        line.fillBelow = true;
        line.fillOpacity = 35;
        line.fxOpacity = 100;
        line.fxBlend = "source-over";

        Wave w = new Wave();
        w.layers = new Layer[]{ bars, line };
        return w;
    }

    private static Wave buildMirrorLineOnly() {
        Layer line = new Layer();
        line.type = LayerType.LINE;
        line.count = 64;
        line.scale = 1.0f;
        line.align = "center";
        line.strokeW = 2.5f;
        line.spline = true;
        line.mirror = true;
        line.elastic = false;
        line.colorMode = "frequency";
        line.smoothAttack = 0;
        line.smoothRelease = 75;

        Wave w = new Wave();
        w.layers = new Layer[]{ line };
        return w;
    }
}
