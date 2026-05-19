package com.vocalmonitor.plugin.gamekit;

/**
 * Curated color palette for mini-game plugins.  Provides:
 *
 *   - "warm" / "cool" / "neutral" ramps for backgrounds & UI
 *   - "ui" colors matched to the host's dark theme
 *   - "fx" accent colors for hits / scores / particles
 *   - tiny helpers for alpha / lighten / darken
 *
 * All values are ARGB ints in 0xAARRGGBB form, ready to hand
 * straight to `PluginPaint.setColor(int)`.  Stateless.
 */
public final class Palette {
    private Palette() {}

    // ── Backgrounds ──────────────────────────────────────────
    public static final int SKY_DAY_TOP   = 0xFF66BBE0;
    public static final int SKY_DAY_BOT   = 0xFFA8DDF0;
    public static final int SKY_DUSK_TOP  = 0xFF2A1B6E;
    public static final int SKY_DUSK_MID  = 0xFFEC5A8C;
    public static final int SKY_DUSK_BOT  = 0xFFFFB272;
    public static final int SKY_NIGHT_TOP = 0xFF0A0E2B;
    public static final int SKY_NIGHT_BOT = 0xFF1A1232;

    // ── UI surfaces (match host's dark theme) ───────────────
    public static final int UI_BG_DEEP    = 0xFF101418;
    public static final int UI_BG_PANEL   = 0xFF1A1A1A;
    public static final int UI_BG_CARD    = 0xFFFFFFFF;
    public static final int UI_TEXT       = 0xFFE8E8E8;
    public static final int UI_TEXT_DIM   = 0xFFAAAAAA;
    public static final int UI_TEXT_INK   = 0xFF101018;
    public static final int UI_BORDER     = 0xFF2A2A2A;

    // ── Brand / accent colors ───────────────────────────────
    public static final int ACCENT_YELLOW = 0xFFFFD66B;
    public static final int ACCENT_ORANGE = 0xFFFF8833;
    public static final int ACCENT_RED    = 0xFFE25656;
    public static final int ACCENT_GREEN  = 0xFF66DD66;
    public static final int ACCENT_BLUE   = 0xFF66CCEE;
    public static final int ACCENT_PINK   = 0xFFE25686;
    public static final int ACCENT_AMBER  = 0xFFE3B544;

    // ── Game-feel colors ────────────────────────────────────
    public static final int HIT_FLASH     = 0xFFFFFFFF;
    public static final int DEATH_FLASH   = 0xFFE25656;
    public static final int SPARKLE       = 0xFFFFF1B0;

    // ── Three-stage health/level meter colors ───────────────
    public static int meterColor(float level01) {
        if (level01 > 0.85f) return ACCENT_RED;
        if (level01 > 0.6f)  return ACCENT_AMBER;
        return ACCENT_GREEN;
    }

    // ── Alpha / brightness helpers ──────────────────────────
    /** Replace alpha channel with a 0..1 multiplier of full opacity. */
    public static int withAlpha(int argb, float a01) {
        int a = (int) (Ease.clamp(a01, 0f, 1f) * 255f);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /** Mix `a` toward `b` by `t` in [0, 1] in straight RGBA. */
    public static int mix(int a, int b, float t) {
        t = Ease.clamp(t, 0f, 1f);
        int ax = (a >> 24) & 0xFF, ay = (a >> 16) & 0xFF, az = (a >> 8) & 0xFF, aw = a & 0xFF;
        int bx = (b >> 24) & 0xFF, by = (b >> 16) & 0xFF, bz = (b >> 8) & 0xFF, bw = b & 0xFF;
        int rx = (int) (ax + (bx - ax) * t);
        int ry = (int) (ay + (by - ay) * t);
        int rz = (int) (az + (bz - az) * t);
        int rw = (int) (aw + (bw - aw) * t);
        return (rx << 24) | (ry << 16) | (rz << 8) | rw;
    }

    public static int lighten(int argb, float amt) { return mix(argb, 0xFFFFFFFF, amt); }
    public static int darken(int argb, float amt)  { return mix(argb, 0xFF000000, amt); }
}
