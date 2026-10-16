package com.mygame.mapeditor;

import android.graphics.Path;

public class EditCurrentPointCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private final int x;
    private final int y;

    public EditCurrentPointCommand(ProvinceData provinceData, int x, int y) {
        this.provinceData = provinceData;
        this.x = x;
        this.y = y;
    }

    @Override
    public void execute() {
        if (!provinceData.currentPointsX.isEmpty()
                && provinceData.currentPointsX.get(provinceData.currentPointsX.size() - 1) == x
                && provinceData.currentPointsY.get(provinceData.currentPointsY.size() - 1) == y) {
            return;
        }
        provinceData.currentPointsX.add(x);
        provinceData.currentPointsY.add(y);
        if (provinceData.currentProvincePoints == 0) {
            provinceData.currentProvince = new Path();
            provinceData.currentProvince.moveTo(x, y);
            provinceData.iCurrentProvinceMinX = x;
            provinceData.iCurrentProvinceMaxX = x;
            provinceData.iCurrentProvinceMinY = y;
            provinceData.iCurrentProvinceMaxY = y;
        } else {
            provinceData.currentProvince.lineTo(x, y);
            if (provinceData.iCurrentProvinceMaxX < x) provinceData.iCurrentProvinceMaxX = x;
            if (provinceData.iCurrentProvinceMinX > x) provinceData.iCurrentProvinceMinX = x;
            if (provinceData.iCurrentProvinceMaxY < y) provinceData.iCurrentProvinceMaxY = y;
            if (provinceData.iCurrentProvinceMinY > y) provinceData.iCurrentProvinceMinY = y;
        }
        provinceData.currentProvincePoints++;
        provinceData.iLastPressedPosX = x;
        provinceData.iLastPressedPosY = y;
    }

    @Override
    public void undo() {
        provinceData.undoLastPoint(true);
    }
}
