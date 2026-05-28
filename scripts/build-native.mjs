// Compile every native plugin in plugins/ into a fresh <id>.dex.
//
// Pipeline per plugin:
//   1. Read plugin.json — skip unless engine === "native".
//   2. Compile <ClassName>.java with javac (Java 8 bytecode for d8).
//   3. Pack the .class through d8 to produce <id>.dex.
//   4. Drop the produced .dex next to the .java source.
//
// Build artefacts (compiled .class files) live in scripts/native-stub/build/
// and are gitignored — only the committed .java + .dex matter for the host.
//
// Usage:
//   node scripts/build-native.mjs               # rebuild everything
//   node scripts/build-native.mjs <plugin-id>   # rebuild one
//
// Requires JAVA_HOME-resolvable javac (Java 8+) and d8 (Android SDK
// build-tools). The first time you run this on Windows, d8 is typically
// at C:\Android\build-tools\<ver>\d8.bat — set DEX_TOOL=path/to/d8 if it
// isn't on PATH.
import { readFile, readdir, mkdir, rm, writeFile, access } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS_DIR = join(ROOT, 'plugins');
const STUB_DIR = join(ROOT, 'scripts', 'native-stub');
const BUILD_DIR = join(STUB_DIR, 'build');
// Canonical gamekit source. Plugins that extend GamePluginBase need these
// classes packed INTO their .dex — the host only ships the base plugin API
// (PluginCanvas/PluginHost/…), not gamekit, so a gamekit plugin that didn't
// bundle it would fail to load with "Didn't find class".
const GAMEKIT_DIR = join(ROOT, 'shared', 'src', 'main', 'java', 'com', 'vocalmonitor', 'plugin', 'gamekit');

const D8 = process.env.DEX_TOOL
  ?? (process.platform === 'win32'
        ? 'C:\\Android\\build-tools\\34.0.0\\d8.bat'
        : 'd8');
const JAVAC = process.env.JAVAC ?? 'javac';

async function listDir(dir) {
  try {
    const ents = await readdir(dir, { withFileTypes: true });
    return ents.filter(e => e.isDirectory()).map(e => e.name);
  } catch { return []; }
}

async function exists(p) {
  try { await access(p); return true; } catch { return false; }
}

// Recursively collect every .java path under dir.
async function collectJava(dir) {
  const out = [];
  async function walk(d) {
    for (const e of await readdir(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) await walk(p);
      else if (e.name.endsWith('.java')) out.push(p);
    }
  }
  await walk(dir);
  return out;
}

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: 'pipe', shell: process.platform === 'win32', ...opts });
  return {
    code: r.status,
    out: (r.stdout ?? Buffer.from('')).toString('utf8'),
    err: (r.stderr ?? Buffer.from('')).toString('utf8'),
  };
}

async function compileStub() {
  // Compile every interface stub under scripts/native-stub once per
  // build so plugin .java files have something to import against. The
  // classes never ship — only the host's runtime versions are loaded.
  //
  // Walks the stub tree so adding new contracts (PluginCanvas,
  // PluginPaint, etc. for visual plugins) is just dropping the .java
  // next to the existing one; the script picks them up automatically.
  const stubRoot = join(STUB_DIR, 'com');
  const javaFiles = [];
  async function walk(dir) {
    const ents = await readdir(dir, { withFileTypes: true });
    for (const e of ents) {
      const p = join(dir, e.name);
      if (e.isDirectory()) await walk(p);
      else if (e.name.endsWith('.java')) javaFiles.push(p);
    }
  }
  await walk(stubRoot);
  if (javaFiles.length === 0) throw new Error('no stub .java files found');

  const out = join(BUILD_DIR, 'stub');
  await mkdir(out, { recursive: true });
  const r = run(JAVAC, [
    '--release', '8',
    '-encoding', 'utf-8',
    '-d', out,
    ...javaFiles,
  ]);
  if (r.code !== 0) throw new Error(`stub compile failed:\n${r.err || r.out}`);
}

async function compileOne(category, name, meta) {
  if (!meta.className) throw new Error('plugin.json missing className');
  const folder = join(PLUGINS_DIR, category, name);
  const simpleName = meta.className.split('.').pop();
  const javaSrc = join(folder, `${simpleName}.java`);
  if (!await exists(javaSrc)) throw new Error(`no source at ${javaSrc}`);

  // Gamekit plugins must carry the gamekit classes in their own .dex.
  // Detect by import and, when present, add the real gamekit sources to
  // the javac invocation so their .class files land in classOut and get
  // dexed alongside the plugin. The host-API classes they reference stay
  // external (resolved from the stub classpath, never emitted to classOut).
  const src = await readFile(javaSrc, 'utf8');
  const gamekitSources = /com\.vocalmonitor\.plugin\.gamekit/.test(src)
    ? await collectJava(GAMEKIT_DIR)
    : [];

  // 1. javac to .class.
  const classOut = join(BUILD_DIR, name);
  await rm(classOut, { recursive: true, force: true });
  await mkdir(classOut, { recursive: true });
  const stubCp = join(BUILD_DIR, 'stub');
  const r1 = run(JAVAC, [
    '--release', '8',
    '-encoding', 'utf-8',
    '-cp', stubCp,
    '-d', classOut,
    javaSrc,
    ...gamekitSources,
  ]);
  if (r1.code !== 0) throw new Error(`javac failed:\n${r1.err || r1.out}`);

  // 2. d8 to .dex. Walk the produced .class files (there may be inner
  // classes — Faust-generated code sometimes has them).
  const classFiles = [];
  async function walk(dir) {
    const ents = await readdir(dir, { withFileTypes: true });
    for (const e of ents) {
      const p = join(dir, e.name);
      if (e.isDirectory()) await walk(p);
      else if (e.name.endsWith('.class')) classFiles.push(p);
    }
  }
  await walk(classOut);
  if (classFiles.length === 0) throw new Error('no .class files produced');

  const dexTmp = join(classOut, 'dex');
  await mkdir(dexTmp, { recursive: true });
  const r2 = run(D8, [...classFiles, '--output', dexTmp]);
  if (r2.code !== 0) throw new Error(`d8 failed:\n${r2.err || r2.out}`);

  const produced = join(dexTmp, 'classes.dex');
  if (!await exists(produced)) throw new Error(`d8 did not produce classes.dex`);
  const dexBytes = await readFile(produced);
  const dexOut = join(folder, `${name}.dex`);
  await writeFile(dexOut, dexBytes);
  const sz = (dexBytes.length / 1024).toFixed(1);
  return { dexOut, size: dexBytes.length, sz };
}

async function main() {
  const onlyId = process.argv[2];
  await mkdir(BUILD_DIR, { recursive: true });
  await compileStub();

  const categories = (await listDir(PLUGINS_DIR)).sort();
  let total = 0, fails = 0;
  for (const cat of categories) {
    for (const name of (await listDir(join(PLUGINS_DIR, cat))).sort()) {
      if (onlyId && onlyId !== name) continue;
      const metaPath = join(PLUGINS_DIR, cat, name, 'plugin.json');
      if (!await exists(metaPath)) continue;
      const meta = JSON.parse(await readFile(metaPath, 'utf8'));
      if (meta.engine !== 'native') continue;
      total++;
      try {
        const { sz } = await compileOne(cat, name, meta);
        console.log(`  ok  ${cat}/${name}  [${sz} KB]`);
      } catch (e) {
        fails++;
        console.error(`  FAIL ${cat}/${name}: ${e.message}`);
      }
    }
  }
  if (total === 0) {
    console.log(onlyId
      ? `No native plugin found with id "${onlyId}".`
      : 'No native plugins to build.');
  }
  if (fails > 0) process.exit(1);
}

await main();
