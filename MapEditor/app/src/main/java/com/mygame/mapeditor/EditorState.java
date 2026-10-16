package com.mygame.mapeditor;

import android.content.Context;
import android.graphics.Color;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class EditorState {

    private final Context context;
    private final Runnable invalidator;

    // Editor modes
    public boolean bDrawProvinces = true;
    public boolean bChooseProvinceMode = false;
    public boolean bChangePointMode = false;
    public boolean bAddPointToProvinceMode = false;
    public boolean bRemovePointMode = false;
    public boolean bNewOrderOfProvinces = false;
    public int iEndOfLandProvinces = -1;

    // Split ("cut province in two") mode: the cut line the user is drawing, in map coordinates
    public boolean bSplitMode = false;
    public final List<Integer> splitX = new ArrayList<>();
    public final List<Integer> splitY = new ArrayList<>();

    // Active selection
    public int iActiveProvinceID = -1;
    public boolean bSelectedPointsToChangePosition = false;
    public int iChangePositionX = 0;
    public int iChangePositionY = 0;

    // Display / view
    public boolean drawBG = true;
    public boolean drawNeighboring = false;

    // Display toggles
    public boolean MAXIMUM_SIZE = true;
    public boolean bDrawProvincePoints = true;
    public boolean showProvincePoints = false;
    public boolean showProvinceColors = true;
    public boolean showProvinceIDs = false;
    public boolean bDrawCities = true;
    public boolean bDrawCitiesNames = false;
    public boolean bDrawArmy = false;
    public boolean bDrawMountains = true;

    // Continents
    public List<Integer> lContinent = new ArrayList<>();
    public boolean bContinentMode = false;
    public int iCurrentContinentID = 0;
    public final int[] CONTINENT_COLORS = {
            Color.rgb(220, 80, 80),
            Color.rgb(80, 220, 80),
            Color.rgb(80, 120, 240),
            Color.rgb(240, 200, 80),
            Color.rgb(180, 80, 220),
            Color.rgb(80, 220, 220),
            Color.rgb(240, 140, 60)
    };

    // Sea provinces (index -> is-sea)
    public List<Boolean> lSea = new ArrayList<>();
    public boolean bSeaMode = false;

    public EditorState(Context context, Runnable invalidator) {
        this.context = context;
        this.invalidator = invalidator;
    }

    public void resetModes() {
        this.bChangePointMode = false;
        this.bAddPointToProvinceMode = false;
        this.bRemovePointMode = false;
        this.bChooseProvinceMode = false;
        this.bSelectedPointsToChangePosition = false;
        this.bNewOrderOfProvinces = false;
        this.bContinentMode = false;
        this.bSeaMode = false;
        this.bSplitMode = false;
        clearSplitLine();
    }

    public void clearSplitLine() {
        splitX.clear();
        splitY.clear();
    }

    public void toggleSplitMode() {
        if (!bSplitMode) resetModes();
        bSplitMode = !bSplitMode;
        if (!bSplitMode) clearSplitLine();
        invalidator.run();
    }

    public void toggleChangePointMode() {
        if (!bChangePointMode) resetModes();
        bChangePointMode = !bChangePointMode;
        invalidator.run();
    }

    public void toggleAddPointMode() {
        if (!bAddPointToProvinceMode) resetModes();
        bAddPointToProvinceMode = !bAddPointToProvinceMode;
        invalidator.run();
    }

    public void toggleRemovePointMode() {
        if (!bRemovePointMode) resetModes();
        bRemovePointMode = !bRemovePointMode;
        invalidator.run();
    }

    public void toggleChooseProvinceMode() {
        if (!bChooseProvinceMode) resetModes();
        bChooseProvinceMode = !bChooseProvinceMode;
        invalidator.run();
    }

    public void toggleNewOrderMode() {
        if (!bNewOrderOfProvinces) resetModes();
        bNewOrderOfProvinces = !bNewOrderOfProvinces;
        invalidator.run();
    }

    public void toggleDrawNeighboring() {
        drawNeighboring = !drawNeighboring;
        invalidator.run();
    }

    public void toggleDrawBackground() {
        drawBG = !drawBG;
        invalidator.run();
    }

    public void toggleDrawProvinces() {
        bDrawProvinces = !bDrawProvinces;
        invalidator.run();
    }

    public void toggleShowProvinceIDs() {
        showProvinceIDs = !showProvinceIDs;
        invalidator.run();
    }

    public void toggleContinentMode() {
        if (!bContinentMode) resetModes();
        bContinentMode = !bContinentMode;
        invalidator.run();
    }

    public void setNextContinentId() {
        iCurrentContinentID = (iCurrentContinentID + 1) % CONTINENT_COLORS.length;
        Toast.makeText(context, context.getString(R.string.toast_continent_selected, iCurrentContinentID), Toast.LENGTH_SHORT).show();
    }

    public void toggleSeaMode() {
        if (!bSeaMode) resetModes();
        bSeaMode = !bSeaMode;
        invalidator.run();
    }

    public boolean isSea(int provinceId) {
        return provinceId >= 0 && provinceId < lSea.size() && lSea.get(provinceId);
    }

    public void clearSeaFlags(int provinceCount) {
        lSea = new ArrayList<>(provinceCount);
        for (int i = 0; i < provinceCount; i++) lSea.add(false);
    }

    public void clearActiveProvince() {
        iActiveProvinceID = -1;
        invalidator.run();
    }
}
