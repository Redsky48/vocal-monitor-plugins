package com.vocalmonitor.plugin.gamekit;

/**
 * Easing curves — pass `t` in [0, 1], get a non-linear position in
 * [0, 1] back.  Standard Penner-style set; named by where the slow
 * vs fast part of the motion lives:
 *
 *   in*       — accelerates from rest (slow start, fast end)
 *   out*      — decelerates to rest (fast start, slow end)
 *   inOut*    — symmetric S-curve
 *
 * Use these to drive animations / UI pops / squash-stretch without
 * thinking about cubic-bezier coefficients.  Stateless — safe to
 * call from any thread.
 *
 * Example:
 *   float t = (timeMs - startMs) / DURATION_MS;
 *   if (t > 1f) t = 1f;
 *   float popY = startY + (endY - startY) * Ease.outBack(t);
 */
public final class Ease {
    private Ease() {}

    /** Identity — pass-through. */
    public static float linear(float t) { return t; }

    // ── Quadratic ────────────────────────────────────────────
    public static float inQuad(float t)    { return t * t; }
    public static float outQuad(float t)   { return 1f - (1f - t) * (1f - t); }
    public static float inOutQuad(float t) { return t < 0.5f ? 2f * t * t : 1f - sq(-2f * t + 2f) / 2f; }

    // ── Cubic ────────────────────────────────────────────────
    public static float inCubic(float t)    { return t * t * t; }
    public static float outCubic(float t)   { float u = 1f - t; return 1f - u * u * u; }
    public static float inOutCubic(float t) { return t < 0.5f ? 4f * t * t * t : 1f - cube(-2f * t + 2f) / 2f; }

    // ── Sine ─────────────────────────────────────────────────
    public static float inSine(float t)    { return 1f - (float) Math.cos((t * Math.PI) / 2.0); }
    public static float outSine(float t)   { return (float) Math.sin((t * Math.PI) / 2.0); }
    public static float inOutSine(float t) { return -(float)(Math.cos(Math.PI * t) - 1.0) / 2f; }

    // ── Back (overshoots target then settles) ───────────────
    public static float outBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
    public static float inBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return c3 * t * t * t - c1 * t * t;
    }

    // ── Bounce (multiple settle bumps) ──────────────────────
    public static float outBounce(float t) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (t < 1f / d1)        return n1 * t * t;
        else if (t < 2f / d1) { t -= 1.5f / d1; return n1 * t * t + 0.75f; }
        else if (t < 2.5f / d1) { t -= 2.25f / d1; return n1 * t * t + 0.9375f; }
        else                   { t -= 2.625f / d1; return n1 * t * t + 0.984375f; }
    }
    public static float inBounce(float t)    { return 1f - outBounce(1f - t); }
    public static float inOutBounce(float t) {
        return t < 0.5f ? (1f - outBounce(1f - 2f * t)) / 2f
                        : (1f + outBounce(2f * t - 1f)) / 2f;
    }

    // ── Elastic (springy overshoot — use sparingly) ─────────
    public static float outElastic(float t) {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        float c4 = (float) ((2 * Math.PI) / 3.0);
        return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1.0);
    }

    // ── Utility ─────────────────────────────────────────────
    /** Linear interpolation a → b by t in [0, 1]. */
    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Clamp x to [lo, hi]. */
    public static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    /** 0..1-normalised time inside [start, end].  Useful for one-shot
     *  animations: `Ease.norm(timeMs, startMs, durationMs)`. */
    public static float norm(long timeMs, long startMs, float durationMs) {
        if (durationMs <= 0f) return 1f;
        float t = (timeMs - startMs) / durationMs;
        return clamp(t, 0f, 1f);
    }

    private static float sq(float x)   { return x * x; }
    private static float cube(float x) { return x * x * x; }
}
