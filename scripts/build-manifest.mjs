// Walks plugins/<category>/<id>/{plugin.json, <id>.js} and emits the
// top-level manifest.json that the Vocal Monitor app fetches at runtime.
//
// Source URL is derived from the repository slug — either from
// $GITHUB_REPOSITORY (CI) or from repo.config.json (local). This means a
// fork works automatically: clone, edit repo.config.json once, push, and
// the GitHub Actions rebuild bakes in the new URLs.
import { readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS_DIR = join(ROOT, 'plugins');

// Maps the lowercase folder name to the title-cased label the app shows
// in its category filter chips. Add new categories here when introducing
// new top-level folders under plugins/.
const CATEGORY_LABELS = {
  distortion: 'Distortion',
  lofi: 'Lo-Fi',
  modulation: 'Modulation',
  delay: 'Delay',
  filter: 'Filter',
  'voice-fx': 'Voice FX',
  utility: 'Utility',
  reverb: 'Reverb',
  dynamics: 'Dynamics',
  pitch: 'Pitch',
  eq: 'EQ',
  restoration: 'Restoration',
  creative: 'Creative',
  visual: 'Visual',
  'vocal-analysis': 'Vocal Analysis',
  entertainment: 'Entertainment',
  training: 'Training',
};

async function repoConfig() {
  const env = process.env.GITHUB_REPOSITORY;
  if (env) return { repository: env, branch: process.env.GITHUB_REF_NAME || 'main' };
  try {
    const txt = await readFile(join(ROOT, 'repo.config.json'), 'utf8');
    const cfg = JSON.parse(txt);
    return { repository: cfg.repository, branch: cfg.branch || 'main' };
  } catch {
    return { repository: 'REPLACE-OWNER/vocal-monitor-plugins', branch: 'main' };
  }
}

async function listDir(dir) {
  const ents = await readdir(dir, { withFileTypes: true });
  return ents.filter(e => e.isDirectory()).map(e => e.name);
}

async function readPlugin(categoryDir, pluginDir, cfg) {
  const folder = join(PLUGINS_DIR, categoryDir, pluginDir);
  const metaPath = join(folder, 'plugin.json');
  const meta = JSON.parse(await readFile(metaPath, 'utf8'));
  if (!meta.id || !meta.name) {
    throw new Error(`${categoryDir}/${pluginDir}/plugin.json: missing id or name`);
  }
  if (meta.id !== pluginDir) {
    throw new Error(`${categoryDir}/${pluginDir}/plugin.json: id "${meta.id}" must match folder name "${pluginDir}"`);
  }
  // Drafts are work-in-progress: source stays in the repo so it can be
  // iterated on, but the manifest hides them from the Android app.
  // Flip `"draft": true` to publish.
  if (meta.draft === true) return null;
  const engine = meta.engine ?? 'js';
  // File extension follows the engine: JS plugins ship a `.js` source,
  // native plugins ship a pre-compiled `.dex`, `source` plugins ship
  // their backend as `<id>.dex` (same packaging as native).  Other
  // engines may add their own conventions later — fall back to
  // <id>.<engine> for forward-compat.
  let filename;
  switch (engine) {
    case 'native': filename = `${meta.id}.dex`; break;
    case 'js':     filename = `${meta.id}.js`;  break;
    case 'source': filename = `${meta.id}.dex`; break;
    default:       filename = `${meta.id}.${engine}`; break;
  }
  await readFile(join(folder, filename));  // must exist
  // Stat the payload so the app can show "Size X MB" in the Library
  // listing without first downloading the plugin. Cached in the
  // manifest because manifest builds are stable & published with the
  // source — re-stat at runtime would mean an extra round-trip per row.
  const sizeBytes = (await stat(join(folder, filename))).size;
  if ((engine === 'native' || engine === 'source') && !meta.className) {
    throw new Error(`${categoryDir}/${pluginDir}/plugin.json: ${engine} plugins must declare \`className\``);
  }
  const category = CATEGORY_LABELS[categoryDir]
    ?? (categoryDir[0].toUpperCase() + categoryDir.slice(1));
  const entry = {
    id: meta.id,
    name: meta.name,
    author: meta.author ?? 'Unknown',
    category,
    description: meta.description ?? '',
    version: meta.version ?? '1.0.0',
    tags: Array.isArray(meta.tags) ? meta.tags : [],
    engine,
    source: `https://raw.githubusercontent.com/${cfg.repository}/${cfg.branch}/plugins/${categoryDir}/${pluginDir}/${filename}`,
    sizeBytes,
  };
  if (engine === 'native' || engine === 'source') entry.className = meta.className;
  // Forward custom-UI fields verbatim. ui_kind tells the host whether
  // to host the plugin's canvas-mode render() (VocalMonitorVisualPlugin)
  // or render its declarative spec block. The ui object is the spec
  // payload for ui_kind == "spec" plugins.
  if (typeof meta.ui_kind === 'string') entry.ui_kind = meta.ui_kind;
  if (meta.ui && typeof meta.ui === 'object') entry.ui = meta.ui;
  if (Array.isArray(meta.streams)) entry.streams = meta.streams;
  // Hosts that support fullscreen visualisation (DAW, future slim
  // "expand" mode) read this flag to add a fullscreen action.
  if (meta.fullscreen === true) entry.fullscreen = true;

  // Per-plugin assets — SVG / text resources the plugin loads at
  // runtime via PluginHost.loadAssetText().  Declared in plugin.json as
  // `"assets": ["bird.svg", "logo.svg"]`; the file must exist in the
  // plugin folder (or in plugin-folder/assets/).  Each gets its own
  // CDN URL + sizeBytes baked into the manifest so hosts can fetch
  // them in one batch alongside the plugin payload.
  if (Array.isArray(meta.assets)) {
    const assets = [];
    for (const a of meta.assets) {
      if (typeof a !== 'string' || a.length === 0) continue;
      // Look for the asset next to plugin.json OR inside an assets/
      // subdir — whichever exists.  The slim/DAW host resolves the
      // same way, so authors can ship single files at the top level
      // for tiny plugins, or organise everything in assets/ for
      // bigger ones.
      const candidates = [a, `assets/${a}`];
      let resolved = null;
      let sizeBytes = 0;
      let abs = null;
      for (const c of candidates) {
        const candidate = join(folder, c);
        try {
          const st = await stat(candidate);
          if (st.isFile()) { resolved = c; sizeBytes = st.size; abs = candidate; break; }
        } catch { /* try next */ }
      }
      if (resolved == null) {
        throw new Error(`${categoryDir}/${pluginDir}/plugin.json: declared asset "${a}" not found ` +
          `(looked in plugin folder and in assets/)`);
      }
      // sha256 of the asset bytes, baked into the (signed) manifest so the
      // host can verify the downloaded file's integrity — important for
      // binary assets like ONNX models served from a CDN.
      const sha256 = createHash('sha256').update(await readFile(abs)).digest('hex');
      assets.push({
        name: a,
        source: `https://raw.githubusercontent.com/${cfg.repository}/${cfg.branch}/plugins/${categoryDir}/${pluginDir}/${resolved}`,
        sizeBytes,
        sha256,
      });
    }
    if (assets.length > 0) entry.assets = assets;
  }
  // Forward author-shipped presets verbatim. The Android app's
  // JsPluginLibrary parses the same shape: `[{name, description?, params}]`.
  // Validate lightly so a malformed presets block doesn't fail the build
  // — bad presets are filtered, valid ones included.
  if (Array.isArray(meta.presets)) {
    const cleanPresets = meta.presets
      .filter(p => p && typeof p.name === 'string' && p.name.length > 0)
      .filter(p => p.params && typeof p.params === 'object' && !Array.isArray(p.params))
      .map(p => ({
        name: p.name,
        ...(p.description ? { description: p.description } : {}),
        params: Object.fromEntries(
          Object.entries(p.params)
            .filter(([_, v]) => typeof v === 'number' && Number.isFinite(v)),
        ),
      }))
      .filter(p => Object.keys(p.params).length > 0);
    if (cleanPresets.length > 0) entry.presets = cleanPresets;
  }
  return entry;
}

async function main() {
  const cfg = await repoConfig();
  const categories = (await listDir(PLUGINS_DIR)).sort();
  const plugins = [];
  const drafts = [];
  let errors = 0;
  for (const cat of categories) {
    const names = (await listDir(join(PLUGINS_DIR, cat))).sort();
    for (const name of names) {
      try {
        const entry = await readPlugin(cat, name, cfg);
        if (entry === null) drafts.push(`${cat}/${name}`);
        else plugins.push(entry);
      } catch (e) {
        console.error(`  ✗ ${e.message}`);
        errors++;
      }
    }
  }
  if (errors > 0) {
    console.error(`\n${errors} error(s) — manifest NOT written.`);
    process.exit(1);
  }
  const manifest = {
    version: 1,
    generatedAt: new Date().toISOString(),
    repository: cfg.repository,
    plugins,
  };
  await writeFile(join(ROOT, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Wrote manifest.json with ${plugins.length} plugins from ${categories.length} categories.`);
  if (drafts.length > 0) {
    console.log(`Skipped ${drafts.length} draft(s): ${drafts.join(', ')}`);
  }
  console.log(`Source base: https://raw.githubusercontent.com/${cfg.repository}/${cfg.branch}/`);
}

await main();
