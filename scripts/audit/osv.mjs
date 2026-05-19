// OSV.dev client — batched and concurrent.
//
// OSV ecosystem identifiers we map to:
//   npm, Maven, PyPI, Go, crates.io, GitHub Actions, RubyGems, …
//
// API docs: https://google.github.io/osv.dev/api/
// We use POST /v1/querybatch for efficiency — a single HTTP round-trip
// can ask about up to 1000 packages and we get back per-package vuln IDs
// which we then expand via /v1/vulns/{id} for full detail.

const OSV_QUERY_BATCH = 'https://api.osv.dev/v1/querybatch';
const OSV_VULNS = 'https://api.osv.dev/v1/vulns/';
const ECO_MAP = {
  npm: 'npm',
  Maven: 'Maven',
  PyPI: 'PyPI',
  Go: 'Go',
  'crates.io': 'crates.io',
  'GitHub Actions': 'GitHub Actions',
  Gradle: null,    // OSV has no dedicated Gradle build-tool ecosystem
};

function osvFor(dep) {
  return ECO_MAP[dep.ecosystem] || null;
}

function extractFixedVersions(vuln, depName) {
  const fixed = new Set();
  for (const a of vuln.affected || []) {
    if (a.package?.name && a.package.name !== depName) continue;
    for (const r of a.ranges || []) {
      for (const ev of r.events || []) {
        if (ev.fixed) fixed.add(ev.fixed);
      }
    }
  }
  return [...fixed];
}

function pickSeverity(vuln) {
  // Prefer CVSS v3.1 score if present; fall back to severity.severity string.
  const sev = vuln.severity?.[0];
  if (sev?.score) {
    const m = sev.score.match(/CVSS:[\d.]+\/.*?\/?$/);
    return { type: sev.type, score: sev.score, label: classify(sev.score) };
  }
  return { type: null, score: null, label: 'unknown' };
}

function classify(cvssVector) {
  // Crude: parse the "/A:n" attribute is irrelevant; we look for the
  // baseScore embedded in the vector via the database_specific or
  // fall back to "unknown". OSV usually includes the numeric score
  // alongside; for the sake of a quick label we just bucket by the
  // first number we find after a forward slash.
  const m = cvssVector.match(/\b([0-9]+\.[0-9])\b/);
  if (!m) return 'unknown';
  const n = parseFloat(m[1]);
  if (n >= 9.0) return 'critical';
  if (n >= 7.0) return 'high';
  if (n >= 4.0) return 'medium';
  if (n > 0)    return 'low';
  return 'none';
}

function severityFromVulns(vulns) {
  // Highest severity wins.
  const rank = { none: 0, unknown: 1, low: 2, medium: 3, high: 4, critical: 5 };
  let best = 'none';
  for (const v of vulns) {
    const lab = v.severityLabel || 'unknown';
    if (rank[lab] > rank[best]) best = lab;
  }
  return best;
}

async function fetchVulnDetail(id) {
  try {
    const res = await fetch(OSV_VULNS + encodeURIComponent(id), { method: 'GET' });
    if (!res.ok) return null;
    return await res.json();
  } catch { return null; }
}

export async function queryAll(deps, opts = {}) {
  const onProgress = opts.onProgress || (() => {});
  const queries = [];
  const queryIdx = [];
  for (let i = 0; i < deps.length; i++) {
    const eco = osvFor(deps[i]);
    if (!eco) continue;
    queries.push({
      package: { name: deps[i].name, ecosystem: eco },
      version: deps[i].version,
    });
    queryIdx.push(i);
  }
  if (queries.length === 0) return deps.map(d => ({ ...d, vulnerabilities: [], severity: 'none' }));

  let batchResp;
  try {
    const res = await fetch(OSV_QUERY_BATCH, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ queries }),
    });
    if (!res.ok) throw new Error('osv ' + res.status);
    batchResp = await res.json();
  } catch (e) {
    onProgress({ phase: 'osv', error: e.message });
    return deps.map(d => ({ ...d, vulnerabilities: [], severity: 'none' }));
  }

  // Collect unique vuln IDs we need to fetch for full detail.
  const need = new Set();
  for (const r of batchResp.results || []) {
    for (const v of r.vulns || []) need.add(v.id);
  }
  onProgress({ phase: 'osv', batched: queries.length, vulnIds: need.size });

  // Fetch in parallel (capped concurrency to be polite).
  const ids = [...need];
  const details = new Map();
  const concurrency = 8;
  let cursor = 0;
  async function worker() {
    while (true) {
      const i = cursor++;
      if (i >= ids.length) return;
      const id = ids[i];
      const data = await fetchVulnDetail(id);
      if (data) details.set(id, data);
      onProgress({ phase: 'osv', detailsFetched: cursor, totalDetails: ids.length });
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, ids.length) }, worker));

  // Stitch.
  const out = deps.map(d => ({ ...d, vulnerabilities: [], severity: 'none' }));
  for (let i = 0; i < queryIdx.length; i++) {
    const depIdx = queryIdx[i];
    const r = batchResp.results?.[i];
    if (!r?.vulns?.length) continue;
    const vulns = [];
    for (const stub of r.vulns) {
      const v = details.get(stub.id) || stub;
      const sev = pickSeverity(v);
      vulns.push({
        id: v.id,
        summary: v.summary || (v.details ? v.details.slice(0, 200) : null),
        details: v.details,
        severityLabel: sev.label,
        cvss: sev.score,
        aliases: v.aliases || [],
        published: v.published,
        fixedIn: extractFixedVersions(v, out[depIdx].name),
        references: (v.references || []).map(r => r.url).slice(0, 5),
      });
    }
    out[depIdx].vulnerabilities = vulns;
    out[depIdx].severity = severityFromVulns(vulns);
    onProgress({ phase: 'osv', dep: out[depIdx].name, vulns: vulns.length });
  }
  return out;
}
