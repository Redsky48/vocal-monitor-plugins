# Plugin UI API

This document describes how a Vocal Monitor plugin ships its own UI panel.
Two modes are supported:

| Mode      | Plugin ships               | Renderer        | Best for |
|-----------|----------------------------|-----------------|----------|
| **`spec`**   | A small JSON `ui` block      | Native widgets  | Knobs, sliders, meters — the 95% case |
| **`canvas`** | A `render()` Java/Kotlin method | Skia / Compose | Glow, blur, gradients, oscilloscopes, fully custom looks |

Pick `spec` whenever possible. It's faster, more consistent, and the
declarative JSON survives unchanged when the same plugin is loaded into a
PC DAW that uses a different native renderer.

Pick `canvas` when you need raw drawing primitives — gradients, glow,
blend modes, real-time particle systems, hand-drawn dB curves, etc.

You pick the mode with one field in `plugin.json`:

```jsonc
{
  "id": "my-effect",
  "engine": "native",
  "className": "com.example.MyEffect",
  "ui_kind": "spec"      // or "canvas" — defaults to "spec" if omitted
}
```

---

## Mode 1 — `spec`: declarative widgets

You describe the panel as a JSON block. The host renders it with native
widgets that already exist for the built-in effects (knobs that match the
Equalizer's, meters that match the Compressor's gain-reduction display,
graph cells that match the Reverb impulse view). No drawing code from your
side.

### Schema

```jsonc
{
  "id":      "my-effect",
  "engine":  "native",
  "className": "com.example.MyEffect",
  "ui_kind": "spec",
  "ui": {
    "rows": [
      // Each row is one of the widget objects below.
    ]
  },
  // Optional. Lists the named per-block data streams the host should
  // compute and hand back to your UI on each render tick. Each stream
  // appears as an entry in `streams` arg of widgets that bind to it.
  // Defaults to no streams; declare only what you read.
  "streams": ["peak", "rms", "gainReduction"]
}
```

### Widget reference

Every widget object has a `type`. Optional keys are noted.

**`knob`** — circular control bound to one parameter.
```jsonc
{ "type": "knob", "param": "threshold", "label": "Threshold", "unit": "dB" }
```

**`slider`** — horizontal control bound to one parameter.
```jsonc
{ "type": "slider", "param": "mix", "label": "Mix", "unit": "%" }
```

**`button`** — momentary push or latching toggle bound to a 0/1 param.
```jsonc
{ "type": "button", "param": "freeze", "label": "Freeze", "latching": true }
```

**`meter`** — single-stream level display. `stream` names a value from
the plugin's `streams` declaration.
```jsonc
{ "type": "meter", "stream": "peak", "label": "Peak", "rangeDb": [-60, 0] }
```

**`graph`** — built-in named curve renderer. Currently supported `kind`:
- `compressionCurve` — input-dB → output-dB curve derived from
  `threshold`, `ratio`, `knee`. Plugin doesn't supply the math; host
  computes from param values.
- `eqCurve` — magnitude response derived from a set of band gains.
- `fft` — instantaneous FFT magnitudes (declare `fft` in `streams`).
- `waveform` — last 2048 samples (declare `waveform` in `streams`).
```jsonc
{ "type": "graph", "kind": "compressionCurve",
  "axes": { "x": "threshold", "y": "ratio", "knee": "knee" } }
```

**`group`** — visual grouping for related controls.
```jsonc
{ "type": "group", "label": "REFLECTIONS", "children": [ /* widgets */ ] }
```

**`columns`** — split a row into two or more side-by-side columns.
```jsonc
{ "type": "columns", "weights": [1, 1],
  "children": [ /* left column children */,
                /* right column children */ ] }
```

### Worked example — `noise-gate`

```jsonc
{
  "id":      "noise-gate",
  "ui_kind": "spec",
  "ui": {
    "rows": [
      { "type": "knob",  "param": "threshold",   "label": "Threshold", "unit": "dB" },
      { "type": "knob",  "param": "hysteresis",  "label": "Hysteresis", "unit": "dB" },
      { "type": "columns", "weights": [1, 1], "children": [
          { "type": "slider", "param": "attack",  "label": "Attack",  "unit": "ms" },
          { "type": "slider", "param": "release", "label": "Release", "unit": "ms" }
      ]},
      { "type": "meter", "stream": "gainReduction", "label": "GR", "rangeDb": [-30, 0] }
    ]
  },
  "streams": ["gainReduction"]
}
```

---

## Mode 2 — `canvas`: custom drawing

Your plugin class additionally implements
[`VocalMonitorVisualPlugin`](https://github.com/Redsky48/vocal-monitor-slim/blob/main/app/src/main/java/com/vocalmonitor/plugin/VocalMonitorVisualPlugin.java):

```java
public interface VocalMonitorVisualPlugin extends VocalMonitorNativePlugin {
    /**
     * Called by the host once per UI frame (typically 60 Hz).
     *
     * @param canvas  abstract canvas — calls into native Skia (Android)
     *                or the DAW's own renderer.
     * @param width   panel width in dp-equivalent units (logical pixels).
     * @param height  panel height in the same units.
     * @param timeMs  monotonic milliseconds since the panel opened — use
     *                this for animations instead of System.currentTimeMillis().
     * @param params  current values of every declared parameter.
     * @param streams data streams the plugin requested in its manifest
     *                (`gainReduction`, `peak`, `fft`, etc.). Each value
     *                is a `float[]` — a 1-length array for scalar streams.
     */
    void render(
        PluginCanvas canvas,
        int width, int height,
        long timeMs,
        java.util.Map<String, Float> params,
        java.util.Map<String, float[]> streams
    );
}
```

The host calls your `render()` from the UI thread inside a Compose
`Canvas { ... }` block on Android, and inside the equivalent native
draw call on the PC DAW. **Your code touches `PluginCanvas` only** — the
adapter the host installs translates each call into the native draw
primitive of whatever platform you're on.

### `PluginCanvas` reference

```java
public interface PluginCanvas {
    // Basic shapes
    void drawRect(float left, float top, float right, float bottom, PluginPaint paint);
    void drawRoundRect(float left, float top, float right, float bottom,
                       float radius, PluginPaint paint);
    void drawCircle(float cx, float cy, float radius, PluginPaint paint);
    void drawLine(float x0, float y0, float x1, float y1, PluginPaint paint);
    void drawPath(PluginPath path, PluginPaint paint);
    void drawText(String text, float x, float y, PluginPaint paint);

    // Transform stack
    void save();
    void restore();
    void translate(float dx, float dy);
    void scale(float sx, float sy);
    void rotate(float degrees);

    // Clipping
    void clipRect(float left, float top, float right, float bottom);

    // Path construction
    PluginPath newPath();

    // Paint construction
    PluginPaint newPaint();
}
```

### `PluginPaint` reference

```java
public interface PluginPaint {
    PluginPaint setColor(int argb);                              // 0xAARRGGBB
    PluginPaint setStrokeWidth(float dp);
    PluginPaint setStyle(PluginStyle style);                     // FILL / STROKE / FILL_AND_STROKE
    PluginPaint setAntialias(boolean on);                        // default: true

    // Gradients
    PluginPaint setLinearGradient(float x0, float y0, float x1, float y1,
                                  int[] colors, float[] stops);
    PluginPaint setRadialGradient(float cx, float cy, float radius,
                                  int[] colors, float[] stops);
    PluginPaint clearShader();

    // Effects — the glow you came here for
    PluginPaint setGlow(int color, float radiusDp);              // soft outer glow
    PluginPaint setShadow(float dx, float dy, float radiusDp, int color);
    PluginPaint setBlendMode(BlendMode mode);                    // SRC_OVER, ADD, SCREEN, MULTIPLY, etc.

    // Text
    PluginPaint setTextSize(float dp);
    PluginPaint setTextAlign(int alignment);                     // 0=left 1=center 2=right
}
```

### `PluginPath` reference

```java
public interface PluginPath {
    PluginPath moveTo(float x, float y);
    PluginPath lineTo(float x, float y);
    PluginPath quadTo(float cx, float cy, float x, float y);
    PluginPath cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y);
    PluginPath close();
    PluginPath reset();
}
```

### `BlendMode` enum

`SRC_OVER` (default) · `ADD` · `SCREEN` · `MULTIPLY` · `OVERLAY` ·
`DARKEN` · `LIGHTEN` · `COLOR_DODGE` · `COLOR_BURN`.

### Worked example — pulsing glow tied to peak

```java
public final class GlowingMeter implements VocalMonitorVisualPlugin {
    // ... audio interface impl ...

    @Override
    public void render(PluginCanvas canvas,
                       int width, int height,
                       long timeMs,
                       Map<String, Float> params,
                       Map<String, float[]> streams) {
        float peakDb = streams.get("peak")[0];
        float intensity = Math.max(0f, Math.min(1f, (peakDb + 60f) / 60f));

        // Background
        PluginPaint bg = canvas.newPaint().setColor(0xFF050505);
        canvas.drawRect(0, 0, width, height, bg);

        // Outer glow ring
        PluginPaint glow = canvas.newPaint()
            .setColor(blendRedToYellow(intensity))
            .setStyle(PluginStyle.STROKE)
            .setStrokeWidth(4f)
            .setGlow(blendRedToYellow(intensity), 18f * intensity)
            .setBlendMode(BlendMode.ADD);

        float radius = (height * 0.4f) * (0.85f + intensity * 0.15f);
        canvas.drawCircle(width / 2f, height / 2f, radius, glow);
    }

    private static int blendRedToYellow(float t) {
        int r = 0xFF;
        int g = (int) (0xFF * t);
        return 0xFF000000 | (r << 16) | (g << 8);
    }
}
```

Plugin manifest:
```jsonc
{ "id": "glowing-meter", "ui_kind": "canvas",
  "engine": "native", "className": "com.example.GlowingMeter",
  "streams": ["peak"] }
```

---

## Panel sizing (mobile-friendly)

Phone screens are narrow. A 16:9 envelope plot that reads fine in a desktop
DAW becomes ~80 dp tall at typical mobile widths, which is too short for a
dB-axis with grid lines to register as anything but noise. To avoid that
without hard-coding sizes per platform, the host lets you declare what
shape your canvas wants:

```jsonc
{
  "id": "noise-gate",
  "ui_kind": "canvas",
  "ui": {
    "aspect": "5:3",         // optional, "W:H" string or float
    "min_height_dp": 240     // optional, hard floor in dp
  }
}
```

| Field           | Meaning                                                                                                                                  |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `aspect`        | Width/height ratio. `"5:3"`, `"16:9"`, `"1:1"`, or a raw number. Host derives panel height from the available width using this.          |
| `min_height_dp` | Don't go below this height in dp, regardless of aspect. Use this when your plot has labels or axis ticks that don't read below a certain size. |

The host takes `max(min_height_dp, width / aspect, 220 dp)` and caps at
320 dp. **220 dp is the mobile floor** — anything below collapses
typical dB-axis renderings. **320 dp is the cap** — you can't take over
the screen, the panel sits in a scrollable card.

**Best-practice rules for `render()`:**

1. **Derive all coordinates from `width` and `height`.** Both come in as
   dp-equivalent units; the host's adapter handles dp → device-pixel
   scaling for every draw call. So your code is screen-density-agnostic
   already — but it must not assume a specific *size*. A fixed
   `pad = 14f` is fine; a fixed `plotY1 = height - 100` is not.
2. **Scale text and stroke widths relative to `height`** when the panel
   can be tall or short. A 13 dp title is right at 240 dp; the same
   13 dp looks lost at 320 dp. `textSize = height * 0.06f` reads
   consistently across both.
3. **Guard the trivial case.** If `width < 40 || height < 40` (the
   host briefly hits these during gesture animations), draw nothing
   and return — anything you draw at that size is invisible anyway
   and just burns frame budget.
4. **Don't allocate per-frame.** Cache `PluginPaint` / `PluginPath`
   in `init()` and reuse. Allocations show up as jank on weaker
   phones first.

---

## Custom controls (hide the auto-sliders)

By default the host renders a slider per parameter underneath your
canvas panel. That's the right move when your canvas only *displays*
state — a spectrum, a waveform, a gain-reduction history. But when the
canvas *is* the UI (you're drawing knobs the user grabs and drags), the
auto-sliders are duplicated controls in the user's face.

Two coordinated changes opt out of them:

**1. Tell the host you own the controls.** In your manifest:

```jsonc
{
  "ui_kind": "canvas",
  "ui": {
    "controls": "canvas"     // default: "host" — host shows sliders below
  }
}
```

When `controls: "canvas"`, the host suppresses the auto-slider stack
entirely. Your canvas is the only UI for that plugin.

**2. Implement touch + parameter pass-back.** The plugin gets the
following hooks (all `default void` no-ops, override only what you
need):

```java
public interface VocalMonitorVisualPlugin extends VocalMonitorNativePlugin {
    default void setHost(PluginHost host) { /* opt-in */ }

    default void onTouchDown(float x, float y) { /* opt-in */ }
    default void onTouchMove(float x, float y) { /* opt-in */ }
    default void onTouchUp(float x, float y)   { /* opt-in */ }

    void render(...);
}
```

Coordinates are in the same dp-equivalent units `render()` draws in —
origin at the panel's top-left. The host invokes `setHost` once before
the first `render()` so you can stash the callback:

```java
public interface PluginHost {
    void setParameter(String name, float value);
}
```

### Worked example — a draggable gain knob

```java
public final class MyKnob implements VocalMonitorVisualPlugin {
    private PluginHost host;
    private float gain = 0.7f;           // [0..1]
    private boolean dragging = false;
    private float dragStartY;
    private float gainAtDragStart;

    @Override public void setHost(PluginHost host) { this.host = host; }

    @Override public void onTouchDown(float x, float y) {
        // Hit-test the knob you drew at (cx, cy, radius)
        if (insideKnob(x, y)) {
            dragging = true;
            dragStartY = y;
            gainAtDragStart = gain;
        }
    }

    @Override public void onTouchMove(float x, float y) {
        if (!dragging) return;
        // Vertical drag of 100 dp = full sweep — feels right on phones
        float delta = (dragStartY - y) / 100f;
        gain = clamp01(gainAtDragStart + delta);
        // Pushes value into the host's state. Audio engine sees the
        // change on the next process() block; host bundles all
        // pre-touchUp updates into one undo entry.
        host.setParameter("gain", gain);
    }

    @Override public void onTouchUp(float x, float y) {
        dragging = false;
    }

    @Override public void render(PluginCanvas canvas, int w, int h, ...) {
        // ... draw the knob using `gain` to set its rotation ...
    }
}
```

**Notes:**
- `host.setParameter` is cheap to call — spam it every move event.
  The host does its own commit-on-touch-up bundling, so each gesture
  becomes one undo entry.
- The plugin still receives `params` in every `render()` call. When
  the user loads a preset or undo restores a state, `params.get("gain")`
  is the source of truth; you'll need to sync your internal `gain`
  field to it at the top of `render()` if you've drifted.
- Tap-on-canvas (no movement between down and up) won't get
  consumed by the host, so a tap that turns out to be a list scroll
  still works.

---

## Streams

A "stream" is a named per-frame data value the host computes from the
audio pipeline and hands to your panel. Declared streams are made
available to both `spec` widgets (via `stream` references) and `canvas`
plugins (via the `streams` arg in `render()`).

| Stream            | Shape         | Meaning |
|-------------------|---------------|---------|
| `peak`            | `float[1]`    | Current block peak in dBFS |
| `rms`             | `float[1]`    | Current block RMS in dBFS |
| `gainReduction`   | `float[1]`    | Plugin's reported dB of gain reduction this block (provide via `setGainReduction()` from your `process()` for the host to read) |
| `fft`             | `float[256]`  | Magnitude spectrum, log-mapped 20 Hz – 20 kHz |
| `waveform`        | `float[2048]` | Most recent samples — newest at the end |

Computing a stream costs CPU. Only declare what you use.

If you need a custom stream the host doesn't provide (e.g. a per-band
modulation envelope your plugin internally tracks), expose it via a
method on your plugin class:

```java
public float[] customStream(String name) {
    if ("envelope".equals(name)) return envelopeBuffer;
    return null;
}
```

and reference it in your spec / canvas code as `streams.get("envelope")`.

---

## Frame budget and safety

- Your `render()` runs on the **UI thread**. Aim for **< 4 ms** per frame.
  Anything > 16 ms drops frames; anything > 50 ms triggers the host's
  watchdog, which replaces your panel with the fallback spec view.
- Cache `PluginPaint` and `PluginPath` instances across frames instead
  of allocating them each call. The Android adapter wraps Skia objects
  — fresh allocations make the GC visible.
- Do **not** allocate `float[]` / `int[]` / `String` arrays inside
  `render()`. Allocate them in `init()` and re-fill in `setParameter()`
  if needed.
- No file I/O, no network, no reflection — same restrictions as the
  audio interface.

---

## Compile recipe (additions to `BUILDING_NATIVE_PLUGINS.md`)

If your plugin implements only the audio interface, the existing five-step
recipe is unchanged. To add a custom panel:

1. **Implement either or both.** Just `VocalMonitorVisualPlugin` if you
   want a `canvas` mode; just a `ui` block in `plugin.json` if you want
   `spec` mode; both if you want a canvas panel that falls back to a
   spec panel when the host's watchdog disables canvas rendering.

2. **Reference the new contract jars in your `javac` command.** Same
   stub directory you already use for `VocalMonitorNativePlugin` — it
   now also contains `VocalMonitorVisualPlugin.class`, `PluginCanvas.class`,
   `PluginPaint.class`, `PluginPath.class`, `PluginStyle.class`,
   `BlendMode.class`. No changes needed if you re-pull the stub set.

3. **No new dex flags.** The host detects whether your class implements
   `VocalMonitorVisualPlugin` via `instanceof` at install time.

---

## DAW portability

The `PluginCanvas` interface is *abstract*: your plugin compiles against
the contract, never against `android.graphics.Canvas` or any platform
type. The host on each platform installs an adapter:

- **Android app:** adapter wraps Compose `DrawScope` → Skia.
- **PC DAW (planned):** adapter wraps the DAW's render context — also
  Skia if the DAW uses Compose Multiplatform, otherwise Cairo / Direct2D
  / JavaFX / etc.

The same `.dex` file runs on both. No recompile, no platform `#ifdef`s.
Just the host's adapter changes underneath.

If you find yourself wanting a primitive the API doesn't expose
(e.g. a custom shader), please open an issue in
[Redsky48/vocal-monitor-plugins](https://github.com/Redsky48/vocal-monitor-plugins/issues).
We'd rather grow the abstract API than introduce platform escape hatches.
