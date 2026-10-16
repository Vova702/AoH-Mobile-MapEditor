package com.mygame.mapeditor;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the provinces of a map folder from one coordinate space to another and keeps EVERYTHING
 * that depends on the outlines consistent with them: neighbours and shared borders are recomputed
 * and the scale masks are generated again from the new outlines. Pure Java, unit-tested.
 *
 * <p>All or nothing: the new files are prepared in a staging folder first (any problem - a province
 * that would not fit the 16-bit coordinate range, one that collapses to nothing, a wrong file count -
 * stops here with the map untouched) and only then moved in. If moving in fails half way, the old
 * files are put back.</p>
 */
final class MapRescaler {

    private MapRescaler() {
    }

    interface Progress {
        void onProgress(int percent);
    }

    static final class Result {
        int provinces;
        /** Scale levels of the masks that were inside the map folder (data/scales/provinces) and have been regenerated. */
        int[] nestedMaskScales = new int[0];
        /** The editor's own scales folder was generated again. */
        boolean editorScalesRegenerated;
        /** Something non-fatal the user should know (e.g. the scales could not be regenerated). */
        String warning;
    }

    static Result rescale(File mapDir, int origW, int origH, int newW, int newH,
                          File editorScalesDir, int[] editorScales, Progress progress) throws IOException {
        if (origW <= 0 || origH <= 0 || newW <= 0 || newH <= 0) throw new IOException("Invalid scale dimensions");
        if (!mapDir.isDirectory()) throw new IOException("Map directory not found: " + mapDir);
        final double sx = (double) newW / origW, sy = (double) newH / origH;

        // ---- 1. read ------------------------------------------------------------------------
        List<File> files = MapFiles.provinceFiles(mapDir);
        File packFile = files.isEmpty() ? MapFiles.packFile(mapDir) : null;
        List<ProvinceDecoder.DecodedProvince> src = new ArrayList<>();
        if (!files.isEmpty()) {
            String problem = MapFiles.contiguityProblem(files);
            if (problem != null) throw new IOException(problem);
            for (int i = 0; i < files.size(); i++) {
                File f = files.get(i);
                ProvinceDecoder.DecodedProvince d = ProvinceDecoder.isJavaSerialized(f)
                        ? ProvinceDecoder.decodeProvince(f) : MapExporter.decodeTextProvince(f);
                if (d == null || d.pointsX.length < 2) throw new IOException("Province " + i + " (" + f.getName() + ") has no usable outline");
                src.add(d);
                report(progress, (i + 1) * 8 / files.size());
            }
        } else if (packFile != null) {
            try (ProvincePackReader r = new ProvincePackReader(packFile)) {
                for (int i = 0; i < r.size(); i++) {
                    byte[] blob = r.read(i);
                    if (blob == null) throw new IOException("Province " + i + " is missing in " + packFile.getName());
                    src.add(ProvinceDecoder.decodeProvince(new ByteArrayInputStream(blob)));
                    report(progress, (i + 1) * 8 / r.size());
                }
            }
        }
        final int n = src.size();
        if (n == 0) throw new IOException("No provinces found in " + mapDir);

        // ---- 2. scale, with checks ----------------------------------------------------------
        List<List<Integer>> xs = new ArrayList<>(n), ys = new ArrayList<>(n);
        List<ProvinceExtra> extras = new ArrayList<>(n);
        List<Boolean> sea = new ArrayList<>(n);
        List<Integer> continent = new ArrayList<>(n);
        List<Integer> collapsed = new ArrayList<>();
        long maxAbs = 0;
        for (int i = 0; i < n; i++) {
            ProvinceDecoder.DecodedProvince d = src.get(i);
            int c = Math.min(d.pointsX.length, d.pointsY.length);
            List<Integer> px = new ArrayList<>(c), py = new ArrayList<>(c);
            for (int j = 0; j < c; j++) {
                long x = Math.round(d.pointsX[j] * sx), y = Math.round(d.pointsY[j] * sy);
                maxAbs = Math.max(maxAbs, Math.max(Math.abs(x), Math.abs(y)));
                if (x < Short.MIN_VALUE || x > Short.MAX_VALUE || y < Short.MIN_VALUE || y > Short.MAX_VALUE) continue;
                // downscaling can merge neighbouring points: drop repeats
                if (!px.isEmpty() && px.get(px.size() - 1) == (int) x && py.get(py.size() - 1) == (int) y) continue;
                px.add((int) x);
                py.add((int) y);
            }
            while (px.size() > 1 && px.get(0).equals(px.get(px.size() - 1)) && py.get(0).equals(py.get(py.size() - 1))) {
                px.remove(px.size() - 1);
                py.remove(py.size() - 1);
            }
            if (px.size() < 3) collapsed.add(i);
            xs.add(px);
            ys.add(py);

            d.shiftX = (int) Math.round(d.shiftX * sx);
            d.shiftY = (int) Math.round(d.shiftY * sy);
            d.portShiftX = (int) Math.round(d.portShiftX * sx);
            d.portShiftY = (int) Math.round(d.portShiftY * sy);
            extras.add(ProvinceExtra.fromDecoded(d));
            sea.add(d.levelOfPort < -1);
            continent.add(d.continentId);
        }
        if (maxAbs > Short.MAX_VALUE) {
            throw new IOException("The new size does not fit: province coordinates are stored as 16-bit numbers "
                    + "(max " + Short.MAX_VALUE + ") and the largest one would become " + maxAbs
                    + ". Choose a smaller size (about " + (int) Math.floor(newW * (double) Short.MAX_VALUE / maxAbs)
                    + " px wide or less). Nothing was changed.");
        }
        if (!collapsed.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(10, collapsed.size()); i++) sb.append(i > 0 ? ", " : "").append(collapsed.get(i));
            throw new IOException(collapsed.size() + " province(s) would shrink to less than 3 points at this size (ids: "
                    + sb + (collapsed.size() > 10 ? ", ..." : "") + "). Choose a larger size. Nothing was changed.");
        }
        report(progress, 12);

        MapExporter.applySeaFile(MapExporter.findSideFile(mapDir, MapExporter.SEA_FILE), sea);
        MapExporter.applyContinentsFile(MapExporter.findSideFile(mapDir, MapExporter.CONTINENTS_FILE), continent);

        // ---- 3. neighbours / borders / masks from the NEW outlines -----------------------------
        List<ProvinceDecoder.DecodedProvince> built = ProvinceExportBuilder.build(xs, ys, sea, continent, extras, newW, 2, true);
        if (built.size() != n) throw new IOException("Internal error: " + built.size() + " provinces built, expected " + n);
        report(progress, 30);

        File parent = mapDir.getParentFile() != null ? mapDir.getParentFile() : mapDir.getAbsoluteFile().getParentFile();
        File staging = new File(parent, mapDir.getName() + ".rescale.tmp");
        File backup = new File(parent, mapDir.getName() + ".rescale.bak");
        deleteRecursive(staging);
        deleteRecursive(backup);
        if (!staging.mkdirs()) throw new IOException("Failed to create " + staging);

        Result res = new Result();
        res.provinces = n;
        boolean swapped = false;
        try {
            // provinces
            File stagedProvinces = new File(staging, "provinces");
            File stagedPack = new File(staging, "provinces.pack");
            if (packFile == null) {
                if (!stagedProvinces.mkdirs()) throw new IOException("Failed to create " + stagedProvinces);
                for (int i = 0; i < n; i++) {
                    ProvinceDecoder.encodeProvince(built.get(i), new File(stagedProvinces, String.valueOf(i)));
                    report(progress, 30 + (i + 1) * 15 / n);
                }
                String[] names = stagedProvinces.list();
                if (names == null || names.length != n) throw new IOException("Wrong number of province files prepared");
            } else {
                try (ProvincePackWriter w = new ProvincePackWriter(staging)) {
                    for (int i = 0; i < n; i++) {
                        w.add(ProvinceDecoder.encodeProvinceToBytes(built.get(i)));
                        report(progress, 30 + (i + 1) * 15 / n);
                    }
                    w.finish(stagedPack);
                }
            }

            ScaleMaskGenerator gen = new ScaleMaskGenerator(xs, ys, mainNeighbors(built));

            // masks that live inside the map folder (a map unpacked from the game)
            int[] nested = MapFiles.nestedMaskScales(mapDir);
            File stagedMasks = new File(staging, "masks");
            File nestedRoot = MapFiles.nestedMasksDir(mapDir);
            for (int si = 0; si < nested.length; si++) {
                int k = nested[si];
                if (new File(nestedRoot, String.valueOf(k)).isDirectory()) {
                    File kDir = new File(stagedMasks, String.valueOf(k));
                    if (!kDir.mkdirs()) throw new IOException("Failed to create " + kDir);
                    for (int i = 0; i < n; i++) {
                        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(new File(kDir, String.valueOf(i))))) {
                            os.write(gen.cim(i, k));
                        }
                        report(progress, 45 + (si * n + i + 1) * 25 / (nested.length * n));
                    }
                    String[] names = kDir.list();
                    if (names == null || names.length != n) throw new IOException("Wrong number of masks prepared for scale " + k);
                }
                if (new File(nestedRoot, k + ".pack").isFile()) {
                    try (ProvincePackWriter w = new ProvincePackWriter(staging)) {
                        for (int i = 0; i < n; i++) {
                            w.add(gen.cim(i, k));
                            report(progress, 45 + (si * n + i + 1) * 25 / (nested.length * n));
                        }
                        w.finish(new File(stagedMasks, k + ".pack"));
                    }
                }
            }
            res.nestedMaskScales = nested;

            // ---- 4. move in (rolled back on failure) --------------------------------------------
            if (!backup.mkdirs()) throw new IOException("Failed to create " + backup);
            List<File[]> moved = new ArrayList<>();     // {original location, where it went}
            List<File> created = new ArrayList<>();
            try {
                if (packFile == null) {
                    File dir = MapFiles.provinceDir(mapDir);
                    for (File old : files) {
                        move(old, new File(backup, "p" + old.getName()), moved);
                    }
                    for (int i = 0; i < n; i++) {
                        File dest = new File(dir, String.valueOf(i));
                        move(new File(stagedProvinces, String.valueOf(i)), dest, null);
                        created.add(dest);
                    }
                } else {
                    move(packFile, new File(backup, "provinces.pack"), moved);
                    move(stagedPack, packFile, null);
                    created.add(packFile);
                }
                File[] stagedMaskItems = stagedMasks.listFiles();
                if (stagedMaskItems != null) {
                    for (File item : stagedMaskItems) {
                        File target = new File(nestedRoot, item.getName());
                        if (target.exists()) move(target, new File(backup, "m" + item.getName()), moved);
                        move(item, target, null);
                        created.add(target);
                    }
                }
            } catch (IOException | RuntimeException e) {
                for (File c : created) deleteRecursive(c);
                for (int i = moved.size() - 1; i >= 0; i--) {
                    File[] m = moved.get(i);
                    if (m[1].exists() && !m[1].renameTo(m[0])) {
                        try { copyBack(m[1], m[0]); } catch (IOException ignored) { }
                    }
                }
                throw e;
            }
            swapped = true;

            // ---- 5. the editor's own scales folder ----------------------------------------------
            if (editorScalesDir != null && editorScales != null && editorScales.length > 0) {
                try {
                    ScaleFolderWriter.write(editorScalesDir, gen, n, editorScales, 75, 98,
                            (percent, scale) -> report(progress, percent));
                    res.editorScalesRegenerated = true;
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    // an old set would not match the new outlines: better none than a wrong one
                    deleteRecursive(editorScalesDir);
                    res.warning = "Scales were not regenerated (" + (e.getMessage() != null ? e.getMessage() : e.toString())
                            + "). Open the map in the editor and press Save to generate them.";
                }
            }
            report(progress, 99);
        } finally {
            deleteRecursive(staging);
            deleteRecursive(backup);
        }
        return res;
    }

    private static List<List<Integer>> mainNeighbors(List<ProvinceDecoder.DecodedProvince> built) {
        List<List<Integer>> nb = new ArrayList<>(built.size());
        for (ProvinceDecoder.DecodedProvince d : built) nb.add(d.neighboringProvinces);
        return nb;
    }

    private static void move(File from, File to, List<File[]> journal) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir);
        if (!from.renameTo(to)) throw new IOException("Failed to move " + from + " to " + to);
        if (journal != null) journal.add(new File[]{from, to});
    }

    private static void copyBack(File from, File to) throws IOException {
        if (from.isDirectory()) throw new IOException("Cannot restore " + to);
        java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void report(Progress p, int percent) {
        if (p != null) p.onProgress(Math.max(0, Math.min(100, percent)));
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
