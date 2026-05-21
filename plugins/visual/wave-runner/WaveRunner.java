package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.BlendMode;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave Runner — faithful native port of wave-engine.js.
 *
 * The plugin renders whatever Waveform Builder wave the host has
 * active. The host pushes the JSON via {@link #setConfig(String)}
 * (called from PluginPanel whenever WaveConfigStore.activeWaveJson
 * changes), and feeds the same log-spaced FFT bins the WebView
 * visualizer reads via {@code streams["topVisBins"]}. With matching
 * input on both sides + identical render math, Skia output and JS
 * output line up bar-for-bar.
 *
 * The wave schema is the `wb2.v1` shape from waveform-builder.html:
 *   { schema, layers: [{ type: 'bars'|'line', visible, settings: {…} }] }
 *
 * Known limitations vs. the JS engine:
 *   • `fxFade` trail — needs an offscreen bitmap (PluginCanvas doesn't
 *     expose one). Layers with fxFade > 0 render as if fxFade == 0.
 *   • `fxGlow` / `fxBlur` post-effects — PluginPaint.setGlow exists
 *     but per-bar overhead would dominate the test; left for a later
 *     iteration once the rest of the engine is proven.
 *
 * JSON parsing is hand-rolled (see {@link JsonMini} below) because
 * the cross-platform plugin sandbox doesn't let us depend on
 * org.json or Gson.
 */
public final class WaveRunner
        implements VocalMonitorNativePlugin, VocalMonitorVisualPlugin {

    // ─── Audio analysis (fallback Goertzel — only used when the host
    //     doesn't feed us streams["topVisBins"]) ─────────────────────
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

    // ─── Wave config ─────────────────────────────────────────────────
    private volatile Wave activeWave;  // pushed by setConfig

    // ─── Paint cache ─────────────────────────────────────────────────
    private PluginPaint paintFill;
    private PluginPaint paintStroke;
    private PluginPaint paintArea;

    // ─── Lifecycle ───────────────────────────────────────────────────

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
        // No initial wave — host pushes one once it boots. Until
        // then render() no-ops.
        activeWave = null;
    }

    // ─── Parameters ──────────────────────────────────────────────────
    // freeResize  — 0/1 toggle the host reads to switch the floating
    //               panel's resize handle from aspect-locked uniform
    //               scale to independent width/height. Stored as a
    //               plain plugin param so the same persistence path
    //               that handles every other knob writes / restores
    //               it without special-casing.
    private static final String PARAM_FREE_RESIZE = "freeResize";

    @Override public String[] parameterNames() {
        return new String[]{ PARAM_FREE_RESIZE };
    }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n) {
        if (PARAM_FREE_RESIZE.equals(n)) return "Free Resize";
        return n;
    }
    @Override public void setParameter(String n, float v) { }

    /**
     * Host calls this with the wb2.v1 wave JSON whenever the user
     * switches active wave in Settings (or the live edit channel
     * fires from the PC builder). Parse → swap; on any parse
     * failure keep the previous wave so a malformed push doesn't
     * blank the screen mid-stream.
     */
    @Override public void setConfig(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            Object root = JsonMini.parse(json);
            Wave w = buildWave(root);
            if (w != null) activeWave = w;
        } catch (Throwable t) {
            // Keep last good config.
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

    // ─── Render ──────────────────────────────────────────────────────

    @Override public void render(
            PluginCanvas canvas, int width, int height, long timeMs,
            Map<String, Float> params, Map<String, float[]> streams
    ) {
        // Skip all work until the host has pushed a config.
        Wave wv = activeWave;
        if (wv == null || wv.layers == null || wv.layers.length == 0) return;

        if (paintFill == null) {
            paintFill   = canvas.newPaint().setStyle(PluginStyle.FILL).setAntialias(true);
            paintStroke = canvas.newPaint().setStyle(PluginStyle.STROKE).setAntialias(true);
            paintArea   = canvas.newPaint().setStyle(PluginStyle.FILL).setAntialias(true);
        }

        // Bins. Preferred path: the host hands us the same log-spaced
        // FFT bins the WebView TopVis reads, so output matches the JS
        // engine bit for bit. Fallback: when that stream isn't wired
        // (older host) or is empty (engine idle), run our own Goertzel
        // on the raw waveform so the visual still animates.
        float[] bins = streams != null ? streams.get("topVisBins") : null;
        if (bins == null || bins.length == 0) {
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
            bins = bandLevel;
        }

        // Energy for 'energy' colour mode + pulse detection.
        float energy = 0f;
        for (int i = 0; i < bins.length; i++) energy += bins[i];
        energy /= Math.max(1, bins.length);

        final float W = width, H = height;
        final float tSec = timeMs / 1000f;
        for (int li = 0; li < wv.layers.length; li++) {
            Layer L = wv.layers[li];
            if (L == null || !L.visible) continue;
            BlendMode bm = mapBlend(L.fxBlend);
            paintFill.setBlendMode(bm);
            paintStroke.setBlendMode(bm);
            paintArea.setBlendMode(bm);
            if (L.type == LayerType.BARS) {
                renderBars(canvas, W, H, L, bins, energy, tSec);
            } else if (L.type == LayerType.LINE) {
                renderLine(canvas, W, H, L, bins, energy, tSec);
            }
        }
        paintFill.setBlendMode(BlendMode.SRC_OVER);
        paintStroke.setBlendMode(BlendMode.SRC_OVER);
        paintArea.setBlendMode(BlendMode.SRC_OVER);
    }

    // ─── Bars renderer ───────────────────────────────────────────────

    private void renderBars(
            PluginCanvas canvas, float W, float H,
            Layer L, float[] bins, float energy, float tSec
    ) {
        int n = Math.max(2, L.count);
        if (L._smoothed == null || L._smoothed.length != n) L._smoothed = new float[n];
        float[] sm = L._smoothed;

        float slot = W / n;
        float widW = slot * (Math.max(1, Math.min(100, L.width))) / 100f;
        float gap  = Math.max(0, L.gap);
        float barW = Math.max(1f, widW - gap);
        float scale = L.scale;
        float minH  = Math.max(0, L.minHeight);
        float round = Math.max(0, L.round);

        float attack  = clamp(L.smoothAttack  >= 0 ? L.smoothAttack  : L.smooth, 0, 99) / 100f;
        float release = clamp(L.smoothRelease >= 0 ? L.smoothRelease : L.smooth, 0, 99) / 100f;

        int opacityPct = (L.fxOpacity < 0) ? 100 : L.fxOpacity;
        float alphaMul = opacityPct / 100f;

        int bL = bins.length;
        for (int i = 0; i < n; i++) {
            int idx = (int) Math.floor((i / (float) n) * bL);
            if (idx < 0) idx = 0; else if (idx >= bL) idx = bL - 1;
            float raw = bins[idx];
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
            Layer L, float[] bins, float energy, float tSec
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

        int bL = bins.length;
        for (int i = 0; i < n; i++) {
            int idx = (int) Math.floor((i / (float) n) * bL);
            if (idx < 0) idx = 0; else if (idx >= bL) idx = bL - 1;
            float raw = bins[idx];
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
            for (int i = 0; i < n - 1; i++) {
                float fi = i / (float) Math.max(1, n - 1);
                float mAvg = (mags[i] + mags[i + 1]) * 0.5f;
                int c = applyAlpha(barColor(L, fi, mAvg, energy), alphaMul);
                paintStroke.setColor(c).clearShader().setStrokeWidth(strokeW);
                canvas.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], paintStroke);
            }
        }

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
        // Blend modes are temporarily forced to SRC_OVER. The JS engine
        // implements them via a per-layer OFFSCREEN canvas + composite,
        // so SCREEN(white_pixel, anything) stays bounded by the line's
        // alpha — only the line's actual pixels brighten. The plugin
        // draws strokes directly, so Skia's SCREEN paint applies to
        // every anti-aliased stroke pixel and washes adjacent bars to
        // white. Until PluginCanvas gains a saveLayer primitive
        // (planned), forcing SRC_OVER produces correct-coloured output
        // even though it loses the "screen brightens" effect.
        return BlendMode.SRC_OVER;
    }

    // ─── Spectrum analysis (Goertzel fallback) ───────────────────────

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
            bandLevel[b] = lvl;
        }
    }

    // ─── Scratch buffers ─────────────────────────────────────────────
    private float[] _sX, _sY, _sY2, _sM;
    private float[] scratchX(int n)  { if (_sX  == null || _sX.length  < n) _sX  = new float[n]; return _sX; }
    private float[] scratchY(int n)  { if (_sY  == null || _sY.length  < n) _sY  = new float[n]; return _sY; }
    private float[] scratchY2(int n) { if (_sY2 == null || _sY2.length < n) _sY2 = new float[n]; return _sY2; }
    private float[] scratchM(int n)  { if (_sM  == null || _sM.length  < n) _sM  = new float[n]; return _sM; }

    // ─── Wave model + builder from parsed JSON ──────────────────────

    private enum LayerType { BARS, LINE }

    private static final class Layer {
        LayerType type;
        boolean   visible = true;
        // shared
        int     count       = 48;
        int     width       = 70;
        int     gap         = 1;
        float   scale       = 1.0f;
        int     minHeight   = 2;
        int     round       = 0;
        String  align       = "center";
        int     smooth      = 30;
        int     smoothAttack  = -1;
        int     smoothRelease = -1;
        String  colorMode   = "frequency";
        String  color       = "#ffffff";
        String  color2      = "#5e7eff";
        float   hueShift    = 0f;
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
        // fx
        String  fxStyle     = "fill";
        float   fxStrokeW   = 2f;
        int     fxOpacity   = 100;
        String  fxBlend     = "source-over";
        // scratch
        float[] _smoothed;
    }

    private static final class Wave {
        Layer[] layers;
    }

    @SuppressWarnings("unchecked")
    private static Wave buildWave(Object root) {
        if (!(root instanceof Map)) return null;
        Map<String, Object> obj = (Map<String, Object>) root;
        Object layersObj = obj.get("layers");
        if (!(layersObj instanceof List)) return null;
        List<Object> arr = (List<Object>) layersObj;
        Layer[] out = new Layer[arr.size()];
        int kept = 0;
        for (Object o : arr) {
            if (!(o instanceof Map)) continue;
            Layer l = buildLayer((Map<String, Object>) o);
            if (l == null) continue;
            out[kept++] = l;
        }
        Layer[] trimmed = new Layer[kept];
        System.arraycopy(out, 0, trimmed, 0, kept);
        Wave w = new Wave();
        w.layers = trimmed;
        return w;
    }

    @SuppressWarnings("unchecked")
    private static Layer buildLayer(Map<String, Object> lj) {
        String type = asString(lj.get("type"), "");
        LayerType lt;
        if (type.equals("bars"))      lt = LayerType.BARS;
        else if (type.equals("line")) lt = LayerType.LINE;
        else return null;
        Layer l = new Layer();
        l.type = lt;
        l.visible = asBool(lj.get("visible"), true);
        Object sObj = lj.get("settings");
        Map<String, Object> s = (sObj instanceof Map) ? (Map<String, Object>) sObj : new HashMap<>();

        l.count        = asInt(s.get("count"),        lt == LayerType.LINE ? 64 : 48);
        l.width        = asInt(s.get("width"),        70);
        l.gap          = asInt(s.get("gap"),          1);
        l.scale        = asFloat(s.get("scale"),      1.0f);
        l.minHeight    = asInt(s.get("minHeight"),    lt == LayerType.LINE ? 0 : 2);
        l.round        = asInt(s.get("round"),        0);
        l.align        = asString(s.get("align"),     "center");
        l.smooth       = asInt(s.get("smooth"),       30);
        l.smoothAttack  = asInt(s.get("smoothAttack"),  -1);
        l.smoothRelease = asInt(s.get("smoothRelease"), -1);
        l.colorMode    = asString(s.get("colorMode"),  "solid");
        l.color        = asString(s.get("color"),      "#ffffff");
        l.color2       = asString(s.get("color2"),     "#5e7eff");
        l.hueShift     = asFloat(s.get("hueShift"),    0f);
        l.animContrast = asFloat(s.get("animContrast"), 100f);
        l.animTilt     = asFloat(s.get("animTilt"),     0f);
        Object ca = s.get("respCurve");
        if (ca instanceof List) {
            List<Object> arr = (List<Object>) ca;
            if (!arr.isEmpty()) {
                float[] curve = new float[arr.size()];
                for (int i = 0; i < curve.length; i++) curve[i] = asFloat(arr.get(i), 1f);
                l.respCurve = curve;
            }
        }
        l.strokeW     = asFloat(s.get("strokeW"),  2f);
        l.spline      = asBool(s.get("spline"),    true);
        l.mirror      = asBool(s.get("mirror"),    false);
        l.elastic     = asBool(s.get("elastic"),   false);
        l.fillBelow   = asBool(s.get("fillBelow"), false);
        l.fillOpacity = asInt(s.get("fillOpacity"), 30);
        l.fxStyle     = asString(s.get("fxStyle"),  "fill");
        l.fxStrokeW   = asFloat(s.get("fxStrokeW"), 2f);
        l.fxOpacity   = asInt(s.get("fxOpacity"),   100);
        l.fxBlend     = asString(s.get("fxBlend"),  "source-over");
        return l;
    }

    private static String asString(Object v, String def) {
        return v == null ? def : v.toString();
    }
    private static int asInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return def; }
    }
    private static float asFloat(Object v, float def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).floatValue();
        try { return Float.parseFloat(v.toString()); }
        catch (NumberFormatException e) { return def; }
    }
    private static boolean asBool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        String s = v.toString();
        return s.equals("true") || s.equals("1");
    }

    // ─── Minimal JSON parser ─────────────────────────────────────────
    // Recursive-descent over a char array. Returns nested Map / List /
    // String / Double / Boolean / null — exactly what {@link buildWave}
    // expects. No external deps; small enough that the whole parser
    // adds ~3 KB to the dex. Handles the wb2.v1 wave schema; common
    // escapes (backslash-quote, backslash-backslash, backslash-n,
    // backslash-t, backslash-u-XXXX) are supported, exotic ones
    // are best-effort.

    private static final class JsonMini {
        private final char[] src;
        private int pos;

        private JsonMini(String s) { this.src = s.toCharArray(); this.pos = 0; }

        static Object parse(String s) {
            JsonMini p = new JsonMini(s);
            p.skipWs();
            Object v = p.readValue();
            p.skipWs();
            return v;
        }

        private void skipWs() {
            while (pos < src.length) {
                char c = src[pos];
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        private Object readValue() {
            skipWs();
            if (pos >= src.length) throw new RuntimeException("unexpected eof");
            char c = src[pos];
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBool();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> m = new HashMap<>();
            pos++; // {
            skipWs();
            if (pos < src.length && src[pos] == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (pos >= src.length || src[pos] != '"') throw new RuntimeException("expected key string");
                String key = readString();
                skipWs();
                if (pos >= src.length || src[pos] != ':') throw new RuntimeException("expected ':'");
                pos++;
                Object v = readValue();
                m.put(key, v);
                skipWs();
                if (pos >= src.length) throw new RuntimeException("unexpected eof in object");
                char c = src[pos];
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new RuntimeException("expected , or } in object");
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < src.length && src[pos] == ']') { pos++; return list; }
            while (true) {
                Object v = readValue();
                list.add(v);
                skipWs();
                if (pos >= src.length) throw new RuntimeException("unexpected eof in array");
                char c = src[pos];
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw new RuntimeException("expected , or ] in array");
            }
        }

        private String readString() {
            pos++; // opening "
            StringBuilder sb = new StringBuilder();
            while (pos < src.length) {
                char c = src[pos++];
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length) break;
                    char e = src[pos++];
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length) throw new RuntimeException("bad \\u escape");
                            int cp = Integer.parseInt(new String(src, pos, 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new RuntimeException("unterminated string");
        }

        private Boolean readBool() {
            if (src[pos] == 't') {
                if (pos + 4 <= src.length && src[pos + 1] == 'r' && src[pos + 2] == 'u' && src[pos + 3] == 'e') {
                    pos += 4;
                    return Boolean.TRUE;
                }
            } else if (src[pos] == 'f') {
                if (pos + 5 <= src.length && src[pos + 1] == 'a' && src[pos + 2] == 'l' && src[pos + 3] == 's' && src[pos + 4] == 'e') {
                    pos += 5;
                    return Boolean.FALSE;
                }
            }
            throw new RuntimeException("expected true/false");
        }

        private Object readNull() {
            if (pos + 4 <= src.length && src[pos + 1] == 'u' && src[pos + 2] == 'l' && src[pos + 3] == 'l') {
                pos += 4;
                return null;
            }
            throw new RuntimeException("expected null");
        }

        private Double readNumber() {
            int start = pos;
            if (pos < src.length && src[pos] == '-') pos++;
            while (pos < src.length) {
                char c = src[pos];
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else break;
            }
            String s = new String(src, start, pos - start);
            return Double.parseDouble(s);
        }
    }
}
