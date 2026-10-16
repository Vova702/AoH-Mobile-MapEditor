package com.mygame.mapeditor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

/**
 * Generates the province mask textures the game stores in
 * data/scales/provinces/&lt;scale&gt;/&lt;id&gt; (CIM format: zlib-compressed
 * width, height, pixel-format = 2 (LuminanceAlpha), then 2 bytes per pixel,
 * FF FE = province pixel, 00 00 = empty).
 *
 * This is a scanline re-implementation of the game's own
 * Province.buildProvinceBG(): a pixel belongs to the province when it lies
 * inside the outline and not inside a lower-id neighbor; the one-pixel rim
 * around the outline is claimed by the higher-id province of a pair. For
 * scale k the outline coordinates are multiplied by k first (the game's own
 * fallback ignores k, which is why precomputed masks matter for k &gt; 1).
 * Verified byte-for-byte against the original Earth masks.
 *
 * Neighbor lists passed in must be the SAME ones that are exported as the
 * province's lNeighboringProvinces (main list).
 */
final class ScaleMaskGenerator {

    private final int[][] px;      // outline incl. the duplicated last point the game appends
    private final int[][] py;
    private final int[] minX, maxX, minY, maxY;
    private final int[][] rowOff;  // per province: edge index ranges per scanline
    private final int[][] edges;
    private final int[][] neighbors;
    private int[] tmp = new int[1 << 12];

    ScaleMaskGenerator(List<List<Integer>> xs, List<List<Integer>> ys, List<List<Integer>> mainNeighbors) {
        int n = xs.size();
        px = new int[n][];
        py = new int[n][];
        minX = new int[n];
        maxX = new int[n];
        minY = new int[n];
        maxY = new int[n];
        rowOff = new int[n][];
        edges = new int[n][];
        neighbors = new int[n][];

        for (int id = 0; id < n; id++) {
            List<Integer> lx = xs.get(id);
            List<Integer> ly = ys.get(id);
            int c0 = Math.min(lx.size(), ly.size());
            int c = c0 + 1;
            var x = new int[c];
            var y = new int[c];
            for (int i = 0; i < c0; i++) {
                x[i] = lx.get(i);
                y[i] = ly.get(i);
            }
            if (c0 > 0) {
                x[c - 1] = x[c0 - 1];
                y[c - 1] = y[c0 - 1];
            }
            px[id] = x;
            py[id] = y;

            int mnx = Integer.MAX_VALUE, mxx = Integer.MIN_VALUE, mny = Integer.MAX_VALUE, mxy = Integer.MIN_VALUE;
            for (int i = 0; i < c0; i++) {
                mnx = Math.min(mnx, x[i]);
                mxx = Math.max(mxx, x[i]);
                mny = Math.min(mny, y[i]);
                mxy = Math.max(mxy, y[i]);
            }
            if (c0 == 0) {
                mnx = mxx = mny = mxy = 0;
            }
            minX[id] = mnx;
            maxX[id] = mxx;
            minY[id] = mny;
            maxY[id] = mxy;

            int rows = Math.max(0, mxy - mny);
            var cnt = new int[rows + 1];
            for (int i = 0; i < c; i++) {
                int j = i == 0 ? c - 1 : i - 1;
                int lo = Math.min(y[i], y[j]);
                int hi = Math.max(y[i], y[j]);
                for (int r = lo; r < hi; r++) {
                    if (r - mny >= 0 && r - mny < rows) cnt[r - mny + 1]++;
                }
            }
            for (int r = 0; r < rows; r++) cnt[r + 1] += cnt[r];
            var e = new int[cnt[rows]];
            int[] fill = Arrays.copyOf(cnt, Math.max(rows, 1));
            for (int i = 0; i < c; i++) {
                int j = i == 0 ? c - 1 : i - 1;
                int lo = Math.min(y[i], y[j]);
                int hi = Math.max(y[i], y[j]);
                for (int r = lo; r < hi; r++) {
                    if (r - mny >= 0 && r - mny < rows) e[fill[r - mny]++] = i;
                }
            }
            rowOff[id] = cnt;
            edges[id] = e;

            List<Integer> nl = mainNeighbors.get(id);
            var na = new int[nl.size()];
            for (int i = 0; i < na.length; i++) na[i] = nl.get(i);
            neighbors[id] = na;
        }
    }

    int width(int id, int scale) {
        return (maxX[id] - minX[id]) * scale;
    }

    int height(int id, int scale) {
        return (maxY[id] - minY[id]) * scale;
    }

    /** Complete CIM file bytes (already zlib-compressed) for one province at one scale. */
    byte[] cim(int id, int scale) throws IOException {
        int w = width(id, scale);
        int h = height(id, scale);
        byte[] pix = (w > 0 && h > 0) ? mask(id, scale, w, h) : new byte[0];
        var bo = new ByteArrayOutputStream(Math.max(64, pix.length / 8));
        try (DataOutputStream d = new DataOutputStream(new DeflaterOutputStream(bo))) {
            d.writeInt(w);
            d.writeInt(h);
            d.writeInt(2);
            d.write(pix);
        }
        return bo.toByteArray();
    }

    // ---- rasterization -------------------------------------------------

    private void row(int id, int k, int Y, int X0, int wn, boolean[] t1, boolean[] t2) {
        int r = Math.floorDiv(Y, k) - minY[id];
        if (r < 0 || r >= maxY[id] - minY[id]) return;
        int[] x = px[id], y = py[id], off = rowOff[id], ed = edges[id];
        int m = 0;
        for (int e = off[r]; e < off[r + 1]; e++) {
            int i = ed[e];
            int j = i == 0 ? x.length - 1 : i - 1;
            long dy = (long) (y[j] - y[i]) * k;
            long v = (long) (x[j] - x[i]) * k * (Y - (long) y[i] * k) / dy + (long) x[i] * k;
            if (m == tmp.length) tmp = Arrays.copyOf(tmp, m * 2);
            tmp[m++] = (int) v;
        }
        if (m == 0) return;
        Arrays.sort(tmp, 0, m);
        for (int c = 0; c <= m; c++) {
            if (((m - c) & 1) == 0) continue;
            long from = c == 0 ? Long.MIN_VALUE : tmp[c - 1];
            long to = c == m ? Long.MAX_VALUE : tmp[c];
            int a = (int) Math.max(from, X0) - X0;
            int b = (int) Math.min(to, (long) X0 + wn) - X0;
            for (int q = a; q < b; q++) {
                t1[q] = true;
                if (t2 != null) t2[q] = true;
            }
        }
    }

    private void fillRow(int id, int k, int Y, int X0, int wn, boolean[] own, boolean[] lower, boolean[] any) {
        Arrays.fill(own, false);
        Arrays.fill(lower, false);
        Arrays.fill(any, false);
        row(id, k, Y, X0, wn, own, null);
        for (int n : neighbors[id]) {
            if (n < 0 || n >= px.length) continue;
            row(n, k, Y, X0, wn, any, n < id ? lower : null);
        }
    }

    private byte[] mask(int id, int k, int W, int H) {
        int OX = minX[id] * k, OY = minY[id] * k, wn = W + 2, X0 = OX - 1;
        boolean[][] own = new boolean[3][wn], L = new boolean[3][wn], A = new boolean[3][wn];
        var pix = new byte[W * H * 2];
        for (int Yr = -1; Yr <= 0; Yr++) {
            fillRow(id, k, OY + Yr, X0, wn, own[Yr + 1], L[Yr + 1], A[Yr + 1]);
        }
        for (int y = 0; y < H; y++) {
            int c = (y + 1) % 3, up = y % 3, dn = (y + 2) % 3;
            fillRow(id, k, OY + y + 1, X0, wn, own[dn], L[dn], A[dn]);
            for (int x = 0; x < W; x++) {
                int xi = x + 1;
                boolean draw;
                if (own[c][xi]) {
                    draw = !L[c][xi];
                } else if (own[c][xi + 1] || own[c][xi - 1] || own[up][xi] || own[dn][xi]) {
                    draw = !A[c][xi] && (L[c][xi + 1] || L[c][xi - 1] || L[up][xi] || L[dn][xi]);
                } else {
                    draw = false;
                }
                if (draw) {
                    int o = (y * W + x) * 2;
                    pix[o] = (byte) 0xFF;
                    pix[o + 1] = (byte) 0xFE;
                }
            }
        }
        return pix;
    }
}
