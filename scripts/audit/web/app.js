// DepAudit app — drives aura-designer's event-graph.js (verbatim) with
// dependency-audit data.
//
// Every dep becomes an `AD.state.doc.nodes` entry with kind "function"
// and functionKind = severity (used by the shim's `AD.nodePlugin` to
// pick the stripe / port colour). Every parent → child relation
// becomes an entry in `AD.state.doc.events` with `{ fromId, toId }`.
//
// The event-graph handles all input: drag, pan, zoom, marquee, wire-
// drag, edge-select, multi-select. We just sync data + react to
// AD.state.selectedId changes for the details panel.

(function () {
  const bootstrap = JSON.parse(document.getElementById('bootstrap').textContent);

  // ─── layout constants ─────────────────────────────────────────────
  const COL_GAP = 240;
  const ROW_GAP = 14;
  const COL_PAD = 80;
  const ROW_PAD = 60;
  const ROW_LIMIT = 18;
  const NODE_H_APPROX = 56;

  const state = {
    project: bootstrap.project,
    sources: bootstrap.sources || [],
    deps: [],
    byKey: new Map(),         // depKey → dep object
    severity: { critical: 0, high: 0, medium: 0, low: 0, none: 0, unknown: 0 },
    filters: { ecosystem: new Set(), query: '', vulnOnly: false, directOnly: false },
    currentRoot: bootstrap.root || null,
    colCounters: new Map(),   // depth → row count
  };

  function depKey(d) { return d.ecosystem + '|' + d.name + '|' + d.version; }
  function severityOf(d) { return d.severity || 'none'; }

  // ─── boot the event-graph (verbatim from aura-designer) ───────────
  // It looks for #eventGraph (svg) and #eventInspector (div) by id.

  AD.ui.eventGraph.init();

  // Hook AD.renderAll so every internal mutation also refreshes our
  // details panel + counters.
  const origRender = AD.ui.eventGraph.render;
  AD.renderAll = function () {
    origRender();
    syncDetailsFromAD();
  };

  // ─── data ingestion ───────────────────────────────────────────────

  function allocPos(depth) {
    const row = state.colCounters.get(depth) || 0;
    state.colCounters.set(depth, row + 1);
    const subCol = Math.floor(row / ROW_LIMIT);
    const inSub = row % ROW_LIMIT;
    return {
      x: COL_PAD + depth * COL_GAP + subCol * (170 + 20),
      y: ROW_PAD + inSub * (NODE_H_APPROX + ROW_GAP),
    };
  }

  function depToNode(d) {
    const pos = allocPos(d.depth || 0);
    return {
      id: depKey(d),
      kind: 'function',
      functionKind: severityOf(d),
      label: d.name + '@' + d.version,
      graphX: pos.x,
      graphY: pos.y,
      color: AD.severityColor(severityOf(d)),
      // Keep the raw dep payload attached so the details panel can
      // read it back when the user clicks this node.
      _dep: d,
    };
  }

  function ingestDep(d) {
    const key = depKey(d);
    const existing = state.byKey.get(key);
    if (existing) {
      Object.assign(existing, d);
      const node = AD.state.doc.nodes.find(n => n.id === key);
      if (node) {
        node.functionKind = severityOf(d);
        node.color = AD.severityColor(severityOf(d));
        node._dep = existing;
      }
      AD.ui.eventGraph.render();
      return existing;
    }
    state.byKey.set(key, d);
    state.deps.push(d);
    AD.state.doc.nodes.push(depToNode(d));
    // Wire any parents that exist.
    for (const parentKey of (d.parents || [])) {
      if (state.byKey.has(parentKey)) {
        AD.state.doc.events.push({ fromId: parentKey, toId: key });
      }
    }
    AD.ui.eventGraph.render();
  }

  function ingestVulnUpdate(key, vulnerabilities, severity) {
    const dep = state.byKey.get(key);
    if (!dep) return;
    dep.vulnerabilities = vulnerabilities;
    dep.severity = severity;
    const node = AD.state.doc.nodes.find(n => n.id === key);
    if (node) {
      node.functionKind = severity;
      node.color = AD.severityColor(severity);
      node._dep = dep;
    }
    recomputeSeverity();
    AD.ui.eventGraph.render();
    if (AD.state.selectedId === key) syncDetailsFromAD();
  }

  function recomputeSeverity() {
    const c = { critical: 0, high: 0, medium: 0, low: 0, none: 0, unknown: 0 };
    let direct = 0, trans = 0, vuln = 0;
    for (const d of state.deps) {
      c[severityOf(d)] = (c[severityOf(d)] || 0) + 1;
      if (d.direct) direct++; else trans++;
      if ((d.vulnerabilities || []).length) vuln++;
    }
    state.severity = c;
    el('s-total').textContent = state.deps.length;
    el('s-direct').textContent = direct;
    el('s-trans').textContent = trans;
    el('s-vuln').textContent = vuln;
    el('s-clean').textContent = state.deps.length - vuln;
    el('foot-tally').textContent =
      `${state.deps.length} packages · ${vuln} vulnerable · ${state.sources.length} data sources`;
    renderSeverityBar();
  }
  function el(id) { return document.getElementById(id); }

  function renderSeverityBar() {
    const total = state.deps.length || 1;
    const bar = el('sev-bar'); bar.textContent = '';
    const rows = [
      ['crit', 'critical', 'Critical', state.severity.critical, '#e0314f'],
      ['high', 'high',     'High',     state.severity.high,     '#f87171'],
      ['med',  'medium',   'Medium',   state.severity.medium,   '#fbbf24'],
      ['low',  'low',      'Low',      state.severity.low,      '#60a5fa'],
      ['none', 'none',     'Clean',    state.severity.none,     '#4ade80'],
    ];
    for (const [cls, , , n] of rows) {
      const seg = document.createElement('span');
      seg.className = cls;
      seg.style.width = (n / total * 100) + '%';
      bar.appendChild(seg);
    }
    const legend = el('sev-legend'); legend.textContent = '';
    for (const [cls, , label, n, color] of rows) {
      const li = document.createElement('li');
      const left = document.createElement('span');
      const sw = document.createElement('span');
      sw.className = 'swatch ' + cls;
      sw.style.background = color;
      left.appendChild(sw);
      left.appendChild(document.createTextNode(label));
      const right = document.createElement('span'); right.textContent = n;
      li.append(left, right);
      legend.appendChild(li);
    }
  }

  // ─── filters ──────────────────────────────────────────────────────

  function renderEcoChips() {
    const wrap = el('eco-chips'); wrap.textContent = '';
    const ecos = new Set(state.deps.map(d => d.ecosystem));
    for (const eco of ecos) {
      const b = document.createElement('button');
      b.className = 'chip' + (state.filters.ecosystem.has(eco) ? ' active' : '');
      b.textContent = eco;
      b.addEventListener('click', () => {
        if (state.filters.ecosystem.has(eco)) state.filters.ecosystem.delete(eco);
        else state.filters.ecosystem.add(eco);
        renderEcoChips();
        applyFilter();
      });
      wrap.appendChild(b);
    }
  }

  function applyFilter() {
    // The event-graph doesn't natively filter — but we can mark nodes
    // we want to hide by setting opacity via a custom field. Simpler:
    // remove from AD.state.doc.nodes when filtered out, re-add when
    // shown. To preserve position, keep our copy of all nodes in
    // state.deps and rebuild AD.state.doc.nodes on every filter change.
    rebuildADNodes();
    AD.ui.eventGraph.render();
  }

  function rebuildADNodes() {
    const allowed = new Map();  // key → existing graphX/Y to preserve
    for (const n of AD.state.doc.nodes) allowed.set(n.id, { x: n.graphX, y: n.graphY });
    AD.state.doc.nodes.length = 0;
    state.colCounters = new Map();
    for (const d of state.deps) {
      if (!passesFilter(d)) continue;
      const key = depKey(d);
      const prev = allowed.get(key);
      const node = depToNode(d);
      if (prev) { node.graphX = prev.x; node.graphY = prev.y; }
      AD.state.doc.nodes.push(node);
    }
    // Drop edges that touch a filtered-out node.
    const presentIds = new Set(AD.state.doc.nodes.map(n => n.id));
    AD.state.doc.events = AD.state.doc.events.filter(e =>
      presentIds.has(e.fromId) && presentIds.has(e.toId != null ? e.toId : e.targetId));
  }

  function passesFilter(d) {
    const f = state.filters;
    if (f.ecosystem.size && !f.ecosystem.has(d.ecosystem)) return false;
    if (f.vulnOnly && !(d.vulnerabilities || []).length) return false;
    if (f.directOnly && !d.direct) return false;
    if (f.query) {
      const q = f.query.toLowerCase();
      const hay = [
        d.name, d.version, d.ecosystem,
        ...(d.declaredIn || []),
        ...(d.vulnerabilities || []).flatMap(v => [v.id, v.summary]),
      ].join(' ').toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  }

  el('q').addEventListener('input', (ev) => { state.filters.query = ev.target.value; applyFilter(); });
  el('f-vuln-only').addEventListener('change', (ev) => { state.filters.vulnOnly = ev.target.checked; applyFilter(); });
  el('f-direct-only').addEventListener('change', (ev) => { state.filters.directOnly = ev.target.checked; applyFilter(); });
  el('t-fit').addEventListener('click', () => AD.ui.eventGraph.fit());
  el('t-zin').addEventListener('click', () => zoomBy(1.2));
  el('t-zout').addEventListener('click', () => zoomBy(1 / 1.2));
  el('t-collapse-safe').addEventListener('change', (e) => {
    // Treat "collapse safe" as a filter that hides severity==none nodes.
    state.filters.vulnOnly = e.target.checked;
    el('f-vuln-only').checked = e.target.checked;
    applyFilter();
  });
  el('t-highlight').addEventListener('change', () => { /* event-graph doesn't ship this; left for parity */ });
  el('t-replay').addEventListener('click', () => replayAnimation());

  function zoomBy(factor) {
    const v = AD.state.doc.settings.eventGraphView;
    if (!v) return;
    v.zoom = Math.max(0.18, Math.min(3, v.zoom * factor));
    AD.ui.eventGraph.render();
    const out = document.getElementById('zoom-readout');
    if (out) out.textContent = Math.round(v.zoom * 100) + '%';
  }

  // ─── details panel ───────────────────────────────────────────────

  function syncDetailsFromAD() {
    const id = AD.state.selectedId;
    const empty = el('details-empty');
    const card = el('details');
    if (!id) { empty.hidden = false; card.hidden = true; return; }
    const dep = state.byKey.get(id);
    if (!dep) { empty.hidden = false; card.hidden = true; return; }
    empty.hidden = true; card.hidden = false;
    el('d-name').textContent = dep.name;
    el('d-version').textContent = '@' + dep.version;
    const sev = severityOf(dep);
    const risk = el('d-risk');
    risk.className = 'risk-badge sev-' + sev;
    risk.textContent = sev.toUpperCase();
    el('d-eco').textContent = dep.ecosystem;
    el('d-depth').textContent = dep.depth ?? 0;
    el('d-direct').textContent = dep.direct ? 'yes' : 'no';
    const decl = el('d-declared'); decl.textContent = '';
    for (const p of (dep.declaredIn || [])) {
      const li = document.createElement('li'); li.textContent = p; decl.appendChild(li);
    }
    const vulnsBox = el('d-vulns'); vulnsBox.textContent = '';
    const vulns = dep.vulnerabilities || [];
    el('d-vulns-wrap').hidden = vulns.length === 0;
    el('d-vuln-count').textContent = vulns.length ? `(${vulns.length})` : '';
    for (const v of vulns) {
      const li = document.createElement('li');
      const idLine = document.createElement('div');
      idLine.className = 'vuln-id sev-' + (v.severityLabel || 'unknown');
      idLine.textContent = v.id + (v.severityLabel ? ` [${v.severityLabel}]` : '');
      li.appendChild(idLine);
      if (v.summary) {
        const s = document.createElement('div'); s.className = 'vuln-summary'; s.textContent = v.summary;
        li.appendChild(s);
      }
      if (v.fixedIn && v.fixedIn.length) {
        const f = document.createElement('div'); f.className = 'vuln-fixed';
        f.textContent = 'fixed in: ' + v.fixedIn.join(', ');
        li.appendChild(f);
      }
      if (v.references && v.references.length) {
        const r = document.createElement('div'); r.className = 'vuln-refs';
        r.textContent = v.references.join('  ');
        li.appendChild(r);
      }
      vulnsBox.appendChild(li);
    }
    el('d-ai-wrap').hidden = !dep.aiExplanation;
    if (dep.aiExplanation) el('d-ai').textContent = dep.aiExplanation;
    el('d-fix-wrap').hidden = !dep.fixPlan;
    if (dep.fixPlan) {
      const fx = el('d-fix'); fx.textContent = '';
      const tgt = document.createElement('div');
      tgt.appendChild(document.createTextNode('Target: '));
      const tv = document.createElement('strong'); tv.textContent = dep.fixPlan.targetVersion;
      tgt.appendChild(tv);
      const reason = document.createElement('div'); reason.textContent = dep.fixPlan.reasoning;
      const diff = document.createElement('div'); diff.className = 'fix-diff';
      const minus = document.createElement('div'); minus.className = 'minus'; minus.textContent = '- ' + dep.fixPlan.oldString;
      const plus = document.createElement('div'); plus.className = 'plus';  plus.textContent = '+ ' + dep.fixPlan.newString;
      diff.append(minus, plus);
      fx.append(tgt, reason, diff);
    }
  }

  // ─── data sources panel ──────────────────────────────────────────

  function renderSources() {
    const ul = el('sources-list'); ul.textContent = '';
    for (const s of state.sources) {
      const li = document.createElement('li');
      const head = document.createElement('div');
      const name = document.createElement('span'); name.className = 'src-name'; name.textContent = s.name;
      const status = document.createElement('span'); status.className = s.used ? 'src-on' : 'src-off';
      status.textContent = '  ' + (s.used ? '✓ used' : '◌ skipped');
      head.append(name, status);
      const meta = document.createElement('div'); meta.className = 'src-meta'; meta.textContent = s.detail || '';
      li.append(head, meta);
      ul.appendChild(li);
    }
  }

  // ─── SSE live mode ──────────────────────────────────────────────

  let currentEvt = null;
  function setScanStatus(cls, label) {
    const s = el('scan-status');
    s.className = 'scan-status ' + cls;
    el('scan-label').textContent = label;
  }

  function bootLive() {
    setScanStatus('active', 'Scanning…');
    const toast = el('discovery-toast'); toast.hidden = false;
    el('empty-overlay').classList.remove('hidden');
    if (currentEvt) { try { currentEvt.close(); } catch {} }
    const evt = new EventSource(bootstrap.eventsUrl || '/events');
    currentEvt = evt;
    evt.addEventListener('project', (m) => {
      const p = JSON.parse(m.data);
      state.sources = p.sources || [];
      renderSources();
      if (p.project) {
        el('project-name').textContent = p.project;
        state.project = p.project;
      }
      if (p.root) state.currentRoot = p.root;
    });
    evt.addEventListener('progress', (m) => {
      const p = JSON.parse(m.data);
      if (p.message) el('toast-text').textContent = p.message;
    });
    evt.addEventListener('dep', (m) => {
      const dep = JSON.parse(m.data);
      ingestDep(dep);
      el('empty-overlay').classList.add('hidden');
      el('toast-text').textContent = 'Adding ' + dep.name + '@' + dep.version;
      recomputeSeverity();
      renderEcoChips();
    });
    evt.addEventListener('vuln', (m) => {
      const u = JSON.parse(m.data);
      ingestVulnUpdate(u.key, u.vulnerabilities, u.severity);
      el('toast-text').textContent = 'Checking vulnerabilities · ' + (u.dep || '');
    });
    evt.addEventListener('done', (m) => {
      const r = JSON.parse(m.data);
      setScanStatus('done', `Scan complete — ${state.deps.length} packages, ${r.vulnerable || 0} vulnerable`);
      toast.hidden = true;
      AD.ui.eventGraph.fit();
      evt.close();
    });
    evt.addEventListener('error', () => setScanStatus('error', 'Connection lost'));
  }

  function bootStatic(report) {
    el('empty-overlay').classList.add('hidden');
    el('discovery-toast').hidden = true;
    state.sources = report.sources || state.sources;
    for (const d of report.dependencies) ingestDep(d);
    recomputeSeverity();
    renderEcoChips();
    renderSources();
    AD.ui.eventGraph.fit();
    setScanStatus('done', `Scan complete — ${report.dependencies.length} packages`);
  }

  function replayAnimation() {
    // Snapshot, clear, replay one-at-a-time. Positions are recomputed
    // from scratch so columns still fill correctly.
    const snap = [...state.deps];
    AD.state.doc.nodes.length = 0;
    AD.state.doc.events.length = 0;
    AD.state.selectedId = null;
    state.byKey.clear();
    state.deps = [];
    state.colCounters = new Map();
    AD.ui.eventGraph.render();
    let i = 0;
    const t = setInterval(() => {
      if (i >= snap.length) {
        clearInterval(t);
        AD.ui.eventGraph.fit();
        recomputeSeverity();
        return;
      }
      ingestDep(snap[i++]);
    }, 35);
  }

  // ─── project picker ──────────────────────────────────────────────

  const RECENT_KEY = 'depaudit.recent';
  function loadRecent() {
    try { return JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); } catch { return []; }
  }
  function saveRecent(list) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, 8))); } catch {}
  }
  function rememberRoot(root, name) {
    if (!root) return;
    const list = loadRecent().filter(r => r.root !== root);
    list.unshift({ root, name: name || root, when: Date.now() });
    saveRecent(list);
    renderRecent();
  }
  function renderRecent() {
    const ul = el('recent-list'); ul.textContent = '';
    const list = loadRecent();
    if (list.length === 0) {
      const li = document.createElement('li'); li.className = 'empty';
      li.textContent = 'No recent audits'; ul.appendChild(li); return;
    }
    for (const r of list) {
      const li = document.createElement('li');
      li.textContent = r.root;
      if (r.root === state.currentRoot) li.classList.add('current');
      li.addEventListener('click', () => startAudit(r.root));
      ul.appendChild(li);
    }
  }

  async function startAudit(root) {
    root = (root || '').trim();
    if (!root) return;
    setScanStatus('active', 'Starting…');
    el('toast-text').textContent = 'Validating ' + root + '…';
    el('discovery-toast').hidden = false;
    try {
      const res = await fetch('/audit-config', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ root }),
      });
      const body = await res.json();
      if (!res.ok || !body.ok) throw new Error(body.error || 'Server rejected the path');
      // Wipe graph state and re-open stream.
      AD.state.doc.nodes.length = 0;
      AD.state.doc.events.length = 0;
      AD.state.selectedId = null;
      AD.state.graphSelection = [];
      state.deps = [];
      state.byKey.clear();
      state.colCounters = new Map();
      AD.ui.eventGraph.render();
      recomputeSeverity();
      renderEcoChips();
      state.currentRoot = root;
      rememberRoot(root, body.projectName);
      hidePicker();
      bootLive();
    } catch (e) {
      setScanStatus('error', 'Failed: ' + e.message);
      el('toast-text').textContent = 'Failed: ' + e.message;
      setTimeout(() => { el('discovery-toast').hidden = true; }, 4000);
    }
  }

  function showPicker() {
    el('root-picker').hidden = false;
    el('open-picker').style.display = 'none';
    const i = el('root-input');
    i.value = state.currentRoot || ''; i.focus(); i.select();
  }
  function hidePicker() {
    el('root-picker').hidden = true;
    el('open-picker').style.display = '';
  }

  el('open-picker').addEventListener('click', showPicker);
  el('cancel-picker').addEventListener('click', hidePicker);
  el('start-audit').addEventListener('click', () => startAudit(el('root-input').value));
  el('root-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') startAudit(e.target.value);
    else if (e.key === 'Escape') hidePicker();
  });
  el('start-audit-sidebar').addEventListener('click', () => startAudit(el('root-input-sidebar').value));
  el('root-input-sidebar').addEventListener('keydown', (e) => { if (e.key === 'Enter') startAudit(e.target.value); });

  // Native folder picker — server pops OS dialog, returns the path.
  async function browseAndAudit(intoInputId) {
    const btnIds = ['browse-btn', 'browse-btn-sidebar'];
    for (const id of btnIds) { const b = el(id); if (b) { b.disabled = true; b.textContent = '📁 Opening dialog…'; } }
    try {
      const res = await fetch('/pick-folder', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ startingPath: state.currentRoot || '' }),
      });
      const body = await res.json();
      if (body.cancelled) return; // user x'd out — no-op
      if (!body.ok) throw new Error(body.error || 'picker failed');
      if (intoInputId) el(intoInputId).value = body.path;
      await startAudit(body.path);
    } catch (e) {
      setScanStatus('error', 'Picker failed: ' + e.message);
    } finally {
      const b1 = el('browse-btn');           if (b1) { b1.disabled = false; b1.textContent = '📁 Browse…'; }
      const b2 = el('browse-btn-sidebar');   if (b2) { b2.disabled = false; b2.textContent = '📁 Browse for folder…'; }
    }
  }

  const bbTop = el('browse-btn');         if (bbTop) bbTop.addEventListener('click', () => browseAndAudit('root-input'));
  const bbSide = el('browse-btn-sidebar'); if (bbSide) bbSide.addEventListener('click', () => browseAndAudit('root-input-sidebar'));

  // ─── boot ────────────────────────────────────────────────────────

  renderSources();
  renderEcoChips();
  renderRecent();
  if (bootstrap.live) bootLive();
  else if (bootstrap.dependencies) bootStatic(bootstrap);
  else setScanStatus('idle', 'No data');
})();
