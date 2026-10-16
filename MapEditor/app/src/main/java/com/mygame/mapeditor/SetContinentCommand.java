package com.mygame.mapeditor;

import java.util.List;

public class SetContinentCommand implements EditorCommand {
    private final List<Integer> lContinent;
    private final int provinceId;
    private final int oldContinent;
    private final int newContinent;

    public SetContinentCommand(List<Integer> lContinent, int provinceId, int oldContinent, int newContinent) {
        this.lContinent = lContinent;
        this.provinceId = provinceId;
        this.oldContinent = oldContinent;
        this.newContinent = newContinent;
    }

    @Override
    public void execute() {
        while (lContinent.size() <= provinceId) {
            lContinent.add(0);
        }
        lContinent.set(provinceId, newContinent);
    }

    @Override
    public void undo() {
        if (provinceId < lContinent.size()) {
            lContinent.set(provinceId, oldContinent);
        }
    }
}
