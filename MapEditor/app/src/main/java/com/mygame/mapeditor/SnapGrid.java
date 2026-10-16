package com.mygame.mapeditor;

import android.content.Context;
import android.widget.Toast;

import java.util.List;

/**
 * Manages snap-to-grid and snap-to-nearest-vertex behavior for point placement.
 */
public class SnapGrid {

    boolean bSnapToGrid = false;
    int iGridSize = 10;
    boolean bSnapToPoints = true;
    static final int SNAP_POINT_RADIUS_PX = 21;

    private final Context context;
    private final Runnable invalidator;

    /** Result of a snap-to-nearest-vertex operation. */
    public record SnapResult(int x, int y, boolean snapped) {
    }

    public SnapGrid(Context context, Runnable invalidator) {
        this.context = context;
        this.invalidator = invalidator;
    }

    public void toggleSnapToGrid() {
        bSnapToGrid = !bSnapToGrid;
        String msg = bSnapToGrid
                ? context.getString(R.string.toast_snap_grid_on, iGridSize)
                : context.getString(R.string.toast_snap_grid_off);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        invalidator.run();
    }

    public void toggleSnapToPoints() {
        bSnapToPoints = !bSnapToPoints;
        String msg = bSnapToPoints
                ? context.getString(R.string.toast_snap_points_on, SNAP_POINT_RADIUS_PX)
                : context.getString(R.string.toast_snap_points_off);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
        invalidator.run();
    }

    /** Snaps a single coordinate to the nearest grid intersection. */
    public int snapCoord(int value) {
        if (!bSnapToGrid || iGridSize <= 1) return value;
        return Math.round((float) value / iGridSize) * iGridSize;
    }

    /**
     * Finds the nearest existing vertex (from in-progress or committed provinces)
     * within the snap radius. Returns a SnapResult with the snapped coordinates.
     */
    public SnapResult snapToNearestVertex(
            int srcX, int srcY,
            float mapScale,
            int mapWidth,
            List<Integer> currentPointsX, List<Integer> currentPointsY,
            List<List<Integer>> lProvincesPointsX, List<List<Integer>> lProvincesPointsY,
            List<Integer> lProvincesMinX, List<Integer> lProvincesMaxX,
            List<Integer> lProvincesMinY, List<Integer> lProvincesMaxY) {

        final float radiusMap = SNAP_POINT_RADIUS_PX / mapScale;
        final float radiusSq = radiusMap * radiusMap;

        int bestX = srcX;
        int bestY = srcY;
        float bestDistSq = radiusSq;
        boolean found = false;

        // 1) In-progress province points
        for (int j = 0; j < currentPointsX.size(); j++) {
            int x = currentPointsX.get(j);
            int y = currentPointsY.get(j);
            float dx = x - srcX;
            float dy = y - srcY;
            float d = dx * dx + dy * dy;
            if (d <= bestDistSq) {
                bestDistSq = d;
                bestX = x;
                bestY = y;
                found = true;
            }
        }

        // 2) Committed province points
        final int provinceCount = Math.min(lProvincesPointsX.size(), lProvincesPointsY.size());
        final int margin = (int) Math.ceil(radiusMap) + 1;

        for (int i = 0; i < provinceCount; i++) {
            if (i < lProvincesMinX.size() && i < lProvincesMaxX.size()
                    && i < lProvincesMinY.size() && i < lProvincesMaxY.size()) {
                boolean nearX = false;
                for (int shift : new int[]{0, mapWidth, -mapWidth}) {
                    int minX = lProvincesMinX.get(i) + shift - margin;
                    int maxX = lProvincesMaxX.get(i) + shift + margin;
                    if (srcX >= minX && srcX <= maxX) {
                        nearX = true;
                        break;
                    }
                }
                if (!nearX) continue;
                int minY = lProvincesMinY.get(i) - margin;
                int maxY = lProvincesMaxY.get(i) + margin;
                if (srcY < minY || srcY > maxY) continue;
            }

            List<Integer> px = lProvincesPointsX.get(i);
            List<Integer> py = lProvincesPointsY.get(i);
            int count = Math.min(px.size(), py.size());
            for (int j = 0; j < count; j++) {
                int baseX = px.get(j);
                int y = py.get(j);
                for (int shift : new int[]{0, mapWidth, -mapWidth}) {
                    int x = baseX + shift;
                    float dx = x - srcX;
                    float dy = y - srcY;
                    float d = dx * dx + dy * dy;
                    if (d <= bestDistSq) {
                        bestDistSq = d;
                        bestX = x;
                        bestY = y;
                        found = true;
                    }
                }
            }
        }

        if (found) {
            return new SnapResult(bestX, bestY, true);
        }
        return new SnapResult(srcX, srcY, false);
    }
}
