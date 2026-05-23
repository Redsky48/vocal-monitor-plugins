// Build source-plugin fat-dexes — separate pipeline from build-native.mjs
// because source plugins (currently just youtube-source) bundle a real
// upstream library (NewPipeExtractor + its transitive deps) into the
// produced .dex. Whereas native audio plugins ship one-class-per-.dex,
// source plugins ship a 3-5 MB .dex that contains the upstream + the
// thin VocalMonitorSourcePlugin wrapper.
//
// Pipeline per plugin under plugins/source/<id>/:
//   1. Read plugin.json — require engine === "source" + "upstream".library.
//   2. Resolve the upstream JAR + transitive deps via Gradle (one-time-
//      per-version cache under .gradle/).
//   3. javac the plugin .java against:
//        - scripts/source-stub/   (host interfaces — replaced at runtime)
//        - the resolved upstream JARs (compile + runtime classpath)
//   4. d8 the union of (plugin .class files) + (upstream + transitive
//      .class files) into <id>.dex.
//   5. Drop <id>.dex next to plugin.json.
//
// Usage:
//   node scripts/build-source.mjs               # rebuild every source plugin
//   node scripts/build-source.mjs <plugin-id>   # rebuild one
//   node scripts/build-source.mjs --check-only  # exit 0 if all are up to date
//
// Required environment:
//   JAVAC                      javac on PATH (Java 17+)
//   DEX_TOOL                   d8 (Android SDK build-tools)
//   ANDROID_HOME or ANDROID_SDK_ROOT  used to find d8 when DEX_TOOL is unset
//
// CI:
//   The youtube-source-build workflow in .github/workflows/ runs this
//   nightly + on push to plugins/source/**; it also signs the produced
//   .dex with the ed25519 key held in the PLUGIN_SIGNING_KEY secret.

import { readFile, readdir, mkdir, rm, writeFile, access, copyFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE_PLUGINS_DIR = join(ROOT, 'plugins', 'source');
const STUB_DIR = join(ROOT, 'scripts', 'source-stub');
const BUILD_DIR = join(ROOT, 'scripts', 'source-build');
const CACHE_DIR = join(ROOT, '.gradle-cache');

const D8 = process.env.DEX_TOOL ?? resolveD8();
const JAVAC = process.env.JAVAC ?? 'javac';

function resolveD8() {
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  if (!sdk) return process.platform === 'win32' ? 'd8.bat' : 'd8';
  // Pick whatever build-tools version is installed; the script doesn't
  // depend on a specific one.
  const tools = join(sdk, 'build-tools');
  try {
    const versions = require('node:fs').readdirSync(tools).sort().reverse();
    if (versions.length === 0) return process.platform === 'win32' ? 'd8.bat' : 'd8';
    return join(tools, versions[0], process.platform === 'win32' ? 'd8.bat' : 'd8');
  } catch {
    return process.platform === 'win32' ? 'd8.bat' : 'd8';
  }
}

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, {
    stdio: 'pipe',
    shell: process.platform === 'win32',
    ...opts,
  });
  return {
    code: r.status,
    out: (r.stdout ?? Buffer.from('')).toString('utf8'),
    err: (r.stderr ?? Buffer.from('')).toString('utf8'),
  };
}

async function exists(p) { try { await access(p); return true; } catch { return false; } }

async function listDir(dir) {
  try {
    const ents = await readdir(dir, { withFileTypes: true });
    return ents.filter(e => e.isDirectory()).map(e => e.name);
  } catch { return []; }
}

// ── Stub compile (shared across plugins) ──────────────────────────
async function compileStub() {
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
  return out;
}

// ── Resolve upstream JARs via a throwaway Gradle project ──────────
//
// Why Gradle and not direct Maven CLI? NewPipeExtractor is published on
// JitPack (com.github.TeamNewPipe:NewPipeExtractor:vX.Y.Z); JitPack's
// transitive dep resolution + classifier handling lines up cleanly only
// when a real build tool drives it. Gradle is also already part of the
// Android SDK story the registry assumes.
async function resolveUpstream(pluginDir, upstreamCoord) {
  const projDir = join(CACHE_DIR, 'resolver', upstreamCoord.replace(/[:/]/g, '_'));
  await mkdir(projDir, { recursive: true });

  const settingsKts = `rootProject.name = "upstream-resolver"\n`;
  await writeFile(join(projDir, 'settings.gradle.kts'), settingsKts);

  const buildKts = `
plugins { id("java-library") }
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
dependencies {
    implementation("${upstreamCoord}")
}
tasks.register<Copy>("collectJars") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("collected-jars"))
}
`;
  await writeFile(join(projDir, 'build.gradle.kts'), buildKts);

  const r = run(process.platform === 'win32' ? 'gradle.bat' : 'gradle',
    ['-p', projDir, '--no-daemon', '-q', 'collectJars']);
  if (r.code !== 0) throw new Error(`gradle resolve failed:\n${r.err || r.out}`);

  const collected = join(projDir, 'build', 'collected-jars');
  const jars = (await readdir(collected)).filter(n => n.endsWith('.jar'))
    .map(n => join(collected, n));
  if (jars.length === 0) throw new Error('no jars collected from gradle');
  return jars;
}

// ── Per-plugin build ──────────────────────────────────────────────
async function compileOne(name, meta, stubOut) {
  if (!meta.className) throw new Error('plugin.json missing className');
  if (!meta.upstream || !meta.upstream.library) {
    throw new Error('plugin.json missing upstream.library');
  }
  const folder = join(SOURCE_PLUGINS_DIR, name);
  const simpleName = meta.className.split('.').pop();
  const packagePath = meta.className.substring(0, meta.className.lastIndexOf('.'))
    .replace(/\./g, '/');
  const javaSrc = join(folder, `${simpleName}.java`);
  if (!await exists(javaSrc)) throw new Error(`no source at ${javaSrc}`);

  // `library` is the bare Maven coordinate (group:artifact); `version`
  // is bumped by the auto-update workflow. The actual Gradle resolution
  // coord is the two joined with a colon. We require version to be set
  // — the workflow always populates it before invoking this script.
  if (!meta.upstream.version) {
    throw new Error('plugin.json missing upstream.version (workflow bump step should set this)');
  }
  const coord = `${meta.upstream.library}:${meta.upstream.version}`;
  console.log(`  resolving ${coord} …`);
  const upstreamJars = await resolveUpstream(folder, coord);
  console.log(`    → ${upstreamJars.length} jars on classpath`);

  const classOut = join(BUILD_DIR, name);
  await rm(classOut, { recursive: true, force: true });
  await mkdir(classOut, { recursive: true });
  const cp = [stubOut, ...upstreamJars].join(process.platform === 'win32' ? ';' : ':');
  const r1 = run(JAVAC, [
    '--release', '8',
    '-encoding', 'utf-8',
    '-cp', cp,
    '-d', classOut,
    javaSrc,
  ]);
  if (r1.code !== 0) throw new Error(`javac failed:\n${r1.err || r1.out}`);

  // d8 inputs: plugin .class files (recursively) + every upstream JAR.
  // d8 dexes JAR contents directly so we don't have to unpack them.
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
  const r2 = run(D8, [
    '--min-api', '26',
    '--output', dexTmp,
    ...classFiles,
    ...upstreamJars,
  ]);
  if (r2.code !== 0) throw new Error(`d8 failed:\n${r2.err || r2.out}`);

  const produced = join(dexTmp, 'classes.dex');
  if (!await exists(produced)) throw new Error('d8 did not produce classes.dex');
  const dexBytes = await readFile(produced);
  const dexOut = join(folder, `${name}.dex`);
  await writeFile(dexOut, dexBytes);
  return { dexOut, size: dexBytes.length };
}

async function main() {
  const onlyId = process.argv[2] && !process.argv[2].startsWith('--')
    ? process.argv[2] : null;
  const checkOnly = process.argv.includes('--check-only');

  if (checkOnly) {
    // CI smoke test — just verify every source plugin has a .dex
    // committed alongside its plugin.json. Doesn't trigger a build.
    let missing = 0;
    for (const name of (await listDir(SOURCE_PLUGINS_DIR)).sort()) {
      const dex = join(SOURCE_PLUGINS_DIR, name, `${name}.dex`);
      if (!await exists(dex)) { console.error(`  MISSING ${name}.dex`); missing++; }
    }
    process.exit(missing > 0 ? 1 : 0);
  }

  await mkdir(BUILD_DIR, { recursive: true });
  const stubOut = await compileStub();

  let total = 0, fails = 0;
  for (const name of (await listDir(SOURCE_PLUGINS_DIR)).sort()) {
    if (onlyId && onlyId !== name) continue;
    const metaPath = join(SOURCE_PLUGINS_DIR, name, 'plugin.json');
    if (!await exists(metaPath)) continue;
    const meta = JSON.parse(await readFile(metaPath, 'utf8'));
    if (meta.engine !== 'source') continue;
    total++;
    try {
      const { size } = await compileOne(name, meta, stubOut);
      console.log(`  ok ${name}  [${(size / 1024 / 1024).toFixed(2)} MB]`);
    } catch (e) {
      fails++;
      console.error(`  FAIL ${name}: ${e.message}`);
    }
  }
  if (total === 0) console.log('No source plugins to build.');
  if (fails > 0) process.exit(1);
}

await main();
