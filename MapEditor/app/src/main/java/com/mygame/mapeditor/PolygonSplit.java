package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts one simple polygon into two parts with a polyline ("cut line").
 *
 * <p>Pure Java (no Android classes) so it can be unit-tested on a normal JVM.</p>
 *
 * <p>How it works:
 * <ol>
 *   <li>If the first / last point of the cut line lies inside the polygon, the line is extended
 *       in the direction of its first / last segment until it reaches the border. So the user may
 *       simply tap two points inside a province and get a straight cut.</li>
 *   <li>The line is split at every place where it meets the border; every piece is classified
 *       (inside / outside / along the border) by its own midpoint. Pieces outside the polygon
 *       are ignored.</li>
 *   <li>Exactly one run of inside pieces must remain (otherwise the line would cut the
 *       province into three or more parts); its ends are the two border points.</li>
 *   <li>The two parts are: border from end 1 to end 2 + the cut line, and the rest of the border + the cut line.</li>
 * </ol>
 * Both parts get exactly the same cut vertices, so the new border has identical points on both sides.
 */
final class PolygonSplit {

    private PolygonSplit() {
    }

    enum Problem {
        NONE,
        /** Fewer than two different points. */
        TOO_FEW_POINTS,
        /** The line does not go through the province. */
        NO_CROSSING,
        /** The line enters and leaves the province more than once (would give 3+ parts). */
        MULTIPLE_CROSSINGS,
        /** The line touches the border somewhere in the middle of the cut. */
        TOUCHES_BORDER,
        /** The part of the line inside the province crosses itself. */
        SELF_CROSSING,
        /** The line starts or ends inside the province and could not be extended to the border. */
        ENDS_NOT_ON_BORDER,
        /** Rounding to whole pixels would produce a broken / empty part. */
        BAD_RESULT
    }

    static final class Outcome {
        Problem problem = Problem.NONE;
        /** The bigger part (it keeps the province id). */
        PolygonTrim.Poly partA;
        /** The smaller part (becomes a new province). */
        PolygonTrim.Poly partB;
        /** Length of the cut inside the province. */
        double cutLength;
        /** The two points where the cut meets the border, {x, y}. */
        final List<int[]> borderPoints = new ArrayList<>();

        boolean ok() {
            return problem == Problem.NONE;
        }
    }

    private static final double EPS = 1e-9;
    private static final double POS_EPS = 1e-7;
    private static final int INSIDE = 0, OUTSIDE = 1, BORDER = 2;

    private static final class Breakpoint {
        double g, x, y;
        boolean onBorder;
        int vertex = -1;   // polygon vertex index if the point is exactly a vertex
        int edge = -1;     // otherwise the edge it lies on
        double u;
    }

    // ------------------------------------------------------------------

    static Outcome split(PolygonTrim.Poly poly, int[] lineX, int[] lineY) {
        Outcome out = new Outcome();
        int n = poly.size();
        if (n < 3) {
            out.problem = Problem.BAD_RESULT;
            return out;
        }
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = poly.x()[i];
            py[i] = poly.y()[i];
        }

        List<double[]> line = new ArrayList<>();
        for (int i = 0; i < Math.min(lineX.length, lineY.length); i++) {
            if (line.isEmpty() || line.get(line.size() - 1)[0] != lineX[i] || line.get(line.size() - 1)[1] != lineY[i]) {
                line.add(new double[]{lineX[i], lineY[i]});
            }
        }
        if (line.size() < 2) {
            out.problem = Problem.TOO_FEW_POINTS;
            return out;
        }

        // 1. extend ends that are inside
        if (locate(px, py, line.get(0)[0], line.get(0)[1]) == INSIDE) {
            double[] hit = rayHit(px, py, line.get(0), line.get(1));
            if (hit == null) {
                out.problem = Problem.ENDS_NOT_ON_BORDER;
                return out;
            }
            line.add(0, hit);
        }
        int last = line.size() - 1;
        if (locate(px, py, line.get(last)[0], line.get(last)[1]) == INSIDE) {
            double[] hit = rayHit(px, py, line.get(last), line.get(last - 1));
            if (hit == null) {
                out.problem = Problem.ENDS_NOT_ON_BORDER;
                return out;
            }
            line.add(hit);
        }

        // 2. break points: line vertices + every contact with the border
        List<Breakpoint> raw = new ArrayList<>();
        int m = line.size();
        for (int j = 0; j < m; j++) {
            Breakpoint b = new Breakpoint();
            b.g = j;
            b.x = line.get(j)[0];
            b.y = line.get(j)[1];
            raw.add(b);
        }
        for (int j = 0; j + 1 < m; j++) {
            double ax = line.get(j)[0], ay = line.get(j)[1];
            double dx = line.get(j + 1)[0] - ax, dy = line.get(j + 1)[1] - ay;
            double dd = dx * dx + dy * dy;
            double dl = Math.sqrt(dd);
            for (int i = 0; i < n; i++) {
                int i2 = (i + 1) % n;
                double qx = px[i], qy = py[i];
                double ex = px[i2] - qx, ey = py[i2] - qy;
                double el = Math.sqrt(ex * ex + ey * ey);
                if (el == 0) continue;
                double denom = dx * ey - dy * ex;
                if (Math.abs(denom) > 1e-12 * dl * el) {
                    double t = ((qx - ax) * ey - (qy - ay) * ex) / denom;
                    double u = ((qx - ax) * dy - (qy - ay) * dx) / denom;
                    if (t >= -EPS && t <= 1 + EPS && u >= -EPS && u <= 1 + EPS) {
                        t = Math.max(0, Math.min(1, t));
                        u = Math.max(0, Math.min(1, u));
                        addEvent(raw, j + t, ax + t * dx, ay + t * dy, i, u, n);
                    }
                } else {
                    // parallel: only collinear overlap matters
                    double dist = Math.abs((qx - ax) * dy - (qy - ay) * dx) / dl;
                    if (dist > POS_EPS) continue;
                    double ta = ((qx - ax) * dx + (qy - ay) * dy) / dd;
                    double tb = ((px[i2] - ax) * dx + (py[i2] - ay) * dy) / dd;
                    if (ta >= -EPS && ta <= 1 + EPS) {
                        double t = Math.max(0, Math.min(1, ta));
                        addEvent(raw, j + t, qx, qy, i, 0, n);
                    }
                    if (tb >= -EPS && tb <= 1 + EPS) {
                        double t = Math.max(0, Math.min(1, tb));
                        addEvent(raw, j + t, px[i2], py[i2], i, 1, n);
                    }
                    double ua = ((ax - qx) * ex + (ay - qy) * ey) / (el * el);
                    double ub = ((ax + dx - qx) * ex + (ay + dy - qy) * ey) / (el * el);
                    if (ua >= -EPS && ua <= 1 + EPS) addEvent(raw, j, ax, ay, i, Math.max(0, Math.min(1, ua)), n);
                    if (ub >= -EPS && ub <= 1 + EPS) addEvent(raw, j + 1, ax + dx, ay + dy, i, Math.max(0, Math.min(1, ub)), n);
                }
            }
        }
        raw.sort((p, q) -> Double.compare(p.g, q.g));

        List<Breakpoint> bp = new ArrayList<>();
        for (Breakpoint b : raw) {
            Breakpoint prev = bp.isEmpty() ? null : bp.get(bp.size() - 1);
            if (prev != null && Math.abs(prev.x - b.x) < POS_EPS && Math.abs(prev.y - b.y) < POS_EPS) {
                if (b.onBorder && (!prev.onBorder || (prev.vertex < 0 && b.vertex >= 0))) {
                    prev.onBorder = true;
                    prev.vertex = b.vertex;
                    prev.edge = b.edge;
                    prev.u = b.u;
                }
            } else {
                bp.add(b);
            }
        }

        // 3. classify pieces, find the runs of "inside"
        int pieces = bp.size() - 1;
        int[] cls = new int[Math.max(pieces, 0)];
        int runs = 0, runStart = -1, runEnd = -1;
        for (int k = 0; k < pieces; k++) {
            Breakpoint a = bp.get(k), b = bp.get(k + 1);
            cls[k] = locate(px, py, (a.x + b.x) / 2, (a.y + b.y) / 2);
            if (cls[k] == INSIDE && (k == 0 || cls[k - 1] != INSIDE)) {
                runs++;
                if (runs == 1) runStart = k;
            }
            if (cls[k] == INSIDE && runs == 1) runEnd = k;
        }
        if (runs == 0) {
            out.problem = Problem.NO_CROSSING;
            return out;
        }
        if (runs > 1) {
            out.problem = Problem.MULTIPLE_CROSSINGS;
            return out;
        }

        Breakpoint start = bp.get(runStart), end = bp.get(runEnd + 1);
        if (!start.onBorder || !end.onBorder) {
            out.problem = Problem.ENDS_NOT_ON_BORDER;
            return out;
        }
        for (int k = runStart + 1; k <= runEnd; k++) {
            if (bp.get(k).onBorder) {
                out.problem = Problem.TOUCHES_BORDER;
                return out;
            }
        }

        // 4. the cut (double precision) and its self-crossing check
        List<double[]> chord = new ArrayList<>();
        for (int k = runStart; k <= runEnd + 1; k++) chord.add(new double[]{bp.get(k).x, bp.get(k).y});
        if (selfCrossing(chord)) {
            out.problem = Problem.SELF_CROSSING;
            return out;
        }
        double len = 0;
        for (int k = 0; k + 1 < chord.size(); k++) {
            len += Math.hypot(chord.get(k + 1)[0] - chord.get(k)[0], chord.get(k + 1)[1] - chord.get(k)[1]);
        }
        out.cutLength = len;

        // 5. whole-pixel border points
        int[] n1 = borderNode(start, px, py, n);
        int[] n2 = borderNode(end, px, py, n);
        if (n1[0] == n2[0] && n1[1] == n2[1]) {
            out.problem = Problem.BAD_RESULT;
            return out;
        }
        double s1 = ringPos(n1, start, n), s2 = ringPos(n2, end, n);

        List<int[]> mid = new ArrayList<>();
        for (int k = 1; k < chord.size() - 1; k++) {
            int[] c = {(int) Math.round(chord.get(k)[0]), (int) Math.round(chord.get(k)[1])};
            int[] prev = mid.isEmpty() ? n1 : mid.get(mid.size() - 1);
            if (c[0] == prev[0] && c[1] == prev[1]) continue;
            if (c[0] == n2[0] && c[1] == n2[1]) continue;
            mid.add(c);
        }

        List<int[]> a = new ArrayList<>();
        a.add(n1);
        addPath(a, poly, s1, s2, n);
        a.add(n2);
        for (int k = mid.size() - 1; k >= 0; k--) a.add(mid.get(k));

        List<int[]> b = new ArrayList<>();
        b.add(n2);
        addPath(b, poly, s2, s1, n);
        b.add(n1);
        b.addAll(mid);

        PolygonTrim.Poly pa = toPoly(a), pb = toPoly(b);
        if (pa == null || pb == null) {
            out.problem = Problem.BAD_RESULT;
            return out;
        }
        long total = signedArea2(poly);
        long aa = signedArea2(pa), ab = signedArea2(pb);
        if (total == 0 || Long.signum(aa) != Long.signum(total) || Long.signum(ab) != Long.signum(total)
                || Math.abs(aa) < 2L * PolygonTrim.MIN_PART_AREA || Math.abs(ab) < 2L * PolygonTrim.MIN_PART_AREA
                || Math.abs(Math.abs(aa) + Math.abs(ab) - Math.abs(total)) > 4 + 0.8 * (edgeLen(start, px, py, n) + edgeLen(end, px, py, n))
                || !simple(pa) || !simple(pb)) {
            out.problem = Problem.BAD_RESULT;
            return out;
        }

        if (Math.abs(aa) >= Math.abs(ab)) {
            out.partA = pa;
            out.partB = pb;
        } else {
            out.partA = pb;
            out.partB = pa;
        }
        out.borderPoints.add(n1);
        out.borderPoints.add(n2);
        return out;
    }

    /**
     * Inserts the given border points into the outline of a neighbouring province, where its border
     * runs through them (within a pixel), so both provinces share the same vertices along the new border.
     *
     * @return the new outline, or null when nothing had to be inserted
     */
    static PolygonTrim.Poly insertBorderPoints(PolygonTrim.Poly q, List<int[]> points) {
        List<int[]> ring = new ArrayList<>();
        for (int i = 0; i < q.size(); i++) ring.add(new int[]{q.x()[i], q.y()[i]});
        boolean changed = false;
        for (int[] p : points) {
            boolean exists = false;
            for (int[] v : ring) {
                if (v[0] == p[0] && v[1] == p[1]) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;
            int bestEdge = -1;
            double best = 0.75;
            for (int k = 0; k < ring.size(); k++) {
                int[] a = ring.get(k), b = ring.get((k + 1) % ring.size());
                double d = distToSegment(p[0], p[1], a[0], a[1], b[0], b[1]);
                if (d <= best) {
                    best = d;
                    bestEdge = k;
                }
            }
            if (bestEdge >= 0) {
                ring.add(bestEdge + 1, new int[]{p[0], p[1]});
                changed = true;
            }
        }
        if (!changed) return null;
        return toPoly(ring);
    }

    // ------------------------------------------------------------------

    private static void addEvent(List<Breakpoint> list, double g, double x, double y, int edge, double u, int n) {
        Breakpoint b = new Breakpoint();
        b.g = g;
        b.x = x;
        b.y = y;
        b.onBorder = true;
        b.edge = edge;
        b.u = u;
        if (u <= EPS) b.vertex = edge;
        else if (u >= 1 - EPS) b.vertex = (edge + 1) % n;
        list.add(b);
    }

    /** First point where the ray from {@code from}, pointing away from {@code towards}, meets the border. */
    private static double[] rayHit(double[] px, double[] py, double[] from, double[] towards) {
        double dx = from[0] - towards[0], dy = from[1] - towards[1];
        int n = px.length;
        double bestT = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            double ex = px[i2] - px[i], ey = py[i2] - py[i];
            double denom = dx * ey - dy * ex;
            if (Math.abs(denom) < 1e-12) continue;
            double t = ((px[i] - from[0]) * ey - (py[i] - from[1]) * ex) / denom;
            double u = ((px[i] - from[0]) * dy - (py[i] - from[1]) * dx) / denom;
            if (t > EPS && u >= -EPS && u <= 1 + EPS && t < bestT) bestT = t;
        }
        if (bestT == Double.MAX_VALUE) return null;
        return new double[]{from[0] + bestT * dx, from[1] + bestT * dy};
    }

    private static int locate(double[] px, double[] py, double x, double y) {
        int n = px.length;
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            if (distToSegment(x, y, px[i], py[i], px[i2], py[i2]) <= POS_EPS) return BORDER;
        }
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((py[i] > y) != (py[j] > y)
                    && x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i]) {
                in = !in;
            }
        }
        return in ? INSIDE : OUTSIDE;
    }

    private static double distToSegment(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : ((x - ax) * dx + (y - ay) * dy) / l2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
    }

    private static boolean selfCrossing(List<double[]> c) {
        int s = c.size() - 1;
        for (int i = 0; i < s; i++) {
            for (int j = i + 1; j < s; j++) {
                double[] a = c.get(i), b = c.get(i + 1), p = c.get(j), q = c.get(j + 1);
                if (j == i + 1) {
                    // neighbours may only meet in their common point (no folding back)
                    double cr = (b[0] - a[0]) * (q[1] - p[1]) - (b[1] - a[1]) * (q[0] - p[0]);
                    double dot = (b[0] - a[0]) * (q[0] - p[0]) + (b[1] - a[1]) * (q[1] - p[1]);
                    if (Math.abs(cr) <= 1e-9 && dot < 0) return true;
                    continue;
                }
                if (segmentsIntersect(a, b, p, q)) return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(double[] a, double[] b, double[] p, double[] q) {
        double d1 = orient(p, q, a), d2 = orient(p, q, b), d3 = orient(a, b, p), d4 = orient(a, b, q);
        if (((d1 > 1e-9 && d2 < -1e-9) || (d1 < -1e-9 && d2 > 1e-9))
                && ((d3 > 1e-9 && d4 < -1e-9) || (d3 < -1e-9 && d4 > 1e-9))) return true;
        return (Math.abs(d1) <= 1e-9 && onSeg(p, q, a)) || (Math.abs(d2) <= 1e-9 && onSeg(p, q, b))
                || (Math.abs(d3) <= 1e-9 && onSeg(a, b, p)) || (Math.abs(d4) <= 1e-9 && onSeg(a, b, q));
    }

    private static double orient(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean onSeg(double[] a, double[] b, double[] c) {
        return c[0] >= Math.min(a[0], b[0]) - 1e-9 && c[0] <= Math.max(a[0], b[0]) + 1e-9
                && c[1] >= Math.min(a[1], b[1]) - 1e-9 && c[1] <= Math.max(a[1], b[1]) + 1e-9;
    }

    /** Length of the border edge a (non-vertex) contact lies on; moving the point by rounding changes the area by about this much. */
    private static double edgeLen(Breakpoint b, double[] px, double[] py, int n) {
        if (b.vertex >= 0) return 0;
        int i2 = (b.edge + 1) % n;
        return Math.hypot(px[i2] - px[b.edge], py[i2] - py[b.edge]);
    }

    /** The whole-pixel point for a border contact; an exact vertex stays that vertex. */
    private static int[] borderNode(Breakpoint b, double[] px, double[] py, int n) {
        if (b.vertex >= 0) return new int[]{(int) px[b.vertex], (int) py[b.vertex]};
        return new int[]{(int) Math.round(b.x), (int) Math.round(b.y)};
    }

    /** Position along the ring: vertex index, or edge index + fraction. */
    private static double ringPos(int[] node, Breakpoint b, int n) {
        if (b.vertex >= 0) return b.vertex;
        // a node that rounded onto a vertex is that vertex
        return b.edge + b.u;
    }

    /** Adds the ring vertices strictly between positions {@code from} and {@code to} (walking forward). */
    private static void addPath(List<int[]> dst, PolygonTrim.Poly poly, double from, double to, int n) {
        double span = ((to - from) % n + n) % n;
        int first = (int) Math.floor(from) + 1;
        for (int k = first; k - from < span - 1e-9 && k - first < n; k++) {
            int idx = ((k % n) + n) % n;
            dst.add(new int[]{poly.x()[idx], poly.y()[idx]});
        }
    }

    private static PolygonTrim.Poly toPoly(List<int[]> pts) {
        List<int[]> r = new ArrayList<>();
        for (int[] p : pts) {
            if (!r.isEmpty()) {
                int[] l = r.get(r.size() - 1);
                if (l[0] == p[0] && l[1] == p[1]) continue;
            }
            r.add(p);
        }
        while (r.size() > 1 && r.get(0)[0] == r.get(r.size() - 1)[0] && r.get(0)[1] == r.get(r.size() - 1)[1]) {
            r.remove(r.size() - 1);
        }
        if (r.size() < 3) return null;
        int[] x = new int[r.size()], y = new int[r.size()];
        for (int i = 0; i < r.size(); i++) {
            x[i] = r.get(i)[0];
            y[i] = r.get(i)[1];
        }
        return new PolygonTrim.Poly(x, y);
    }

    static long signedArea2(PolygonTrim.Poly p) {
        long s = 0;
        int n = p.size();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            s += (long) p.x()[i] * p.y()[j] - (long) p.x()[j] * p.y()[i];
        }
        return s;
    }

    /** No two non-neighbouring edges cross each other (touching is tolerated). */
    static boolean simple(PolygonTrim.Poly p) {
        int n = p.size();
        for (int i = 0; i < n; i++) {
            double[] a = {p.x()[i], p.y()[i]}, b = {p.x()[(i + 1) % n], p.y()[(i + 1) % n]};
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue;
                double[] c = {p.x()[j], p.y()[j]}, d = {p.x()[(j + 1) % n], p.y()[(j + 1) % n]};
                double d1 = orient(c, d, a), d2 = orient(c, d, b), d3 = orient(a, b, c), d4 = orient(a, b, d);
                if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return false;
            }
        }
        return true;
    }
}
