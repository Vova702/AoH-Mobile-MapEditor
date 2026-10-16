package com.mygame.mapeditor;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class GamePanel extends View {

    // ------------------------------------------------------------------
    // Пензлі (Paint)
    // ------------------------------------------------------------------
    Paint bgFillPaint = new Paint();
    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ------------------------------------------------------------------
    // Рендерер
    // ------------------------------------------------------------------
    MapRendered mapRenderer;

    // ------------------------------------------------------------------
    // Context / Assets
    // ------------------------------------------------------------------
    final Context context;
    final AssetManager assets;

    // ------------------------------------------------------------------
    // Фони та провінції (доступні для MapRendered)
    // ------------------------------------------------------------------
    Bitmap mapEditorBackground;

    // Province data — see ProvinceData.java (provinceData.*)
    static final int iPadding = 5;
    final CameraState camera = new CameraState();
    final PerformanceStats stats = new PerformanceStats();
    final EditorState editor;  // initialized in constructor
    final UndoRedoManager undoRedo;  // initialized in constructor
    final SnapGrid snapGrid;  // initialized in constructor
    final ProvinceData provinceData;  // initialized in constructor

    // ------------------------------------------------------------------
    // Editor state — see EditorState.java
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Міста та Гори (доступні для MapRendered)
    // ------------------------------------------------------------------
    List<String> citiesNames = new ArrayList<>();
    List<Integer> citiesX = new ArrayList<>();
    List<Integer> citiesY = new ArrayList<>();

    List<String> mountainsNames = new ArrayList<>();
    List<Integer> mountainsX = new ArrayList<>();
    List<Integer> mountainsY = new ArrayList<>();

    volatile boolean citiesLoaded = false;

    // ------------------------------------------------------------------
    // Додаткові списки — see ProvinceData.java
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Стан завантаження карти
    // ------------------------------------------------------------------
    volatile boolean mapDataReady = false;
    volatile boolean mapDataLoading = false;
    volatile boolean mapLoadFailed = false;
    volatile int mapLoadPercent = 0;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "MapEditor-Worker");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------
    // Константи деталізації
    // ------------------------------------------------------------------
    static final float DETAIL_POINTS_SCALE = 0.0f;
    static final float DETAIL_CITIES_SCALE = 0.55f;
    static final float DETAIL_NAMES_SCALE = 1.05f;
    static final float DETAIL_MOUNTAINS_SCALE = 0.65f;

    // ------------------------------------------------------------------
    // Константи файлів та директорій
    // ------------------------------------------------------------------
    private static final String SOURCE_EDITOR_DATA_DIR = "editor_data";
    private static final String DEFAULT_BACKGROUND_IMAGE = "bg.png";
    private static final String MOUNTAINS_FILE = "mountains.json";
    private static final String SEA_FILE = "sea_info.json";
    private static final String CONTINENTS_FILE = "continents_info.json";
    private static final String configFile = "config_editor.txt";
    private static final String EXPORT_DIR_NAME = "export";
    private static final String STATE_DIR_NAME = "state";
    private static final String SAVE_PROV_FILE = "save_prov.txt";
    private static final String[] CITY_FILES = {
            "cities.json", "cities_1.json", "cities_2.json", "cities_3.json"
    };
    private String sBackgroundImage = DEFAULT_BACKGROUND_IMAGE;
    private String legacyMapDataFile = "mapAoC2_v2.txt";

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // Province data — see ProvinceData.java (provinceData.*)
    // ------------------------------------------------------------------
    // provinceData.currentProvince, provinceData.currentProvincePoints, provinceData.lProvincesPointsX/Y,
    // provinceData.lProvincesCenterX/Y, lProvincesMin/MaxX/Y, provinceData.currentPointsX/Y,
    // lNewOrderOfProvinces_*, iCurrentProvinceShiftArmyX/Y,
    // provinceData.iLastPressedPosX/Y, provinceData.pathR/G/B, provinceData.neighboring, iLandProvinceAlpha,
    // iSeaProvinceAlpha, foundFirst, forward — all in provinceData

    List<Button> lButtons = new ArrayList<>();
    private Button togglePointsButton;
    private Button toggleFillButton;
    private Button toggleDensityButton;
    private Button toggleIdsButton;
    private Button toggleSeaButton;
    private Button undoButton;
    private Button redoButton;

    public int iMousePosX = 0;
    public int iMousePosY = 0;

    // provinceData.iLastPressedPosX/Y — see ProvinceData.java

    // Континенти — see EditorState.java

    // Undo / Redo — see UndoRedoManager.java

    // Snap state — see SnapGrid.java
    boolean lastTapSnappedToVertex = false;

    // Province points density: 25, 50, 75, or 100 percent
    private int pointsDensityPercent = 25;

    // Callback set by MainActivity to launch the system file picker
    Runnable onImportRequested;

    // Callback set by MainActivity to launch the "Save As" document picker for exports
    java.util.function.Consumer<File> onExportZipRequested;

    // Active map subfolder name — appended to the working-assets path
    private final String activeMapName;

    // ------------------------------------------------------------------
    // МЕТОДИ КЕРУВАННЯ ТА SNAP-ТОЧОК
    // toggleContinentMode, setNextContinentId, resetModes → EditorState
    // toggleSnapToGrid, toggleSnapToPoints, snapCoord → SnapGrid
    // ------------------------------------------------------------------

    public void toggleSnapToGrid() { snapGrid.toggleSnapToGrid(); }
    public void toggleSnapToPoints() { snapGrid.toggleSnapToPoints(); }
    private int snapCoord(int value) { return snapGrid.snapCoord(value); }
    public int getPointsDensityPercent() { return pointsDensityPercent; }

    private void snapLastPressedToNearestVertex() {
        var r = snapGrid.snapToNearestVertex(
                provinceData.iLastPressedPosX, provinceData.iLastPressedPosY,
                camera.fMapScale,
                camera.iMapEditorBackgroundWidth,
                provinceData.currentPointsX, provinceData.currentPointsY,
                provinceData.lProvincesPointsX, provinceData.lProvincesPointsY,
                provinceData.lProvincesMinX, provinceData.lProvincesMaxX,
                provinceData.lProvincesMinY, provinceData.lProvincesMaxY);
        provinceData.iLastPressedPosX = r.x();
        provinceData.iLastPressedPosY = r.y();
        lastTapSnappedToVertex = r.snapped();
    }
	

    // executeCommand(), undo(), redo() moved to UndoRedoManager

    // Кольори та сусідні провінції — see ProvinceData.java

    // Жести та стан дотиків
    private final ScaleGestureDetector scaleDetector;
    private float lastTouchX, lastTouchY;
    private boolean isDragging = false;
    private long lastTapTime = 0L;
    private float lastTapX = 0f;
    private float lastTapY = 0f;
    private static final float TAP_SLOP_PX = 16f;

    private boolean touchStartedOnToolbar = false;
    private boolean hadMultiTouch = false;
    
    final float density;

    // ------------------------------------------------------------------
    // Конструктор класу GamePanel
    // ------------------------------------------------------------------
    public GamePanel(Context context, String mapName) {
        super(context);
        this.context = context;
        this.assets = context.getAssets();
        this.density = getResources().getDisplayMetrics().density;
        this.activeMapName = mapName;
        Runnable redrawAndUpdateButtons = () -> {
            postInvalidateOnAnimation();
            updateUndoRedoButtons();
        };
        this.editor = new EditorState(context, redrawAndUpdateButtons);
        this.undoRedo = new UndoRedoManager(context, redrawAndUpdateButtons);
        this.snapGrid = new SnapGrid(context, redrawAndUpdateButtons);
        this.provinceData = new ProvinceData(this.editor, this.undoRedo, this::postInvalidateOnAnimation);

        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);

        // Створюємо художника, який робить усю графічну роботу
        mapRenderer = new MapRendered(this);

        try {
            parseConfigFile();
        } catch (IOException ignored) {
        }

        addToolbar();

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        return mapDataReady;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (!mapDataReady) return false;

                        float rawFactor = detector.getScaleFactor();
                        if (rawFactor <= 0f || Float.isNaN(rawFactor) || Float.isInfinite(rawFactor)) {
                            return false;
                        }
                        
                        float smoothFactor = 1.0f + (rawFactor - 1.0f) * 0.58f;
                        if (smoothFactor < 0.90f) smoothFactor = 0.90f;
                        if (smoothFactor > 1.10f) smoothFactor = 1.10f;

                        final float oldScale = camera.fMapScale;
                        camera.applyPinchZoom(detector.getFocusX(), detector.getFocusY(),
                                smoothFactor, getWidth(), getHeight());

                        if (camera.fMapScale != oldScale) {
                            postInvalidateOnAnimation();
                        }
                        return true;
                    }
                });

        loadMapDataAsync();
    }

    /** Завантаження даних у фоновому потоці */
    private void loadMapDataAsync() {
        if (mapDataLoading || mapDataReady) return;
        mapDataLoading = true;
        setLoadPercent(2);

        backgroundExecutor.execute(() -> {
            try {
                synchronized (MAP_IO_LOCK) {
                    ensureWorkingEditorData();
                    setLoadPercent(10);
                    loadBackground();
                    setLoadPercent(25);
                    loadProvincesFromFile();
                }
                mapLoadFailed = provinceData.vProvinces == null || provinceData.vProvinces.isEmpty();
            } catch (Throwable ignored) {
                provinceData.vProvinces = new Vector<>();
                mapLoadFailed = true;
            }

            setLoadPercent(100);
            mapDataReady = true;
            mapDataLoading = false;
            postInvalidateOnAnimation();
        });
    }

    /** Оновлює прогрес завантаження й запитує перемалювання (можна з фонового потоку). */
    private void setLoadPercent(int percent) {
        mapLoadPercent = percent;
        postInvalidateOnAnimation();
    }
    

    /** Compact phone toolbar. It is laid out at the bottom so it never hides under the status bar. */
    private void addToolbar() {
        final float d = density;
        final int gap = Math.max(2, Math.round(4f * d));
        final int side = Math.max(4, Math.round(4f * d));
        final int h = Math.max(44, Math.round(48f * d));

        addToolButton(getContext().getString(R.string.tool_add_prov), getContext().getString(R.string.tool_tip_add_prov), this::startNewProvince);
        addToolButton(getContext().getString(R.string.tool_remove_prov), getContext().getString(R.string.tool_tip_remove_prov), this::deleteActiveProvince);
        addToolButton(getContext().getString(R.string.tool_add_point), getContext().getString(R.string.tool_tip_add_point), editor::toggleAddPointMode);
        addToolButton(getContext().getString(R.string.tool_remove_point), getContext().getString(R.string.tool_tip_remove_point), editor::toggleRemovePointMode);
        addToolButton(getContext().getString(R.string.tool_move_point), getContext().getString(R.string.tool_tip_move_point), editor::toggleChangePointMode);
        addToolButton(getContext().getString(R.string.tool_delete_prov), getContext().getString(R.string.tool_tip_delete_prov), editor::toggleChooseProvinceMode);
        addToolButton(getContext().getString(R.string.tool_set_order), getContext().getString(R.string.tool_tip_set_order), editor::toggleNewOrderMode);
        addToolButton(getContext().getString(R.string.tool_split), getContext().getString(R.string.tool_tip_split), this::onSplitButton);
        addToolButton(getContext().getString(R.string.tool_toggle_neighbor), getContext().getString(R.string.tool_tip_toggle_neighbor), () -> {
            if (!provinceData.neighboringProvincesReadyToUse) startNeighboringProvincesCalculation();
            editor.toggleDrawNeighboring();
        });
        toggleSeaButton = new Button(getContext().getString(R.string.tool_sea_off), 0, 0, 0, 0, true);
        toggleSeaButton.setTooltip(getContext().getString(R.string.tool_tip_sea));
        toggleSeaButton.setOnClickListener(() -> {
            editor.toggleSeaMode();
            toggleSeaButton.sText = editor.bSeaMode
                    ? getContext().getString(R.string.tool_sea_on)
                    : getContext().getString(R.string.tool_sea_off);
            postInvalidateOnAnimation();
        });
        lButtons.add(toggleSeaButton);
        togglePointsButton = new Button(editor.showProvincePoints ? getContext().getString(R.string.tool_points_on) : getContext().getString(R.string.tool_points_off), 0, 0, 0, 0, true);
        togglePointsButton.setTooltip(getContext().getString(R.string.tool_tip_toggle_points));
        togglePointsButton.setOnClickListener(() -> {
            editor.showProvincePoints = !editor.showProvincePoints;
            togglePointsButton.sText = editor.showProvincePoints ? getContext().getString(R.string.tool_points_on) : getContext().getString(R.string.tool_points_off);
            postInvalidateOnAnimation();
        });
        lButtons.add(togglePointsButton);
        toggleFillButton = new Button(editor.showProvinceColors ? getContext().getString(R.string.tool_fill_on) : getContext().getString(R.string.tool_fill_off), 0, 0, 0, 0, true);
        toggleFillButton.setTooltip(getContext().getString(R.string.tool_tip_toggle_fill));
        toggleFillButton.setOnClickListener(() -> {
            editor.showProvinceColors = !editor.showProvinceColors;
            toggleFillButton.sText = editor.showProvinceColors ? getContext().getString(R.string.tool_fill_on) : getContext().getString(R.string.tool_fill_off);
            invalidate();
        });
        lButtons.add(toggleFillButton);
        toggleDensityButton = new Button(String.format(getContext().getString(R.string.tool_density), 25), 0, 0, 0, 0, true);
        toggleDensityButton.setTooltip(getContext().getString(R.string.tool_tip_density));
        toggleDensityButton.setOnClickListener(() -> {
            pointsDensityPercent = (pointsDensityPercent >= 100) ? 25 : pointsDensityPercent + 25;
            toggleDensityButton.sText = String.format(getContext().getString(R.string.tool_density), pointsDensityPercent);
            postInvalidateOnAnimation();
        });
        lButtons.add(toggleDensityButton);
        undoButton = new Button(getContext().getString(R.string.tool_undo), 0, 0, 0, 0, true);
        undoButton.setTooltip(getContext().getString(R.string.tool_tip_undo));
        undoButton.setOnClickListener(this::onUndo);
        lButtons.add(undoButton);
        redoButton = new Button(getContext().getString(R.string.tool_redo), 0, 0, 0, 0, true);
        redoButton.setTooltip(getContext().getString(R.string.tool_tip_redo));
        redoButton.setOnClickListener(this::onRedo);
        lButtons.add(redoButton);
        addToolButton(getContext().getString(R.string.tool_save), getContext().getString(R.string.tool_tip_save), this::confirm);
        addToolButton(getContext().getString(R.string.tool_import), getContext().getString(R.string.tool_tip_import), this::requestFileImport);
        toggleIdsButton = new Button(editor.showProvinceIDs ? getContext().getString(R.string.tool_show_ids_on) : getContext().getString(R.string.tool_show_ids_off), 0, 0, 0, 0, true);
        toggleIdsButton.setTooltip(getContext().getString(R.string.tool_tip_show_ids));
        toggleIdsButton.setOnClickListener(() -> {
            editor.toggleShowProvinceIDs();
            toggleIdsButton.sText = editor.showProvinceIDs ? getContext().getString(R.string.tool_show_ids_on) : getContext().getString(R.string.tool_show_ids_off);
            postInvalidateOnAnimation();
        });
        lButtons.add(toggleIdsButton);
        updateUndoRedoButtons();

        layoutToolbar(getWidth(), getHeight(), gap, side, h);
    }

    private void addToolButton(String label, String tooltip, Runnable action) {
        var b = new Button(label, 0, 0, 0, 0, true);
        b.setTooltip(tooltip);
        b.setOnClickListener(action);
        lButtons.add(b);
    }

    private void layoutToolbar(int viewW, int viewH, int gap, int side, int h) {
        if (lButtons.isEmpty() || viewW <= 0 || viewH <= 0) return;

        final int count = lButtons.size();
        final int row1Count = (count + 1) / 2;
        final int row2Count = count - row1Count;

        final int maxPerRow = row1Count;
        final int available = Math.max(1, viewW - side * 2 - gap * (maxPerRow - 1));
        final int bw = Math.max(Math.round(34f * density), available / maxPerRow);

        final int rowGap = Math.max(2, Math.round(2f * density));
        final int row2Y = Math.max(0, viewH - h - Math.round(8f * density));
        final int row1Y = Math.max(0, row2Y - h - rowGap);

        for (int i = 0; i < count; i++) {
            Button b = lButtons.get(i);
            if (i < row1Count) {
                b.iPosX = side + i * (bw + gap);
                b.iPosY = row1Y;
            } else {
                b.iPosX = side + (i - row1Count) * (bw + gap);
                b.iPosY = row2Y;
            }
            b.iWidth = bw;
            b.iHeight = h;
        }
    }

    private void releaseAllButtons() {
        for (Button b : lButtons) {
            b.onRelease();
        }
    }

    // resetModes() moved to EditorState.resetModes()


    // ==================================================================
    // DRAWING  (was: draw(Graphics g))
    // ==================================================================
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // camera.GWIDTH/camera.GHEIGHT used to be a fixed 1920x1040 constant regardless of the
        // real screen - that broke visibility culling and the background fill
        // rect on any device with a different resolution/aspect ratio.
        if (w > 0 && h > 0) {
            camera.GWIDTH = w;
            camera.GHEIGHT = h;
            final int gap = Math.max(2, Math.round(4f * density));
            final int side = Math.max(4, Math.round(4f * density));
            final int toolbarH = Math.max(44, Math.round(48f * density));
            layoutToolbar(w, h, gap, side, toolbarH);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        bgFillPaint.setColor(Color.rgb(205, 205, 205));
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgFillPaint);

        if (!mapDataReady) {
            textPaint.setTextSize(20f);
            textPaint.setColor(Color.DKGRAY);
            String statusText = mapLoadFailed
                    ? getContext().getString(R.string.loading_failed)
                    : getContext().getString(R.string.loading_map, mapLoadPercent);
            canvas.drawText(statusText, 16f, getHeight() * 0.5f, textPaint);
            for (Button b : lButtons) b.draw(canvas);
            return;
        }

        // Всю складну графіку малює MapRendered
        if (mapRenderer != null) {
            mapRenderer.render(canvas);
        }

        // Кнопки інтерфейсу малюються зверху
        for (Button b : lButtons) {
            b.draw(canvas);
        }
    }
    

    /** Methods screenToMapX/Y, zoomAt, zoomStepAt, clampMapPosition moved to CameraState */

    /**
     * Original code used g2d.draw3DRect(x, y, 0, 0, false) to mark single points -
     * a 0x0 "3D rect" has no direct Canvas equivalent, so points are drawn as a
     * small filled circle instead (visually the same purpose: a point marker).
     */

    // ==================================================================
    // TOUCH INPUT  (was: MouseListener / MouseMotionListener / MouseWheelListener)
    //
    // Mapping: single-finger drag = pan the map (was: drag with a mouse button
    // held that matched modifier bit 4). Single tap (down+up with little
    // movement) = the original "left click" (modifier bit 16) dispatch, which
    // triggers whichever edit mode is currently active. Two-finger pinch = zoom
    // (replaces the mouse wheel handler).
    // ==================================================================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        scaleDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);

                lastTouchX = event.getX();
                lastTouchY = event.getY();
                iMousePosX = Math.round(event.getX());
                iMousePosY = Math.round(event.getY());

                isDragging = false;
                hadMultiTouch = false;
                touchStartedOnToolbar = false;

                for (int i = lButtons.size() - 1; i >= 0; i--) {
                    if (lButtons.get(i).isClicked(event.getX(), event.getY())) {
                        touchStartedOnToolbar = true;
                        lButtons.get(i).onPress();
                        postDelayed(this::postInvalidateOnAnimation, Button.TOOLTIP_DELAY_MS + 16);
                        break;
                    }
                }

                postInvalidateOnAnimation();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN -> {
                releaseAllButtons();
                hadMultiTouch = true;
                isDragging = true;
                touchStartedOnToolbar = false;

                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }

            case MotionEvent.ACTION_MOVE -> {
                iMousePosX = Math.round(event.getX());
                iMousePosY = Math.round(event.getY());

                if (event.getPointerCount() == 1
                        && !scaleDetector.isInProgress()
                        && !touchStartedOnToolbar) {

                    final float dx = event.getX() - lastTouchX;
                    final float dy = event.getY() - lastTouchY;

                    if (isDragging || Math.hypot(dx, dy) > TAP_SLOP_PX) {
                        if (!isDragging) releaseAllButtons();
                        isDragging = true;
                        camera.iXMapPos -= Math.round(dx / camera.fMapScale);
                        camera.iYMapPos -= Math.round(dy / camera.fMapScale);
                        camera.clampYPosition(getWidth(), getHeight());

                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                }

                postInvalidateOnAnimation();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP -> {
                hadMultiTouch = true;
                isDragging = true;

                // Prevent the remaining finger from causing a large jump after pinch.
                final int remaining = event.getActionIndex() == 0 ? 1 : 0;
                if (event.getPointerCount() > remaining) {
                    lastTouchX = event.getX(remaining);
                    lastTouchY = event.getY(remaining);
                }
                return true;
            }

            case MotionEvent.ACTION_UP -> {
                final float touchX = event.getX();
                final float touchY = event.getY();

                releaseAllButtons();

                if (!touchStartedOnToolbar && !isDragging && !hadMultiTouch && mapDataReady) {
                    boolean buttonConsumed = false;
                    for (int i = lButtons.size() - 1; i >= 0; i--) {
                        if (lButtons.get(i).handleTap(touchX, touchY)) {
                            buttonConsumed = true;
                            break;
                        }
                    }

                    if (!buttonConsumed) {
                        final long now = System.currentTimeMillis();
                        final boolean doubleTap = now - lastTapTime < 280L
                                && Math.hypot(touchX - lastTapX, touchY - lastTapY) < 48f;

                        if (doubleTap) {
                            // The first tap of this pair already ran through
                            // handleMapTap() below on its own ACTION_UP and, in
                            // the default mode, added a point to the current
                            // province before we could know a second tap was
                            // coming. Retract it - but only if we were in that
                            // default add-point mode (the other modes don't
                            // add a point on tap, so there's nothing to undo).
                            if (editor.bSplitMode) {
                                // the first tap of this pair put a point on the cut line - take it back
                                if (lastTapAddedSplitPoint) removeLastSplitPoint();
                            } else if (!editor.bChooseProvinceMode && !editor.bNewOrderOfProvinces && !editor.bChangePointMode
                                    && !editor.bAddPointToProvinceMode && !editor.bRemovePointMode) {
                                undoLastPoint();
                            }
                            camera.zoomAt(touchX, touchY, camera.fMapScale < 1.20f ? 1.45f : 0.72f, getWidth(), getHeight());
                            lastTapTime = 0L;
                        } else {
                            provinceData.iLastPressedPosX = camera.screenToMapX(touchX);
                            provinceData.iLastPressedPosY = camera.screenToMapY(touchY);
                            snapLastPressedToNearestVertex();
                            handleMapTap();

                            lastTapTime = now;
                            lastTapX = touchX;
                            lastTapY = touchY;
                        }
                    }
                } else if (touchStartedOnToolbar && !hadMultiTouch) {
                    for (int i = lButtons.size() - 1; i >= 0; i--) {
                        if (lButtons.get(i).handleTap(touchX, touchY)) break;
                    }
                }

                isDragging = false;
                hadMultiTouch = false;
                touchStartedOnToolbar = false;

                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                updateUndoRedoButtons();
                postInvalidateOnAnimation();
                return true;
            }

            case MotionEvent.ACTION_CANCEL -> {
                releaseAllButtons();
                isDragging = false;
                hadMultiTouch = false;
                touchStartedOnToolbar = false;

                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                postInvalidateOnAnimation();
                return true;
            }

            default -> {
                return true;
            }
        }
    }

    /** Equivalent of the original mousePressed() left-click (modifiers & 16) branch. */
    private void handleMapTap() {
        if (editor.bSeaMode) {
            for (int i = 0; i < provinceData.vProvinces.size(); i++) {
                if (pathContains(provinceData.iLastPressedPosX, provinceData.iLastPressedPosY, i)) {
                    boolean oldVal = editor.isSea(i);
                    boolean newVal = !oldVal;
                    if (oldVal != newVal) {
                        undoRedo.executeCommand(new SetSeaCommand(editor.lSea, i, oldVal, newVal));
                    }
                    break;
                }
            }
        } else if (editor.bContinentMode) {
            // Перебираємо всі провінції та перевіряємо, в яку з них влучив тап
            for (int i = 0; i < provinceData.vProvinces.size(); i++) {
                if (pathContains(provinceData.iLastPressedPosX, provinceData.iLastPressedPosY, i)) {
                    int oldVal = (i < editor.lContinent.size()) ? editor.lContinent.get(i) : 0;
                    int newVal = editor.iCurrentContinentID;

                    // Якщо континент змінився — виконуємо через команду (щоб записалось у стек Undo)
                    if (oldVal != newVal) {
                        undoRedo.executeCommand(new SetContinentCommand(editor.lContinent, i, oldVal, newVal));
                    }
                    break;
                }
            }
        } else if (editor.bSplitMode) {
            addSplitPoint();
        } else if (editor.bChooseProvinceMode) {
            setProvinceID();
        } else if (editor.bNewOrderOfProvinces) {
            int prevSize = provinceData.lNewOrderOfPorivnces_IDs.size();
            setProvinceIDNewOrder();
            if (provinceData.lNewOrderOfPorivnces_IDs.size() > prevSize) {
                int sel = provinceData.lNewOrderOfPorivnces_IDs.get(provinceData.lNewOrderOfPorivnces_IDs.size() - 1);
                undoRedo.executeCommand(new ReorderCommand(provinceData, sel));
            }
        } else if (editor.bChangePointMode) {
            if (editor.bSelectedPointsToChangePosition) {
                int id = findProvinceForPoint(editor.iChangePositionX, editor.iChangePositionY);
                if (id >= 0) {
                    var cmd = new EditProvinceCommand(provinceData);
                    cmd.captureBefore();
                    changePointPosition();
                    cmd.captureAfter();
                    undoRedo.executeCommand(cmd);
                } else {
                    changePointPosition();
                }
            } else {
                changePointPosition();
            }
        } else if (editor.bAddPointToProvinceMode) {
            var cmd = new EditProvinceCommand(provinceData);
            cmd.captureBefore();
            boolean added = addPointToProvince();
            if (added) {
                cmd.captureAfter();
                undoRedo.executeCommand(cmd);
            }
        } else if (editor.bRemovePointMode) {
            int id = findProvinceIdForEdit();
            if (id >= 0) {
                var cmd = new EditProvinceCommand(provinceData);
                cmd.captureBefore();
                removePoint();
                cmd.captureAfter();
                undoRedo.executeCommand(cmd);
            } else {
                removePoint();
            }
        } else if (provinceData.currentProvincePoints <= 0
                || !provinceData.currentPointsX.get(provinceData.currentPointsX.size() - 1).equals(provinceData.iLastPressedPosX)
                || !provinceData.currentPointsY.get(provinceData.currentPointsY.size() - 1).equals(provinceData.iLastPressedPosY)) {
            addPointToCurrentProvince(provinceData.iLastPressedPosX, provinceData.iLastPressedPosY);
        }
    }

    // --- ПУНКТ 3: Пошук та перехід до провінції за ID ---

    // 1. Метод центрування камери на обраній провінції
    public void goToProvince(int provinceId) {
        if (provinceId < 0 || provinceId >= provinceData.vProvinces.size()) {
            Toast.makeText(context, getContext().getString(R.string.toast_province_not_found, provinceId), Toast.LENGTH_SHORT).show();
            return;
        }

        // Отримуємо центр провінції
        int cx = provinceData.lProvincesCenterX.get(provinceId);
        int cy = provinceData.lProvincesCenterY.get(provinceId);

        // Обчислюємо видиму область екрана з урахуванням зуму
        float screenW = getWidth() / camera.fMapScale;
        float screenH = getHeight() / camera.fMapScale;

        // Зміщуємо карту так, щоб центр провінції опинився по центру екрана
        camera.iXMapPos = Math.round(cx - screenW / 2f);
        camera.iYMapPos = Math.round(cy - screenH / 2f);

        // Підсвічуємо її білим кольором
        editor.iActiveProvinceID = provinceId;

        camera.clampMapPosition(getWidth(), getHeight()); // Обмежуємо координати межами карти
        postInvalidateOnAnimation();
    }

    // 2. Метод викликає діалогове вікно вводу ID
    public void showGoToIdDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        builder.setTitle(getContext().getString(R.string.dialog_title_goto));
        builder.setMessage(getContext().getString(R.string.dialog_msg_goto, provinceData.vProvinces.size() - 1));

        final android.widget.EditText input = new android.widget.EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);

        builder.setPositiveButton(getContext().getString(R.string.btn_go), (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (!text.isEmpty()) {
                try {
                    int id = Integer.parseInt(text);
                    goToProvince(id);
                } catch (NumberFormatException e) {
                    Toast.makeText(context, getContext().getString(R.string.toast_invalid_id), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(getContext().getString(R.string.btn_cancel), null);
        builder.show();
    }
    
    // --- ПУНКТ 4: Автоперевірка проблемних провінцій ---

    public void checkMapIssues() {
        List<Integer> invalidPoints = new ArrayList<>();
        List<Integer> isolated = new ArrayList<>();
        List<Integer> selfIntersecting = new ArrayList<>();

        int total = provinceData.lProvincesPointsX.size();

        for (int i = 0; i < total; i++) {
            List<Integer> px = provinceData.lProvincesPointsX.get(i);
            List<Integer> py = provinceData.lProvincesPointsY.get(i);

            // 1. Перевірка: менше ніж 3 точки
            if (px == null || px.size() < 3) {
                invalidPoints.add(i);
                continue;
            }

            // 2. Перевірка: ізольована провінція (не має сусідів)
            if (provinceData.neighboringProvincesReadyToUse && i < provinceData.neighboring.size()) {
                if (provinceData.neighboring.get(i).isEmpty()) {
                    isolated.add(i);
                }
            }

            // 3. Перевірка: самоперетин ліній
            if (provinceData.hasSelfIntersection(px, py)) {
                selfIntersecting.add(i);
            }
        }

        // Виведення результату
        if (invalidPoints.isEmpty() && isolated.isEmpty() && selfIntersecting.isEmpty()) {
            Toast.makeText(context, getContext().getString(R.string.toast_map_perfect), Toast.LENGTH_LONG).show();
        } else {
            var msg = new StringBuilder();
            if (!invalidPoints.isEmpty()) msg.append("\n• ").append(getContext().getString(R.string.scan_invalid_points, invalidPoints.toString()));
            if (!isolated.isEmpty()) msg.append("\n• ").append(getContext().getString(R.string.scan_no_neighbors, isolated.toString()));
            if (!selfIntersecting.isEmpty()) msg.append("\n• ").append(getContext().getString(R.string.scan_self_intersect, selfIntersecting.toString()));

            new android.app.AlertDialog.Builder(context)
                    .setTitle(getContext().getString(R.string.dialog_title_scan_results))
                    .setMessage(msg.toString())
                    .setPositiveButton(getContext().getString(R.string.btn_ok), null)
                    .show();
        }
    }

    // ==================================================================
    // EDITING LOGIC  — all data operations delegated to provinceData
    // ==================================================================

    private int findProvinceIdForEdit() {
        for (int i = 0; i < provinceData.lProvincesPointsX.size(); i++) {
            for (int j = 0; j < provinceData.lProvincesPointsX.get(i).size(); j++) {
                if (provinceData.lProvincesPointsX.get(i).get(j) == provinceData.iLastPressedPosX
                        && provinceData.lProvincesPointsY.get(i).get(j) == provinceData.iLastPressedPosY) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findProvinceForPoint(int px, int py) {
        for (int i = 0; i < provinceData.lProvincesPointsX.size(); i++) {
            for (int j = 0; j < provinceData.lProvincesPointsX.get(i).size(); j++) {
                if (provinceData.lProvincesPointsX.get(i).get(j) == px
                        && provinceData.lProvincesPointsY.get(i).get(j) == py) {
                    return i;
                }
            }
        }
        return -1;
    }

    public void addPointToCurrentProvince(int nX, int nY) {
        provinceData.addPointToCurrentProvince(nX, nY, lastTapSnappedToVertex, snapGrid);
        int sx = provinceData.currentPointsX.get(provinceData.currentPointsX.size() - 1);
        int sy = provinceData.currentPointsY.get(provinceData.currentPointsY.size() - 1);
        undoRedo.executeCommand(new EditCurrentPointCommand(provinceData, sx, sy));
        postInvalidateOnAnimation();
    }

    public void rebuildCurrentProvince() { provinceData.rebuildCurrentProvince(); }
    public void rebuildPath(int id) { provinceData.rebuildPath(id); }

    private static PolygonTrim.Poly toPoly(List<Integer> xs, List<Integer> ys) {
        int n = Math.min(xs.size(), ys.size());
        int[] x = new int[n], y = new int[n];
        for (int i = 0; i < n; i++) {
            x[i] = xs.get(i);
            y[i] = ys.get(i);
        }
        return new PolygonTrim.Poly(x, y);
    }

    /**
     * Cuts the territory of existing provinces out of the province that was just drawn, so two
     * provinces never occupy the same ground. The drawn outline is the one that gives way; the
     * existing provinces keep their shape.
     *
     * @return true when this method dealt with the drawing (added the trimmed province(s), or refused it
     *         and left the drawing as it was); false when the caller should add it exactly as drawn
     */
    private boolean commitTrimmed(List<Integer> xs, List<Integer> ys) {
        PolygonTrim.Outcome out;
        List<Integer> ids = new ArrayList<>();
        try {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (int i = 0; i < xs.size(); i++) {
                minX = Math.min(minX, xs.get(i));
                maxX = Math.max(maxX, xs.get(i));
                minY = Math.min(minY, ys.get(i));
                maxY = Math.max(maxY, ys.get(i));
            }
            List<PolygonTrim.Poly> others = new ArrayList<>();
            for (int i = 0; i < provinceData.lProvincesPointsX.size(); i++) {
                if (i < provinceData.lProvincesMinX.size()
                        && (provinceData.lProvincesMaxX.get(i) < minX || provinceData.lProvincesMinX.get(i) > maxX
                        || provinceData.lProvincesMaxY.get(i) < minY || provinceData.lProvincesMinY.get(i) > maxY)) {
                    continue;
                }
                others.add(toPoly(provinceData.lProvincesPointsX.get(i), provinceData.lProvincesPointsY.get(i)));
                ids.add(i);
            }
            if (others.isEmpty()) return false;
            out = PolygonTrim.subtract(toPoly(xs, ys), others);
        } catch (RuntimeException e) {
            toast(getContext().getString(R.string.toast_trim_failed));
            return false;
        }

        if (!out.overlapped) return false;
        if (out.problem == PolygonTrim.Problem.NOTHING_LEFT) {
            toast(getContext().getString(R.string.toast_trim_inside));
            return true;
        }
        if (out.problem == PolygonTrim.Problem.HAS_HOLE) {
            toast(getContext().getString(R.string.toast_trim_hole));
            return true;
        }

        java.util.Map<Integer, PolygonTrim.Poly> updated = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Integer, PolygonTrim.Poly> e : out.updatedOthers.entrySet()) {
            updated.put(ids.get(e.getKey()), e.getValue());
        }
        java.util.Random rnd = new java.util.Random();
        List<int[]> colors = new ArrayList<>();
        for (int i = 0; i < out.parts.size(); i++) {
            colors.add(new int[]{rnd.nextInt(255), rnd.nextInt(255), rnd.nextInt(255)});
        }

        int first = provinceData.lProvincesPointsX.size();
        provinceData.discardCurrentDrawing();
        undoRedo.executeCommand(new CommitTrimmedProvincesCommand(provinceData, first, out.parts, colors, updated));
        updateUndoRedoButtons();
        postInvalidateOnAnimation();
        toast(out.parts.size() == 1
                ? getContext().getString(R.string.toast_trimmed)
                : getContext().getString(R.string.toast_trimmed_parts, out.parts.size()));
        return true;
    }

    public void startNewProvince() {
        if (provinceData.currentProvincePoints > 1 && provinceData.currentPointsX.size() >= 3
                && commitTrimmed(new ArrayList<>(provinceData.currentPointsX), new ArrayList<>(provinceData.currentPointsY))) {
            return;
        }

        int oldSize = provinceData.lProvincesPointsX.size();
        List<Integer> savedX = new ArrayList<>(provinceData.currentPointsX);
        List<Integer> savedY = new ArrayList<>(provinceData.currentPointsY);
        provinceData.startNewProvince(snapGrid);
        if (provinceData.lProvincesPointsX.size() > oldSize) {
            int idx = oldSize;
            undoRedo.executeCommand(new CommitProvinceCommand(
                    provinceData, idx, savedX, savedY,
                    provinceData.pathR.get(idx),
                    provinceData.pathG.get(idx),
                    provinceData.pathB.get(idx)));
            updateUndoRedoButtons();
        }
        postInvalidateOnAnimation();
        toast(getContext().getString(R.string.toast_new_province_ready));
    }

    // ------------------------------------------------------------------
    // Cut a province in two with a line
    // ------------------------------------------------------------------

    private boolean lastTapAddedSplitPoint = false;

    /** Cut button: enters the mode; pressed again with a line drawn it performs the cut, without one it leaves the mode. */
    private void onSplitButton() {
        if (!editor.bSplitMode) {
            editor.toggleSplitMode();
            toast(getContext().getString(R.string.toast_split_hint));
        } else if (editor.splitX.size() >= 2) {
            performSplit();
        } else {
            editor.toggleSplitMode();
        }
        postInvalidateOnAnimation();
    }

    private void addSplitPoint() {
        int x = provinceData.iLastPressedPosX, y = provinceData.iLastPressedPosY;
        int n = editor.splitX.size();
        lastTapAddedSplitPoint = false;
        if (n > 0 && editor.splitX.get(n - 1) == x && editor.splitY.get(n - 1) == y) return;
        editor.splitX.add(x);
        editor.splitY.add(y);
        lastTapAddedSplitPoint = true;
        postInvalidateOnAnimation();
    }

    private void removeLastSplitPoint() {
        int n = editor.splitX.size();
        if (n == 0) return;
        editor.splitX.remove(n - 1);
        editor.splitY.remove(n - 1);
        lastTapAddedSplitPoint = false;
        postInvalidateOnAnimation();
    }

    private void performSplit() {
        int m = editor.splitX.size();
        int[] lx = new int[m], ly = new int[m];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < m; i++) {
            lx[i] = editor.splitX.get(i);
            ly[i] = editor.splitY.get(i);
            minX = Math.min(minX, lx[i]);
            maxX = Math.max(maxX, lx[i]);
            minY = Math.min(minY, ly[i]);
            maxY = Math.max(maxY, ly[i]);
        }

        int bestId = -1;
        PolygonSplit.Outcome best = null;
        PolygonSplit.Problem worst = PolygonSplit.Problem.NO_CROSSING;
        try {
            for (int i = 0; i < provinceData.lProvincesPointsX.size(); i++) {
                if (i < provinceData.lProvincesMinX.size()
                        && (provinceData.lProvincesMaxX.get(i) < minX || provinceData.lProvincesMinX.get(i) > maxX
                        || provinceData.lProvincesMaxY.get(i) < minY || provinceData.lProvincesMinY.get(i) > maxY)) {
                    continue;
                }
                PolygonSplit.Outcome o = PolygonSplit.split(
                        toPoly(provinceData.lProvincesPointsX.get(i), provinceData.lProvincesPointsY.get(i)), lx, ly);
                if (o.ok()) {
                    boolean better = best == null
                            || (i == editor.iActiveProvinceID)
                            || (bestId != editor.iActiveProvinceID && o.cutLength > best.cutLength);
                    if (better) {
                        best = o;
                        bestId = i;
                    }
                } else if (o.problem.ordinal() > worst.ordinal() && o.problem != PolygonSplit.Problem.TOO_FEW_POINTS) {
                    worst = o.problem;
                }
            }
        } catch (RuntimeException e) {
            toast(getContext().getString(R.string.toast_split_bad));
            return;
        }

        if (best == null) {
            int res;
            switch (worst) {
                case MULTIPLE_CROSSINGS: res = R.string.toast_split_multi; break;
                case TOUCHES_BORDER: res = R.string.toast_split_touch; break;
                case SELF_CROSSING: res = R.string.toast_split_self; break;
                case ENDS_NOT_ON_BORDER:
                case BAD_RESULT: res = R.string.toast_split_bad; break;
                default: res = R.string.toast_split_none; break;
            }
            toast(getContext().getString(res));
            return;
        }

        // neighbours that share the border where the cut ends get the end points as vertices too
        java.util.Map<Integer, PolygonTrim.Poly> updated = new java.util.LinkedHashMap<>();
        for (int i = 0; i < provinceData.lProvincesPointsX.size(); i++) {
            if (i == bestId) continue;
            PolygonTrim.Poly q = PolygonSplit.insertBorderPoints(
                    toPoly(provinceData.lProvincesPointsX.get(i), provinceData.lProvincesPointsY.get(i)), best.borderPoints);
            if (q != null) updated.put(i, q);
        }

        java.util.Random rnd = new java.util.Random();
        int[] color = {rnd.nextInt(255), rnd.nextInt(255), rnd.nextInt(255)};
        var cmd = new SplitProvinceCommand(provinceData, bestId, best.partA, best.partB, color, updated);
        undoRedo.executeCommand(cmd);
        editor.toggleSplitMode();   // leaves the mode and clears the line
        editor.iActiveProvinceID = -1;
        updateUndoRedoButtons();
        postInvalidateOnAnimation();
        toast(getContext().getString(R.string.toast_split_done, bestId, cmd.newProvinceId()));
    }

    public void deleteActiveProvince() {
        int id = editor.iActiveProvinceID;
        if (id < 0 || id >= provinceData.lProvincesPointsX.size()) {
            toast(getContext().getString(R.string.toast_no_province_selected));
            return;
        }
        undoRedo.executeCommand(new DeleteProvinceCommand(provinceData, id));
        editor.iActiveProvinceID = -1;
        updateUndoRedoButtons();
        postInvalidateOnAnimation();
        toast(getContext().getString(R.string.toast_province_deleted));
    }

    public void undoLastPoint() {
        provinceData.undoLastPoint(lastTapSnappedToVertex);
        postInvalidateOnAnimation();
    }

    public void onUndo() {
        if (editor.bSplitMode && !editor.splitX.isEmpty()) {
            removeLastSplitPoint();
            return;
        }
        if (undoRedo.canUndo()) {
            undoRedo.undo();
        } else if (provinceData.currentProvincePoints > 0) {
            undoLastPoint();
        }
        updateUndoRedoButtons();
    }

    public void onRedo() {
        if (undoRedo.canRedo()) undoRedo.redo();
        updateUndoRedoButtons();
    }

    private void updateUndoRedoButtons() {
        if (undoButton != null) {
            undoButton.setDisabled(!undoRedo.canUndo() && provinceData.currentProvincePoints <= 0);
        }
        if (redoButton != null) {
            redoButton.setDisabled(!undoRedo.canRedo());
        }
    }

    public void removePoint() { provinceData.removePoint(); }
    public boolean addPointToProvince() { return provinceData.addPointToProvince(); }

    public void changePointPosition() {
        provinceData.changePointPosition(lastTapSnappedToVertex, snapGrid);
        if (editor.bSelectedPointsToChangePosition) {
            undoRedo.isDirty = true;
        }
        postInvalidateOnAnimation();
    }

    public void setProvinceIDNewOrder() { provinceData.setProvinceIDNewOrder(); }

    public void setProvinceID() {
        if (provinceData.setProvinceID()) {
            postInvalidateOnAnimation();
            toast(getContext().getString(R.string.toast_selected_province, editor.iActiveProvinceID));
        }
    }

    public void autoPath() { provinceData.autoPath(snapGrid); }
    public void buildProvinceColors() { provinceData.buildProvinceColors(); }
    public boolean seaAcces(int i) { return provinceData.seaAcces(i); }
    public String getNeighboring(int i) { return provinceData.getNeighboring(i); }
    public void startNeighboringProvincesCalculation() {
        provinceData.startNeighboringProvincesCalculation(camera);
    }

    private boolean pathContains(int nX, int nY, int nProvinceID) {
        return provinceData.pathContains(nX, nY, nProvinceID);
    }

    private void toast(String msg) {
        post(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }

    public void panLeft() { camera.iXMapPos -= camera.iShiftXY; postInvalidateOnAnimation(); }
    public void panRight() { camera.iXMapPos += camera.iShiftXY; postInvalidateOnAnimation(); }
    public void panUp() { camera.iYMapPos -= camera.iShiftXY; postInvalidateOnAnimation(); }
    public void panDown() { camera.iYMapPos += camera.iShiftXY; postInvalidateOnAnimation(); }
    public void increaseShiftXY() { camera.iShiftXY++; postInvalidateOnAnimation(); }
    public void decreaseShiftXY() { camera.iShiftXY--; postInvalidateOnAnimation(); }

    /**
     * One lock for ALL reading/writing of the map files, shared by every GamePanel instance:
     * a screen that opens right after "Save" must not list the province files while the previous
     * screen is still swapping them in (it would see only part of them).
     */
    private static final Object MAP_IO_LOCK = new Object();

    // --- save progress + scales (see confirm()) ---------------------------------------------
    private final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** The dialog of the save that is running right now (null when idle or when no dialog could be shown). */
    private volatile SaveProgressDialog saveDialog;
    /** Scale-mask generator snapshotted from the provinces written by the latest save. */
    private ScaleMaskGenerator pendingScaleGen;
    private int pendingScaleCount;

    /** Percent plan: provinces + flags = 0..50, scales 1,3,5,7 = 50..99, done = 100. */
    private static final int PCT_PROVINCES_DONE = 50;
    private static final int PCT_SCALES_DONE = 99;

    private void reportSave(int percent) {
        SaveProgressDialog d = saveDialog;
        if (d != null) d.set(percent, null);
    }

    /** Folder for the generated scale masks: <app files>/scales/<mapName>/provinces/<k>/<id> (NOT inside the map folder). */
    private File scalesOutputDir() {
        return MapManager.scalesDirectory(context, activeMapName);
    }

    /**
     * A map unpacked from the game keeps its provinces in data/provinces. After a save the numbered
     * files in the map folder are the current ones (the editor reads those first), so the old copy
     * is outdated - and it would be picked up again by anything that looks inside the folder.
     */
    private void removeSupersededProvinceCopies() {
        File root = workingAssetsDir();
        File[] current = root.listFiles((dir, name) -> name.matches("\\d+"));
        if (current == null || current.length == 0) return;
        deleteRecursive(new File(root, "data/provinces"));
        File pack = new File(root, "data/provinces.pack");
        if (pack.isFile()) pack.delete();
        File rootPack = new File(root, "provinces.pack");
        if (rootPack.isFile()) rootPack.delete();
    }

    /** The masks inside the map folder (from the game) no longer match the saved outlines; the fresh ones are in the scales folder. */
    private void removeSupersededMasks() {
        deleteRecursive(MapFiles.nestedMasksDir(workingAssetsDir()));
    }

    private void generateScales() throws IOException {
        ScaleMaskGenerator gen = pendingScaleGen;
        int n = pendingScaleCount;
        pendingScaleGen = null;
        if (gen == null || n <= 0) return;
        ScaleFolderWriter.write(scalesOutputDir(), gen, n, ScalePlan.SCALES,
                PCT_PROVINCES_DONE, PCT_SCALES_DONE, (percent, scale) -> {
                    SaveProgressDialog d = saveDialog;
                    if (d != null) d.set(percent, d.stageScales(scale));
                });
    }

    public void confirm() {
        // a second tap while a save is running would only queue a duplicate
        if (!saving.compareAndSet(false, true)) return;
        final SaveProgressDialog dialog = SaveProgressDialog.show(getContext());

        backgroundExecutor.execute(() -> {
            String saveError = null;
            String scalesError = null;
            try {
                synchronized (MAP_IO_LOCK) {
                    saveDialog = dialog;
                    pendingScaleGen = null;
                    reportSave(1);
                    try {
                        if (editor.bNewOrderOfProvinces) saveNewOrderOfProvinces();
                        else saveProvinces();
                        saveSeaFlags();
                        saveContinentFlags();
                        removeSupersededProvinceCopies();
                    } catch (Throwable e) {
                        saveError = e.getMessage() != null ? e.getMessage() : e.toString();
                    }
                    if (saveError == null) {
                        reportSave(PCT_PROVINCES_DONE);
                        try {
                            generateScales();
                            removeSupersededMasks();
                        } catch (Throwable e) {
                            scalesError = e instanceof OutOfMemoryError
                                    ? getContext().getString(R.string.toast_scales_no_memory)
                                    : (e.getMessage() != null ? e.getMessage() : e.toString());
                        }
                    }
                    pendingScaleGen = null;
                }
            } finally {
                saveDialog = null;
                saving.set(false);
                if (dialog != null) {
                    dialog.set(100, null);
                    dialog.dismiss();
                }
            }

            if (saveError != null) {
                toast(getContext().getString(R.string.toast_save_failed, saveError));
            } else if (scalesError != null) {
                toast(getContext().getString(R.string.toast_saved_scales_failed, scalesError));
            } else {
                toast(getContext().getString(R.string.toast_saved));
            }
        });
    }

    /**
     * Saves province data as AoC2 binary files, creates a ZIP, and
     * delegates to MainActivity for the system "Save As" document picker.
     */
    public void exportProvinces() {
        backgroundExecutor.execute(() -> {
            try {
                // make sure the files on disk match what is on screen, the exporter reads them
                synchronized (MAP_IO_LOCK) {
                    if (editor.bNewOrderOfProvinces) saveNewOrderOfProvinces();
                    else saveProvinces();
                    saveSeaFlags();
                    saveContinentFlags();
                }
            } catch (IOException e) {
                toast(getContext().getString(R.string.toast_save_failed, e.getMessage()));
                return;
            }

            post(() -> {
                if (onExportZipRequested == null) {
                    toast(getContext().getString(R.string.toast_export_error, "No save handler"));
                    return;
                }
                ExportUi.run((android.app.Activity) getContext(), workingAssetsDir(), activeMapName, onExportZipRequested);
            });
        });
    }

    private static short[] intListToShortArray(List<Integer> list) {
        var arr = new short[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).shortValue();
        }
        return arr;
    }

    // ==================================================================
    // FILE IMPORT via Storage Access Framework
    // ==================================================================

    private void requestFileImport() {
        if (onImportRequested != null) {
            onImportRequested.run();
        } else {
            toast(getContext().getString(R.string.toast_import_not_available));
        }
    }

    /**
     * Copies files from SAF content URIs into the working assets directory.
     * ZIP files are automatically extracted into the current working directory.
     * Runs on a background thread. On completion posts a Toast summary.
     */
    public void importFilesFromUris(java.util.List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;

        backgroundExecutor.execute(() -> {
            int successCount = 0;
            int failCount = 0;
            android.content.ContentResolver resolver = context.getContentResolver();

            for (Uri uri : uris) {
                // Derive a filename from the URI
                String fileName = resolveFileName(resolver, uri);
                var dest = new File(workingAssetsDir(), fileName);

                // Detect and handle ZIP archives
                if (fileName.toLowerCase().endsWith(".zip")) {
                    try {
                        MapManager.extractZipToDirectory(
                                context, uri, workingAssetsDir(), resolver);
                        successCount++;
                    } catch (Exception e) {
                        failCount++;
                    }
                    continue;
                }

                try (InputStream in = resolver.openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    if (in == null) { failCount++; continue; }
                    var buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }

            final int sc = successCount;
            final int fc = failCount;
            post(() -> {
                if (fc == 0) {
                    toast(getContext().getString(R.string.toast_imported, sc));
                } else {
                    toast(getContext().getString(R.string.toast_imported_with_fails, sc, fc));
                }

                // Reload background so any imported bg.png or map data takes effect.
                // Must run on the UI thread because loadBackground() modifies Bitmap and
                // CameraState fields that onDraw() reads without synchronization.
                if (sc > 0) {
                    loadBackground();
                    postInvalidateOnAnimation();
                }
            });
        });
    }

    private static String resolveFileName(android.content.ContentResolver resolver, Uri uri) {
        String name = "imported_file";
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        String n = cursor.getString(idx);
                        if (n != null && !n.isEmpty()) name = n;
                    }
                }
            }
        }
        if (name.equals("imported_file")) {
            String path = uri.getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0) name = path.substring(slash + 1);
            }
        }
        return name;
    }

    // ASSET LOADING  (was: FileReader("...") on disk)
    // ==================================================================

    private void loadBackground() {
        var opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;

        var bg = new File(workingAssetsDir(), DEFAULT_BACKGROUND_IMAGE);
        if (bg.isFile()) {
            try (InputStream is = new java.io.FileInputStream(bg)) {
                mapEditorBackground = BitmapFactory.decodeStream(is, null, opts);
            } catch (IOException e) {
                mapEditorBackground = null;
            }
        }

        if (mapEditorBackground != null) {
            camera.iMapEditorBackgroundWidth = mapEditorBackground.getWidth();
            camera.iMapEditorBackgroundHeight = mapEditorBackground.getHeight();
        } else {
            camera.iMapEditorBackgroundWidth = camera.GWIDTH;
            camera.iMapEditorBackgroundHeight = camera.GHEIGHT;
        }
    }

    /** assets/config_editor.txt - tiny config; no city/mountain I/O. */
    private void parseConfigFile() throws IOException {
        try (InputStream is = assets.open(configFile);
             var bfr = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String sLine;
            while ((sLine = bfr.readLine()) != null) {
                String[] tempR = sLine.split("=");
                try {
                    if (tempR[0].equals("IMAGE_PATH")) {
                        sBackgroundImage = resolveEditorDataPath(tempR[1]);
                    } else if (tempR[0].equals("MAP_DATA_PATH")) {
                        // Спосіб 2 (альтернативний): дозволяє перевизначити ім'я
                        // комбінованого файлу провінцій (наприклад mapAoC2_v2.txt)
                        // замість жорстко заданого DEFAULT_LEGACY_MAP_DATA_FILE.
                        // "Спосіб 1" (окремі файли 0,1,2,...) як і раніше в пріоритеті.
                        String mapDataValue = tempR[1].trim();
                        if (!mapDataValue.isEmpty()) {
                            legacyMapDataFile = resolveEditorDataPath(mapDataValue);
                        }
                    } else if (tempR[0].equals("WIDTH")) {
                        camera.GWIDTH = Integer.parseInt(tempR[1]);
                    } else if (tempR[0].equals("HEIGHT")) {
                        camera.GHEIGHT = Integer.parseInt(tempR[1]);
                    } else if (tempR[0].equals("MAXIMUM_SIZE")) {
                        editor.MAXIMUM_SIZE = Boolean.parseBoolean(tempR[1]);
                    } else if (tempR[0].equals("DRAW_POINTS")) {
                        editor.bDrawProvincePoints = Boolean.parseBoolean(tempR[1]);
                    } else if (tempR[0].equals("DRAW_ARMY")) {
                        editor.bDrawArmy = Boolean.parseBoolean(tempR[1]);
                    }
                } catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
                }
            }
        } catch (IOException e) {
            // matches original: missing config is non-fatal, defaults are used
        }
        // NOTE: loadCities()/loadMountains() are intentionally NOT called here
        // anymore - see loadCitiesAndMountainsAsync() below.
    }

    /**
     * Loads the ~4269 cities and the mountains JSON on a background thread,
     * then swaps them into the live lists on the UI thread and redraws.
     *
     * Call this exactly once (e.g. from Activity.onCreate right after creating
     * the GamePanel) - it guards against re-entry itself, but was previously
     * possible to trigger twice (once from GamePanel's constructor via
     * loadConfigFile(), once more manually), which silently duplicated every
     * city/mountain point and - worse - mutated the shared lists from a
     * background thread while onDraw() was reading them on the UI thread.
     * Building into local temp lists and swapping the reference on the UI
     * thread (via post()) avoids that race entirely.
     */
    public void loadCitiesAndMountainsAsync() {
        if (citiesLoaded) return;
        citiesLoaded = true;

        backgroundExecutor.execute(() -> {
            List<String> tCityNames = new ArrayList<>();
            List<Integer> tCityX = new ArrayList<>();
            List<Integer> tCityY = new ArrayList<>();
            loadCities(tCityNames, tCityX, tCityY);

            List<String> tMountainNames = new ArrayList<>();
            List<Integer> tMountainX = new ArrayList<>();
            List<Integer> tMountainY = new ArrayList<>();
            loadMountains(tMountainNames, tMountainX, tMountainY);

            post(() -> {
                citiesNames = tCityNames;
                citiesX = tCityX;
                citiesY = tCityY;
                mountainsNames = tMountainNames;
                mountainsX = tMountainX;
                mountainsY = tMountainY;
                postInvalidateOnAnimation();
            });
        });
    }

    /**
     * assets/editor_data/cities.json, cities_1.json .. cities_4.json.
     * Expected shape: a JSON array of objects, each with "Name", "x", "y".
     * (Original desktop code hand-parsed loosely-formatted text looking for
     * those same three tokens; now that real JSON is available we parse it
     * properly with org.json instead of scanning characters.)
     */
    private void loadCities(List<String> outNames, List<Integer> outX, List<Integer> outY) {
        for (String fileName : CITY_FILES) {
            var file = new File(workingAssetsDir(), fileName);
            if (!file.exists()) continue;
            try (InputStream is = new java.io.FileInputStream(file)) {
                String json = readFully(is);
                var arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String name = o.optString("Name", "");
                    int x = o.optInt("x", o.optInt("X", 0));
                    int y = o.optInt("y", o.optInt("Y", 0));
                    outX.add(x);
                    outY.add(y);
                    outNames.add(name);
                }
            } catch (IOException | JSONException e) {
                // відсутній або пошкоджений файл — пропускаємо
            }
        }
    }

    /** editor_data/mountains.json - same array-of-objects shape as cities. */
    private void loadMountains(List<String> outNames, List<Integer> outX, List<Integer> outY) {
        var file = new File(workingAssetsDir(), MOUNTAINS_FILE);
        if (!file.exists()) return;
        try (InputStream is = new java.io.FileInputStream(file)) {
            String json = readFully(is);
            var arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                outNames.add(o.optString("Name", ""));
                outX.add(o.optInt("x", o.optInt("X", 0)));
                outY.add(o.optInt("y", o.optInt("Y", 0)));
            }
        } catch (IOException | JSONException e) {
            // опціональний ресурс — якщо відсутній або пошкоджений, ігноруємо
        }
    }

    private static String readFully(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        var buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /**
     * Loads province files from files/assets/0, files/assets/1, files/assets/2...
     * Each file has no extension and normally contains one line:
     * X1,X2,...;Y1,Y2,...
     */
    private void loadProvincesFromFile() throws IOException {
        provinceData.clearAll();
        editor.lSea = new ArrayList<>();
        editor.lContinent = new ArrayList<>();

        // Спосіб 1 (основний, поточний Android-формат): кожна провінція -
        // окремий файл "0", "1", "2", ... в робочій asset-директорії.
        boolean loaded = loadProvincesFromNumberedFiles();

        // Спосіб 2 (альтернативний, legacy): один комбінований файл
        // (наприклад mapAoC2_v2.txt) у форматі оригінального desktop-редактора -
        // кожна провінція записана як пара рядків (X через кому, Y через кому)
        // одна за одною до кінця файлу. Використовується лише як fallback,
        // якщо спосіб 1 не знайшов жодної провінції.
        if (!loaded) {
            loaded = loadProvincesFromLegacyCombinedFile();
        }

        mapLoadPercent = 90;
        buildProvinceColors();
        setLoadPercent(97);
        provinceData.recomputeProvinceBounds(camera);
        int loadedCount = provinceData.vProvinces.size();
        while (editor.lSea.size() < loadedCount) editor.lSea.add(false);
        while (editor.lContinent.size() < loadedCount) editor.lContinent.add(0);
        loadSeaFlags();
        loadContinentFlags();
        setLoadPercent(100);
    }

    /** Спосіб 1: файли "0", "1", "2", ... по одному на провінцію. */
    private boolean loadProvincesFromNumberedFiles() throws IOException {
        File root = workingAssetsDir();
        File[] provinceFiles = root.listFiles((dir, name) -> name.matches("\\d+"));

        // Same map in the game's own layout (e.g. an exported/unzipped map): data/provinces/<id>
        if ((provinceFiles == null || provinceFiles.length == 0)) {
            var nested = new File(root, "data/provinces");
            File[] nestedFiles = nested.isDirectory() ? nested.listFiles((dir, name) -> name.matches("\\d+")) : null;
            if (nestedFiles != null && nestedFiles.length > 0) provinceFiles = nestedFiles;
        }

        if (provinceFiles != null && provinceFiles.length > 0) {
            Arrays.sort(provinceFiles, Comparator.comparingInt(f -> Integer.parseInt(f.getName())));
            for (int i = 0; i < provinceFiles.length; i++) {
                loadSingleProvinceFromDisk(provinceFiles[i]);
                setLoadPercent(25 + Math.min(62, i * 62 / Math.max(1, provinceFiles.length)));
            }
            return !provinceData.vProvinces.isEmpty();
        }

        // NEW consolidated format: provinces.pack (root or data/)
        var pack = new File(root, "provinces.pack");
        if (!pack.isFile()) pack = new File(root, "data/provinces.pack");
        if (pack.isFile()) {
            try (ProvincePackReader reader = new ProvincePackReader(pack)) {
                int total = reader.size();
                for (int i = 0; i < total; i++) {
                    byte[] blob = reader.read(i);
                    if (blob == null) continue;
                    ProvinceDecoder.DecodedProvince dec = ProvinceDecoder.decodeProvince(new java.io.ByteArrayInputStream(blob));
                    if (dec == null) continue;
                    int newId = provinceData.vProvinces.size();
                    provinceData.addProvinceFromDecoded(dec);
                    while (editor.lSea.size() <= newId) editor.lSea.add(false);
                    editor.lSea.set(newId, dec.levelOfPort < -1);
                    while (editor.lContinent.size() <= newId) editor.lContinent.add(0);
                    editor.lContinent.set(newId, dec.continentId);
                    setLoadPercent(25 + Math.min(62, i * 62 / Math.max(1, total)));
                }
            }
        }

        return !provinceData.vProvinces.isEmpty();
    }

    private boolean loadSingleProvinceFromDisk(File file) throws IOException {
        if (ProvinceDecoder.isJavaSerialized(file)) {
            ProvinceDecoder.DecodedProvince dec = ProvinceDecoder.decodeProvince(file);
            if (dec != null) {
                int newId = provinceData.vProvinces.size();
                provinceData.addProvinceFromDecoded(dec);
                // Preserve sea/continent that the binary file already carried
                // (e.g. re-opening a project exported by this same editor, or
                // a map that shipped with the real game's data). sea_info.json
                // / continents_info.json, loaded afterwards, still win if present.
                while (editor.lSea.size() <= newId) editor.lSea.add(false);
                editor.lSea.set(newId, dec.levelOfPort < -1);
                while (editor.lContinent.size() <= newId) editor.lContinent.add(0);
                editor.lContinent.set(newId, dec.continentId);
                return true;
            }
        } else {
            List<String> lines = readNonEmptyLines(file);
            if (!lines.isEmpty()) {
                provinceData.addProvinceFromLines(lines);
                return true;
            }
        }
        return false;
    }


    /**
     * Спосіб 2 (альтернативний): один комбінований файл, наприклад mapAoC2_v2.txt,
     * розміщений у робочій asset-директорії (workingAssetsDir()) - тобто те саме
     * місце, куди копіюються бандлені assets/editor_data/* файли. Ім'я файлу можна
     * перевизначити через MAP_DATA_PATH в config_editor.txt.
     */
    private boolean loadProvincesFromLegacyCombinedFile() {
        File legacyFile = workingAssetFile(legacyMapDataFile);
        if (!legacyFile.isFile()) {
            return false;
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new java.io.FileInputStream(legacyFile),
                        StandardCharsets.UTF_8))) {
            String xLine;
            while ((xLine = br.readLine()) != null) {
                xLine = xLine.trim();
                if (xLine.isEmpty()) continue;

                String yLine = br.readLine();
                if (yLine == null) break;
                yLine = yLine.trim();

                provinceData.addProvinceFromLines(Arrays.asList(xLine, yLine));
            }
        } catch (IOException e) {
            return false;
        }

        return !provinceData.vProvinces.isEmpty();
    }

    private List<String> readNonEmptyLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new java.io.FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Парсить одну провінцію з рядків формату "X1,X2,X3..." + "Y1,Y2,Y3..."
     * (або одного рядка "X1,X2,...;Y1,Y2,...") і додає її у provinceData.vProvinces /
     * provinceData.lProvincesPointsX / provinceData.lProvincesPointsY. Спільна логіка для обох способів
     * завантаження.
     */

    /** Рахує bounding box / центр для кожної завантаженої провінції. */

    private void ensureWorkingEditorData() throws IOException {
        File target = workingAssetsDir();
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException(getContext().getString(R.string.toast_save_failed, target));
        }

        // Copy only missing files so edits survive app restarts.
        copyAssetTreeIfMissing(SOURCE_EDITOR_DATA_DIR, target);
    }

    /**
     * Returns the on-device working directory for the active map.
     * Path: {@code <external-files>/maps/<activeMapName>/}
     */
    private File workingAssetsDir() {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            return new File(external, "maps/" + activeMapName);
        }
        return new File(context.getFilesDir(), "maps/" + activeMapName);
    }

    private File workingAssetFile(String relativePath) {
        return new File(workingAssetsDir(), relativePath);
    }

    private String resolveEditorDataPath(String path) {
        if (path == null || path.trim().isEmpty()) return DEFAULT_BACKGROUND_IMAGE;
        path = path.trim();
        String prefix = SOURCE_EDITOR_DATA_DIR + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private void copyAssetTreeIfMissing(String assetDir, File targetDir) throws IOException {
        String[] children = assets.list(assetDir);
        if (children == null) return;

        for (String child : children) {
            String assetPath = assetDir + "/" + child;
            var target = new File(targetDir, child);

            String[] nested = assets.list(assetPath);
            if (nested != null && nested.length > 0) {
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("Failed to create " + target);
                }
                copyAssetTreeIfMissing(assetPath, target);
            } else {
                copyAssetFileIfMissing(assetPath, target);
            }
        }
    }

    private void copyAssetFileIfMissing(String assetPath, File target) throws IOException {
        if (target.exists()) return;

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create " + parent);
        }

        try (InputStream in = assets.open(assetPath);
             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            var buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private void zipDirectoryToZip(File dir, ZipOutputStream zos, String prefix)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;

        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            String entry = prefix + child.getName();
            if (child.isDirectory()) {
                zipDirectoryToZip(child, zos, entry + "/");
            } else {
                zos.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new java.io.FileInputStream(child)) {
                    var buffer = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) zos.write(buffer, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    // ==================================================================
    // SAVING
    //
    // The working map is files/assets/0, files/assets/1, files/assets/2...
    // with no extension and no Provinces subdirectory.
    // ==================================================================

    private File stateDir() {
        var dir = new File(context.getFilesDir(), STATE_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void saveProv() {
        var f = new File(stateDir(), SAVE_PROV_FILE);
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            writeCoordinates(bw, provinceData.currentPointsX, provinceData.currentPointsY, ";");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveProvinces() throws IOException {
        saveProv();

        List<List<Integer>> allX = new ArrayList<>(provinceData.lProvincesPointsX);
        List<List<Integer>> allY = new ArrayList<>(provinceData.lProvincesPointsY);

        if (provinceData.currentProvincePoints > 1 && provinceData.currentPointsX.size() > 1) {
            allX.add(new ArrayList<>(provinceData.currentPointsX));
            allY.add(new ArrayList<>(provinceData.currentPointsY));
        }

        writeProvinceFiles(allX, allY);
    }

    private void writeProvinceFiles(
            List<List<Integer>> allX,
            List<List<Integer>> allY) throws IOException {
        writeProvinceFiles(allX, allY, null);
    }

    /**
     * The preserved per-province data (terrain, growth rate, region, shifts, port data, old
     * connections) for the provinces about to be written, in OUTPUT order. Connection ids are
     * renumbered from editor ids to output ids (links to provinces that are not written are dropped).
     *
     * @param sourceIds output index -> editor id (null = identity)
     */
    private List<ProvinceExtra> outputExtras(int outCount, List<Integer> sourceIds) {
        java.util.Map<Integer, Integer> idMap = new java.util.HashMap<>();
        for (int i = 0; i < outCount; i++) {
            int src = (sourceIds != null && i < sourceIds.size()) ? sourceIds.get(i) : i;
            idMap.put(src, i);
        }

        List<ProvinceExtra> out = new ArrayList<>(outCount);
        for (int i = 0; i < outCount; i++) {
            int src = (sourceIds != null && i < sourceIds.size()) ? sourceIds.get(i) : i;
            ProvinceExtra e = (src >= 0 && src < provinceData.lExtras.size())
                    ? provinceData.lExtras.get(src).copy() : new ProvinceExtra();
            e.remapLinks(idMap);
            out.add(e);
        }
        return out;
    }

    /**
     * @param sourceIds for each output province, the id it had in the editor (used to look up its
     *                  sea / continent flags); null = identity (output index == editor index)
     */
    private void writeProvinceFiles(
            List<List<Integer>> allX,
            List<List<Integer>> allY,
            List<Integer> sourceIds) throws IOException {

        File root = workingAssetsDir();
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Failed to create " + root);
        }

        var tempDir = new File(context.getFilesDir(), "province_save_tmp");
        deleteRecursive(tempDir);
        if (!tempDir.mkdirs()) {
            throw new IOException("Failed to create " + tempDir);
        }

        // Full data, not just outlines: neighbors, shared borders, sea/land and continents
        // (previously only the outline was written, so every saved/exported map came out
        // with no neighbors, no borders and no sea provinces).
        List<Boolean> seaOut = new ArrayList<>(allX.size());
        List<Integer> contOut = new ArrayList<>(allX.size());
        for (int i = 0; i < allX.size(); i++) {
            int src = (sourceIds != null && i < sourceIds.size()) ? sourceIds.get(i) : i;
            seaOut.add(src >= 0 && src < editor.lSea.size() && Boolean.TRUE.equals(editor.lSea.get(src)));
            contOut.add(src >= 0 && src < editor.lContinent.size() ? editor.lContinent.get(src) : 0);
        }
        reportSave(3);
        List<ProvinceDecoder.DecodedProvince> built = ProvinceExportBuilder.build(
                allX, allY, seaOut, contOut, outputExtras(allX.size(), sourceIds),
                camera.iMapEditorBackgroundWidth, 2, true);
        reportSave(25);

        // The scale masks are made from exactly these outlines and neighbor lists. The generator
        // copies the coordinates now, so later edits cannot leak into the masks.
        pendingScaleGen = null;
        if (!built.isEmpty()) {
            List<List<Integer>> mainNeighbors = new ArrayList<>(built.size());
            for (ProvinceDecoder.DecodedProvince d : built) mainNeighbors.add(d.neighboringProvinces);
            pendingScaleGen = new ScaleMaskGenerator(allX, allY, mainNeighbors);
            pendingScaleCount = built.size();
        }

        int lastEncodePct = -1;
        for (int i = 0; i < built.size(); i++) {
            ProvinceDecoder.encodeProvince(built.get(i), new File(tempDir, String.valueOf(i)));
            int pct = 25 + (i + 1) * 20 / built.size();
            if (pct != lastEncodePct) {
                lastEncodePct = pct;
                reportSave(pct);
            }
        }

        // Never replace a map with an empty one - that is how a whole map used to disappear.
        if (built.isEmpty()) {
            deleteRecursive(tempDir);
            throw new IOException("No provinces to save - existing map files were kept");
        }

        // Swap in two safe steps: old files are MOVED to a backup folder (not deleted), the new
        // ones are moved in, and only when everything succeeded is the backup removed. If anything
        // fails half way, the old files are put back.
        var backupDir = new File(context.getFilesDir(), "province_backup_tmp");
        deleteRecursive(backupDir);
        if (!backupDir.mkdirs()) {
            throw new IOException("Failed to create " + backupDir);
        }

        File[] old = root.listFiles((dir, name) -> name.matches("\\d+"));
        File[] fresh = tempDir.listFiles();
        try {
            if (old != null) {
                for (File f : old) {
                    var saved = new File(backupDir, f.getName());
                    if (!f.renameTo(saved)) {
                        copyFile(f, saved);
                        if (!f.delete()) throw new IOException("Failed to replace old file " + f.getName());
                    }
                }
            }
            if (fresh != null) {
                for (File f : fresh) {
                    var dest = new File(root, f.getName());
                    if (!f.renameTo(dest)) {
                        copyFile(f, dest);
                        if (!f.delete()) {
                            throw new IOException("Failed to clean up " + f);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // roll back: drop whatever new files made it, put the old ones back
            File[] partial = root.listFiles((dir, name) -> name.matches("\\d+"));
            if (partial != null) for (File f : partial) f.delete();
            File[] saved = backupDir.listFiles();
            if (saved != null) {
                for (File f : saved) {
                    var dest = new File(root, f.getName());
                    if (!f.renameTo(dest)) {
                        try { copyFile(f, dest); } catch (IOException ignored) { }
                    }
                }
            }
            deleteRecursive(tempDir);
            deleteRecursive(backupDir);
            throw e;
        }

        deleteRecursive(backupDir);
        deleteRecursive(tempDir);
        reportSave(48);
    }

    private void writeCoordinates(
            BufferedWriter bw,
            List<Integer> xs,
            List<Integer> ys,
            String separator) throws IOException {

        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) bw.write(",");
            bw.write(Integer.toString(xs.get(i)));
        }
        bw.write(separator);

        for (int i = 0; i < ys.size(); i++) {
            if (i > 0) bw.write(",");
            bw.write(Integer.toString(ys.get(i)));
        }
    }

    /** editor_data/sea_info.json - array of province indices that are sea. */
    private void saveSeaFlags() throws IOException {
        File root = workingAssetsDir();
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Failed to create " + root);
        }

        List<Integer> order = new ArrayList<>();
        if (editor.bNewOrderOfProvinces) {
            order = newOrderOutputIds();
        } else {
            for (int i = 0; i < editor.lSea.size(); i++) order.add(i);
        }

        var arr = new JSONArray();
        for (int i = 0; i < order.size() && i < editor.lSea.size(); i++) {
            int srcId = order.get(i);
            if (srcId >= 0 && srcId < editor.lSea.size() && editor.lSea.get(srcId)) {
                arr.put(i);
            }
        }

        var out = new File(root, SEA_FILE);
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            bw.write(arr.toString());
        }
    }

    private void loadSeaFlags() {
        var file = new File(workingAssetsDir(), SEA_FILE);
        if (!file.exists()) return;
        try {
            String json = readNonEmptyLines(file).get(0);
            var arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                int idx = arr.optInt(i, -1);
                if (idx >= 0 && idx < editor.lSea.size()) {
                    editor.lSea.set(idx, true);
                }
            }
        } catch (Exception e) {
            // ignore malformed sea_info.json
        }
    }

    /**
     * continents_info.json - {"provinceIndex": continentId, ...} for every
     * province whose continent isn't the default (0). Mirrors sea_info.json's
     * approach; previously this editor had no persistence for continent
     * assignments at all (SetContinentCommand only updated in-memory state),
     * so re-opening a project silently lost every continent the user painted.
     */
    private void saveContinentFlags() throws IOException {
        File root = workingAssetsDir();
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Failed to create " + root);
        }

        List<Integer> order = new ArrayList<>();
        if (editor.bNewOrderOfProvinces) {
            order = newOrderOutputIds();
        } else {
            for (int i = 0; i < editor.lContinent.size(); i++) order.add(i);
        }

        var obj = new JSONObject();
        for (int i = 0; i < order.size() && i < editor.lContinent.size(); i++) {
            int srcId = order.get(i);
            if (srcId < 0 || srcId >= editor.lContinent.size()) continue;
            int continentId = editor.lContinent.get(srcId);
            if (continentId != 0) {
                try {
                    obj.put(String.valueOf(i), continentId);
                } catch (JSONException ignored) {
                }
            }
        }

        var out = new File(root, CONTINENTS_FILE);
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            bw.write(obj.toString());
        }
    }

    private void loadContinentFlags() {
        var file = new File(workingAssetsDir(), CONTINENTS_FILE);
        if (!file.exists()) return;
        try {
            String json = readNonEmptyLines(file).get(0);
            var obj = new JSONObject(json);
            var keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int idx = Integer.parseInt(key);
                if (idx >= 0 && idx < editor.lContinent.size()) {
                    editor.lContinent.set(idx, obj.optInt(key, 0));
                }
            }
        } catch (Exception e) {
            // ignore malformed continents_info.json
        }
    }

    /** Province ids in the order they are written in "new order" mode: picked ones first, ALL others after them. */
    private List<Integer> newOrderOutputIds() {
        return SaveOrder.outputOrder(provinceData.lNewOrderOfPorivnces_IDs, provinceData.lProvincesPointsX.size());
    }

    public void saveNewOrderOfProvinces() throws IOException {
        List<Integer> outIds = newOrderOutputIds();
        int nSize = outIds.size();
        List<List<Integer>> orderedX = new ArrayList<>(nSize);
        List<List<Integer>> orderedY = new ArrayList<>(nSize);
        List<Integer> sourceIds = new ArrayList<>(nSize);

        for (int i = 0; i < nSize; i++) {
            int id = outIds.get(i);
            orderedX.add(new ArrayList<>(provinceData.lProvincesPointsX.get(id)));
            orderedY.add(new ArrayList<>(provinceData.lProvincesPointsY.get(id)));
            sourceIds.add(id);
        }

        writeProvinceFiles(orderedX, orderedY, sourceIds);
    }

    private static void copyFile(File source, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create " + parent);
        }

        try (InputStream in = new java.io.FileInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            var buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // shutdown(), not shutdownNow(): leaving the screen right after "Save" must not interrupt the save
        backgroundExecutor.shutdown();
        if (mapEditorBackground != null && !mapEditorBackground.isRecycled()) {
            mapEditorBackground.recycle();
            mapEditorBackground = null;
        }
    }

}

// EditorCommand, SetContinentCommand → moved to their own files
