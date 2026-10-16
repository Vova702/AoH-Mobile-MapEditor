package com.mygame.mapeditor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import java.util.List;
import java.util.ArrayList;

/**
 * Відповідає виключно за відмальовування карти, UI та оверлеїв.
 */
public class MapRendered {

    private final GamePanel panel;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgFillPaint = new Paint();
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();

    private static final float DETAIL_POINTS_SCALE = 0.0f;
    // Поріг зуму, нижче якого точки-вершини ВСІХ провінцій взагалі не
    // малюються - при такому віддаленні вони все одно зливаються в кашу
    // й нічого не дають, а лише вантажать слабкі телефони. Не плутати з
    // DETAIL_POINTS_SCALE вище - та стосується лише кількох точок провінції,
    // яку зараз малює користувач, і на продуктивність не впливає.
    private static final float VERTEX_DOTS_MIN_SCALE = 0.15f;
    private static final float DETAIL_CITIES_SCALE = 0.55f;
    private static final float DETAIL_NAMES_SCALE = 1.05f;
    private static final float DETAIL_MOUNTAINS_SCALE = 0.65f;

    // Reusable buffer for batched point drawing — never re-allocated per frame
    private float[] pointBuffer = new float[4096];

    public MapRendered(GamePanel panel) {
        this.panel = panel;

        strokePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(20f);
    }

    public void render(Canvas canvas) {
        final int viewW = panel.getWidth();
        final int viewH = panel.getHeight();

        bgFillPaint.setColor(Color.rgb(205, 205, 205));
        canvas.drawRect(0, 0, viewW, viewH, bgFillPaint);

        if (!panel.mapDataReady) {
            textPaint.setTextSize(20f);
            textPaint.setColor(Color.DKGRAY);
            String statusText = panel.mapLoadFailed
                    ? panel.context.getString(R.string.loading_failed)
                    : panel.context.getString(R.string.loading_map);
            canvas.drawText(statusText, 16f, viewH * 0.5f, textPaint);
            for (Button b : panel.lButtons) b.draw(canvas);
            return;
        }

        canvas.save();
        canvas.scale(panel.camera.fMapScale, panel.camera.fMapScale);

        final int[] tiles = {-panel.camera.iMapEditorBackgroundWidth, 0, panel.camera.iMapEditorBackgroundWidth};
        final float visibleW = viewW / panel.camera.fMapScale;
        final float visibleH = viewH / panel.camera.fMapScale;

        for (int tile : tiles) {
            final float left = tile - panel.camera.iXMapPos;
            final float top = -panel.camera.iYMapPos;

            if (left > visibleW || left + panel.camera.iMapEditorBackgroundWidth < 0
                    || top > visibleH || top + panel.camera.iMapEditorBackgroundHeight < 0) {
                continue;
            }

            canvas.save();
            canvas.translate(left, top);

            if (panel.mapEditorBackground != null) {
                canvas.drawBitmap(panel.mapEditorBackground, 0, 0, null);
            }

            canvas.translate(-tile, 0);
            if (panel.editor.bDrawProvinces) drawProvinces(canvas);
            drawArmy(canvas);
            drawPoints(canvas, panel.camera.fMapScale);
            canvas.restore();
        }
        canvas.restore();

        canvas.save();
        canvas.scale(panel.camera.fMapScale, panel.camera.fMapScale);
        canvas.translate(-panel.camera.iXMapPos, -panel.camera.iYMapPos);

        if (panel.provinceData.currentPointsX.size() > 1 && panel.provinceData.currentProvince != null) {
            fillPaint.setColor(Color.argb(125, 48, 65, 122));
            canvas.drawPath(panel.provinceData.currentProvince, fillPaint);
        }

        if (panel.editor.bDrawProvincePoints && panel.camera.fMapScale >= DETAIL_POINTS_SCALE) {
            for (int i = 0; i < panel.provinceData.currentPointsX.size(); i++) {
                drawPointMarker(canvas, panel.provinceData.currentPointsX.get(i), panel.provinceData.currentPointsY.get(i),
                        i == 0 ? Color.BLACK : Color.MAGENTA);
            }
        }

        drawSplitLine(canvas);

        if (panel.provinceData.currentPointsX.size() == 2) {
            strokePaint.setColor(Color.BLACK);
            canvas.drawRect(panel.provinceData.currentPointsX.get(0), panel.provinceData.currentPointsY.get(0),
                    panel.provinceData.currentPointsX.get(1), panel.provinceData.currentPointsY.get(1), strokePaint);
        }

        if (panel.editor.drawNeighboring) drawNeighboring(canvas);

        final int mouseMapX = screenToMapX(panel.iMousePosX);
        final int mouseMapY = screenToMapY(panel.iMousePosY);
        drawPointMarker(canvas, mouseMapX, mouseMapY, Color.RED);

        canvas.restore();

        drawProvinceIDs(canvas);

        for (Button b : panel.lButtons) b.draw(canvas);

        if (panel.editor.bChangePointMode || panel.editor.bChooseProvinceMode || panel.editor.bAddPointToProvinceMode
                || panel.editor.bRemovePointMode || panel.editor.bNewOrderOfProvinces || panel.editor.bSplitMode) {
            drawMode(canvas);
        }
        drawTextInfo(canvas);
        drawPerformanceOverlay(canvas);
        drawModeBadge(canvas);
    }

    private int screenToMapX(float screenX) {
        return Math.round(panel.camera.iXMapPos + screenX / panel.camera.fMapScale);
    }

    private int screenToMapY(float screenY) {
        return Math.round(panel.camera.iYMapPos + screenY / panel.camera.fMapScale);
    }

    /**
     * Адаптивне малювання точок:
     * Розмір точок автоматично зменшується при збільшенні зуму (panel.camera.fMapScale),
     * щоб на зумі 15X вони не ставали гігантськими.
     */
    /** The cut line of the "split province" mode: thick orange line with numbered-free point markers. */
    private void drawSplitLine(Canvas canvas) {
        int n = panel.editor.splitX.size();
        if (!panel.editor.bSplitMode || n == 0) return;
        strokePaint.setColor(Color.rgb(255, 140, 0));
        float oldWidth = strokePaint.getStrokeWidth();
        strokePaint.setStrokeWidth(Math.max(1f, 3f * panel.density / panel.camera.fMapScale));
        for (int i = 0; i + 1 < n; i++) {
            canvas.drawLine(panel.editor.splitX.get(i), panel.editor.splitY.get(i),
                    panel.editor.splitX.get(i + 1), panel.editor.splitY.get(i + 1), strokePaint);
        }
        strokePaint.setStrokeWidth(oldWidth);
        for (int i = 0; i < n; i++) {
            drawPointMarker(canvas, panel.editor.splitX.get(i), panel.editor.splitY.get(i),
                    i == 0 ? Color.rgb(255, 215, 0) : Color.rgb(255, 140, 0));
        }
    }

    private void drawPointMarker(Canvas canvas, int x, int y, int color) {
        fillPaint.setColor(color);
        float adaptiveRadius = (8f * panel.density) / panel.camera.fMapScale;
        canvas.drawCircle(x, y, adaptiveRadius, fillPaint);
    }

    private float viewLeft() {
        int w = panel.camera.iMapEditorBackgroundWidth;
        if (w <= 0) return panel.camera.iXMapPos;
        return ((panel.camera.iXMapPos % w) + w) % w;
    }

    private boolean provinceVisible(int i) {
        if (i < 0 || i >= panel.provinceData.vProvinces.size()
                || i >= panel.provinceData.lProvincesMinX.size() || i >= panel.provinceData.lProvincesMaxX.size()
                || i >= panel.provinceData.lProvincesMinY.size() || i >= panel.provinceData.lProvincesMaxY.size()) {
            return false;
        }

        final float left = viewLeft();
        final float right = left + panel.getWidth() / panel.camera.fMapScale;
        final float top = panel.camera.iYMapPos;
        final float bottom = panel.camera.iYMapPos + panel.getHeight() / panel.camera.fMapScale;

        final int minX = panel.provinceData.lProvincesMinX.get(i);
        final int maxX = panel.provinceData.lProvincesMaxX.get(i);
        final int minY = panel.provinceData.lProvincesMinY.get(i);
        final int maxY = panel.provinceData.lProvincesMaxY.get(i);

        boolean x = (maxX >= left && minX <= right)
                || (maxX + panel.camera.iMapEditorBackgroundWidth >= left && minX + panel.camera.iMapEditorBackgroundWidth <= right)
                || (maxX - panel.camera.iMapEditorBackgroundWidth >= left && minX - panel.camera.iMapEditorBackgroundWidth <= right);

        return x && maxY >= top && minY <= bottom;
    }

    private boolean pointVisible(int x, int y) {
        final float left = viewLeft();
        final float right = left + panel.getWidth() / panel.camera.fMapScale;
        final float top = panel.camera.iYMapPos;
        final float bottom = panel.camera.iYMapPos + panel.getHeight() / panel.camera.fMapScale;

        boolean xVisible = (x >= left && x <= right)
                || (x + panel.camera.iMapEditorBackgroundWidth >= left && x + panel.camera.iMapEditorBackgroundWidth <= right)
                || (x - panel.camera.iMapEditorBackgroundWidth >= left && x - panel.camera.iMapEditorBackgroundWidth <= right);

        return xVisible && y >= top && y <= bottom;
    }

    private void drawProvinces(Canvas canvas) {
        final int size = panel.provinceData.vProvinces.size();
        for (int i = 0; i < size; i++) {
            if (panel.editor.bNewOrderOfProvinces && i < panel.provinceData.lNewOrderOfProvinces_wasChosen.size()
                    && panel.provinceData.lNewOrderOfProvinces_wasChosen.get(i)) continue;
            if (!provinceVisible(i)) continue;

            if (panel.editor.showProvinceColors) {
                if (panel.editor.bContinentMode) {
                    int contId = (i < panel.editor.lContinent.size()) ? panel.editor.lContinent.get(i) : 0;
                    int color = panel.editor.CONTINENT_COLORS[Math.abs(contId) % panel.editor.CONTINENT_COLORS.length];
                    fillPaint.setColor(Color.argb(160, Color.red(color), Color.green(color), Color.blue(color)));
                } else if (panel.editor.isSea(i)) {
                    fillPaint.setColor(Color.argb(panel.provinceData.iSeaProvinceAlpha, 45, 110, 190));
                } else if (i == panel.editor.iActiveProvinceID) {
                    fillPaint.setColor(Color.WHITE);
                } else if (i < panel.provinceData.pathR.size() && i < panel.provinceData.pathG.size() && i < panel.provinceData.pathB.size()) {
                    fillPaint.setColor(Color.argb(panel.provinceData.iLandProvinceAlpha,
                            panel.provinceData.pathR.get(i), panel.provinceData.pathG.get(i), panel.provinceData.pathB.get(i)));
                } else {
                    fillPaint.setColor(Color.argb(panel.provinceData.iLandProvinceAlpha, 120, 120, 120));
                }
                canvas.drawPath(panel.provinceData.vProvinces.elementAt(i), fillPaint);
            } else {
                strokePaint.setColor(Color.argb(180, 80, 80, 80));
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(Math.max(1f, 1.5f / panel.camera.fMapScale));
                canvas.drawPath(panel.provinceData.vProvinces.elementAt(i), strokePaint);
            }
        }
    }

    private void drawPoints(Canvas canvas, float scale) {
        if (panel.editor.showProvincePoints && panel.editor.bDrawProvincePoints && scale >= VERTEX_DOTS_MIN_SCALE) {
            final int baseStep = scale < 0.30f ? 8 : (scale < 0.40f ? 4 : (scale < 0.70f ? 2 : 1));
            final int densityPercent = panel.getPointsDensityPercent();
            final int provinceCount = panel.provinceData.vProvinces.size();
            final float radius = (8f * panel.density) / scale;
            final float diameter = radius * 2;

            // Canvas.drawPoints() sizes points by strokeWidth (diameter) and shapes
            // them by strokeCap. ROUND cap + strokeWidth = diameter gives filled circles
            // matching the old per-point drawCircle() calls.
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setStrokeCap(Paint.Cap.ROUND);
            fillPaint.setStrokeWidth(diameter);

            for (int i = 0; i < provinceCount; i++) {
                if (!provinceVisible(i)) continue;
                if (i >= panel.provinceData.lProvincesPointsX.size() || i >= panel.provinceData.lProvincesPointsY.size()) continue;

                final java.util.List<Integer> px = panel.provinceData.lProvincesPointsX.get(i);
                final java.util.List<Integer> py = panel.provinceData.lProvincesPointsY.get(i);
                final int count = Math.min(px.size(), py.size());
                final int color = (i < panel.provinceData.pathR.size() && i < panel.provinceData.pathG.size() && i < panel.provinceData.pathB.size())
                        ? Color.rgb(panel.provinceData.pathG.get(i), panel.provinceData.pathB.get(i), panel.provinceData.pathR.get(i))
                        : Color.BLACK;

                fillPaint.setColor(color);

                int bufferIdx = 0;
                for (int j = 0; j < count; j += baseStep) {
                    if (densityPercent < 100) {
                        int group = j / baseStep;
                        switch (densityPercent) {
                            case 25 -> { if (group % 4 != 0) continue; }
                            case 50 -> { if (group % 2 != 0) continue; }
                            case 75 -> { if (group % 4 == 3) continue; }
                            default -> { }
                        }
                    }

                    if (bufferIdx + 2 > pointBuffer.length) {
                        canvas.drawPoints(pointBuffer, 0, bufferIdx, fillPaint);
                        bufferIdx = 0;
                    }
                    pointBuffer[bufferIdx++] = px.get(j);
                    pointBuffer[bufferIdx++] = py.get(j);
                }

                if (bufferIdx > 0) {
                    canvas.drawPoints(pointBuffer, 0, bufferIdx, fillPaint);
                }
            }
        }

        if (panel.editor.bDrawCities && scale >= DETAIL_CITIES_SCALE) {
            final int count = Math.min(panel.citiesX.size(), Math.min(panel.citiesY.size(), panel.citiesNames.size()));
            final boolean names = panel.editor.bDrawCitiesNames && scale >= DETAIL_NAMES_SCALE;
            if (names) {
                textPaint.setTextSize(3f);
                textPaint.setColor(Color.rgb(240, 240, 240));
            }

            fillPaint.setColor(Color.BLACK);
            for (int i = 0; i < count; i++) {
                int x = panel.citiesX.get(i);
                int y = panel.citiesY.get(i);
                if (!pointVisible(x, y)) continue;

                canvas.drawRect(x - 1, y - 1, x + 1, y + 1, fillPaint);
                if (names) canvas.drawText(panel.citiesNames.get(i), x - 1, y - 1, textPaint);
            }
        }

        if (panel.editor.bDrawMountains && scale >= DETAIL_MOUNTAINS_SCALE) {
            fillPaint.setColor(Color.DKGRAY);
            final int count = Math.min(panel.mountainsX.size(), panel.mountainsY.size());
            for (int i = 0; i < count; i++) {
                int x = panel.mountainsX.get(i);
                int y = panel.mountainsY.get(i);
                if (pointVisible(x, y)) canvas.drawCircle(x, y, 2f, fillPaint);
            }
        }
    }

    private void drawArmy(Canvas canvas) {
        if (!panel.editor.bDrawArmy) return;
        textPaint.setTextSize(14f);
        for (int i = 0; i < panel.provinceData.vProvinces.size(); i++) {
            boolean visible = (!panel.editor.bNewOrderOfProvinces || !panel.provinceData.lNewOrderOfProvinces_wasChosen.get(i))
                    && panel.camera.iXMapPos + (panel.camera.GWIDTH / panel.camera.fMapScale) >= panel.provinceData.lProvincesMinX.get(i) 
                    && panel.camera.iXMapPos <= panel.provinceData.lProvincesMaxX.get(i)
                    && panel.camera.iYMapPos + (panel.camera.GHEIGHT / panel.camera.fMapScale) >= panel.provinceData.lProvincesMinY.get(i) 
                    && panel.camera.iYMapPos <= panel.provinceData.lProvincesMaxY.get(i);
            
            if (!visible) continue;
            String label = "" + i;
            textPaint.getTextBounds(label, 0, label.length(), textBounds);
            int cx = panel.provinceData.lProvincesCenterX.get(i);
            int cy = panel.provinceData.lProvincesCenterY.get(i);
            fillPaint.setColor(Color.argb(255, 10, 10, 10));
            canvas.drawRect(cx - 2 - textBounds.width() / 2f, cy - 2 - textBounds.height() / 2f,
                    cx - 2 - textBounds.width() / 2f + textBounds.width() + 4, 
                    cy - 2 - textBounds.height() / 2f + textBounds.height() / 2f + 2,
                    fillPaint);
            textPaint.setColor(Color.argb(255, 255, 255, 45));
            canvas.drawText(label, cx - textBounds.width() / 2f, cy + 1, textPaint);
        }
    }

    private void drawProvinceIDs(Canvas canvas) {
        if (!panel.editor.showProvinceIDs) return;
        if (panel.camera.fMapScale < 0.35f) return;

        float textSize = Math.max(14f, 14f * panel.density);
        textPaint.setTextSize(textSize);
        textPaint.setFakeBoldText(true);
        textPaint.setStyle(Paint.Style.FILL);

        float scale = panel.camera.fMapScale;
        float iX = panel.camera.iXMapPos;
        float iY = panel.camera.iYMapPos;

        for (int i = 0; i < panel.provinceData.vProvinces.size(); i++) {
            if (!provinceVisible(i)) continue;

            String id = String.valueOf(i);
            textPaint.getTextBounds(id, 0, id.length(), textBounds);

            float cx = panel.provinceData.lProvincesCenterX.get(i);
            float cy = panel.provinceData.lProvincesCenterY.get(i);

            float sx = (cx - iX) * scale;
            float sy = (cy - iY) * scale;

            float pad = 4f * panel.density;
            float hw = textBounds.width() / 2f;
            float hh = textBounds.height() / 2f;

            fillPaint.setColor(Color.argb(180, 0, 0, 0));
            canvas.drawRect(sx - hw - pad, sy - hh - pad,
                    sx + hw + pad, sy + hh + pad, fillPaint);
            textPaint.setColor(Color.WHITE);
            float baseline = sy - (textBounds.top + textBounds.bottom) / 2f;
            canvas.drawText(id, sx - hw, baseline, textPaint);
        }
    }

    private void drawTextInfo(Canvas canvas) {
        textPaint.setTextSize(Math.max(12f, 13f * panel.density));
        String s1 = String.format(panel.context.getString(R.string.debug_coords),
                panel.provinceData.iLastPressedPosX, panel.provinceData.iLastPressedPosY);
        String s2 = String.format(java.util.Locale.US, panel.context.getString(R.string.debug_zoom),
                panel.camera.fMapScale);

        float w = Math.max(textPaint.measureText(s1), textPaint.measureText(s2));
        float x = Math.max(4f, panel.getWidth() - w - 12f * panel.density);
        float y = 22f * panel.density;

        fillPaint.setColor(Color.argb(155, 0, 0, 0));
        canvas.drawRoundRect(x - 7f * panel.density, 3f * panel.density,
                panel.getWidth() - 4f * panel.density, y + 25f * panel.density,
                7f * panel.density, 7f * panel.density, fillPaint);

        textPaint.setColor(Color.WHITE);
        canvas.drawText(s2, x, y, textPaint);
        canvas.drawText(s1, x, y + 16f * panel.density, textPaint);
    }

    private void drawPerformanceOverlay(Canvas canvas) {
        panel.stats.fpsFrameCount++;
        long now = System.nanoTime();
        long elapsed = now - panel.stats.fpsWindowStart;

        if (elapsed >= 500_000_000L) {
            panel.stats.currentFps = panel.stats.fpsFrameCount * 1_000_000_000f / elapsed;
            panel.stats.fpsFrameCount = 0;
            panel.stats.fpsWindowStart = now;
        }

        overlayPaint.setTextSize(Math.max(11f, 12f * panel.density));
        overlayPaint.setColor(Color.WHITE);
        String fps = String.format(java.util.Locale.US, panel.context.getString(R.string.debug_fps), panel.stats.currentFps);
        String prov = String.format(panel.context.getString(R.string.debug_province_count), panel.provinceData.lProvincesPointsX.size());

        float x = 7f * panel.density;
        float y = 21f * panel.density;
        fillPaint.setColor(Color.argb(150, 0, 0, 0));
        float width = Math.max(overlayPaint.measureText(fps), overlayPaint.measureText(prov))
                + 12f * panel.density;
        canvas.drawRoundRect(3f * panel.density, 3f * panel.density,
                3f * panel.density + width, 47f * panel.density,
                6f * panel.density, 6f * panel.density, fillPaint);

        canvas.drawText(fps, x, y, overlayPaint);
        canvas.drawText(prov, x, y + 17f * panel.density, overlayPaint);
    }

    private void drawModeBadge(Canvas canvas) {
        String mode;
        if (panel.editor.bChangePointMode) {
            mode = panel.editor.bSelectedPointsToChangePosition
                    ? panel.context.getString(R.string.mode_move_point)
                    : panel.context.getString(R.string.mode_change_point);
        } else if (panel.editor.bChooseProvinceMode) {
            mode = panel.context.getString(R.string.mode_delete_province);
        } else if (panel.editor.bAddPointToProvinceMode) {
            mode = panel.context.getString(R.string.mode_add_point);
        } else if (panel.editor.bRemovePointMode) {
            mode = panel.context.getString(R.string.mode_remove_point);
        } else if (panel.editor.bNewOrderOfProvinces) {
            mode = panel.context.getString(R.string.mode_order);
        } else if (panel.editor.bSplitMode) {
            mode = panel.context.getString(R.string.mode_split);
        } else {
            mode = panel.context.getString(R.string.mode_pan_tap);
        }

        textPaint.setTextSize(Math.max(12f, 13f * panel.density));
        textPaint.setColor(Color.WHITE);

        float w = textPaint.measureText(mode) + 18f * panel.density;
        float y = 58f * panel.density;

        fillPaint.setColor(Color.argb(185, 20, 20, 20));
        canvas.drawRoundRect(5f * panel.density, y,
                5f * panel.density + w, y + 28f * panel.density,
                8f * panel.density, 8f * panel.density, fillPaint);
        canvas.drawText(mode, 14f * panel.density, y + 19f * panel.density, textPaint);
    }

    // ------------------------------------------------------------------
    // Малювання сусідніх провінцій
    // ------------------------------------------------------------------
    private void drawNeighboring(Canvas canvas) {
        if (panel == null || panel.provinceData.neighboring == null) return;

        // Налаштування ліній (товщина адаптується під масштабування)
        strokePaint.setColor(Color.argb(180, 255, 165, 0)); 
        strokePaint.setStrokeWidth(Math.max(1f, 2f / panel.camera.fMapScale));
        strokePaint.setStyle(Paint.Style.STROKE);

        for (int i = 0; i < panel.provinceData.neighboring.size(); i++) {
            List<Integer> neighbors = panel.provinceData.neighboring.get(i);
            if (neighbors == null) continue;

            for (int neighborId : neighbors) {
                if (neighborId >= 0 && neighborId < panel.provinceData.lProvincesCenterX.size() && i < panel.provinceData.lProvincesCenterX.size()) {
                    float x1 = panel.provinceData.lProvincesCenterX.get(i);
                    float y1 = panel.provinceData.lProvincesCenterY.get(i);
                    float x2 = panel.provinceData.lProvincesCenterX.get(neighborId);
                    float y2 = panel.provinceData.lProvincesCenterY.get(neighborId);

                    canvas.drawLine(x1, y1, x2, y2, strokePaint);
                }
            }
        }
    }

    private void drawMode(Canvas canvas) {
        drawModeBadge(canvas);
    }

}
