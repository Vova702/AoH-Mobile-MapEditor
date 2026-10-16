package com.mygame.mapeditor;

import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

public class CommitProvinceCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private final int committedIndex;
    private final List<Integer> pointsX;
    private final List<Integer> pointsY;
    private final int pathR, pathG, pathB;

    public CommitProvinceCommand(ProvinceData provinceData, int committedIndex,
                                  List<Integer> pointsX, List<Integer> pointsY,
                                  int pathR, int pathG, int pathB) {
        this.provinceData = provinceData;
        this.committedIndex = committedIndex;
        this.pointsX = new ArrayList<>(pointsX);
        this.pointsY = new ArrayList<>(pointsY);
        this.pathR = pathR;
        this.pathG = pathG;
        this.pathB = pathB;
    }

    @Override
    public void execute() {
        if (committedIndex < provinceData.lProvincesPointsX.size()) return;
        var nPath = new Path();
        nPath.moveTo(pointsX.get(0), pointsY.get(0));
        for (int i = 1; i < pointsX.size(); i++) {
            nPath.lineTo(pointsX.get(i), pointsY.get(i));
        }
        nPath.close();
        provinceData.vProvinces.add(nPath);
        provinceData.lProvincesPointsX.add(new ArrayList<>(pointsX));
        provinceData.lProvincesPointsY.add(new ArrayList<>(pointsY));
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < pointsX.size(); i++) {
            int x = pointsX.get(i), y = pointsY.get(i);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        provinceData.lProvincesMinX.add(minX);
        provinceData.lProvincesMaxX.add(maxX);
        provinceData.lProvincesMinY.add(minY);
        provinceData.lProvincesMaxY.add(maxY);
        provinceData.lProvincesCenterX.add((minX + maxX) / 2);
        provinceData.lProvincesCenterY.add((minY + maxY) / 2);
        provinceData.lNewOrderOfProvinces_wasChosen.add(false);
        provinceData.pathR.add(pathR);
        provinceData.pathG.add(pathG);
        provinceData.pathB.add(pathB);
        provinceData.appendProvinceMeta();
    }

    @Override
    public void undo() {
        if (committedIndex >= provinceData.lProvincesPointsX.size()) return;
        int idx = committedIndex;
        provinceData.removeLastProvinceMeta();
        provinceData.vProvinces.remove(idx);
        provinceData.lProvincesPointsX.remove(idx);
        provinceData.lProvincesPointsY.remove(idx);
        provinceData.lProvincesCenterX.remove(idx);
        provinceData.lProvincesCenterY.remove(idx);
        provinceData.lProvincesMinX.remove(idx);
        provinceData.lProvincesMinY.remove(idx);
        provinceData.lProvincesMaxX.remove(idx);
        provinceData.lProvincesMaxY.remove(idx);
        provinceData.lNewOrderOfProvinces_wasChosen.remove(idx);
        if (idx < provinceData.pathR.size()) provinceData.pathR.remove(idx);
        if (idx < provinceData.pathG.size()) provinceData.pathG.remove(idx);
        if (idx < provinceData.pathB.size()) provinceData.pathB.remove(idx);
    }
}
