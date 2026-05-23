package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginBitmap;
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
 * Effect coverage matches the JS engine:
 *   • fxGlow / fxBlur — applied per draw via PluginPaint.setGlow
 *     and PluginPaint.setBlur (BlurMaskFilter on the Android side).
 *   • fxBlend — layer routes through canvas.saveLayer() so blend
 *     modes (SCREEN, MULTIPLY, OVERLAY, …) isolate to the layer's
 *     pixels instead of bleeding into adjacent layers' anti-aliased
 *     fringes.
 *   • fxFade trail — layer routes through a persistent
 *     PluginBitmap acquired via canvas.acquireBitmap(); previous
 *     frame's pixels survive the partial-alpha wipe so motion
 *     leaves a fading trail.
 *   • flow-l / flow-r / flow-c / pulse — animation modes mirror the
 *     JS engine's renderBarsFlow / renderLineFlow with the same
 *     per-mode propagation + per-source injection logic.
 *
 * On hosts older than the bitmap / saveLayer / blur extensions the
 * plugin still loads — the SDK ships default no-op impls and the
 * plugin's bitmap acquisition checks for null + falls back to
 * direct rendering. Visual output degrades gracefully.
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
    // Layer paint — passed to canvas.saveLayer() so the matching
    // restore composites with the layer's fxBlend / fxOpacity. Cached
    // across frames; updated per-layer.
    private PluginPaint paintLayer;

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
            paintLayer  = canvas.newPaint().setStyle(PluginStyle.FILL).setAntialias(true);
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
            // Decide composition path. Mirrors `layerNeedsOffscreen`
            // in wave-engine.js. fxFade > 0 needs a PERSISTENT
            // offscreen bitmap so previous frame's pixels can bleed
            // through a partial alpha wipe; the saveLayer path can't
            // do that (its buffer is allocated fresh each frame).
            // fxBlend / fxOpacity alone can ride the cheaper
            // saveLayer route.
            boolean fade = L.fxFade > 0;
            boolean blendOrOpacity = !"source-over".equals(L.fxBlend) ||
                                     (L.fxOpacity >= 0 && L.fxOpacity < 100);
            int alpha = clampInt(L.fxOpacity < 0 ? 100 : L.fxOpacity, 0, 100);
            int alphaByte = (alpha * 255) / 100;
            boolean flow = !"freq-grow".equals(L.animMode);

            PluginBitmap bmp = fade
                ? canvas.acquireBitmap("layer_" + li, W, H)
                : null;
            if (bmp != null) {
                // Phase 3 path — persistent bitmap, fade wipe, then
                // composite to parent with blend + opacity.
                float wipeAlpha = (1f - L.fxFade / 100f) * 0.95f + 0.05f;
                bmp.fadeWipe(wipeAlpha);
                L._useLayer = true;
                PluginCanvas bc = bmp.canvas();
                if (L.type == LayerType.BARS) {
                    if (flow) renderBarsFlow(bc, W, H, L, bins, energy, tSec);
                    else      renderBars    (bc, W, H, L, bins, energy, tSec);
                } else if (L.type == LayerType.LINE) {
                    if (flow) renderLineFlow(bc, W, H, L, bins, energy, tSec);
                    else      renderLine    (bc, W, H, L, bins, energy, tSec);
                } else if (L.type == LayerType.PARTICLES) {
                    renderParticles(bc, W, H, L, bins, energy, tSec, timeMs);
                }
                paintLayer.clearShader()
                          .setColor((alphaByte << 24) | 0x00FFFFFF)
                          .setBlendMode(mapBlend(L.fxBlend));
                canvas.drawBitmap(bmp, paintLayer);
                continue;
            }

            // Phase 2 path — saveLayer for blend / opacity (no fade).
            // Also the graceful fallback when fxFade is set but the
            // host returned null (older host without bitmap API).
            L._useLayer = blendOrOpacity;
            if (blendOrOpacity) {
                paintLayer.clearShader()
                          .setColor((alphaByte << 24) | 0x00FFFFFF)
                          .setBlendMode(mapBlend(L.fxBlend));
                canvas.saveLayer(0f, 0f, W, H, paintLayer);
            }
            if (L.type == LayerType.BARS) {
                if (flow) renderBarsFlow(canvas, W, H, L, bins, energy, tSec);
                else      renderBars    (canvas, W, H, L, bins, energy, tSec);
            } else if (L.type == LayerType.LINE) {
                if (flow) renderLineFlow(canvas, W, H, L, bins, energy, tSec);
                else      renderLine    (canvas, W, H, L, bins, energy, tSec);
            } else if (L.type == LayerType.PARTICLES) {
                renderParticles(canvas, W, H, L, bins, energy, tSec, timeMs);
            }
            if (blendOrOpacity) canvas.restore();
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
        // When rendering into a saveLayer offscreen buffer, the layer
        // paint already carries fxOpacity at composite time — inner
        // paints must draw at full alpha so we don't double-attenuate.
        float alphaMul = L._useLayer ? 1f : (opacityPct / 100f);

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

    /**
     * Flow animation modes — propagate bar values sideways then inject
     * fresh energy at one edge / the centre. Mirrors
     * `renderBarsFlow` in wave-engine.js so JS and native paths match
     * pixel-for-pixel when the user picks flow-l / flow-r / flow-c /
     * pulse from the builder.
     */
    private void renderBarsFlow(
            PluginCanvas canvas, float W, float H,
            Layer L, float[] bins, float energy, float tSec
    ) {
        int n = Math.max(2, L.count);
        if (L._flow == null || L._flow.length != n) L._flow = new float[n];
        float[] vals = L._flow;
        // Snapshot before in-place propagation — both flow-l and flow-r
        // read neighbours that the same loop is about to overwrite.
        float[] old = scratchY(n);
        System.arraycopy(vals, 0, old, 0, n);

        float slot  = W / n;
        float widW  = slot * (Math.max(1, Math.min(100, L.width))) / 100f;
        float gap   = Math.max(0, L.gap);
        float barW  = Math.max(1f, widW - gap);
        float scale = L.scale;
        float minH  = Math.max(0, L.minHeight);
        float round = Math.max(0, L.round);
        float smPct = clamp(L.smooth, 0, 99) / 100f;
        float alpha = Math.max(0.02f, 1f - smPct);
        float sens  = L.animSensitivity / 100f;
        float thresh = L.animThreshold  / 100f;
        float decay = 1f - (L.animDecay / 100f);
        int opacityPct = (L.fxOpacity < 0) ? 100 : L.fxOpacity;
        // When rendering into a saveLayer offscreen buffer, the layer
        // paint already carries fxOpacity at composite time — inner
        // paints must draw at full alpha so we don't double-attenuate.
        float alphaMul = L._useLayer ? 1f : (opacityPct / 100f);
        String mode = L.animMode;

        if ("flow-l".equals(mode)) {
            for (int i = 0; i < n - 1; i++) vals[i] = old[i] + (old[i + 1] - old[i]) * alpha;
        } else if ("flow-r".equals(mode)) {
            for (int i = n - 1; i > 0; i--) vals[i] = old[i] + (old[i - 1] - old[i]) * alpha;
        } else if ("flow-c".equals(mode) || "pulse".equals(mode)) {
            int c = n / 2;
            for (int i = 0; i < c; i++)     vals[i] = old[i] + (old[i + 1] - old[i]) * alpha;
            for (int i = n - 1; i > c; i--) vals[i] = old[i] + (old[i - 1] - old[i]) * alpha;
        }

        float rawSource = sourceValue(bins, L.animSource);
        float injected = clamp(rawSource * sens, 0f, 1f);
        if ("flow-l".equals(mode)) {
            vals[n - 1] = vals[n - 1] * decay + injected * (1f - decay);
        } else if ("flow-r".equals(mode)) {
            vals[0]     = vals[0]     * decay + injected * (1f - decay);
        } else if ("flow-c".equals(mode)) {
            int c = n / 2;
            vals[c]     = vals[c]     * decay + injected * (1f - decay);
        } else if ("pulse".equals(mode)) {
            int c = n / 2;
            long now = System.nanoTime() / 1_000_000L;
            float delta = energy - L._lastEnergy;
            L._lastEnergy = energy;
            if (delta > thresh && now - L._lastPulseT > 90L) {
                vals[c] = injected;
                L._lastPulseT = now;
            } else {
                vals[c] = vals[c] * decay;
            }
        }

        int bL = bins.length;
        for (int i = 0; i < n; i++) {
            float fi = i / (float) Math.max(1, n - 1);
            float cv = curveAt(L.respCurve, fi);
            float m  = shapeMagnitude(vals[i], fi, L.animContrast, L.animTilt) * cv;
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
        // fxGlow → soft outer glow under whichever paint we end up
        // using. Radius matches the JS engine (shadowBlur = fxGlow/100
        // * 24px). Cleared after the draw so the next layer doesn't
        // inherit it.
        float glowR = (L.fxGlow > 0) ? (L.fxGlow * 24f / 100f) : 0f;
        // fxBlur → BlurMaskFilter applied per draw (matches JS
        // ctx.filter = 'blur(Xpx)' which the engine wraps in
        // ctx.save/restore around each bar). JS max blur radius is 4px
        // at fxBlur=100, hence the /100*4 scale.
        float blurR = (L.fxBlur > 0) ? (L.fxBlur * 4f / 100f) : 0f;
        if (style.equals("outline")) {
            float sw = L.fxStrokeW > 0 ? L.fxStrokeW : 2f;
            paintStroke.setColor(color).setStrokeWidth(sw).clearShader();
            if (glowR > 0f) paintStroke.setGlow(color, glowR);
            if (blurR > 0f) paintStroke.setBlur(blurR);
            if (round > 0f) {
                canvas.drawRoundRect(x, y, x + bw, y + bh, Math.min(round, Math.min(bw, bh) / 2f), paintStroke);
            } else {
                canvas.drawRect(x, y, x + bw, y + bh, paintStroke);
            }
            if (glowR > 0f) paintStroke.setGlow(color, 0f);
            if (blurR > 0f) paintStroke.setBlur(0f);
        } else if (style.equals("glow-only")) {
            float gr = Math.max(10f, L.fxGlow * 24f / 100f) + 10f;
            int faded = applyAlpha(color, 0.25f);
            paintFill.setColor(faded).clearShader().setGlow(color, gr);
            if (blurR > 0f) paintFill.setBlur(blurR);
            if (round > 0f) {
                canvas.drawRoundRect(x, y, x + bw, y + bh, Math.min(round, Math.min(bw, bh) / 2f), paintFill);
            } else {
                canvas.drawRect(x, y, x + bw, y + bh, paintFill);
            }
            paintFill.setGlow(color, 0f);
            if (blurR > 0f) paintFill.setBlur(0f);
        } else {
            paintFill.setColor(color).clearShader();
            if (glowR > 0f) paintFill.setGlow(color, glowR);
            if (blurR > 0f) paintFill.setBlur(blurR);
            if (round > 0f) {
                canvas.drawRoundRect(x, y, x + bw, y + bh, Math.min(round, Math.min(bw, bh) / 2f), paintFill);
            } else {
                canvas.drawRect(x, y, x + bw, y + bh, paintFill);
            }
            if (glowR > 0f) paintFill.setGlow(color, 0f);
            if (blurR > 0f) paintFill.setBlur(0f);
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
        // When rendering into a saveLayer offscreen buffer, the layer
        // paint already carries fxOpacity at composite time — inner
        // paints must draw at full alpha so we don't double-attenuate.
        float alphaMul = L._useLayer ? 1f : (opacityPct / 100f);

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
        paintLinePath(canvas, W, H, L, xs, ys, mags, n, baseline,
                      strokeW, alphaMul, energy);
    }

    /**
     * Mirror of `renderLineFlow` in wave-engine.js. Same flow / pulse
     * propagation as bars; the only diff is how vals[] turns into the
     * geometry (polyline + optional spline, optional mirror, optional
     * fillBelow). Geometry path is copied straight from renderLine.
     */
    private void renderLineFlow(
            PluginCanvas canvas, float W, float H,
            Layer L, float[] bins, float energy, float tSec
    ) {
        int n = Math.max(2, L.count);
        if (L._flow == null || L._flow.length != n) L._flow = new float[n];
        float[] vals = L._flow;
        float[] old = scratchY2(n);
        System.arraycopy(vals, 0, old, 0, n);

        float scale = L.scale;
        float minH = Math.max(0, L.minHeight);
        float strokeW = Math.max(0.5f, L.strokeW);
        float smPct = clamp(L.smooth, 0, 99) / 100f;
        float alpha = Math.max(0.02f, 1f - smPct);
        float sens   = L.animSensitivity / 100f;
        float thresh = L.animThreshold   / 100f;
        float decay  = 1f - (L.animDecay / 100f);
        int opacityPct = (L.fxOpacity < 0) ? 100 : L.fxOpacity;
        // When rendering into a saveLayer offscreen buffer, the layer
        // paint already carries fxOpacity at composite time — inner
        // paints must draw at full alpha so we don't double-attenuate.
        float alphaMul = L._useLayer ? 1f : (opacityPct / 100f);
        String mode = L.animMode;

        if ("flow-l".equals(mode)) {
            for (int i = 0; i < n - 1; i++) vals[i] = old[i] + (old[i + 1] - old[i]) * alpha;
        } else if ("flow-r".equals(mode)) {
            for (int i = n - 1; i > 0; i--) vals[i] = old[i] + (old[i - 1] - old[i]) * alpha;
        } else if ("flow-c".equals(mode) || "pulse".equals(mode)) {
            int c = n / 2;
            for (int i = 0; i < c; i++)     vals[i] = old[i] + (old[i + 1] - old[i]) * alpha;
            for (int i = n - 1; i > c; i--) vals[i] = old[i] + (old[i - 1] - old[i]) * alpha;
        }

        float rawSource = sourceValue(bins, L.animSource);
        float injected = clamp(rawSource * sens, 0f, 1f);
        if ("flow-l".equals(mode)) {
            vals[n - 1] = vals[n - 1] * decay + injected * (1f - decay);
        } else if ("flow-r".equals(mode)) {
            vals[0]     = vals[0]     * decay + injected * (1f - decay);
        } else if ("flow-c".equals(mode)) {
            int c = n / 2;
            vals[c]     = vals[c]     * decay + injected * (1f - decay);
        } else if ("pulse".equals(mode)) {
            int c = n / 2;
            long now = System.nanoTime() / 1_000_000L;
            float delta = energy - L._lastEnergy;
            L._lastEnergy = energy;
            if (delta > thresh && now - L._lastPulseT > 90L) {
                vals[c] = injected;
                L._lastPulseT = now;
            } else {
                vals[c] = vals[c] * decay;
            }
        }

        float baseline =
            L.align.equals("top")    ? strokeW * 0.5f :
            L.align.equals("center") ? H * 0.5f :
                                       H - strokeW * 0.5f;
        float dir = L.align.equals("top") ? +1f : -1f;
        float ampMul = L.align.equals("center") ? 0.5f : 1f;
        float maxAmp = H - strokeW;
        boolean elastic = L.elastic;
        float elasticT = elastic ? tSec * 2f : 0f;

        float[] xs   = scratchX(n);
        float[] ys   = scratchY(n);
        float[] mags = scratchM(n);
        for (int i = 0; i < n; i++) {
            float fi = i / (float) Math.max(1, n - 1);
            float cv = curveAt(L.respCurve, fi);
            float m = shapeMagnitude(vals[i], fi, L.animContrast, L.animTilt) * cv;
            mags[i] = m;
            float amp = Math.min(maxAmp, Math.max(minH, m * H * scale * ampMul));
            float sign = elastic ? (float) Math.sin(i * 0.55f + elasticT) : 1f;
            xs[i] = (i / (float) Math.max(1, n - 1)) * W;
            ys[i] = baseline + dir * sign * amp;
        }
        paintLinePath(canvas, W, H, L, xs, ys, mags, n, baseline,
                      strokeW, alphaMul, energy);
    }

    // ─── Particles renderer ──────────────────────────────────────────
    // Faithful port of renderParticlesSkia in waveform-builder.html.
    // SoA particle pool on Layer (lazy-grown when L.count changes),
    // Euler-step physics (gravity + wind + sine turbulence + drag),
    // emission anchored by spawnSource with audio-modulated rate, and
    // per-particle alpha/size lerp over life. Skia drawCircle + per-
    // paint MaskFilter glow gives the same plasma / fire / spark look
    // the designer previews.
    private void renderParticles(
            PluginCanvas canvas, float W, float H,
            Layer L, float[] bins, float energy, float tSec, long timeMs
    ) {
        int cap = Math.max(8, Math.min(1024, L.count));
        ensureParticlePool(L, cap);

        // dt — clamp so a dropped frame doesn't fire a one-step
        // physics catastrophe.
        if (L._lastT == 0L) L._lastT = timeMs;
        float dt = Math.max(0f, Math.min(0.05f, (timeMs - L._lastT) / 1000f));
        L._lastT = timeMs;

        // Audio coupling. Same source/sensitivity/threshold knobs as
        // bars/line flow modes, for cross-type consistency.
        float sens   = L.animSensitivity / 100f;
        float thresh = L.animThreshold   / 100f;
        float rawSource = sourceValue(bins, L.animSource);
        float audioMul = Math.max(0f, Math.min(3f, rawSource * sens));
        String mode = L.animMode == null ? "continuous" : L.animMode;

        // Spawn budget — bank fractional spawns across frames.
        float wantSpawn = L.spawnRate * dt * (1f + audioMul);
        if ("pulse".equals(mode)) {
            float delta = energy - L._lastEnergy;
            L._lastEnergy = energy;
            // Match wave-engine.js fallback: when spawnRate is 0 the
            // user means "no continuous emission" but still wants
            // pulse bursts to fire (e.g. Sparks template — burst-only
            // by design). JS does `(s.spawnRate || 40) * 0.4`; the
            // Java port previously skipped the falsy-coercion and
            // produced ZERO bursts on Sparks. Falling back to 40 for
            // the burst-count calc restores parity.
            int srForBurst = (L.spawnRate <= 0) ? 40 : L.spawnRate;
            wantSpawn = (delta < thresh) ? 0f : Math.min(20f, srForBurst * 0.4f);
        }
        L._spawnBank += wantSpawn;
        while (L._spawnBank >= 1f) {
            spawnParticle(L, W, H, audioMul);
            L._spawnBank -= 1f;
        }

        // Physics + lifecycle step.
        float gravity = L.gravity;
        float wind = L.wind;
        float dragV = L.drag / 100f;
        float turbAmp = L.turbulence;
        String boundary = L.boundary == null ? "kill" : L.boundary;
        float restitution = L.bounceRestitution / 100f;
        float dragMul = Math.max(0f, 1f - dragV * dt);
        for (int i = 0; i < cap; i++) {
            if (!L._palive[i]) continue;
            float ax = wind;
            float ay = gravity;
            if (turbAmp > 0f) {
                ax += turb(L._px[i], L._py[i],         tSec) * turbAmp;
                ay += turb(L._py[i], L._px[i] + 100f,  tSec) * turbAmp;
            }
            L._vx[i] = (L._vx[i] + ax * dt) * dragMul;
            L._vy[i] = (L._vy[i] + ay * dt) * dragMul;
            L._px[i] += L._vx[i] * dt;
            L._py[i] += L._vy[i] * dt;
            if ("bounce".equals(boundary)) {
                if (L._py[i] > H) { L._py[i] = H; L._vy[i] = -L._vy[i] * restitution; }
                if (L._py[i] < 0) { L._py[i] = 0; L._vy[i] = -L._vy[i] * restitution; }
                if (L._px[i] > W) { L._px[i] = W; L._vx[i] = -L._vx[i] * restitution; }
                if (L._px[i] < 0) { L._px[i] = 0; L._vx[i] = -L._vx[i] * restitution; }
            } else if ("wrap".equals(boundary)) {
                if (L._px[i] < 0) L._px[i] += W;
                else if (L._px[i] > W) L._px[i] -= W;
                if (L._py[i] < 0) L._py[i] += H;
                else if (L._py[i] > H) L._py[i] -= H;
            } else {
                if (L._px[i] < -8f || L._px[i] > W + 8f ||
                    L._py[i] < -8f || L._py[i] > H + 8f) {
                    L._palive[i] = false;
                    continue;
                }
            }
            L._page[i] += dt;
            if (L._page[i] >= L._plife[i]) { L._palive[i] = false; continue; }
        }

        // Render alive particles. Match the bars/line discipline:
        // when the layer composites via saveLayer / bitmap, draw
        // fully opaque inside and let the layer paint carry alpha.
        int opacityPct = (L.fxOpacity < 0) ? 100 : L.fxOpacity;
        float alphaMul = L._useLayer ? 1f : (opacityPct / 100f);
        // Glow / blur magnitude tuned to match the designer's Skia
        // renderer (BlurStyle.Solid sigma=5 for particles, sigma=2.5
        // for layer blur). Android's setShadowLayer takes a radius
        // that's sigma * sqrt(3) ≈ 1.73 → 9 dp ≈ 5 sigma.
        // Bars/line keep the older 24f scale because their visuals
        // are bar-dominated and they were authored under the old
        // Canvas2D shadowBlur scale — different artistic intent.
        float glowR = (L.fxGlow > 0) ? (L.fxGlow * 9f / 100f) : 0f;
        float blurR = (L.fxBlur > 0) ? (L.fxBlur * 3f / 100f) : 0f;

        float alphaStart = L.alphaStart / 100f;
        float alphaEnd   = L.alphaEnd   / 100f;
        float sizeEndMul = L.sizeEnd;

        for (int i = 0; i < cap; i++) {
            if (!L._palive[i]) continue;
            float t = L._page[i] / Math.max(0.001f, L._plife[i]);
            float a = alphaStart + (alphaEnd - alphaStart) * t;
            if (a <= 0f) continue;
            float sizeMul = 1f + (sizeEndMul - 1f) * t;
            float r = Math.max(0.5f, (L._psize[i] * sizeMul) * 0.5f);
            int baseCol = barColor(L, L._phue[i], audioMul, energy);
            int color = applyAlpha(baseCol, a * alphaMul);
            paintFill.setColor(color).clearShader();
            if (glowR > 0f) paintFill.setGlow(color, glowR);
            if (blurR > 0f) paintFill.setBlur(blurR);
            canvas.drawCircle(L._px[i], L._py[i], r, paintFill);
            if (glowR > 0f) paintFill.setGlow(color, 0f);
            if (blurR > 0f) paintFill.setBlur(0f);
        }
    }

    private static void ensureParticlePool(Layer L, int cap) {
        if (L._palive != null && L._palive.length == cap) return;
        L._px     = new float[cap];
        L._py    = new float[cap];
        L._vx     = new float[cap];
        L._vy     = new float[cap];
        L._page   = new float[cap];
        L._plife  = new float[cap];
        L._psize  = new float[cap];
        L._phue   = new float[cap];
        L._palive = new boolean[cap];
        L._spawnCursor = 0;
    }

    private static int nextFreeSlot(Layer L) {
        boolean[] alive = L._palive;
        int cap = alive.length;
        int cursor = L._spawnCursor < 0 ? 0 : (L._spawnCursor % cap);
        for (int k = 0; k < cap; k++) {
            int i = (cursor + k) % cap;
            if (!alive[i]) {
                L._spawnCursor = (i + 1) % cap;
                return i;
            }
        }
        return -1;
    }

    private static int spawnParticle(Layer L, float W, float H, float audioMul) {
        int i = nextFreeSlot(L);
        if (i < 0) return -1;
        float spawnXFrac = L.spawnX / 100f;
        float spawnYFrac = L.spawnY / 100f;
        float px = spawnXFrac * W;
        float py = spawnYFrac * H;
        String src = L.spawnSource == null ? "point" : L.spawnSource;
        if ("bottom-line".equals(src)) {
            px = (float)(Math.random() * W); py = H - 1f;
        } else if ("top-line".equals(src)) {
            px = (float)(Math.random() * W); py = 1f;
        } else if ("random".equals(src)) {
            px = (float)(Math.random() * W); py = (float)(Math.random() * H);
        } else if ("rect".equals(src)) {
            // Rectangle centred at (spawnX, spawnY) % with size
            // (spawnW, spawnH) % of canvas. spawnW=spawnH=0 collapses
            // to a single point — same as 'point' source.
            float halfW = (L.spawnW / 100f) * W * 0.5f;
            float halfH = (L.spawnH / 100f) * H * 0.5f;
            float cx = spawnXFrac * W;
            float cy = spawnYFrac * H;
            px = cx + ((float)(Math.random() - 0.5)) * 2f * halfW;
            py = cy + ((float)(Math.random() - 0.5)) * 2f * halfH;
        }
        float baseDeg   = L.baseAngle;
        float spreadDeg = L.angleSpread;
        float speedBase = L.initialSpeed;
        float speedJit  = L.speedJitter / 100f;
        float angleDeg  = baseDeg + (float)((Math.random() - 0.5) * spreadDeg);
        if ("radial".equals(src)) angleDeg = (float)(Math.random() * 360.0);
        float angle = (float)(angleDeg * Math.PI / 180.0);
        float speed = speedBase * (1f + speedJit * (float)((Math.random() - 0.5) * 2));
        float burst = L.burstSpeed * audioMul;
        float grow  = L.energyGrow / 100f;
        float speedFinal = speed * (1f + grow * audioMul) + burst;
        float sizeBase = L.particleSize;
        float sizeJit  = L.sizeJitter / 100f;
        float size = Math.max(0.5f, sizeBase * (1f + sizeJit * (float)((Math.random() - 0.5) * 2))
                                     * (1f + grow * audioMul));
        float lifeBase = L.life;
        float lifeJit  = L.lifeJitter / 100f;
        float life = Math.max(0.05f, lifeBase * (1f + lifeJit * (float)((Math.random() - 0.5) * 2)));

        L._px[i]     = px;
        L._py[i]     = py;
        L._vx[i]     = (float) Math.cos(angle) * speedFinal;
        L._vy[i]     = (float) Math.sin(angle) * speedFinal;
        L._page[i]   = 0f;
        L._plife[i]  = life;
        L._psize[i]  = size;
        L._phue[i]   = (float) Math.random();
        L._palive[i] = true;
        return i;
    }

    private static float turb(float x, float y, float tSec) {
        return (float)(Math.sin(x * 0.013 + tSec * 1.7) * 0.6
                     + Math.sin(y * 0.011 - tSec * 2.3 + x * 0.005) * 0.4);
    }

    /**
     * Shared geometry → strokes / fills / mirror painter. Used by both
     * renderLine (freq-grow / static) and renderLineFlow. fxGlow is
     * applied to paintStroke + paintArea once at the top and cleared
     * once at the bottom so every draw inside picks it up.
     */
    private void paintLinePath(
            PluginCanvas canvas, float W, float H, Layer L,
            float[] xs, float[] ys, float[] mags, int n, float baseline,
            float strokeW, float alphaMul, float energy
    ) {
        float glowR = (L.fxGlow > 0) ? (L.fxGlow * 24f / 100f) : 0f;
        float blurR = (L.fxBlur > 0) ? (L.fxBlur * 4f  / 100f) : 0f;
        int glowColor = parseHex(L.color, 0xFFFFFFFF);
        if (glowR > 0f) {
            paintStroke.setGlow(glowColor, glowR);
            paintArea.setGlow(glowColor, glowR);
        }
        if (blurR > 0f) {
            paintStroke.setBlur(blurR);
            paintArea.setBlur(blurR);
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
        if (glowR > 0f) {
            paintStroke.setGlow(glowColor, 0f);
            paintArea.setGlow(glowColor, 0f);
        }
        if (blurR > 0f) {
            paintStroke.setBlur(0f);
            paintArea.setBlur(0f);
        }
    }

    /**
     * Bin → injection source mapping. `bass/mid/high` average
     * subbands of the spectrum, `peak` is the loudest single bin,
     * `energy` averages everything. Matches `sourceValue` in
     * wave-engine.js.
     */
    private static float sourceValue(float[] bins, String key) {
        int N = bins.length;
        if (N == 0) return 0f;
        String k = (key == null) ? "energy" : key;
        if ("bass".equals(k)) return avgRange(bins, 0, Math.max(1, (int)(N * 0.15f)));
        if ("mid".equals(k))  return avgRange(bins, (int)(N * 0.15f), (int)(N * 0.50f));
        if ("high".equals(k)) return avgRange(bins, (int)(N * 0.50f), N);
        if ("peak".equals(k)) {
            float m = 0f;
            for (int i = 0; i < N; i++) if (bins[i] > m) m = bins[i];
            return m;
        }
        return avgRange(bins, 0, N);
    }

    private static float avgRange(float[] bins, int lo, int hi) {
        if (hi <= lo) return 0f;
        float s = 0f;
        for (int i = lo; i < hi; i++) s += bins[i];
        return s / (hi - lo);
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
        // Carried on the paint passed to canvas.saveLayer() so the
        // layer's offscreen buffer composites onto the parent canvas
        // with this blend. That's how the JS engine isolates blend
        // modes too — without the saveLayer step a SCREEN paint would
        // wash neighbouring layers' antialiased fringes to white.
        if (b == null) return BlendMode.SRC_OVER;
        switch (b) {
            case "lighter":     return BlendMode.ADD;
            case "screen":      return BlendMode.SCREEN;
            case "multiply":    return BlendMode.MULTIPLY;
            case "overlay":     return BlendMode.OVERLAY;
            case "darken":      return BlendMode.DARKEN;
            case "lighten":     return BlendMode.LIGHTEN;
            case "color-dodge": return BlendMode.COLOR_DODGE;
            case "color-burn":  return BlendMode.COLOR_BURN;
            default:            return BlendMode.SRC_OVER;
        }
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

    private enum LayerType { BARS, LINE, PARTICLES }

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
        // Per-bar / per-line glow radius. JS maps fxGlow/100*24 px to
        // ctx.shadowBlur + shadowColor=barColor. Wired via
        // PluginPaint.setGlow on every paint, cleared after the draw.
        int     fxGlow      = 0;
        // Frame-to-frame trail (Phase 3 — needs offscreen bitmap).
        int     fxFade      = 0;
        // Per-bar blur (Phase 2 — needs PluginPaint.setBlur).
        int     fxBlur      = 0;
        // Anim mode + injection knobs for flow-l/flow-r/flow-c/pulse.
        // Missing animMode = "freq-grow" (static, every bar bound to
        // its own bin). Flow modes inject energy at one edge / centre
        // and propagate sideways; pulse only injects on transient
        // peaks. Mirrors wave-engine.js renderBarsFlow.
        String  animMode        = "freq-grow";
        String  animSource      = "energy";
        float   animSensitivity = 100f;
        float   animThreshold   = 4f;
        float   animDecay       = 15f;
        // ── particles-specific ──
        // Matches renderParticlesSkia defaults in waveform-builder.html
        // (Skia is source of truth — this Java side is a faithful
        // mirror so .wave.json renders identically on device).
        int     spawnRate    = 40;
        String  spawnSource  = "point";
        int     spawnX       = 50;
        int     spawnY       = 100;
        // Rect source extent (% of canvas, around centre (spawnX, spawnY)).
        // Only used when spawnSource is "rect"; ignored for point /
        // bottom-line / top-line / radial / random.
        int     spawnW       = 20;
        int     spawnH       = 20;
        float   baseAngle    = 270f;
        float   angleSpread  = 60f;
        float   initialSpeed = 80f;
        float   burstSpeed   = 0f;
        int     speedJitter  = 30;
        float   particleSize = 4f;
        int     sizeJitter   = 30;
        String  boundary     = "kill";
        int     bounceRestitution = 60;
        float   gravity      = -120f;
        float   wind         = 0f;
        float   drag         = 20f;
        float   turbulence   = 0f;
        float   life         = 1.5f;
        int     lifeJitter   = 30;
        float   sizeEnd      = 0.4f;
        int     alphaStart   = 100;
        int     alphaEnd     = 0;
        float   energyGrow   = 80f;
        // scratch
        float[] _smoothed;
        float[] _flow;
        float   _lastEnergy = 0f;
        long    _lastPulseT = 0L;
        // particle pool (SoA — parallel arrays indexed by slot). Lazy
        // created in renderParticles' ensure step; recreated when
        // L.count changes.
        float[] _px, _py, _vx, _vy, _page, _plife, _psize, _phue;
        boolean[] _palive;
        int     _spawnCursor = 0;
        long    _lastT       = 0L;     // ms — frame dt source
        float   _spawnBank   = 0f;     // accumulated fractional spawns
        /** Set per-frame by render() — true when this layer renders
         *  into its own offscreen layer (blend mode or partial
         *  opacity). When true, the per-bar / per-line paints draw
         *  fully opaque + with default blend; the layer paint that
         *  was handed to canvas.saveLayer() carries opacity + blend
         *  at composite time. JS engine works the same way. */
        boolean _useLayer = false;
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
        if (type.equals("bars"))           lt = LayerType.BARS;
        else if (type.equals("line"))      lt = LayerType.LINE;
        else if (type.equals("particles")) lt = LayerType.PARTICLES;
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
        l.fxGlow      = asInt(s.get("fxGlow"),      0);
        l.fxFade      = asInt(s.get("fxFade"),      0);
        l.fxBlur      = asInt(s.get("fxBlur"),      0);
        l.animMode        = asString(s.get("animMode"),        "freq-grow");
        l.animSource      = asString(s.get("animSource"),      "energy");
        l.animSensitivity = asFloat(s.get("animSensitivity"),  100f);
        l.animThreshold   = asFloat(s.get("animThreshold"),    4f);
        l.animDecay       = asFloat(s.get("animDecay"),        15f);
        // ── particles-specific ──
        // Defaults match waveform-builder.html TYPES.particles
        // (Skia is the source of truth; this side mirrors it).
        l.spawnRate         = asInt(s.get("spawnRate"),          40);
        l.spawnSource       = asString(s.get("spawnSource"),     "point");
        l.spawnX            = asInt(s.get("spawnX"),             50);
        l.spawnY            = asInt(s.get("spawnY"),             100);
        l.spawnW            = asInt(s.get("spawnW"),             20);
        l.spawnH            = asInt(s.get("spawnH"),             20);
        l.baseAngle         = asFloat(s.get("baseAngle"),        270f);
        l.angleSpread       = asFloat(s.get("angleSpread"),      60f);
        l.initialSpeed      = asFloat(s.get("initialSpeed"),     80f);
        l.burstSpeed        = asFloat(s.get("burstSpeed"),       0f);
        l.speedJitter       = asInt(s.get("speedJitter"),        30);
        l.particleSize      = asFloat(s.get("particleSize"),     4f);
        l.sizeJitter        = asInt(s.get("sizeJitter"),         30);
        l.boundary          = asString(s.get("boundary"),        "kill");
        l.bounceRestitution = asInt(s.get("bounceRestitution"),  60);
        l.gravity           = asFloat(s.get("gravity"),          -120f);
        l.wind              = asFloat(s.get("wind"),             0f);
        l.drag              = asFloat(s.get("drag"),             20f);
        l.turbulence        = asFloat(s.get("turbulence"),       0f);
        l.life              = asFloat(s.get("life"),             1.5f);
        l.lifeJitter        = asInt(s.get("lifeJitter"),         30);
        l.sizeEnd           = asFloat(s.get("sizeEnd"),          0.4f);
        l.alphaStart        = asInt(s.get("alphaStart"),         100);
        l.alphaEnd          = asInt(s.get("alphaEnd"),           0);
        l.energyGrow        = asFloat(s.get("energyGrow"),       80f);
        // Particles inject defaults if the field is missing (most
        // .wave.json files won't carry these on bars/line layers).
        // For particles, animMode default is 'continuous' rather
        // than 'freq-grow'.
        if (lt == LayerType.PARTICLES) {
            l.animMode = asString(s.get("animMode"), "continuous");
        }
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
