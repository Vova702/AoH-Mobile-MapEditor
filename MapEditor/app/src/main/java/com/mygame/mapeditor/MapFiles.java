package com.mygame.mapeditor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which files of a map folder ARE the provinces. Pure Java.
 *
 * <p>A map folder can hold several kinds of files whose names are plain numbers: the province files
 * ({@code 0, 1, 2 ...}), but in a map unpacked from the game also the scale masks
 * ({@code data/scales/provinces/<k>/<id>}). The exporter and the map scaler used to collect EVERY
 * numbered file below the folder, so they took the masks for provinces: the province count came
 * out several times too big, scaling "skipped" thousands of files, and the masks never followed
 * the scaled outlines.</p>
 *
 * <p>The rule is the one the editor uses to open a map: numbered files directly in the map folder;
 * only if there are none, the ones in {@code data/provinces}. Nothing else is ever a province.</p>
 */
final class MapFiles {

    private MapFiles() {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d{1,9}");

    /** The province files, sorted by number. Empty when the map has none (e.g. only provinces.pack). */
    static List<File> provinceFiles(File mapDir) {
        List<File> files = numbered(mapDir);
        if (files.isEmpty()) files = numbered(new File(mapDir, "data/provinces"));
        files.sort(Comparator.comparingInt(f -> Integer.parseInt(f.getName())));
        return files;
    }

    /** The folder the province files live in (the map folder itself, or data/provinces); null if there are none. */
    static File provinceDir(File mapDir) {
        if (!numbered(mapDir).isEmpty()) return mapDir;
        File nested = new File(mapDir, "data/provinces");
        return numbered(nested).isEmpty() ? null : nested;
    }

    /** provinces.pack of the NEW format (map folder or data/), or null. */
    static File packFile(File mapDir) {
        File p = new File(mapDir, "provinces.pack");
        if (p.isFile()) return p;
        p = new File(mapDir, "data/provinces.pack");
        return p.isFile() ? p : null;
    }

    /** data/scales/provinces of a map in the game's layout (it may not exist). */
    static File nestedMasksDir(File mapDir) {
        return new File(mapDir, "data/scales/provinces");
    }

    /** The scale levels a nested mask folder holds: sub-folders "1", "3" ... and packs "1.pack" ... (sorted, no duplicates). */
    static int[] nestedMaskScales(File mapDir) {
        File dir = nestedMasksDir(mapDir);
        File[] children = dir.listFiles();
        if (children == null) return new int[0];
        java.util.TreeSet<Integer> ks = new java.util.TreeSet<>();
        for (File c : children) {
            String n = c.getName();
            if (c.isDirectory() && NUMBER.matcher(n).matches()) ks.add(Integer.parseInt(n));
            else if (c.isFile() && n.endsWith(".pack") && NUMBER.matcher(n.substring(0, n.length() - 5)).matches()) {
                ks.add(Integer.parseInt(n.substring(0, n.length() - 5)));
            }
        }
        int[] out = new int[ks.size()];
        int i = 0;
        for (int k : ks) out[i++] = k;
        return out;
    }

    /** Province numbers must be exactly 0..n-1; returns null when they are, otherwise a message naming the first problem. */
    static String contiguityProblem(List<File> sortedFiles) {
        for (int i = 0; i < sortedFiles.size(); i++) {
            int num = Integer.parseInt(sortedFiles.get(i).getName());
            if (num != i) {
                return "Province files are not numbered 0.." + (sortedFiles.size() - 1)
                        + " without gaps (expected " + i + " but found " + num + ")";
            }
        }
        return null;
    }

    private static List<File> numbered(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isFile() && NUMBER.matcher(f.getName()).matches()) out.add(f);
        }
        return out;
    }
}
