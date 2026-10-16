package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Province adjacency + shared border polylines, computed from outline
 * vertices (a vertex that appears in two outlines means those provinces
 * touch there). Used synchronously by the live editor and by
 * {@link MapExporter}, which works purely off files on disk.
 *
 * Unlike the first version of this algorithm, a vertex can have MANY
 * owners: where three provinces meet at one point, every pair among them
 * is recorded as neighbors (previously only the first province to claim
 * the point counted, so some adjacencies were lost).
 *
 * Horizontal wrap-around: a point at x also matches x +/- backgroundWidth.
 */
final class NeighborComputer {

    private NeighborComputer() {
    }

    /** One shared run of consecutive outline vertices with a neighbor. */
    record Border(int withProvince, short[] xs, short[] ys) {
    }

    /**
     * @param neighbors neighbors.get(p) - ids of provinces touching p, first-seen order, no duplicates, never p itself.
     * @param borders   borders.get(p) - like the original game data: exactly one border per neighbor PAIR, stored on the
     *                  province with the smaller id (withProvince is the larger id), as the longest shared outline run.
     */
    record Result(List<List<Integer>> neighbors, List<List<Border>> borders) {
    }

    /** Neighbors only (cheaper; used by the on-screen debug overlay). */
    static List<List<Integer>> compute(List<List<Integer>> pointsX, List<List<Integer>> pointsY, int backgroundWidth) {
        return computeAll(pointsX, pointsY, backgroundWidth, false, 1).neighbors();
    }

    /**
     * @param minSharedVertices how many outline vertices two provinces must share to count as neighbors
     *                          (1 = touching at a single corner counts; 2 = they must share a segment)
     */
    static Result computeAll(List<List<Integer>> pointsX, List<List<Integer>> pointsY,
                             int backgroundWidth, boolean withBorders, int minSharedVertices) {
        final int count = pointsX.size();

        // vertex -> every province that has it on its outline
        final HashMap<Long, int[]> owners = new HashMap<>();
        for (int p = 0; p < count; p++) {
            List<Integer> xs = pointsX.get(p);
            List<Integer> ys = pointsY.get(p);
            int n = Math.min(xs.size(), ys.size());
            for (int j = 0; j < n; j++) {
                long key = key(xs.get(j), ys.get(j));
                int[] cur = owners.get(key);
                if (cur == null) {
                    owners.put(key, new int[]{p});
                } else if (cur[cur.length - 1] != p) {
                    var grown = new int[cur.length + 1];
                    System.arraycopy(cur, 0, grown, 0, cur.length);
                    grown[cur.length] = p;
                    owners.put(key, grown);
                }
            }
        }

        final List<List<Integer>> neighbors = new ArrayList<>(count);
        final List<List<Border>> borders = new ArrayList<>(count);

        for (int p = 0; p < count; p++) {
            List<Integer> xs = pointsX.get(p);
            List<Integer> ys = pointsY.get(p);
            int n = Math.min(xs.size(), ys.size());

            // per vertex: set of other provinces touching it
            List<int[]> perVertex = new ArrayList<>(n);
            LinkedHashSet<Integer> seen = new LinkedHashSet<>();
            HashMap<Integer, Integer> sharedCount = new HashMap<>();
            for (int j = 0; j < n; j++) {
                LinkedHashSet<Integer> here = new LinkedHashSet<>();
                int x = xs.get(j);
                int y = ys.get(j);
                int[] candX = backgroundWidth > 0
                        ? new int[]{x, x + backgroundWidth, x - backgroundWidth}
                        : new int[]{x};
                for (int cx : candX) {
                    int[] o = owners.get(key(cx, y));
                    if (o == null) continue;
                    for (int other : o) {
                        if (other != p) here.add(other);
                    }
                }
                var arr = new int[here.size()];
                int k = 0;
                for (int v : here) arr[k++] = v;
                perVertex.add(arr);
                for (int v : arr) {
                    Integer c = sharedCount.get(v);
                    sharedCount.put(v, c == null ? 1 : c + 1);
                    seen.add(v);
                }
            }
            if (minSharedVertices > 1) {
                LinkedHashSet<Integer> kept = new LinkedHashSet<>();
                for (int v : seen) {
                    if (sharedCount.get(v) >= minSharedVertices) kept.add(v);
                }
                seen = kept;
            }
            neighbors.add(new ArrayList<>(seen));

            if (!withBorders) {
                borders.add(new ArrayList<>());
                continue;
            }
            borders.add(buildBorders(p, xs, ys, n, perVertex, seen));
        }

        return new Result(neighbors, borders);
    }

    /** Maximal cyclic runs (>= 2 vertices) of outline vertices shared with each neighbor. */
    private static List<Border> buildBorders(int ownerId, List<Integer> xs, List<Integer> ys, int n,
                                             List<int[]> perVertex, LinkedHashSet<Integer> neighborSet) {
        List<Border> out = new ArrayList<>();
        if (n < 2) return out;

        for (int nb : neighborSet) {
            if (nb < ownerId) continue; // the pair's single border lives on the smaller-id province
            var shared = new boolean[n];
            int sharedCount = 0;
            for (int j = 0; j < n; j++) {
                for (int o : perVertex.get(j)) {
                    if (o == nb) {
                        shared[j] = true;
                        sharedCount++;
                        break;
                    }
                }
            }
            if (sharedCount < 2) continue;

            if (sharedCount == n) {
                out.add(makeBorder(nb, xs, ys, 0, n, n));
                continue;
            }

            int bestStart = -1;
            int bestLen = 0;

            // start the scan at a non-shared vertex so runs never straddle the seam
            int start = 0;
            while (shared[start]) start++;

            int j = 0;
            while (j < n) {
                int idx = (start + j) % n;
                if (!shared[idx]) {
                    j++;
                    continue;
                }
                int runStart = idx;
                int len = 0;
                while (j < n && shared[(start + j) % n]) {
                    len++;
                    j++;
                }
                if (len >= 2 && len > bestLen) {
                    bestLen = len;
                    bestStart = runStart;
                }
            }
            if (bestStart >= 0) out.add(makeBorder(nb, xs, ys, bestStart, bestLen, n));
        }
        return out;
    }

    private static Border makeBorder(int nb, List<Integer> xs, List<Integer> ys, int from, int len, int n) {
        var bx = new short[len];
        var by = new short[len];
        for (int k = 0; k < len; k++) {
            int i = (from + k) % n;
            bx[k] = (short) (int) xs.get(i);
            by[k] = (short) (int) ys.get(i);
        }
        return new Border(nb, bx, by);
    }

    /**
     * Splits a province's neighbors into the two lists the game stores,
     * following the rule observed in the original maps (Earth / Kepler):
     *  - LAND province: lNeighboringProvinces = land neighbors,
     *                   lNeighboringSeaProvinces = sea neighbors;
     *  - SEA province:  lNeighboringProvinces = ALL neighbors (land + sea),
     *                   lNeighboringSeaProvinces stays empty.
     */
    static void split(boolean selfSea, List<Integer> all, List<Boolean> isSea,
                      List<Integer> mainList, List<Integer> seaList) {
        for (Integer n : all) {
            if (n == null) continue;
            boolean nSea = n >= 0 && n < isSea.size() && Boolean.TRUE.equals(isSea.get(n));
            if (selfSea || !nSea) mainList.add(n);
            else seaList.add(n);
        }
    }

    private static long key(int x, int y) {
        return (((long) x) << 32) | (y & 0xffffffffL);
    }
}
