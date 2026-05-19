// Generic dependency discovery.
//
// Auto-detects the ecosystems present at `root` and emits a list of
// dependency records:
//
//   { ecosystem, name, version, file, line, declaredIn, parentKey?,
//     depth, kind?, direct: bool }
//
// `parentKey` is the `${ecosystem}|${name}|${version}` of the node
// that pulls this one in. `depth` is the number of edges from the
// nearest direct dependency (0 for direct deps).
//
// Transitive resolution is supported for npm (package-lock.json,
// pnpm-lock.yaml, yarn.lock) without running any package manager.
// Maven transitives require Gradle / Maven to run, so we record only
// the direct coordinates declared in build files.
//
// Pure stdlib — no third-party YAML / TOML / lockfile libraries are
// pulled in, so the auditor itself stays supply-chain-safe.

import { readFile, readdir, stat } from 'node:fs/promises';
import { join, relative, basename } from 'node:path';

const SKIP_DIRS = new Set([
  '.git', '.gradle', '.kotlin', 'build', 'node_modules', 'dist', 'target',
  '.idea', '.vscode', '.next', '.nuxt', '.svelte-kit', 'coverage',
  '.venv', 'venv', '__pycache__',
]);

async function walk(dir, out = []) {
  let entries;
  try { entries = await readdir(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (SKIP_DIRS.has(e.name)) continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) await walk(p, out);
    else out.push(p);
  }
  return out;
}

function lineOf(src, idx) {
  return src.slice(0, idx).split('\n').length;
}

// ─── Gradle (Kotlin DSL) ──────────────────────────────────────────────

function parseGradleKts(src, file) {
  const deps = [];
  const coordRx = /["']([\w.-]+):([\w.-]+):([\w.+-]+)["']/g;
  let m;
  while ((m = coordRx.exec(src)) !== null) {
    if (m[1] === 'com.vocalmonitor') continue;
    deps.push({
      ecosystem: 'Maven',
      name: `${m[1]}:${m[2]}`,
      version: m[3],
      file,
      line: lineOf(src, m.index),
      direct: true,
      depth: 0,
    });
  }
  const pluginRx = /(?:kotlin\(["']([\w.-]+)["']\)|id\(["']([\w.-]+)["']\))\s+version\s+["']([\w.+-]+)["']/g;
  while ((m = pluginRx.exec(src)) !== null) {
    deps.push({
      ecosystem: 'Maven',
      name: m[2] || `org.jetbrains.kotlin.${m[1]}`,
      version: m[3],
      file,
      line: lineOf(src, m.index),
      direct: true,
      depth: 0,
      kind: 'gradle-plugin',
    });
  }
  return deps;
}

// ─── Gradle (Groovy DSL) ──────────────────────────────────────────────

function parseGradleGroovy(src, file) {
  const deps = [];
  // Matches `implementation 'group:artifact:version'` and `implementation "group:artifact:version"`
  const rx = /(?:implementation|api|compileOnly|runtimeOnly|testImplementation)\s*[\(\s]\s*["']([\w.-]+):([\w.-]+):([\w.+-]+)["']/g;
  let m;
  while ((m = rx.exec(src)) !== null) {
    deps.push({
      ecosystem: 'Maven',
      name: `${m[1]}:${m[2]}`,
      version: m[3],
      file,
      line: lineOf(src, m.index),
      direct: true,
      depth: 0,
    });
  }
  return deps;
}

// ─── Gradle wrapper ───────────────────────────────────────────────────

function parseGradleWrapper(src, file) {
  const m = src.match(/distributionUrl=.*gradle-([\d.]+)-/);
  return m ? [{
    ecosystem: 'Gradle',
    name: 'gradle',
    version: m[1],
    file,
    line: 1,
    direct: true,
    depth: 0,
  }] : [];
}

// ─── GitHub Actions ───────────────────────────────────────────────────

function parseWorkflow(src, file) {
  const deps = [];
  const rx = /^\s*-?\s*uses:\s*([\w.-]+\/[\w.-]+)@([\w.-]+)/gm;
  let m;
  while ((m = rx.exec(src)) !== null) {
    deps.push({
      ecosystem: 'GitHub Actions',
      name: m[1],
      version: m[2],
      file,
      line: lineOf(src, m.index),
      direct: true,
      depth: 0,
    });
  }
  return deps;
}

// ─── npm (package.json — direct only) ─────────────────────────────────

function parsePackageJson(src, file) {
  let pkg;
  try { pkg = JSON.parse(src); } catch { return []; }
  const out = [];
  for (const field of ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies']) {
    if (!pkg[field]) continue;
    for (const [name, version] of Object.entries(pkg[field])) {
      out.push({
        ecosystem: 'npm',
        name,
        version: String(version).replace(/^[\^~>=<]+/, ''),
        file,
        line: 1,
        direct: true,
        depth: 0,
        kind: field === 'devDependencies' ? 'dev' : undefined,
      });
    }
  }
  return out;
}

// ─── npm (package-lock.json — transitive) ─────────────────────────────

function parsePackageLock(src, file, rootDirectSet) {
  let lock;
  try { lock = JSON.parse(src); } catch { return []; }
  const out = [];
  // npm v3 lockfile uses `packages` keyed by relative install path.
  if (lock.packages) {
    for (const [path, info] of Object.entries(lock.packages)) {
      if (!path || path === '') continue;
      if (info.link) continue;
      // Path like "node_modules/foo" or "node_modules/foo/node_modules/bar".
      const segs = path.split('node_modules/').slice(1);
      if (segs.length === 0) continue;
      const name = segs[segs.length - 1].replace(/\/$/, '');
      if (!info.version) continue;
      // Depth: number of node_modules segments minus 1.
      const depth = segs.length - 1;
      const directKey = `npm|${name}|${info.version}`;
      out.push({
        ecosystem: 'npm',
        name,
        version: info.version,
        file,
        line: 1,
        direct: depth === 0 && rootDirectSet.has(name),
        depth,
        installPath: path,
        resolved: info.resolved,
        integrity: info.integrity,
      });
    }
  }
  return out;
}

// ─── pnpm-lock.yaml — naive transitive scan (no full YAML parser) ─────

function parsePnpmLock(src, file) {
  const out = [];
  // pnpm lock has lines like:  '/foo@1.2.3': or  /foo@1.2.3:
  // We only extract name@version pairs.
  const rx = /^\s+['"]?\/?([@\w./-]+)@([\d][\w.+-]*)['"]?:/gm;
  let m;
  const seen = new Set();
  while ((m = rx.exec(src)) !== null) {
    const name = m[1];
    const version = m[2];
    const key = `${name}@${version}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      ecosystem: 'npm',
      name,
      version,
      file,
      line: lineOf(src, m.index),
      direct: false,
      depth: 1,
    });
  }
  return out;
}

// ─── yarn.lock — naive scan ───────────────────────────────────────────

function parseYarnLock(src, file) {
  const out = [];
  // yarn lock entries look like:
  //   foo@^1.2.3, foo@~1.2.0:
  //     version "1.2.4"
  const blockRx = /^"?([@\w./-]+)@[^\n"]+(?:,\s*"?[@\w./-]+@[^\n"]+)*"?:\s*\n(?:\s+[^\n]+\n)*?\s+version\s+"([^"]+)"/gm;
  let m;
  const seen = new Set();
  while ((m = blockRx.exec(src)) !== null) {
    const key = `${m[1]}@${m[2]}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      ecosystem: 'npm',
      name: m[1],
      version: m[2],
      file,
      line: lineOf(src, m.index),
      direct: false,
      depth: 1,
    });
  }
  return out;
}

// ─── Python ───────────────────────────────────────────────────────────

function parseRequirementsTxt(src, file) {
  const out = [];
  const lines = src.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const ln = lines[i].trim();
    if (!ln || ln.startsWith('#') || ln.startsWith('-')) continue;
    const m = ln.match(/^([\w.-]+)\s*==\s*([\w.+-]+)/);
    if (m) {
      out.push({
        ecosystem: 'PyPI',
        name: m[1],
        version: m[2],
        file,
        line: i + 1,
        direct: true,
        depth: 0,
      });
    }
  }
  return out;
}

// ─── Rust ─────────────────────────────────────────────────────────────

function parseCargoLock(src, file) {
  const out = [];
  const blocks = src.split(/\n(?=\[\[package\]\])/);
  let lineCursor = 1;
  for (const b of blocks) {
    const nameM = b.match(/name\s*=\s*"([^"]+)"/);
    const verM = b.match(/version\s*=\s*"([^"]+)"/);
    if (nameM && verM) {
      out.push({
        ecosystem: 'crates.io',
        name: nameM[1],
        version: verM[1],
        file,
        line: lineCursor,
        direct: false,
        depth: 0,
      });
    }
    lineCursor += b.split('\n').length;
  }
  return out;
}

// ─── Go ───────────────────────────────────────────────────────────────

function parseGoMod(src, file) {
  const out = [];
  const rx = /^\s*([\w./-]+)\s+(v[\w.+-]+)(?:\s+\/\/\s*indirect)?/gm;
  let m;
  while ((m = rx.exec(src)) !== null) {
    if (m[1] === 'go') continue;
    out.push({
      ecosystem: 'Go',
      name: m[1],
      version: m[2],
      file,
      line: lineOf(src, m.index),
      direct: !m[0].includes('indirect'),
      depth: m[0].includes('indirect') ? 1 : 0,
    });
  }
  return out;
}

// ─── orchestrator ─────────────────────────────────────────────────────

export async function detectProject(root) {
  const ecosystems = new Set();
  const probes = [
    ['package.json', 'npm'],
    ['build.gradle.kts', 'gradle'],
    ['build.gradle', 'gradle'],
    ['settings.gradle.kts', 'gradle'],
    ['settings.gradle', 'gradle'],
    ['requirements.txt', 'pypi'],
    ['pyproject.toml', 'pypi'],
    ['Cargo.toml', 'cargo'],
    ['go.mod', 'go'],
    ['pom.xml', 'maven'],
  ];
  for (const [name, eco] of probes) {
    try { await stat(join(root, name)); ecosystems.add(eco); } catch {}
  }
  // Detect github actions
  try { await stat(join(root, '.github', 'workflows')); ecosystems.add('github-actions'); } catch {}
  let projectName = basename(root);
  try {
    const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
    if (pkg.name) projectName = pkg.name;
  } catch {}
  return { root, projectName, ecosystems: [...ecosystems] };
}

export async function discoverDeps(root, opts = {}) {
  const onProgress = opts.onProgress || (() => {});
  const files = await walk(root);
  onProgress({ phase: 'scan', filesScanned: files.length });

  let collected = [];
  const directNpmNames = new Set();

  for (const f of files) {
    const rel = relative(root, f).replace(/\\/g, '/');
    let src;
    try { src = await readFile(f, 'utf8'); } catch { continue; }
    let found = [];
    if (f.endsWith('build.gradle.kts')) {
      found = parseGradleKts(src, rel);
    } else if (f.endsWith('build.gradle')) {
      found = parseGradleGroovy(src, rel);
    } else if (f.endsWith('gradle-wrapper.properties')) {
      found = parseGradleWrapper(src, rel);
    } else if (rel.startsWith('.github/workflows/') && (f.endsWith('.yml') || f.endsWith('.yaml'))) {
      found = parseWorkflow(src, rel);
    } else if (rel === 'package.json' || (rel.endsWith('/package.json') && !rel.includes('node_modules/'))) {
      found = parsePackageJson(src, rel);
      for (const d of found) directNpmNames.add(d.name);
    } else if (rel === 'requirements.txt' || rel.endsWith('/requirements.txt')) {
      found = parseRequirementsTxt(src, rel);
    } else if (rel === 'go.mod' || rel.endsWith('/go.mod')) {
      found = parseGoMod(src, rel);
    } else if (rel === 'Cargo.lock' || rel.endsWith('/Cargo.lock')) {
      found = parseCargoLock(src, rel);
    }
    if (found.length) {
      collected.push(...found);
      onProgress({ phase: 'scan', found: collected.length });
    }
  }

  // Second pass: lockfiles for transitives (need direct npm names known).
  for (const f of files) {
    const rel = relative(root, f).replace(/\\/g, '/');
    let src;
    try { src = await readFile(f, 'utf8'); } catch { continue; }
    let found = [];
    if (rel === 'package-lock.json' || rel.endsWith('/package-lock.json')) {
      found = parsePackageLock(src, rel, directNpmNames);
    } else if (rel === 'pnpm-lock.yaml' || rel.endsWith('/pnpm-lock.yaml')) {
      found = parsePnpmLock(src, rel);
    } else if (rel === 'yarn.lock' || rel.endsWith('/yarn.lock')) {
      found = parseYarnLock(src, rel);
    }
    if (found.length) {
      collected.push(...found);
      onProgress({ phase: 'scan', found: collected.length });
    }
  }

  // Dedup. Prefer the "more direct" record (lower depth, direct=true).
  const byKey = new Map();
  for (const d of collected) {
    const key = `${d.ecosystem}|${d.name}|${d.version}`;
    const prev = byKey.get(key);
    if (!prev) {
      byKey.set(key, { ...d, declaredIn: [`${d.file}:${d.line}`] });
      continue;
    }
    prev.declaredIn.push(`${d.file}:${d.line}`);
    if (d.direct && !prev.direct) prev.direct = true;
    if (d.depth < prev.depth) prev.depth = d.depth;
    if (!prev.installPath && d.installPath) prev.installPath = d.installPath;
  }
  return [...byKey.values()];
}
