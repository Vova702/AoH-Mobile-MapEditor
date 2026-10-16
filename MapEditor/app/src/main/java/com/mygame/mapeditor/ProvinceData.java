package com.mygame.mapeditor;

import android.graphics.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;

/**
 * All province vertex/path data, bounds, colors, neighboring info,
 * and editing operations. Pure data container — no Android UI dependencies.
 */
public class ProvinceData {

    final EditorState editor;
    final UndoRedoManager undoRedo;
    private final Runnable invalidator;

    // --- Core province shapes ---
    Vector<Path> vProvinces = new Vector<>();

    // --- Per-province coordinate lists ---
    List<List<Integer>> lProvincesPointsX = new ArrayList<>();
    List<List<Integer>> lProvincesPointsY = new ArrayList<>();
    List<Integer> lProvincesCenterX = new ArrayList<>();
    List<Integer> lProvincesCenterY = new ArrayList<>();
    List<Integer> lProvincesMinX = new ArrayList<>();
    List<Integer> lProvincesMaxX = new ArrayList<>();
    List<Integer> lProvincesMinY = new ArrayList<>();
    List<Integer> lProvincesMaxY = new ArrayList<>();

    // --- Current (in-progress) province ---
    Path currentProvince = null;
    int currentProvincePoints = 0;
    List<Integer> currentPointsX = new ArrayList<>();
    List<Integer> currentPointsY = new ArrayList<>();
    int iCurrentProvinceMinX;
    int iCurrentProvinceMaxX;
    int iCurrentProvinceMinY;
    int iCurrentProvinceMaxY;
    int iCurrentProvinceShiftArmyX = 0;
    int iCurrentProvinceShiftArmyY = 0;

    // --- Province colors ---
    List<Integer> pathR = new ArrayList<>();
    List<Integer> pathG = new ArrayList<>();
    List<Integer> pathB = new ArrayList<>();
    int iLandProvinceAlpha = 120;
    int iSeaProvinceAlpha = 80;

    // --- Neighboring provinces ---
    List<List<Integer>> neighboring = new ArrayList<>();
    List<List<Integer>> lNeighboringProvinces = new ArrayList<>();
    Thread _NeighboringProvincesThread = null;
    boolean neighboringProvincesReadyToUse = false;

    // --- Province ordering ---
    List<Boolean> lNewOrderOfProvinces_wasChosen = new ArrayList<>();
    List<Integer> lNewOrderOfProvinces_IDs = new ArrayList<>();
    List<Integer> lNewOrderOfPorivnces_IDs = new ArrayList<>();

    /**
     * Per-province game data that is not outline (terrain, growth rate, region, shifts, port
     * data, old connections). Parallel to {@link #vProvinces}; see {@link ProvinceExtra}.
     */
    public final List<ProvinceExtra> lExtras = new ArrayList<>();

    // --- AutoPath state ---
    boolean foundFirst = false;
    boolean forward = false;

    // --- Input coordinates (set by touch handling) ---
    int iLastPressedPosX = 0;
    int iLastPressedPosY = 0;

    static final int iPadding = 5;

    public ProvinceData(EditorState editor, UndoRedoManager undoRedo, Runnable invalidator) {
        this.editor = editor;
        this.undoRedo = undoRedo;
        this.invalidator = invalidator;
    }

    // ==================================================================
    // Pure data methods
    // ==================================================================

    public void rebuildPath(int id) {
        var oP = new Path();
        int iSize = lProvincesPointsX.get(id).size();
        for (int i = 0; i < iSize; i++) {
            if (i == 0) {
                oP.moveTo(lProvincesPointsX.get(id).get(i), lProvincesPointsY.get(id).get(i));
            } else {
                oP.lineTo(lProvincesPointsX.get(id).get(i), lProvincesPointsY.get(id).get(i));
            }
        }
        vProvinces.set(id, oP);
    }

    public void rebuildCurrentProvince() {
        int iSize = currentPointsX.size();
        for (int i = 0; i < iSize; i++) {
            if (i == 0) {
                currentProvince = new Path();
                currentProvince.moveTo(currentPointsX.get(i), currentPointsY.get(i));
            } else {
                currentProvince.lineTo(currentPointsX.get(i), currentPointsY.get(i));
            }
        }
        updateCurrentProvinceCenterPoint();
    }

    private void updateCurrentProvinceCenterPoint() {
        if (!currentPointsX.isEmpty()) {
            int v = currentPointsX.get(0);
            iCurrentProvinceMinX = v;
            iCurrentProvinceMaxX = v;
            int v2 = currentPointsY.get(0);
            iCurrentProvinceMinY = v2;
            iCurrentProvinceMaxY = v2;
            for (int i = 1; i < currentPointsX.size(); i++) {
                if (iCurrentProvinceMaxX < currentPointsX.get(i)) iCurrentProvinceMaxX = currentPointsX.get(i);
                if (iCurrentProvinceMinX > currentPointsX.get(i)) iCurrentProvinceMinX = currentPointsX.get(i);
                if (iCurrentProvinceMaxY < currentPointsY.get(i)) iCurrentProvinceMaxY = currentPointsY.get(i);
                if (iCurrentProvinceMinY > currentPointsY.get(i)) iCurrentProvinceMinY = currentPointsY.get(i);
            }
        }
    }

    public void updateProvinceBounds(int id) {
        if (id < 0 || id >= lProvincesPointsX.size() || lProvincesPointsX.get(id).isEmpty()) return;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        List<Integer> xs = lProvincesPointsX.get(id);
        List<Integer> ys = lProvincesPointsY.get(id);
        int n = Math.min(xs.size(), ys.size());
        for (int j = 0; j < n; j++) {
            int x = xs.get(j), y = ys.get(j);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        lProvincesMinX.set(id, minX);
        lProvincesMaxX.set(id, maxX);
        lProvincesMinY.set(id, minY);
        lProvincesMaxY.set(id, maxY);
        lProvincesCenterX.set(id, (minX + maxX) / 2);
        lProvincesCenterY.set(id, (minY + maxY) / 2);
    }

    public void recomputeProvinceBounds(CameraState camera) {
        for (int i = 0; i < vProvinces.size(); i++) {
            int minX = camera.iMapEditorBackgroundWidth;
            int maxX = -camera.iMapEditorBackgroundWidth;
            int minY = camera.iMapEditorBackgroundHeight;
            int maxY = -camera.iMapEditorBackgroundHeight;

            for (int j = 0; j < lProvincesPointsX.get(i).size(); j++) {
                int x = lProvincesPointsX.get(i).get(j);
                int y = lProvincesPointsY.get(i).get(j);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }

            if (lProvincesMinX.size() <= i) {
                lProvincesMinX.add(minX);
                lProvincesMaxX.add(maxX);
                lProvincesMinY.add(minY);
                lProvincesMaxY.add(maxY);
                lProvincesCenterX.add((minX + maxX) / 2);
                lProvincesCenterY.add((minY + maxY) / 2);
                lNewOrderOfProvinces_wasChosen.add(false);
            } else {
                lProvincesMinX.set(i, minX);
                lProvincesMaxX.set(i, maxX);
                lProvincesMinY.set(i, minY);
                lProvincesMaxY.set(i, maxY);
                lProvincesCenterX.set(i, (minX + maxX) / 2);
                lProvincesCenterY.set(i, (minY + maxY) / 2);
            }
        }
    }

    public boolean pathContains(int nX, int nY, int nProvinceID) {
        if (nProvinceID < 0 || nProvinceID >= lProvincesMinX.size()) return false;
        if (nX < lProvincesMinX.get(nProvinceID) || nX > lProvincesMaxX.get(nProvinceID)
                || nY < lProvincesMinY.get(nProvinceID) || nY > lProvincesMaxY.get(nProvinceID)) {
            return false;
        }

        boolean output = false;
        int i = 0;
        int j = lProvincesPointsX.get(nProvinceID).size() - 1;
        int iSize = lProvincesPointsX.get(nProvinceID).size();
        while (i < iSize) {
            int yi = lProvincesPointsY.get(nProvinceID).get(i);
            int yj = lProvincesPointsY.get(nProvinceID).get(j);
            int xi = lProvincesPointsX.get(nProvinceID).get(i);
            int xj = lProvincesPointsX.get(nProvinceID).get(j);
            if ((yi > nY) != (yj > nY) && nX < ((xj - xi) * (nY - yi)) / (yj - yi) + xi) {
                output = !output;
            }
            j = i;
            i++;
        }
        return output;
    }

    public boolean hasSelfIntersection(List<Integer> px, List<Integer> py) {
        int n = px.size();
        for (int i = 0; i < n; i++) {
            int x1 = px.get(i), y1 = py.get(i);
            int x2 = px.get((i + 1) % n), y2 = py.get((i + 1) % n);
            for (int j = i + 2; j < n; j++) {
                if (j == n - 1 && i == 0) continue;
                int x3 = px.get(j), y3 = py.get(j);
                int x4 = px.get((j + 1) % n), y4 = py.get((j + 1) % n);
                if (linesIntersect(x1, y1, x2, y2, x3, y3, x4, y4)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean linesIntersect(int x1, int y1, int x2, int y2,
                                   int x3, int y3, int x4, int y4) {
        double ccw1 = (y3 - y1) * (x2 - x1) - (x3 - x1) * (y2 - y1);
        double ccw2 = (y4 - y1) * (x2 - x1) - (x4 - x1) * (y2 - y1);
        double ccw3 = (y1 - y3) * (x4 - x3) - (x1 - x3) * (y4 - y3);
        double ccw4 = (y2 - y3) * (x4 - x3) - (x2 - x3) * (y4 - y3);
        return ((ccw1 * ccw2) < 0) && ((ccw3 * ccw4) < 0);
    }

    public void buildProvinceColors() {
        var oR = new Random();
        pathR.clear();
        pathG.clear();
        pathB.clear();
        for (int i = 0; i < vProvinces.size(); i++) {
            pathR.add(oR.nextInt(255));
            pathG.add(oR.nextInt(255));
            pathB.add(oR.nextInt(255));
        }
    }

    public boolean pointUsedElsewhere(int i, int startID) {
        int nSize = lProvincesPointsX.size();
        for (int n = 0; n < nSize; n++) {
            if (i != n) {
                int oSize = lProvincesPointsX.get(n).size();
                for (int o = 0; o < oSize; o++) {
                    if (Objects.equals(lProvincesPointsX.get(n).get(o), lProvincesPointsX.get(i).get(startID))
                            && Objects.equals(lProvincesPointsY.get(n).get(o), lProvincesPointsY.get(i).get(startID))) {
                        return true;
                    }
                }
            }
        }
        if (!currentPointsX.isEmpty()) {
            int lastIdx = currentPointsX.size() - 1;
            if (Objects.equals(currentPointsX.get(lastIdx), lProvincesPointsX.get(i).get(startID))
                    && Objects.equals(currentPointsY.get(lastIdx), lProvincesPointsY.get(i).get(startID))) {
                return true;
            }
        }
        return false;
    }

    public String getNeighboring(int i) {
        var output = new StringBuilder();
        for (int j = 0; j < neighboring.get(i).size(); j++) {
            boolean add = true;
            for (int k = j + 1; k < neighboring.get(i).size(); k++) {
                if (neighboring.get(i).get(k).intValue() == neighboring.get(i).get(j).intValue()) {
                    add = false;
                }
            }
            int c = neighboring.get(i).get(j);
            if (c > editor.iEndOfLandProvinces && i < editor.iEndOfLandProvinces + 1) {
                add = false;
            }
            if (add) {
                output.append(neighboring.get(i).get(j)).append(", ");
            }
        }
        return output.toString();
    }

    public boolean seaAcces(int i) {
        for (int j = 0; j < neighboring.get(i).size(); j++) {
            for (int k = 0; k < j; k++) {
                if (neighboring.get(i).get(j) > editor.iEndOfLandProvinces) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================================================================
    // Province loading helpers (called from GamePanel load methods)
    // ==================================================================

    public void addProvinceFromDecoded(ProvinceDecoder.DecodedProvince dec) {
        List<Integer> tempPointsX = ProvinceDecoder.toIntList(dec.pointsX);
        List<Integer> tempPointsY = ProvinceDecoder.toIntList(dec.pointsY);

        if (tempPointsX.size() < 2) return;

        var nPath = new Path();
        nPath.moveTo(tempPointsX.get(0), tempPointsY.get(0));
        for (int i = 1; i < tempPointsX.size(); i++) {
            nPath.lineTo(tempPointsX.get(i), tempPointsY.get(i));
        }
        nPath.close();

        int newId = vProvinces.size();
        vProvinces.add(nPath);
        lProvincesPointsX.add(tempPointsX);
        lProvincesPointsY.add(tempPointsY);

        // keep everything that is not outline, so it survives editing and is written back on save
        while (lExtras.size() < newId) lExtras.add(new ProvinceExtra());
        lExtras.add(ProvinceExtra.fromDecoded(dec));
    }

    public void addProvinceFromLines(List<String> lines) {
        String xLine;
        String yLine;

        if (lines.size() == 1 && lines.get(0).contains(";")) {
            String[] parts = lines.get(0).split(";", 2);
            if (parts.length < 2) return;
            xLine = parts[0];
            yLine = parts[1];
        } else {
            if (lines.size() < 2) return;
            xLine = lines.get(0);
            yLine = lines.get(1);
        }

        String[] sX = xLine.split(",");
        String[] sY = yLine.split(",");
        int count = Math.min(sX.length, sY.length);

        List<Integer> tempPointsX = new ArrayList<>(count);
        List<Integer> tempPointsY = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            try {
                tempPointsX.add(Integer.parseInt(sX[i].trim()));
                tempPointsY.add(Integer.parseInt(sY[i].trim()));
            } catch (NumberFormatException ignored) {
            }
        }

        if (tempPointsX.size() < 2) return;

        var nPath = new Path();
        nPath.moveTo(tempPointsX.get(0), tempPointsY.get(0));
        for (int i = 1; i < tempPointsX.size(); i++) {
            nPath.lineTo(tempPointsX.get(i), tempPointsY.get(i));
        }
        nPath.close();

        vProvinces.add(nPath);
        lProvincesPointsX.add(tempPointsX);
        lProvincesPointsY.add(tempPointsY);
    }

    // ==================================================================
    // Editing operations
    // ==================================================================

    public void addPointToCurrentProvince(int nX, int nY, boolean lastTapSnappedToVertex, SnapGrid snapGrid) {
        int snappedX = lastTapSnappedToVertex ? nX : snapGrid.snapCoord(nX);
        int snappedY = lastTapSnappedToVertex ? nY : snapGrid.snapCoord(nY);

        currentPointsX.add(snappedX);
        currentPointsY.add(snappedY);

        if (currentProvincePoints == 0) {
            currentProvince = new Path();
            currentProvince.moveTo(snappedX, snappedY);
            iCurrentProvinceMinX = snappedX;
            iCurrentProvinceMaxX = snappedX;
            iCurrentProvinceMinY = snappedY;
            iCurrentProvinceMaxY = snappedY;
        } else {
            currentProvince.lineTo(snappedX, snappedY);
            if (iCurrentProvinceMaxX < snappedX) iCurrentProvinceMaxX = snappedX;
            if (iCurrentProvinceMinX > snappedX) iCurrentProvinceMinX = snappedX;
            if (iCurrentProvinceMaxY < snappedY) iCurrentProvinceMaxY = snappedY;
            if (iCurrentProvinceMinY > snappedY) iCurrentProvinceMinY = snappedY;
        }
        currentProvincePoints++;
    }

    public void startNewProvince(SnapGrid snapGrid) {
        if (currentProvincePoints > 1 && currentPointsX.size() > 1) {
            var nPath = new Path();
            nPath.moveTo(currentPointsX.get(0), currentPointsY.get(0));
            for (int i = 1; i < currentPointsX.size(); i++) {
                nPath.lineTo(currentPointsX.get(i), currentPointsY.get(i));
            }
            nPath.close();
            vProvinces.add(nPath);
            lProvincesPointsX.add(new ArrayList<>(currentPointsX));
            lProvincesPointsY.add(new ArrayList<>(currentPointsY));
            while (lExtras.size() < vProvinces.size()) lExtras.add(new ProvinceExtra());

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int i = 0; i < currentPointsX.size(); i++) {
                int x = currentPointsX.get(i), y = currentPointsY.get(i);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
            lProvincesMinX.add(minX);
            lProvincesMaxX.add(maxX);
            lProvincesMinY.add(minY);
            lProvincesMaxY.add(maxY);
            lProvincesCenterX.add((minX + maxX) / 2);
            lProvincesCenterY.add((minY + maxY) / 2);
            lNewOrderOfProvinces_wasChosen.add(false);

            var r = new Random();
            pathR.add(r.nextInt(255));
            pathG.add(r.nextInt(255));
            pathB.add(r.nextInt(255));
        }

        editor.resetModes();
        currentPointsX.clear();
        currentPointsY.clear();
        currentProvincePoints = 0;
        currentProvince = null;
        editor.iActiveProvinceID = -1;
        editor.iChangePositionX = 0;
        editor.iChangePositionY = 0;
        editor.bSelectedPointsToChangePosition = false;
    }

    /** Ends the drawing in progress WITHOUT adding it as a province (the caller adds it, e.g. trimmed). */
    public void discardCurrentDrawing() {
        editor.resetModes();
        currentPointsX.clear();
        currentPointsY.clear();
        currentProvincePoints = 0;
        currentProvince = null;
        editor.iActiveProvinceID = -1;
        editor.iChangePositionX = 0;
        editor.iChangePositionY = 0;
        editor.bSelectedPointsToChangePosition = false;
    }

    public boolean deleteActiveProvince() {
        if (editor.iActiveProvinceID < 0 || editor.iActiveProvinceID >= vProvinces.size()) {
            return false;
        }
        final int id = editor.iActiveProvinceID;
        ProvinceMeta.remove(lExtras, editor.lSea, editor.lContinent, lNewOrderOfPorivnces_IDs, vProvinces.size(), id);
        vProvinces.remove(id);
        lProvincesPointsX.remove(id);
        lProvincesPointsY.remove(id);
        lProvincesCenterX.remove(id);
        lProvincesCenterY.remove(id);
        lProvincesMinX.remove(id);
        lProvincesMinY.remove(id);
        lProvincesMaxX.remove(id);
        lProvincesMaxY.remove(id);
        if (id < pathR.size()) pathR.remove(id);
        if (id < pathG.size()) pathG.remove(id);
        if (id < pathB.size()) pathB.remove(id);
        if (id < lNewOrderOfProvinces_wasChosen.size()) lNewOrderOfProvinces_wasChosen.remove(id);
        editor.iActiveProvinceID = -1;
        return true;
    }

    public void undoLastPoint(boolean lastTapSnappedToVertex) {
        if (editor.bNewOrderOfProvinces) {
            if (!lNewOrderOfPorivnces_IDs.isEmpty()) {
                int lastId = lNewOrderOfPorivnces_IDs.get(lNewOrderOfPorivnces_IDs.size() - 1);
                lNewOrderOfProvinces_wasChosen.set(lastId, false);
                lNewOrderOfPorivnces_IDs.remove(lNewOrderOfPorivnces_IDs.size() - 1);
            }
        } else if (!currentPointsX.isEmpty()) {
            currentPointsX.remove(currentPointsX.size() - 1);
            currentPointsY.remove(currentPointsY.size() - 1);
            if (!currentPointsX.isEmpty()) {
                iLastPressedPosX = currentPointsX.get(currentPointsX.size() - 1);
                iLastPressedPosY = currentPointsY.get(currentPointsY.size() - 1);
            }
            currentProvincePoints--;
            rebuildCurrentProvince();
        }
    }

    public void removePoint() {
        for (int i = 0; i < vProvinces.size(); i++) {
            if (iLastPressedPosX < lProvincesMinX.get(i) || iLastPressedPosX > lProvincesMaxX.get(i)
                    || iLastPressedPosY < lProvincesMinY.get(i) || iLastPressedPosY > lProvincesMaxY.get(i)) continue;
            for (int j = 0; j < lProvincesPointsX.get(i).size(); j++) {
                if (lProvincesPointsX.get(i).get(j) == iLastPressedPosX && lProvincesPointsY.get(i).get(j) == iLastPressedPosY) {
                    List<Integer> tempX = new ArrayList<>();
                    List<Integer> tempY = new ArrayList<>();
                    for (int a = 0; a < lProvincesPointsX.get(i).size(); a++) {
                        if (a != j) {
                            tempX.add(lProvincesPointsX.get(i).get(a));
                            tempY.add(lProvincesPointsY.get(i).get(a));
                        }
                    }
                    lProvincesPointsX.set(i, tempX);
                    lProvincesPointsY.set(i, tempY);
                    rebuildPath(i);
                }
            }
        }
    }

    public boolean addPointToProvince() {
        final int px = iLastPressedPosX;
        final int py = iLastPressedPosY;

        // If the tap landed exactly on an existing vertex, there is nothing new to insert.
        int existing = findVertexIndex(px, py);
        if (existing >= 0) return false;

        int bestProvince = -1;
        int bestIdx = -1;
        long bestDist = Long.MAX_VALUE;

        int n = lProvincesPointsX.size();
        for (int i = 0; i < n; i++) {
            List<Integer> xs = lProvincesPointsX.get(i);
            List<Integer> ys = lProvincesPointsY.get(i);
            int m = Math.min(xs.size(), ys.size());
            if (m < 3) continue;

            for (int j = 0; j < m; j++) {
                int j2 = (j + 1) % m;
                long d = distToSegmentSq(px, py,
                        xs.get(j), ys.get(j), xs.get(j2), ys.get(j2));
                if (d < bestDist) {
                    bestDist = d;
                    bestProvince = i;
                    bestIdx = j2;
                }
            }
        }

        if (bestProvince < 0) return false;

        List<Integer> xs = new ArrayList<>(lProvincesPointsX.get(bestProvince));
        List<Integer> ys = new ArrayList<>(lProvincesPointsY.get(bestProvince));
        xs.add(bestIdx, px);
        ys.add(bestIdx, py);
        lProvincesPointsX.set(bestProvince, xs);
        lProvincesPointsY.set(bestProvince, ys);
        rebuildPath(bestProvince);
        updateProvinceBounds(bestProvince);
        return true;
    }

    private int findVertexIndex(int px, int py) {
        for (int i = 0; i < lProvincesPointsX.size(); i++) {
            List<Integer> xs = lProvincesPointsX.get(i);
            List<Integer> ys = lProvincesPointsY.get(i);
            for (int j = 0; j < xs.size(); j++) {
                if (xs.get(j) == px && ys.get(j) == py) return i;
            }
        }
        return -1;
    }

    private long distToSegmentSq(long px, long py, long x1, long y1, long x2, long y2) {
        long dx = x2 - x1;
        long dy = y2 - y1;
        long lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {
            long vx = px - x1, vy = py - y1;
            return vx * vx + vy * vy;
        }
        long t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        long cx = x1 + t * dx;
        long cy = y1 + t * dy;
        long vx = px - cx, vy = py - cy;
        return vx * vx + vy * vy;
    }

    public void changePointPosition(boolean lastTapSnappedToVertex, SnapGrid snapGrid) {
        if (editor.bSelectedPointsToChangePosition) {
            int snappedX = lastTapSnappedToVertex ? iLastPressedPosX : snapGrid.snapCoord(iLastPressedPosX);
            int snappedY = lastTapSnappedToVertex ? iLastPressedPosY : snapGrid.snapCoord(iLastPressedPosY);

            for (int i = 0; i < vProvinces.size(); i++) {
                for (int j = 0; j < lProvincesPointsX.get(i).size(); j++) {
                    if (lProvincesPointsX.get(i).get(j) == editor.iChangePositionX && lProvincesPointsY.get(i).get(j) == editor.iChangePositionY) {
                        lProvincesPointsX.get(i).set(j, snappedX);
                        lProvincesPointsY.get(i).set(j, snappedY);
                        rebuildPath(i);
                        updateProvinceBounds(i);
                    }
                }
            }
            editor.bSelectedPointsToChangePosition = false;
            return;
        }

        editor.iChangePositionX = iLastPressedPosX;
        editor.iChangePositionY = iLastPressedPosY;
        for (int i = 0; i < vProvinces.size(); i++) {
            if (editor.iChangePositionX < lProvincesMinX.get(i) || editor.iChangePositionX > lProvincesMaxX.get(i)
                    || editor.iChangePositionY < lProvincesMinY.get(i) || editor.iChangePositionY > lProvincesMaxY.get(i)) continue;
            for (int j = 0; j < lProvincesPointsX.get(i).size(); j++) {
                if (lProvincesPointsX.get(i).get(j) == editor.iChangePositionX && lProvincesPointsY.get(i).get(j) == editor.iChangePositionY) {
                    editor.bSelectedPointsToChangePosition = true;
                    return;
                }
            }
        }
    }

    public void setProvinceIDNewOrder() {
        for (int i = 0; i < vProvinces.size(); i++) {
            if (pathContains(iLastPressedPosX, iLastPressedPosY, i) && !lNewOrderOfProvinces_wasChosen.get(i)) {
                lNewOrderOfProvinces_wasChosen.set(i, true);
                lNewOrderOfPorivnces_IDs.add(i);
            }
        }
    }

    public boolean setProvinceID() {
        for (int i = 0; i < vProvinces.size(); i++) {
            if (pathContains(iLastPressedPosX, iLastPressedPosY, i)) {
                editor.iActiveProvinceID = i;
                return true;
            }
        }
        return false;
    }

    public void autoPath(SnapGrid snapGrid) {
        int startID = 0;
        int i = 0;
        int iSize = lProvincesPointsX.size();
        while (i < iSize) {
            boolean found = false;
            int k = 0;
            int kSize = lProvincesPointsX.get(i).size();
            while (k < kSize) {
                if (!Objects.equals(currentPointsX.get(currentPointsX.size() - 1), lProvincesPointsX.get(i).get(k))
                        || !Objects.equals(currentPointsY.get(currentPointsY.size() - 1), lProvincesPointsY.get(i).get(k))) {
                    k++;
                } else if (k != 0
                        && Objects.equals(currentPointsX.get(currentPointsX.size() - 2), lProvincesPointsX.get(i).get(k - 1))
                        && Objects.equals(currentPointsY.get(currentPointsY.size() - 2), lProvincesPointsY.get(i).get(k - 1))) {
                    found = true;
                    forward = true;
                    startID = k + 1;
                    break;
                } else if (k + 1 < kSize
                        && Objects.equals(currentPointsX.get(currentPointsX.size() - 2), lProvincesPointsX.get(i).get(k + 1))
                        && Objects.equals(currentPointsY.get(currentPointsY.size() - 2), lProvincesPointsY.get(i).get(k + 1))) {
                    forward = false;
                    found = true;
                    startID = k - 1;
                    break;
                } else {
                    k++;
                }
            }
            if (!found) {
                i++;
                continue;
            }
            if (forward) {
                int kSize2 = lProvincesPointsX.get(i).size();
                while (startID < kSize2) {
                    if (pointUsedElsewhere(i, startID)) return;
                    addPointToCurrentProvinceDirect(lProvincesPointsX.get(i).get(startID), lProvincesPointsY.get(i).get(startID));
                    startID++;
                }
            } else {
                while (startID > 0) {
                    if (pointUsedElsewhere(i, startID)) return;
                    addPointToCurrentProvinceDirect(lProvincesPointsX.get(i).get(startID), lProvincesPointsY.get(i).get(startID));
                    startID--;
                }
            }
        }
        rebuildCurrentProvince();
    }

    private void addPointToCurrentProvinceDirect(int nX, int nY) {
        currentPointsX.add(nX);
        currentPointsY.add(nY);
        if (currentProvincePoints == 0) {
            currentProvince = new Path();
            currentProvince.moveTo(nX, nY);
            iCurrentProvinceMinX = nX;
            iCurrentProvinceMaxX = nX;
            iCurrentProvinceMinY = nY;
            iCurrentProvinceMaxY = nY;
        } else {
            currentProvince.lineTo(nX, nY);
            if (iCurrentProvinceMaxX < nX) iCurrentProvinceMaxX = nX;
            if (iCurrentProvinceMinX > nX) iCurrentProvinceMinX = nX;
            if (iCurrentProvinceMaxY < nY) iCurrentProvinceMaxY = nY;
            if (iCurrentProvinceMinY > nY) iCurrentProvinceMinY = nY;
        }
        currentProvincePoints++;
    }

    // ==================================================================
    // Neighboring provinces computation (inner class)
    // ------------------------------------------------------------------
    // Per-province meta (extras / sea / continent / new-order ids) kept in step with the geometry
    // ------------------------------------------------------------------

    /** Call right BEFORE a province is removed from the geometry lists. */
    public ProvinceMeta.Removal removeProvinceMeta(int id) {
        return ProvinceMeta.remove(lExtras, editor.lSea, editor.lContinent, lNewOrderOfPorivnces_IDs, vProvinces.size(), id);
    }

    /** Undo of {@link #removeProvinceMeta}; call right AFTER the province is back in the geometry lists. */
    public void restoreProvinceMeta(int id, ProvinceMeta.Removal removal) {
        ProvinceMeta.restore(lExtras, editor.lSea, editor.lContinent, lNewOrderOfPorivnces_IDs, id, removal);
    }

    /** Call right AFTER a new province was appended to the geometry lists. */
    public void appendProvinceMeta() {
        ProvinceMeta.pad(lExtras, editor.lSea, editor.lContinent, vProvinces.size());
    }

    /**
     * A province was cut in two: the new part {@code to} takes over everything that is not its outline
     * (terrain, growth, region, sea flag, continent) from the part {@code from}. Connections are not copied -
     * they are worked out from the geometry.
     */
    public void inheritProvinceMeta(int from, int to) {
        ProvinceMeta.pad(lExtras, editor.lSea, editor.lContinent, vProvinces.size());
        if (from < 0 || to < 0 || from >= lExtras.size() || to >= lExtras.size() || from == to) return;
        ProvinceExtra c = lExtras.get(from).copy();
        c.links.clear();
        c.seaLinks.clear();
        if (c.levelOfPort >= 0) c.levelOfPort = ProvinceExportBuilder.LEVEL_LAND_NO_PORT;
        lExtras.set(to, c);
        editor.lSea.set(to, editor.lSea.get(from));
        editor.lContinent.set(to, editor.lContinent.get(from));
    }

    /** Call right BEFORE the last province is removed from the geometry lists. */
    public void removeLastProvinceMeta() {
        ProvinceMeta.removeLast(lExtras, editor.lSea, editor.lContinent, vProvinces.size());
    }

    // ==================================================================

    public void startNeighboringProvincesCalculation(CameraState camera) {
        _NeighboringProvincesThread = new NeighboringProvincesThread(camera);
        _NeighboringProvincesThread.start();
    }

    public void clearAll() {
        vProvinces = new Vector<>();
        lProvincesPointsX.clear();
        lProvincesPointsY.clear();
        lProvincesCenterX.clear();
        lProvincesCenterY.clear();
        lProvincesMinX.clear();
        lProvincesMinY.clear();
        lProvincesMaxX.clear();
        lProvincesMaxY.clear();
        lNewOrderOfProvinces_wasChosen.clear();
        lNewOrderOfPorivnces_IDs.clear();
        lExtras.clear();
        neighboring.clear();
    }

    private class NeighboringProvincesThread extends Thread {
        private final CameraState camera;

        NeighboringProvincesThread(CameraState camera) {
            this.camera = camera;
        }

        @Override
        public void run() {
            try {
                neighboring = NeighborComputer.compute(lProvincesPointsX, lProvincesPointsY, camera.iMapEditorBackgroundWidth);
                neighboringProvincesReadyToUse = true;
                invalidator.run();
            } catch (Throwable ignored) {
                neighboringProvincesReadyToUse = false;
            }
        }
    }
}
