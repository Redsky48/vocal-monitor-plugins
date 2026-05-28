package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.gamekit.Ease;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.audio.NoteName;
import com.vocalmonitor.plugin.gamekit.audio.PitchTracker;

import java.util.Map;

/**
 * Lip Trill / SOVT Coach — trains semi-occluded vocal-tract exercises
 * (lip trills, tongue trills, straw phonation).  These are the
 * cornerstone of modern voice therapy and warm-up: the lips/straw
 * create back-pressure that balances the vocal folds, and a *good*
 * trill shows up acoustically as a steady carrier pitch with a
 * regular, shallow amplitude flutter (~18–40 Hz "brrr").
 *
 * <p>What it measures, frame by frame, from {@code streams["waveform"]}:
 * <ul>
 *   <li><b>Flutter rate</b> — the modulation frequency of the RMS
 *       envelope.  We track the envelope's own zero-crossings about its
 *       running mean to estimate how many times per second the buzz
 *       pulses.  A healthy lip trill sits around 20–35 Hz.</li>
 *   <li><b>Flutter regularity</b> — variance of successive flutter
 *       periods.  Even periods = a relaxed, well-supported trill;
 *       erratic periods = a sputtering, effortful one.</li>
 *   <li><b>Pitch steadiness</b> — the carrier note should stay put.
 *       We reuse the gamekit {@link PitchTracker} and score the cent
 *       drift.</li>
 * </ul>
 *
 * <p>The three combine into a single 0–100 "trill quality" score that
 * drives the visuals: a vertical SOVT "bubble tube" whose bubbles rise
 * smoothly and densely when the trill is good and stutter / stall when
 * it falls apart, plus a steadiness ring and a hold-timer that rewards
 * sustaining a clean trill.  No grading guilt — the copy frames it as
 * "keep the bubbles flowing".
 *
 * <p>Visual-only on the live monitor (audio passes through unchanged);
 * see {@link GamePluginBase} for the passthrough {@code process()}.
 */
public final class LipTrill extends GamePluginBase {

    // ── Pitch / carrier ─────────────────────────────────────
    private final PitchTracker pitch = new PitchTracker();

    // ── Envelope-modulation (flutter) analysis ──────────────
    // The host hands us short PCM blocks (~1024 samples ≈ 23 ms) — that's
    // SHORTER than one period of a 20-35 Hz trill, so we cannot measure
    // the flutter inside a single block. Instead we keep all envelope state
    // PERSISTENT across blocks and time the interval between successive
    // up-crossings of the envelope's own slow baseline. Two stages of
    // de-carrier-ing are needed: (1) decimate |x| with a boxcar hop down to
    // ~440 Hz, then (2) a 2-stage one-pole low-pass at ~45 Hz. A single
    // one-pole was too gentle — it leaked the rectified carrier ripple
    // (2·f0 ≥ ~200 Hz) and the crossing timer locked onto THAT instead of
    // the 12-45 Hz flutter (steady tones read as ~58 Hz "flutter"). The
    // boxcar + cascade kills the ripple so only the real flutter survives.
    private float accHop = 0f;           // boxcar decimation accumulator
    private int   hopCount = 0;
    private float env1 = 0f, env2 = 0f;  // 2-stage LP of decimated |x|
    private float envBase = 0f;          // slow baseline (mean of env2)
    private float envMax = 0f, envMin = 0f; // decaying extremes for depth
    private boolean flutAbove = false;   // crossing-detector hysteresis state
    private int decSinceCross = 0;       // decimated samples since up-cross
    private float[] lastWave = null;     // dedupe re-rendered snapshots
    private float flutterRateHz = 0f;    // smoothed estimate
    private float flutterDepth = 0f;     // 0..1 modulation depth
    private float regularity = 0f;       // 0..1, 1 = perfectly even

    // period bookkeeping for regularity
    private float periodMean = 0.035f;
    private float periodVar = 0f;

    // ── Quality + session state ─────────────────────────────
    private float quality = 0f;          // 0..1 smoothed
    private boolean active = false;      // currently trilling
    private float holdS = 0f;            // current clean-trill streak (s)
    private float bestHoldS = 0f;        // best this session

    // ── Bubble tube (visual) ────────────────────────────────
    private static final int MAX_BUBBLES = 48;
    private final float[] bubX = new float[MAX_BUBBLES];
    private final float[] bubY = new float[MAX_BUBBLES];   // 0..1 up the tube
    private final float[] bubR = new float[MAX_BUBBLES];   // radius (rel)
    private final float[] bubV = new float[MAX_BUBBLES];   // rise speed (rel/s)
    private final boolean[] bubAlive = new boolean[MAX_BUBBLES];
    private float spawnAccum = 0f;

    // ── PRNG (no java.util.Random alloc churn) ──────────────
    private long rng = 0x51AB11ED7E57L;
    private float rnd() {
        rng ^= rng << 13; rng ^= rng >>> 7; rng ^= rng << 17;
        return (rng & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }

    @Override
    protected void onInit(int sr) {
        pitch.setSampleRate(sr).lpCutoff(700f).floor(0.006f).reset();
        accHop = 0f; hopCount = 0;
        env1 = env2 = envBase = envMax = envMin = 0f;
        flutAbove = false; decSinceCross = 0; lastWave = null;
        flutterRateHz = flutterDepth = regularity = 0f;
        periodMean = 0.035f; periodVar = 0f;
        quality = 0f; active = false; holdS = 0f; bestHoldS = 0f;
        spawnAccum = 0f;
        for (int i = 0; i < MAX_BUBBLES; i++) bubAlive[i] = false;
    }

    // ── Analysis ────────────────────────────────────────────
    private void analyse(Map<String, float[]> streams) {
        pitch.feed(streams, dt);
        float[] wave = streams != null ? streams.get("waveform") : null;
        if (wave == null || wave.length < 32) {
            decayIdle();
            return;
        }
        // Render can fire faster than the mic delivers new PCM; walking
        // the same snapshot twice would double-count crossings and inflate
        // the rate. Only run the envelope walk on a fresh array.
        if (wave != lastWave) {
            lastWave = wave;
            walkEnvelope(wave);
        }
        updateScores();
    }

    /** Decimate |x| (boxcar) → 2-stage LP → up-crossing timer.  All state
     *  persists across blocks so the 12-45 Hz flutter is reconstructed even
     *  though one block is shorter than a flutter period. */
    private void walkEnvelope(float[] wave) {
        final int fs = sampleRate;
        final int HOP = Math.max(16, fs / 441);   // ~441 Hz decimated rate
        final float fsd = (float) fs / HOP;
        // 2-stage one-pole LP at ~45 Hz on the decimated stream: passes the
        // 12-45 Hz flutter, rejects the rectified-carrier ripple (≥2·f0).
        final float aLp   = 1f - (float) Math.exp(-2 * Math.PI * 45f / fsd);
        final float aBase = 1f - (float) Math.exp(-2 * Math.PI * 3f  / fsd);
        final float aExt  = 1f - (float) Math.exp(-2 * Math.PI * 10f / fsd);
        final float minR = 8f, maxR = 55f;
        final int refractory = (int) (fsd / maxR);

        for (int i = 0; i < wave.length; i++) {
            accHop += Math.abs(wave[i]);
            if (++hopCount < HOP) continue;
            float e = accHop / HOP;                // boxcar-decimated mean-abs
            accHop = 0f; hopCount = 0;

            env1 += aLp * (e - env1);
            env2 += aLp * (env1 - env2);
            envBase += aBase * (env2 - envBase);
            if (env2 > envMax) envMax = env2; else envMax += aExt * (env2 - envMax);
            if (env2 < envMin) envMin = env2; else envMin += aExt * (env2 - envMin);

            float dev = env2 - envBase;
            float thr = Math.max(envBase * 0.06f, 8e-5f);
            if (!flutAbove && dev > thr && decSinceCross >= refractory) {
                flutAbove = true;
                float periodS = decSinceCross / fsd;
                decSinceCross = 0;
                float r = periodS > 1e-4f ? 1f / periodS : 0f;
                if (r >= minR && r <= maxR) {
                    flutterRateHz += 0.25f * (r - flutterRateHz);
                    float d = periodS - periodMean;
                    periodMean += 0.15f * d;
                    periodVar  += 0.15f * (d * d - periodVar);
                    float cov = periodMean > 1e-4f
                        ? (float) Math.sqrt(periodVar) / periodMean : 1f;
                    regularity += 0.25f * (Ease.clamp(1f - cov * 2.2f, 0f, 1f) - regularity);
                }
            } else if (flutAbove && dev < -thr * 0.5f) {
                flutAbove = false;
            }
            if (decSinceCross < (int) fsd) decSinceCross++;

            // No pulse for >~1/6 s → the buzz has stopped; let it fall.
            if (decSinceCross > fsd / 6f) {
                flutterRateHz += 0.05f * (0f - flutterRateHz);
                regularity    += 0.03f * (0f - regularity);
            }
        }

        float denom = envMax + envMin + 1e-5f;
        float depth = denom > 1e-5f ? (envMax - envMin) / denom : 0f;
        flutterDepth += 0.2f * (Ease.clamp(depth, 0f, 1f) - flutterDepth);
    }

    /** Derive the trill state + quality score; runs every frame. */
    private void updateScores() {
        boolean inBand = flutterRateHz >= 12f && flutterRateHz <= 45f;
        boolean hasDepth = flutterDepth > 0.08f;
        boolean loud = pitch.rms() > 0.006f;
        active = inBand && hasDepth && loud;

        float drift = Math.abs(pitch.centsFrom(NoteName.snapToSemitone(pitch.hz())));
        float pitchScore = Ease.clamp(1f - drift / 60f, 0f, 1f);
        float rateScore = inBand
            ? Ease.clamp(1f - Math.abs(flutterRateHz - 27f) / 22f, 0f, 1f)
            : 0f;
        float target = active
            ? (0.45f * regularity + 0.30f * rateScore + 0.25f * pitchScore)
            : 0f;
        quality += 0.15f * (target - quality);

        if (active && quality > 0.55f) {
            holdS += dt;
            if (holdS > bestHoldS) bestHoldS = holdS;
        } else if (!active) {
            holdS = 0f;
        }
    }

    private void decayIdle() {
        flutterDepth  *= 0.85f;
        flutterRateHz *= 0.85f;
        regularity    *= 0.9f;
        quality += 0.1f * (0f - quality);
        active = false;
        lastWave = null;
    }

    // ── Bubble simulation ───────────────────────────────────
    private void stepBubbles(float tubeCx, float tubeHalfW) {
        // Spawn rate proportional to quality.
        float rate = 2f + quality * 26f;            // bubbles/sec
        if (active) {
            spawnAccum += rate * dt;
            while (spawnAccum >= 1f) {
                spawnAccum -= 1f;
                spawnBubble();
            }
        } else {
            spawnAccum *= 0.5f;
        }
        for (int i = 0; i < MAX_BUBBLES; i++) {
            if (!bubAlive[i]) continue;
            bubY[i] += bubV[i] * dt;
            // gentle horizontal wobble keyed to flutter
            bubX[i] += (float) Math.sin((bubY[i] + i) * 9.0) * 0.04f * dt;
            if (bubY[i] > 1.02f) bubAlive[i] = false;
        }
    }

    private void spawnBubble() {
        for (int i = 0; i < MAX_BUBBLES; i++) {
            if (bubAlive[i]) continue;
            bubAlive[i] = true;
            bubX[i] = (rnd() - 0.5f) * 0.7f;        // -0.35..0.35 of half-width
            bubY[i] = 0f;
            bubR[i] = 0.4f + rnd() * 0.9f;          // relative radius
            bubV[i] = 0.55f + quality * 0.9f + rnd() * 0.25f;
            return;
        }
    }

    @Override
    public void onTouchDown(float x, float y) {
        // tap resets the session best — a deliberate "new run" gesture
        bestHoldS = 0f;
        holdS = 0f;
    }

    // ── Render ──────────────────────────────────────────────
    @Override
    public void render(PluginCanvas c, int w, int h, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(w, h, timeMs, streams);
        analyse(streams);

        float cx = w * 0.5f;
        // Background: deep calm gradient, warmer when the trill is good.
        int topCold = 0xFF0E1726, botCold = 0xFF101A2E;
        int topWarm = 0xFF13243A, botWarm = 0xFF0E2030;
        Gfx.gradientSky(c, w, h,
            Palette.mix(topCold, topWarm, quality),
            Palette.mix(botCold, botWarm, quality));

        c.save();
        juice.applyShake(c);

        // ── Title ── (dynamic coaching cue sits just below, drawn later)
        Gfx.textCenter(c, "Lip Trill Coach", cx, 34f * scale, 22f * scale, Palette.UI_TEXT);

        // ── SOVT bubble tube (centre) ──
        float tubeW = Math.min(w * 0.26f, 150f * scale);
        float tubeHalfW = tubeW * 0.5f;
        float tubeTop = h * 0.24f;
        float tubeBot = h * 0.72f;
        float tubeH = tubeBot - tubeTop;
        float rad = tubeHalfW;

        // tube glass
        PluginPaint glass = c.newPaint();
        glass.setLinearGradient(cx - tubeHalfW, 0, cx + tubeHalfW, 0,
            new int[] { 0x16FFFFFF, 0x05FFFFFF, 0x16FFFFFF },
            new float[] { 0f, 0.5f, 1f });
        c.drawRoundRect(cx - tubeHalfW, tubeTop, cx + tubeHalfW, tubeBot, rad, glass);
        // water fill colour reflects quality
        int waterTop = Palette.withAlpha(Palette.mix(0xFF2B6CB0, 0xFF2BAE8F, quality), 0.30f);
        int waterBot = Palette.withAlpha(Palette.mix(0xFF1B3A6B, 0xFF166B5C, quality), 0.55f);
        PluginPaint water = c.newPaint();
        water.setLinearGradient(0, tubeTop, 0, tubeBot,
            new int[] { waterTop, waterBot }, new float[] { 0f, 1f });
        c.drawRoundRect(cx - tubeHalfW + 2, tubeTop + 2, cx + tubeHalfW - 2, tubeBot - 2, rad, water);

        // bubbles
        stepBubbles(cx, tubeHalfW);
        for (int i = 0; i < MAX_BUBBLES; i++) {
            if (!bubAlive[i]) continue;
            float by = tubeBot - bubY[i] * tubeH;
            float bx = cx + bubX[i] * tubeHalfW;
            float r = bubR[i] * 5.5f * scale;
            float a = Ease.clamp(1f - bubY[i] * 0.4f, 0.25f, 0.9f);
            Gfx.strokeCircle(c, bx, by, r,
                Palette.withAlpha(0xFFEAF6FF, a * 0.7f),
                Palette.withAlpha(0xFFFFFFFF, a), 1.3f * scale);
        }
        // tube rim highlight
        PluginPaint rim = c.newPaint();
        rim.setColor(0x33FFFFFF); rim.setStyle(PluginStyle.STROKE); rim.setStrokeWidth(2f * scale);
        c.drawRoundRect(cx - tubeHalfW, tubeTop, cx + tubeHalfW, tubeBot, rad, rim);

        // ── Steadiness ring (left) ──
        float ringCx = w * 0.20f, ringCy = h * 0.46f, ringR = Math.min(w, h) * 0.10f;
        drawArcMeter(c, ringCx, ringCy, ringR, regularity, "STEADY",
            Math.round(regularity * 100f) + "%");

        // ── Flutter-rate dial (right) ──
        float rrCx = w * 0.80f, rrCy = h * 0.46f, rrR = Math.min(w, h) * 0.10f;
        // map 12..45 Hz onto 0..1 for the arc; ideal band 20..35 shaded
        float rateNorm = Ease.clamp((flutterRateHz - 12f) / (45f - 12f), 0f, 1f);
        drawArcMeter(c, rrCx, rrCy, rrR, rateNorm, "FLUTTER",
            (flutterRateHz >= 5f ? Math.round(flutterRateHz) + " Hz" : "—"));

        // ── Quality bar + hold timer (bottom) ──
        float barY = h * 0.90f;
        float barX0 = w * 0.18f, barX1 = w * 0.82f;
        Gfx.textLeft(c, "TRILL QUALITY", barX0, barY - 14f * scale, 12f * scale, Palette.UI_TEXT_DIM);
        // bar bg
        PluginPaint bbg = c.newPaint(); bbg.setColor(0xFF1B2330);
        c.drawRoundRect(barX0, barY, barX1, barY + 12f * scale, 6f * scale, bbg);
        // bar fill
        int qCol = quality > 0.7f ? Palette.ACCENT_GREEN
                 : quality > 0.4f ? Palette.ACCENT_AMBER : Palette.ACCENT_RED;
        PluginPaint bfg = c.newPaint();
        bfg.setColor(qCol); bfg.setGlow(qCol, 8f * scale);
        c.drawRoundRect(barX0, barY, barX0 + (barX1 - barX0) * quality, barY + 12f * scale, 6f * scale, bfg);

        // hold timer chip (right of bar)
        String holdTxt = active ? String.format("%.1fs", holdS) : "hold…";
        Gfx.textRight(c, "best " + String.format("%.1fs", bestHoldS),
            barX1, barY - 14f * scale, 12f * scale, Palette.UI_TEXT_DIM);

        // ── Coaching cue (centre, above tube) ──
        String cue;
        int cueCol;
        if (!active) {
            cue = "Brrr — lips loose, let it buzz";
            cueCol = Palette.UI_TEXT_DIM;
        } else if (flutterRateHz < 18f) {
            cue = "A touch faster — relax the lips";
            cueCol = Palette.ACCENT_AMBER;
        } else if (flutterRateHz > 38f) {
            cue = "Ease off — less pressure";
            cueCol = Palette.ACCENT_AMBER;
        } else if (quality > 0.7f) {
            cue = "Lovely — steady as she goes  " + holdTxt;
            cueCol = Palette.ACCENT_GREEN;
        } else {
            cue = "Good — even it out  " + holdTxt;
            cueCol = Palette.ACCENT_BLUE;
        }
        Gfx.textCenter(c, cue, cx, 60f * scale, 15f * scale, cueCol);

        // carrier note readout under tube
        if (pitch.voiced()) {
            Gfx.textCenter(c, NoteName.of(pitch.hz()) + " · " + Math.round(pitch.hz()) + " Hz",
                cx, tubeBot + 24f * scale, 14f * scale, Palette.UI_TEXT_DIM);
        }

        c.restore();
        particles.draw(c);
        juice.drawOverlay(c, w, h);
    }

    /** Circular gauge: background ring + value arc (270° sweep) + centred
     *  label/value.  Arc built from short line segments (no arc API). */
    private void drawArcMeter(PluginCanvas c, float cx, float cy, float r,
                              float v01, String label, String value) {
        v01 = Ease.clamp(v01, 0f, 1f);
        final float startDeg = 135f, sweepDeg = 270f;
        int segs = 48;
        // background track
        PluginPath bg = c.newPath();
        buildArc(bg, cx, cy, r, startDeg, sweepDeg, segs);
        PluginPaint bgP = c.newPaint();
        bgP.setColor(0xFF243042); bgP.setStyle(PluginStyle.STROKE);
        bgP.setStrokeWidth(7f * scale);
        c.drawPath(bg, bgP);
        // value arc
        int col = v01 > 0.66f ? Palette.ACCENT_GREEN
                : v01 > 0.33f ? Palette.ACCENT_AMBER : Palette.ACCENT_RED;
        PluginPath va = c.newPath();
        buildArc(va, cx, cy, r, startDeg, sweepDeg * v01, segs);
        PluginPaint vp = c.newPaint();
        vp.setColor(col); vp.setStyle(PluginStyle.STROKE); vp.setStrokeWidth(7f * scale);
        vp.setGlow(col, 7f * scale);
        c.drawPath(va, vp);
        // centre value + label
        Gfx.textCenter(c, value, cx, cy + 4f * scale, 20f * scale, Palette.UI_TEXT);
        Gfx.textCenter(c, label, cx, cy + r + 16f * scale, 11f * scale, Palette.UI_TEXT_DIM);
    }

    /** Append a poly-line approximation of an arc to {@code path}.
     *  Angles in degrees, clockwise from the +x axis, y-down screen. */
    private void buildArc(PluginPath path, float cx, float cy, float r,
                          float startDeg, float sweepDeg, int segs) {
        if (segs < 1) segs = 1;
        for (int i = 0; i <= segs; i++) {
            float t = (float) i / segs;
            double ang = Math.toRadians(startDeg + sweepDeg * t);
            float px = cx + (float) Math.cos(ang) * r;
            float py = cy + (float) Math.sin(ang) * r;
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
    }
}
