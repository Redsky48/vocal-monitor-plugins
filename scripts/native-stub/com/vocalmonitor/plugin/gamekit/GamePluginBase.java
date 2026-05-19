package com.vocalmonitor.plugin.gamekit;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.PluginHost;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;

import java.util.Map;

/**
 * Boilerplate-reducing base class for mini-game plugins.
 *
 * Pre-wires the bits every game plugin in this repo ends up writing:
 *   - {@link Juice}, {@link Particles}, {@link MicTrigger} instances
 *   - frame-dt + canvas-scale bookkeeping
 *   - a passthrough {@link #process} so the host's save-time render
 *     emits the dry signal (game plugins don't transform audio)
 *   - empty parameter list (override if your game has knobs)
 *
 * Subclasses just provide {@link #render} and call {@link #beginFrame}
 * at the top.  Keep render() concise — game logic in a private
 * `step()`, drawing in a private `draw()`, both invoked from render().
 *
 * Example:
 *
 *   public final class MyGame extends GamePluginBase {
 *       @Override public void render(PluginCanvas c, int w, int h, long timeMs,
 *                                    Map<String, Float> params,
 *                                    Map<String, float[]> streams) {
 *           beginFrame(w, h, timeMs, streams);
 *           if (mic.hit()) flap();
 *           step(dt, w, h);
 *           draw(c, w, h);
 *           particles.draw(c);
 *           juice.drawOverlay(c, w, h);
 *       }
 *   }
 *
 * NOTE: subclasses still need their own state machine + onTouchDown
 * + init logic.  This base owns the "every game does the same wiring
 * dance" parts and gets out of your way for the actual gameplay.
 */
public abstract class GamePluginBase implements VocalMonitorVisualPlugin {

    /** Reference-DP used to compute {@link #scale}.  ~360 dp is a
     *  typical phone width — pick visuals to look right there and
     *  every other canvas scales proportionally. */
    public static final float REF_DP = 360f;

    protected PluginHost host = null;
    protected int sampleRate = 44100;

    protected long lastMs = -1L;
    /** Seconds since the previous render frame (or 0.016 on first frame). */
    protected float dt = 0.016f;
    /** Canvas-relative scale, {@code min(width, height) / REF_DP}. */
    protected float scale = 1f;

    protected final Juice juice = new Juice();
    protected final Particles particles = new Particles(160);
    protected final MicTrigger mic = new MicTrigger();

    @Override public void setHost(PluginHost h) { this.host = h; }

    @Override
    public void init(int sr) {
        this.sampleRate = sr;
        this.lastMs = -1L;
        this.dt = 0.016f;
        this.scale = 1f;
        juice.reset();
        particles.reset();
        mic.reset();
        onInit(sr);
    }

    /** Hook for subclasses — runs after the kit's own init cleanup.
     *  Override to reset game state. */
    protected void onInit(int sr) { /* opt-in */ }

    // ── Default plugin contract ─────────────────────────────
    @Override public String[] parameterNames()      { return new String[0]; }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n)  { return n; }
    @Override public void setParameter(String n, float v) { /* opt-in */ }

    /** Default: pure passthrough.  Game plugins don't transform audio
     *  in the live monitor path (slim drives visuals via
     *  streams["waveform"] in render), and at save-time the dry
     *  signal is the most useful default. */
    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    /**
     * Call this at the top of your render() override.  Updates the
     * per-frame fields ({@link #dt}, {@link #scale}), pulls the mic,
     * advances juice / particle simulations.
     */
    protected void beginFrame(int width, int height, long timeMs, Map<String, float[]> streams) {
        dt = lastMs < 0L ? 0.016f
            : Math.min(0.10f, (timeMs - lastMs) / 1000f);
        lastMs = timeMs;
        scale = Math.min(width, height) / REF_DP;
        mic.feed(streams, dt);
        juice.update(dt);
        particles.update(dt);
    }
}
