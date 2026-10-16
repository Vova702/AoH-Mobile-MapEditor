package com.mygame.mapeditor;

import java.util.List;

public class SetSeaCommand implements EditorCommand {
    private final List<Boolean> lSea;
    private final int provinceId;
    private final boolean oldSea;
    private final boolean newSea;

    public SetSeaCommand(List<Boolean> lSea, int provinceId, boolean oldSea, boolean newSea) {
        this.lSea = lSea;
        this.provinceId = provinceId;
        this.oldSea = oldSea;
        this.newSea = newSea;
    }

    @Override
    public void execute() {
        while (lSea.size() <= provinceId) {
            lSea.add(false);
        }
        lSea.set(provinceId, newSea);
    }

    @Override
    public void undo() {
        if (provinceId < lSea.size()) {
            lSea.set(provinceId, oldSea);
        }
    }
}