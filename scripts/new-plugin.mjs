// Scaffold a new plugin folder from template/. Saves the contributor
// from copy-paste-rename boilerplate — drop them straight into editing
// the DSP loop.
//
// Usage:
//   node scripts/new-plugin.mjs <category> <id> [<display-name>]
//
// Example:
//   node scripts/new-plugin.mjs modulation gate-tremolo "Gate Tremolo"
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

const CATEGORIES = ['distortion', 'modulation', 'delay', 'filter', 'lofi', 'voice-fx', 'reverb', 'dynamics', 'utility'];

const [category, id, ...rest] = process.argv.slice(2);
const displayName = rest.join(' ').trim();

function die(msg) { console.error(msg); process.exit(1); }

if (!category || !id) {
    die(`Usage: node scripts/new-plugin.mjs <category> <id> [<display-name>]

Categories: ${CATEGORIES.join(', ')}

Example:
  node scripts/new-plugin.mjs modulation gate-tremolo "Gate Tremolo"`);
}
if (!CATEGORIES.includes(category)) {
    die(`Unknown category "${category}". Pick one of: ${CATEGORIES.join(', ')}\n` +
        `(or add a new category folder + entry in scripts/build-manifest.mjs CATEGORY_LABELS)`);
}
if (!/^[a-z][a-z0-9-]*$/.test(id)) {
    die(`Invalid id "${id}". Use lowercase kebab-case: a-z, 0-9, hyphens. Must start with a letter.`);
}

const folder = join(ROOT, 'plugins', category, id);
try {
    await access(folder);
    die(`Folder already exists: plugins/${category}/${id}/\nPick a different id or delete the existing folder first.`);
} catch {
    // Doesn't exist — good, that's what we want.
}

const friendlyName = displayName || id.split('-').map(w => w[0].toUpperCase() + w.slice(1)).join(' ');

// ── Build folder + files from template/ ──────────────────────────────
const templateJs   = await readFile(join(ROOT, 'template', 'plugin.js'),   'utf8');
const templateJson = await readFile(join(ROOT, 'template', 'plugin.json'), 'utf8');

await mkdir(folder, { recursive: true });

// Substitute id + name everywhere a placeholder appears.
const newJs = templateJs
    .replace(/REPLACE-ME/g, id)
    .replace(/ReplaceMe/g, id.split('-').map(w => w[0].toUpperCase() + w.slice(1)).join(''))
    .replace(/replaceMe/g, id.split('-').map((w, i) => i === 0 ? w : w[0].toUpperCase() + w.slice(1)).join(''));

const meta = JSON.parse(templateJson);
meta.id = id;
meta.name = friendlyName;
meta.tags = [category];

await writeFile(join(folder, `${id}.js`),     newJs);
await writeFile(join(folder, 'plugin.json'),  JSON.stringify(meta, null, 2) + '\n');

console.log(`✓ Created plugins/${category}/${id}/`);
console.log(`  ├── plugin.json`);
console.log(`  └── ${id}.js`);
console.log();
console.log(`Next steps:`);
console.log(`  1. Edit plugins/${category}/${id}/${id}.js — implement your DSP.`);
console.log(`  2. Edit plugins/${category}/${id}/plugin.json — write a real description.`);
console.log(`  3. node scripts/validate-plugins.mjs   # local check`);
console.log(`  4. Commit, push to your fork, open a PR.`);
