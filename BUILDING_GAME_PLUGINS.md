# PluginGameKit — building game / interactive plugins

> **For AI agents and human contributors building mini-games on the Vocal Monitor plugin runtime.** This guide is the prompt-shaped spec — short, opinionated, copy-pasteable. The DSP-only "passthrough effect" guide is [BUILDING_NATIVE_PLUGINS.md](BUILDING_NATIVE_PLUGINS.md).

PluginGameKit is a tiny Java library that sits on top of `PluginCanvas` and the rest of the [Native plugin API](NATIVE_PLUGIN_API.md). It gives you premium-looking 2D game plugins with ~50–200 lines of game-specific code instead of 600.

## What you get

| Class | Role | Stateful? |
|---|---|---|
| `Gfx` | drawing helpers (panels, cards, gradients, text, level bars, rings) | no |
| `Ease` | easing curves (cubic, back, bounce, elastic, lerp, clamp, norm) | no |
| `Palette` | curated colour constants + alpha / mix / lighten / darken | no |
| `Collision` | AABB / circle / point tests | no |
| `MicTrigger` | chirp / clap / shout onset detector — `feed()` + `hit()` | yes |
| `Juice` | screen shake, full-screen flash, impact rings, score popups | yes |
| `Particles` | fixed-pool particle system (burst, trail, spawn, update, draw) | yes |
| `GamePluginBase` | abstract base class wiring all of the above together | yes |
| `audio.PitchTracker` | LP + zero-crossing pitch estimator (60–800 Hz, ~cents-grade) | yes |
| `audio.RmsFollower` | envelope follower with separate attack / release | yes |
| `audio.OnsetDetector` | generic spike-vs-baseline transient detector | yes |
| `audio.NoteName` | Hz ↔ "A4" string + cents-from-target conversion | no |
| `ui.HitZone` | headless tap-region with `pressed()` + `clickedThisFrame()` | yes |
| `ui.Button` | tappable rounded-rect button; `draw(…)` returns `true` on click | yes |
| `ui.Toggle` | pill on/off switch | yes |
| `ui.Slider` | horizontal value slider 0..1, drag-to-set | yes |
| `ui.Knob` | rotary knob, vertical-drag to change, 270° sweep | yes |
| `dev.Profiler` | per-section ms timer with on-screen averaged readout | yes |
| `svg.PluginShape` | parsed SVG drawable; `draw(canvas, x, y, scale)` + tint variant | yes |
| `svg.Svg` | SVG-text → `PluginShape` parser (subset: paths, rects, circles, polys, groups) | no |

Everything lives under `com.vocalmonitor.plugin.gamekit`. The stubs are at `shared/src/main/java/com/vocalmonitor/plugin/gamekit/` (canonical) and mirrored to `scripts/native-stub/com/vocalmonitor/plugin/gamekit/` for the dex build.

## Scaffolding a new plugin

The fastest way to start: `node scripts/new-plugin.mjs <category> <id> --native <template>`. Templates:

| Template | Manifest | What you get |
|---|---|---|
| `game` | fullscreen 9:16 + `streams: ["waveform"]` | `extends GamePluginBase` with score + score-pop + chirp-flap-particles scaffold |
| `analyzer` | 16:9 + `streams: ["waveform"]` | visual analyzer with `RmsFollower` + level bar |
| `effect` | no `ui_kind` | pure-audio `VocalMonitorNativePlugin` with one mix parameter |
| `pickerui` | fullscreen 16:9 + `streams: ["waveform"]` | demo with `Button` + `Toggle` + `Knob` wired to touch events |

Then `node scripts/build-native.mjs <id>` compiles the .dex, and either `tools/test-app/run.bat` or the DAW picks it up.

## Quick start — a flap-style game in ~80 lines

```java
package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.gamekit.*;
import java.util.Map;

public final class TinyFlap extends GamePluginBase {

    private float birdY = 0.4f;
    private float birdVel = 0f;
    private int score = 0;
    private boolean playing = false;

    @Override
    protected void onInit(int sr) {
        birdY = 0.4f; birdVel = 0f; score = 0; playing = false;
    }

    @Override
    public void onTouchDown(float x, float y) {
        if (!playing) { playing = true; flap(0.5f * 800f); }
        else flap(birdHeight());
    }

    private float birdHeight() { return -800f; }  // px/s impulse

    private void flap(float impulse) {
        birdVel = -Math.abs(impulse);
        juice.shake(3f, 0.12f);
        particles.burst(0, 0, 4, Palette.SPARKLE);  // wing-puff
    }

    @Override
    public void render(PluginCanvas c, int w, int h, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(w, h, timeMs, streams);
        if (mic.hit()) flap(-h * 0.65f);
        // physics
        if (playing) {
            birdVel += h * 1.6f * dt;
            birdY  += (birdVel * dt) / h;
            if (birdY > 0.95f || birdY < -0.05f) { playing = false; juice.flash(0.25f, Palette.DEATH_FLASH); }
        }
        // draw
        Gfx.gradientSky(c, w, h, Palette.SKY_DAY_TOP, Palette.SKY_DAY_BOT);
        c.save();
        juice.applyShake(c);
        float bx = w * 0.3f, by = birdY * h;
        Gfx.strokeCircle(c, bx, by, 20f * scale, Palette.ACCENT_YELLOW, Palette.UI_TEXT_INK, 2f * scale);
        c.restore();
        particles.draw(c);
        juice.drawOverlay(c, w, h);
        Gfx.textCenter(c, "Score " + score, w / 2f, 36f * scale, 20f * scale, Palette.UI_TEXT);
        if (!playing) Gfx.textCenter(c, "Chirp to fly", w / 2f, h * 0.5f, 28f * scale, Palette.UI_TEXT);
    }
}
```

The plugin.json for any game plugin:

```json
{
  "id": "tiny-flap",
  "name": "Tiny Flap",
  "engine": "native",
  "className": "com.vocalmonitor.plugin.community.TinyFlap",
  "ui_kind": "canvas",
  "ui": { "aspect": "9:16", "min_height_dp": 480 },
  "fullscreen": true,
  "streams": ["waveform"]
}
```

**Required fields for any game plugin:**
- `"engine": "native"` and `"className": "<fqn>"`
- `"ui_kind": "canvas"`
- `"streams": ["waveform"]` — otherwise `mic.feed()` gets nothing on the Android slim live monitor (mic samples enter via `streams["waveform"]` in render, not via `process()`)
- `"fullscreen": true` if you want a Fullscreen action button on the node

## Hard rules

1. **No allocation in the hot loop.** No `new` inside `render()` / `step()` except via factory helpers that you know are pool-backed (`particles.burst`, etc.). Don't allocate paint inside per-particle loops — `Gfx` allocates per call which is fine for HUD, NOT fine for a 200-particle burst.
2. **Scale everything by `scale`.** It's `min(width, height) / 360f`. Use it for text sizes, stroke widths, radii. Use `width` / `height` directly for positions.
3. **Physics derived from canvas dims, not constants.** Gravity ≈ `height × 1.6–2.0` px/s². Flap impulse ≈ `−height × 0.6`. Scroll speed ≈ `width × 0.35`. Constants like `1400f` will feel wrong on every device that isn't a 360-dp phone.
4. **Live mic via `mic.feed(streams, dt)`** at the top of render, never via `process()`. Process() is passthrough for game plugins.
5. **`beginFrame` first.** Every game-plugin render() starts with `beginFrame(w, h, timeMs, streams)`. It updates `dt`, `scale`, `mic`, `juice`, `particles` in one call. Forget it and nothing animates.

## Visual minimums

A game plugin SHOULD have:
- A gradient or layered background (`Gfx.gradientSky`).
- An animated protagonist that reacts to player input.
- A persistent score / state HUD (`Gfx.textCenter`, `Gfx.pill`).
- A "ready" overlay (`Gfx.card`) before play.
- A "game over" overlay with score + best.
- At least one `juice.shake` + `juice.flash` triggered on hit/death.
- At least one `particles.burst` triggered on score or hit.

A game plugin SHOULD NOT:
- Render so many particles or paths that the frame budget (~4 ms) blows.
- Depend on external assets.
- Use `process()` to drive visual state on the slim live monitor.

## Audio analysis cheat sheet

Pitch + volume from the live mic — drop-in helpers in `gamekit.audio`:

```java
private final PitchTracker pitch = new PitchTracker();
private final RmsFollower rms = new RmsFollower();

@Override public void init(int sr) { pitch.setSampleRate(sr); }

// in render():
pitch.feed(streams, dt);
rms.feed(streams, dt);
if (pitch.voiced()) {
    float hz    = pitch.hz();
    float cents = pitch.centsFrom(440f);    // A4 reference
    String note = NoteName.of(hz);
}
float level = rms.level();   // 0..1-ish smoothed RMS
```

`PitchTracker` defaults are tuned for voice (LP 800 Hz, follow rate 0.30, RMS floor 0.008).  Override via fluent setters: `pitch.lpCutoff(600f).floor(0.005f).idleHz(220f)`.  Don't trust sub-10¢ readings — for a tuner-grade plugin you'd want autocorrelation; for "is the user going up or down" this is plenty.

`OnsetDetector` is the generalised version of `MicTrigger` — instead of just returning `hit()`, it exposes `level()`, `baseline()`, `hotness()` for visual feedback.

## UI widgets

`gamekit.ui` widgets are stateful — keep one instance per logical control as a field, forward `onTouchDown/Move/Up` to them, draw them in `render()`.

```java
private final Button startBtn = new Button().fill(Palette.ACCENT_YELLOW);
private final Knob   gainKnob = new Knob().value01(0.5f);
private final Toggle muteSw   = new Toggle();

@Override public void onTouchDown(float x, float y) {
    startBtn.touchDown(x, y);
    gainKnob.touchDown(x, y);
    muteSw.touchDown(x, y);
}
// onTouchMove / onTouchUp dispatch the same way

@Override public void render(...) {
    // Button: draw returns true on the frame the user clicked.
    if (startBtn.draw(c, "Start",
            cx - 80f * scale, cy - 24f * scale,
            cx + 80f * scale, cy + 24f * scale, scale)) {
        startGame();
    }
    gainKnob.draw(c, knobCx, knobCy, knobR, scale);
    muteSw.draw(c, swX0, swY0, swX1, swY1, scale);
    host.setParameter("gain", gainKnob.valueScaled(-12f, 12f));
}
```

Every widget owns a `HitZone` exposed as a public final field — useful when you need custom hit-test logic (e.g. "is the user dragging this knob right now?" via `gainKnob.hit.pressed()`).

## Profiler

For perf debugging:

```java
private final Profiler prof = new Profiler();

prof.section("step");   stepWorld(dt, w, h);   prof.end();
prof.section("world");  drawScene(c, w, h);    prof.end();
prof.section("juice");  particles.draw(c);
                        juice.drawOverlay(c, w, h);  prof.end();
prof.drawOverlay(c, w, h, scale);   // small bottom-right table
```

`prof.totalMs()` reads the averaged frame time.  Disable shipping with `prof.enabled(false)`.

## SVG vector assets

Plugins can ship `.svg` files alongside the Java source and load them at runtime through the host. The parser turns the SVG into a `PluginShape` — an opaque packed-path object that draws to any `PluginCanvas` with one call.

```java
public final class MyPlugin extends GamePluginBase {
    private PluginShape bird;

    @Override public void render(PluginCanvas c, int w, int h, long timeMs, ...) {
        beginFrame(w, h, timeMs, streams);
        if (bird == null && host != null) {
            String svg = host.loadAssetText("bird.svg");
            if (svg != null) bird = Svg.parse(svg);
        }
        if (bird != null && !bird.isEmpty()) {
            float fit = Math.min(w / bird.viewBoxWidth(),
                                 h / bird.viewBoxHeight()) * 0.8f;
            bird.draw(c, w / 2f - bird.viewBoxWidth() * fit / 2f,
                          h / 2f - bird.viewBoxHeight() * fit / 2f, fit);
        }
    }
}
```

**Authoring rules:**

1. **Drop the file in `plugins/<cat>/<id>/` or `plugins/<cat>/<id>/assets/`.** Either location works; the host tries both.
2. **Declare it in `plugin.json`:** `"assets": ["bird.svg"]` — the manifest builder fails the build if you reference a file that isn't there.
3. **Always null-check `host` and the parse result.** Older hosts won't implement `loadAssetText` and return `null`; SVGs with unsupported features parse to an empty shape (`bird.isEmpty()`).

**Supported SVG subset** (v1): viewBox, `<rect>` `<circle>` `<ellipse>` `<line>` `<polygon>` `<polyline>` `<path>`, path commands `MLHVCQTSZ` (absolute + relative), groups `<g>` inheriting `fill` / `stroke` / `stroke-width` / `opacity`. Color formats: `#rgb` `#rrggbb` `rgb(r,g,b)` `none` + common named colors.

**NOT supported** — pre-process with Inkscape "Save As → Optimized SVG" or `svgo` to flatten:
- arcs (`A`/`a` commands)
- transforms on individual elements
- gradients, filters, masks, `<use>` / `<defs>`
- `<style>` blocks (CSS)
- text-as-text (convert to paths)

**Reference plugin:** `plugins/visual/svg-demo/` — loads `assets/bird.svg`, draws it bobbing + scaling with mic level. Read its `SvgDemo.java` for the lazy-load pattern.

**Tinting:** `shape.drawTinted(c, x, y, scale, color)` replaces every fill colour in the shape with `color` — load one neutral icon, render it in many palette colours.

## Mic detection tuning cheat sheet

`MicTrigger` defaults:
- `floor = 0.015` — absolute RMS minimum
- `mult = 2.5` — multiplier above baseline to fire
- `refractoryS = 0.18` — cooldown

Override via fluent setters: `new MicTrigger().floor(0.005f).mult(2.0f).refractoryS(0.10f)`. For sustained-tone games (balloon-blow style) you generally don't want `MicTrigger.hit()` — read `mic.level()` continuously instead. For sharp-onset games (clap / chirp), use `hit()`.

## Juice recipe book

```java
// Tap / score:
juice.shake(3f * scale, 0.10f);
juice.scorePop("+1", x, y, Palette.ACCENT_YELLOW);
particles.burst(x, y, 8, Palette.ACCENT_YELLOW);

// Hit / collision:
juice.shake(10f * scale, 0.25f);
juice.flash(0.15f, Palette.HIT_FLASH);
juice.impactRing(x, y, 30f * scale, Palette.HIT_FLASH);
particles.burst(x, y, 18, Palette.ACCENT_ORANGE);

// Death / game-over:
juice.shake(14f * scale, 0.40f);
juice.flash(0.30f, Palette.DEATH_FLASH);
particles.burst(x, y, 32, Palette.ACCENT_RED);
```

## Common pitfalls

- **Bird/sprite that doesn't move on slim, but moves in the DAW.** You're driving state from `process()`. Move state updates to a `feedLive(streams)` helper called from render.
- **Text the size of a pinhead on desktop fullscreen.** You forgot `* scale` on your `setTextSize`.
- **Game gets faster on a tall window.** Your gravity/speed constants are absolute. Derive from `height` / `width`.
- **Crashes immediately after start.** You forgot the AABB hitbox is 2× the visual radius. Shrink the hitbox or move spawn margins.
- **Score counter jitters between integers.** You're calling `Integer.toString(score)` with a float somewhere — round once and store the int.

## Where to look in the repo

- **Reference plugin built on the kit:** `plugins/entertainment/angry-chirp/` — flappy-style chirp game.
- **Live-mic flow:** every visual plugin uses `streams["waveform"]` — see `plugins/vocal-analysis/articulation/` for the proven-good pattern (host-supplied mic ring → render-time analysis).
- **Plugin runtime + dex build:** [BUILDING_NATIVE_PLUGINS.md](BUILDING_NATIVE_PLUGINS.md).
