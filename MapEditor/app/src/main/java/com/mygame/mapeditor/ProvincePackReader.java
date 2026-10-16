package com.mygame.mapeditor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Random-access reader for the consolidated province pack
 * ({@code provinces.pack}, see {@link ProvincePackWriter}) so the editor can
 * open maps stored in the NEW format as well as the old one-file-per-province
 * layout. Entries are read on demand; the whole pack is never kept in memory.
 */
final class ProvincePackReader implements Closeable {

    private final RandomAccessFile raf;
    private final int[] offsets;
    private final int[] lengths;
    private final long dataStart;

    ProvincePackReader(File file) throws IOException {
        raf = new RandomAccessFile(file, "r");
        try {
            if (raf.readInt() != ProvincePackWriter.MAGIC) throw new IOException("Not a province pack: " + file);
            int version = raf.readInt();
            if (version != ProvincePackWriter.VERSION) throw new IOException("Unsupported pack version " + version);
            int count = raf.readInt();
            if (count < 0 || count > 10_000_000) throw new IOException("Corrupt pack header");
            var header = new byte[count * 8];
            raf.readFully(header);
            offsets = new int[count];
            lengths = new int[count];
            for (int i = 0; i < count; i++) {
                int b = i * 8;
                offsets[i] = ((header[b] & 0xff) << 24) | ((header[b + 1] & 0xff) << 16) | ((header[b + 2] & 0xff) << 8) | (header[b + 3] & 0xff);
                lengths[i] = ((header[b + 4] & 0xff) << 24) | ((header[b + 5] & 0xff) << 16) | ((header[b + 6] & 0xff) << 8) | (header[b + 7] & 0xff);
            }
            dataStart = 12L + count * 8L;
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    int size() {
        return offsets.length;
    }

    byte[] read(int id) throws IOException {
        if (id < 0 || id >= offsets.length || lengths[id] <= 0) return null;
        var buf = new byte[lengths[id]];
        raf.seek(dataStart + offsets[id]);
        raf.readFully(buf);
        return buf;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
