// DepAudit — generic dependency-audit CLI + localhost web viewer.
//
// Works on any project root. Auto-detects npm / Gradle / Maven /
// PyPI / Go / Cargo / GitHub Actions and pulls transitive deps from
// lockfiles when available.
//
// Pure Node stdlib — zero npm dependencies — so the auditor itself
// cannot introduce supply-chain risk.
//
// Usage:
//   node scripts/audit-deps.mjs                         # audit current repo
//   node scripts/audit-deps.mjs --root <path>           # audit any project
//   node scripts/audit-deps.mjs --serve                 # live web viewer on 127.0.0.1:7777
//   node scripts/audit-deps.mjs --html                  # write static HTML report
//   node scripts/audit-deps.mjs --json                  # machine-readable to stdout
//   node scripts/audit-deps.mjs --ai                    # AI explanations (needs ANTHROPIC_API_KEY)
//   node scripts/audit-deps.mjs --fix                   # AI fix proposals
//   node scripts/audit-deps.mjs --fix --apply           # apply AI fixes
//
// Exit codes: 0 = clean, 1 = vulnerabilities found, 2 = tool error.

import { readFile, writeFile, stat } from 'node:fs/promises';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { webcrypto as crypto } from 'node:crypto';
import { discoverDeps, detectProject } from './audit/discover.mjs';
import { queryAll } from './audit/osv.mjs';
import { explainVulnerability, planFix, aiModels } from './audit/ai.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const WEB_DIR = join(SCRIPT_DIR, 'audit', 'web');
const DEFAULT_ROOT = resolve(SCRIPT_DIR, '..');

const rawArgs = process.argv.slice(2);
const args = new Set(rawArgs);

function flagValue(name, fallback) {
  const m = rawArgs.find(a => a.startsWith(name + '='));
  if (m) return m.slice(name.length + 1);
  const i = rawArgs.indexOf(name);
  if (i >= 0 && i + 1 < rawArgs.length) return rawArgs[i + 1];
  return fallback;
}

let ROOT = resolve(flagValue('--root', DEFAULT_ROOT));
const PORT = parseInt(flagValue('--port', '7777'), 10);
const useAi = args.has('--ai') || args.has('--fix');
const fixMode = args.has('--fix');
const applyFix = args.has('--apply');
const jsonOnly = args.has('--json');
const htmlOnly = args.has('--html');
const serveMode = args.has('--serve');
const verbose = args.has('-v') || args.has('--verbose');

const log = (...m) => jsonOnly ? null : console.log(...m);
const warn = (...m) => console.error(...m);

function color(text, c) {
  if (jsonOnly || !process.stdout.isTTY) return text;
  const codes = { red: 31, green: 32, yellow: 33, cyan: 36, gray: 90, bold: 1 };
  return `\x1b[${codes[c] || 0}m${text}\x1b[0m`;
}

// ─── core pipeline ────────────────────────────────────────────────────

async function runAudit({ onProgress = () => {}, onDep = () => {}, onVuln = () => {} } = {}) {
  const project = await detectProject(ROOT);
  onProgress({ phase: 'detect', project });

  const deps = await discoverDeps(ROOT, {
    onProgress: (p) => onProgress({ phase: 'scan', ...p }),
  });
  // Emit each discovered dep immediately (for SSE).
  for (const d of deps) onDep(d);

  onProgress({ phase: 'osv-start', count: deps.length });
  const withVulns = await queryAll(deps, {
    onProgress: (p) => onProgress({ phase: 'osv', ...p }),
  });

  // Pair vuln results back to depKey and emit per-dep updates.
  for (const d of withVulns) {
    onVuln({
      key: `${d.ecosystem}|${d.name}|${d.version}`,
      dep: d.name + '@' + d.version,
      vulnerabilities: d.vulnerabilities,
      severity: d.severity,
    });
  }

  const sources = sourcesUsed({ project, depsByEco: countByEco(withVulns) });
  return { project, deps: withVulns, sources };
}

function countByEco(deps) {
  const c = {};
  for (const d of deps) c[d.ecosystem] = (c[d.ecosystem] || 0) + 1;
  return c;
}

function sourcesUsed({ project, depsByEco }) {
  const ecoSummary = Object.entries(depsByEco).map(([k, v]) => `${k}:${v}`).join(', ') || 'none';
  return [
    {
      name: 'OSV.dev',
      used: true,
      detail: `Open Source Vulnerability database — queried for ${ecoSummary}.`,
      url: 'https://osv.dev',
    },
    {
      name: 'GitHub Advisory Database',
      used: true,
      detail: 'OSV ingests GHSA — every GitHub security advisory is included in the OSV query above.',
      url: 'https://github.com/advisories',
    },
    {
      name: 'NVD / CVE',
      used: true,
      detail: 'OSV cross-references CVE IDs and CVSS scores from the National Vulnerability Database.',
      url: 'https://nvd.nist.gov',
    },
    {
      name: 'Local lockfiles',
      used: true,
      detail: `Parsed package-lock.json / pnpm-lock.yaml / yarn.lock / Cargo.lock / go.mod / requirements.txt for transitives. Detected ecosystems: ${project.ecosystems.join(', ') || 'none'}.`,
    },
    {
      name: 'Anthropic Claude API',
      used: useAi,
      detail: useAi
        ? `Used model: ${aiModels.fast} (explain) ${fixMode ? '+ ' + aiModels.reasoning + ' (fix)' : ''}`
        : 'Pass --ai or --fix and export ANTHROPIC_API_KEY to enable.',
    },
  ];
}

// ─── AI extension ─────────────────────────────────────────────────────

async function attachAi(report) {
  if (!useAi) return report;
  if (!process.env.ANTHROPIC_API_KEY) {
    warn(color('! ANTHROPIC_API_KEY not set — skipping AI step.', 'yellow'));
    return report;
  }
  const ctx =
    `${report.project.projectName}: ecosystems=${report.project.ecosystems.join(',')}. ` +
    `Direct deps=${report.deps.filter(d => d.direct).length}, transitives=${report.deps.filter(d => !d.direct).length}.`;
  for (const d of report.deps) {
    if (!d.vulnerabilities?.length) continue;
    try {
      d.aiExplanation = await explainVulnerability(d, ctx);
    } catch (e) {
      warn(color('  ! AI explain failed for ' + d.name + ': ' + e.message, 'yellow'));
    }
    if (fixMode) {
      try {
        const [rel, lineStr] = (d.declaredIn?.[0] || ':1').split(':');
        if (rel) {
          const snippet = await readFileLine(rel, parseInt(lineStr, 10));
          d.fixPlan = await planFix(d, snippet, ctx);
          if (applyFix) {
            d.fixApplied = await applyFixPlan(rel, d.fixPlan);
          }
        }
      } catch (e) {
        warn(color('  ! AI fix failed for ' + d.name + ': ' + e.message, 'yellow'));
      }
    }
  }
  return report;
}

async function readFileLine(rel, line) {
  const src = await readFile(join(ROOT, rel), 'utf8');
  return src.split('\n')[line - 1] || '';
}

async function applyFixPlan(rel, plan) {
  const full = join(ROOT, rel);
  const src = await readFile(full, 'utf8');
  if (!src.includes(plan.oldString)) return { ok: false, reason: 'oldString not found' };
  const matches = src.split(plan.oldString).length - 1;
  if (matches > 1) return { ok: false, reason: `${matches} ambiguous matches` };
  await writeFile(full, src.replace(plan.oldString, plan.newString));
  return { ok: true };
}

// ─── HTML rendering ───────────────────────────────────────────────────

function safeJsonEmbed(obj) {
  return JSON.stringify(obj).replace(/</g, '\\u003c');
}

function buildCsp(nonce, extra = {}) {
  const parts = {
    'default-src': "'none'",
    'script-src': `'nonce-${nonce}'`,
    'style-src': `'nonce-${nonce}'`,
    'img-src': 'data:',
    'connect-src': "'none'",
    'base-uri': "'none'",
    'form-action': "'none'",
    'frame-ancestors': "'none'",
    ...extra,
  };
  return Object.entries(parts).map(([k, v]) => `${k} ${v}`).join('; ');
}

async function renderPage(bootstrap) {
  const [shell, css, shimJs, egJs, appJs] = await Promise.all([
    readFile(join(WEB_DIR, 'index.html'), 'utf8'),
    readFile(join(WEB_DIR, 'styles.css'), 'utf8'),
    readFile(join(WEB_DIR, 'ad-shim.js'), 'utf8'),
    readFile(join(WEB_DIR, 'event-graph.js'), 'utf8'),
    readFile(join(WEB_DIR, 'app.js'), 'utf8'),
  ]);
  const nonce = Buffer.from(crypto.getRandomValues(new Uint8Array(16))).toString('base64');
  // Live mode needs SSE on /events — allow connect-src for self.
  const csp = bootstrap.live
    ? buildCsp(nonce, { 'connect-src': "'self'" })
    : buildCsp(nonce);
  const html = shell
    .replaceAll('{{CSP}}', csp)
    .replaceAll('{{NONCE}}', nonce)
    .replaceAll('{{PROJECT}}', escapeHtml(bootstrap.project || ''))
    .replace('{{CSS}}', css)
    .replace('{{SHIM_JS}}', shimJs)
    .replace('{{EG_JS}}', egJs)
    .replace('{{APP_JS}}', appJs)
    .replace('{{DATA}}', safeJsonEmbed(bootstrap));
  return { html, csp };
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[c]);
}

// ─── native folder picker (OS dialog) ─────────────────────────────────

// Returns the picked absolute path, or null if the user cancelled / no
// dialog tool available on this platform. Spawned as a child process so
// it can't lock up the HTTP server thread.
function pickFolderNative(startingPath) {
  return new Promise((resolveOuter) => {
    let cmd, args, parseStdout;
    if (process.platform === 'win32') {
      // Shell.Application COM works from STA-less PowerShell and
      // returns the selected folder's full path. Single-quoted
      // string passed via -Command — escape internal single quotes.
      const startArg = (startingPath || '').replace(/'/g, "''");
      const ps =
        `$ErrorActionPreference='Stop';` +
        `$shell = New-Object -ComObject Shell.Application;` +
        `$f = $shell.BrowseForFolder(0,'Select project folder for DepAudit',0,'${startArg}');` +
        `if ($f) { Write-Output $f.Self.Path }`;
      cmd = 'powershell.exe';
      args = ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', ps];
      parseStdout = (s) => s.trim();
    } else if (process.platform === 'darwin') {
      const start = startingPath ? `default location POSIX file "${startingPath.replace(/"/g, '\\"')}"` : '';
      const osa = `try
        set f to POSIX path of (choose folder with prompt "Select project folder for DepAudit" ${start})
        return f
      on error
        return ""
      end try`;
      cmd = 'osascript';
      args = ['-e', osa];
      parseStdout = (s) => s.trim().replace(/\/$/, '');
    } else {
      // Linux: zenity, fall back to kdialog.
      cmd = 'zenity';
      args = ['--file-selection', '--directory', '--title=Select project folder for DepAudit'];
      if (startingPath) args.push('--filename=' + startingPath + '/');
      parseStdout = (s) => s.trim();
    }

    let proc;
    try { proc = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] }); }
    catch (e) { resolveOuter(null); return; }

    let out = '', err = '';
    proc.stdout.on('data', (d) => { out += d.toString(); });
    proc.stderr.on('data', (d) => { err += d.toString(); });
    proc.on('error', () => resolveOuter(null));
    proc.on('close', (code) => {
      const picked = parseStdout(out);
      if (code === 0 && picked) resolveOuter(picked);
      else resolveOuter(null);
    });
  });
}

// ─── live server (SSE) ────────────────────────────────────────────────

async function startLiveServer() {
  const bootstrap = {
    live: true,
    project: (await detectProject(ROOT)).projectName,
    root: ROOT,
    eventsUrl: '/events',
    sources: [],
  };
  const { html, csp } = await renderPage(bootstrap);

  // Origin check — defence-in-depth against another localhost process
  // POSTing to our config endpoint. Browsers send Origin on cross-origin
  // requests; same-origin fetch from our own page sends it too.
  const expectedOrigin = `http://127.0.0.1:${PORT}`;
  function originOk(req) {
    const o = req.headers.origin;
    return !o || o === expectedOrigin || o === `http://localhost:${PORT}`;
  }

  function readBody(req, limit = 4096) {
    return new Promise((resolveBody, rejectBody) => {
      let size = 0;
      const chunks = [];
      req.on('data', c => {
        size += c.length;
        if (size > limit) { rejectBody(new Error('body too large')); req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => resolveBody(Buffer.concat(chunks).toString('utf8')));
      req.on('error', rejectBody);
    });
  }

  return new Promise((resolveSrv, reject) => {
    const server = createServer(async (req, res) => {
      // Whitelist by (method, path).
      if (req.url === '/pick-folder' && req.method === 'POST') {
        if (!originOk(req)) {
          res.writeHead(403, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: 'forbidden origin' }));
          return;
        }
        try {
          // Optional body: { startingPath } — use current ROOT if omitted.
          const raw = await readBody(req).catch(() => '');
          let start = ROOT;
          if (raw) {
            try { const b = JSON.parse(raw); if (b.startingPath) start = String(b.startingPath); } catch {}
          }
          const picked = await pickFolderNative(start);
          res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
          if (picked) {
            const proj = await detectProject(resolve(picked));
            res.end(JSON.stringify({ ok: true, path: picked, projectName: proj.projectName, ecosystems: proj.ecosystems }));
          } else {
            res.end(JSON.stringify({ ok: false, cancelled: true }));
          }
        } catch (e) {
          res.writeHead(500, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: e.message }));
        }
        return;
      }

      if (req.url === '/audit-config' && req.method === 'POST') {
        if (!originOk(req)) {
          res.writeHead(403, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: 'forbidden origin' }));
          return;
        }
        try {
          const raw = await readBody(req);
          const body = JSON.parse(raw);
          const newRoot = resolve(String(body.root || ''));
          const st = await stat(newRoot).catch(() => null);
          if (!st || !st.isDirectory()) {
            res.writeHead(400, { 'content-type': 'application/json' });
            res.end(JSON.stringify({ ok: false, error: 'path is not a directory: ' + newRoot }));
            return;
          }
          ROOT = newRoot;
          const proj = await detectProject(ROOT);
          res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
          res.end(JSON.stringify({ ok: true, root: ROOT, projectName: proj.projectName, ecosystems: proj.ecosystems }));
        } catch (e) {
          res.writeHead(400, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: e.message }));
        }
        return;
      }

      if (req.method !== 'GET' && req.method !== 'HEAD') {
        res.writeHead(405, { 'Allow': 'GET, HEAD, POST' }).end();
        return;
      }

      if (req.url === '/' || req.url === '/index.html') {
        // Re-render page each time so the bootstrap reflects the current
        // ROOT (which may have changed via /audit-config).
        const fresh = {
          ...bootstrap,
          project: (await detectProject(ROOT)).projectName,
          root: ROOT,
        };
        const { html: pageHtml, csp: pageCsp } = await renderPage(fresh);
        res.writeHead(200, {
          'content-type': 'text/html; charset=utf-8',
          'content-security-policy': pageCsp,
          'cache-control': 'no-store',
          'x-content-type-options': 'nosniff',
          'x-frame-options': 'DENY',
          'referrer-policy': 'no-referrer',
          'permissions-policy': 'interest-cohort=()',
        });
        if (req.method === 'HEAD') res.end(); else res.end(pageHtml);
        return;
      }
      if (req.url === '/events') {
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-store',
          'connection': 'keep-alive',
          'x-accel-buffering': 'no',
        });
        streamAudit(res).catch(e => {
          res.write(`event: error\ndata: ${JSON.stringify({ message: e.message })}\n\n`);
          res.end();
        });
        return;
      }
      res.writeHead(404, { 'content-type': 'text/plain' }).end('Not found');
    });
    server.on('error', reject);
    server.listen(PORT, '127.0.0.1', () => resolveSrv(server));
  });
}

async function streamAudit(res) {
  function send(event, data) {
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  }

  const project = await detectProject(ROOT);
  // Provisional sources list (Anthropic flag may flip after AI step).
  send('project', { project: project.projectName, root: ROOT, sources: sourcesUsed({ project, depsByEco: {} }) });

  const report = await runAudit({
    onProgress: (p) => {
      if (p.phase === 'scan') send('progress', { message: `Discovered ${p.found || 0} packages…` });
      else if (p.phase === 'osv-start') send('progress', { message: `Querying ${p.count} packages on OSV.dev…` });
      else if (p.phase === 'osv' && p.detailsFetched) send('progress', { message: `Loading vuln detail ${p.detailsFetched}/${p.totalDetails}…` });
    },
    onDep: (d) => send('dep', d),
    onVuln: (u) => send('vuln', u),
  });

  if (useAi) {
    send('progress', { message: 'Running AI analysis…' });
    await attachAi(report);
    for (const d of report.deps) {
      if (d.aiExplanation || d.fixPlan) {
        send('vuln', {
          key: `${d.ecosystem}|${d.name}|${d.version}`,
          dep: d.name + '@' + d.version,
          vulnerabilities: d.vulnerabilities,
          severity: d.severity,
        });
      }
    }
  }

  send('done', {
    total: report.deps.length,
    vulnerable: report.deps.filter(d => d.vulnerabilities?.length).length,
  });
  res.end();
}

// ─── text output for non-serve runs ───────────────────────────────────

function formatText(report) {
  const total = report.deps.length;
  const vuln = report.deps.filter(d => d.vulnerabilities?.length).length;
  const lines = [];
  lines.push(color('DepAudit', 'bold') + color(`  ${report.project.projectName}`, 'gray'));
  lines.push(color(`  ${total} packages · ${vuln ? color(vuln, 'red') : color(vuln, 'green')} vulnerable`, 'gray'));
  lines.push('');
  if (vuln === 0) {
    lines.push(color('✓ No known vulnerabilities.', 'green'));
  } else {
    lines.push(color('Vulnerable:', 'red'));
    for (const d of report.deps) {
      if (!d.vulnerabilities?.length) continue;
      lines.push('  ' + color(d.name + '@' + d.version, 'yellow') + color('  ' + d.ecosystem, 'gray'));
      for (const v of d.vulnerabilities) {
        lines.push('    ' + color(v.id, 'red') + ` [${v.severityLabel}] ${v.summary || ''}`);
        if (v.fixedIn?.length) lines.push('      fixed in: ' + v.fixedIn.join(', '));
      }
      if (d.aiExplanation) {
        lines.push(color('    AI: ', 'cyan') + d.aiExplanation.replace(/\n/g, '\n    '));
      }
      if (d.fixPlan) {
        lines.push(color('    fix → ' + d.fixPlan.targetVersion, 'cyan'));
        lines.push('      - ' + d.fixPlan.oldString);
        lines.push('      + ' + d.fixPlan.newString);
      }
    }
  }
  return lines.join('\n');
}

// ─── main ─────────────────────────────────────────────────────────────

async function main() {
  if (serveMode) {
    const server = await startLiveServer();
    log(color(`DepAudit viewer: http://127.0.0.1:${PORT}`, 'cyan'));
    log(color('Auditing ' + ROOT, 'gray'));
    log(color('Loopback only — Ctrl+C to stop', 'gray'));
    const stop = () => server.close(() => process.exit(0));
    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
    return;
  }

  log(color('Scanning ' + ROOT + '…', 'gray'));
  const report = await runAudit({
    onProgress: verbose
      ? (p) => log(color(`  ${p.phase}: ${JSON.stringify(p)}`, 'gray'))
      : undefined,
  });
  await attachAi(report);

  const summary = {
    auditDate: new Date().toISOString().slice(0, 10),
    project: report.project.projectName,
    ecosystems: report.project.ecosystems,
    sources: report.sources,
    summary: {
      totalDependencies: report.deps.length,
      direct: report.deps.filter(d => d.direct).length,
      transitive: report.deps.filter(d => !d.direct).length,
      withKnownVulnerabilities: report.deps.filter(d => d.vulnerabilities?.length).length,
      bySeverity: bySeverity(report.deps),
    },
    dependencies: report.deps,
  };

  if (jsonOnly) {
    process.stdout.write(JSON.stringify(summary, null, 2) + '\n');
    process.exit(summary.summary.withKnownVulnerabilities ? 1 : 0);
  }

  console.log(formatText(report));
  await writeFile(join(ROOT, 'dependencies-audit.json'), JSON.stringify(summary, null, 2));

  if (htmlOnly || !args.has('--no-html')) {
    const bootstrap = { live: false, project: summary.project, sources: summary.sources, dependencies: summary.dependencies };
    const { html } = await renderPage(bootstrap);
    await writeFile(join(ROOT, 'dependencies-audit.html'), html);
    log('');
    log(color('Wrote dependencies-audit.json + dependencies-audit.html', 'gray'));
  }

  process.exit(summary.summary.withKnownVulnerabilities ? 1 : 0);
}

function bySeverity(deps) {
  const c = { critical: 0, high: 0, medium: 0, low: 0, none: 0, unknown: 0 };
  for (const d of deps) c[d.severity || 'none'] = (c[d.severity || 'none'] || 0) + 1;
  return c;
}

main().catch(e => {
  warn(color('Tool error: ' + (e.stack || e.message), 'red'));
  process.exit(2);
});
