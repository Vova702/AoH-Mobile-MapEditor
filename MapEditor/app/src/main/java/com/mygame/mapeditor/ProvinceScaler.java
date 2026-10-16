package com.mygame.mapeditor;

import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Файлове масштабування даних карти в межах однієї теки:
 * перенумеровані провінції ("0", "1", "2", ...), об'єднаний legacy-файл
 * (mapAoC2_v2.txt), cities*.json та mountains.json - з одного координатного
 * простору в інший за формулою x' = round(x * newW / origW), y' = round(y * newH / origH).
 * Після успішного масштабування записується province_space.txt з розміром
 * нового координатного простору.
 */
public final class ProvinceScaler {

    private static final String[] CITY_FILES = {
            "cities.json", "cities_1.json", "cities_2.json", "cities_3.json"
    };
    private static final String MOUNTAINS_FILE = "mountains.json";
    private static final String LEGACY_FILE_NAME = "mapAoC2_v2.txt";
    private static final String SPACE_FILE = "province_space.txt";

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static final class Result {
        public final int scaledFiles;
        public final int skippedFiles;
        /** Non-fatal problem to show the user (e.g. scales could not be regenerated), or null. */
        public final String warning;

        Result(int scaledFiles, int skippedFiles, String warning) {
            this.scaledFiles = scaledFiles;
            this.skippedFiles = skippedFiles;
            this.warning = warning;
        }
    }

    private ProvinceScaler() {
    }

    /**
     * Масштабує карту у {@code mapDir}: провінції (з перерахунком сусідів/кордонів і
     * перегенерацією масок scales), legacy-файл, cities*.json, mountains.json.
     * Усе або нічого для провінцій: при помилці (наприклад, координати не влазять в int16)
     * карта лишається без змін. Callback {@code listener} викликається не на головному потоці.
     *
     * @param editorScalesDir тека scales редактора для цієї карти, яку треба перегенерувати (може бути null)
     */
    public static Result scaleMapDirectory(File mapDir, int origW, int origH,
                                           int newW, int newH, File editorScalesDir,
                                           ProgressListener listener) throws IOException {
        if (origW <= 0 || origH <= 0 || newW <= 0 || newH <= 0) {
            throw new IOException("Invalid scale dimensions");
        }
        if (!mapDir.isDirectory()) {
            throw new IOException("Map directory not found: " + mapDir);
        }

        final double sx = (double) newW / origW;
        final double sy = (double) newH / origH;

        // 1. provinces (+ neighbours, borders, scale masks) - only the real province files
        int scaled = 0;
        String warning = null;
        boolean hasProvinces = !MapFiles.provinceFiles(mapDir).isEmpty() || MapFiles.packFile(mapDir) != null;
        if (hasProvinces) {
            MapRescaler.Result r = MapRescaler.rescale(mapDir, origW, origH, newW, newH,
                    editorScalesDir, ScalePlan.SCALES,
                    p -> { if (listener != null) listener.onProgress(p * 90 / 100); });
            scaled += r.provinces;
            warning = r.warning;
        }

        // 2. the other files (legacy combined file, cities, mountains)
        List<File> targets = collectTargets(mapDir);
        int skipped = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (scaleFile(targets.get(i), sx, sy)) scaled++; else skipped++;
            if (listener != null) listener.onProgress(90 + (i + 1) * 9 / targets.size());
        }

        writeSpaceFile(mapDir, newW, newH);
        if (listener != null) listener.onProgress(100);
        return new Result(scaled, skipped, warning);
    }

    /** Читає збережений раніше координатний простір (province_space.txt), або null. */
    public static int[] readProvinceSpace(File mapDir) {
        var f = new File(mapDir, SPACE_FILE);
        if (!f.isFile()) return null;
        try {
            List<String> lines = readNonEmptyLines(f);
            Integer w = null;
            Integer h = null;
            for (String line : lines) {
                String[] kv = line.split("=", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim();
                if (key.equals("WIDTH")) w = Integer.parseInt(kv[1].trim());
                else if (key.equals("HEIGHT")) h = Integer.parseInt(kv[1].trim());
            }
            if (w == null || h == null || w <= 0 || h <= 0) return null;
            return new int[]{w, h};
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Читає розміри зображення (bounds) без повного декодування, або null. */
    public static int[] readImageSize(File imageFile) {
        if (imageFile == null || !imageFile.isFile()) return null;
        var opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        return new int[]{opts.outWidth, opts.outHeight};
    }

    // ------------------------------------------------------------------
    // Збір цілей + диспетчеризація
    // ------------------------------------------------------------------

    private static List<File> collectTargets(File mapDir) {
        List<File> out = new ArrayList<>();

        var legacy = new File(mapDir, LEGACY_FILE_NAME);
        if (!legacy.isFile()) legacy = new File(mapDir, "Provinces/" + LEGACY_FILE_NAME);
        if (legacy.isFile()) out.add(legacy);

        for (String name : CITY_FILES) {
            File f = findFileRecursive(mapDir, name);
            if (f != null) out.add(f);
        }
        File mountains = findFileRecursive(mapDir, MOUNTAINS_FILE);
        if (mountains != null) out.add(mountains);

        return out;
    }

    /** Шукає файл з точним ім'ям рекурсивно по всьому дереву, або null. */
    private static File findFileRecursive(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile()) {
                if (f.getName().equals(name)) return f;
            } else if (f.isDirectory()) {
                File found = findFileRecursive(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean scaleFile(File f, double sx, double sy) {
        try {
            String name = f.getName();
            if (name.equals(LEGACY_FILE_NAME)) {
                return scaleLegacyFile(f, sx, sy);
            }
            return scaleJsonPointsFile(f, sx, sy);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void replaceFile(File target, File tmp) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Cannot rename temp file");
        }
    }

    // ------------------------------------------------------------------
    // Формати файлів
    // ------------------------------------------------------------------

    private static boolean scaleLegacyFile(File f, double sx, double sy) throws IOException {
        List<String> lines = readNonEmptyLines(f);

        var out = new StringBuilder();
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            out.append(scaleCsvLine(lines.get(i), sx)).append('\n');
            out.append(scaleCsvLine(lines.get(i + 1), sy)).append('\n');
        }
        if (out.length() == 0) return false;

        writeAtomic(f, out.toString());
        return true;
    }

    private static boolean scaleJsonPointsFile(File f, double sx, double sy) throws IOException {
        String json;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            var buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
            json = sb.toString();
        } catch (IOException e) {
            return false;
        }

        try {
            var arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.has("x")) {
                    o.put("x", scaleInt(o.optInt("x", 0), sx));
                } else if (o.has("X")) {
                    o.put("X", scaleInt(o.optInt("X", 0), sx));
                }
                if (o.has("y")) {
                    o.put("y", scaleInt(o.optInt("y", 0), sy));
                } else if (o.has("Y")) {
                    o.put("Y", scaleInt(o.optInt("Y", 0), sy));
                }
            }
            writeAtomic(f, arr.toString() + "\n");
            return true;
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Допоміжні
    // ------------------------------------------------------------------

    private static String scaleCsvLine(String line, double factor) {
        List<Integer> out = new ArrayList<>();
        for (String p : line.split(",")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try {
                out.add(scaleInt(Integer.parseInt(p), factor));
            } catch (NumberFormatException ignored) {
            }
        }
        return join(out);
    }

    private static int scaleInt(int value, double factor) {
        return (int) Math.round(value * factor);
    }

    private static String join(List<Integer> values) {
        var sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private static List<String> readNonEmptyLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    private static void writeAtomic(File f, String content) throws IOException {
        var tmp = new File(f.getParentFile(), f.getName() + ".scale.tmp");
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            bw.write(content);
        }
        replaceFile(f, tmp);
    }

    private static void writeSpaceFile(File mapDir, int width, int height) throws IOException {
        writeAtomic(new File(mapDir, SPACE_FILE), "WIDTH=" + width + "\nHEIGHT=" + height + "\n");
    }
}