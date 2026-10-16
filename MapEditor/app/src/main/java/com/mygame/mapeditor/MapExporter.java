package com.mygame.mapeditor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Експорт теки карти у ZIP з бінарними файлами провінцій AoC2/AoH2
 * (формат {@code editor_data.zip}, який розуміє оригінальна гра та
 * цей редактор): кожна провінція - файл {@code 0}, {@code 1}, ...
 *
 * На відміну від експорту прямо з редактора (який бере провінції з пам'яті),
 * цей експорт читає дані, що вже збережені на диску в теці карти - тобто
 * працює з головного меню без завантаження всієї карти.
 *
 * Вміє обробляти обидва формати по-файлово:
 *  - бінарні (Java-серіалізовані) файли копіюються у ZIP як є;
 *  - текстові ("X1,X2,...;Y1,Y2,..." або два рядки X-координат / Y-координат)
 *    конвертуються у бінарний формат через ProvinceDecoder.
 */
public final class MapExporter {

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private static final Pattern NUMBERED_FILE = Pattern.compile("\\d+");
    private static long lastReportTime = 0;
    private static int lastReportedPercent = -1;

    private MapExporter() {
    }

    /**
     * Створює {@code editor_data.zip} з провінціями з теки карти.
     *
     * @param mapDir    тека карти (maps/&lt;mapName&gt;/)
     * @param exportDir тека, куди буде записано ZIP
     * @param zipName   ім'я файлу ZIP
     * @param listener  колбек прогресу (0..100), може бути null
     * @return записаний zip-файл
     */
    public static File exportAoc2Zip(File mapDir, File exportDir, String zipName,
                                     ProgressListener listener) throws IOException {
        if (mapDir == null || !mapDir.isDirectory()) {
            throw new IOException("Map directory not found: " + mapDir);
        }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("Failed to create " + exportDir);
        }

        List<File> provinceFiles = collectNumberedFiles(mapDir);
        sortByNumber(provinceFiles);

        var tmpDir = new File(exportDir, "aoc2_tmp");
        deleteRecursive(tmpDir);
        if (!tmpDir.mkdirs()) {
            throw new IOException("Failed to create temp dir " + tmpDir);
        }

        try {
            int total = Math.max(1, provinceFiles.size());
            int written = 0;

            for (int i = 0; i < provinceFiles.size(); i++) {
                File src = provinceFiles.get(i);
                var dst = new File(tmpDir, src.getName());

                if (ProvinceDecoder.isJavaSerialized(src)) {
                    copyFile(src, dst);
                    written++;
                } else {
                    ProvinceDecoder.DecodedProvince dec = decodeTextProvince(src);
                    if (dec != null && dec.pointsX.length >= 2) {
                        ProvinceDecoder.encodeProvince(dec, dst);
                        written++;
                    }
                }

                report(listener, 5 + (i + 1) * 70 / total);
            }

            if (written == 0) {
                throw new IOException("No provinces found to export in " + mapDir);
            }

            var zipFile = new File(exportDir, zipName);
            try (ZipOutputStream zos = new ZipOutputStream(
                    new FileOutputStream(zipFile), StandardCharsets.UTF_8)) {
                zipDirectoryToZip(tmpDir, zos, "", listener, 75, 100, written);
            }
            report(listener, 100);
            return zipFile;
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    // ==================================================================
    // Повний експорт: суходіл/море, сусіди, кордони, континенти, scales
    // ==================================================================

    public enum Format {
        /** data/provinces/&lt;id&gt; + data/scales/provinces/&lt;scale&gt;/&lt;id&gt; - one loose file per province (original game layout). */
        OLD_LOOSE_FILES,
        /** data/provinces.pack + data/scales/provinces/&lt;scale&gt;.pack - consolidated, far fewer files (needs the matching game build). */
        NEW_PACK
    }

    public static final class ExportOptions {
        public Format format = Format.OLD_LOOSE_FILES;
        /** Scale levels to generate masks for, e.g. {1, 2}. Empty = no scales. */
        public int[] scales = new int[0];
        /** Map width in editor coordinates for horizontal wrap-around of neighbors, 0 = off. */
        public int wrapWidth = 0;
        /** 2 = neighbors must share a segment (recommended); 1 = touching at a single corner also counts. */
        public int minSharedVertices = 2;
        /**
         * Keep the connections the loaded map already had (even where outlines no longer touch).
         * Off = neighbors are derived from the outlines only.
         */
        public boolean keepOldConnections = true;
        /** Also write config.json, data/regions, province_names, cities/*.json, wonders/wonders.json. */
        public boolean skeleton = false;
        /** Map name for config.json (skeleton). */
        public String mapName = "Map";
    }

    public static final String SEA_FILE = "sea_info.json";
    public static final String CONTINENTS_FILE = "continents_info.json";

    /**
     * Full export of the map saved in {@code mapDir} into a ZIP that mirrors
     * the game's asset layout (unzip straight into map/&lt;MapName&gt;/):
     * <pre>
     *   data/provinces/&lt;id&gt;                      (OLD_LOOSE_FILES)   or  data/provinces.pack  (NEW_PACK)
     *   data/scales/provinces/&lt;k&gt;/&lt;id&gt;          (OLD_LOOSE_FILES)   or  data/scales/provinces/&lt;k&gt;.pack
     *   data/scales/provinces/Age_of_Civilizations   (list of generated scales, e.g. "1;2;")
     * </pre>
     * Unlike {@link #exportAoc2Zip} this does NOT just copy outlines: it
     * recomputes neighbors and shared borders, applies sea_info.json and
     * continents_info.json and writes real levelOfPort / continent values,
     * so the exported map is actually playable.
     */
    public static File exportMap(File mapDir, File exportDir, String zipName,
                                 ExportOptions opt, ProgressListener listener) throws IOException {
        if (mapDir == null || !mapDir.isDirectory()) throw new IOException("Map directory not found: " + mapDir);
        if (!exportDir.exists() && !exportDir.mkdirs()) throw new IOException("Failed to create " + exportDir);
        if (opt == null) opt = new ExportOptions();

        List<File> files = collectNumberedFiles(mapDir);
        sortByNumber(files);
        if (files.isEmpty()) throw new IOException("No provinces found to export in " + mapDir);

        // ids must be contiguous 0..n-1: neighbor lists and sea/continent files refer to them
        for (int i = 0; i < files.size(); i++) {
            int num = Integer.parseInt(files.get(i).getName());
            if (num != i) {
                throw new IOException("Province files are not numbered 0.." + (files.size() - 1)
                        + " without gaps (expected " + i + " but found " + num + ")");
            }
        }

        final int n = files.size();
        List<List<Integer>> xs = new ArrayList<>(n);
        List<List<Integer>> ys = new ArrayList<>(n);
        List<ProvinceExtra> extras = new ArrayList<>(n);
        List<Boolean> sea = new ArrayList<>(n);
        List<Integer> continent = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            File f = files.get(i);
            ProvinceDecoder.DecodedProvince dec;
            if (ProvinceDecoder.isJavaSerialized(f)) {
                dec = ProvinceDecoder.decodeProvince(f);
            } else {
                dec = decodeTextProvince(f);
            }
            if (dec == null || dec.pointsX.length < 2) {
                throw new IOException("Province " + i + " (" + f.getName() + ") has no usable outline");
            }
            xs.add(ProvinceDecoder.toIntList(dec.pointsX));
            ys.add(ProvinceDecoder.toIntList(dec.pointsY));
            extras.add(ProvinceExtra.fromDecoded(dec));
            sea.add(dec.levelOfPort < -1);
            continent.add(dec.continentId);
            report(listener, 1 + (i + 1) * 9 / n);
        }

        applySeaFile(findSideFile(mapDir, SEA_FILE), sea);
        applyContinentsFile(findSideFile(mapDir, CONTINENTS_FILE), continent);

        report(listener, 12);
        List<ProvinceDecoder.DecodedProvince> built = ProvinceExportBuilder.build(
                xs, ys, sea, continent, extras, opt.wrapWidth, Math.max(1, opt.minSharedVertices), opt.keepOldConnections);
        report(listener, 30);

        var tmpDir = new File(exportDir, "aoc2_tmp");
        deleteRecursive(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("Failed to create temp dir " + tmpDir);

        try {
            var dataDir = new File(tmpDir, "data");
            var scalesDir = new File(dataDir, "scales/provinces");

            // ---- provinces ----
            if (opt.format == Format.NEW_PACK) {
                try (ProvincePackWriter pack = new ProvincePackWriter(tmpDir)) {
                    for (int i = 0; i < n; i++) {
                        pack.add(ProvinceDecoder.encodeProvinceToBytes(built.get(i)));
                        report(listener, 30 + (i + 1) * 20 / n);
                    }
                    pack.finish(new File(dataDir, "provinces.pack"));
                }
            } else {
                var out = new File(dataDir, "provinces");
                if (!out.mkdirs()) throw new IOException("Failed to create " + out);
                for (int i = 0; i < n; i++) {
                    ProvinceDecoder.encodeProvince(built.get(i), new File(out, String.valueOf(i)));
                    report(listener, 30 + (i + 1) * 20 / n);
                }
            }

            // ---- scales ----
            int[] scales = opt.scales == null ? new int[0] : opt.scales;
            if (scales.length > 0) {
                List<List<Integer>> mainNb = new ArrayList<>(n);
                for (ProvinceDecoder.DecodedProvince d : built) mainNb.add(d.neighboringProvinces);
                var gen = new ScaleMaskGenerator(xs, ys, mainNb);

                if (!scalesDir.mkdirs()) throw new IOException("Failed to create " + scalesDir);
                var index = new StringBuilder();
                for (int si = 0; si < scales.length; si++) {
                    int k = scales[si];
                    if (k < 1) continue;
                    index.append(k).append(';');
                    int from = 50 + si * 45 / scales.length;
                    int to = 50 + (si + 1) * 45 / scales.length;

                    if (opt.format == Format.NEW_PACK) {
                        try (ProvincePackWriter pack = new ProvincePackWriter(tmpDir)) {
                            for (int i = 0; i < n; i++) {
                                pack.add(gen.cim(i, k));
                                report(listener, from + (i + 1) * (to - from) / n);
                            }
                            pack.finish(new File(scalesDir, k + ".pack"));
                        }
                    } else {
                        var kDir = new File(scalesDir, String.valueOf(k));
                        if (!kDir.mkdirs()) throw new IOException("Failed to create " + kDir);
                        for (int i = 0; i < n; i++) {
                            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(new File(kDir, String.valueOf(i))))) {
                                os.write(gen.cim(i, k));
                            }
                            report(listener, from + (i + 1) * (to - from) / n);
                        }
                    }
                }
                try (OutputStream os = new FileOutputStream(new File(scalesDir, "Age_of_Civilizations"))) {
                    os.write(index.toString().getBytes(StandardCharsets.UTF_8));
                }
            }

            if (opt.skeleton) {
                writeSkeleton(tmpDir, n, opt);
            }

            // ---- zip ----
            var zipFile = new File(exportDir, zipName);
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile), StandardCharsets.UTF_8)) {
                zos.setLevel(opt.format == Format.NEW_PACK ? 1 : 6);
                zipTree(tmpDir, tmpDir, zos, listener, 95, 100);
            }
            report(listener, 100);
            return zipFile;
        } finally {
            deleteRecursive(tmpDir);
        }
    }

    /**
     * The small text files a map folder normally has next to its province data. They are all
     * optional for the game (missing = empty), but config.json is what registers the map's
     * properties. NOTE: the map folder still has to be added to map/Age_of_Civilizations.json,
     * and ContinentsPackage / RegionsPackage / Scenario must name things that exist in the game.
     */
    private static void writeSkeleton(File root, int provinceCount, ExportOptions opt) throws IOException {
        int scale = 1;
        if (opt.scales != null) {
            for (int k : opt.scales) {
                if (k >= 1) {
                    scale = k;
                    break;
                }
            }
        }
        String name = opt.mapName == null ? "Map" : opt.mapName.replace("\\", "").replace("\"", "");
        String config = "{\n\tMap: [\n\t\t{\n"
                + "\t\t\tMapName: \"" + name + "\",\n"
                + "\t\t\tAuthor: \"\",\n"
                + "\t\t\tBackgroundName: \"AoC2\",\n"
                + "\t\t\tContinentsPackage: \"Earth6\",\n"
                + "\t\t\tRegionsPackage: \"Earth_AoC2\",\n"
                + "\t\t\tNumberOfProvinces: " + provinceCount + ",\n"
                + "\t\t\tMapScale: " + scale + ",\n"
                + "\t\t\tWorldMap: true,\n"
                + "\t\t\tScenario: \"\",\n"
                + "\t\t\tWiki: \"\"\n"
                + "\t\t},\n\t],\n\n\tAge_of_Civilizations: Map\n}\n";
        writeText(new File(root, "config.json"), config);
        writeText(new File(root, "data/regions"), "0");
        writeText(new File(root, "province_names/names"), "");
        for (String f : new String[]{"cities.json", "cities_1.json", "cities_2.json", "cities_3.json", "cities_4.json"}) {
            writeText(new File(root, "cities/" + f), "{\n\tcities:\n\t[\n\t]\n}\n");
        }
        writeText(new File(root, "cities/mountains.json"), "{\n\tmountains:\n\t[\n\t]\n}\n");
        writeText(new File(root, "wonders/wonders.json"), "{\n\twonders:\n\t[\n\t]\n}\n");
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Failed to create " + parent);
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void zipTree(File root, File dir, ZipOutputStream zos,
                                ProgressListener listener, int fromPct, int toPct) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        java.util.Arrays.sort(children);
        for (File f : children) {
            if (f.isDirectory()) {
                zipTree(root, f, zos, listener, fromPct, toPct);
            } else {
                String name = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    var buf = new byte[32 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) zos.write(buf, 0, r);
                }
                zos.closeEntry();
            }
        }
    }

    static File findSideFile(File dir, String name) {
        var direct = new File(dir, name);
        if (direct.isFile()) return direct;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isDirectory()) {
                File r = findSideFile(c, name);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** sea_info.json = [3,17,18,...] indices of sea provinces; listed ones become sea, others keep what the file had. */
    static void applySeaFile(File f, List<Boolean> sea) throws IOException {
        if (f == null) return;
        var json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("-?\\d+").matcher(json);
        while (m.find()) {
            int idx = Integer.parseInt(m.group());
            if (idx >= 0 && idx < sea.size()) sea.set(idx, true);
        }
    }

    /** continents_info.json = {"12":1,"13":1,...} province index -> continent id (missing = keep). */
    static void applyContinentsFile(File f, List<Integer> continent) throws IOException {
        if (f == null) return;
        var json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"(\\d+)\"\\s*:\\s*(-?\\d+)").matcher(json);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx >= 0 && idx < continent.size()) continent.set(idx, Integer.parseInt(m.group(2)));
        }
    }

    // ------------------------------------------------------------------
    // Збір файлів провінцій
    // ------------------------------------------------------------------

    /** Only the real province files - never the scale masks (see {@link MapFiles}). */
    private static List<File> collectNumberedFiles(File dir) {
        return new ArrayList<>(MapFiles.provinceFiles(dir));
    }

    private static void collectNumberedFilesRecursive(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                if (NUMBERED_FILE.matcher(f.getName()).matches()) {
                    out.add(f);
                }
            } else if (f.isDirectory()) {
                collectNumberedFilesRecursive(f, out);
            }
        }
    }

    private static void sortByNumber(List<File> files) {
        files.sort(Comparator.comparingInt(f -> Integer.parseInt(f.getName())));
    }

    // ------------------------------------------------------------------
    // Текст -> бінарний формат
    // ------------------------------------------------------------------

    static ProvinceDecoder.DecodedProvince decodeTextProvince(File f) throws IOException {
        List<String> lines = readNonEmptyLines(f);
        String xLine;
        String yLine;
        if (lines.size() == 1 && lines.get(0).contains(";")) {
            String[] parts = lines.get(0).split(";", 2);
            if (parts.length < 2) return null;
            xLine = parts[0];
            yLine = parts[1];
        } else {
            if (lines.size() < 2) return null;
            xLine = lines.get(0);
            yLine = lines.get(1);
        }

        List<Integer> xs = parseCoords(xLine);
        List<Integer> ys = parseCoords(yLine);
        int n = Math.min(xs.size(), ys.size());
        if (n < 2) return null;

        var pointsX = new short[n];
        var pointsY = new short[n];
        for (int i = 0; i < n; i++) {
            pointsX[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, xs.get(i)));
            pointsY[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, ys.get(i)));
        }

        var dec = new ProvinceDecoder.DecodedProvince();
        dec.pointsX = pointsX;
        dec.pointsY = pointsY;
        return dec;
    }

    private static List<Integer> parseCoords(String line) {
        List<Integer> result = new ArrayList<>();
        for (String part : line.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                result.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static List<String> readNonEmptyLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // ZIP
    // ------------------------------------------------------------------

    private static void zipDirectoryToZip(File dir, ZipOutputStream zos, String prefix,
                                          ProgressListener listener, int startPct, int endPct,
                                          int totalFiles) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;

        int done = 0;
        for (File f : files) {
            String entryName = prefix + f.getName();
            zos.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                var buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
            }
            zos.closeEntry();

            done++;
            int pct = startPct + (done * (endPct - startPct)) / Math.max(1, totalFiles);
            report(listener, Math.min(endPct, pct));
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            var buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void report(ProgressListener listener, int percent) {
        if (listener == null) return;
        long now = System.currentTimeMillis();
        if (percent != lastReportedPercent
                && (percent == 100 || now - lastReportTime >= 100)) {
            lastReportedPercent = percent;
            lastReportTime = now;
            listener.onProgress(percent);
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}