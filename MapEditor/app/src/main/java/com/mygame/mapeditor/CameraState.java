package com.mygame.mapeditor;

public class CameraState {

    public static final float MIN_ZOOM = 0.18f;
    public static final float MAX_ZOOM = 50.00f;

    public int GWIDTH = 1920;
    public int GHEIGHT = 1040;
    public int iMapEditorBackgroundWidth = 1920;
    public int iMapEditorBackgroundHeight = 1040;

    public int iXMapPos = 0;
    public int iYMapPos = 0;
    public float fMapScale = 1.0f;
    public int iShiftXY = 15;

    public int screenToMapX(float screenX) {
        return Math.round(iXMapPos + screenX / fMapScale);
    }

    public int screenToMapY(float screenY) {
        return Math.round(iYMapPos + screenY / fMapScale);
    }

    public void zoomAt(float screenX, float screenY, float multiplier, int viewW, int viewH) {
        final float oldScale = fMapScale;
        float newScale = oldScale * multiplier;
        if (newScale < MIN_ZOOM) newScale = MIN_ZOOM;
        if (newScale > MAX_ZOOM) newScale = MAX_ZOOM;
        if (Math.abs(newScale - oldScale) < 0.001f) return;

        final float mapX = iXMapPos + screenX / oldScale;
        final float mapY = iYMapPos + screenY / oldScale;

        fMapScale = newScale;
        iXMapPos = Math.round(mapX - screenX / newScale);
        iYMapPos = Math.round(mapY - screenY / newScale);
        clampYPosition(viewW, viewH);
    }

    public void zoomStepAt(float screenX, float screenY, boolean zoomIn, int viewW, int viewH) {
        zoomAt(screenX, screenY, zoomIn ? 1.18f : 0.847f, viewW, viewH);
    }

    public void applyPinchZoom(float focusX, float focusY, float smoothFactor, int viewW, int viewH) {
        final float oldScale = fMapScale;
        float newScale = oldScale * smoothFactor;
        if (newScale < MIN_ZOOM) newScale = MIN_ZOOM;
        if (newScale > MAX_ZOOM) newScale = MAX_ZOOM;

        if (newScale != oldScale) {
            final float mapX = iXMapPos + focusX / oldScale;
            final float mapY = iYMapPos + focusY / oldScale;
            fMapScale = newScale;
            iXMapPos = Math.round(mapX - focusX / newScale);
            iYMapPos = Math.round(mapY - focusY / newScale);
            clampYPosition(viewW, viewH);
        }
    }

    public void clampMapPosition(int viewW, int viewH) {
        if (iMapEditorBackgroundWidth <= 0 || iMapEditorBackgroundHeight <= 0) return;

        final int worldW = iMapEditorBackgroundWidth;
        final int worldH = iMapEditorBackgroundHeight;
        final int viewMapH = Math.max(1, Math.round(viewH / fMapScale));

        iXMapPos %= worldW;
        if (iXMapPos < 0) iXMapPos += worldW;

        final int maxY = Math.max(0, worldH - viewMapH);
        if (iYMapPos < 0) iYMapPos = 0;
        if (iYMapPos > maxY) iYMapPos = maxY;
    }

    /**
     * Clamps only the Y axis. Use during drag gestures so that horizontal
     * scrolling does not snap back from the X modulo wrap.
     */
    public void clampYPosition(int viewW, int viewH) {
        if (iMapEditorBackgroundHeight <= 0) return;
        final int worldH = iMapEditorBackgroundHeight;
        final int viewMapH = Math.max(1, Math.round(viewH / fMapScale));
        final int maxY = Math.max(0, worldH - viewMapH);
        if (iYMapPos < 0) iYMapPos = 0;
        if (iYMapPos > maxY) iYMapPos = maxY;
    }
}
