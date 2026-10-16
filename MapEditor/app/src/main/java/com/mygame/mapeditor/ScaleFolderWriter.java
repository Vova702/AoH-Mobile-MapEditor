package com.mygame.mapeditor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes the province scale masks into their OWN folder, mirroring the game's layout:
 *
 * <pre>
 *   &lt;scalesDir&gt;/provinces/&lt;k&gt;/&lt;id&gt;        one mask per province, per scale k
 *   &lt;scalesDir&gt;/provinces/Age_of_Civilizations   list of the scales, e.g. "1;3;5;7;"
 * </pre>
 *
 * The folder must NOT live inside the map folder: the exporter and the map scaler pick up every
 * file whose name is a number, recursively, and would take the masks for provinces.
 *
 * Everything is generated into a sibling "&lt;name&gt;.tmp" folder first and swapped in only when
 * complete, so an interrupted run never leaves a half-written scale set behind.
 */
final class ScaleFolderWriter {

    interface Listener {
        /** @param scale the scale level being generated right now */
        void onProgress(int percent, int scale);
    }

    private ScaleFolderWriter() {
    }

    static void write(File scalesDir, ScaleMaskGenerator gen, int provinceCount, int[] scales,
                      int fromPercent, int toPercent, Listener listener) throws IOException {
        File parent = scalesDir.getParentFile();
        if (parent == null) throw new IOException("Bad scales folder: " + scalesDir);
        var tmp = new File(parent, scalesDir.getName() + ".tmp");
        deleteRecursive(tmp);

        boolean done = false;
        try {
            var provincesDir = new File(tmp, "provinces");
            if (!provincesDir.mkdirs()) throw new IOException("Failed to create " + provincesDir);

            var index = new StringBuilder();
            int lastPercent = -1;
            for (int si = 0; si < scales.length; si++) {
                int k = scales[si];
                if (k < 1) continue;
                index.append(k).append(';');

                var kDir = new File(provincesDir, String.valueOf(k));
                if (!kDir.mkdirs()) throw new IOException("Failed to create " + kDir);
                if (listener != null) {
                    lastPercent = Math.max(lastPercent, ScalePlan.percent(scales, si, 0, provinceCount, fromPercent, toPercent));
                    listener.onProgress(lastPercent, k);
                }
                for (int i = 0; i < provinceCount; i++) {
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(new File(kDir, String.valueOf(i))))) {
                        os.write(gen.cim(i, k));
                    }
                    if (listener != null) {
                        int pct = ScalePlan.percent(scales, si, i + 1, provinceCount, fromPercent, toPercent);
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            listener.onProgress(pct, k);
                        }
                    }
                }
            }
            // every scale must hold exactly one mask per province - never publish a set that does not
            for (int k : scales) {
                if (k < 1) continue;
                String[] names = new File(provincesDir, String.valueOf(k)).list();
                if (names == null || names.length != provinceCount) {
                    throw new IOException("Scale " + k + " has " + (names == null ? 0 : names.length)
                            + " masks, expected " + provinceCount);
                }
            }
            try (OutputStream os = new FileOutputStream(new File(provincesDir, "Age_of_Civilizations"))) {
                os.write(index.toString().getBytes(StandardCharsets.UTF_8));
            }

            deleteRecursive(scalesDir);
            if (!tmp.renameTo(scalesDir)) throw new IOException("Failed to move scales into " + scalesDir);
            done = true;
        } finally {
            if (!done) deleteRecursive(tmp);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
