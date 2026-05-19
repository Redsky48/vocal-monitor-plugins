package com.vocalmonitor.plugin.gamekit;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginPaint;

/**
 * Allocation-free particle system — single struct-of-arrays pool
 * sized at construction.  Spawning when the pool is full overwrites
 * the slot that's about to expire next, so a long-running game
 * never leaks and never allocates.
 *
 * Each particle has: position, velocity, lifetime, colour, radius.
 * Gravity is a per-system constant (set via {@link #gravity}).
 *
 *   particles.burst(x, y, 18, Palette.SPARKLE);     // feathers / dust
 *   particles.trail(x, y, Palette.ACCENT_YELLOW);   // ghost trail
 *   particles.update(dt);
 *   particles.draw(canvas);
 */
public final class Particles {

    private final int capacity;
    private final float[] x, y, vx, vy, life, life0, radius;
    private final int[] color;
    private int writeCursor = 0;

    private float gravity = 600f;
    private long rng = 0xBEEFCAFEL;

    public Particles(int capacity) {
        this.capacity = capacity;
        x = new float[capacity];
        y = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        life = new float[capacity];
        life0 = new float[capacity];
        radius = new float[capacity];
        color = new int[capacity];
    }

    /** World-space px/s² gravity applied to vy.  Default 600 — set to
     *  0 for floaty embers, higher for heavy debris. */
    public Particles gravity(float g) { this.gravity = g; return this; }

    /** Spawn `count` particles at (x, y) with random outward velocities
     *  and one colour.  Use for hit-impacts / explosions / feathers. */
    public void burst(float x0, float y0, int count, int color) {
        for (int i = 0; i < count; i++) {
            int slot = slotForSpawn();
            double angle = rand() * Math.PI * 2.0;
            float speed = 100f + rand() * 220f;
            x[slot]  = x0;
            y[slot]  = y0;
            vx[slot] = (float) (Math.cos(angle) * speed);
            vy[slot] = (float) (Math.sin(angle) * speed);
            life0[slot] = life[slot] = 0.5f + rand() * 0.5f;
            radius[slot] = 2.5f + rand() * 2.5f;
            this.color[slot] = color;
        }
    }

    /** Spawn one trail particle — slow upward drift, short lifetime.
     *  Use for ghost trails on a moving sprite. */
    public void trail(float x0, float y0, int color) {
        int slot = slotForSpawn();
        x[slot]  = x0;
        y[slot]  = y0;
        vx[slot] = (rand() - 0.5f) * 30f;
        vy[slot] = -10f - rand() * 30f;
        life0[slot] = life[slot] = 0.35f + rand() * 0.15f;
        radius[slot] = 1.8f + rand() * 1.8f;
        this.color[slot] = color;
    }

    /** Custom spawn — full control over velocity / lifetime / size.
     *  Use this when burst() / trail() defaults don't fit. */
    public void spawn(float x0, float y0, float velX, float velY,
                      float lifeS, float r, int color) {
        int slot = slotForSpawn();
        x[slot]  = x0; y[slot]  = y0;
        vx[slot] = velX; vy[slot] = velY;
        life0[slot] = life[slot] = lifeS;
        radius[slot] = r;
        this.color[slot] = color;
    }

    /** Integrate physics + age particles.  Call once per render frame. */
    public void update(float dt) {
        for (int i = 0; i < capacity; i++) {
            if (life[i] <= 0f) continue;
            vy[i] += gravity * dt;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            life[i] -= dt;
        }
    }

    /** Draw every live particle as a filled circle, alpha-faded by
     *  remaining lifetime fraction. */
    public void draw(PluginCanvas canvas) {
        PluginPaint p = canvas.newPaint();
        for (int i = 0; i < capacity; i++) {
            if (life[i] <= 0f) continue;
            float frac = life[i] / Math.max(life0[i], 0.001f);
            if (frac < 0f) frac = 0f; if (frac > 1f) frac = 1f;
            p.setColor(Palette.withAlpha(color[i], frac));
            canvas.drawCircle(x[i], y[i], radius[i], p);
        }
    }

    public void reset() {
        for (int i = 0; i < capacity; i++) life[i] = 0f;
        writeCursor = 0;
    }

    public int capacity() { return capacity; }

    /** Pick a free slot — or, if none, the oldest live one.  Round-
     *  robin cursor keeps the "oldest" probe O(1) amortised. */
    private int slotForSpawn() {
        // First pass: any expired slot from the round-robin cursor.
        for (int i = 0; i < capacity; i++) {
            int idx = (writeCursor + i) % capacity;
            if (life[idx] <= 0f) { writeCursor = (idx + 1) % capacity; return idx; }
        }
        // Pool saturated — overwrite the next cursor position.
        int idx = writeCursor;
        writeCursor = (writeCursor + 1) % capacity;
        return idx;
    }

    private float rand() {
        rng ^= rng << 13;
        rng ^= rng >>> 7;
        rng ^= rng << 17;
        return (rng & 0x7FFFFFFF) / (float) Integer.MAX_VALUE;
    }
}
