package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything the game stores for a province that is NOT its outline: terrain,
 * growth rate, region, label shifts, port data and the province's "connections"
 * (neighbor links as they were in the loaded map).
 *
 * The editor only ever edits outlines, but it used to throw all of this away
 * when a map was opened and then wrote provinces back with defaults - so
 * terrain types, growth rates and connections of existing provinces were lost
 * after any edit. These objects travel with the province through every edit,
 * delete / undo and reorder, and are written back on save / export.
 */
final class ProvinceExtra {
    int regionId;
    int shiftX;
    int shiftY;
    int portShiftX;
    int portShiftY;
    /** Raw levelOfPort of the loaded province (sea: -2/-3, land: -1 or 0). New provinces: -1. */
    int levelOfPort = ProvinceExportBuilder.LEVEL_LAND_NO_PORT;
    float growthRate;
    String terrainTag;
    /** Connections as loaded (ids in the CURRENT numbering): land list and sea list. */
    final List<Integer> links = new ArrayList<>();
    final List<Integer> seaLinks = new ArrayList<>();

    static ProvinceExtra fromDecoded(ProvinceDecoder.DecodedProvince d) {
        var e = new ProvinceExtra();
        e.regionId = d.regionId;
        e.shiftX = d.shiftX;
        e.shiftY = d.shiftY;
        e.portShiftX = d.portShiftX;
        e.portShiftY = d.portShiftY;
        e.levelOfPort = d.levelOfPort;
        e.growthRate = d.growthRate;
        e.terrainTag = d.terrainTag;
        if (d.neighboringProvinces != null) e.links.addAll(d.neighboringProvinces);
        if (d.neighboringSeaProvinces != null) e.seaLinks.addAll(d.neighboringSeaProvinces);
        return e;
    }

    ProvinceExtra copy() {
        var e = new ProvinceExtra();
        e.regionId = regionId;
        e.shiftX = shiftX;
        e.shiftY = shiftY;
        e.portShiftX = portShiftX;
        e.portShiftY = portShiftY;
        e.levelOfPort = levelOfPort;
        e.growthRate = growthRate;
        e.terrainTag = terrainTag;
        e.links.addAll(links);
        e.seaLinks.addAll(seaLinks);
        return e;
    }

    /** All connections, land first, without duplicates. */
    List<Integer> allLinks() {
        List<Integer> out = new ArrayList<>(links.size() + seaLinks.size());
        for (Integer n : links) if (n != null && !out.contains(n)) out.add(n);
        for (Integer n : seaLinks) if (n != null && !out.contains(n)) out.add(n);
        return out;
    }

    /** Renumbers link ids through {@code idMap} (old -> new); links to provinces missing from the map are dropped. */
    void remapLinks(Map<Integer, Integer> idMap) {
        remapList(links, idMap);
        remapList(seaLinks, idMap);
    }

    private static void remapList(List<Integer> list, Map<Integer, Integer> idMap) {
        List<Integer> out = new ArrayList<>(list.size());
        for (Integer old : list) {
            Integer mapped = old == null ? null : idMap.get(old);
            if (mapped != null && !out.contains(mapped)) out.add(mapped);
        }
        list.clear();
        list.addAll(out);
    }
}
