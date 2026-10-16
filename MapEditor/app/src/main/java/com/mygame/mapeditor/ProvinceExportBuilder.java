package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds fully populated {@link ProvinceDecoder.DecodedProvince} objects
 * (outline + neighbors + shared borders + sea/land + continent + everything the
 * loaded map carried for the province) from the editor's state.
 *
 * Before this class every save / export wrote ONLY the outline, so exported
 * maps had empty neighbor lists, no borders, no sea provinces, and - after any
 * edit - lost the terrain type, growth rate, region, shifts and port data that
 * the loaded provinces had.
 *
 * All input lists are in OUTPUT order (index i = the id the province will have
 * in the exported map), so province ids inside neighbor / border data already
 * refer to exported ids.
 */
final class ProvinceExportBuilder {

    private ProvinceExportBuilder() {
    }

    static final int LEVEL_SEA = -2;
    static final int LEVEL_LAND_NO_PORT = -1;
    static final int LEVEL_LAND_PORT = 0;

    /**
     * @param xs, ys            outlines, output order
     * @param sea               sea flag per province (missing entries = land)
     * @param continent         continent id per province (missing entries = 0)
     * @param extras            preserved per-province data, output order (entries / the list may be missing = defaults);
     *                          its link ids must already be in output numbering
     * @param backgroundWidth   map width for horizontal wrap-around, 0 = off
     * @param minSharedVertices 2 = neighbors must share a segment (recommended), 1 = corner contact counts
     * @param keepOldConnections also keep the connections the loaded map had, even where the outlines no
     *                          longer touch (links are made symmetric); false = neighbors from outlines only
     */
    static List<ProvinceDecoder.DecodedProvince> build(
            List<List<Integer>> xs, List<List<Integer>> ys,
            List<Boolean> sea, List<Integer> continent,
            List<ProvinceExtra> extras,
            int backgroundWidth, int minSharedVertices, boolean keepOldConnections) {

        final int count = xs.size();
        NeighborComputer.Result nb = NeighborComputer.computeAll(xs, ys, backgroundWidth, true, minSharedVertices);

        List<Boolean> seaFull = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            seaFull.add(i < sea.size() && Boolean.TRUE.equals(sea.get(i)));
        }

        // geometry neighbors, optionally united with the old connections (made symmetric)
        List<LinkedHashSet<Integer>> all = new ArrayList<>(count);
        for (int i = 0; i < count; i++) all.add(new LinkedHashSet<>(nb.neighbors().get(i)));
        if (keepOldConnections && extras != null) {
            for (int i = 0; i < count && i < extras.size(); i++) {
                ProvinceExtra e = extras.get(i);
                if (e == null) continue;
                for (Integer other : e.allLinks()) {
                    if (other == null || other < 0 || other >= count || other == i) continue;
                    all.get(i).add(other);
                    all.get(other).add(i);
                }
            }
        }

        List<ProvinceDecoder.DecodedProvince> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ProvinceExtra old = (extras != null && i < extras.size()) ? extras.get(i) : null;
            var d = new ProvinceDecoder.DecodedProvince();

            d.pointsX = toShorts(xs.get(i));
            d.pointsY = toShorts(ys.get(i));

            boolean selfSea = seaFull.get(i);
            NeighborComputer.split(selfSea, new ArrayList<>(all.get(i)), seaFull, d.neighboringProvinces, d.neighboringSeaProvinces);

            if (selfSea) {
                d.levelOfPort = (old != null && old.levelOfPort < LEVEL_SEA) ? old.levelOfPort : LEVEL_SEA;
            } else {
                d.levelOfPort = d.neighboringSeaProvinces.isEmpty() ? LEVEL_LAND_NO_PORT : LEVEL_LAND_PORT;
            }

            d.continentId = i < continent.size() && continent.get(i) != null ? continent.get(i) : 0;

            if (old != null) {
                d.regionId = old.regionId;
                d.shiftX = old.shiftX;
                d.shiftY = old.shiftY;
                d.growthRate = old.growthRate;
                d.terrainTag = old.terrainTag;
                d.portShiftX = old.portShiftX;
                d.portShiftY = old.portShiftY;
            }

            for (NeighborComputer.Border b : nb.borders().get(i)) {
                var db = new ProvinceDecoder.DecodedBorder();
                db.withProvinceId = b.withProvince();
                db.pointsX = b.xs();
                db.pointsY = b.ys();
                d.borders.add(db);
            }
            out.add(d);
        }
        return out;
    }

    private static short[] toShorts(List<Integer> list) {
        var a = new short[list.size()];
        for (int i = 0; i < a.length; i++) {
            int v = list.get(i);
            a[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
        return a;
    }
}
