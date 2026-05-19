/* Node-graph event editor — replaces the old chain table.
 *
 * Visual model mirrors AudioGraphSheet.kt:
 *   • Each AD doc node is a rounded box on the graph with one input port (●
 *     on the left) and one output port (● on the right).
 *   • Each AD doc event is an edge — a cubic bezier from the source node's
 *     output port to the target node's input port — labelled with
 *     "fromEvent → action".
 *
 * Interactions:
 *   - Drag node body              → move the node (updates graphX/graphY)
 *   - Drag from output port       → rubber-band wire → drop on input port
 *                                   → new event (defaults: onEnter → Show)
 *   - Click on edge               → select it; inline editor below the
 *                                   canvas exposes from/event/action/target
 *                                   + duplicate / delete buttons
 *   - Click node                  → also selects the node in AD.state so the
 *                                   right-panel properties update
 *   - Mouse wheel                 → zoom (anchored to cursor)
 *   - Middle-button drag          → pan (alt: drag from empty area)
 */
(function () {
  const AD = (window.AD = window.AD || {});
  const UI = (AD.ui = AD.ui || {});
  const NS = "http://www.w3.org/2000/svg";

  /* Visual constants. Trigger / aura get the wide pill; event / action
   * get a slightly smaller, distinct rounded chip. Hit-testing per node
   * uses each node's own dimensions via `sizeOf()`. */
  const NODE_W   = 150;
  const NODE_H   = 56;
  const EV_W     = 124;
  const EV_H     = 44;
  const PORT_R   = 6;
  const PORT_HIT = 14;   // px in world space
  const EDGE_HIT = 8;    // px in world space

  function sizeOf(n) {
    if (n.kind === "event" || n.kind === "action" ||
        n.kind === "animation" || n.kind === "element" ||
        n.kind === "flow" || n.kind === "function") {
      return { w: EV_W, h: EV_H };
    }
    return { w: NODE_W, h: NODE_H };
  }

  let svg = null;
  let inspector = null;

  // Drag state.
  let dragMode = null;     // "pan" | "move-node" | "wire" | "marquee"
  let dragNodeId = null;
  let dragLast = null;     // world coords
  let dragStart = null;    // for pan: screen coords + initial pan offset
  let wireFromId = null;
  /** When the wire was started from a named output port (eg. "then" /
   *  "else" on an if-else node), the port name is recorded here and
   *  written onto the new connection as `conn.fromPort` on drop. Null
   *  means default port — the connection omits `fromPort` to keep
   *  legacy docs untouched. */
  let wireFromPort = null;
  let wireTo = null;       // world coords of cursor while wiring
  let dragMoved = false;   // distinguishes a drag from a click

  // Marquee rubber-band for multi-select (Shift+drag in empty area).
  let marqueeStart = null; // world coords
  let marqueeEnd   = null;
  let marqueeAdditive = false;

  // Edge selection (index into AD.state.doc.events).
  let selectedEdgeIdx = null;

  function selectGraphNode(id, additive) {
    if (additive) {
      const i = AD.state.graphSelection.indexOf(id);
      if (i >= 0) {
        AD.state.graphSelection.splice(i, 1);
        AD.state.selectedId = AD.state.graphSelection[AD.state.graphSelection.length - 1] || null;
      } else {
        AD.state.graphSelection.push(id);
        AD.state.selectedId = id;
      }
    } else if (AD.state.graphSelection.length > 1 &&
               AD.state.graphSelection.includes(id)) {
      // Plain click on a node that's already part of the multi-selection
      // — keep the whole selection so the next drag moves everyone
      // together. Just promote it to "primary" for the right panel.
      AD.state.selectedId = id;
    } else {
      AD.state.graphSelection = [id];
      AD.state.selectedId = id;
    }
  }

  function isGraphSelected(id) {
    return AD.state.graphSelection && AD.state.graphSelection.includes(id);
  }

  /* ── DOM / utility ────────────────────────────────────────────────── */

  function el(name, attrs) {
    const e = document.createElementNS(NS, name);
    if (attrs) for (const k in attrs) {
      if (attrs[k] !== null && attrs[k] !== undefined) e.setAttribute(k, attrs[k]);
    }
    return e;
  }

  function view() {
    const s = AD.state.doc.settings;
    if (!s.eventGraphView) s.eventGraphView = { zoom: 1, panX: 40, panY: 20 };
    return s.eventGraphView;
  }

  function screenToWorld(clientX, clientY) {
    const r = svg.getBoundingClientRect();
    const v = view();
    return {
      x: (clientX - r.left - v.panX) / v.zoom,
      y: (clientY - r.top  - v.panY) / v.zoom,
    };
  }

  function sizeCanvas() {
    if (!svg) return;
    const r = svg.getBoundingClientRect();
    svg.setAttribute("viewBox", `0 0 ${r.width} ${r.height}`);
  }

  /* ── Auto-layout ──────────────────────────────────────────────────── */

  function ensureLayout() {
    // Find the rightmost positioned node so newcomers don't collide.
    let cursorX = 40;
    for (const n of AD.state.doc.nodes) {
      if (n.graphX != null && n.graphY != null) {
        cursorX = Math.max(cursorX, n.graphX + NODE_W + 50);
      }
    }
    for (const n of AD.state.doc.nodes) {
      if (n.graphX == null || n.graphY == null) {
        n.graphX = cursorX;
        n.graphY = 50;
        cursorX += NODE_W + 50;
      }
    }
  }

  /* ── Hit tests (world coords) ─────────────────────────────────────── */

  function portInputPos(n)  { const s = sizeOf(n); return { x: n.graphX,         y: n.graphY + s.h / 2 }; }
  function portOutputPos(n, port) {
    const ports = outputPortsFor(n);
    if (port) {
      const m = ports.find((p) => p.name === port);
      if (m) return { x: m.x, y: m.y };
    }
    return { x: ports[0].x, y: ports[0].y };
  }
  /** Output-port descriptors for a node. Most nodes have one port
   *  named "out" at the right-middle. Multi-branch flow nodes
   *  (currently just `if-else`) have multiple labelled ports stacked
   *  vertically along the right edge — connections record their
   *  `fromPort` so the runtime can route to the right branch. */
  function outputPortsFor(n) {
    const s = sizeOf(n);
    if (n.kind === "flow" && n.flowKind === "if-else") {
      return [
        { name: "then", label: "T", x: n.graphX + s.w, y: n.graphY + s.h * 0.33 },
        { name: "else", label: "E", x: n.graphX + s.w, y: n.graphY + s.h * 0.67 },
      ];
    }
    return [{ name: "out", label: "", x: n.graphX + s.w, y: n.graphY + s.h / 2 }];
  }

  /* ── Dynamic edge geometry ──────────────────────────────────────────
   * Wires attach to the closest side of each node based on the line
   * between centres — they're no longer pinned to fixed left/right ports.
   * `nodeAttachPoint(n, toward)` returns the intersection of that line
   * with the node's bbox; `edgeNormal` returns an outward unit vector for
   * the side we landed on so the bezier control point can leave the
   * node perpendicular to that side. */
  function nodeCenter(n) {
    const s = sizeOf(n);
    return { x: n.graphX + s.w / 2, y: n.graphY + s.h / 2 };
  }
  function nodeAttachPoint(n, towards) {
    const s = sizeOf(n);
    const cx = n.graphX + s.w / 2;
    const cy = n.graphY + s.h / 2;
    const dx = towards.x - cx;
    const dy = towards.y - cy;
    if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) {
      return { x: cx + s.w / 2, y: cy };
    }
    const tx = (s.w / 2) / Math.max(0.001, Math.abs(dx));
    const ty = (s.h / 2) / Math.max(0.001, Math.abs(dy));
    const t = Math.min(tx, ty);
    return { x: cx + dx * t, y: cy + dy * t };
  }
  function edgeNormal(n, p) {
    const s = sizeOf(n);
    const cx = n.graphX + s.w / 2;
    const cy = n.graphY + s.h / 2;
    const dx = p.x - cx;
    const dy = p.y - cy;
    const projX = Math.abs(dx) / Math.max(0.001, s.w / 2);
    const projY = Math.abs(dy) / Math.max(0.001, s.h / 2);
    if (projX > projY) return { x: Math.sign(dx) || 1, y: 0 };
    return { x: 0, y: Math.sign(dy) || 1 };
  }
  function buildEdgePath(from, to, fromPort) {
    // When the source has multiple labelled output ports (if-else)
    // and the connection records `fromPort`, anchor the wire at that
    // port instead of letting the "closest side wins" logic snap to
    // a different y. For default-port connections we keep the
    // existing dynamic-attach behaviour so single-port nodes still
    // re-route gracefully on layout changes.
    const ports = outputPortsFor(from);
    const useFixedSource = fromPort && ports.length > 1 &&
                           ports.some((p) => p.name === fromPort);
    const p0 = useFixedSource
      ? portOutputPos(from, fromPort)
      : nodeAttachPoint(from, nodeCenter(to));
    const p3 = nodeAttachPoint(to,   nodeCenter(from));
    const n0 = useFixedSource ? { x: 1, y: 0 } : edgeNormal(from, p0);
    const n3 = edgeNormal(to,   p3);
    const dist = Math.hypot(p3.x - p0.x, p3.y - p0.y);
    const k = Math.max(40, Math.min(180, dist * 0.5));
    return {
      p0, p3,
      p1: { x: p0.x + n0.x * k, y: p0.y + n0.y * k },
      p2: { x: p3.x + n3.x * k, y: p3.y + n3.y * k },
    };
  }
  function bezierD(b) {
    return `M ${b.p0.x},${b.p0.y} C ${b.p1.x},${b.p1.y} ${b.p2.x},${b.p2.y} ${b.p3.x},${b.p3.y}`;
  }
  function arrowHeadD(p2, p3, len) {
    const tx = p3.x - p2.x, ty = p3.y - p2.y;
    const tl = Math.hypot(tx, ty) || 1;
    const tnx = tx / tl, tny = ty / tl;
    const L = len || 16;
    const W = L * 0.55;
    const bx = p3.x - tnx * L;
    const by = p3.y - tny * L;
    const px = -tny, py = tnx;
    return `M ${p3.x},${p3.y} L ${bx + px * W},${by + py * W} L ${bx - px * W},${by - py * W} Z`;
  }

  function hitNodeBody(p) {
    // Reverse so the topmost (later in array) wins.
    for (let i = AD.state.doc.nodes.length - 1; i >= 0; i--) {
      const n = AD.state.doc.nodes[i];
      if (n.graphX == null) continue;
      const s = sizeOf(n);
      if (p.x >= n.graphX && p.x <= n.graphX + s.w &&
          p.y >= n.graphY && p.y <= n.graphY + s.h) return n;
    }
    return null;
  }

  /** Scan every output port across every node and return the closest
   *  one within hit tolerance. Returns `{ node, port }` with port =
   *  port name (e.g. "out", "then", "else"). Callers that only care
   *  about the node read `.node`. Null when nothing's near. */
  function hitOutputPort(p) {
    const tol = PORT_HIT / view().zoom;
    let bestNode = null, bestPort = null, bestD = tol;
    for (const n of AD.state.doc.nodes) {
      if (n.graphX == null) continue;
      for (const port of outputPortsFor(n)) {
        const d = Math.hypot(p.x - port.x, p.y - port.y);
        if (d < bestD) { bestNode = n; bestPort = port.name; bestD = d; }
      }
    }
    return bestNode ? { node: bestNode, port: bestPort } : null;
  }

  function hitInputPort(p) {
    const tol = PORT_HIT / view().zoom;
    let best = null, bestD = tol;
    for (const n of AD.state.doc.nodes) {
      if (n.graphX == null) continue;
      const ip = portInputPos(n);
      const d = Math.hypot(p.x - ip.x, p.y - ip.y);
      if (d < bestD) { best = n; bestD = d; }
    }
    return best;
  }

  function distSeg(px, py, x1, y1, x2, y2) {
    const dx = x2 - x1, dy = y2 - y1;
    const len2 = dx * dx + dy * dy;
    if (len2 === 0) return Math.hypot(px - x1, py - y1);
    let t = ((px - x1) * dx + (py - y1) * dy) / len2;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
  }

  /* ── Floating settings popover for event / action / animation nodes ── */

  function showNodePopover(n) {
    const pop = document.getElementById("nodePopover");
    if (!pop) return;
    pop.innerHTML = buildPopover(n);
    positionPopover(pop, n);
    pop.style.display = "block";
    wirePopover(n, pop);
  }
  function hideNodePopover() {
    const pop = document.getElementById("nodePopover");
    if (pop) pop.style.display = "none";
  }
  function positionPopover(pop, n) {
    const s = sizeOf(n);
    const v = view();
    const r = svg.getBoundingClientRect();
    const nodeRight = r.left + v.panX + (n.graphX + s.w) * v.zoom;
    const nodeLeft  = r.left + v.panX + n.graphX * v.zoom;
    const nodeTop   = r.top  + v.panY + n.graphY * v.zoom;
    const popW = 300;
    let left = nodeRight + 12;
    if (left + popW > window.innerWidth - 8) {
      left = nodeLeft - popW - 12;
      if (left < 4) left = 4;
    }
    let top = nodeTop - 4;
    const popH = 360;
    if (top + popH > window.innerHeight - 8) {
      top = Math.max(8, window.innerHeight - popH - 8);
    }
    if (top < 8) top = 8;
    pop.style.left = left + "px";
    pop.style.top  = top  + "px";
  }
  /* Generic plugin-param renderer. Walks the plugin spec's `params`
   * array and emits a widget row per param. Used by flow nodes today;
   * element nodes still use their bespoke single-param form for
   * back-compat with existing plugins. */
  function renderPluginParams(n, params) {
    const w = AD.ui.widgets;
    if (!params || !params.length) return "";
    let html = "";
    for (const p of params) {
      const id = "pop_p_" + p.name;
      const cur = n[p.name] !== undefined ? n[p.name] : p.default;
      switch (p.type) {
        case "number":
        case "range": {
          const fmt = p.unit
            ? (x) => `${x}${p.unit}`
            : (p.format || ((x) => `${x}`));
          html += w.rangeField(id, p.label, +cur || 0,
            p.min ?? 0, p.max ?? 1000, p.step ?? 1, fmt);
          break;
        }
        case "select":
          html += w.selectField(id, p.label, cur || (p.options && p.options[0]),
            p.options || []);
          break;
        case "combo": {
          // Autocomplete combo box: dropdown of suggestions + free
          // text entry. Used for "field key" pickers where the most
          // common values are predictable but the user must be able
          // to type anything (a future-added field, a node-only key
          // not in the suggestion list, …).
          const val = String(cur == null ? "" : cur)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
          const listId = id + "_list";
          const opts = (p.options || []).map((o) => {
            const safe = String(o).replace(/"/g, "&quot;");
            return `<option value="${safe}">`;
          }).join("");
          html += `<div class="row"><label>${p.label}</label>
            <input id="${id}" type="text" list="${listId}" value="${val}"
                   autocomplete="off"
                   placeholder="${p.placeholder || ""}">
            <datalist id="${listId}">${opts}</datalist>
          </div>`;
          break;
        }
        case "toggle":
          html += w.toggleField(id, p.label, !!cur);
          break;
        case "text":
        default: {
          const val = String(cur == null ? "" : cur)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
          html += `<div class="row"><label>${p.label}</label>
            <input id="${id}" type="text" value="${val}"
                   placeholder="${p.placeholder || ""}"></div>`;
        }
      }
    }
    return html;
  }
  function wirePluginParams(n, params) {
    const W = AD.ui.widgets;
    if (!params) return;
    for (const p of params) {
      const id = "pop_p_" + p.name;
      switch (p.type) {
        case "number":
        case "range":
          W.wireRange(id, (v) => { n[p.name] = v; });
          break;
        case "select":
          W.wireSelect(id, (v) => { n[p.name] = v; });
          break;
        case "toggle":
          W.wireToggle(id, () => !!n[p.name], (v) => { n[p.name] = v; });
          break;
        case "combo":
        case "text":
        default: {
          const inp = document.getElementById(id);
          if (inp) {
            inp.addEventListener("input",  (e) => { n[p.name] = e.target.value; });
            inp.addEventListener("change", () => AD.history.push());
          }
        }
      }
    }
  }
  /** Default visible label for a node when `n.label` is empty —
   *  matches what drawNode falls back to in the chain graph. Used
   *  in the popover's Label-field placeholder so the user sees the
   *  current default before typing. */
  function defaultLabelFor(n) {
    if (n.kind === "event")     return n.eventKind || "event";
    if (n.kind === "action")    return n.actionKind || "action";
    if (n.kind === "animation") return n.animationKind || "fade-in";
    if (n.kind === "element")   return n.elementKind || "element";
    if (n.kind === "flow")      return n.flowKind || "flow";
    if (n.kind === "function")  return n.functionKind || "function";
    return n.id || "node";
  }

  function buildPopover(n) {
    const w = AD.ui.widgets;
    const kindText =
      n.kind === "event"     ? "EVENT" :
      n.kind === "action"    ? "ACTION" :
      n.kind === "animation" ? "ANIMATION" :
      n.kind === "element"   ? "ELEMENT" :
      n.kind === "flow"      ? "FLOW" :
      n.kind === "function"  ? "FUNCTION" : "NODE";
    // Every popover gets a Label text field at the top — lets the
    // user give any node (trigger / aura / event / action / animation
    // / element) a human-readable display name that overrides the
    // default kind text in the chain graph.
    const labelVal = (n.label || "").replace(/&/g, "&amp;").replace(/"/g, "&quot;");
    const labelRow =
      `<div class="row"><label>Label</label>
         <input id="pop_label" type="text" value="${labelVal}"
                placeholder="(uses ${defaultLabelFor(n)})"></div>`;
    let body = labelRow;
    if (n.kind === "trigger" || n.kind === "aura") {
      body +=
        w.colorRow   ("pop_nodeColor",   "Color",             n.color, "#FFD447") +
        w.toggleField("pop_initVisible", "Initially visible", !!n.initiallyVisible) +
        w.toggleField("pop_initEnabled", "Initially enabled", !!n.initiallyEnabled) +
        w.toggleField("pop_isAnchor",    "Layout anchor",     !!n.isAnchor) +
        w.toggleField("pop_hitTest",     "Hit-test",          n.hitTest !== false);
    } else if (n.kind === "event") {
      body += w.selectField("pop_evKind", "Event", n.eventKind || "onEnter", AD.EVENT_KINDS);
    } else if (n.kind === "action") {
      body += w.selectField("pop_acKind", "Action", n.actionKind || "Show", AD.ACTION_KINDS);
    } else if (n.kind === "element") {
      // Native element — kind dropdown + per-kind param (Toast text /
      // Vibrate ms / Speak phrase / etc.).
      const elKinds = AD.ELEMENT_KINDS.map((e) => e.kind);
      body += w.selectField("pop_elKind", "Element", n.elementKind || elKinds[0], elKinds);
      const meta = AD.elementMeta(n.elementKind);
      if (meta && meta.paramLabel) {
        const val = (n.elementParam != null ? n.elementParam : "")
          .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
        body += `<div class="row">
          <label>${meta.paramLabel}</label>
          <input id="pop_elParam" type="${meta.paramType || "text"}"
                 placeholder="${meta.paramPlaceholder || ""}" value="${val}">
        </div>`;
      }
    } else if (n.kind === "flow") {
      // Flow node: kind dropdown + generic params from plugin spec.
      const flKinds = AD.FLOW_KINDS.map((e) => e.kind);
      body += w.selectField("pop_flKind", "Flow", n.flowKind || flKinds[0], flKinds);
      const plugin = AD.flowMeta(n.flowKind);
      if (plugin) body += renderPluginParams(n, plugin.params);
    } else if (n.kind === "function") {
      // Function node: app-side API surface. Same generic-params
      // approach as flow — pluginu spec deklarē params + types.
      const fnKinds = AD.FUNCTION_KINDS.map((e) => e.kind);
      body += w.selectField("pop_fnKind", "Function", n.functionKind || fnKinds[0], fnKinds);
      const plugin = AD.functionMeta(n.functionKind);
      if (plugin) body += renderPluginParams(n, plugin.params);
    } else if (n.kind === "animation") {
      body +=
        w.selectField("pop_anKind",  "Kind",     n.animationKind || "fade-in", AD.ANIMATION_KINDS) +
        w.rangeField ("pop_anDur",   "Duration", n.animDuration  || 500, 50, 5000, 50,
                      (x) => `${x.toFixed(0)} ms`) +
        w.rangeField ("pop_anSpd",   "Speed",    n.animSpeed     || 1,   0.1, 5, 0.1,
                      (x) => `${x.toFixed(2)}×`) +
        w.rangeField ("pop_anIntensity", "Intensity",
                      (n.animIntensity != null ? n.animIntensity : 1) * 100,
                      0, 100, 1, (x) => `${x.toFixed(0)}%`) +
        w.selectField("pop_anCurve", "Curve",    n.animCurve     || "ease-out", AD.ANIM_CURVES) +
        w.segmentedField("pop_anDir", "Dir",     n.animDirection || "forward", [
          { value: "forward",   label: "▶" },
          { value: "backward",  label: "◀" },
          { value: "alternate", label: "↔" },
        ]) +
        w.toggleField("pop_anLoop", "Loop", !!n.animLoop) +
        w.toggleField("pop_anInt",  "Interrupt", !!n.animInterrupt);
    }
    return `
      <div class="pop-head">
        <span class="dot" style="background:${n.color}; color:${n.color};"></span>
        <span class="kind">${kindText}</span>
        <span class="id">${n.id}</span>
      </div>
      ${body}
      <div class="pop-actions">
        <button class="pop-dup" title="Duplicate this node">⧉ Duplicate</button>
        <button class="pop-del" title="Delete this node">× Delete</button>
      </div>`;
  }
  function wirePopover(n, root) {
    const W = AD.ui.widgets;
    // Label is editable on every node kind. drawNode uses it when
    // non-empty, falling back to the kind / id otherwise.
    const lbl = document.getElementById("pop_label");
    if (lbl) {
      lbl.addEventListener("input",  (e) => { n.label = e.target.value; AD.renderAll(); });
      lbl.addEventListener("change", () => AD.history.push());
    }
    if (n.kind === "trigger" || n.kind === "aura") {
      W.wireColor("pop_nodeColor", (v) => { n.color = v; AD.renderAll(); });
      W.wireToggle("pop_initVisible",
        () => !!n.initiallyVisible, (v) => { n.initiallyVisible = v; });
      W.wireToggle("pop_initEnabled",
        () => !!n.initiallyEnabled, (v) => { n.initiallyEnabled = v; });
      W.wireToggle("pop_isAnchor",
        () => !!n.isAnchor, (v) => {
          if (v) {
            // Only one anchor per doc — clear the flag elsewhere.
            for (const x of AD.state.doc.nodes) x.isAnchor = false;
          }
          n.isAnchor = v;
        });
      W.wireToggle("pop_hitTest",
        () => n.hitTest !== false, (v) => { n.hitTest = v; });
      return;
    }
    if (n.kind === "event") {
      W.wireSelect("pop_evKind", (v) => {
        // Keep the user's custom label if they set one — only sync
        // label to the kind when it was empty / matched the prior
        // default (so freshly-added nodes still auto-rename).
        if (!n.label || n.label === n.eventKind) n.label = v;
        n.eventKind = v; n.color = AD.eventKindColor(v);
      });
    } else if (n.kind === "action") {
      W.wireSelect("pop_acKind", (v) => {
        if (!n.label || n.label === n.actionKind) n.label = v;
        n.actionKind = v; n.color = AD.actionKindColor(v);
      });
    } else if (n.kind === "element") {
      W.wireSelect("pop_elKind", (v) => {
        const meta = AD.elementMeta(v); if (!meta) return;
        // Preserve user-set labels; sync only when default-matched.
        if (!n.label || n.label === (AD.elementMeta(n.elementKind)?.label)) n.label = meta.label;
        n.elementKind = v; n.color = meta.color;
        // Param spec may have changed — re-open the popover so the
        // input field appears / disappears for the new kind.
        showNodePopover(n);
      });
      const elIn = document.getElementById("pop_elParam");
      if (elIn) {
        elIn.addEventListener("input",  (e) => { n.elementParam = e.target.value; });
        elIn.addEventListener("change", () => AD.history.push());
      }
    } else if (n.kind === "flow") {
      W.wireSelect("pop_flKind", (v) => {
        const plugin = AD.flowMeta(v); if (!plugin) return;
        if (!n.label || n.label === (AD.flowMeta(n.flowKind)?.label)) n.label = plugin.label;
        n.flowKind = v; n.color = plugin.color;
        // Seed defaults of the new plugin's params if the user hasn't
        // already set those fields. This lets switching kind feel
        // sensible (delay→counter shows step=1, etc.).
        if (plugin.params) {
          for (const p of plugin.params) {
            if (p.default !== undefined && n[p.name] === undefined) {
              n[p.name] = p.default;
            }
          }
        }
        showNodePopover(n);
      });
      const plugin = AD.flowMeta(n.flowKind);
      if (plugin) wirePluginParams(n, plugin.params);
    } else if (n.kind === "function") {
      W.wireSelect("pop_fnKind", (v) => {
        const plugin = AD.functionMeta(v); if (!plugin) return;
        if (!n.label || n.label === (AD.functionMeta(n.functionKind)?.label)) n.label = plugin.label;
        n.functionKind = v;
        n.targetClass  = plugin.targetClass || "";
        n.methodName   = plugin.methodName  || "";
        n.color        = plugin.color;
        if (plugin.params) {
          for (const p of plugin.params) {
            if (p.default !== undefined && n[p.name] === undefined) {
              n[p.name] = p.default;
            }
          }
        }
        showNodePopover(n);
      });
      const fnPlugin = AD.functionMeta(n.functionKind);
      if (fnPlugin) wirePluginParams(n, fnPlugin.params);
    } else if (n.kind === "animation") {
      W.wireSelect("pop_anKind", (v) => {
        if (!n.label || n.label === n.animationKind) n.label = v;
        n.animationKind = v; n.color = AD.animKindColor(v);
      });
      W.wireRange("pop_anDur",       (v) => (n.animDuration  = v),       (x) => `${x.toFixed(0)} ms`);
      W.wireRange("pop_anSpd",       (v) => (n.animSpeed     = v),       (x) => `${x.toFixed(2)}×`);
      W.wireRange("pop_anIntensity", (v) => (n.animIntensity = v / 100), (x) => `${x.toFixed(0)}%`);
      W.wireSelect("pop_anCurve",    (v) => (n.animCurve     = v));
      W.wireSegmented("pop_anDir", (v) => (n.animDirection = v));
      W.wireToggle("pop_anLoop", () => !!n.animLoop,      (v) => (n.animLoop      = v));
      W.wireToggle("pop_anInt",  () => !!n.animInterrupt, (v) => (n.animInterrupt = v));
    }
    root.querySelector(".pop-dup").addEventListener("click", () => {
      const copy = JSON.parse(JSON.stringify(n));
      const prefix = n.kind === "event"     ? "ev_"
                  : n.kind === "action"    ? "ac_"
                  : n.kind === "animation" ? "an_"
                  : n.kind === "element"   ? "el_"
                  : n.kind === "flow"      ? "fl_"
                  : n.kind === "function"  ? "fn_"
                  : "n_";
      copy.id = prefix + AD.uuid().slice(2);
      copy.graphX = (n.graphX || 0) + 30;
      copy.graphY = (n.graphY || 0) + 30;
      AD.state.doc.nodes.push(copy);
      AD.state.selectedId = copy.id;
      hideNodePopover();
      AD.history.push();
      AD.renderAll();
    });
    root.querySelector(".pop-del").addEventListener("click", () => {
      hideNodePopover();
      deleteNode(n);
    });
  }

  /* Re-anchor the popover to its node after any view transform changes. */
  function repositionPopoverIfOpen() {
    const pop = document.getElementById("nodePopover");
    if (!pop || pop.style.display === "none") return;
    const n = AD.state.doc.nodes.find((x) => x.id === AD.state.selectedId);
    if (n && (n.kind === "event" || n.kind === "action" ||
              n.kind === "animation" || n.kind === "element" ||
              n.kind === "flow" || n.kind === "function")) {
      positionPopover(pop, n);
    } else {
      hideNodePopover();
    }
  }

  function deleteNode(n) {
    if (!n) return;
    // Trigger / aura carry shapes — confirm so a stray click doesn't wipe them.
    if ((n.kind === "trigger" || n.kind === "aura") &&
        n.shapes && n.shapes.length > 0) {
      if (!confirm(`Delete node "${n.id}" (${n.shapes.length} shape${n.shapes.length === 1 ? "" : "s"})?`)) return;
    }
    AD.state.doc.nodes  = AD.state.doc.nodes.filter((x) => x.id !== n.id);
    AD.state.doc.events = AD.state.doc.events.filter(
      (e) => e.fromId !== n.id && e.toId !== n.id && e.targetId !== n.id);
    AD.state.graphSelection = (AD.state.graphSelection || []).filter((id) => id !== n.id);
    AD.state.selectedId       =
      AD.state.graphSelection[AD.state.graphSelection.length - 1] ||
      AD.state.doc.nodes[0]?.id || null;
    AD.state.selectedShapeIdx = null;
    AD.state.selectedShapeIdxs = [];
    selectedEdgeIdx = null;
    AD.history.push();
    AD.renderAll();
  }
  // Expose for input.js's Delete-key handler.
  AD.deleteNodeById = function (id) {
    deleteNode(AD.state.doc.nodes.find((n) => n.id === id));
  };

  function hitEdge(p) {
    const tol = EDGE_HIT / view().zoom;
    let bestIdx = -1, bestD = tol;
    const nodes = AD.state.doc.nodes;
    for (let i = 0; i < AD.state.doc.events.length; i++) {
      const ev = AD.state.doc.events[i];
      const from = nodes.find((n) => n.id === ev.fromId);
      const to   = nodes.find((n) => n.id === (ev.toId != null ? ev.toId : ev.targetId));
      if (!from || !to || from.graphX == null || to.graphX == null) continue;
      const path = buildEdgePath(from, to, ev.fromPort);
      let prevX = path.p0.x, prevY = path.p0.y;
      for (let s = 1; s <= 24; s++) {
        const t = s / 24;
        const u = 1 - t;
        const bx = u*u*u*path.p0.x + 3*u*u*t*path.p1.x + 3*u*t*t*path.p2.x + t*t*t*path.p3.x;
        const by = u*u*u*path.p0.y + 3*u*u*t*path.p1.y + 3*u*t*t*path.p2.y + t*t*t*path.p3.y;
        const d = distSeg(p.x, p.y, prevX, prevY, bx, by);
        if (d < bestD) { bestIdx = i; bestD = d; }
        prevX = bx; prevY = by;
      }
    }
    return bestIdx;
  }

  /* ── Edge styling ─────────────────────────────────────────────────── */

  function edgeColor(conn) {
    const nodes = AD.state.doc.nodes;
    const from = nodes.find((n) => n.id === conn.fromId);
    const to   = nodes.find((n) => n.id === conn.toId);
    if (from && from.kind === "event")  return AD.eventKindColor(from.eventKind);
    if (from && from.kind === "action") return AD.actionKindColor(from.actionKind);
    if (to && to.kind === "event")      return AD.eventKindColor(to.eventKind);
    if (to && to.kind === "action")     return AD.actionKindColor(to.actionKind);
    return "#7AB8FF";
  }

  /* ── Drawing ──────────────────────────────────────────────────────── */

  function drawNode(parent, n) {
    const isSelected = isGraphSelected(n.id) || AD.state.selectedId === n.id;
    const rt = AD.runtime ? AD.runtime(n.id) : null;
    const isActive   = rt && rt.active;
    const isHidden   = rt && !rt.visible;
    const isDisabled = rt && !rt.enabled;
    const flash      = AD.state.flashActive;
    const inFlash    = flash && flash.nodes.has(n.id);
    const flashDim   = flash && !inFlash;
    const s = sizeOf(n);
    const isMini = (n.kind === "event" || n.kind === "action" ||
                    n.kind === "animation" || n.kind === "element" ||
                    n.kind === "flow" || n.kind === "function");

    const g = el("g", {
      transform: `translate(${n.graphX} ${n.graphY})`,
      "data-node-id": n.id,
    });
    // Flash dims non-active nodes; otherwise, hidden / disabled still dim.
    if (flashDim)        g.setAttribute("opacity", "0.18");
    else if (isHidden)   g.setAttribute("opacity", "0.30");
    else if (isDisabled) g.setAttribute("opacity", "0.55");

    // Bright outer pulse for nodes inside the active chain.
    if (inFlash) {
      g.appendChild(el("rect", {
        x: -7, y: -7, width: s.w + 14, height: s.h + 14, rx: 12, ry: 12,
        fill: "none", stroke: "#FFD447", "stroke-width": 2.5, opacity: 0.85,
      }));
    }

    if (isActive) {
      g.appendChild(el("rect", {
        x: -5, y: -5, width: s.w + 10, height: s.h + 10, rx: 11, ry: 11,
        fill: "none", stroke: "#FFD447", "stroke-width": 2, opacity: 0.7,
      }));
    }
    if (isSelected && !isActive) {
      g.appendChild(el("rect", {
        x: -3, y: -3, width: s.w + 6, height: s.h + 6, rx: 9, ry: 9,
        fill: "none", stroke: n.color, "stroke-width": 1, opacity: 0.35,
      }));
    }
    g.appendChild(el("rect", {
      x: 0, y: 0, width: s.w, height: s.h, rx: 7, ry: 7,
      fill: isActive ? "#2a2620" : "#1a1a1d",
      stroke: isActive ? "#FFD447" : (isSelected ? "#FFD447" : "#3a3a42"),
      "stroke-width": (isActive || isSelected) ? 1.5 : 1,
    }));

    // Kind indicator stripe.
    const elementPlugin = (n.kind === "element")
      ? (AD.nodePlugin && AD.nodePlugin("element", n.elementKind))
      : null;
    const flowPlugin = (n.kind === "flow")
      ? (AD.nodePlugin && AD.nodePlugin("flow", n.flowKind))
      : null;
    const functionPlugin = (n.kind === "function")
      ? (AD.nodePlugin && AD.nodePlugin("function", n.functionKind))
      : null;
    // Single source for the icon-bearing plugin spec (element / flow
    // / function all render the same way in the mini-card branch).
    const iconPlugin = elementPlugin || flowPlugin || functionPlugin;
    const stripe =
      n.kind === "event"     ? AD.eventKindColor(n.eventKind) :
      n.kind === "action"    ? AD.actionKindColor(n.actionKind) :
      n.kind === "animation" ? AD.animKindColor(n.animationKind) :
      n.kind === "element"   ? ((elementPlugin && elementPlugin.color) || "#7AD8FF") :
      n.kind === "flow"      ? ((flowPlugin && flowPlugin.color) || "#FFD180") :
      n.kind === "function"  ? ((functionPlugin && functionPlugin.color) || "#7AB8FF") :
      // Trigger / aura: use the node's own color so the color-picker
      // change is visible across the whole card (stripe + ring + glyph).
      (n.kind === "aura" || n.kind === "trigger") ? (n.color || "#7AB8FF") :
      "#FFD447";
    g.appendChild(el("rect", {
      x: 0, y: 0, width: 3, height: s.h, fill: stripe, opacity: 0.8,
    }));

    if (isMini) {
      // Element nodes: icon + kind label. The icon is the Lucide
      // SVG inlined as an <image href="data:..."> so we don't have
      // to parse / append svg children manually. Falls back to the
      // plain centred label if the icon library wasn't loaded
      // (file:// origin) or the plugin didn't declare an icon ref.
      if (iconPlugin && iconPlugin.icon &&
          AD._icons && AD._icons[iconPlugin.icon]) {
        const iconSpec = AD._icons[iconPlugin.icon];
        // Inline-SVG → base64 data URL; recolor stroke to match
        // the node's stripe so the icon visually matches its kind.
        const tinted = iconSpec.svg.replace(/stroke="[^"]*"/g, `stroke="${stripe}"`);
        const dataUrl = "data:image/svg+xml;base64," +
          btoa(unescape(encodeURIComponent(tinted)));
        const ico = el("image", {
          x: 6, y: (s.h - 22) / 2,
          width: 22, height: 22,
          preserveAspectRatio: "xMidYMid meet",
        });
        ico.setAttributeNS("http://www.w3.org/1999/xlink", "href", dataUrl);
        ico.setAttribute("href", dataUrl);
        g.appendChild(ico);
        // Kind label sits to the right of the icon.
        const lbl = el("text", {
          x: 32, y: s.h / 2 + 1, fill: stripe,
          "font-size": 11, "font-family": "ui-monospace, Menlo, Consolas, monospace",
          "dominant-baseline": "middle", "font-weight": 600,
        });
        const kindText =
          (n.kind === "flow")     ? n.flowKind :
          (n.kind === "function") ? n.functionKind :
          n.elementKind;
        lbl.textContent = n.label || kindText || iconPlugin.label || n.kind;
        g.appendChild(lbl);
      } else {
        // Compact pill: prefer user-set label, fall back to kind.
        const big = el("text", {
          x: s.w / 2, y: s.h / 2 + 1, fill: stripe,
          "font-size": 12, "font-family": "ui-monospace, Menlo, Consolas, monospace",
          "text-anchor": "middle", "dominant-baseline": "middle",
          "font-weight": 600,
        });
        big.textContent = n.label ||
          ((n.kind === "event")     ? (n.eventKind || "onEnter") :
           (n.kind === "action")    ? (n.actionKind || "Show")  :
           (n.kind === "animation") ? (n.animationKind || "fade-in") :
           (n.kind === "element")   ? (n.elementKind || "element") :
           (n.kind === "flow")      ? (n.flowKind    || "flow")    :
           (n.kind === "function")  ? (n.functionKind || "function") :
           n.id);
        g.appendChild(big);
      }
      // Animation subtitle: show duration / loop indicator under the kind name.
      if (n.kind === "animation") {
        const sub = el("text", {
          x: s.w / 2, y: s.h - 4, fill: "#666",
          "font-size": 8, "text-anchor": "middle",
          "font-family": "ui-monospace, Menlo, Consolas, monospace",
        });
        const ms    = (n.animDuration != null ? n.animDuration : 500);
        const speed = (n.animSpeed    != null ? n.animSpeed    : 1);
        const head  = n.animLoop ? "↻" : "▶";
        const spdTag = speed !== 1 ? ` · ${speed.toFixed(2)}×` : "";
        sub.textContent = `${head} ${ms}ms${spdTag} · ${n.animCurve || "ease-out"}`;
        g.appendChild(sub);
      }
      // Sub-label (the abstract node "id") for traceability. Animation
      // nodes already show duration + curve at the bottom, so skip the id
      // there to avoid stacking two labels in one strip.
      if (n.kind !== "animation") {
        const sub = el("text", {
          x: s.w - 8, y: s.h - 4, fill: "#555",
          "font-size": 8, "text-anchor": "end",
          "font-family": "ui-monospace, Menlo, Consolas, monospace",
        });
        sub.textContent = n.id;
        g.appendChild(sub);
      }
    } else {
      // Trigger and aura are the same concept now — both get the aura
      // glyph (concentric rings + 4 rays) so they read consistently.
      const cx = 16, cy = 20;
      g.appendChild(el("circle", {
        cx, cy, r: 4, fill: "none",
        stroke: n.color, "stroke-width": 1.5, opacity: 0.95,
      }));
      g.appendChild(el("circle", {
        cx, cy, r: 7.5, fill: "none",
        stroke: n.color, "stroke-width": 1, opacity: 0.55,
      }));
      const rays = [[0, -10, 0, -11.5], [10, 0, 11.5, 0],
                    [0, 10, 0, 11.5], [-10, 0, -11.5, 0]];
      for (const [x1, y1, x2, y2] of rays) {
        g.appendChild(el("line", {
          x1: cx + x1, y1: cy + y1, x2: cx + x2, y2: cy + y2,
          stroke: n.color, "stroke-width": 1.4, opacity: 0.7,
          "stroke-linecap": "round",
        }));
      }
      const display = n.label || n.id;
      const idText = display.length > 17 ? display.slice(0, 16) + "…" : display;
      const lbl = el("text", {
        x: 32, y: 22, fill: "#e6e6e6",
        "font-size": 12, "font-family": "ui-monospace, Menlo, Consolas, monospace",
      });
      lbl.textContent = idText;
      g.appendChild(lbl);
      const sub = el("text", { x: 32, y: 40, fill: "#888", "font-size": 10 });
      if (n.kind === "group") {
        const cc = (n.children && Array.isArray(n.children.nodes))
          ? n.children.nodes.length : 0;
        sub.textContent = `CHAIN · ${cc} node${cc === 1 ? "" : "s"}`;
      } else {
        const shapeCount = (n.shapes || []).length;
        sub.textContent = `AURA · ${shapeCount} shape${shapeCount === 1 ? "" : "s"}`;
      }
      g.appendChild(sub);
    }

    // Output ports. Most nodes have a single one at the right-middle;
    // multi-branch flow nodes (if-else) get one circle per labelled
    // branch stacked vertically. Each port is hit-testable separately
    // so drag-to-connect can record which branch the wire came from.
    const ports = outputPortsFor(n);
    for (const p of ports) {
      const py = p.y - n.graphY;  // port y relative to the group's local origin
      const port = el("g", { class: "node-out-port" });
      port.appendChild(el("circle", {
        cx: s.w + 1, cy: py, r: PORT_R,
        fill: "#1a1a1d",
        stroke: stripe, "stroke-width": 1.5,
      }));
      port.appendChild(el("circle", {
        cx: s.w + 1, cy: py, r: PORT_R - 2.5,
        fill: stripe, opacity: 0.85,
      }));
      if (p.label) {
        const lbl = el("text", {
          x: s.w + 1, y: py + 3,
          fill: "#0a0a0c",
          "font-size": 9,
          "font-family": "ui-monospace, Menlo, Consolas, monospace",
          "font-weight": 700,
          "text-anchor": "middle",
        });
        lbl.textContent = p.label;
        port.appendChild(lbl);
      }
      g.appendChild(port);
    }
    parent.appendChild(g);
  }

  function drawEdge(parent, conn, idx) {
    const nodes = AD.state.doc.nodes;
    const from = nodes.find((n) => n.id === conn.fromId);
    const to   = nodes.find((n) => n.id === (conn.toId != null ? conn.toId : conn.targetId));
    if (!from || !to || from.graphX == null || to.graphX == null) return;

    const path = buildEdgePath(from, to, conn.fromPort);
    const d = bezierD(path);
    const c = edgeColor(conn);
    const isSel    = selectedEdgeIdx === idx;
    const flash    = AD.state.flashActive;
    const inFlash  = flash && flash.edges.has(idx);
    const flashDim = flash && !inFlash;
    const baseOpacity = flashDim ? 0.18 : 0.92;

    // Halo for selection / chain flash.
    if (isSel || inFlash) {
      parent.appendChild(el("path", {
        d, fill: "none", stroke: c, "stroke-width": inFlash ? 10 : 6,
        opacity: inFlash ? 0.32 : 0.20, "pointer-events": "none",
        "stroke-linecap": "round",
      }));
    }
    // Invisible wide hit-area with a <title> child — gives the user
    // a hover tooltip that explains what branch / value activates
    // the wire. For if-else "then" / "else" ports, says it
    // explicitly; for regular connections falls back to source→target.
    const hit = el("path", {
      d, fill: "none", stroke: "transparent", "stroke-width": 14,
      "stroke-linecap": "round", "pointer-events": "stroke",
    });
    const titleEl = el("title");
    let titleText;
    if (conn.fromPort === "then") titleText = `Fires when ${from.id} predicate is TRUE`;
    else if (conn.fromPort === "else") titleText = `Fires when ${from.id} predicate is FALSE`;
    else if (conn.fromPort) titleText = `Port "${conn.fromPort}": ${from.id} → ${to.id}`;
    else titleText = `${from.id} → ${to.id}`;
    titleEl.textContent = titleText;
    hit.appendChild(titleEl);
    parent.appendChild(hit);
    // Underlay (always visible, gives the line continuity behind the
    // flowing dashes).
    parent.appendChild(el("path", {
      d, fill: "none", stroke: c,
      "stroke-width": (inFlash || isSel) ? 2.2 : 1.4,
      opacity: baseOpacity * 0.55,
      "stroke-linecap": "round",
      "pointer-events": "none",
    }));
    // Animated flow — dashes march from source toward target, giving the
    // wire a visible direction even when zoomed out.
    const flow = el("path", {
      d, fill: "none", stroke: c,
      "stroke-width": (inFlash || isSel) ? 3.2 : 2.2,
      "stroke-linecap": "round",
      "stroke-dasharray": "10 14",
      opacity: baseOpacity,
      "pointer-events": "none",
    });
    flow.appendChild(el("animate", {
      attributeName: "stroke-dashoffset",
      from: "0", to: "-24",
      dur: inFlash ? "0.7s" : "1.4s",
      repeatCount: "indefinite",
    }));
    parent.appendChild(flow);
    // Bigger arrowhead at the target attach point, oriented along the
    // bezier's end tangent (so it always points into the node correctly).
    parent.appendChild(el("path", {
      d: arrowHeadD(path.p2, path.p3, inFlash ? 20 : 16),
      fill: c, opacity: flashDim ? 0.3 : 1,
      "pointer-events": "none",
    }));
  }

  function drawWirePreview(parent) {
    const from = AD.state.doc.nodes.find((n) => n.id === wireFromId);
    if (!from || !wireTo) return;
    // Anchor the preview at the specific port the drag started from
    // (then / else on if-else) so the user sees the wire come out of
    // the right circle while they're aiming at a target.
    const ports = outputPortsFor(from);
    const useFixed = wireFromPort && ports.length > 1 &&
                     ports.some((p) => p.name === wireFromPort);
    const p0 = useFixed
      ? portOutputPos(from, wireFromPort)
      : nodeAttachPoint(from, wireTo);
    const p3 = wireTo;
    const n0 = useFixed ? { x: 1, y: 0 } : edgeNormal(from, p0);
    const dist = Math.hypot(p3.x - p0.x, p3.y - p0.y);
    const k = Math.max(30, Math.min(160, dist * 0.5));
    const p1 = { x: p0.x + n0.x * k, y: p0.y + n0.y * k };
    // Approach the cursor along the direction it came from.
    const p2 = { x: p3.x - (p3.x - p0.x) * 0.25, y: p3.y - (p3.y - p0.y) * 0.25 };
    const d = `M ${p0.x},${p0.y} C ${p1.x},${p1.y} ${p2.x},${p2.y} ${p3.x},${p3.y}`;
    parent.appendChild(el("path", {
      d, fill: "none", stroke: "#FFD447", "stroke-width": 2.2,
      "stroke-linecap": "round",
      "stroke-dasharray": "8 8", opacity: 0.85,
      "pointer-events": "none",
    }));
    parent.appendChild(el("path", {
      d: arrowHeadD(p2, p3, 16),
      fill: "#FFD447", opacity: 0.9,
      "pointer-events": "none",
    }));
  }

  function drawGrid(parent, r, v) {
    const step = 40 * v.zoom;
    if (step < 8) return; // too dense, skip
    const offX = ((v.panX % step) + step) % step;
    const offY = ((v.panY % step) + step) % step;
    const grid = el("g", { opacity: 0.05, "pointer-events": "none" });
    for (let x = offX; x < r.width; x += step) {
      grid.appendChild(el("line", {
        x1: x, x2: x, y1: 0, y2: r.height,
        stroke: "#FFD447", "stroke-width": 1,
      }));
    }
    for (let y = offY; y < r.height; y += step) {
      grid.appendChild(el("line", {
        y1: y, y2: y, x1: 0, x2: r.width,
        stroke: "#FFD447", "stroke-width": 1,
      }));
    }
    parent.appendChild(grid);
  }

  /* ── Render ───────────────────────────────────────────────────────── */

  function render() {
    if (!svg) return;
    sizeCanvas();
    svg.innerHTML = "";
    ensureLayout();
    const r = svg.getBoundingClientRect();
    const v = view();

    drawGrid(svg, r, v);

    const world = el("g", {
      transform: `translate(${v.panX} ${v.panY}) scale(${v.zoom})`,
    });
    svg.appendChild(world);

    // Edges drawn first so node bodies overlap them at ports.
    for (let i = 0; i < AD.state.doc.events.length; i++) {
      drawEdge(world, AD.state.doc.events[i], i);
    }
    if (wireFromId && wireTo) drawWirePreview(world);
    for (const n of AD.state.doc.nodes) drawNode(world, n);

    if (marqueeStart && marqueeEnd) {
      const mx0 = Math.min(marqueeStart.x, marqueeEnd.x);
      const my0 = Math.min(marqueeStart.y, marqueeEnd.y);
      const mx1 = Math.max(marqueeStart.x, marqueeEnd.x);
      const my1 = Math.max(marqueeStart.y, marqueeEnd.y);
      world.appendChild(el("rect", {
        x: mx0, y: my0, width: mx1 - mx0, height: my1 - my0,
        fill: "rgba(255,212,71,0.06)",
        stroke: "#FFD447", "stroke-width": 1,
        "stroke-dasharray": "4 3", opacity: 0.7,
        "pointer-events": "none",
      }));
    }

    renderInspector();
  }

  /* ── Inline edge inspector ────────────────────────────────────────── */

  function renderInspector() {
    if (!inspector) return;

    // Edge selected → from/to dropdowns + dup / del.
    if (selectedEdgeIdx != null && AD.state.doc.events[selectedEdgeIdx]) {
      renderEdgeInspector();
      return;
    }
    // Node selected (no edge) → node info + del button.
    const n = AD.state.doc.nodes.find((x) => x.id === AD.state.selectedId);
    if (n) {
      renderNodeInspector(n);
      return;
    }
    // Nothing selected → contextual hint.
    inspector.innerHTML = AD.state.preview
      ? `<span class="hint"><b>Preview</b> · click a node to fire its onEnter chain · click again for onExit · linked Show/Hide actions update the canvas live</span>`
      : `<span class="hint">Drag output → input port to connect · click a node or edge to edit · wheel = zoom · middle-drag = pan</span>`;
  }

  function renderNodeInspector(n) {
    const dot = `<span class="dot" style="background:${n.color}; box-shadow: 0 0 6px ${n.color};"></span>`;
    const kindStr =
      n.kind === "event"     ? `event · ${n.eventKind || "onEnter"}` :
      n.kind === "action"    ? `action · ${n.actionKind || "Show"}` :
      n.kind === "animation" ? `animation · ${n.animationKind || "fade-in"}` :
      n.kind === "element"   ? `element · ${n.elementKind || "?"}` :
      n.kind === "flow"      ? `flow · ${n.flowKind || "?"}` :
      n.kind === "function"  ? `function · ${n.functionKind || "?"}` :
      `${n.kind || "trigger"} · ${(n.shapes || []).length} shape${(n.shapes||[]).length === 1 ? "" : "s"}`;
    inspector.innerHTML = `
      <span class="nodeInfo">
        ${dot}
        <b class="nodeId">${n.id}</b>
        <span class="kind">${kindStr}</span>
      </span>
      <button class="dup" title="Duplicate this node">⧉</button>
      <button class="del" title="Delete this node (Del)">×</button>
    `;
    inspector.querySelector(".dup").addEventListener("click", () => {
      const copy = JSON.parse(JSON.stringify(n));
      const prefix = n.kind === "event"     ? "ev_"
                  : n.kind === "action"    ? "ac_"
                  : n.kind === "animation" ? "an_"
                  : n.kind === "element"   ? "el_"
                  : n.kind === "flow"      ? "fl_"
                  : n.kind === "function"  ? "fn_"
                  : "n_";
      copy.id = prefix + AD.uuid().slice(2);
      copy.graphX = (n.graphX || 0) + 30;
      copy.graphY = (n.graphY || 0) + 30;
      AD.state.doc.nodes.push(copy);
      AD.state.selectedId = copy.id;
      AD.history.push();
      AD.renderAll();
    });
    inspector.querySelector(".del").addEventListener("click", () => deleteNode(n));
  }

  function renderEdgeInspector() {
    const conn = AD.state.doc.events[selectedEdgeIdx];
    const tid = conn.toId != null ? conn.toId : conn.targetId;
    const optsFrom = AD.state.doc.nodes.map((n) =>
      `<option value="${n.id}" ${n.id === conn.fromId ? "selected" : ""}>${n.id}</option>`).join("");
    const optsTo = `<option value="">(none)</option>` + AD.state.doc.nodes.map((n) =>
      `<option value="${n.id}" ${n.id === tid ? "selected" : ""}>${n.id}</option>`).join("");

    inspector.innerHTML = `
      <select class="from" title="Source node">${optsFrom}</select>
      <span class="arrow">→</span>
      <select class="target" title="Target node">${optsTo}</select>
      <button class="dup" title="Duplicate edge">⧉</button>
      <button class="del" title="Delete edge">×</button>
    `;
    inspector.querySelector(".from").addEventListener("change", (e) => {
      conn.fromId = e.target.value; AD.history.push(); render();
    });
    inspector.querySelector(".target").addEventListener("change", (e) => {
      conn.toId = e.target.value || null;
      // Drop any legacy targetId so the new value is canonical.
      delete conn.targetId;
      AD.history.push(); render();
    });
    inspector.querySelector(".dup").addEventListener("click", () => {
      AD.state.doc.events.push(JSON.parse(JSON.stringify(conn)));
      selectedEdgeIdx = AD.state.doc.events.length - 1;
      AD.history.push(); render();
    });
    inspector.querySelector(".del").addEventListener("click", () => {
      AD.state.doc.events.splice(selectedEdgeIdx, 1);
      selectedEdgeIdx = null;
      AD.history.push(); render();
    });
  }

  /* ── Interactions ─────────────────────────────────────────────────── */

  function onMouseDown(e) {
    AD.state.activePanel = "eventGraph";
    // Close any open node popover — re-shown below if a new chain
    // node is selected (event / action / animation).
    hideNodePopover();
    const w = screenToWorld(e.clientX, e.clientY);
    const v = view();
    dragMoved = false;

    if (e.button === 1) {
      dragMode = "pan";
      dragStart = { mx: e.clientX, my: e.clientY, px: v.panX, py: v.panY };
      e.preventDefault();
      return;
    }
    if (e.button === 2) { e.preventDefault(); return; }

    // Output port has priority over the body so it works even when the
    // port circle visually overlaps the node bbox edge.
    if (!AD.state.preview) {
      const portHit = hitOutputPort(w);
      if (portHit) {
        dragMode = "wire";
        wireFromId   = portHit.node.id;
        wireFromPort = portHit.port;
        wireTo = w;
        dragMoved = true;
        hideNodePopover();
        render();
        return;
      }
    }

    const node = hitNodeBody(w);
    if (node) {
      if (AD.state.preview) {
        // In preview, treat as a tap unless the user drags > threshold;
        // mouseup fires onEnter/onExit depending on the active state.
        dragMode = "tap-node";
        dragNodeId = node.id;
        dragLast = w;
        dragStart = { mx: e.clientX, my: e.clientY };
        return;
      }
      // Defer the actual mode (move-node vs wire) to the first mousemove
      // beyond a small threshold: cursor staying inside the node => move,
      // cursor leaving the node bounds => start a wire OUT of this node.
      // This replaces the dedicated input/output port dots — connections
      // attach to whichever side faces the target dynamically.
      dragMode = "tentative-node";
      dragNodeId = node.id;
      dragLast = w;
      dragStart = { mx: e.clientX, my: e.clientY };
      selectGraphNode(node.id, e.shiftKey);
      AD.state.selectedShapeIdx = null;
      AD.renderAll();
      const isMulti = AD.state.graphSelection.length > 1;
      if (!isMulti &&
          (node.kind === "event" || node.kind === "action" ||
           node.kind === "animation" || node.kind === "element" ||
           node.kind === "flow" || node.kind === "function")) {
        showNodePopover(node);
      }
      return;
    }

    const edgeIdx = hitEdge(w);
    if (edgeIdx >= 0) {
      selectedEdgeIdx = edgeIdx;
      render();
      return;
    }

    // Empty area: Shift+drag → marquee multi-select, plain drag → pan.
    selectedEdgeIdx = null;
    if (e.shiftKey) {
      marqueeStart = { x: w.x, y: w.y };
      marqueeEnd   = { x: w.x, y: w.y };
      marqueeAdditive = true;
      dragMode = "marquee";
      render();
      return;
    }
    // Plain empty click → clear multi-selection too.
    if (AD.state.graphSelection.length > 0) {
      AD.state.graphSelection = [];
      AD.state.selectedId = null;
    }
    dragMode = "pan";
    dragStart = { mx: e.clientX, my: e.clientY, px: v.panX, py: v.panY };
    render();
  }

  function onMouseMove(e) {
    if (!dragMode) {
      const w = screenToWorld(e.clientX, e.clientY);
      if (hitOutputPort(w) != null) { svg.style.cursor = "crosshair"; return; }
      if (hitNodeBody(w))   { svg.style.cursor = "move";      return; }
      const edge = hitEdge(w);
      if (edge >= 0)        { svg.style.cursor = "pointer";   return; }
      svg.style.cursor = "default";
      return;
    }
    dragMoved = true;

    if (dragMode === "pan") {
      const v = view();
      v.panX = dragStart.px + (e.clientX - dragStart.mx);
      v.panY = dragStart.py + (e.clientY - dragStart.my);
      render();
      repositionPopoverIfOpen();
      return;
    }
    if (dragMode === "marquee") {
      const p = screenToWorld(e.clientX, e.clientY);
      marqueeEnd = { x: p.x, y: p.y };
      render();
      return;
    }
    if (dragMode === "tentative-node") {
      const moved = Math.hypot(e.clientX - dragStart.mx, e.clientY - dragStart.my);
      if (moved < 4) return;
      const n = AD.state.doc.nodes.find((x) => x.id === dragNodeId);
      if (!n) { dragMode = null; return; }
      const s = sizeOf(n);
      const pw = screenToWorld(e.clientX, e.clientY);
      const inside = pw.x >= n.graphX && pw.x <= n.graphX + s.w &&
                     pw.y >= n.graphY && pw.y <= n.graphY + s.h;
      if (inside) {
        dragMode = "move-node";
        dragMoved = true;
        hideNodePopover();
        // Fall through to the move-node branch below for this same event.
      } else {
        // Cursor left the node — start a wire FROM this node.
        dragMode = "wire";
        wireFromId = n.id;
        wireTo = pw;
        hideNodePopover();
        render();
        return;
      }
    }

    if (dragMode === "move-node") {
      const w = screenToWorld(e.clientX, e.clientY);
      const dx = w.x - dragLast.x;
      const dy = w.y - dragLast.y;
      const sel = AD.state.graphSelection || [];
      const ids = (sel.length > 1 && sel.includes(dragNodeId)) ? sel : [dragNodeId];
      let moved = false;
      for (const id of ids) {
        const n = AD.state.doc.nodes.find((x) => x.id === id);
        if (n && n.graphX != null) {
          n.graphX += dx;
          n.graphY += dy;
          moved = true;
        }
      }
      if (moved) {
        dragLast = w;
        render();
        repositionPopoverIfOpen();
      }
      return;
    }
    if (dragMode === "tap-node") {
      // Convert a tap into a node move once it has dragged > 4 px.
      const dx = e.clientX - dragStart.mx;
      const dy = e.clientY - dragStart.my;
      if (Math.hypot(dx, dy) > 4) {
        dragMode = "move-node";
        dragMoved = true;
        // Drop into the move branch on the next move event.
      }
      return;
    }
    if (dragMode === "wire") {
      wireTo = screenToWorld(e.clientX, e.clientY);
      render();
      return;
    }
  }

  function onMouseUp(e) {
    if (dragMode === "tentative-node") {
      // Just a click — selection already happened in mousedown. Nothing else.
      dragMode = null;
      dragNodeId = null;
      return;
    }
    if (dragMode === "tap-node") {
      // Preview-mode click — fire onEnter or onExit on this node.
      const node = AD.state.doc.nodes.find((x) => x.id === dragNodeId);
      if (node && AD.state.preview) {
        const rt = AD.runtime(node.id);
        if (rt) {
          rt.active = !rt.active;
          AD.fireEvent(node.id, rt.active ? "onEnter" : "onExit");
        }
      }
      dragMode = null;
      dragNodeId = null;
      render();
      return;
    }
    if (dragMode === "marquee") {
      const a = marqueeStart, b = marqueeEnd;
      marqueeStart = null; marqueeEnd = null;
      dragMode = null;
      if (a && b) {
        const x0 = Math.min(a.x, b.x), y0 = Math.min(a.y, b.y);
        const x1 = Math.max(a.x, b.x), y1 = Math.max(a.y, b.y);
        const next = marqueeAdditive ? [...AD.state.graphSelection] : [];
        const seen = new Set(next);
        for (const n of AD.state.doc.nodes) {
          if (n.graphX == null) continue;
          const s = sizeOf(n);
          if (n.graphX + s.w >= x0 && n.graphX <= x1 &&
              n.graphY + s.h >= y0 && n.graphY <= y1) {
            if (!seen.has(n.id)) { next.push(n.id); seen.add(n.id); }
          }
        }
        AD.state.graphSelection = next;
        AD.state.selectedId = next[next.length - 1] || null;
      }
      hideNodePopover();
      AD.renderAll();
      return;
    }
    if (dragMode === "wire") {
      const w = screenToWorld(e.clientX, e.clientY);
      // Drop on any part of a node body becomes the target — ports are
      // gone, the entire box is now a connection sink.
      const target = hitNodeBody(w);
      if (target && target.id !== wireFromId) {
        // Block the same (from, to, fromPort) triplet from being
        // wired twice — but allow distinct ports on the same source
        // to share a single target (eg. an if-else where then + else
        // both feed the same downstream "fallthrough" node).
        const dup = AD.state.doc.events.some((c) => {
          const tid = c.toId != null ? c.toId : c.targetId;
          return c.fromId === wireFromId && tid === target.id &&
                 (c.fromPort || "out") === (wireFromPort || "out");
        });
        if (!dup) {
          const conn = { fromId: wireFromId, toId: target.id };
          if (wireFromPort && wireFromPort !== "out") conn.fromPort = wireFromPort;
          AD.state.doc.events.push(conn);
          selectedEdgeIdx = AD.state.doc.events.length - 1;
          AD.history.push();
        } else {
          // Surface the existing edge instead of creating a duplicate.
          selectedEdgeIdx = AD.state.doc.events.findIndex((c) => {
            const tid = c.toId != null ? c.toId : c.targetId;
            return c.fromId === wireFromId && tid === target.id;
          });
        }
      }
      wireFromId   = null;
      wireFromPort = null;
      wireTo = null;
    }
    if (dragMode === "move-node" && dragMoved) {
      AD.history.push();
    }
    dragMode = null;
    dragNodeId = null;
    dragLast = null;
    render();
  }

  function onWheel(e) {
    e.preventDefault();
    const r = svg.getBoundingClientRect();
    const mx = e.clientX - r.left;
    const my = e.clientY - r.top;
    const v = view();
    const before = v.zoom;
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
    const next = Math.max(0.3, Math.min(3, before * factor));
    v.panX = mx - (mx - v.panX) * (next / before);
    v.panY = my - (my - v.panY) * (next / before);
    v.zoom = next;
    render();
    repositionPopoverIfOpen();
  }

  /* Add a fresh event / action / animation / element node near the
   * centre of the view. Element nodes are native effect targets
   * (haptic / sound / toast / etc.) and live alongside the abstract
   * chain nodes — the chain walker treats them as terminal fire-and-
   * forget activations. */
  function addLogicNode(kind, subKind) {
    const r = svg.getBoundingClientRect();
    const v = view();
    const cx = (r.width  / 2 - v.panX) / v.zoom;
    const cy = (r.height / 2 - v.panY) / v.zoom;
    const n =
      kind === "event"     ? AD.defaultEventNode(subKind)     :
      kind === "action"    ? AD.defaultActionNode(subKind)    :
      kind === "animation" ? AD.defaultAnimationNode(subKind) :
      kind === "element"   ? AD.defaultElementNode(subKind)   :
      kind === "flow"      ? AD.defaultFlowNode(subKind)      :
      kind === "function"  ? AD.defaultFunctionNode(subKind)  :
      null;
    if (!n) return;
    n.graphX = cx - (EV_W / 2);
    n.graphY = cy - (EV_H / 2);
    AD.state.doc.nodes.push(n);
    AD.state.selectedId = n.id;
    AD.state.selectedShapeIdx = null;
    AD.history.push();
    AD.renderAll();
  }

  /* Floating menu anchored under the +Event / +Action / +Animation
   * / +Element button. Element kinds carry an explicit (kind, label,
   * color) record so we don't need a colour-lookup function for them. */
  function showKindMenu(btn, kinds, kindGroup) {
    const menu = document.getElementById("graphAddMenu");
    if (!menu) return;
    let html;
    if (kindGroup === "element" || kindGroup === "flow" || kindGroup === "function") {
      // Group by `category` field on the plugin spec — flow especially
      // benefits (Timing / Logic / State / Routing / Debug).
      const fam = kindGroup;
      const plugins = (AD._nodePlugins && AD._nodePlugins[fam]) || {};
      const grouped = {};
      for (const e of kinds) {
        const p = plugins[e.kind];
        const cat = (p && p.category) || "Other";
        (grouped[cat] = grouped[cat] || []).push(e);
      }
      const cats = Object.keys(grouped);
      const renderItem = (e) => `
        <div class="load-item" data-k="${e.kind}">
          <span class="dot" style="background:${e.color}"></span>
          <span class="name">${e.label}</span>
        </div>`;
      if (cats.length > 1) {
        html = cats.map((cat) => `
          <div class="load-section">${cat}</div>
          ${grouped[cat].map(renderItem).join("")}
        `).join('<div class="load-sep"></div>');
      } else {
        html = kinds.map(renderItem).join("");
      }
    } else {
      const colorFn =
        kindGroup === "event"  ? AD.eventKindColor :
        kindGroup === "action" ? AD.actionKindColor :
                                 AD.animKindColor;
      // Group by `category` field on the plugin spec if present; falls
      // back to a single "All" bucket for back-compat. Event family is
      // the obvious beneficiary (Pointer / Tap / Drag / Lifecycle /
      // Chain) but the same scaffolding works for actions / animations
      // as plugin authors add categories later.
      const plugins = (AD._nodePlugins && AD._nodePlugins[kindGroup]) || {};
      const grouped = {};
      for (const k of kinds) {
        const p = plugins[k];
        const cat = (p && p.category) || "Other";
        (grouped[cat] = grouped[cat] || []).push(k);
      }
      const cats = Object.keys(grouped);
      if (cats.length > 1) {
        html = cats.map((cat) => `
          <div class="load-section">${cat}</div>
          ${grouped[cat].map((k) => `
            <div class="load-item" data-k="${k}">
              <span class="dot" style="background:${colorFn(k)}"></span>
              <span class="name">${k}</span>
            </div>`).join("")}
        `).join('<div class="load-sep"></div>');
      } else {
        html = kinds.map((k) => `
          <div class="load-item" data-k="${k}">
            <span class="dot" style="background:${colorFn(k)}"></span>
            <span class="name">${k}</span>
          </div>`).join("");
      }
    }
    menu.innerHTML = html;
    const r = btn.getBoundingClientRect();
    menu.style.left = r.left + "px";
    menu.style.top  = (r.bottom + 4) + "px";
    menu.style.display = "block";

    menu.querySelectorAll(".load-item").forEach((item) => {
      item.addEventListener("click", () => {
        addLogicNode(kindGroup, item.dataset.k);
        hideKindMenu();
      });
    });
    setTimeout(() => document.addEventListener("mousedown", outsideMenuClick), 0);
  }
  function hideKindMenu() {
    const menu = document.getElementById("graphAddMenu");
    if (menu) menu.style.display = "none";
    document.removeEventListener("mousedown", outsideMenuClick);
  }
  function outsideMenuClick(e) {
    const menu = document.getElementById("graphAddMenu");
    if (!menu) return;
    if (!menu.contains(e.target) &&
        e.target.id !== "addEventNodeBtn" &&
        e.target.id !== "addActionNodeBtn" &&
        e.target.id !== "addAnimNodeBtn" &&
        e.target.id !== "addElementNodeBtn" &&
        e.target.id !== "addFlowNodeBtn" &&
        e.target.id !== "addFunctionNodeBtn" &&
        !e.target.closest("#addEventNodeBtn, #addActionNodeBtn, #addAnimNodeBtn, #addElementNodeBtn, #addFlowNodeBtn, #addFunctionNodeBtn")) {
      hideKindMenu();
    }
  }

  function fitToContent() {
    if (AD.state.doc.nodes.length === 0) return;
    const r = svg.getBoundingClientRect();
    let mnx = Infinity, mny = Infinity, mxx = -Infinity, mxy = -Infinity;
    for (const n of AD.state.doc.nodes) {
      if (n.graphX == null) continue;
      if (n.graphX < mnx) mnx = n.graphX;
      if (n.graphY < mny) mny = n.graphY;
      if (n.graphX + NODE_W > mxx) mxx = n.graphX + NODE_W;
      if (n.graphY + NODE_H > mxy) mxy = n.graphY + NODE_H;
    }
    if (mnx === Infinity) return;
    const pad = 40;
    const z = Math.min(r.width / (mxx - mnx + pad * 2),
                       r.height / (mxy - mny + pad * 2), 1.5);
    const v = view();
    v.zoom = Math.max(0.3, z);
    v.panX = (r.width  - (mnx + mxx) * v.zoom) / 2;
    v.panY = (r.height - (mny + mxy) * v.zoom) / 2;
    render();
  }

  function autoLayout() {
    let cursorX = 40;
    const y0 = 50;
    for (const n of AD.state.doc.nodes) {
      n.graphX = cursorX;
      n.graphY = y0;
      cursorX += NODE_W + 50;
    }
    AD.history.push();
    fitToContent();
  }

  /* ── Public surface ───────────────────────────────────────────────── */

  UI.eventGraph = {
    init() {
      svg = document.getElementById("eventGraph");
      inspector = document.getElementById("eventInspector");

      const addEv = document.getElementById("addEventNodeBtn");
      if (addEv) addEv.addEventListener("click", () =>
        showKindMenu(addEv, AD.EVENT_KINDS, "event"));
      const addAc = document.getElementById("addActionNodeBtn");
      if (addAc) addAc.addEventListener("click", () =>
        showKindMenu(addAc, AD.ACTION_KINDS, "action"));
      const addAn = document.getElementById("addAnimNodeBtn");
      if (addAn) addAn.addEventListener("click", () =>
        showKindMenu(addAn, AD.ANIMATION_KINDS, "animation"));
      const addEl = document.getElementById("addElementNodeBtn");
      if (addEl) addEl.addEventListener("click", () =>
        showKindMenu(addEl, AD.ELEMENT_KINDS, "element"));
      const addFl = document.getElementById("addFlowNodeBtn");
      if (addFl) addFl.addEventListener("click", () =>
        showKindMenu(addFl, AD.FLOW_KINDS, "flow"));
      const addFn = document.getElementById("addFunctionNodeBtn");
      if (addFn) addFn.addEventListener("click", () =>
        showKindMenu(addFn, AD.FUNCTION_KINDS, "function"));

      const fitBtn = document.getElementById("fitGraphBtn");
      if (fitBtn) fitBtn.addEventListener("click", fitToContent);
      const layoutBtn = document.getElementById("layoutGraphBtn");
      if (layoutBtn) layoutBtn.addEventListener("click", autoLayout);

      // Chain library buttons. Group / Ungroup / Save chain operate
      // on the current graphSelection; Chains opens a dropdown of
      // saved templates fetched from /api/chains.
      const groupBtn = document.getElementById("groupSelBtn");
      if (groupBtn) groupBtn.addEventListener("click", () => {
        const label = prompt("Group label:", "Chain") || "Chain";
        AD.chains && AD.chains.groupSelection(label);
      });
      const ungroupBtn = document.getElementById("ungroupBtn");
      if (ungroupBtn) ungroupBtn.addEventListener("click", () => {
        const sel = (AD.state.graphSelection || [])[0];
        if (sel) AD.chains && AD.chains.ungroup(sel);
      });
      const saveChainBtn = document.getElementById("saveChainBtn");
      if (saveChainBtn) saveChainBtn.addEventListener("click", () => {
        AD.chains && AD.chains.saveSelectionToLibrary();
      });
      const chainLibBtn = document.getElementById("chainLibBtn");
      if (chainLibBtn) chainLibBtn.addEventListener("click", () =>
        openChainLibMenu(chainLibBtn));

      // Keyboard shortcuts on the graph SVG. Plain Ctrl+S would
      // shadow the browser save dialog so we use Ctrl+E for "save
      // chain" — same finger position, no conflict.
      document.addEventListener("keydown", (e) => {
        if (!(e.ctrlKey || e.metaKey)) return;
        // Only when the event-graph has focus or no input is focused.
        const ae = document.activeElement;
        if (ae && (ae.tagName === "INPUT" || ae.tagName === "TEXTAREA" ||
                   ae.isContentEditable)) return;
        if (e.key === "g" && !e.shiftKey) {
          e.preventDefault();
          const label = prompt("Group label:", "Chain") || "Chain";
          AD.chains && AD.chains.groupSelection(label);
        } else if (e.key === "G" || (e.key === "g" && e.shiftKey)) {
          e.preventDefault();
          const sel = (AD.state.graphSelection || [])[0];
          if (sel) AD.chains && AD.chains.ungroup(sel);
        } else if (e.key === "e") {
          e.preventDefault();
          AD.chains && AD.chains.saveSelectionToLibrary();
        }
      });

      svg.addEventListener("mousedown", onMouseDown);
      // Double-click any chain-graph node → open the settings popover.
      // For trigger / aura the popover holds quick toggles (visible /
      // enabled / anchor); for event / action / animation / element
      // it's the kind dropdown + params. Single click still selects
      // (and for chain nodes auto-shows the popover); dblclick is a
      // deliberate "I want to edit this node now" gesture.
      svg.addEventListener("dblclick", (e) => {
        const w = screenToWorld(e.clientX, e.clientY);
        const node = hitNodeBody(w);
        if (!node) return;
        e.preventDefault();
        e.stopPropagation();
        AD.state.selectedId = node.id;
        AD.state.graphSelection = [node.id];
        AD.renderAll();
        showNodePopover(node);
      });
      svg.addEventListener("mousemove", onMouseMove);
      svg.addEventListener("mouseup",   onMouseUp);
      svg.addEventListener("wheel",     onWheel, { passive: false });
      svg.addEventListener("contextmenu", (e) => e.preventDefault());
      window.addEventListener("resize", () => render());
      // Re-render whenever the graph SVG's own size changes (sidebar
      // collapse, orientation flip, chain panel resize). Keeps the
      // viewBox in sync with the real pixel rect so cursor → world
      // mapping never drifts.
      if (typeof ResizeObserver !== "undefined") {
        new ResizeObserver(() => render()).observe(svg);
      }
      sizeCanvas();
    },
    render,
    fit: fitToContent,
    deselectEdge() { selectedEdgeIdx = null; render(); },
    /** Returns true if an edge is currently selected. Used by the
     *  panel-scoped Delete-key handler to decide whether the
     *  keystroke should go to edge-removal or node-removal. */
    hasSelectedEdge() { return selectedEdgeIdx != null; },
    /** Drop the currently-selected edge from doc.events. No-op if
     *  no edge is selected. Returns true if something was deleted. */
    deleteSelectedEdge() {
      if (selectedEdgeIdx == null) return false;
      if (selectedEdgeIdx < 0 || selectedEdgeIdx >= AD.state.doc.events.length) {
        selectedEdgeIdx = null;
        return false;
      }
      AD.state.doc.events.splice(selectedEdgeIdx, 1);
      selectedEdgeIdx = null;
      AD.history.push();
      render();
      return true;
    },
  };

  /** Drop-down listing every saved chain under /api/chains. Clicking
   *  a row drops a copy into the current doc; the × button deletes
   *  the template file. Anchored under the toolbar's "Chains" button
   *  and dismissed on outside click. */
  async function openChainLibMenu(anchor) {
    const menu = document.getElementById("chainLibMenu");
    if (!menu) return;
    const chains = (AD.chains && (await AD.chains.listLibrary())) || [];
    if (chains.length === 0) {
      menu.innerHTML = `<div class="load-empty">No saved chains yet — select nodes + Save chain.</div>`;
    } else {
      menu.innerHTML = chains
        .sort((a, b) => (b.mtime || 0) - (a.mtime || 0))
        .map((c) => `
          <div class="load-item" data-name="${c.name.replace(/"/g, "&quot;")}">
            <span class="name">${(c.label || c.name).replace(/</g, "&lt;")}</span>
            <button class="del" data-name="${c.name.replace(/"/g, "&quot;")}" title="Delete">×</button>
          </div>`).join("");
    }
    const r = anchor.getBoundingClientRect();
    menu.style.display = "block";
    menu.style.left = r.left + "px";
    menu.style.top  = (r.bottom + 4) + "px";
    // Wire rows
    menu.querySelectorAll(".load-item").forEach((row) => {
      row.addEventListener("click", (e) => {
        if (e.target.closest(".del")) {
          const name = e.target.closest(".del").dataset.name;
          AD.chains.deleteFromLibrary(name).then(() => openChainLibMenu(anchor));
          return;
        }
        const name = row.dataset.name;
        AD.chains.insertFromLibrary(name);
        menu.style.display = "none";
      });
    });
    setTimeout(() => document.addEventListener("mousedown", outsideCloseChainLib), 0);
  }
  function outsideCloseChainLib(e) {
    const menu = document.getElementById("chainLibMenu");
    const btn  = document.getElementById("chainLibBtn");
    if (!menu) return;
    if (!menu.contains(e.target) && e.target !== btn && !btn.contains(e.target)) {
      menu.style.display = "none";
      document.removeEventListener("mousedown", outsideCloseChainLib);
    }
  }
})();
