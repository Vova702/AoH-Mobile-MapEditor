package com.mygame.mapeditor;

/**
 * Which scale masks the editor writes on every save, and how to turn "province i of n at scale k"
 * into one smooth progress percentage. Pure Java on purpose, so it can be unit-tested.
 *
 * A mask at scale k has k*k times as many pixels as at scale 1, so the time it takes grows roughly
 * with k*k. Progress is therefore weighted by k*k: otherwise the bar would race through scale 1
 * and then crawl through scale 7.
 */
final class ScalePlan {

    private ScalePlan() {
    }

    /** The scale levels generated on every save. */
    static final int[] SCALES = {1, 3, 5, 7};

    /**
     * @param scales     scale levels in the order they are generated
     * @param scaleIndex index into {@code scales} of the scale being generated now
     * @param done       provinces already finished at that scale (0..total)
     * @param total      number of provinces
     * @param from       percent at the very start of scale generation
     * @param to         percent at the very end of scale generation
     * @return a percent in [from, to], never going backwards as the arguments advance
     */
    static int percent(int[] scales, int scaleIndex, int done, int total, int from, int to) {
        long all = 0;
        long before = 0;
        for (int i = 0; i < scales.length; i++) {
            long w = (long) scales[i] * scales[i];
            all += w;
            if (i < scaleIndex) before += w;
        }
        if (all == 0 || total <= 0 || scaleIndex < 0 || scaleIndex >= scales.length) return from;
        long k = scales[scaleIndex];
        int d = Math.max(0, Math.min(done, total));
        // weight finished so far, in units of 1/total of a province-at-scale-1
        long progress = before * total + k * k * d;
        return (int) (from + (long) (to - from) * progress / (all * total));
    }
}
