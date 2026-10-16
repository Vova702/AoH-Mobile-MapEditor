package com.mygame.mapeditor;

import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

public class DeleteProvinceCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private final int provinceId;
    private final List<Integer> pointsX;
    private final List<Integer> pointsY;
    private final int pathR, pathG, pathB;
    private final int centerX, centerY;
    private final int minX, maxX, minY, maxY;
    private final boolean wasChosen;
    private ProvinceMeta.Removal removal;

    public DeleteProvinceCommand(ProvinceData provinceData, int provinceId) {
        this.provinceData = provinceData;
        this.provinceId = provinceId;
        this.pointsX = new ArrayList<>(provinceData.lProvincesPointsX.get(provinceId));
        this.pointsY = new ArrayList<>(provinceData.lProvincesPointsY.get(provinceId));
        this.pathR = provinceData.pathR.get(provinceId);
        this.pathG = provinceData.pathG.get(provinceId);
        this.pathB = provinceData.pathB.get(provinceId);
        this.centerX = provinceData.lProvincesCenterX.get(provinceId);
        this.centerY = provinceData.lProvincesCenterY.get(provinceId);
        this.minX = provinceData.lProvincesMinX.get(provinceId);
        this.maxX = provinceData.lProvincesMaxX.get(provinceId);
        this.minY = provinceData.lProvincesMinY.get(provinceId);
        this.maxY = provinceData.lProvincesMaxY.get(provinceId);
        this.wasChosen = provinceData.lNewOrderOfProvinces_wasChosen.get(provinceId);
    }

    @Override
    public void execute() {
        removal = provinceData.removeProvinceMeta(provinceId);
        provinceData.vProvinces.remove(provinceId);
        provinceData.lProvincesPointsX.remove(provinceId);
        provinceData.lProvincesPointsY.remove(provinceId);
        provinceData.lProvincesCenterX.remove(provinceId);
        provinceData.lProvincesCenterY.remove(provinceId);
        provinceData.lProvincesMinX.remove(provinceId);
        provinceData.lProvincesMinY.remove(provinceId);
        provinceData.lProvincesMaxX.remove(provinceId);
        provinceData.lProvincesMaxY.remove(provinceId);
        provinceData.lNewOrderOfProvinces_wasChosen.remove(provinceId);
        if (provinceId < provinceData.pathR.size()) provinceData.pathR.remove(provinceId);
        if (provinceId < provinceData.pathG.size()) provinceData.pathG.remove(provinceId);
        if (provinceId < provinceData.pathB.size()) provinceData.pathB.remove(provinceId);
    }

    @Override
    public void undo() {
        var nPath = new Path();
        nPath.moveTo(pointsX.get(0), pointsY.get(0));
        for (int i = 1; i < pointsX.size(); i++) {
            nPath.lineTo(pointsX.get(i), pointsY.get(i));
        }
        nPath.close();
        provinceData.vProvinces.add(provinceId, nPath);
        provinceData.lProvincesPointsX.add(provinceId, new ArrayList<>(pointsX));
        provinceData.lProvincesPointsY.add(provinceId, new ArrayList<>(pointsY));
        provinceData.lProvincesCenterX.add(provinceId, centerX);
        provinceData.lProvincesCenterY.add(provinceId, centerY);
        provinceData.lProvincesMinX.add(provinceId, minX);
        provinceData.lProvincesMaxX.add(provinceId, maxX);
        provinceData.lProvincesMinY.add(provinceId, minY);
        provinceData.lProvincesMaxY.add(provinceId, maxY);
        provinceData.lNewOrderOfProvinces_wasChosen.add(provinceId, wasChosen);
        provinceData.pathR.add(provinceId, pathR);
        provinceData.pathG.add(provinceId, pathG);
        provinceData.pathB.add(provinceId, pathB);
        if (removal != null) provinceData.restoreProvinceMeta(provinceId, removal);
    }
}
