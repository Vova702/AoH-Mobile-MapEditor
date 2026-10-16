package com.mygame.mapeditor;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the full province point lists before and after a vertex edit,
 * so the change can be undone/redone even when the affected province is not
 * known at construction time (e.g. "insert vertex into edge" searches all
 * provinces for the nearest segment).
 */
public class EditProvinceCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private List<List<Integer>> pointsXbefore;
    private List<List<Integer>> pointsYbefore;
    private List<List<Integer>> pointsXafter;
    private List<List<Integer>> pointsYafter;
    private boolean captured = false;
    private boolean firstExecute = true;

    public EditProvinceCommand(ProvinceData provinceData) {
        this.provinceData = provinceData;
    }

    /** Snapshots the current state as the "before" state. Must be called before the edit. */
    public void captureBefore() {
        this.pointsXbefore = snapshotX();
        this.pointsYbefore = snapshotY();
        this.captured = false;
    }

    /** Snapshots the current state as the "after" state. Must be called after the edit. */
    public void captureAfter() {
        this.pointsXafter = snapshotX();
        this.pointsYafter = snapshotY();
        if (!captured && pointsXbefore == null) {
            pointsXbefore = snapshotX();
            pointsYbefore = snapshotY();
        }
        captured = true;
    }

    @Override
    public void execute() {
        if (firstExecute) {
            firstExecute = false;
            return;
        }
        if (!captured || pointsXafter == null) return;
        restore(pointsXafter, pointsYafter);
    }

    @Override
    public void undo() {
        if (!captured || pointsXbefore == null) return;
        restore(pointsXbefore, pointsYbefore);
    }

    private void restore(List<List<Integer>> xs, List<List<Integer>> ys) {
        for (int i = 0; i < xs.size(); i++) {
            provinceData.lProvincesPointsX.set(i, new ArrayList<>(xs.get(i)));
            provinceData.lProvincesPointsY.set(i, new ArrayList<>(ys.get(i)));
            provinceData.rebuildPath(i);
            provinceData.updateProvinceBounds(i);
        }
    }

    private List<List<Integer>> snapshotX() {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> list : provinceData.lProvincesPointsX) {
            out.add(new ArrayList<>(list));
        }
        return out;
    }

    private List<List<Integer>> snapshotY() {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> list : provinceData.lProvincesPointsY) {
            out.add(new ArrayList<>(list));
        }
        return out;
    }
}
