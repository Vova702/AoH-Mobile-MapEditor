package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cuts one province in two. One undo step:
 *
 * <ul>
 *   <li>the province keeps its id and gets the bigger part as its outline;</li>
 *   <li>the other part is added as a NEW province (last id) with the same sea flag, continent,
 *       terrain and region;</li>
 *   <li>neighbours whose border runs through the two cut end points get those points inserted into
 *       their outline (same shape), so the new border has identical vertices on both sides.</li>
 * </ul>
 */
public class SplitProvinceCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private final int provinceId;
    private final int newId;
    private final CommitProvinceCommand newPart;

    private final List<Integer> changedIds = new ArrayList<>();
    private final List<List<Integer>> oldX = new ArrayList<>();
    private final List<List<Integer>> oldY = new ArrayList<>();
    private final List<List<Integer>> newX = new ArrayList<>();
    private final List<List<Integer>> newY = new ArrayList<>();

    /**
     * @param keep    outline the existing province gets
     * @param added   outline of the new province
     * @param color   {r, g, b} of the new province
     * @param updated neighbour id -> its outline with the cut end points inserted
     */
    public SplitProvinceCommand(ProvinceData provinceData, int provinceId,
                                PolygonTrim.Poly keep, PolygonTrim.Poly added, int[] color,
                                Map<Integer, PolygonTrim.Poly> updated) {
        this.provinceData = provinceData;
        this.provinceId = provinceId;
        this.newId = provinceData.lProvincesPointsX.size();
        this.newPart = new CommitProvinceCommand(provinceData, newId, toList(added.x()), toList(added.y()),
                color[0], color[1], color[2]);
        remember(provinceId, keep);
        for (Map.Entry<Integer, PolygonTrim.Poly> e : updated.entrySet()) {
            if (e.getKey() != provinceId) remember(e.getKey(), e.getValue());
        }
    }

    public int newProvinceId() {
        return newId;
    }

    private void remember(int id, PolygonTrim.Poly outline) {
        changedIds.add(id);
        oldX.add(new ArrayList<>(provinceData.lProvincesPointsX.get(id)));
        oldY.add(new ArrayList<>(provinceData.lProvincesPointsY.get(id)));
        newX.add(toList(outline.x()));
        newY.add(toList(outline.y()));
    }

    @Override
    public void execute() {
        for (int i = 0; i < changedIds.size(); i++) apply(changedIds.get(i), newX.get(i), newY.get(i));
        newPart.execute();
        provinceData.inheritProvinceMeta(provinceId, newId);
    }

    @Override
    public void undo() {
        newPart.undo();
        for (int i = 0; i < changedIds.size(); i++) apply(changedIds.get(i), oldX.get(i), oldY.get(i));
    }

    private void apply(int id, List<Integer> xs, List<Integer> ys) {
        if (id < 0 || id >= provinceData.lProvincesPointsX.size()) return;
        provinceData.lProvincesPointsX.set(id, new ArrayList<>(xs));
        provinceData.lProvincesPointsY.set(id, new ArrayList<>(ys));
        provinceData.rebuildPath(id);
        provinceData.updateProvinceBounds(id);
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) l.add(v);
        return l;
    }
}
