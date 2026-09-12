package dev.idebugger.echoreplay.storage;

import dev.idebugger.echoreplay.util.Io;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Streaming writer of the .echoreplay.gz v1 format.
 *
 * <p>Two modes:
 * <ul>
 *   <li>gzipped — the final {@code .echoreplay.gz} recording.</li>
 *   <li>raw — the crash-safety checkpoint ({@code .partial}) file: identical
 *       section framing without gzip, so a crash mid-write leaves a file whose
 *       complete sections are still parseable (see {@link GzipRecordingReader#readLenient}).
 *       Raw files are only consumed by this plugin's recovery path, never by
 *       the regular reader.</li>
 * </ul>
 *
 * Usage: write the header sections once on the IO thread, then repeatedly
 * appendTimelineEvent(...) from the IO thread. close() finishes the stream.
 * Not thread-safe; the caller serializes access.
 */
public final class GzipRecordingWriter implements AutoCloseable {

    private final GZIPOutputStream gzip;
    private final DataOutputStream out;

    public GzipRecordingWriter(java.io.OutputStream raw) throws IOException {
        this(raw, true);
    }

    /** @param gzipped false for raw checkpoint streams (no gzip framing). */
    public GzipRecordingWriter(java.io.OutputStream raw, boolean gzipped) throws IOException {
        this(raw, gzipped, true);
    }

    private GzipRecordingWriter(java.io.OutputStream raw, boolean gzipped, boolean writeHeader) throws IOException {
        if (gzipped) {
            // Best compression: final writes run on the IO thread and files
            // are small, so trade a few ms of CPU for a smaller recording.
            java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(raw) {{
                def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
            }};
            this.gzip = gz;
            this.out = new DataOutputStream(this.gzip);
        } else {
            this.gzip = null;
            this.out = new DataOutputStream(raw);
        }
        if (!writeHeader) return;
        // magic
        out.writeByte('E');
        out.writeByte('C');
        out.writeByte('H');
        out.writeByte('O');
        // format u16 LE
        byte[] fmt = { (byte) (RecordingFormat.FORMAT & 0xFF), (byte) ((RecordingFormat.FORMAT >> 8) & 0xFF) };
        out.write(fmt);
        // flags u16 LE = 0
        out.writeByte(0);
        out.writeByte(0);
    }

    /**
     * Headerless raw appender for resume: continues an existing checkpoint
     * section stream without emitting a second magic header (the lenient
     * reader concatenates sections across the whole file).
     */
    public static GzipRecordingWriter appendRaw(java.io.OutputStream raw) throws IOException {
        return new GzipRecordingWriter(raw, false, false);
    }

    /**
     * Write the meta section. For checkpoint files the duration may be 0;
     * recovery recomputes it from the last event.
     */
    public void writeMeta(String serverVersion, java.util.UUID worldUuid, String worldName,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          long epochMillis, java.util.UUID recorderUuid, String recorderName,
                          long durationMillis, String name) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        Io.writeUtf(b, serverVersion);
        Io.writeUtf(b, worldUuid.toString());
        Io.writeUtf(b, worldName);
        b.writeInt(minX);
        b.writeInt(minY);
        b.writeInt(minZ);
        b.writeInt(maxX);
        b.writeInt(maxY);
        b.writeInt(maxZ);
        b.writeLong(epochMillis);
        Io.writeUtf(b, recorderUuid.toString());
        Io.writeUtf(b, recorderName);
        b.writeLong(durationMillis);
        Io.writeUtf(b, name);
        b.flush();
        writeSection(RecordingFormat.SEC_META, bos.toByteArray());
    }

    public void writePalette(java.util.List<String> palette) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeInt(palette.size());
        for (String s : palette) {
            Io.writeUtf(b, s);
        }
        b.flush();
        writeSection(RecordingFormat.SEC_PALETTE, bos.toByteArray());
    }

    /**
     * @param sizeX sizeY sizeZ dimensions of the stored dense grid
     * @param data  palette indices, row-major (x fastest)
     * @param bitsPerEntry bits used per entry (authoritative)
     */
    public void writeBlocks(int sizeX, int sizeY, int sizeZ, int[] data, int bitsPerEntry) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeInt(sizeX);
        b.writeInt(sizeY);
        b.writeInt(sizeZ);
        b.writeByte(bitsPerEntry);
        int total = data.length;
        long mask = (1L << bitsPerEntry) - 1;
        int perLong = Math.min(64 / bitsPerEntry, 32);
        int longCount = (total + perLong - 1) / perLong;
        b.writeInt(longCount);
        for (int li = 0; li < longCount; li++) {
            long packed = 0;
            int start = li * perLong;
            for (int i = 0; i < perLong; i++) {
                int idx = start + i;
                if (idx >= total) break;
                packed |= ((long) (data[idx] & mask)) << (i * bitsPerEntry);
            }
            b.writeLong(packed);
        }
        b.flush();
        writeSection(RecordingFormat.SEC_BLOCKS, bos.toByteArray());
    }

    /** @param entries each is [relX, relY, relZ, nbtLen, nbt...] already packed */
    public void writeBlockNbt(byte[] entries) throws IOException {
        writeSection(RecordingFormat.SEC_BLOCK_NBT, entries);
    }

    public void writeEntities(byte[] entries) throws IOException {
        writeSection(RecordingFormat.SEC_ENTITIES, entries);
    }

    public void appendTimelineEvent(long tickMillis, byte[] body, byte typeId) throws IOException {
        // S-4: hard guard against the u16 truncation that silently dropped
        // any event > 65535 bytes (a player carrying a shulker-of-shulkers,
        // a complex firework, or any large chat component would simply vanish
        // from the recording AND leave ~2MB of unreadable payload in the file).
        // The caller catches this and skips just the one oversized event,
        // logging its type+tick so the rest of the recording survives.
        if (body.length > 0xFFFF) {
            throw new IOException("timeline event body too large: " + body.length
                + " bytes (u16 length limit) — event type=" + (typeId & 0xFF)
                + " tick=" + tickMillis + "ms; skipping this single event. "
                + "(Format v2 will use varint/i32 lengths; for v1, split oversized "
                + "PlayerSpawn equipment into follow-up Equipment events.)");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeLong(tickMillis);
        b.writeByte(typeId);
        b.writeShort(body.length);
        b.write(body);
        b.flush();
        writeSection(RecordingFormat.SEC_TIMELINE, bos.toByteArray());
    }

    /** Write one complete section (public for the checkpoint flush path). */
    public void writeSection(int type, byte[] body) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Io.LeOut b = Io.leOut(bos);
        b.writeInt(type);
        b.writeInt(body.length);
        b.write(body);
        b.flush();
        out.write(bos.toByteArray());
    }

    /** Push buffered bytes to disk (checkpoint durability). */
    public void flush() throws IOException {
        out.flush();
    }

    public void close() throws IOException {
        out.flush();
        if (gzip != null) {
            gzip.finish();
            gzip.close();
        } else {
            out.close();
        }
    }
}
