package com.mygame.mapeditor;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MapManager {

    /** Shared throttling state for the TXT-import progress bar. */
    private static final class Throttle {
        long lastTime = 0;
        int lastPercent = -1;
    }

    private static final String PREF_NAME = "MapEditorPrefs";
    private static final String ACTIVE_MAP = "ACTIVE_MAP";
    private static final String MAPS_DIR = "maps";

    private final Context context;
    private final SharedPreferences prefs;

    public interface ProgressReporter {
        default void onProgressUpdate(int percent) {
        }
    }

    public interface ZipImportCallback extends ProgressReporter {
        void onZipImportSuccess(String mapName);
        void onZipImportError(String errorMessage);
    }

    public interface TxtImportCallback extends ProgressReporter {
        void onTxtImportSuccess(String mapName);
        void onTxtImportError(String errorMessage);
    }

    public interface DeleteMapCallback {
        void onDeleteSuccess(String mapName);
        void onDeleteError(String errorMessage);
    }

    public MapManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the name of the currently active map, or null if none is selected. */
    public String getActiveMapName() {
        String name = prefs.getString(ACTIVE_MAP, null);
        if (name != null && name.isEmpty()) name = null;
        return name;
    }

    /** Persists the active map name. */
    public void setActiveMapName(String name) {
        prefs.edit().putString(ACTIVE_MAP, name).apply();
    }

    /**
     * Folder of the generated scale masks of a map: {@code <external-files>/scales/<mapName>/provinces/<k>/<id>}.
     * Deliberately NOT inside the map folder. One definition for the editor (Save) and the map scaler.
     */
    public static File scalesDirectory(Context context, String mapName) {
        File ext = context.getExternalFilesDir(null);
        File base = ext != null ? ext : context.getFilesDir();
        return new File(base, "scales/" + mapName);
    }

    /** Returns the on-device directory for a given map under maps/. */
    public File getMapDirectory(String mapName) {
        return new File(getMapsBaseDir(), mapName);
    }

    /** Scans the on-device maps/ directory and returns a sorted list of subdirectory names (maps). */
    public List<String> discoverMaps() {
        List<String> maps = new ArrayList<>();
        File mapsBase = getMapsBaseDir();
        if (mapsBase.isDirectory()) {
            File[] dirs = mapsBase.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    maps.add(dir.getName());
                }
            }
        }
        Collections.sort(maps);
        return maps;
    }

    /**
     * Asynchronously deletes a map folder and all its contents.
     * If the deleted map is the currently active map, ACTIVE_MAP is cleared.
     * Runs on a background thread; callback is invoked on the main thread.
     */
    public void deleteMapAsync(String mapName, DeleteMapCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File mapDir = getMapDirectory(mapName);
                if (!mapDir.exists()) {
                    throw new Exception("Map directory not found: " + mapName);
                }
                deleteRecursive(mapDir);

                String active = getActiveMapName();
                if (active != null && active.equals(mapName)) {
                    prefs.edit().remove(ACTIVE_MAP).apply();
                }

                new Handler(Looper.getMainLooper()).post(() -> callback.onDeleteSuccess(mapName));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onDeleteError(e.getMessage()));
            }
        });
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    /**
     * Returns the base directory for ZIP-extracted maps.
     * Path: {@code <external-files>/maps/}
     */
    public File getMapsBaseDir() {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            return new File(external, MAPS_DIR);
        }
        return new File(context.getFilesDir(), MAPS_DIR);
    }

    /**
     * Asynchronously extracts a ZIP archive from the given content URI into
     * {@code <external-files>/maps/<zip-name-without-extension>/}.
     * Progress is reported via {@link ZipImportCallback#onProgressUpdate(int)}.
     * The extraction runs on a background thread; callbacks are invoked on
     * the main thread.
     */
    public void importZipArchive(Uri zipUri, ContentResolver resolver, ZipImportCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String mapName = doImportZipWithProgress(zipUri, resolver, callback);
                new Handler(Looper.getMainLooper()).post(() -> callback.onZipImportSuccess(mapName));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onZipImportError(e.getMessage()));
            }
        });
    }

    /**
     * Imports a province text file (alternating X-lines and Y-lines, comma separated)
     * as a new map. Each matching pair of lines becomes one province, written to a
     * numbered file (0, 1, 2, ...) in {@code maps/<mapFolderName>/} - the same format
     * the editor loads natively. The map folder takes its name from the text file.
     * Runs on a background thread; callbacks are invoked on the main thread.
     */
    public void importTxtMap(Uri txtUri, ContentResolver resolver, String mapFolderName,
                             TxtImportCallback callback) {
        importTxtMap(txtUri, resolver, mapFolderName, null, callback);
    }

    /**
     * Imports a text map, optionally using the user-picked background image
     * ({@code backgroundUri}) instead of the bundled default bg.png. Pass
     * {@code null} to fall back to the default background.
     */
    public void importTxtMap(Uri txtUri, ContentResolver resolver, String mapFolderName,
                             Uri backgroundUri, TxtImportCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                doImportTxt(txtUri, resolver, mapFolderName, backgroundUri, callback);
                new Handler(Looper.getMainLooper()).post(() -> callback.onTxtImportSuccess(mapFolderName));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onTxtImportError(e.getMessage()));
            }
        });
    }

    private void doImportTxt(Uri txtUri, ContentResolver resolver, String mapFolderName,
                             Uri backgroundUri, TxtImportCallback callback) throws Exception {
        var targetDir = new File(getMapsBaseDir(), mapFolderName);
        List<String> lines = new ArrayList<>();
        try (InputStream in = resolver.openInputStream(txtUri);
             var br = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (in == null) throw new Exception("Could not open the selected file");
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
        }

        if (lines.size() < 2) {
            throw new Exception("The text file has no province data (need X/Y coordinate lines)");
        }

        deleteRecursive(targetDir);
        if (!targetDir.mkdirs()) {
            throw new Exception("Could not create map directory: " + targetDir);
        }

        var throttle = new Throttle();
        if (backgroundUri != null) {
            copyUserBackground(resolver, backgroundUri, targetDir, callback, throttle);
        } else {
            copyDefaultBackground(targetDir);
            reportTxtProgress(throttle, callback, 5);
        }

        int provinceCount = 0;
        try {
            int expectedProvinces = Math.max(1, (lines.size() + 1) / 2);
            for (int k = 0; k + 1 < lines.size(); k += 2) {
                writeProvinceFile(targetDir, provinceCount, lines.get(k), lines.get(k + 1));
                provinceCount++;
                if (callback != null) {
                    int pct = 10 + (int) Math.min(90L, (provinceCount * 90L) / expectedProvinces);
                    reportTxtProgress(throttle, callback, pct);
                }
            }
            reportTxtProgress(throttle, callback, 100);
        } catch (Exception e) {
            deleteRecursive(targetDir);
            throw e;
        }

        if (provinceCount == 0) {
            deleteRecursive(targetDir);
            throw new Exception("No valid province data was found in the text file");
        }
    }

    /**
     * Writes a single province file in the game's Java-serialized binary
     * format (Province_GameData2), so TXT-imported provinces are already
     * serialized like the game expects. Invalid/incomplete values are skipped.
     */
    private void writeProvinceFile(File dir, int index, String xLine, String yLine) throws IOException {
        List<Integer> xs = parseCoords(xLine);
        List<Integer> ys = parseCoords(yLine);
        int count = Math.min(xs.size(), ys.size());
        if (count < 2) return;

        var p = new ProvinceDecoder.DecodedProvince();
        p.pointsX = new short[xs.size()];
        p.pointsY = new short[ys.size()];
        for (int i = 0; i < xs.size(); i++) {
            int v = xs.get(i);
            p.pointsX[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
        for (int i = 0; i < ys.size(); i++) {
            int v = ys.get(i);
            p.pointsY[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }

        ProvinceDecoder.encodeProvince(p, new File(dir, String.valueOf(index)));
    }

    private List<Integer> parseCoords(String line) {
        List<Integer> result = new ArrayList<>();
        String[] parts = line.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                result.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    /**
     * If {@code f} is a numbered province file (name matches digits) holding
     * editor text format (X...;Y... or two CSV lines), rewrites it in the
     * game's Java-serialized binary format (Province_GameData2). Binary files
     * and non-province files are left untouched. Errors are ignored so a bad
     * file never breaks the ZIP extraction.
     */
    private static void serializeProvinceFileIfNeeded(File f) {
        if (!f.isFile()) return;
        String name = f.getName();
        if (!name.matches("\\d+")) return;
        try {
            if (ProvinceDecoder.isJavaSerialized(f)) return;

            List<String> lines = new ArrayList<>();
            try (InputStream in = new FileInputStream(f);
                 var br = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) lines.add(line);
                }
            }

            String xLine;
            String yLine;
            if (lines.size() == 1 && lines.get(0).contains(";")) {
                int idx = lines.get(0).indexOf(';');
                xLine = lines.get(0).substring(0, idx).trim();
                yLine = lines.get(0).substring(idx + 1).trim();
            } else if (lines.size() >= 2) {
                xLine = lines.get(0).trim();
                yLine = lines.get(1).trim();
            } else {
                return;
            }

            List<Integer> xs = parseCoordsStatic(xLine);
            List<Integer> ys = parseCoordsStatic(yLine);
            int count = Math.min(xs.size(), ys.size());
            if (count < 2) return;

            var p = new ProvinceDecoder.DecodedProvince();
            p.pointsX = new short[count];
            p.pointsY = new short[count];
            for (int i = 0; i < count; i++) {
                p.pointsX[i] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, xs.get(i)));
                p.pointsY[i] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, ys.get(i)));
            }
            ProvinceDecoder.encodeProvince(p, f);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static List<Integer> parseCoordsStatic(String line) {
        List<Integer> result = new ArrayList<>();
        String[] parts = line.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                result.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    /**
     * Copies the bundled background image (bg.png) into the map directory if
     * present, so an imported TXT map has a background to draw over.
     */
    private void copyDefaultBackground(File targetDir) {
        try {
            AssetManager am = context.getAssets();
            try (InputStream in = am.open("editor_data/bg.png");
                 var fos = new FileOutputStream(new File(targetDir, "bg.png"))) {
                var buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) fos.write(buffer, 0, n);
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Posts a progress update to the main thread, throttled to at most one
     * update per 100 ms (100% always reported).
     */
    private static void reportTxtProgress(Throttle throttle, TxtImportCallback callback, int pct) {
        if (callback == null) return;
        long now = System.currentTimeMillis();
        if (pct != throttle.lastPercent
                && (pct == 100 || now - throttle.lastTime >= 100)) {
            throttle.lastPercent = pct;
            throttle.lastTime = now;
            int reported = pct;
            new Handler(Looper.getMainLooper()).post(() -> callback.onProgressUpdate(reported));
        }
    }

    /**
     * Copies the user-picked background image (via content URI) into the map
     * directory as bg.png, so the imported TXT map uses the chosen image.
     * Reports copying progress on the 0-10% portion of the overall import bar.
     */
    private void copyUserBackground(ContentResolver resolver, Uri backgroundUri, File targetDir,
                                    TxtImportCallback callback, Throttle throttle)
            throws RuntimeException {
        long total = -1;
        try (android.database.Cursor cursor = resolver.query(backgroundUri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    total = cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }

        try (InputStream in = resolver.openInputStream(backgroundUri);
             var fos = new FileOutputStream(new File(targetDir, "bg.png"))) {
            if (in == null) throw new Exception("Could not open the selected background image");
            var buffer = new byte[16 * 1024];
            long copied = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                fos.write(buffer, 0, n);
                copied += n;
                if (callback != null && total > 0) {
                    int pct = (int) Math.min(10L, (copied * 10L) / total);
                    reportTxtProgress(throttle, callback, pct);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not copy the background image: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the map folder name from the URI, creates the target directory,
     * counts ZIP entries (first pass), then extracts with progress (second pass).
     */
    private String doImportZipWithProgress(Uri zipUri, ContentResolver resolver,
                                            ZipImportCallback callback) throws Exception {
        String fileName = resolveFileName(resolver, zipUri);
        if (fileName == null || fileName.isEmpty()) {
            throw new Exception("Could not resolve file name from URI");
        }

        String mapFolderName = fileName;
        if (mapFolderName.toLowerCase(Locale.US).endsWith(".zip")) {
            mapFolderName = mapFolderName.substring(0, mapFolderName.length() - 4);
        }
        if (mapFolderName.isEmpty()) {
            throw new Exception("Invalid ZIP file name (empty after removing extension)");
        }

        var targetDir = new File(getMapsBaseDir(), mapFolderName);

        int totalEntries = countZipEntries(zipUri, resolver);
        if (totalEntries == 0) {
            throw new Exception("ZIP archive is empty");
        }

        deleteRecursive(targetDir);
        try {
            extractZipToDirectory(context, zipUri, targetDir, resolver, totalEntries, callback);
        } catch (IOException e) {
            deleteRecursive(targetDir);
            throw e;
        }
        return mapFolderName;
    }

    /**
     * Counts the number of entries in a ZIP archive (first pass).
     */
    private static int countZipEntries(Uri zipUri, ContentResolver resolver) throws IOException {
        int count = 0;
        try (InputStream in = resolver.openInputStream(zipUri);
             var zis = new ZipInputStream(in)) {
            if (in == null) throw new IOException("Could not open input stream for: " + zipUri);
            while (zis.getNextEntry() != null) {
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }

    /**
     * Extracts a ZIP archive from a content URI into the specified target
     * directory. Includes Zip Slip prevention: each entry's canonical path
     * must remain inside the target directory. Reports progress via the
     * callback on the main thread, throttled to at most one update per 100ms
     * (always reports 100%).
     *
     * <p>This overload with {@code totalEntries} and {@code callback} is used
     * by the progress-enabled import path. The public 4-param overload
     * delegates here with no progress reporting.</p>
     */
    public static void extractZipToDirectory(Context context, Uri zipUri, File targetDir,
                                              ContentResolver resolver) throws IOException {
        extractZipToDirectory(context, zipUri, targetDir, resolver, 0, null);
    }

    private static void extractZipToDirectory(Context context, Uri zipUri, File targetDir,
                                               ContentResolver resolver, int totalEntries,
                                               ZipImportCallback callback) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Could not create directory: " + targetDir);
        }

        try (InputStream in = resolver.openInputStream(zipUri);
             var zis = new ZipInputStream(in)) {
            if (in == null) throw new IOException("Could not open input stream for: " + zipUri);

            var buffer = new byte[16 * 1024];
            ZipEntry entry;
            int extractedCount = 0;
            long lastProgressTime = 0;
            int lastReportedPercent = -1;
            boolean hasProgress = callback != null && totalEntries > 0;

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                var entryFile = new File(targetDir, entryName);

                String canonicalTarget = targetDir.getCanonicalPath();
                String canonicalEntry = entryFile.getCanonicalPath();
                if (!canonicalEntry.startsWith(canonicalTarget + File.separator)) {
                    throw new SecurityException(
                            "Zip Slip detected: blocked entry \"" + entryName + "\"");
                }

                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                } else {
                    File parent = entryFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(entryFile)) {
                        int n;
                        while ((n = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, n);
                        }
                    }
                    serializeProvinceFileIfNeeded(entryFile);
                }
                zis.closeEntry();

                if (hasProgress) {
                    extractedCount++;
                    int percent = (int) ((extractedCount * 100L) / totalEntries);
                    long now = System.currentTimeMillis();
                    if (percent != lastReportedPercent
                            && (percent == 100 || now - lastProgressTime >= 100)) {
                        lastReportedPercent = percent;
                        lastProgressTime = now;
                        int pct = percent;
                        new Handler(Looper.getMainLooper()).post(() -> callback.onProgressUpdate(pct));
                    }
                }
            }
        }
    }

    public static String resolveFileName(ContentResolver resolver, Uri uri) {
        String name = "imported_file";
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
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

}
