#!/usr/bin/env node
// Smoke-test every native plugin in plugins/:
//
//   1. Walk plugins/<cat>/<id>/*.dex
//   2. Compose a tiny Java test driver that, for each plugin:
//        a. loads the .class from per-plugin build dir
//        b. instantiates via the no-arg constructor
//        c. calls init(44100)
//        d. pushes a 256-sample silent block through process()
//        e. pushes a 256-sample sinewave block through process()
//        f. NaN / Inf check on the output
//   3. Run the driver; collect per-plugin pass/fail; emit a summary.
//
// Catches:
//   - NPE in init()
//   - process() that NaNs / Infs on silence (uninitialised state)
//   - process() that explodes on a normal-amplitude input
//   - constructor that throws (missing no-arg constructor, etc.)
//
// What it doesn't catch: visual plugins' render() — that needs a
// real PluginCanvas adapter and is exercised by the DAW + test-app
// on every launch.  This script is just the audio-thread sanity net.
//
// Usage:
//   node scripts/smoke-test.mjs          # test every plugin
//   node scripts/smoke-test.mjs <id>     # test one plugin by id
import { readFile, readdir, stat, writeFile, mkdir } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS_DIR = join(ROOT, 'plugins');
const STUB_DIR = join(ROOT, 'scripts', 'native-stub');
const BUILD_ROOT = join(STUB_DIR, 'build');
const DRIVER_DIR = join(STUB_DIR, 'build', 'smoke-driver');

async function listDir(dir) {
  try { return (await readdir(dir, { withFileTypes: true })).filter(e => e.isDirectory()).map(e => e.name); }
  catch { return []; }
}
async function exists(p) {
  try { await stat(p); return true; } catch { return false; }
}

async function collectPlugins(filterId) {
  const out = [];
  for (const cat of await listDir(PLUGINS_DIR)) {
    for (const id of await listDir(join(PLUGINS_DIR, cat))) {
      if (filterId && id !== filterId) continue;
      const manifestPath = join(PLUGINS_DIR, cat, id, 'plugin.json');
      try {
        const meta = JSON.parse(await readFile(manifestPath, 'utf8'));
        if (meta.engine !== 'native') continue;
        if (meta.draft === true) continue;
        if (!meta.className) continue;
        // Each plugin lives in its own per-id build dir from build-native.mjs.
        const classDir = join(BUILD_ROOT, id);
        if (!(await exists(classDir))) continue;
        out.push({ id, cat, className: meta.className, classDir });
      } catch { /* skip malformed */ }
    }
  }
  return out;
}

function emitDriver(plugins) {
  // The driver is a tiny Java program we compile + run once; it loads
  // every plugin's .class with a URLClassLoader rooted at that plugin's
  // build dir, exercises it, prints OK / FAIL per plugin to stdout.
  const lines = [];
  lines.push('import java.io.File;');
  lines.push('import java.lang.reflect.Method;');
  lines.push('import java.net.URL;');
  lines.push('import java.net.URLClassLoader;');
  lines.push('import com.vocalmonitor.plugin.VocalMonitorNativePlugin;');
  lines.push('');
  lines.push('public class SmokeDriver {');
  lines.push('    public static void main(String[] args) throws Exception {');
  lines.push('        int ok = 0, fail = 0;');
  lines.push('        long start = System.nanoTime();');
  for (const p of plugins) {
    const cd = p.classDir.replace(/\\/g, '\\\\');
    lines.push(`        ok += test("${p.id}", "${cd}", "${p.className}") ? 1 : 0;`);
    lines.push(`        fail += test("${p.id}", "${cd}", "${p.className}") ? 0 : 1;`);
    // Note: above ran test twice — replace with single-call form below.
    lines.pop(); lines.pop();
    lines.push(`        { boolean r = test("${p.id}", "${cd}", "${p.className}"); if (r) ok++; else fail++; }`);
  }
  lines.push('        long ms = (System.nanoTime() - start) / 1_000_000L;');
  lines.push('        System.out.println("---");');
  lines.push('        System.out.println("smoke: ok=" + ok + " fail=" + fail + " in " + ms + "ms");');
  lines.push('        if (fail > 0) System.exit(1);');
  lines.push('    }');
  lines.push('');
  lines.push('    static boolean test(String id, String classDir, String className) {');
  lines.push('        try {');
  lines.push('            URL[] cp = new URL[] { new File(classDir).toURI().toURL() };');
  lines.push('            URLClassLoader cl = new URLClassLoader(cp, SmokeDriver.class.getClassLoader());');
  lines.push('            Class<?> raw = cl.loadClass(className);');
  lines.push('            if (!VocalMonitorNativePlugin.class.isAssignableFrom(raw)) {');
  lines.push('                System.err.println("FAIL " + id + " — class doesn\'t implement VocalMonitorNativePlugin");');
  lines.push('                return false;');
  lines.push('            }');
  lines.push('            VocalMonitorNativePlugin p = (VocalMonitorNativePlugin) raw.getDeclaredConstructor().newInstance();');
  lines.push('            p.init(44100);');
  lines.push('            int N = 256;');
  lines.push('            float[] in = new float[N];');
  lines.push('            float[] out = new float[N];');
  lines.push('            // 1) Silence block — uninit state shouldn\'t produce NaN/Inf.');
  lines.push('            p.process(in, out);');
  lines.push('            checkBlock(id, out, "silence");');
  lines.push('            // 2) 220 Hz sine at 0.5 amplitude.');
  lines.push('            for (int i = 0; i < N; i++) in[i] = (float)(0.5 * Math.sin(2.0 * Math.PI * 220.0 * i / 44100.0));');
  lines.push('            p.process(in, out);');
  lines.push('            checkBlock(id, out, "sine");');
  lines.push('            return true;');
  lines.push('        } catch (Throwable t) {');
  lines.push('            System.err.println("FAIL " + id + " — " + t.getClass().getSimpleName() + ": " + t.getMessage());');
  lines.push('            return false;');
  lines.push('        }');
  lines.push('    }');
  lines.push('');
  lines.push('    static void checkBlock(String id, float[] out, String stage) {');
  lines.push('        for (int i = 0; i < out.length; i++) {');
  lines.push('            float v = out[i];');
  lines.push('            if (Float.isNaN(v) || Float.isInfinite(v)) {');
  lines.push('                throw new RuntimeException("NaN/Inf at " + stage + "[" + i + "]: " + v);');
  lines.push('            }');
  lines.push('        }');
  lines.push('    }');
  lines.push('}');
  return lines.join('\n');
}

function run(cmd, args) {
  const r = spawnSync(cmd, args, {
    stdio: 'inherit', shell: process.platform === 'win32',
  });
  return r.status;
}

async function main() {
  const filterId = process.argv[2];
  const plugins = await collectPlugins(filterId);
  if (plugins.length === 0) {
    if (filterId) console.error(`No plugin matched "${filterId}".  Run build-native first?`);
    else console.error('No plugins found.  Run build-native first?');
    process.exit(1);
  }
  console.log(`smoke-testing ${plugins.length} plugin(s)…`);
  // Build the stub interfaces so the driver can reference them.
  const stubBuild = join(BUILD_ROOT, 'stub');
  if (!(await exists(join(stubBuild, 'com/vocalmonitor/plugin/VocalMonitorNativePlugin.class')))) {
    console.error('Stub interfaces not compiled — run build-native first.');
    process.exit(1);
  }
  await mkdir(DRIVER_DIR, { recursive: true });
  const driverSrc = join(DRIVER_DIR, 'SmokeDriver.java');
  await writeFile(driverSrc, emitDriver(plugins));
  // Compile.
  const javac = process.env.JAVAC ?? 'javac';
  if (run(javac, [
    '--release', '8', '-encoding', 'utf-8',
    '-cp', stubBuild,
    '-d', DRIVER_DIR,
    driverSrc,
  ]) !== 0) {
    console.error('Driver compile failed.');
    process.exit(1);
  }
  // Run.
  const java = process.env.JAVA ?? 'java';
  const code = run(java, [
    '-cp', `${DRIVER_DIR}${process.platform === 'win32' ? ';' : ':'}${stubBuild}`,
    'SmokeDriver',
  ]);
  process.exit(code);
}

main().catch(e => { console.error(e); process.exit(1); });
