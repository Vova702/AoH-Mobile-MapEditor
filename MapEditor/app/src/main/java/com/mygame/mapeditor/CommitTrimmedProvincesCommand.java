package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds a newly drawn province whose overlap with existing provinces was cut away. One undo step:
 *
 * <ul>
 *   <li>the new province is added - or several, when the free area was split into separate pieces;</li>
 *   <li>the existing provinces that were cut get the border points inserted into their outline
 *       (same shape; this makes the shared border use identical vertices on both sides).</li>
 * </ul>
 */
public class CommitTrimmedProvincesCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private final List<CommitProvinceCommand> parts = new ArrayList<>();

    private final List<Integer> changedIds = new ArrayList<>();
    private final List<List<Integer>> oldX = new ArrayList<>();
    private final List<List<Integer>> oldY = new ArrayList<>();
    private final List<List<Integer>> newX = new ArrayList<>();
    private final List<List<Integer>> newY = new ArrayList<>();

    /**
     * @param firstIndex index the first new province gets (= current province count)
     * @param newParts   outlines of the new province(s)
     * @param colors     one {r, g, b} per new part
     * @param updated    existing province id -> its outline with the border points inserted
     */
    public CommitTrimmedProvincesCommand(ProvinceData provinceData, int firstIndex,
                                         List<PolygonTrim.Poly> newParts, List<int[]> colors,
                                         Map<Integer, PolygonTrim.Poly> updated) {
        this.provinceData = provinceData;
        for (int i = 0; i < newParts.size(); i++) {
            PolygonTrim.Poly p = newParts.get(i);
            int[] c = colors.get(i);
            parts.add(new CommitProvinceCommand(provinceData, firstIndex + i,
                    toList(p.x()), toList(p.y()), c[0], c[1], c[2]));
        }
        for (Map.Entry<Integer, PolygonTrim.Poly> e : updated.entrySet()) {
            int id = e.getKey();
            changedIds.add(id);
            oldX.add(new ArrayList<>(provinceData.lProvincesPointsX.get(id)));
            oldY.add(new ArrayList<>(provinceData.lProvincesPointsY.get(id)));
            newX.add(toList(e.getValue().x()));
            newY.add(toList(e.getValue().y()));
        }
    }

    @Override
    public void execute() {
        for (int i = 0; i < changedIds.size(); i++) apply(changedIds.get(i), newX.get(i), newY.get(i));
        for (CommitProvinceCommand p : parts) p.execute();
    }

    @Override
    public void undo() {
        for (int i = parts.size() - 1; i >= 0; i--) parts.get(i).undo();
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
