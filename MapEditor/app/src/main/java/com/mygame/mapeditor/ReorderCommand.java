package com.mygame.mapeditor;

public class ReorderCommand implements EditorCommand {

    private final ProvinceData provinceData;
    private final int provinceId;

    public ReorderCommand(ProvinceData provinceData, int provinceId) {
        this.provinceData = provinceData;
        this.provinceId = provinceId;
    }

    @Override
    public void execute() {
        if (provinceId < 0 || provinceId >= provinceData.lNewOrderOfProvinces_wasChosen.size()) return;
        if (provinceData.lNewOrderOfProvinces_wasChosen.get(provinceId)) return;
        provinceData.lNewOrderOfProvinces_wasChosen.set(provinceId, true);
        provinceData.lNewOrderOfPorivnces_IDs.add(provinceId);
    }

    @Override
    public void undo() {
        if (provinceId < 0 || provinceId >= provinceData.lNewOrderOfProvinces_wasChosen.size()) return;
        if (!provinceData.lNewOrderOfProvinces_wasChosen.get(provinceId)) return;
        provinceData.lNewOrderOfProvinces_wasChosen.set(provinceId, false);
        provinceData.lNewOrderOfPorivnces_IDs.remove((Integer) provinceId);
    }
}
