// One-off helper: take a kebab-case plugin id, locate its plugin.json,
// add the `engine` / `className` fields, tag "native" onto the tags
// array (idempotent), and delete the matching <id>.js source so the
// validator routes through the native code path instead. ClassName is
// derived from the id by capitalising each kebab segment, prefixed with
// com.vocalmonitor.plugin.community.
//
// Usage:
//   node scripts/promote-native.mjs <plugin-id> [<plugin-id> ...]
import { readFile, writeFile, readdir, rm, access } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS_DIR = join(ROOT, 'plugins');

function classNameFor(id) {
  return 'com.vocalmonitor.plugin.community.' +
    id.split('-').map(s => s[0].toUpperCase() + s.slice(1)).join('');
}

async function exists(p) { try { await access(p); return true; } catch { return false; } }

async function listDir(dir) {
  try { return (await readdir(dir, { withFileTypes: true })).filter(e => e.isDirectory()).map(e => e.name); }
  catch { return []; }
}

async function findPlugin(id) {
  for (const cat of await listDir(PLUGINS_DIR)) {
    const folder = join(PLUGINS_DIR, cat, id);
    if (await exists(join(folder, 'plugin.json'))) return { cat, folder };
  }
  return null;
}

async function promote(id) {
  const located = await findPlugin(id);
  if (!located) {
    console.error(`  FAIL ${id}: not found under plugins/*/`);
    return false;
  }
  const { folder } = located;
  const metaPath = join(folder, 'plugin.json');
  const meta = JSON.parse(await readFile(metaPath, 'utf8'));
  meta.engine = 'native';
  meta.className = classNameFor(id);
  if (!Array.isArray(meta.tags)) meta.tags = [];
  if (!meta.tags.includes('native')) meta.tags.push('native');
  await writeFile(metaPath, JSON.stringify(meta, null, 2) + '\n');

  const jsPath = join(folder, `${id}.js`);
  if (await exists(jsPath)) {
    await rm(jsPath);
    console.log(`  ok  ${id}  [plugin.json updated, ${id}.js removed]`);
  } else {
    console.log(`  ok  ${id}  [plugin.json updated]`);
  }
  return true;
}

const ids = process.argv.slice(2);
if (ids.length === 0) {
  console.error('Usage: node scripts/promote-native.mjs <plugin-id> [<plugin-id> ...]');
  process.exit(1);
}
let fails = 0;
for (const id of ids) {
  if (!await promote(id)) fails++;
}
if (fails > 0) process.exit(1);
