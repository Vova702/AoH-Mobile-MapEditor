package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cuts the territory of existing provinces out of a newly drawn one, so two provinces never
 * occupy the same ground. Pure Java (no Android classes) so it can be unit-tested.
 *
 * <p>Method: both outlines are split ("noded") at every point where they cross or touch, so they
 * share exactly the same vertices there. Every resulting piece of the new outline is then kept or
 * dropped depending on whether it lies inside the other province, and the pieces of the other
 * province's outline that lie inside the new one are added (reversed) as the new cut border.
 * Finally the kept pieces are linked into closed rings.
 *
 * <p>Crossing points are rounded to whole numbers. The SAME rounded point is used for both
 * outlines, and it is also inserted into the other province's outline ({@link Outcome#updatedOthers}),
 * so the two borders coincide exactly: no gap and no overlap, and the provinces still count as
 * neighbors (neighbors are detected through shared vertices).
 *
 * <p>All coordinates must fit in a signed 16-bit range (the same limit the game files have).
 */
final class PolygonTrim {

    private PolygonTrim() {
    }

    /** Parts smaller than this (area, in coordinate units squared) are rounding slivers and are dropped. */
    static final int MIN_PART_AREA = 2;

    enum Problem {
        NONE,
        /** The new polygon lies completely inside other provinces: nothing is left of it. */
        NOTHING_LEFT,
        /** What is left would need a hole (the new polygon surrounds another province). */
        HAS_HOLE
    }

    record Poly(int[] x, int[] y) {
        int size() {
            return x.length;
        }
    }

    static final class Outcome {
        /** true when the new polygon really overlapped the area of at least one other polygon. */
        boolean overlapped;
        Problem problem = Problem.NONE;
        /** What remains of the new polygon: one outline per separate piece. */
        final List<Poly> parts = new ArrayList<>();
        /** Rings that would be holes (only filled when {@code problem == HAS_HOLE}). */
        final List<Poly> holes = new ArrayList<>();
        /** Index (into the {@code others} list) -> that polygon with the cut points inserted (same shape). */
        final Map<Integer, Poly> updatedOthers = new LinkedHashMap<>();
    }

    /**
     * @param subject the newly drawn outline (any orientation, at least 3 distinct points)
     * @param others  the outlines it must not overlap (only the ones that may be near are needed)
     * @throws IllegalArgumentException when the input cannot be processed (degenerate or out of range);
     *                                  callers should then keep the polygon as drawn
     */
    static Outcome subtract(Poly subject, List<Poly> others) {
        var out = new Outcome();

        Poly s = clean(subject);
        if (s == null) throw new IllegalArgumentException("Degenerate polygon");
        boolean subjectReversed = area2(s) < 0;
        if (subjectReversed) s = reverse(s);

        List<int[]> region = edgesOf(s);

        for (int k = 0; k < others.size(); k++) {
            Poly b = clean(others.get(k));
            if (b == null) continue;
            boolean bReversed = area2(b) < 0;
            if (bReversed) b = reverse(b);
            if (!boxesTouch(region, b)) continue;

            Step step = step(region, b);
            if (!step.overlapped) continue;

            out.overlapped = true;
            region = step.region;
            if (step.bRing.length != b.size()) {
                var nb = new Poly(step.bRingX(), step.bRingY());
                out.updatedOthers.put(k, bReversed ? reverse(nb) : nb);
            }
        }

        if (!out.overlapped) return out;

        for (int[][] ring : link(region)) {
            Poly p = ringToPoly(ring);
            if (p == null) continue;
            long a2 = area2(p);
            if (Math.abs(a2) < 2L * MIN_PART_AREA) continue;
            if (a2 > 0) out.parts.add(subjectReversed ? reverse(p) : p);
            else out.holes.add(subjectReversed ? p : reverse(p));
        }

        if (!out.holes.isEmpty()) out.problem = Problem.HAS_HOLE;
        else if (out.parts.isEmpty()) out.problem = Problem.NOTHING_LEFT;
        return out;
    }

    // ------------------------------------------------------------------
    // one subtraction
    // ------------------------------------------------------------------

    private static final class Step {
        boolean overlapped;
        List<int[]> region;
        int[][] bRing; // B's outline with the cut points inserted, as {x,y} pairs, in B's (normalised) order

        int[] bRingX() {
            var r = new int[bRing.length];
            for (int i = 0; i < r.length; i++) r[i] = bRing[i][0];
            return r;
        }

        int[] bRingY() {
            var r = new int[bRing.length];
            for (int i = 0; i < r.length; i++) r[i] = bRing[i][1];
            return r;
        }
    }

    /** region - b, where both are directed with the interior on the left (positive area). */
    private static Step step(List<int[]> region, Poly b) {
        List<int[]> bEdges = edgesOf(b);

        List<List<double[]>> splitsR = newLists(region.size());
        List<List<double[]>> splitsB = newLists(bEdges.size());
        for (int i = 0; i < region.size(); i++) {
            int[] a = region.get(i);
            for (int j = 0; j < bEdges.size(); j++) {
                int[] e = bEdges.get(j);
                if (Math.max(a[0], a[2]) < Math.min(e[0], e[2]) || Math.min(a[0], a[2]) > Math.max(e[0], e[2])
                        || Math.max(a[1], a[3]) < Math.min(e[1], e[3]) || Math.min(a[1], a[3]) > Math.max(e[1], e[3])) {
                    continue;
                }
                intersect(a, e, splitsR.get(i), splitsB.get(j));
            }
        }

        // Each piece also gets the midpoint of its TRUE (unrounded) stretch of the original edge:
        // deciding inside/outside from the rounded end points is unreliable for pieces that are only
        // a pixel or two long.
        List<int[]> rSub = new ArrayList<>();
        List<double[]> rMid = new ArrayList<>();
        for (int i = 0; i < region.size(); i++) splitEdge(region.get(i), splitsR.get(i), rSub, rMid, null);
        List<int[]> bSub = new ArrayList<>();
        List<double[]> bMid = new ArrayList<>();
        List<int[]> bRing = new ArrayList<>();
        for (int j = 0; j < bEdges.size(); j++) splitEdge(bEdges.get(j), splitsB.get(j), bSub, bMid, bRing);

        Set<Long> rDir = new HashSet<>();
        for (int[] e : rSub) rDir.add(key(e[0], e[1], e[2], e[3]));
        Set<Long> bDir = new HashSet<>();
        for (int[] e : bSub) bDir.add(key(e[0], e[1], e[2], e[3]));

        var st = new Step();
        List<int[]> kept = new ArrayList<>();

        for (int i = 0; i < rSub.size(); i++) {
            int[] e = rSub.get(i);
            if (bDir.contains(key(e[0], e[1], e[2], e[3]))) {
                st.overlapped = true;                       // same direction: both interiors on the same side
            } else if (bDir.contains(key(e[2], e[3], e[0], e[1]))) {
                kept.add(e);                                // opposite direction: they only touch
            } else if (inside(bEdges, rMid.get(i)[0], rMid.get(i)[1])) {
                st.overlapped = true;                       // inside the other province: cut away
            } else {
                kept.add(e);
            }
        }
        for (int j = 0; j < bSub.size(); j++) {
            int[] f = bSub.get(j);
            if (rDir.contains(key(f[0], f[1], f[2], f[3])) || rDir.contains(key(f[2], f[3], f[0], f[1]))) continue;
            if (inside(region, bMid.get(j)[0], bMid.get(j)[1])) {
                st.overlapped = true;
                kept.add(new int[]{f[2], f[3], f[0], f[1]}); // the cut border, reversed
            }
        }

        st.bRing = bRing.toArray(new int[0][]);
        if (!st.overlapped) return st;

        // zero-width slits: an edge and its exact opposite cancel each other
        Map<Long, List<int[]>> byKey = new HashMap<>();
        List<int[]> result = new ArrayList<>();
        for (int[] e : kept) {
            List<int[]> opposite = byKey.get(key(e[2], e[3], e[0], e[1]));
            if (opposite != null && !opposite.isEmpty()) {
                opposite.remove(opposite.size() - 1);
                continue;
            }
            byKey.computeIfAbsent(key(e[0], e[1], e[2], e[3]), z -> new ArrayList<>()).add(e);
        }
        for (List<int[]> l : byKey.values()) result.addAll(l);
        st.region = result;
        return st;
    }

    private static List<List<double[]>> newLists(int n) {
        List<List<double[]>> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(new ArrayList<>(2));
        return l;
    }

    /**
     * Adds to {@code sa} / {@code sb} the points where segments a and b cross, touch or overlap.
     * A split is {x, y, t}: the (rounded) point and its exact position t in 0..1 along that edge.
     */
    private static void intersect(int[] a, int[] b, List<double[]> sa, List<double[]> sb) {
        long px = a[0], py = a[1], rx = a[2] - a[0], ry = a[3] - a[1];
        long qx = b[0], qy = b[1], sx = b[2] - b[0], sy = b[3] - b[1];
        long d = rx * sy - ry * sx;
        long qpx = qx - px, qpy = qy - py;
        if (d != 0) {
            long tn = qpx * sy - qpy * sx;
            long un = qpx * ry - qpy * rx;
            if (d < 0) {
                d = -d;
                tn = -tn;
                un = -un;
            }
            if (tn < 0 || tn > d || un < 0 || un > d) return;
            int x, y;
            if (tn == 0) {
                x = a[0];
                y = a[1];
            } else if (tn == d) {
                x = a[2];
                y = a[3];
            } else if (un == 0) {
                x = b[0];
                y = b[1];
            } else if (un == d) {
                x = b[2];
                y = b[3];
            } else {
                x = (int) Math.round(px + (double) tn * rx / d);
                y = (int) Math.round(py + (double) tn * ry / d);
            }
            sa.add(new double[]{x, y, (double) tn / d});
            sb.add(new double[]{x, y, (double) un / d});
        } else {
            if (qpx * ry - qpy * rx != 0) return; // parallel, not on one line
            if (onSegment(a, b[0], b[1])) sa.add(new double[]{b[0], b[1], along(a, b[0], b[1])});
            if (onSegment(a, b[2], b[3])) sa.add(new double[]{b[2], b[3], along(a, b[2], b[3])});
            if (onSegment(b, a[0], a[1])) sb.add(new double[]{a[0], a[1], along(b, a[0], a[1])});
            if (onSegment(b, a[2], a[3])) sb.add(new double[]{a[2], a[3], along(b, a[2], a[3])});
        }
    }

    /** Position 0..1 of a point lying on segment e. */
    private static double along(int[] e, int x, int y) {
        double dx = e[2] - e[0], dy = e[3] - e[1];
        return ((x - e[0]) * dx + (y - e[1]) * dy) / (dx * dx + dy * dy);
    }

    private static boolean onSegment(int[] e, int x, int y) {
        long cross = (long) (e[2] - e[0]) * (y - e[1]) - (long) (e[3] - e[1]) * (x - e[0]);
        if (cross != 0) return false;
        return x >= Math.min(e[0], e[2]) && x <= Math.max(e[0], e[2])
                && y >= Math.min(e[1], e[3]) && y <= Math.max(e[1], e[3]);
    }

    /**
     * Splits {@code e} at the given points. Pieces go to {@code outEdges}; {@code outMids} gets, for each
     * piece, the midpoint of its true stretch of {@code e}. When {@code ring} is not null the start of
     * every piece is appended to it (that is the outline with the new vertices inserted).
     * Points that round to the same integer point collapse into one node.
     */
    private static void splitEdge(int[] e, List<double[]> splits, List<int[]> outEdges,
                                  List<double[]> outMids, List<int[]> ring) {
        List<double[]> sorted = new ArrayList<>(splits);
        sorted.sort((p, q) -> Double.compare(p[2], q[2]));
        sorted.add(new double[]{e[2], e[3], 1.0});

        double dx = e[2] - e[0], dy = e[3] - e[1];
        int curX = e[0], curY = e[1];
        double curT = 0.0;
        for (double[] sp : sorted) {
            int x = (int) sp[0], y = (int) sp[1];
            if (x == curX && y == curY) {
                curT = sp[2];
                continue;
            }
            double tm = (curT + sp[2]) / 2.0;
            outEdges.add(new int[]{curX, curY, x, y});
            outMids.add(new double[]{e[0] + tm * dx, e[1] + tm * dy});
            if (ring != null) ring.add(new int[]{curX, curY});
            curX = x;
            curY = y;
            curT = sp[2];
        }
    }

    // ------------------------------------------------------------------
    // linking kept edges into rings
    // ------------------------------------------------------------------

    private static List<int[][]> link(List<int[]> edges) {
        Map<Long, List<Integer>> from = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            int[] e = edges.get(i);
            from.computeIfAbsent(pointKey(e[0], e[1]), z -> new ArrayList<>()).add(i);
        }
        var used = new boolean[edges.size()];
        List<int[][]> rings = new ArrayList<>();

        for (int i = 0; i < edges.size(); i++) {
            if (used[i]) continue;
            List<int[]> pts = new ArrayList<>();
            int[] first = edges.get(i);
            used[i] = true;
            int[] cur = first;
            pts.add(new int[]{cur[0], cur[1]});
            int guard = edges.size() + 2;
            while (!(cur[2] == first[0] && cur[3] == first[1])) {
                if (guard-- < 0) throw new IllegalStateException("Ring does not close");
                List<Integer> cands = from.get(pointKey(cur[2], cur[3]));
                int best = -1;
                double bestAngle = -10;
                if (cands != null) {
                    for (int c : cands) {
                        if (used[c]) continue;
                        int[] n = edges.get(c);
                        double din_x = cur[2] - cur[0], din_y = cur[3] - cur[1];
                        double dout_x = n[2] - n[0], dout_y = n[3] - n[1];
                        // the sharpest left turn keeps touching rings apart
                        double ang = Math.atan2(din_x * dout_y - din_y * dout_x, din_x * dout_x + din_y * dout_y);
                        if (ang > bestAngle) {
                            bestAngle = ang;
                            best = c;
                        }
                    }
                }
                if (best < 0) throw new IllegalStateException("Open outline");
                used[best] = true;
                cur = edges.get(best);
                pts.add(new int[]{cur[0], cur[1]});
            }
            rings.add(pts.toArray(new int[0][]));
        }
        return rings;
    }

    private static Poly ringToPoly(int[][] ring) {
        List<int[]> pts = new ArrayList<>(Arrays.asList(ring));
        boolean changed = true;
        while (changed && pts.size() >= 3) {
            changed = false;
            for (int i = 0; i < pts.size() && pts.size() >= 3; i++) {
                int[] p = pts.get((i + pts.size() - 1) % pts.size());
                int[] c = pts.get(i);
                int[] n = pts.get((i + 1) % pts.size());
                boolean dup = c[0] == p[0] && c[1] == p[1];
                boolean spike = p[0] == n[0] && p[1] == n[1];
                if (dup || spike) {
                    pts.remove(i);
                    changed = true;
                    i--;
                }
            }
        }
        if (pts.size() < 3) return null;
        int[] x = new int[pts.size()], y = new int[pts.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = pts.get(i)[0];
            y[i] = pts.get(i)[1];
        }
        return new Poly(x, y);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Removes repeated points and a repeated closing point; null when fewer than 3 points remain. */
    private static Poly clean(Poly p) {
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) {
            check(p.x()[i]);
            check(p.y()[i]);
            if (!pts.isEmpty()) {
                int[] last = pts.get(pts.size() - 1);
                if (last[0] == p.x()[i] && last[1] == p.y()[i]) continue;
            }
            pts.add(new int[]{p.x()[i], p.y()[i]});
        }
        while (pts.size() > 1 && pts.get(0)[0] == pts.get(pts.size() - 1)[0] && pts.get(0)[1] == pts.get(pts.size() - 1)[1]) {
            pts.remove(pts.size() - 1);
        }
        if (pts.size() < 3) return null;
        int[] x = new int[pts.size()], y = new int[pts.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = pts.get(i)[0];
            y[i] = pts.get(i)[1];
        }
        var r = new Poly(x, y);
        return area2(r) == 0 ? null : r;
    }

    private static void check(int v) {
        if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) throw new IllegalArgumentException("Coordinate out of range: " + v);
    }

    private static List<int[]> edgesOf(Poly p) {
        List<int[]> e = new ArrayList<>(p.size());
        for (int i = 0; i < p.size(); i++) {
            int j = (i + 1) % p.size();
            e.add(new int[]{p.x()[i], p.y()[i], p.x()[j], p.y()[j]});
        }
        return e;
    }

    /** Twice the signed area; positive = interior on the left (counter-clockwise in a y-up system). */
    static long area2(Poly p) {
        long s = 0;
        for (int i = 0; i < p.size(); i++) {
            int j = (i + 1) % p.size();
            s += (long) p.x()[i] * p.y()[j] - (long) p.x()[j] * p.y()[i];
        }
        return s;
    }

    private static Poly reverse(Poly p) {
        int n = p.size();
        int[] x = new int[n], y = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = p.x()[n - 1 - i];
            y[i] = p.y()[n - 1 - i];
        }
        return new Poly(x, y);
    }

    private static boolean boxesTouch(List<int[]> region, Poly b) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] e : region) {
            minX = Math.min(minX, Math.min(e[0], e[2]));
            maxX = Math.max(maxX, Math.max(e[0], e[2]));
            minY = Math.min(minY, Math.min(e[1], e[3]));
            maxY = Math.max(maxY, Math.max(e[1], e[3]));
        }
        int bMinX = Integer.MAX_VALUE, bMaxX = Integer.MIN_VALUE, bMinY = Integer.MAX_VALUE, bMaxY = Integer.MIN_VALUE;
        for (int i = 0; i < b.size(); i++) {
            bMinX = Math.min(bMinX, b.x()[i]);
            bMaxX = Math.max(bMaxX, b.x()[i]);
            bMinY = Math.min(bMinY, b.y()[i]);
            bMaxY = Math.max(bMaxY, b.y()[i]);
        }
        return !(bMaxX < minX || bMinX > maxX || bMaxY < minY || bMinY > maxY);
    }

    /** Even-odd point-in-region test over directed edges. */
    private static boolean inside(List<int[]> edges, double mx, double my) {
        boolean in = false;
        for (int[] e : edges) {
            if ((e[1] > my) != (e[3] > my)) {
                double xi = e[0] + (my - e[1]) * (e[2] - e[0]) / (double) (e[3] - e[1]);
                if (mx < xi) in = !in;
            }
        }
        return in;
    }

    private static long pointKey(int x, int y) {
        return ((long) (x & 0xFFFF) << 16) | (y & 0xFFFF);
    }

    private static long key(int x1, int y1, int x2, int y2) {
        return (pointKey(x1, y1) << 32) | pointKey(x2, y2);
    }
}
