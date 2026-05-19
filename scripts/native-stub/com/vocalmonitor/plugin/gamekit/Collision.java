package com.vocalmonitor.plugin.gamekit;

/**
 * Axis-aligned bounding-box and circle collision tests.  Stateless,
 * allocation-free — pure float math.  Suitable for the modest
 * O(actors × obstacles) loops mini-games run.
 */
public final class Collision {
    private Collision() {}

    /** Two AABBs overlap? */
    public static boolean rectVsRect(
        float ax0, float ay0, float ax1, float ay1,
        float bx0, float by0, float bx1, float by1
    ) {
        return ax1 > bx0 && ax0 < bx1 && ay1 > by0 && ay0 < by1;
    }

    /** A circle (centre + radius) overlaps an AABB? */
    public static boolean circleVsRect(
        float cx, float cy, float r,
        float x0, float y0, float x1, float y1
    ) {
        float nx = clamp(cx, x0, x1);
        float ny = clamp(cy, y0, y1);
        float dx = cx - nx, dy = cy - ny;
        return dx * dx + dy * dy <= r * r;
    }

    /** Two circles overlap? */
    public static boolean circleVsCircle(
        float ax, float ay, float ar,
        float bx, float by, float br
    ) {
        float dx = ax - bx, dy = ay - by;
        float rSum = ar + br;
        return dx * dx + dy * dy <= rSum * rSum;
    }

    /** Point inside an AABB? */
    public static boolean pointInRect(
        float px, float py, float x0, float y0, float x1, float y1
    ) {
        return px >= x0 && px <= x1 && py >= y0 && py <= y1;
    }

    /** Point inside a circle? */
    public static boolean pointInCircle(float px, float py, float cx, float cy, float r) {
        float dx = px - cx, dy = py - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
