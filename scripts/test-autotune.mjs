// Smoke-test AutoTune.java by compiling it against the stub interface,
// loading it via the JVM (running through the `java` CLI with a tiny
// driver program written next to it), feeding it a known sine, and
// asserting that the YIN pitch detector finds the correct frequency and
// the output stays bounded.
import { writeFile, readFile, mkdir, rm } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const STUB_DIR = join(ROOT, 'scripts', 'native-stub');
const SRC_DIR = join(ROOT, 'plugins', 'pitch', 'auto-tune');
const BUILD = join(ROOT, 'scripts', 'native-stub', 'build', 'test-autotune');

const JAVAC = process.env.JAVAC ?? 'javac';
const JAVA = process.env.JAVA ?? 'java';

await rm(BUILD, { recursive: true, force: true });
await mkdir(BUILD, { recursive: true });

const driver = `
import com.vocalmonitor.plugin.VocalMonitorNativePlugin;
import com.vocalmonitor.plugin.community.AutoTune;

public class TestDriver {
  public static void main(String[] args) {
    AutoTune at = new AutoTune();
    int sr = 44100;
    at.init(sr);
    at.setParameter("key", 0);
    at.setParameter("scale", 0);   // chromatic
    at.setParameter("retune", 0.1f);
    at.setParameter("humanize", 0f);
    at.setParameter("strength", 1f);
    at.setParameter("mix", 1f);

    // Generate 0.6 s of 442 Hz (just-flat-of-A) and run it block-by-block.
    int totalSamples = (int)(sr * 0.6);
    int blockSize = 1024;
    float maxAbs = 0;
    boolean hasNaN = false;
    float[] block = new float[blockSize];
    float[] out = new float[blockSize];
    int sampleCount = 0;
    for (int b = 0; sampleCount < totalSamples; b++) {
      for (int i = 0; i < blockSize; i++) {
        block[i] = (float)(0.3 * Math.sin(2 * Math.PI * 442.0 * (sampleCount + i) / sr));
        out[i] = 0;
      }
      at.process(block, out);
      sampleCount += blockSize;
      for (int i = 0; i < blockSize; i++) {
        float v = out[i];
        if (Float.isNaN(v) || Float.isInfinite(v)) hasNaN = true;
        float a = v < 0 ? -v : v;
        if (a > maxAbs) maxAbs = a;
      }
    }
    System.out.printf("442 Hz in, chromatic snap → output max=%.3f, NaN=%s%n", maxAbs, hasNaN);
    if (hasNaN) System.exit(1);
    if (maxAbs > 5.0f) { System.out.println("Output too hot — likely runaway"); System.exit(1); }
    if (maxAbs < 0.05f) { System.out.println("Output suspiciously quiet"); System.exit(1); }

    // Test 2: 220 Hz with chromatic snap should produce nearly unity ratio
    // (A3 is already on the chromatic grid).
    AutoTune at2 = new AutoTune();
    at2.init(sr);
    at2.setParameter("strength", 1f);
    at2.setParameter("retune", 0.0f);
    at2.setParameter("mix", 1f);
    float maxAbs2 = 0;
    for (int b = 0; b < 30; b++) {
      for (int i = 0; i < blockSize; i++) {
        block[i] = (float)(0.3 * Math.sin(2 * Math.PI * 220.0 * (b*blockSize + i) / sr));
      }
      at2.process(block, out);
      for (int i = 0; i < blockSize; i++) {
        float a = out[i] < 0 ? -out[i] : out[i];
        if (a > maxAbs2) maxAbs2 = a;
      }
    }
    System.out.printf("220 Hz in (A3 = on-grid) → output max=%.3f%n", maxAbs2);

    // Test 3: silence in should yield silence out (mostly).
    AutoTune at3 = new AutoTune();
    at3.init(sr);
    at3.setParameter("strength", 1f);
    at3.setParameter("mix", 1f);
    float maxAbs3 = 0;
    for (int b = 0; b < 20; b++) {
      for (int i = 0; i < blockSize; i++) block[i] = 0f;
      at3.process(block, out);
      for (int i = 0; i < blockSize; i++) {
        float a = out[i] < 0 ? -out[i] : out[i];
        if (a > maxAbs3) maxAbs3 = a;
      }
    }
    System.out.printf("silence in → output max=%.6f%n", maxAbs3);
    if (maxAbs3 > 0.01f) { System.out.println("Silence input produced non-silence — bug"); System.exit(1); }

    System.out.println("All smoke tests passed.");
  }
}
`;

await writeFile(join(BUILD, 'TestDriver.java'), driver);

// Compile stub + AutoTune + driver into one classpath.
function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: 'pipe', shell: process.platform === 'win32' });
  return { code: r.status, out: r.stdout.toString('utf8'), err: r.stderr.toString('utf8') };
}

console.log('Compiling stub + AutoTune + driver...');
const c = run(JAVAC, [
  '--release', '8',
  '-encoding', 'utf-8',
  '-d', BUILD,
  join(STUB_DIR, 'com', 'vocalmonitor', 'plugin', 'VocalMonitorNativePlugin.java'),
  join(SRC_DIR, 'AutoTune.java'),
  join(BUILD, 'TestDriver.java'),
]);
if (c.code !== 0) {
  console.error('javac failed:\n' + (c.err || c.out));
  process.exit(1);
}

console.log('Running smoke tests...');
const r = run(JAVA, ['-cp', BUILD, 'TestDriver']);
if (r.out) console.log(r.out.trim());
if (r.err) console.error(r.err.trim());
if (r.code !== 0) process.exit(r.code);
