package com.vocalmonitor.plugin.gamekit.svg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal SVG parser — turns SVG source text into a {@link PluginShape}
 * that can be drawn via {@link PluginShape#draw}.
 *
 * Supported features (v1):
 *
 *   - root viewBox (mandatory — recommended) or width/height (fallback)
 *   - elements: rect, circle, ellipse, line, polyline, polygon, path,
 *     g (group, inherits attributes)
 *   - path d= commands: M m L l H h V v C c S s Q q T t Z z
 *     (S/T smooth variants reflect the previous control point)
 *   - attributes: fill, stroke, stroke-width, opacity, fill-opacity,
 *     stroke-opacity
 *   - colors: #rgb / #rrggbb / rgb(r,g,b) / "none" / common named
 *     colors (black, white, red, green, blue, …)
 *
 * Intentionally NOT supported in v1 — pre-process your SVG to flatten
 * these (Inkscape's "Save As → Optimized SVG", or `svgo`):
 *
 *   - arcs (A/a in path)              — flatten to cubics
 *   - transforms on elements          — bake into coordinates
 *   - gradients (linear/radial)       — flatten or substitute solid
 *   - filters, masks, clipPaths       — flatten
 *   - <use> / <defs> / <symbol>       — flatten / inline
 *   - text                            — convert to path
 *   - CSS in &lt;style&gt; blocks     — inline as attributes
 *
 * The parser is regex-based — not XML-strict — which is fine for the
 * "small icon / character" SVGs that motivate this kit.  A
 * malformed file produces an empty {@link PluginShape} (parser
 * fails open).  Use {@link PluginShape#isEmpty()} to detect.
 */
public final class Svg {
    private Svg() {}

    /** Parse SVG text; returns an empty shape on failure. */
    public static PluginShape parse(String svg) {
        if (svg == null || svg.length() == 0) return empty();
        try {
            return new Parser(svg).run();
        } catch (RuntimeException e) {
            return empty();
        }
    }

    private static PluginShape empty() {
        return new PluginShape(
            new int[0], new int[0], new float[0],
            new int[0], new int[0],
            new int[0], new int[0], new float[0],
            0f, 0f, 100f, 100f
        );
    }

    // ───────────────────────────────────────────────────────
    // Parser implementation
    // ───────────────────────────────────────────────────────
    private static final class Parser {
        final String src;
        int pos = 0;

        // Output accumulators.
        final IntArrayList cmdTypes = new IntArrayList();
        final IntArrayList cmdOffsets = new IntArrayList();
        final FloatArrayList coords = new FloatArrayList();
        final IntArrayList layerStarts = new IntArrayList();
        final IntArrayList layerEnds = new IntArrayList();
        final IntArrayList layerFills = new IntArrayList();
        final IntArrayList layerStrokes = new IntArrayList();
        final FloatArrayList layerStrokeWs = new FloatArrayList();

        // Default viewBox if root doesn't declare one.
        float vbX = 0f, vbY = 0f, vbW = 100f, vbH = 100f;

        // Style stack — inherited through <g>.
        final List<Map<String, String>> styleStack = new ArrayList<>();

        Parser(String svg) {
            this.src = svg;
            Map<String, String> root = new HashMap<>();
            // SVG defaults: fill = black, stroke = none.
            root.put("fill", "black");
            root.put("stroke", "none");
            root.put("stroke-width", "1");
            root.put("opacity", "1");
            root.put("fill-opacity", "1");
            root.put("stroke-opacity", "1");
            styleStack.add(root);
        }

        PluginShape run() {
            // Pull root attributes (viewBox) from the <svg> tag.
            Map<String, String> rootAttrs = firstTagAttributes("svg");
            if (rootAttrs != null) {
                String vb = rootAttrs.get("viewBox");
                if (vb != null) {
                    String[] p = vb.trim().split("[\\s,]+");
                    if (p.length == 4) {
                        try {
                            vbX = Float.parseFloat(p[0]);
                            vbY = Float.parseFloat(p[1]);
                            vbW = Float.parseFloat(p[2]);
                            vbH = Float.parseFloat(p[3]);
                        } catch (NumberFormatException ignore) {}
                    }
                } else {
                    Float w = attrAsFloat(rootAttrs, "width");
                    Float h = attrAsFloat(rootAttrs, "height");
                    if (w != null && h != null) { vbW = w; vbH = h; }
                }
                // The root <svg> tag itself can carry inherited fill /
                // stroke — fold into the root style.
                mergeStyleAttrs(styleStack.get(0), rootAttrs);
            }
            // Walk tags in source order.  Each opening tag of a known
            // shape adds a layer; <g> pushes a style frame, </g> pops.
            while (true) {
                int tagStart = src.indexOf('<', pos);
                if (tagStart < 0) break;
                pos = tagStart + 1;
                if (pos < src.length() && src.charAt(pos) == '/') {
                    pos++;
                    String name = readName();
                    if ("g".equals(name) && styleStack.size() > 1) {
                        styleStack.remove(styleStack.size() - 1);
                    }
                    skipUntil('>');
                    continue;
                }
                if (pos < src.length() && src.charAt(pos) == '!') {
                    // Comment / DOCTYPE — skip to '>'.
                    skipUntil('>');
                    continue;
                }
                if (pos < src.length() && src.charAt(pos) == '?') {
                    skipUntil('>');
                    continue;
                }
                String name = readName();
                Map<String, String> attrs = readAttributes();
                boolean selfClose = false;
                if (pos < src.length() && src.charAt(pos) == '/') {
                    selfClose = true;
                    pos++;
                }
                if (pos < src.length() && src.charAt(pos) == '>') pos++;
                handleTag(name, attrs, selfClose);
            }

            // Assemble final shape.
            return new PluginShape(
                cmdTypes.toArray(), cmdOffsets.toArray(), coords.toArray(),
                layerStarts.toArray(), layerEnds.toArray(),
                layerFills.toArray(), layerStrokes.toArray(), layerStrokeWs.toArray(),
                vbX, vbY, vbW, vbH
            );
        }

        // ── Tag dispatch ────────────────────────────────────
        void handleTag(String name, Map<String, String> attrs, boolean selfClose) {
            Map<String, String> inherited = styleStack.get(styleStack.size() - 1);
            Map<String, String> merged = new HashMap<>(inherited);
            mergeStyleAttrs(merged, attrs);
            switch (name) {
                case "g":
                    if (!selfClose) styleStack.add(merged);
                    return;
                case "rect":     handleRect(attrs, merged); return;
                case "circle":   handleCircle(attrs, merged); return;
                case "ellipse":  handleEllipse(attrs, merged); return;
                case "line":     handleLine(attrs, merged); return;
                case "polygon":  handlePolyOrLine(attrs, merged, true); return;
                case "polyline": handlePolyOrLine(attrs, merged, false); return;
                case "path":     handlePath(attrs, merged); return;
                default: /* ignore */ return;
            }
        }

        // ── Shape handlers ──────────────────────────────────
        void handleRect(Map<String, String> a, Map<String, String> style) {
            Float x = attrAsFloat(a, "x");
            Float y = attrAsFloat(a, "y");
            Float w = attrAsFloat(a, "width");
            Float h = attrAsFloat(a, "height");
            if (w == null || h == null) return;
            float xv = x == null ? 0f : x;
            float yv = y == null ? 0f : y;
            // rx/ry handled as plain rect for v1 — close to the visual
            // for small radii; flatten with svgo for pixel-perfect.
            int start = cmdTypes.size();
            emit(PluginShape.OP_MOVE,  xv,        yv);
            emit(PluginShape.OP_LINE,  xv + w,    yv);
            emit(PluginShape.OP_LINE,  xv + w,    yv + h);
            emit(PluginShape.OP_LINE,  xv,        yv + h);
            emit(PluginShape.OP_CLOSE);
            commitLayer(start, style);
        }

        void handleCircle(Map<String, String> a, Map<String, String> style) {
            Float cx = attrAsFloat(a, "cx");
            Float cy = attrAsFloat(a, "cy");
            Float r  = attrAsFloat(a, "r");
            if (r == null) return;
            float cxv = cx == null ? 0f : cx;
            float cyv = cy == null ? 0f : cy;
            int start = cmdTypes.size();
            emitCircleAsBeziers(cxv, cyv, r, r);
            commitLayer(start, style);
        }

        void handleEllipse(Map<String, String> a, Map<String, String> style) {
            Float cx = attrAsFloat(a, "cx");
            Float cy = attrAsFloat(a, "cy");
            Float rx = attrAsFloat(a, "rx");
            Float ry = attrAsFloat(a, "ry");
            if (rx == null || ry == null) return;
            int start = cmdTypes.size();
            emitCircleAsBeziers(cx == null ? 0 : cx, cy == null ? 0 : cy, rx, ry);
            commitLayer(start, style);
        }

        // Magic constant: 4 * (sqrt(2) - 1) / 3 — quarter-circle as
        // a cubic bezier, max error ≈ 0.00027 of radius.
        private static final float K = 0.5522847498f;
        void emitCircleAsBeziers(float cx, float cy, float rx, float ry) {
            emit(PluginShape.OP_MOVE, cx, cy - ry);
            emit(PluginShape.OP_CUBIC,
                cx + K * rx, cy - ry,       cx + rx, cy - K * ry,   cx + rx, cy);
            emit(PluginShape.OP_CUBIC,
                cx + rx, cy + K * ry,       cx + K * rx, cy + ry,   cx, cy + ry);
            emit(PluginShape.OP_CUBIC,
                cx - K * rx, cy + ry,       cx - rx, cy + K * ry,   cx - rx, cy);
            emit(PluginShape.OP_CUBIC,
                cx - rx, cy - K * ry,       cx - K * rx, cy - ry,   cx, cy - ry);
            emit(PluginShape.OP_CLOSE);
        }

        void handleLine(Map<String, String> a, Map<String, String> style) {
            Float x1 = attrAsFloat(a, "x1");
            Float y1 = attrAsFloat(a, "y1");
            Float x2 = attrAsFloat(a, "x2");
            Float y2 = attrAsFloat(a, "y2");
            if (x1 == null || y1 == null || x2 == null || y2 == null) return;
            int start = cmdTypes.size();
            emit(PluginShape.OP_MOVE, x1, y1);
            emit(PluginShape.OP_LINE, x2, y2);
            commitLayer(start, style);
        }

        void handlePolyOrLine(Map<String, String> a, Map<String, String> style, boolean close) {
            String pts = a.get("points");
            if (pts == null) return;
            float[] xs = parseNumberList(pts);
            if (xs.length < 4) return;
            int start = cmdTypes.size();
            emit(PluginShape.OP_MOVE, xs[0], xs[1]);
            for (int i = 2; i + 1 < xs.length; i += 2) {
                emit(PluginShape.OP_LINE, xs[i], xs[i + 1]);
            }
            if (close) emit(PluginShape.OP_CLOSE);
            commitLayer(start, style);
        }

        void handlePath(Map<String, String> a, Map<String, String> style) {
            String d = a.get("d");
            if (d == null) return;
            int start = cmdTypes.size();
            parsePathD(d);
            commitLayer(start, style);
        }

        // ── Path "d" attribute parser ───────────────────────
        void parsePathD(String d) {
            int i = 0, n = d.length();
            char curCmd = 0;
            float curX = 0f, curY = 0f;
            float startX = 0f, startY = 0f;
            float ctrlX = 0f, ctrlY = 0f;       // last cubic / quad control
            boolean haveCtrl = false;
            int prevOp = -1;

            while (i < n) {
                char c = d.charAt(i);
                if (Character.isWhitespace(c) || c == ',') { i++; continue; }
                if (Character.isLetter(c)) {
                    curCmd = c;
                    i++;
                    continue;
                }
                if (curCmd == 0) break;        // d= started with a number — malformed
                boolean rel = Character.isLowerCase(curCmd);
                char op = Character.toUpperCase(curCmd);
                switch (op) {
                    case 'M': {
                        float[] pt = readPair(d, i); i = (int) pt[2];
                        float x = rel ? curX + pt[0] : pt[0];
                        float y = rel ? curY + pt[1] : pt[1];
                        emit(PluginShape.OP_MOVE, x, y);
                        curX = x; curY = y;
                        startX = x; startY = y;
                        // Subsequent implicit coords after M are LINETOs.
                        curCmd = rel ? 'l' : 'L';
                        haveCtrl = false;
                        prevOp = PluginShape.OP_MOVE;
                        break;
                    }
                    case 'L': {
                        float[] pt = readPair(d, i); i = (int) pt[2];
                        float x = rel ? curX + pt[0] : pt[0];
                        float y = rel ? curY + pt[1] : pt[1];
                        emit(PluginShape.OP_LINE, x, y);
                        curX = x; curY = y;
                        haveCtrl = false;
                        prevOp = PluginShape.OP_LINE;
                        break;
                    }
                    case 'H': {
                        float[] pt = readNumber(d, i); i = (int) pt[1];
                        float x = rel ? curX + pt[0] : pt[0];
                        emit(PluginShape.OP_LINE, x, curY);
                        curX = x;
                        haveCtrl = false;
                        prevOp = PluginShape.OP_LINE;
                        break;
                    }
                    case 'V': {
                        float[] pt = readNumber(d, i); i = (int) pt[1];
                        float y = rel ? curY + pt[0] : pt[0];
                        emit(PluginShape.OP_LINE, curX, y);
                        curY = y;
                        haveCtrl = false;
                        prevOp = PluginShape.OP_LINE;
                        break;
                    }
                    case 'C': {
                        float[] a1 = readPair(d, i); i = (int) a1[2];
                        float[] a2 = readPair(d, i); i = (int) a2[2];
                        float[] a3 = readPair(d, i); i = (int) a3[2];
                        float x1 = rel ? curX + a1[0] : a1[0], y1 = rel ? curY + a1[1] : a1[1];
                        float x2 = rel ? curX + a2[0] : a2[0], y2 = rel ? curY + a2[1] : a2[1];
                        float x  = rel ? curX + a3[0] : a3[0], y  = rel ? curY + a3[1] : a3[1];
                        emit(PluginShape.OP_CUBIC, x1, y1, x2, y2, x, y);
                        ctrlX = x2; ctrlY = y2; haveCtrl = true;
                        curX = x; curY = y;
                        prevOp = PluginShape.OP_CUBIC;
                        break;
                    }
                    case 'S': {
                        // First control = reflection of last cubic control;
                        // if previous wasn't a cubic, use current point.
                        float x1, y1;
                        if (haveCtrl && prevOp == PluginShape.OP_CUBIC) {
                            x1 = 2f * curX - ctrlX; y1 = 2f * curY - ctrlY;
                        } else { x1 = curX; y1 = curY; }
                        float[] a2 = readPair(d, i); i = (int) a2[2];
                        float[] a3 = readPair(d, i); i = (int) a3[2];
                        float x2 = rel ? curX + a2[0] : a2[0], y2 = rel ? curY + a2[1] : a2[1];
                        float x  = rel ? curX + a3[0] : a3[0], y  = rel ? curY + a3[1] : a3[1];
                        emit(PluginShape.OP_CUBIC, x1, y1, x2, y2, x, y);
                        ctrlX = x2; ctrlY = y2; haveCtrl = true;
                        curX = x; curY = y;
                        prevOp = PluginShape.OP_CUBIC;
                        break;
                    }
                    case 'Q': {
                        float[] a1 = readPair(d, i); i = (int) a1[2];
                        float[] a2 = readPair(d, i); i = (int) a2[2];
                        float x1 = rel ? curX + a1[0] : a1[0], y1 = rel ? curY + a1[1] : a1[1];
                        float x  = rel ? curX + a2[0] : a2[0], y  = rel ? curY + a2[1] : a2[1];
                        emit(PluginShape.OP_QUAD, x1, y1, x, y);
                        ctrlX = x1; ctrlY = y1; haveCtrl = true;
                        curX = x; curY = y;
                        prevOp = PluginShape.OP_QUAD;
                        break;
                    }
                    case 'T': {
                        float x1, y1;
                        if (haveCtrl && prevOp == PluginShape.OP_QUAD) {
                            x1 = 2f * curX - ctrlX; y1 = 2f * curY - ctrlY;
                        } else { x1 = curX; y1 = curY; }
                        float[] a2 = readPair(d, i); i = (int) a2[2];
                        float x = rel ? curX + a2[0] : a2[0], y = rel ? curY + a2[1] : a2[1];
                        emit(PluginShape.OP_QUAD, x1, y1, x, y);
                        ctrlX = x1; ctrlY = y1; haveCtrl = true;
                        curX = x; curY = y;
                        prevOp = PluginShape.OP_QUAD;
                        break;
                    }
                    case 'Z': {
                        emit(PluginShape.OP_CLOSE);
                        curX = startX; curY = startY;
                        haveCtrl = false;
                        prevOp = PluginShape.OP_CLOSE;
                        // No coord to consume — already past 'z'.
                        break;
                    }
                    case 'A':
                        // Arcs not supported in v1 — skip past the
                        // 7 arc parameters so the parser doesn't choke.
                        for (int k = 0; k < 7; k++) {
                            float[] tmp = readNumber(d, i);
                            i = (int) tmp[1];
                            if (k == 5 || k == 6) {
                                // x and y of the arc endpoint — track current
                                // point to avoid throwing off subsequent ops.
                                if (k == 5) curX = rel ? curX + tmp[0] : tmp[0];
                                else        curY = rel ? curY + tmp[0] : tmp[0];
                            }
                        }
                        // Add a straight line to the endpoint as a
                        // fallback — better than a gap.
                        emit(PluginShape.OP_LINE, curX, curY);
                        haveCtrl = false;
                        prevOp = PluginShape.OP_LINE;
                        break;
                    default:
                        return;   // unknown command — bail
                }
            }
        }

        // ── Emit helpers ────────────────────────────────────
        void emit(int op, float x, float y) {
            cmdTypes.add(op);
            cmdOffsets.add(coords.size());
            coords.add(x); coords.add(y);
        }
        void emit(int op, float x1, float y1, float x2, float y2) {
            cmdTypes.add(op);
            cmdOffsets.add(coords.size());
            coords.add(x1); coords.add(y1); coords.add(x2); coords.add(y2);
        }
        void emit(int op, float x1, float y1, float x2, float y2, float x3, float y3) {
            cmdTypes.add(op);
            cmdOffsets.add(coords.size());
            coords.add(x1); coords.add(y1);
            coords.add(x2); coords.add(y2);
            coords.add(x3); coords.add(y3);
        }
        void emit(int op) {
            cmdTypes.add(op);
            cmdOffsets.add(coords.size());
        }
        void commitLayer(int startCmdIdx, Map<String, String> style) {
            int end = cmdTypes.size();
            if (end == startCmdIdx) return;
            layerStarts.add(startCmdIdx);
            layerEnds.add(end);
            layerFills.add(resolveColor(
                style.get("fill"), style.get("fill-opacity"), style.get("opacity"), 0xFF000000));
            int stroke = resolveColor(
                style.get("stroke"), style.get("stroke-opacity"), style.get("opacity"), 0);
            layerStrokes.add(stroke);
            Float w = floatOrNull(style.get("stroke-width"));
            layerStrokeWs.add(w == null ? 1f : w);
        }

        // ── Number / pair readers ──────────────────────────
        /** Returns [num, nextPos]. */
        float[] readNumber(String s, int from) {
            int i = from;
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            int start = i;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i == start) throw new RuntimeException("expected number");
            float v = Float.parseFloat(s.substring(start, i));
            return new float[] { v, i };
        }

        /** Returns [x, y, nextPos]. */
        float[] readPair(String s, int from) {
            float[] a = readNumber(s, from);
            float[] b = readNumber(s, (int) a[1]);
            return new float[] { a[0], b[0], b[1] };
        }

        float[] parseNumberList(String list) {
            FloatArrayList out = new FloatArrayList();
            int i = 0, n = list.length();
            while (i < n) {
                while (i < n && (Character.isWhitespace(list.charAt(i)) || list.charAt(i) == ',')) i++;
                if (i >= n) break;
                float[] v = readNumber(list, i);
                out.add(v[0]);
                i = (int) v[1];
            }
            return out.toArray();
        }

        // ── Tag / attribute parsing helpers ────────────────
        String readName() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c) || c == '>' || c == '/' || c == '=') break;
                pos++;
            }
            return src.substring(start, pos);
        }

        Map<String, String> readAttributes() {
            Map<String, String> out = new HashMap<>();
            while (pos < src.length()) {
                while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
                if (pos >= src.length()) break;
                char c = src.charAt(pos);
                if (c == '>' || c == '/') break;
                int nameStart = pos;
                while (pos < src.length() && src.charAt(pos) != '=' && !Character.isWhitespace(src.charAt(pos)) && src.charAt(pos) != '>') {
                    pos++;
                }
                String name = src.substring(nameStart, pos);
                while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
                if (pos < src.length() && src.charAt(pos) == '=') {
                    pos++;
                    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
                    if (pos < src.length() && (src.charAt(pos) == '"' || src.charAt(pos) == '\'')) {
                        char q = src.charAt(pos++);
                        int valStart = pos;
                        while (pos < src.length() && src.charAt(pos) != q) pos++;
                        String value = src.substring(valStart, pos);
                        if (pos < src.length()) pos++;
                        if (!name.isEmpty()) out.put(name, value);
                    }
                } else if (!name.isEmpty()) {
                    out.put(name, "");
                }
            }
            return out;
        }

        Map<String, String> firstTagAttributes(String wantName) {
            int saved = pos;
            int find = src.indexOf('<' + wantName, 0);
            if (find < 0) return null;
            pos = find + 1 + wantName.length();
            Map<String, String> a = readAttributes();
            pos = saved;
            return a;
        }

        void skipUntil(char ch) {
            while (pos < src.length() && src.charAt(pos) != ch) pos++;
            if (pos < src.length()) pos++;
        }

        // ── Style helpers ──────────────────────────────────
        void mergeStyleAttrs(Map<String, String> base, Map<String, String> attrs) {
            for (String k : new String[] { "fill", "stroke", "stroke-width",
                "opacity", "fill-opacity", "stroke-opacity" }) {
                if (attrs.containsKey(k)) base.put(k, attrs.get(k));
            }
            String style = attrs.get("style");
            if (style != null) parseStyleString(base, style);
        }

        void parseStyleString(Map<String, String> base, String style) {
            for (String pair : style.split(";")) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).trim();
                String val = pair.substring(colon + 1).trim();
                base.put(key, val);
            }
        }

        Float attrAsFloat(Map<String, String> a, String key) {
            return floatOrNull(a.get(key));
        }
    }

    // ───────────────────────────────────────────────────────
    // Color parsing — package-private statics so the parser can use
    // them without inflating its own class size.
    // ───────────────────────────────────────────────────────
    static int resolveColor(String spec, String localOpacity, String globalOpacity, int defaultArgb) {
        if (spec == null) spec = "";
        spec = spec.trim();
        if (spec.equalsIgnoreCase("none") || spec.isEmpty()) {
            return 0;   // transparent / no draw
        }
        int rgb = parseColor(spec);
        if (rgb < 0) return defaultArgb;
        float a = 1f;
        Float lo = floatOrNull(localOpacity);
        Float go = floatOrNull(globalOpacity);
        if (lo != null) a *= lo;
        if (go != null) a *= go;
        int alpha = (int) (a * 255f);
        if (alpha < 0) alpha = 0; if (alpha > 255) alpha = 255;
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    /** Returns 0xRRGGBB (no alpha) on success, -1 on parse failure. */
    static int parseColor(String s) {
        s = s.trim();
        if (s.startsWith("#")) {
            String hex = s.substring(1);
            try {
                if (hex.length() == 3) {
                    int r = Integer.parseInt(hex.substring(0, 1), 16);
                    int g = Integer.parseInt(hex.substring(1, 2), 16);
                    int b = Integer.parseInt(hex.substring(2, 3), 16);
                    return ((r * 17) << 16) | ((g * 17) << 8) | (b * 17);
                }
                if (hex.length() == 6) return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignore) {}
            return -1;
        }
        if (s.startsWith("rgb(") && s.endsWith(")")) {
            try {
                String inner = s.substring(4, s.length() - 1);
                String[] parts = inner.split(",");
                int r = clamp255((int) Float.parseFloat(parts[0].trim()));
                int g = clamp255((int) Float.parseFloat(parts[1].trim()));
                int b = clamp255((int) Float.parseFloat(parts[2].trim()));
                return (r << 16) | (g << 8) | b;
            } catch (Throwable ignore) {}
            return -1;
        }
        Integer named = NamedColors.get(s.toLowerCase());
        return named == null ? -1 : named;
    }

    static int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    static Float floatOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // Common named colors — full CSS list is 140-odd; this is the
    // subset that actually appears in everyday icon SVGs.
    private static final class NamedColors {
        private static final Map<String, Integer> M = new HashMap<>();
        static {
            M.put("black",    0x000000); M.put("white",   0xFFFFFF);
            M.put("red",      0xFF0000); M.put("green",   0x008000);
            M.put("blue",     0x0000FF); M.put("yellow",  0xFFFF00);
            M.put("cyan",     0x00FFFF); M.put("magenta", 0xFF00FF);
            M.put("gray",     0x808080); M.put("grey",    0x808080);
            M.put("silver",   0xC0C0C0); M.put("maroon",  0x800000);
            M.put("olive",    0x808000); M.put("navy",    0x000080);
            M.put("purple",   0x800080); M.put("teal",    0x008080);
            M.put("aqua",     0x00FFFF); M.put("fuchsia", 0xFF00FF);
            M.put("orange",   0xFFA500); M.put("pink",    0xFFC0CB);
            M.put("brown",    0xA52A2A); M.put("gold",    0xFFD700);
            M.put("lime",     0x00FF00); M.put("indigo",  0x4B0082);
            M.put("violet",   0xEE82EE); M.put("transparent", 0x000000);
        }
        static Integer get(String name) { return M.get(name); }
    }

    // ───────────────────────────────────────────────────────
    // Cheap dynamic-int / -float lists (avoid pulling Trove etc.)
    // ───────────────────────────────────────────────────────
    static final class IntArrayList {
        int[] data = new int[16];
        int len = 0;
        void add(int v) {
            if (len == data.length) {
                int[] bigger = new int[data.length * 2];
                System.arraycopy(data, 0, bigger, 0, len);
                data = bigger;
            }
            data[len++] = v;
        }
        int size() { return len; }
        int[] toArray() {
            int[] out = new int[len];
            System.arraycopy(data, 0, out, 0, len);
            return out;
        }
    }
    static final class FloatArrayList {
        float[] data = new float[32];
        int len = 0;
        void add(float v) {
            if (len == data.length) {
                float[] bigger = new float[data.length * 2];
                System.arraycopy(data, 0, bigger, 0, len);
                data = bigger;
            }
            data[len++] = v;
        }
        int size() { return len; }
        float[] toArray() {
            float[] out = new float[len];
            System.arraycopy(data, 0, out, 0, len);
            return out;
        }
    }
}
