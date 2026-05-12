// Walks plugins/<category>/<id>/{plugin.json, <id>.js} and emits the
// top-level manifest.json that the Vocal Monitor app fetches at runtime.
//
// Source URL is derived from the repository slug — either from
// $GITHUB_REPOSITORY (CI) or from repo.config.json (local). This means a
// fork works automatically: clone, edit repo.config.json once, push, and
// the GitHub Actions rebuild bakes in the new URLs.
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

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
  const engine = meta.engine ?? 'js';
  // File extension follows the engine: JS plugins ship a `.js` source,
  // native plugins ship a pre-compiled `.dex`. The app's installer
  // routes to the matching engine based on this same field.
  const filename = engine === 'native' ? `${meta.id}.dex` : `${meta.id}.js`;
  await readFile(join(folder, filename));  // must exist
  if (engine === 'native' && !meta.className) {
    throw new Error(`${categoryDir}/${pluginDir}/plugin.json: native plugins must declare \`className\``);
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
  };
  if (engine === 'native') entry.className = meta.className;
  return entry;
}

async function main() {
  const cfg = await repoConfig();
  const categories = (await listDir(PLUGINS_DIR)).sort();
  const plugins = [];
  let errors = 0;
  for (const cat of categories) {
    const names = (await listDir(join(PLUGINS_DIR, cat))).sort();
    for (const name of names) {
      try {
        plugins.push(await readPlugin(cat, name, cfg));
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
  console.log(`Source base: https://raw.githubusercontent.com/${cfg.repository}/${cfg.branch}/`);
}

await main();
