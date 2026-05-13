// Smoke-test AutoTune.java by compiling against the stub interface,
// running it through the JVM (via a small Java driver), feeding it a
// known sine, and verifying that the OUTPUT pitch matches the expected
// snap target via a DFT peak sweep — the only test that catches the
// kind of formula bugs that level-only tests would silently pass.
import { writeFile, mkdir, rm } from 'node:fs/promises';
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
import com.vocalmonitor.plugin.community.AutoTune;

public class TestDriver {
  static final int SR = 44100;
  static final int BLOCK = 1024;

  // Single-frequency DFT bin power: |Σ x[n] · e^(-2πi·f·n/sr)|². Sweep
  // frequencies, return the one with the highest power. Robust against
  // amplitude modulation that confuses time-domain autocorrelation.
  static float dftPeakFreq(float[] data, int len, float fMin, float fMax) {
    float bestPower = -1, bestF = fMin;
    float step = 0.25f;
    for (float f = fMin; f <= fMax; f += step) {
      float re = 0, im = 0;
      float w = (float)(2 * Math.PI * f / SR);
      for (int i = 0; i < len; i++) {
        re += data[i] * (float)Math.cos(w * i);
        im -= data[i] * (float)Math.sin(w * i);
      }
      float power = re*re + im*im;
      if (power > bestPower) { bestPower = power; bestF = f; }
    }
    return bestF;
  }

  static float[] render(float inputFreq, float key, float scale, float retune,
                        float strength, float mix, float humanize) {
    AutoTune at = new AutoTune();
    at.init(SR);
    at.setParameter("key", key);
    at.setParameter("scale", scale);
    at.setParameter("retune", retune);
    at.setParameter("strength", strength);
    at.setParameter("mix", mix);
    at.setParameter("humanize", humanize);
    int totalBlocks = 100;
    float[] block = new float[BLOCK];
    float[] out = new float[BLOCK];
    float[] tail = new float[BLOCK * 16];
    int tailWrite = 0;
    int sampleCount = 0;
    for (int b = 0; b < totalBlocks; b++) {
      for (int i = 0; i < BLOCK; i++) {
        block[i] = (float)(0.4 * Math.sin(2 * Math.PI * inputFreq * (sampleCount + i) / SR));
      }
      at.process(block, out);
      sampleCount += BLOCK;
      for (int i = 0; i < BLOCK; i++) {
        tail[tailWrite] = out[i];
        tailWrite = (tailWrite + 1) % tail.length;
        if (Float.isNaN(out[i]) || Float.isInfinite(out[i])) {
          throw new RuntimeException("NaN/Inf at sample " + sampleCount);
        }
      }
    }
    float[] ordered = new float[tail.length];
    for (int i = 0; i < tail.length; i++) ordered[i] = tail[(tailWrite + i) % tail.length];
    return ordered;
  }

  public static void main(String[] args) {
    int passed = 0, failed = 0;

    // ---- Test 1: 220 Hz in, chromatic snap. A3 on grid → expect 220.
    {
      float[] tail = render(220f, 0, 0, 0.0f, 1, 1, 0);
      float f = dftPeakFreq(tail, tail.length, 180f, 280f);
      double cents = 1200 * Math.log(f / 220.0) / Math.log(2);
      boolean ok = Math.abs(cents) < 30;
      System.out.printf("%s 220 Hz → chromatic snap → %.2f Hz (%+.0f cents)%n",
          ok ? "PASS" : "FAIL", f, cents);
      if (ok) passed++; else failed++;
    }

    // ---- Test 2: 442 Hz in, chromatic snap → expect 440 (A4 on grid).
    {
      float[] tail = render(442f, 0, 0, 0.0f, 1, 1, 0);
      float f = dftPeakFreq(tail, tail.length, 380f, 480f);
      double cents = 1200 * Math.log(f / 440.0) / Math.log(2);
      boolean ok = Math.abs(cents) < 30;
      System.out.printf("%s 442 Hz → chromatic snap → %.2f Hz (%+.0f cents from 440)%n",
          ok ? "PASS" : "FAIL", f, cents);
      if (ok) passed++; else failed++;
    }

    // ---- Test 3: 240 Hz → C-major snap → expect B3 (246.94).
    {
      float[] tail = render(240f, 0, 1, 0.0f, 1, 1, 0);
      float f = dftPeakFreq(tail, tail.length, 200f, 290f);
      double cents = 1200 * Math.log(f / 246.94) / Math.log(2);
      boolean ok = Math.abs(cents) < 50;
      System.out.printf("%s 240 Hz → C-major snap → %.2f Hz (%+.0f cents from B3)%n",
          ok ? "PASS" : "FAIL", f, cents);
      if (ok) passed++; else failed++;
    }

    // ---- Test 4: silence in → silence out.
    {
      AutoTune at = new AutoTune();
      at.init(SR);
      at.setParameter("strength", 1f);
      at.setParameter("mix", 1f);
      float[] block = new float[BLOCK];
      float[] out = new float[BLOCK];
      float maxOut = 0;
      for (int b = 0; b < 30; b++) {
        for (int i = 0; i < BLOCK; i++) block[i] = 0;
        at.process(block, out);
        for (int i = 0; i < BLOCK; i++) {
          float a = out[i] < 0 ? -out[i] : out[i];
          if (a > maxOut) maxOut = a;
        }
      }
      boolean ok = maxOut < 0.01f;
      System.out.printf("%s silence in → max out %.6f%n", ok ? "PASS" : "FAIL", maxOut);
      if (ok) passed++; else failed++;
    }

    // ---- Test 5: bypass (strength=0) → input pitch passthrough.
    {
      float[] tail = render(442f, 0, 0, 0.0f, 0, 1, 0);
      float f = dftPeakFreq(tail, tail.length, 380f, 480f);
      double cents = 1200 * Math.log(f / 442.0) / Math.log(2);
      boolean ok = Math.abs(cents) < 30;
      System.out.printf("%s 442 Hz strength=0 → %.2f Hz (%+.0f cents from input)%n",
          ok ? "PASS" : "FAIL", f, cents);
      if (ok) passed++; else failed++;
    }

    // ---- Test 6: 260 Hz → C-major snap → expect C4 (261.63 Hz).
    // Clearly closer to C than B; sharp test of small-shift accuracy.
    {
      float[] tail = render(260f, 0, 1, 0.0f, 1, 1, 0);
      float f = dftPeakFreq(tail, tail.length, 220f, 290f);
      double cents = 1200 * Math.log(f / 261.63) / Math.log(2);
      boolean ok = Math.abs(cents) < 30;
      System.out.printf("%s 260 Hz → C-major snap → %.2f Hz (%+.0f cents from C4)%n",
          ok ? "PASS" : "FAIL", f, cents);
      if (ok) passed++; else failed++;
    }

    // ---- Presets 1..6 — verify each produces non-silent output
    // when fed a 260 Hz input. Each preset should produce SOME audio,
    // not all-silence (which would indicate a broken signal path).
    String[] presetNames = { "(Custom)", "Natural", "Pop", "Hard", "Cher", "Country", "Subtle" };
    for (int presetIdx = 1; presetIdx <= 6; presetIdx++) {
      AutoTune at = new AutoTune();
      at.init(SR);
      at.setParameter("preset", presetIdx);
      at.setParameter("key", 0);
      at.setParameter("scale", 1);  // major
      at.setParameter("mix", 1);
      float[] block = new float[BLOCK];
      float[] out = new float[BLOCK];
      int totalBlocks = 200;  // ~4.6 s
      float[] tail = new float[BLOCK * 16];
      int tailWrite = 0;
      for (int b = 0; b < totalBlocks; b++) {
        for (int i = 0; i < BLOCK; i++) {
          block[i] = (float)(0.4 * Math.sin(2 * Math.PI * 260.0 * (b*BLOCK + i) / SR));
        }
        at.process(block, out);
        for (int i = 0; i < BLOCK; i++) {
          tail[tailWrite] = out[i];
          tailWrite = (tailWrite + 1) % tail.length;
        }
      }
      float[] ordered = new float[tail.length];
      for (int i = 0; i < tail.length; i++) ordered[i] = tail[(tailWrite + i) % tail.length];
      // Compute RMS of last 8 blocks (steady-state output).
      float rms = 0;
      for (int i = 0; i < ordered.length; i++) rms += ordered[i] * ordered[i];
      rms = (float) Math.sqrt(rms / ordered.length);
      // Find the dominant frequency.
      float fDominant = dftPeakFreq(ordered, ordered.length, 200f, 300f);
      boolean nonSilent = rms > 0.05f;
      System.out.printf("%s preset=%d (%s): RMS=%.3f, peak=%.1f Hz%n",
          nonSilent ? "PASS" : "FAIL", presetIdx, presetNames[presetIdx], rms, fDominant);
      if (nonSilent) passed++; else failed++;
    }

    System.out.printf("%n%d passed, %d failed%n", passed, failed);
    System.exit(failed == 0 ? 0 : 1);
  }
}
`;

await writeFile(join(BUILD, 'TestDriver.java'), driver);

function run(cmd, args) {
  const r = spawnSync(cmd, args, { stdio: 'pipe', shell: process.platform === 'win32' });
  return {
    code: r.status,
    out: r.stdout.toString('utf8'),
    err: r.stderr.toString('utf8'),
  };
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

console.log('Running pitch-verifying smoke tests...\n');
const r = run(JAVA, ['-cp', BUILD, 'TestDriver']);
if (r.out) process.stdout.write(r.out);
if (r.err) process.stderr.write(r.err);
process.exit(r.code ?? 0);
