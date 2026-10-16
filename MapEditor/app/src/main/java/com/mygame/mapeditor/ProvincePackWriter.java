package com.mygame.mapeditor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Writes the consolidated "pack" container used by the game's
 * ProvincesDataStore: one file holding every province's bytes back to back
 * instead of thousands of loose files.
 *
 * Layout (big-endian):
 *   int magic 0x41505256 ("APRV"), int version 1, int count,
 *   count x (int offset, int length), then the concatenated entries.
 *
 * Entries are streamed to a temporary file while their lengths are
 * recorded, so even a 20 000 province map (50+ MB) never has to be held in
 * memory at once - important on a phone.
 */
final class ProvincePackWriter implements Closeable {

    static final int MAGIC = 0x41505256;
    static final int VERSION = 1;

    private final File tmp;
    private final OutputStream body;
    private int[] lengths = new int[1024];
    private int count = 0;
    private long total = 0;

    ProvincePackWriter(File tmpDir) throws IOException {
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("Failed to create " + tmpDir);
        this.tmp = File.createTempFile("pack_body", ".tmp", tmpDir);
        this.body = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
    }

    void add(byte[] entry) throws IOException {
        if (count == lengths.length) lengths = Arrays.copyOf(lengths, count * 2);
        lengths[count++] = entry.length;
        total += entry.length;
        if (total > Integer.MAX_VALUE) throw new IOException("Pack is larger than 2 GB");
        body.write(entry);
    }

    int size() {
        return count;
    }

    /** Writes header + entries to {@code out} and deletes the temporary body file. */
    void finish(File out) throws IOException {
        body.close();
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Failed to create " + parent);
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(count);
            int offset = 0;
            for (int i = 0; i < count; i++) {
                dos.writeInt(offset);
                dos.writeInt(lengths[i]);
                offset += lengths[i];
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(tmp), 1 << 16)) {
                var buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) dos.write(buf, 0, n);
            }
        }
        tmp.delete();
    }

    @Override
    public void close() throws IOException {
        body.close();
        tmp.delete();
    }
}
