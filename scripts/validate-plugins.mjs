// PR-time validator. For every plugins/<cat>/<id>/ folder:
//
//   1. plugin.json parses as JSON and has the required fields.
//   2. plugin id matches the folder name (so URLs resolve correctly).
//   3. <id>.js exists and parses as ES5-syntax JavaScript via Node's vm.
//   4. <id>.js does NOT use constructs the Rhino interpreter rejects on
//      Android — ES6 class, ES modules, CommonJS require, WebAssembly,
//      DOM globals, network/IO APIs.
//   5. <id>.js calls registerProcessor() at least once.
//
// Exits non-zero with a summary if anything fails — wired into the
// validate.yml GH Action so contributors get fast feedback in PRs.
import { readFile, readdir } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS_DIR = join(ROOT, 'plugins');

const FORBIDDEN = [
  { rx: /\bclass\s+[A-Za-z_$][\w$]*/, msg: 'ES6 `class` syntax — Rhino interpreter on Android does not support it. Use prototype style: `function Foo() {}` + `Foo.prototype.method = function () {}`.' },
  { rx: /^\s*import\s+[\w*{},\s]+\s+from\s+/m, msg: '`import` is not available — plugin must be self-contained.' },
  { rx: /^\s*export\s+(default|const|let|var|function|class)\b/m, msg: '`export` is not available — emit code via registerProcessor() only.' },
  { rx: /\brequire\s*\(/, msg: 'CommonJS `require()` is not available.' },
  { rx: /\bWebAssembly\b/, msg: 'WebAssembly is not available on Rhino.' },
  { rx: /\bdocument\b/, msg: 'DOM `document` is not available — no GUI inside a plugin.' },
  { rx: /\bwindow\b/, msg: '`window` is not available.' },
  { rx: /\blocalStorage\b/, msg: 'localStorage is not available.' },
  { rx: /\bfetch\s*\(/, msg: 'fetch() is not available — plugin cannot reach the network.' },
  { rx: /\bXMLHttpRequest\b/, msg: 'XHR is not available.' },
];

const REQUIRED_FIELDS = ['id', 'name', 'description'];

let failed = 0;
const fails = [];

function fail(path, msg) {
  fails.push({ path, msg });
  failed++;
}

// Block /* ... */ and line // ... comments. Naive but good enough — a
// "comment-shaped" string literal in a plugin would be very unusual.
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

async function listDir(dir) {
  try {
    const ents = await readdir(dir, { withFileTypes: true });
    return ents.filter(e => e.isDirectory()).map(e => e.name);
  } catch {
    return [];
  }
}

async function validatePlugin(category, name) {
  const folder = join(PLUGINS_DIR, category, name);
  const metaPath = join(folder, 'plugin.json');
  let meta;
  try {
    meta = JSON.parse(await readFile(metaPath, 'utf8'));
  } catch (e) {
    return fail(`${category}/${name}/plugin.json`, `invalid JSON: ${e.message}`);
  }
  for (const k of REQUIRED_FIELDS) {
    if (!meta[k]) return fail(metaPath, `missing required field: ${k}`);
  }
  if (meta.id !== name) {
    return fail(metaPath, `id "${meta.id}" must match folder name "${name}"`);
  }
  if (!/^[a-z0-9-]+$/.test(meta.id)) {
    return fail(metaPath, `id "${meta.id}" must be kebab-case (a-z, 0-9, -)`);
  }
  const engine = meta.engine ?? 'js';
  if (engine === 'native') return validateNativePlugin(folder, category, name, meta);
  // `source` plugins ship the same way as native (.dex + className) —
  // reuse the native validator with a different filename hint.
  if (engine === 'source') return validateNativePlugin(folder, category, name, meta);
  if (engine !== 'js') {
    return fail(metaPath, `unknown engine "${engine}" (expected "js", "native", or "source")`);
  }
  const jsPath = join(folder, `${meta.id}.js`);
  let src;
  try {
    src = await readFile(jsPath, 'utf8');
  } catch {
    return fail(jsPath, 'missing JS source');
  }
  // Strip comments before applying the forbidden-token check — otherwise
  // a docstring that mentions "window" or "fetch" as prose would trip the
  // regex even though the code itself never uses it.
  const stripped = stripComments(src);
  for (const { rx, msg } of FORBIDDEN) {
    if (rx.test(stripped)) return fail(jsPath, msg);
  }
  try {
    new vm.Script(src, { filename: `${name}.js` });
  } catch (e) {
    return fail(jsPath, `syntax error: ${e.message}`);
  }
  if (!/registerProcessor\s*\(\s*['"]/.test(src)) {
    return fail(jsPath, 'does not call registerProcessor()');
  }
  console.log(`  ok  ${category}/${name}`);
}

/**
 * Native plugins ship a `.dex` file rather than JS source. We can't run
 * the bytecode here in CI (it's Android DEX, not JVM), but we can verify:
 *
 *   - plugin.json declares `className`
 *   - <id>.dex exists and starts with the DEX magic ("dex\n035" or "dex\n038")
 *   - file size is sane (under 5 MB to match the app's runtime cap)
 */
async function validateNativePlugin(folder, category, name, meta) {
  if (!meta.className) {
    return fail(join(folder, 'plugin.json'), 'native plugin must declare `className`');
  }
  const dexPath = join(folder, `${meta.id}.dex`);
  let bytes;
  try {
    bytes = await readFile(dexPath);
  } catch {
    return fail(dexPath, 'missing .dex — compile your source with d8 and commit the result');
  }
  if (bytes.length > 5_000_000) {
    return fail(dexPath, `dex too large (${bytes.length} bytes; cap is 5 MB)`);
  }
  // DEX magic: "dex\n<version>\0" where <version> is "035" through "041".
  const magic = bytes.slice(0, 8).toString('ascii');
  if (!/^dex\n0[0-9]{2}\0$/.test(magic)) {
    return fail(dexPath, `not a DEX file (header was ${JSON.stringify(magic)})`);
  }
  console.log(`  ok  ${category}/${name}  [native, ${bytes.length} bytes]`);
}

const categories = (await listDir(PLUGINS_DIR)).sort();
let total = 0;
for (const cat of categories) {
  const names = (await listDir(join(PLUGINS_DIR, cat))).sort();
  for (const name of names) {
    total++;
    await validatePlugin(cat, name);
  }
}

console.log();
if (failed > 0) {
  console.error(`${failed} of ${total} plugin(s) failed validation:\n`);
  for (const f of fails) console.error(`  ✗ ${f.path}\n    ${f.msg}`);
  process.exit(1);
}
console.log(`All ${total} plugin(s) valid.`);
