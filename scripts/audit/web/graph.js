// SVG dependency-graph viewer.
//
// Ported from aura-designer's event-graph (tools/aura-designer/src/ui/
// event-graph.js): same SVG-first approach, same input model.
// Interactions:
//   - Drag node body              → move node (and any other selected nodes)
//   - Drag from output port (●)   → rubber-band wire → drop on input port
//                                   → adds a "manual" edge to the chain
//   - Click node                  → select; emits 'select' to host
//   - Click edge                  → select edge; can be deleted with Del/Backspace
//   - Middle-button drag          → pan (alt: drag from empty area)
//   - Shift-drag in empty area    → marquee multi-select
//   - Mouse wheel                 → zoom anchored to cursor
//   - Ctrl/Cmd+click              → toggle selection (additive)
//
// Layout: nodes positioned in columns by depth (Direct → L1 → L2 …).

(function () {
  const NS = 'http://www.w3.org/2000/svg';
  const NODE_W = 170;
  const NODE_H = 56;
  const COL_GAP = 240;
  const ROW_GAP = 14;
  const COL_PAD = 80;
  const ROW_PAD = 60;
  const ROW_LIMIT = 18;
  const PORT_R = 5;
  const PORT_HIT = 14;     // px in world space — generous tap target
  const EDGE_HIT = 8;

  function el(tag, attrs, children) {
    const e = document.createElementNS(NS, tag);
    if (attrs) for (const k in attrs) if (attrs[k] !== null && attrs[k] !== undefined) e.setAttribute(k, attrs[k]);
    if (children) for (const c of children) if (c) e.appendChild(c);
    return e;
  }
  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
  function depKey(d) { return d.ecosystem + '|' + d.name + '|' + d.version; }
  function truncate(s, n) { s = String(s || ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; }

  /* ─── DepGraph ───────────────────────────────────────────────────── */

  class DepGraph {
    constructor(svgEl) {
      this.svg = svgEl;
      this.view = { zoom: 0.85, panX: 40, panY: 40 };
      this.nodes = new Map();   // key → { dep, x, y, dom, inner }
      this.edges = [];          // { from, to, dom, hitDom, severity, manual }
      this.selection = new Set();
      this.selectedEdgeIdx = null;
      this.highlightBranch = false;
      this.collapseSafe = false;
      this.listeners = { select: [], edgeSelect: [], edgeAdded: [] };
      this._colMaxRow = new Map();

      // Layer groups: edges → marquee → nodes → wires (in-progress).
      this.root = el('g', { class: 'world' });
      this.edgeLayer = el('g', { class: 'edges' });
      this.colLayer  = el('g', { class: 'col-labels' });
      this.nodeLayer = el('g', { class: 'nodes' });
      this.overlay   = el('g', { class: 'overlay' });
      this.root.append(this.edgeLayer, this.colLayer, this.nodeLayer, this.overlay);
      this.svg.append(this.root);

      // Arrow-head marker for edges.
      const defs = el('defs');
      defs.appendChild(this._arrowMarker('arr', 'var(--line)'));
      defs.appendChild(this._arrowMarker('arr-warn', 'var(--bad)'));
      defs.appendChild(this._arrowMarker('arr-crit', 'var(--crit)'));
      defs.appendChild(this._arrowMarker('arr-hi',   'var(--accent)'));
      this.svg.appendChild(defs);

      this._bindInput();
      this._applyTransform();
      this._resize();
      new ResizeObserver(() => this._resize()).observe(this.svg);
      // Wire-drag state holders, populated only while dragging.
      this._wire = null;       // { fromKey, x, y, dom }
      this._marquee = null;    // { startX, startY, x, y, dom, additive }
      this._activeDrag = null; // node-move drag
      this._panStart = null;
    }

    _arrowMarker(id, color) {
      const m = el('marker', {
        id, viewBox: '0 0 10 10', refX: '9', refY: '5',
        markerWidth: '8', markerHeight: '8', orient: 'auto-start-reverse',
      });
      m.appendChild(el('path', { d: 'M 0 0 L 10 5 L 0 10 z', fill: color }));
      return m;
    }

    on(ev, fn) { (this.listeners[ev] || (this.listeners[ev] = [])).push(fn); return this; }
    _emit(ev, payload) { for (const fn of (this.listeners[ev] || [])) fn(payload); }

    _resize() {
      const r = this.svg.getBoundingClientRect();
      this.svg.setAttribute('viewBox', `0 0 ${r.width} ${r.height}`);
    }
    _applyTransform() {
      this.root.setAttribute('transform',
        `translate(${this.view.panX} ${this.view.panY}) scale(${this.view.zoom})`);
      const out = document.getElementById('zoom-readout');
      if (out) out.textContent = Math.round(this.view.zoom * 100) + '%';
    }
    _screenToWorld(cx, cy) {
      const r = this.svg.getBoundingClientRect();
      return {
        x: (cx - r.left - this.view.panX) / this.view.zoom,
        y: (cy - r.top  - this.view.panY) / this.view.zoom,
      };
    }
    setZoom(z, anchor) {
      const a = anchor || { x: this.svg.clientWidth / 2, y: this.svg.clientHeight / 2 };
      const before = this._screenToWorld(a.x, a.y);
      this.view.zoom = clamp(z, 0.18, 3);
      const after = this._screenToWorld(a.x, a.y);
      this.view.panX += (after.x - before.x) * this.view.zoom;
      this.view.panY += (after.y - before.y) * this.view.zoom;
      this._applyTransform();
    }
    fit() {
      if (this.nodes.size === 0) return;
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const n of this.nodes.values()) {
        minX = Math.min(minX, n.x);
        minY = Math.min(minY, n.y);
        maxX = Math.max(maxX, n.x + NODE_W);
        maxY = Math.max(maxY, n.y + NODE_H);
      }
      const w = maxX - minX, h = maxY - minY;
      const r = this.svg.getBoundingClientRect();
      const margin = 50;
      const zx = (r.width - margin * 2) / w;
      const zy = (r.height - margin * 2) / h;
      this.view.zoom = clamp(Math.min(zx, zy), 0.18, 1.5);
      this.view.panX = margin - minX * this.view.zoom;
      this.view.panY = margin - minY * this.view.zoom;
      this._applyTransform();
    }

    /* ─── data ops ─── */

    upsertNode(dep, opts = {}) {
      const key = depKey(dep);
      if (this.nodes.has(key)) {
        const n = this.nodes.get(key);
        n.dep = dep;
        this._restyleNode(n);
        // Severity may have changed → restyle every edge that ends here.
        for (const e of this.edges) if (e.to === key) this._restyleEdge(e);
        return n;
      }
      const pos = this._allocatePosition(dep);
      const node = { dep, x: pos.x, y: pos.y, dom: null, inner: null };
      node.dom = this._buildNodeDom(node, opts.animate);
      this.nodes.set(key, node);
      this.nodeLayer.appendChild(node.dom);
      this._renderColumnLabel(dep.depth || 0);
      return node;
    }

    addEdge(fromKey, toKey, opts = {}) {
      if (!this.nodes.has(fromKey) || !this.nodes.has(toKey)) return null;
      if (fromKey === toKey) return null;
      if (this.edges.some(e => e.from === fromKey && e.to === toKey)) return null;
      const e = {
        from: fromKey, to: toKey, dom: null, hitDom: null,
        severity: 'none', manual: !!opts.manual,
      };
      // Visible edge (thin) + invisible hit-zone (thicker) for click.
      e.dom = el('path', { class: 'edge' });
      e.hitDom = el('path', { class: 'edge-hit', stroke: 'transparent', fill: 'none' });
      this.edgeLayer.append(e.hitDom, e.dom);
      this.edges.push(e);
      this._restyleEdge(e);
      this._redrawEdge(e);
      // Hit-zone click → select edge.
      const idx = this.edges.length - 1;
      e.hitDom.addEventListener('click', (ev) => {
        ev.stopPropagation();
        this.selectEdge(idx);
      });
      if (opts.manual) this._emit('edgeAdded', { from: fromKey, to: toKey });
      return e;
    }

    removeEdge(idx) {
      const e = this.edges[idx];
      if (!e) return;
      e.dom.remove(); e.hitDom.remove();
      this.edges.splice(idx, 1);
      if (this.selectedEdgeIdx === idx) this.selectedEdgeIdx = null;
    }

    setData(deps) {
      this.nodeLayer.textContent = '';
      this.edgeLayer.textContent = '';
      this.colLayer.textContent = '';
      this.nodes.clear();
      this.edges = [];
      this.selection.clear();
      this.selectedEdgeIdx = null;
      this._colMaxRow = new Map();
      for (const d of deps) this.upsertNode(d);
      for (const d of deps) {
        const childKey = depKey(d);
        for (const parentKey of (d.parents || [])) {
          if (this.nodes.has(parentKey)) this.addEdge(parentKey, childKey);
        }
      }
      this.fit();
    }

    selectKey(key, additive) {
      if (!additive) this.selection.clear();
      if (key) {
        if (additive && this.selection.has(key)) this.selection.delete(key);
        else this.selection.add(key);
      }
      this._renderSelection();
      this._emit('select', key);
    }
    get selectedKey() {
      // Primary key for the host (last added).
      let last = null;
      for (const k of this.selection) last = k;
      return last;
    }
    selectEdge(idx) {
      this.selectedEdgeIdx = idx;
      this.selection.clear();
      this._renderSelection();
      const e = this.edges[idx];
      if (e) this._emit('edgeSelect', { from: e.from, to: e.to, idx });
    }

    setHighlightBranch(on) { this.highlightBranch = on; this._applyDim(); }
    setCollapseSafe(on)    { this.collapseSafe = on; this._applyVisibility(); }
    setFilter(predicate)   { this._filter = predicate; this._applyVisibility(); }

    /* ─── layout ─── */

    _allocatePosition(dep) {
      const depth = dep.depth || 0;
      const row = this._colMaxRow.get(depth) || 0;
      this._colMaxRow.set(depth, row + 1);
      const subCol = Math.floor(row / ROW_LIMIT);
      const inSub = row % ROW_LIMIT;
      const x = COL_PAD + depth * COL_GAP + subCol * (NODE_W + 20);
      const y = ROW_PAD + inSub * (NODE_H + ROW_GAP);
      return { x, y };
    }

    _renderColumnLabel(depth) {
      const id = 'col-label-' + depth;
      if (document.getElementById(id)) return;
      const x = COL_PAD + depth * COL_GAP;
      const txt = el('text', { id, class: 'column-label', x, y: ROW_PAD - 22 });
      txt.textContent = depth === 0 ? 'Direct' : 'Level ' + depth;
      this.colLayer.appendChild(txt);
    }

    /* ─── nodes ─── */

    _buildNodeDom(node, animate) {
      const { dep, x, y } = node;
      const sev = dep.severity || 'none';
      const g = el('g', {
        class: 'node sev-' + sev + (animate ? ' entering' : ''),
        transform: `translate(${x} ${y})`,
        'data-key': depKey(dep),
        tabindex: '0',
      });
      const inner = el('g', { class: 'node-inner' });
      inner.appendChild(el('rect', { class: 'node-rect', x: 0, y: 0, width: NODE_W, height: NODE_H, rx: 8, ry: 8 }));
      inner.appendChild(el('circle', { class: 'sev-dot sev-' + sev, cx: NODE_W - 14, cy: 14, r: 5 }));
      // Visible input port (left) + output port (right).
      inner.appendChild(el('circle', { class: 'port port-in',  cx: 0,       cy: NODE_H / 2, r: PORT_R }));
      inner.appendChild(el('circle', { class: 'port port-out', cx: NODE_W,  cy: NODE_H / 2, r: PORT_R }));
      const ecoT = el('text',  { class: 'node-eco',     x: 10, y: 14 });
      ecoT.textContent = (dep.ecosystem || '').toUpperCase();
      const nameT = el('text', { class: 'node-name',    x: 10, y: 30 });
      nameT.textContent = truncate(dep.name, 22);
      const verT = el('text',  { class: 'node-version', x: 10, y: 46 });
      verT.textContent = '@' + truncate(dep.version, 18);
      inner.append(ecoT, nameT, verT);
      g.append(inner);
      node.inner = inner;

      if (animate) setTimeout(() => g.classList.remove('entering'), 400);

      // Pointer handling: mousedown decides between move-node / wire-drag.
      g.addEventListener('mousedown', (ev) => this._onNodeDown(ev, node));
      g.addEventListener('click', (ev) => {
        if (this._suppressClick) { this._suppressClick = false; return; }
        ev.stopPropagation();
        this.selectKey(depKey(node.dep), ev.ctrlKey || ev.metaKey);
      });
      g.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter' || ev.key === ' ') {
          ev.preventDefault();
          this.selectKey(depKey(node.dep));
        }
      });
      return g;
    }

    _restyleNode(node) {
      const sev = node.dep.severity || 'none';
      const baseCls = 'node sev-' + sev;
      const sel = this.selection.has(depKey(node.dep)) ? ' selected' : '';
      node.dom.setAttribute('class', baseCls + sel);
      const dot = node.dom.querySelector('.sev-dot');
      if (dot) dot.setAttribute('class', 'sev-dot sev-' + sev);
    }

    _renderSelection() {
      for (const [key, n] of this.nodes) {
        n.dom.classList.toggle('selected', this.selection.has(key));
      }
      for (let i = 0; i < this.edges.length; i++) {
        this.edges[i].dom.classList.toggle('selected', this.selectedEdgeIdx === i);
      }
      this._applyDim();
    }

    /* ─── edges (dynamic attach + bezier — ported from aura-designer) ─── */

    _nodeCenter(node) { return { x: node.x + NODE_W / 2, y: node.y + NODE_H / 2 }; }
    _nodeAttachPoint(node, towards) {
      const cx = node.x + NODE_W / 2;
      const cy = node.y + NODE_H / 2;
      const dx = towards.x - cx;
      const dy = towards.y - cy;
      if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return { x: cx + NODE_W / 2, y: cy };
      const tx = (NODE_W / 2) / Math.max(0.001, Math.abs(dx));
      const ty = (NODE_H / 2) / Math.max(0.001, Math.abs(dy));
      const t = Math.min(tx, ty);
      return { x: cx + dx * t, y: cy + dy * t };
    }
    _edgeNormal(node, p) {
      const cx = node.x + NODE_W / 2, cy = node.y + NODE_H / 2;
      const dx = p.x - cx, dy = p.y - cy;
      const projX = Math.abs(dx) / Math.max(0.001, NODE_W / 2);
      const projY = Math.abs(dy) / Math.max(0.001, NODE_H / 2);
      if (projX > projY) return { x: Math.sign(dx) || 1, y: 0 };
      return { x: 0, y: Math.sign(dy) || 1 };
    }
    _edgePath(fromKey, toKey) {
      const a = this.nodes.get(fromKey);
      const b = this.nodes.get(toKey);
      if (!a || !b) return '';
      const p0 = this._nodeAttachPoint(a, this._nodeCenter(b));
      const p3 = this._nodeAttachPoint(b, this._nodeCenter(a));
      const n0 = this._edgeNormal(a, p0);
      const n3 = this._edgeNormal(b, p3);
      const dist = Math.hypot(p3.x - p0.x, p3.y - p0.y);
      const k = Math.max(40, Math.min(180, dist * 0.5));
      const p1 = { x: p0.x + n0.x * k, y: p0.y + n0.y * k };
      const p2 = { x: p3.x + n3.x * k, y: p3.y + n3.y * k };
      return `M ${p0.x},${p0.y} C ${p1.x},${p1.y} ${p2.x},${p2.y} ${p3.x},${p3.y}`;
    }
    _redrawEdge(e) {
      const d = this._edgePath(e.from, e.to);
      e.dom.setAttribute('d', d);
      e.hitDom.setAttribute('d', d);
      e.hitDom.setAttribute('stroke-width', EDGE_HIT);
    }
    _restyleEdge(e) {
      const child = this.nodes.get(e.to);
      const sev = child?.dep?.severity || 'none';
      let cls = 'edge';
      if (sev === 'critical')    cls += ' crit';
      else if (sev === 'high' || sev === 'medium') cls += ' danger';
      if (e.manual) cls += ' manual';
      let marker = 'url(#arr)';
      if (sev === 'critical') marker = 'url(#arr-crit)';
      else if (sev === 'high' || sev === 'medium') marker = 'url(#arr-warn)';
      e.dom.setAttribute('class', cls);
      e.dom.setAttribute('marker-end', marker);
      e.severity = sev;
    }
    _updateEdgesFor(nodeKey) {
      for (const e of this.edges) {
        if (e.from === nodeKey || e.to === nodeKey) this._redrawEdge(e);
      }
    }

    /* ─── highlight / filter ─── */

    _applyDim() {
      const focusKey = this.selectedKey;
      if (!this.highlightBranch || !focusKey) {
        for (const n of this.nodes.values()) n.dom.classList.remove('dim');
        for (const e of this.edges) e.dom.classList.remove('highlight', 'dim');
        return;
      }
      const lineage = this._collectLineage(focusKey);
      for (const [k, n] of this.nodes) n.dom.classList.toggle('dim', !lineage.has(k));
      for (const e of this.edges) {
        const onPath = lineage.has(e.from) && lineage.has(e.to);
        e.dom.classList.toggle('highlight', onPath);
        e.dom.classList.toggle('dim', !onPath);
      }
    }
    _collectLineage(rootKey) {
      const r = new Set([rootKey]);
      const up = [rootKey];
      while (up.length) {
        const k = up.pop();
        for (const e of this.edges) if (e.to === k && !r.has(e.from)) { r.add(e.from); up.push(e.from); }
      }
      const down = [rootKey];
      while (down.length) {
        const k = down.pop();
        for (const e of this.edges) if (e.from === k && !r.has(e.to)) { r.add(e.to); down.push(e.to); }
      }
      return r;
    }
    _applyVisibility() {
      const f = this._filter;
      for (const [, n] of this.nodes) {
        let vis = true;
        if (this.collapseSafe && (n.dep.severity || 'none') === 'none') vis = false;
        if (f && !f(n.dep)) vis = false;
        n.dom.style.display = vis ? '' : 'none';
      }
      for (const e of this.edges) {
        const a = this.nodes.get(e.from), b = this.nodes.get(e.to);
        e.dom.style.display = (a && b && a.dom.style.display !== 'none' && b.dom.style.display !== 'none') ? '' : 'none';
        e.hitDom.style.display = e.dom.style.display;
      }
    }

    /* ─── hit-tests in world space ─── */

    _hitOutputPort(p) {
      const tol = PORT_HIT / this.view.zoom;
      let best = null, bestD = tol;
      for (const [k, n] of this.nodes) {
        const px = n.x + NODE_W, py = n.y + NODE_H / 2;
        const d = Math.hypot(p.x - px, p.y - py);
        if (d < bestD) { best = k; bestD = d; }
      }
      return best;
    }
    _hitInputPort(p) {
      const tol = PORT_HIT / this.view.zoom;
      let best = null, bestD = tol;
      for (const [k, n] of this.nodes) {
        const px = n.x, py = n.y + NODE_H / 2;
        const d = Math.hypot(p.x - px, p.y - py);
        if (d < bestD) { best = k; bestD = d; }
      }
      return best;
    }
    _nodesInRect(x1, y1, x2, y2) {
      const lo = { x: Math.min(x1, x2), y: Math.min(y1, y2) };
      const hi = { x: Math.max(x1, x2), y: Math.max(y1, y2) };
      const out = [];
      for (const [k, n] of this.nodes) {
        if (n.x + NODE_W > lo.x && n.x < hi.x &&
            n.y + NODE_H > lo.y && n.y < hi.y) out.push(k);
      }
      return out;
    }

    /* ─── input ─── */

    _bindInput() {
      this.svg.addEventListener('mousedown', (ev) => this._onSvgDown(ev));
      window.addEventListener('mousemove', (ev) => this._onMove(ev));
      window.addEventListener('mouseup',   (ev) => this._onUp(ev));
      this.svg.addEventListener('wheel', (ev) => {
        ev.preventDefault();
        const factor = ev.deltaY < 0 ? 1.12 : 1 / 1.12;
        this.setZoom(this.view.zoom * factor, { x: ev.offsetX, y: ev.offsetY });
      }, { passive: false });
      this.svg.addEventListener('click', (ev) => {
        // Empty-area click clears selection (unless ctrl held).
        if (this._suppressClick) { this._suppressClick = false; return; }
        if (!ev.ctrlKey && !ev.metaKey) {
          this.selection.clear();
          this.selectedEdgeIdx = null;
          this._renderSelection();
          this._emit('select', null);
        }
      });
      this.svg.addEventListener('keydown', (ev) => {
        if ((ev.key === 'Delete' || ev.key === 'Backspace') && this.selectedEdgeIdx !== null) {
          this.removeEdge(this.selectedEdgeIdx);
          this._renderSelection();
        }
      });
      this.svg.setAttribute('tabindex', '0');
    }

    _onSvgDown(ev) {
      // Decide based on target + modifiers.
      // The node DOM stops propagation in its own mousedown handler.
      const isMiddle = ev.button === 1;
      const isAlt = ev.altKey;
      const onEmpty = ev.target === this.svg || ev.target === this.root || ev.target.classList?.contains('col-labels');
      if (ev.shiftKey && onEmpty) {
        // Marquee.
        const wp = this._screenToWorld(ev.clientX, ev.clientY);
        const dom = el('rect', { class: 'marquee', x: wp.x, y: wp.y, width: 0, height: 0 });
        this.overlay.appendChild(dom);
        this._marquee = { startX: wp.x, startY: wp.y, x: wp.x, y: wp.y, dom, additive: ev.ctrlKey || ev.metaKey };
        ev.preventDefault();
        return;
      }
      if (isMiddle || isAlt || onEmpty) {
        this._panStart = { x: ev.clientX, y: ev.clientY, px: this.view.panX, py: this.view.panY };
        ev.preventDefault();
      }
    }

    _onNodeDown(ev, node) {
      if (ev.button !== 0 || ev.altKey) return;
      ev.stopPropagation();
      const wp = this._screenToWorld(ev.clientX, ev.clientY);
      // Output-port hit → start wire-drag from this node.
      const outPort = { x: node.x + NODE_W, y: node.y + NODE_H / 2 };
      if (Math.hypot(wp.x - outPort.x, wp.y - outPort.y) <= PORT_HIT / this.view.zoom) {
        this._beginWireDrag(node, wp);
        return;
      }
      // Otherwise, drag node body.
      // If the node isn't selected and shift/ctrl not held, select-only it.
      const key = depKey(node.dep);
      const additive = ev.ctrlKey || ev.metaKey;
      if (!this.selection.has(key)) this.selectKey(key, additive);
      // Snapshot positions of every node we're dragging.
      const snapshot = [];
      for (const k of this.selection) {
        const n = this.nodes.get(k);
        if (n) snapshot.push({ key: k, x0: n.x, y0: n.y });
      }
      this._activeDrag = { sx: wp.x, sy: wp.y, snapshot, moved: false };
    }

    _beginWireDrag(node, wp) {
      const start = { x: node.x + NODE_W, y: node.y + NODE_H / 2 };
      const dom = el('path', { class: 'wire-drag', d: `M ${start.x},${start.y} L ${wp.x},${wp.y}` });
      this.overlay.appendChild(dom);
      this._wire = { fromKey: depKey(node.dep), start, x: wp.x, y: wp.y, dom };
    }

    _onMove(ev) {
      if (this._panStart) {
        this.view.panX = this._panStart.px + (ev.clientX - this._panStart.x);
        this.view.panY = this._panStart.py + (ev.clientY - this._panStart.y);
        this._applyTransform();
        return;
      }
      if (this._activeDrag) {
        const wp = this._screenToWorld(ev.clientX, ev.clientY);
        const dx = wp.x - this._activeDrag.sx;
        const dy = wp.y - this._activeDrag.sy;
        for (const s of this._activeDrag.snapshot) {
          const n = this.nodes.get(s.key);
          if (!n) continue;
          n.x = s.x0 + dx;
          n.y = s.y0 + dy;
          n.dom.setAttribute('transform', `translate(${n.x} ${n.y})`);
          this._updateEdgesFor(s.key);
        }
        this._activeDrag.moved = true;
        return;
      }
      if (this._wire) {
        const wp = this._screenToWorld(ev.clientX, ev.clientY);
        // Snap to nearest input port if close.
        const targetKey = this._hitInputPort(wp);
        let end = wp;
        if (targetKey) {
          const t = this.nodes.get(targetKey);
          end = { x: t.x, y: t.y + NODE_H / 2 };
          this._wire.dom.classList.add('snap');
        } else this._wire.dom.classList.remove('snap');
        // Smooth bezier between start and current.
        const s = this._wire.start;
        const dx = Math.max(40, Math.min(180, Math.hypot(end.x - s.x, end.y - s.y) * 0.5));
        const p1 = { x: s.x + dx, y: s.y };
        const p2 = { x: end.x - dx, y: end.y };
        this._wire.dom.setAttribute('d', `M ${s.x},${s.y} C ${p1.x},${p1.y} ${p2.x},${p2.y} ${end.x},${end.y}`);
        return;
      }
      if (this._marquee) {
        const wp = this._screenToWorld(ev.clientX, ev.clientY);
        this._marquee.x = wp.x; this._marquee.y = wp.y;
        const x = Math.min(this._marquee.startX, wp.x);
        const y = Math.min(this._marquee.startY, wp.y);
        const w = Math.abs(wp.x - this._marquee.startX);
        const h = Math.abs(wp.y - this._marquee.startY);
        this._marquee.dom.setAttribute('x', x);
        this._marquee.dom.setAttribute('y', y);
        this._marquee.dom.setAttribute('width', w);
        this._marquee.dom.setAttribute('height', h);
      }
    }

    _onUp(ev) {
      if (this._activeDrag?.moved) this._suppressClick = true;
      this._panStart = null;
      this._activeDrag = null;
      if (this._wire) {
        const wp = this._screenToWorld(ev.clientX, ev.clientY);
        const target = this._hitInputPort(wp);
        if (target && target !== this._wire.fromKey) {
          this.addEdge(this._wire.fromKey, target, { manual: true });
        }
        this._wire.dom.remove();
        this._wire = null;
        this._suppressClick = true;
      }
      if (this._marquee) {
        const hits = this._nodesInRect(this._marquee.startX, this._marquee.startY, this._marquee.x, this._marquee.y);
        if (!this._marquee.additive) this.selection.clear();
        for (const k of hits) this.selection.add(k);
        this._renderSelection();
        if (hits.length) this._emit('select', hits[hits.length - 1]);
        this._marquee.dom.remove();
        this._marquee = null;
        this._suppressClick = true;
      }
    }
  }

  window.DepGraph = DepGraph;
  window.depKey = depKey;
})();
