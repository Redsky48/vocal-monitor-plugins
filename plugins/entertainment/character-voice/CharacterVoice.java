package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.PluginPaint;
import com.vocalmonitor.plugin.PluginPath;
import com.vocalmonitor.plugin.PluginStyle;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Character Voice — click an avatar, sing, sound like that character.
 *
 * Six characters in a 3×2 grid:
 *
 *   ROBOT      ALIEN      PRINCESS
 *   SMURF      MONSTER    PIRATE
 *
 * Each character is a stored preset of:
 *   - pitchRate   delay-line pitch shift rate (1.0 = no shift,
 *                 1.5 ≈ +7 semitones, 0.5 ≈ −12 semitones)
 *   - ringHz      ring-modulator carrier in Hz (0 = bypass ring mod)
 *   - lpHz        4-pole low-pass cutoff (0 = bypass)
 *   - hpHz        one-pole high-pass cutoff (0 = bypass)
 *   - drive       pre-tanh gain
 *   - tremoloHz   amplitude-modulation rate (0 = bypass)
 *   - tremoloDepth 0..1, how deep the tremolo dips
 *
 * The pitch shifter is the classic two-tap crossfade ring buffer —
 * read pointers at N/2 apart, both advance at `pitchRate` per output
 * sample, contribution windowed by `sin(π · d / N)`.  Cheap, sounds
 * OK on speech for ±12 semitones, has a slight latency (~46 ms).
 *
 * Touch goes through {@link PluginHost#setParameter} via the
 * `character` index (0..5).  Clicking outside the avatar zone is
 * ignored.
 */
public final class CharacterVoice implements VocalMonitorVisualPlugin {

    // ── Character presets ──────────────────────────────────────
    private static final String[] NAMES =
        { "ROBOT", "ALIEN", "PRINCESS", "SMURF", "MONSTER", "PIRATE" };

    // pitchRate, ringHz, lpHz, hpHz, drive, tremHz, tremDepth
    private static final float[][] PRESETS = {
        // Robot — ring mod + drive, no pitch shift
        { 1.0f,  80f, 3500f,   0f, 1.6f, 0f,    0f   },
        // Alien — pitch up + ring mod + slow tremolo
        { 1.30f, 220f,    0f,   0f, 1.4f, 5.5f,  0.4f },
        // Princess — pitch up + slight high-shelf-y
        { 1.20f,   0f, 6000f,   0f, 1.0f, 0f,    0f   },
        // Smurf — big pitch up
        { 1.68f,   0f,    0f,   0f, 1.0f, 0f,    0f   },
        // Monster — pitch down + LP + drive + sub feel
        { 0.66f,   0f,  900f,   0f, 3.0f, 0f,    0f   },
        // Pirate — moderate pitch down + HP + drive
        { 0.78f,   0f,    0f, 250f, 2.0f, 0f,    0f   },
    };

    // ── Audio state ────────────────────────────────────────────
    private int sampleRate = 44100;
    private float pitchRate = 1f;
    private float ringHz = 0f;
    private float lpHz = 0f;
    private float hpHz = 0f;
    private float drive = 1f;
    private float tremHz = 0f;
    private float tremDepth = 0f;

    // Pitch shifter (two-tap crossfade).
    private static final int PS_N = 2048;
    private final float[] psBuf = new float[PS_N];
    private int psW = 0;
    private float psR1 = 0f;
    private float psR2 = PS_N / 2f;

    // Ring mod carrier phase.
    private double ringPhase = 0.0;
    // Tremolo phase.
    private double tremPhase = 0.0;
    // LP cascade (4-pole).
    private float lp1 = 0f, lp2 = 0f, lp3 = 0f, lp4 = 0f;
    // HP (one-pole HP via x - LP).
    private float hpLp = 0f;
    // Envelope for the meter.
    private float envelope = 0f;

    private int selectedCharacter = 0;
    private PluginHost host = null;

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        // Zero out delay line + phases.
        for (int i = 0; i < PS_N; i++) psBuf[i] = 0f;
        psW = 0; psR1 = 0f; psR2 = PS_N / 2f;
        ringPhase = 0.0; tremPhase = 0.0;
        lp1 = lp2 = lp3 = lp4 = 0f;
        hpLp = 0f;
        envelope = 0f;
        applyPreset(selectedCharacter);
    }

    private void applyPreset(int idx) {
        if (idx < 0 || idx >= PRESETS.length) return;
        selectedCharacter = idx;
        float[] p = PRESETS[idx];
        pitchRate = p[0];
        ringHz    = p[1];
        lpHz      = p[2];
        hpHz      = p[3];
        drive     = p[4];
        tremHz    = p[5];
        tremDepth = p[6];
    }

    @Override public void setHost(PluginHost h) { this.host = h; }

    @Override public String[] parameterNames() {
        return new String[] { "character" };
    }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return PRESETS.length - 1; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n)  { return "Character"; }
    @Override public void setParameter(String n, float v) {
        if ("character".equals(n)) {
            int idx = Math.round(v);
            if (idx < 0) idx = 0;
            if (idx >= PRESETS.length) idx = PRESETS.length - 1;
            applyPreset(idx);
        }
    }

    // ── Touch hit-zones — recomputed from the layout each render ──
    // Stored as { cx, cy, radius } per character index.
    private final float[] avX = new float[PRESETS.length];
    private final float[] avY = new float[PRESETS.length];
    private float avR = 0f;

    @Override
    public void onTouchDown(float x, float y) {
        for (int i = 0; i < PRESETS.length; i++) {
            float dx = x - avX[i];
            float dy = y - avY[i];
            if (dx * dx + dy * dy <= avR * avR) {
                applyPreset(i);
                if (host != null) host.setParameter("character", (float) i);
                return;
            }
        }
    }

    @Override
    public void process(float[] input, float[] output) {
        final double twoPi = 2.0 * Math.PI;
        final double ringInc = (ringHz > 0f) ? twoPi * ringHz / sampleRate : 0.0;
        final double tremInc = (tremHz > 0f) ? twoPi * tremHz / sampleRate : 0.0;
        final float lpAlpha  = (lpHz > 0f)
            ? (float) (1.0 - Math.exp(-2.0 * Math.PI * lpHz / sampleRate)) : 0f;
        final float hpAlpha  = (hpHz > 0f)
            ? (float) (1.0 - Math.exp(-2.0 * Math.PI * hpHz / sampleRate)) : 0f;

        for (int i = 0; i < input.length; i++) {
            float x = input[i];

            // ── Pitch shift via 2-tap crossfade delay line ──
            psBuf[psW] = x;
            psW++; if (psW >= PS_N) psW -= PS_N;
            psR1 += pitchRate; if (psR1 >= PS_N) psR1 -= PS_N;
            psR2 += pitchRate; if (psR2 >= PS_N) psR2 -= PS_N;
            int r1i = (int) psR1;
            int r2i = (int) psR2;
            float r1f = psR1 - r1i;
            float r2f = psR2 - r2i;
            float s1 = psBuf[r1i] * (1f - r1f) + psBuf[(r1i + 1) % PS_N] * r1f;
            float s2 = psBuf[r2i] * (1f - r2f) + psBuf[(r2i + 1) % PS_N] * r2f;
            float d1 = psR1 - psW; if (d1 < 0) d1 += PS_N;
            float d2 = psR2 - psW; if (d2 < 0) d2 += PS_N;
            float w1 = (float) Math.sin(Math.PI * d1 / PS_N);
            float w2 = (float) Math.sin(Math.PI * d2 / PS_N);
            x = s1 * w1 + s2 * w2;

            // ── Ring modulation ──
            if (ringHz > 0f) {
                float carrier = (float) Math.sin(ringPhase);
                ringPhase += ringInc;
                if (ringPhase > twoPi) ringPhase -= twoPi;
                x = x * carrier;
            }

            // ── Drive ──
            if (drive > 1.01f) {
                x = (float) Math.tanh(x * drive);
            }

            // ── High-pass (subtract LP from signal) ──
            if (hpHz > 0f) {
                hpLp += hpAlpha * (x - hpLp);
                x = x - hpLp;
            }

            // ── 4-pole Low-pass cascade ──
            if (lpHz > 0f) {
                lp1 += lpAlpha * (x   - lp1);
                lp2 += lpAlpha * (lp1 - lp2);
                lp3 += lpAlpha * (lp2 - lp3);
                lp4 += lpAlpha * (lp3 - lp4);
                x = lp4;
            }

            // ── Tremolo (amplitude mod) ──
            if (tremHz > 0f) {
                float tm = 1f - tremDepth * 0.5f * (1f - (float) Math.cos(tremPhase));
                tremPhase += tremInc;
                if (tremPhase > twoPi) tremPhase -= twoPi;
                x = x * tm;
            }

            // Soft-clip safety on the final stage.
            x = (float) Math.tanh(x);
            output[i] = x;

            // Envelope for the meter.
            float absx = Math.abs(x);
            if (absx > envelope) envelope += 0.05f * (absx - envelope);
            else                  envelope += 0.001f * (absx - envelope);
        }
    }

    // ── Render ─────────────────────────────────────────────────
    @Override
    public void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        Map<String, Float> params,
        Map<String, float[]> streams
    ) {
        // Sunset-purple gradient background.
        PluginPaint bg = canvas.newPaint();
        bg.setLinearGradient(0, 0, 0, height,
            new int[] { 0xFF1A1232, 0xFF3A1B5C, 0xFF6B2566 },
            new float[] { 0f, 0.5f, 1f });
        canvas.drawRect(0, 0, width, height, bg);

        // Title.
        PluginPaint title = canvas.newPaint();
        title.setColor(0xFFFFFFFF);
        title.setTextSize(24f);
        title.setTextAlign(1);
        canvas.drawText("Pick a character — sing to become them!",
            width / 2f, 36f, title);

        // 3 columns × 2 rows grid layout.
        int cols = 3, rows = 2;
        float padX = 36f, padY = 70f;
        float footerH = 56f;
        float gridW = width - padX * 2;
        float gridH = height - padY - footerH;
        float cellW = gridW / cols;
        float cellH = gridH / rows;
        avR = Math.min(cellW, cellH) * 0.32f;

        for (int i = 0; i < PRESETS.length; i++) {
            int col = i % cols;
            int row = i / cols;
            float cx = padX + cellW * col + cellW / 2f;
            float cy = padY + cellH * row + cellH / 2f - 12f;
            avX[i] = cx; avY[i] = cy;
            drawAvatar(canvas, i, cx, cy, avR, i == selectedCharacter, timeMs);
            // Name label below.
            PluginPaint nm = canvas.newPaint();
            nm.setColor(i == selectedCharacter ? 0xFFFFD66B : 0xFFCFCFCF);
            nm.setTextSize(15f);
            nm.setTextAlign(1);
            canvas.drawText(NAMES[i], cx, cy + avR + 24f, nm);
        }

        // Output level meter across the bottom.
        float meterY = height - 34f;
        float meterPad = 36f;
        PluginPaint mBg = canvas.newPaint();
        mBg.setColor(0xFF1A1A2A);
        canvas.drawRoundRect(meterPad, meterY - 8, width - meterPad, meterY + 8, 6f, mBg);
        float lvl = Math.min(1f, envelope * 3f);
        PluginPaint mFg = canvas.newPaint();
        mFg.setColor(0xFFFFD66B);
        canvas.drawRoundRect(meterPad, meterY - 8,
            meterPad + (width - meterPad * 2) * lvl, meterY + 8, 6f, mFg);
    }

    /** Draw a stylised face for character [idx] centred at (cx,cy). */
    private void drawAvatar(
        PluginCanvas canvas, int idx,
        float cx, float cy, float r,
        boolean selected, long timeMs
    ) {
        // Selection ring.
        if (selected) {
            PluginPaint ring = canvas.newPaint();
            ring.setColor(0xFFFFD66B);
            ring.setGlow(0xFFFFD66B, 20f);
            ring.setStyle(PluginStyle.STROKE);
            ring.setStrokeWidth(4f);
            float pulse = 1f + 0.04f * (float) Math.sin(timeMs * 0.005);
            canvas.drawCircle(cx, cy, r * 1.12f * pulse, ring);
        }

        switch (idx) {
            case 0: drawRobot(canvas,    cx, cy, r); break;
            case 1: drawAlien(canvas,    cx, cy, r, timeMs); break;
            case 2: drawPrincess(canvas, cx, cy, r); break;
            case 3: drawSmurf(canvas,    cx, cy, r); break;
            case 4: drawMonster(canvas,  cx, cy, r); break;
            case 5: drawPirate(canvas,   cx, cy, r); break;
        }
    }

    // ─── Avatar drawings ──────────────────────────────────────
    private void drawRobot(PluginCanvas c, float cx, float cy, float r) {
        PluginPaint body = c.newPaint();
        body.setColor(0xFFB6B6C0);
        c.drawRoundRect(cx - r*0.85f, cy - r*0.85f, cx + r*0.85f, cy + r*0.85f, r*0.20f, body);
        // antenna
        PluginPaint ant = c.newPaint();
        ant.setColor(0xFF808088);
        ant.setStyle(PluginStyle.STROKE);
        ant.setStrokeWidth(3f);
        c.drawLine(cx, cy - r*0.85f, cx, cy - r*1.20f, ant);
        PluginPaint bulb = c.newPaint();
        bulb.setColor(0xFFE25656);
        bulb.setGlow(0xFFE25656, 8f);
        c.drawCircle(cx, cy - r*1.25f, r*0.10f, bulb);
        // eyes (square)
        PluginPaint eye = c.newPaint();
        eye.setColor(0xFF66CCFF);
        eye.setGlow(0xFF66CCFF, 6f);
        c.drawRect(cx - r*0.45f, cy - r*0.20f, cx - r*0.18f, cy + r*0.05f, eye);
        c.drawRect(cx + r*0.18f, cy - r*0.20f, cx + r*0.45f, cy + r*0.05f, eye);
        // grill mouth
        PluginPaint mouth = c.newPaint();
        mouth.setColor(0xFF303038);
        c.drawRoundRect(cx - r*0.40f, cy + r*0.30f, cx + r*0.40f, cy + r*0.55f, r*0.05f, mouth);
        PluginPaint bar = c.newPaint();
        bar.setColor(0xFFB6B6C0);
        for (int i = 0; i < 4; i++) {
            float bx = cx - r*0.32f + i * r*0.21f;
            c.drawRect(bx, cy + r*0.32f, bx + r*0.05f, cy + r*0.53f, bar);
        }
    }

    private void drawAlien(PluginCanvas c, float cx, float cy, float r, long timeMs) {
        // Head — green oval.
        PluginPaint head = c.newPaint();
        head.setRadialGradient(cx - r*0.2f, cy - r*0.2f, r*1.3f,
            new int[] { 0xFFB5F0A8, 0xFF4FB04A, 0xFF2A6B28 },
            new float[] { 0f, 0.55f, 1f });
        c.drawCircle(cx, cy + r*0.05f, r*0.88f, head);
        // Antennae (wobble with time).
        PluginPaint stalk = c.newPaint();
        stalk.setColor(0xFF2A6B28);
        stalk.setStyle(PluginStyle.STROKE);
        stalk.setStrokeWidth(3f);
        float wob = (float) Math.sin(timeMs * 0.004) * r * 0.10f;
        c.drawLine(cx - r*0.35f, cy - r*0.80f, cx - r*0.30f + wob, cy - r*1.15f, stalk);
        c.drawLine(cx + r*0.35f, cy - r*0.80f, cx + r*0.30f - wob, cy - r*1.15f, stalk);
        PluginPaint bulb = c.newPaint();
        bulb.setColor(0xFFEDFF66);
        bulb.setGlow(0xFFEDFF66, 8f);
        c.drawCircle(cx - r*0.30f + wob, cy - r*1.15f, r*0.10f, bulb);
        c.drawCircle(cx + r*0.30f - wob, cy - r*1.15f, r*0.10f, bulb);
        // Big black eyes.
        PluginPaint eye = c.newPaint();
        eye.setColor(0xFF101018);
        c.drawCircle(cx - r*0.25f, cy - r*0.05f, r*0.20f, eye);
        c.drawCircle(cx + r*0.25f, cy - r*0.05f, r*0.20f, eye);
        PluginPaint gleam = c.newPaint();
        gleam.setColor(0xFFFFFFFF);
        c.drawCircle(cx - r*0.18f, cy - r*0.12f, r*0.05f, gleam);
        c.drawCircle(cx + r*0.32f, cy - r*0.12f, r*0.05f, gleam);
    }

    private void drawPrincess(PluginCanvas c, float cx, float cy, float r) {
        // Face.
        PluginPaint face = c.newPaint();
        face.setRadialGradient(cx - r*0.2f, cy - r*0.2f, r*1.3f,
            new int[] { 0xFFFFE6D7, 0xFFFFC7A5 },
            new float[] { 0f, 1f });
        c.drawCircle(cx, cy + r*0.10f, r*0.78f, face);
        // Crown (pink triangle with three points).
        PluginPath crown = c.newPath();
        crown.moveTo(cx - r*0.55f, cy - r*0.55f);
        crown.lineTo(cx - r*0.35f, cy - r*0.90f);
        crown.lineTo(cx - r*0.15f, cy - r*0.55f);
        crown.lineTo(cx,           cy - r*0.95f);
        crown.lineTo(cx + r*0.15f, cy - r*0.55f);
        crown.lineTo(cx + r*0.35f, cy - r*0.90f);
        crown.lineTo(cx + r*0.55f, cy - r*0.55f);
        crown.close();
        PluginPaint crownP = c.newPaint();
        crownP.setColor(0xFFFFD66B);
        crownP.setGlow(0xFFFFD66B, 8f);
        c.drawPath(crown, crownP);
        // Crown gems.
        PluginPaint gem = c.newPaint();
        gem.setColor(0xFFE25686);
        c.drawCircle(cx,           cy - r*0.66f, r*0.06f, gem);
        gem.setColor(0xFF66CCFF);
        c.drawCircle(cx - r*0.35f, cy - r*0.66f, r*0.05f, gem);
        c.drawCircle(cx + r*0.35f, cy - r*0.66f, r*0.05f, gem);
        // Eyes (closed lashes for cuteness).
        PluginPaint eye = c.newPaint();
        eye.setColor(0xFF402030);
        c.drawCircle(cx - r*0.22f, cy + r*0.02f, r*0.07f, eye);
        c.drawCircle(cx + r*0.22f, cy + r*0.02f, r*0.07f, eye);
        // Mouth.
        PluginPaint mouth = c.newPaint();
        mouth.setColor(0xFFE05078);
        c.drawCircle(cx, cy + r*0.35f, r*0.10f, mouth);
        // Cheek blush.
        PluginPaint blush = c.newPaint();
        blush.setColor(0x66FF7799);
        c.drawCircle(cx - r*0.40f, cy + r*0.22f, r*0.10f, blush);
        c.drawCircle(cx + r*0.40f, cy + r*0.22f, r*0.10f, blush);
    }

    private void drawSmurf(PluginCanvas c, float cx, float cy, float r) {
        // Round blue face.
        PluginPaint face = c.newPaint();
        face.setRadialGradient(cx - r*0.2f, cy - r*0.2f, r*1.3f,
            new int[] { 0xFF99CCFF, 0xFF3A88E0, 0xFF1F5BA8 },
            new float[] { 0f, 0.6f, 1f });
        c.drawCircle(cx, cy + r*0.05f, r*0.85f, face);
        // White hat (floppy point).
        PluginPath hat = c.newPath();
        hat.moveTo(cx - r*0.62f, cy - r*0.40f);
        hat.lineTo(cx + r*0.62f, cy - r*0.40f);
        hat.quadTo(cx + r*0.30f, cy - r*1.10f, cx + r*0.10f, cy - r*0.90f);
        hat.quadTo(cx - r*0.15f, cy - r*0.65f, cx - r*0.62f, cy - r*0.40f);
        hat.close();
        PluginPaint hatP = c.newPaint();
        hatP.setColor(0xFFFFFFFF);
        c.drawPath(hat, hatP);
        // Eyes.
        PluginPaint eye = c.newPaint();
        eye.setColor(0xFF101018);
        c.drawCircle(cx - r*0.22f, cy + r*0.00f, r*0.08f, eye);
        c.drawCircle(cx + r*0.22f, cy + r*0.00f, r*0.08f, eye);
        // Big smile.
        PluginPaint smile = c.newPaint();
        smile.setColor(0xFF101018);
        smile.setStyle(PluginStyle.STROKE);
        smile.setStrokeWidth(3f);
        PluginPath sm = c.newPath();
        sm.moveTo(cx - r*0.30f, cy + r*0.25f);
        sm.quadTo(cx, cy + r*0.55f, cx + r*0.30f, cy + r*0.25f);
        c.drawPath(sm, smile);
    }

    private void drawMonster(PluginCanvas c, float cx, float cy, float r) {
        // Red blob with bumpy outline.
        PluginPath body = c.newPath();
        int N = 14;
        for (int i = 0; i < N; i++) {
            double a = i * 2.0 * Math.PI / N;
            float bump = (i % 2 == 0) ? 1.05f : 0.92f;
            float bx = cx + (float)(Math.cos(a) * r * bump);
            float by = cy + (float)(Math.sin(a) * r * bump);
            if (i == 0) body.moveTo(bx, by); else body.lineTo(bx, by);
        }
        body.close();
        PluginPaint bodyP = c.newPaint();
        bodyP.setRadialGradient(cx - r*0.3f, cy - r*0.3f, r*1.5f,
            new int[] { 0xFFFF9966, 0xFFD13E3E, 0xFF7A1B1B },
            new float[] { 0f, 0.6f, 1f });
        c.drawPath(body, bodyP);
        // Single big angry eye.
        PluginPaint eyeW = c.newPaint();
        eyeW.setColor(0xFFFAFAFA);
        c.drawCircle(cx, cy - r*0.15f, r*0.32f, eyeW);
        PluginPaint pupil = c.newPaint();
        pupil.setColor(0xFFEDEC55);
        c.drawCircle(cx, cy - r*0.10f, r*0.18f, pupil);
        PluginPaint sl = c.newPaint();
        sl.setColor(0xFF101018);
        c.drawCircle(cx, cy - r*0.10f, r*0.08f, sl);
        // Fangs.
        PluginPath fangs = c.newPath();
        fangs.moveTo(cx - r*0.30f, cy + r*0.30f);
        fangs.lineTo(cx - r*0.20f, cy + r*0.55f);
        fangs.lineTo(cx - r*0.10f, cy + r*0.30f);
        fangs.moveTo(cx + r*0.10f, cy + r*0.30f);
        fangs.lineTo(cx + r*0.20f, cy + r*0.55f);
        fangs.lineTo(cx + r*0.30f, cy + r*0.30f);
        PluginPaint fP = c.newPaint();
        fP.setColor(0xFFFFFFFF);
        c.drawPath(fangs, fP);
    }

    private void drawPirate(PluginCanvas c, float cx, float cy, float r) {
        // Face.
        PluginPaint face = c.newPaint();
        face.setRadialGradient(cx - r*0.2f, cy - r*0.2f, r*1.3f,
            new int[] { 0xFFF4D9B3, 0xFFD9A86C },
            new float[] { 0f, 1f });
        c.drawCircle(cx, cy + r*0.05f, r*0.82f, face);
        // Bandana.
        PluginPath ban = c.newPath();
        ban.moveTo(cx - r*0.82f, cy - r*0.30f);
        ban.lineTo(cx + r*0.82f, cy - r*0.30f);
        ban.lineTo(cx + r*0.85f, cy - r*0.55f);
        ban.lineTo(cx - r*0.85f, cy - r*0.55f);
        ban.close();
        PluginPaint banP = c.newPaint();
        banP.setColor(0xFFE25656);
        c.drawPath(ban, banP);
        // Bandana dots.
        PluginPaint dot = c.newPaint();
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(cx - r*0.40f, cy - r*0.42f, r*0.04f, dot);
        c.drawCircle(cx + r*0.10f, cy - r*0.45f, r*0.04f, dot);
        c.drawCircle(cx + r*0.45f, cy - r*0.40f, r*0.04f, dot);
        // Eye patch (left).
        PluginPaint patch = c.newPaint();
        patch.setColor(0xFF101018);
        c.drawCircle(cx - r*0.25f, cy - r*0.05f, r*0.18f, patch);
        // Strap.
        PluginPaint strap = c.newPaint();
        strap.setColor(0xFF101018);
        strap.setStyle(PluginStyle.STROKE);
        strap.setStrokeWidth(3f);
        c.drawLine(cx - r*0.85f, cy - r*0.20f, cx - r*0.05f, cy + r*0.15f, strap);
        // Right eye.
        PluginPaint eye = c.newPaint();
        eye.setColor(0xFF101018);
        c.drawCircle(cx + r*0.25f, cy - r*0.05f, r*0.08f, eye);
        // Mustache / beard hint.
        PluginPaint beard = c.newPaint();
        beard.setColor(0xFF555555);
        c.drawRoundRect(cx - r*0.45f, cy + r*0.25f, cx + r*0.45f, cy + r*0.55f, r*0.10f, beard);
        // Mouth grin.
        PluginPaint mouth = c.newPaint();
        mouth.setColor(0xFFFAFAFA);
        mouth.setStyle(PluginStyle.STROKE);
        mouth.setStrokeWidth(2f);
        PluginPath grin = c.newPath();
        grin.moveTo(cx - r*0.20f, cy + r*0.35f);
        grin.quadTo(cx, cy + r*0.50f, cx + r*0.20f, cy + r*0.35f);
        c.drawPath(grin, mouth);
    }
}
