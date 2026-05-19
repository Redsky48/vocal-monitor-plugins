#!/usr/bin/env node
// Scaffold a new plugin folder under plugins/<category>/<id>/.
//
// Two flavours:
//
//   JS plugin (legacy default — Rhino-backed):
//     node scripts/new-plugin.mjs <category> <id> [display-name]
//     # e.g. node scripts/new-plugin.mjs modulation gate-tremolo "Gate Tremolo"
//
//   Native plugin (Java source + dex):
//     node scripts/new-plugin.mjs <category> <id> --native <template>
//     # e.g. node scripts/new-plugin.mjs entertainment sing-along --native game
//
//   Native templates:
//     game      — fullscreen mini-game on top of GamePluginBase (juice +
//                 particles + mic detection wired up)
//     analyzer  — visual analyzer (waveform stream, no audio transform)
//     effect    — pure-audio effect, no visual
//     pickerui  — visual + UI widgets (Button / Knob / Toggle demo)
//
// All variants refuse to overwrite an existing folder.

import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const CATEGORIES = [
  'distortion', 'modulation', 'delay', 'filter', 'lofi', 'voice-fx',
  'reverb', 'dynamics', 'utility', 'pitch', 'eq', 'restoration',
  'creative', 'entertainment', 'training', 'vocal-analysis', 'visual',
];
const NATIVE_TEMPLATES = ['game', 'analyzer', 'effect', 'pickerui'];

function die(msg) { console.error(msg); process.exit(1); }

function pascal(id) {
  return id.split('-').map(s => s[0].toUpperCase() + s.slice(1)).join('');
}
function titleCase(id) {
  return id.split('-').map(s => s[0].toUpperCase() + s.slice(1)).join(' ');
}

async function exists(p) {
  try { await access(p); return true; } catch { return false; }
}

// ───────────────────────────────────────────────────────────
// Argument parsing
// ───────────────────────────────────────────────────────────
const args = process.argv.slice(2);
let nativeTemplate = null;
const nativeIdx = args.indexOf('--native');
if (nativeIdx >= 0) {
  nativeTemplate = args[nativeIdx + 1];
  args.splice(nativeIdx, 2);
  if (!NATIVE_TEMPLATES.includes(nativeTemplate)) {
    die(`Unknown --native template "${nativeTemplate}".  Allowed: ${NATIVE_TEMPLATES.join(' | ')}`);
  }
}

const [category, id, ...rest] = args;
const displayName = rest.join(' ').trim();

if (!category || !id) {
  die(`Usage:
  node scripts/new-plugin.mjs <category> <id> [display-name]            # JS plugin
  node scripts/new-plugin.mjs <category> <id> --native <template>       # native plugin

Native templates: ${NATIVE_TEMPLATES.join(' | ')}
Categories:       ${CATEGORIES.join(', ')}

Examples:
  node scripts/new-plugin.mjs modulation gate-tremolo "Gate Tremolo"
  node scripts/new-plugin.mjs entertainment sing-along --native game`);
}

if (!CATEGORIES.includes(category)) {
  console.warn(`⚠ Unknown category "${category}" — proceeding anyway.\n` +
    `  Add it to scripts/build-manifest.mjs CATEGORY_LABELS for a nicer label.`);
}
if (!/^[a-z][a-z0-9-]*$/.test(id)) {
  die(`Invalid id "${id}". Use lowercase kebab-case: a-z, 0-9, hyphens.`);
}

const folder = join(ROOT, 'plugins', category, id);
if (await exists(folder)) {
  die(`Folder already exists: plugins/${category}/${id}/`);
}

const friendlyName = displayName || titleCase(id);

if (nativeTemplate) {
  await scaffoldNative(category, id, friendlyName, nativeTemplate);
} else {
  await scaffoldJs(category, id, friendlyName);
}

// ───────────────────────────────────────────────────────────
// JS path — preserves the original behaviour
// ───────────────────────────────────────────────────────────
async function scaffoldJs(category, id, displayName) {
  const templateJs   = await readFile(join(ROOT, 'template', 'plugin.js'),   'utf8');
  const templateJson = await readFile(join(ROOT, 'template', 'plugin.json'), 'utf8');

  await mkdir(folder, { recursive: true });
  const cls = pascal(id);
  const camel = cls.charAt(0).toLowerCase() + cls.slice(1);
  const newJs = templateJs
    .replace(/REPLACE-ME/g, id)
    .replace(/ReplaceMe/g, cls)
    .replace(/replaceMe/g, camel);

  const meta = JSON.parse(templateJson);
  meta.id = id;
  meta.name = displayName;
  meta.tags = [category];

  await writeFile(join(folder, `${id}.js`),    newJs);
  await writeFile(join(folder, 'plugin.json'), JSON.stringify(meta, null, 2) + '\n');

  console.log(`✓ Created plugins/${category}/${id}/  (JS template)`);
  console.log(`  plugin.json`);
  console.log(`  ${id}.js`);
  console.log('');
  console.log('Next steps:');
  console.log(`  1. Edit plugins/${category}/${id}/${id}.js — implement your DSP.`);
  console.log('  2. node scripts/validate-plugins.mjs   # local check');
  console.log('  3. Commit & open a PR.');
}

// ───────────────────────────────────────────────────────────
// Native path — Java + manifest
// ───────────────────────────────────────────────────────────
async function scaffoldNative(category, id, displayName, template) {
  const cls = pascal(id);
  const className = `com.vocalmonitor.plugin.community.${cls}`;
  const manifest = nativeManifest(category, id, displayName, className, template);
  const source = nativeSource(template, cls, displayName);

  await mkdir(folder, { recursive: true });
  await writeFile(join(folder, 'plugin.json'),
    JSON.stringify(manifest, null, 2) + '\n');
  await writeFile(join(folder, `${cls}.java`), source);

  console.log(`✓ Created plugins/${category}/${id}/  (native ${template} template)`);
  console.log('  plugin.json');
  console.log(`  ${cls}.java`);
  console.log('');
  console.log('Next steps:');
  console.log(`  1. Edit plugins/${category}/${id}/${cls}.java`);
  console.log('  2. node scripts/build-native.mjs           # compile to .dex');
  console.log('  3. tools/test-app/run.bat or DAW run       # try it');
  console.log('  4. node scripts/build-manifest.mjs         # publish');
}

function nativeManifest(category, id, name, className, template) {
  const base = {
    id, name,
    author: 'Vocal Monitor',
    version: '1.0.0',
    description: `${name} — replace this description in plugin.json before publishing.`,
    tags: [category, 'native'],
    engine: 'native',
    className,
  };
  switch (template) {
    case 'effect':
      return base;
    case 'analyzer':
      return { ...base, ui_kind: 'canvas',
        ui: { aspect: '16:9', min_height_dp: 200 },
        streams: ['waveform'] };
    case 'game':
      return { ...base, ui_kind: 'canvas',
        ui: { aspect: '9:16', min_height_dp: 480 },
        fullscreen: true,
        streams: ['waveform'] };
    case 'pickerui':
      return { ...base, ui_kind: 'canvas',
        ui: { aspect: '16:9', min_height_dp: 320 },
        fullscreen: true,
        streams: ['waveform'] };
  }
}

function nativeSource(template, cls, name) {
  switch (template) {
    case 'effect':   return tplEffect(cls, name);
    case 'analyzer': return tplAnalyzer(cls, name);
    case 'game':     return tplGame(cls, name);
    case 'pickerui': return tplPickerUi(cls, name);
  }
}

// ── Native template strings ─────────────────────────────────
function tplEffect(cls, name) {
  return `package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.VocalMonitorNativePlugin;

/**
 * ${name} — pure-audio effect.  No visual side, no streams declared.
 * Audio passes through process(); replace the body with real DSP.
 */
public final class ${cls} implements VocalMonitorNativePlugin {

    private int sampleRate = 44_100;
    private float mix = 1f;

    @Override public void init(int sr) { this.sampleRate = sr; }

    @Override public String[] parameterNames()        { return new String[] { "mix" }; }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) { return 1f; }
    @Override public String parameterLabel(String n)  { return "Mix"; }
    @Override public void setParameter(String n, float v) {
        if ("mix".equals(n)) mix = v;
    }

    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) {
            // TODO: real DSP here.  This is a passthrough scaffold.
            output[i] = input[i] * mix + input[i] * (1f - mix);
        }
    }
}
`;
}

function tplAnalyzer(cls, name) {
  return `package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.VocalMonitorVisualPlugin;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.audio.RmsFollower;

import java.util.Map;

/**
 * ${name} — visual analyzer.  Reads streams["waveform"] every render
 * frame.  process() is passthrough; all visual state derives from
 * the streams map inside render().
 */
public final class ${cls} implements VocalMonitorVisualPlugin {

    private final RmsFollower rms = new RmsFollower();
    private long lastMs = -1L;

    @Override public void init(int sr) { rms.reset(); lastMs = -1L; }

    @Override public String[] parameterNames()        { return new String[0]; }
    @Override public float parameterMin(String n)     { return 0f; }
    @Override public float parameterMax(String n)     { return 1f; }
    @Override public float parameterDefault(String n) { return 0f; }
    @Override public String parameterLabel(String n)  { return n; }
    @Override public void setParameter(String n, float v) {}

    @Override
    public void process(float[] input, float[] output) {
        int n = Math.min(input.length, output.length);
        for (int i = 0; i < n; i++) output[i] = input[i];
    }

    @Override
    public void render(PluginCanvas c, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        float dt = lastMs < 0L ? 0.016f : Math.min(0.10f, (timeMs - lastMs) / 1000f);
        lastMs = timeMs;
        rms.feed(streams, dt);
        float scale = Math.min(width, height) / 360f;

        Gfx.clear(c, width, height, Palette.UI_BG_DEEP);
        Gfx.textCenter(c, "${name}", width / 2f, 28f * scale,
            18f * scale, Palette.UI_TEXT);
        Gfx.levelBar(c,
            24f * scale, height * 0.5f - 8f * scale,
            width - 24f * scale, height * 0.5f + 8f * scale,
            Math.min(1f, rms.level() * 6f));
        // TODO: draw your analysis here.
    }
}
`;
}

function tplGame(cls, name) {
  return `package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;

import java.util.Map;

/**
 * ${name} — voice-controlled mini-game.  Built on GamePluginBase so
 * juice + particles + mic detection are wired up by default.
 *
 * See BUILDING_GAME_PLUGINS.md for the kit's full API.
 */
public final class ${cls} extends GamePluginBase {

    private int score = 0;

    @Override protected void onInit(int sr) {
        score = 0;
        // mic.floor(0.015f).mult(2.5f).refractoryS(0.18f);
    }

    @Override
    public void onTouchDown(float x, float y) {
        score++;
        juice.scorePop("+1", x, y, Palette.ACCENT_YELLOW);
        particles.burst(x, y, 8, Palette.ACCENT_YELLOW);
    }

    @Override
    public void render(PluginCanvas c, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(width, height, timeMs, streams);
        if (mic.hit()) {
            score++;
            juice.shake(6f * scale, 0.15f);
            particles.burst(width / 2f, height / 2f, 12, Palette.ACCENT_GREEN);
        }

        Gfx.gradientSky(c, width, height, Palette.SKY_DAY_TOP, Palette.SKY_DAY_BOT);

        c.save();
        juice.applyShake(c);
        // TODO: draw your world here.
        c.restore();

        particles.draw(c);
        juice.drawOverlay(c, width, height);
        Gfx.textCenter(c, "Score " + score, width / 2f, 36f * scale,
            22f * scale, Palette.UI_TEXT);
        Gfx.textCenter(c, "Chirp or tap", width / 2f, height * 0.5f,
            18f * scale, Palette.UI_TEXT_DIM);
    }
}
`;
}

function tplPickerUi(cls, name) {
  return `package com.vocalmonitor.plugin.community;

import com.vocalmonitor.plugin.PluginCanvas;
import com.vocalmonitor.plugin.gamekit.GamePluginBase;
import com.vocalmonitor.plugin.gamekit.Gfx;
import com.vocalmonitor.plugin.gamekit.Palette;
import com.vocalmonitor.plugin.gamekit.ui.Button;
import com.vocalmonitor.plugin.gamekit.ui.Knob;
import com.vocalmonitor.plugin.gamekit.ui.Toggle;

import java.util.Map;

/**
 * ${name} — interactive plugin demonstrating gamekit/ui/ widgets.
 * Replace the demo layout with your own controls.
 */
public final class ${cls} extends GamePluginBase {

    private final Button actionBtn = new Button().fill(Palette.ACCENT_YELLOW);
    private final Toggle muteSw    = new Toggle().value(false);
    private final Knob   gainKnob  = new Knob().value01(0.5f);

    @Override
    public void onTouchDown(float x, float y) {
        actionBtn.touchDown(x, y);
        muteSw.touchDown(x, y);
        gainKnob.touchDown(x, y);
    }
    @Override
    public void onTouchMove(float x, float y) {
        actionBtn.touchMove(x, y);
        muteSw.touchMove(x, y);
        gainKnob.touchMove(x, y);
    }
    @Override
    public void onTouchUp(float x, float y) {
        actionBtn.touchUp(x, y);
        muteSw.touchUp(x, y);
        gainKnob.touchUp(x, y);
    }

    @Override
    public void render(PluginCanvas c, int width, int height, long timeMs,
                       Map<String, Float> params, Map<String, float[]> streams) {
        beginFrame(width, height, timeMs, streams);
        Gfx.clear(c, width, height, Palette.UI_BG_DEEP);
        Gfx.textCenter(c, "${name}", width / 2f, 36f * scale,
            22f * scale, Palette.UI_TEXT);

        float cy = height * 0.55f;
        float knobR = Math.min(width, height) * 0.12f;
        gainKnob.draw(c, width * 0.25f, cy, knobR, scale);
        muteSw.draw(c, width * 0.45f, cy - 16f * scale,
                       width * 0.55f, cy + 16f * scale, scale);
        if (actionBtn.draw(c, "Tap me",
                width * 0.65f, cy - 24f * scale,
                width * 0.90f, cy + 24f * scale, scale)) {
            juice.shake(4f * scale, 0.10f);
        }

        Gfx.textLeft(c,
            "Gain "  + Math.round(gainKnob.value01() * 100f) + "%",
            width * 0.20f, height * 0.78f, 13f * scale, Palette.UI_TEXT_DIM);
        Gfx.textLeft(c,
            "Muted " + (muteSw.value() ? "yes" : "no"),
            width * 0.45f, height * 0.78f, 13f * scale, Palette.UI_TEXT_DIM);

        juice.drawOverlay(c, width, height);
    }
}
`;
}
