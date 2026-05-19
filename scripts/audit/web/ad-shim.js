// AD shim — minimum surface area that aura-designer's event-graph.js
// expects, adapted for dependency-audit data.
//
// The event-graph reads/mutates `window.AD.state.doc.{nodes, events,
// settings}` and calls back into other AD subsystems (history, widgets,
// chains, runtime). We stub the read paths to return defaults and make
// every mutation a no-op (we don't need undo, popovers, runtime
// preview, plugin libraries, etc.).
//
// Color/severity hookup: every dep is rendered as a `kind: "function"`
// node with `functionKind` set to its severity label. `AD.nodePlugin`
// returns a colour for that severity so drawNode's stripe/port use the
// right shade. Critical/high/medium/low/none map to fixed palette
// values that match our styles.css custom properties.

(function () {
  const AD = window.AD = window.AD || {};

  AD.state = {
    doc: {
      nodes: [],     // package nodes
      events: [],    // edges (parent → child)
      settings: {},  // event-graph stores its panX/panY/zoom under .eventGraphView here
    },
    graphSelection: [],
    selectedId: null,
    selectedShapeIdx: null,
    activePanel: null,
    flashActive: null,
    preview: false,
  };

  AD.history = { push() { /* no undo needed in audit viewer */ } };

  AD.uuid = function () {
    return 'id_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
  };

  // renderAll is called by the event-graph after mutations. The host
  // app overrides this with its own composite refresh that re-renders
  // the graph AND repaints the side panels.
  AD.renderAll = function () {
    if (AD.ui && AD.ui.eventGraph && AD.ui.eventGraph.render) AD.ui.eventGraph.render();
  };

  AD.runtime = function () { return null; };

  // ─── Widget stubs (popovers reference these — popovers are disabled
  //     here, but the function calls happen during render setup paths) ──

  AD.ui = AD.ui || {};
  AD.ui.widgets = {
    rangeField:    () => '',
    selectField:   () => '',
    toggleField:   () => '',
    colorRow:      () => '',
    segmentedField:() => '',
    wireRange:     () => {},
    wireSelect:    () => {},
    wireToggle:    () => {},
    wireColor:     () => {},
  };

  // ─── Node-kind enums + colour helpers ─────────────────────────────

  AD.EVENT_KINDS     = [];
  AD.ACTION_KINDS    = [];
  AD.ELEMENT_KINDS   = [];
  AD.FLOW_KINDS      = [];
  AD.FUNCTION_KINDS  = [];
  AD.ANIMATION_KINDS = [];
  AD.ANIM_CURVES     = [];

  // Severity → palette. Matches the CSS custom properties used elsewhere.
  const SEV_COLOR = {
    critical: '#e0314f',
    high:     '#f87171',
    medium:   '#fbbf24',
    low:      '#60a5fa',
    none:     '#4ade80',
    unknown:  '#888d97',
  };
  function severityColor(label) { return SEV_COLOR[label] || SEV_COLOR.unknown; }
  AD.severityColor = severityColor;

  AD.eventKindColor  = () => '#7AB8FF';
  AD.actionKindColor = () => '#7AB8FF';
  AD.animKindColor   = () => '#7AB8FF';

  AD.elementMeta  = () => null;
  AD.flowMeta     = () => null;
  AD.functionMeta = () => null;

  // The event-graph calls `AD.nodePlugin("function", n.functionKind)`
  // to look up the plugin spec for function-kind nodes. For dep audit
  // we set functionKind = severity, so this returns a fake plugin
  // whose .color drives the stripe + port colour.
  AD.nodePlugin = function (kind, sub) {
    if (kind === 'function') return { color: severityColor(sub), label: sub || 'none' };
    return null;
  };

  AD._icons = {};   // no Lucide-style icon library — falls back to plain text labels

  // Delete helper used by the inspector's × button. Removes the node
  // and any incident edges. Notifies the host so it can update its
  // details panel.
  AD.deleteNodeById = function (id) {
    AD.state.doc.nodes  = AD.state.doc.nodes.filter(n => n.id !== id);
    AD.state.doc.events = AD.state.doc.events.filter(e =>
      e.fromId !== id && (e.toId != null ? e.toId : e.targetId) !== id);
    if (AD.state.selectedId === id) AD.state.selectedId = null;
    AD.state.graphSelection = AD.state.graphSelection.filter(x => x !== id);
    AD.renderAll();
    if (AD.onNodeDeleted) AD.onNodeDeleted(id);
  };

  // AD.chains is referenced inside Ctrl+G / Ctrl+E shortcuts; leaving
  // it undefined causes those branches to short-circuit. Same for the
  // toolbar buttons — they're optional `if (btn) …` lookups.
})();
