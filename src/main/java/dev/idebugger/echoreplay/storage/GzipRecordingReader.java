package dev.idebugger.echoreplay.storage;

import dev.idebugger.echoreplay.select.Cuboid;
import dev.idebugger.echoreplay.util.Io;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reader for the .echoreplay.gz v1 format. Validates magic, rejects format >
 * supported, and exposes sections. Click-through unknown sections for forward
 * compatibility.
 */
public final class GzipRecordingReader {

    private byte[] meta;
    private List<String> palette;
    private int[] blockData;
    private int blockSizeX, blockSizeY, blockSizeZ;
    private byte[] blockNbt;
    private byte[] entities;
    private final List<byte[]> timelineFragments = new ArrayList<>();

    private GzipRecordingReader() {}

    /**
     * @return loaded reader, or null on decode error (caller reports).
     */
    public static GzipRecordingReader read(java.io.InputStream raw) {
        Io.LeIn in = null;
        try {
            in = Io.leIn(new GZIPInputStream(raw));
            byte[] magic = new byte[4];
            in.readFully(magic);
            boolean ok = magic[0] == 'E' && magic[1] == 'C' && magic[2] == 'H' && magic[3] == 'O';
            if (!ok) {
                throw new IOException("Bad magic — not an EchoReplay file");
            }
            int format = in.readUnsignedShort();
            if (format > RecordingFormat.FORMAT) {
                throw new IOException("Recording format " + format + " newer than plugin's supported " + RecordingFormat.FORMAT + " — update the plugin");
            }
            in.readUnsignedShort(); // flags

            GzipRecordingReader r = new GzipRecordingReader();
            while (true) {
                int type;
                int len;
                try {
                    type = in.readInt();
                    len = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                byte[] body = new byte[len];
                in.readFully(body);
                switch (type) {
                    case RecordingFormat.SEC_META -> r.meta = body;
                    case RecordingFormat.SEC_PALETTE -> r.palette = parsePalette(body);
                    case RecordingFormat.SEC_BLOCKS -> {
                        BlockDataBox b = parseBlocks(body);
                        r.blockData = b.data;
                        r.blockSizeX = b.sizeX;
                        r.blockSizeY = b.sizeY;
                        r.blockSizeZ = b.sizeZ;
                    }
                    case RecordingFormat.SEC_BLOCK_NBT -> r.blockNbt = body;
                    case RecordingFormat.SEC_ENTITIES -> r.entities = body;
                    case RecordingFormat.SEC_TIMELINE -> r.timelineFragments.add(body);
                    default -> {/* unknown section — skip */}
                }
            }
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Lenient reader for raw (non-gzip) crash-safety checkpoint files. Unlike
     * {@link #read} it tolerates a truncated final section (crash mid-write):
     * complete sections are kept, the truncated tail is dropped. Sections are
     * assigned in file order, so the LAST PALETTE seen wins — checkpoints
     * append an up-to-date palette with every flush.
     *
     * @return loaded reader, or null when no complete section could be read.
     */
    public static GzipRecordingReader readLenient(java.io.InputStream raw) {
        Io.LeIn in = null;
        try {
            in = Io.leIn(raw);
            byte[] magic = new byte[4];
            in.readFully(magic);
            boolean ok = magic[0] == 'E' && magic[1] == 'C' && magic[2] == 'H' && magic[3] == 'O';
            if (!ok) {
                throw new IOException("Bad magic — not an EchoReplay file");
            }
            int format = in.readUnsignedShort();
            if (format > RecordingFormat.FORMAT) {
                throw new IOException("Recording format " + format + " newer than supported " + RecordingFormat.FORMAT);
            }
            in.readUnsignedShort(); // flags

            GzipRecordingReader r = new GzipRecordingReader();
            boolean gotAny = false;
            while (true) {
                int type;
                int len;
                try {
                    type = in.readInt();
                    len = in.readInt();
                    byte[] body = new byte[len];
                    in.readFully(body);
                    switch (type) {
                        case RecordingFormat.SEC_META -> { r.meta = body; gotAny = true; }
                        case RecordingFormat.SEC_PALETTE -> { r.palette = parsePalette(body); gotAny = true; }
                        case RecordingFormat.SEC_BLOCKS -> {
                            BlockDataBox b = parseBlocks(body);
                            r.blockData = b.data;
                            r.blockSizeX = b.sizeX;
                            r.blockSizeY = b.sizeY;
                            r.blockSizeZ = b.sizeZ;
                            gotAny = true;
                        }
                        case RecordingFormat.SEC_BLOCK_NBT -> { r.blockNbt = body; gotAny = true; }
                        case RecordingFormat.SEC_ENTITIES -> { r.entities = body; gotAny = true; }
                        case RecordingFormat.SEC_TIMELINE -> r.timelineFragments.add(body);
                        default -> {/* unknown section — skip */}
                    }
                } catch (EOFException eof) {
                    break; // truncated tail after a crash — keep what we have
                }
            }
            return gotAny ? r : null;
        } catch (IOException e) {
            return null;
        }
    }

    public byte[] meta() { return meta; }
    public List<String> palette() { return palette; }
    public int[] blockData() { return blockData; }
    public int blockSizeX() { return blockSizeX; }
    public int blockSizeY() { return blockSizeY; }
    public int blockSizeZ() { return blockSizeZ; }
    public byte[] blockNbt() { return blockNbt; }
    public byte[] entities() { return entities; }

    /** Decode all accumulated timeline fragments into a time-sorted list. */
    public List<dev.idebugger.echoreplay.model.TimelineEvent> timeline() {
        List<dev.idebugger.echoreplay.model.TimelineEvent> out = new ArrayList<>();
        for (byte[] frag : timelineFragments) {
            try {
                Io.LeIn in = Io.leIn(frag);
                long t = in.readLong();
                int typeId = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                byte[] body = new byte[len];
                in.readFully(body);
                var ev = TimelineCodec.decode(body, typeId, t, palette);
                if (ev != null) out.add(ev);
            } catch (IOException ignored) { java.util.logging.Logger.getLogger("EchoReplay").log(java.util.logging.Level.FINE, "EchoReplay: suppressed IOException", ignored);
            }
        }
        out.sort(java.util.Comparator.comparingLong(dev.idebugger.echoreplay.model.TimelineEvent::tickMillis));
        return out;
    }

    /**
     * Drop the raw timeline fragment bytes after decoding. Halves peak memory
     * on large recordings (fragments + decoded objects otherwise coexist).
     */
    public void releaseFragments() {
        timelineFragments.clear();
    }

    private static List<String> parsePalette(byte[] body) throws IOException {
        Io.LeIn in = Io.leIn(body);
        int count = in.readInt();
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(Io.readUtf(in));
        }
        return list;
    }

    private record BlockDataBox(int sizeX, int sizeY, int sizeZ, int[] data) {}

    private static BlockDataBox parseBlocks(byte[] body) throws IOException {
        Io.LeIn in = Io.leIn(body);
        int sizeX = in.readInt();
        int sizeY = in.readInt();
        int sizeZ = in.readInt();
        int bits = in.readUnsignedByte();
        int total = sizeX * sizeY * sizeZ;
        int perLong = Math.min(64 / bits, 32);
        int longCount = in.readInt();
        int[] data = new int[total];
        long mask = (1L << bits) - 1;
        for (int li = 0; li < longCount; li++) {
            long packed = in.readLong();
            int start = li * perLong;
            for (int i = 0; i < perLong; i++) {
                int idx = start + i;
                if (idx >= total) break;
                data[idx] = (int) ((packed >> (i * bits)) & mask);
            }
        }
        return new BlockDataBox(sizeX, sizeY, sizeZ, data);
    }

    public static void main(String[] ignored) throws IOException {
        // self-check for the block packing round-trip
        int[] data = {0, 1, 2, 3, 4, 5};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        var w = new GzipRecordingWriter(bos);
        w.writeBlocks(1, 6, 1, data, 3);
        w.close();
        var r = read(new java.io.ByteArrayInputStream(bos.toByteArray()));
        System.out.println("roundtrip sizeX=" + r.blockSizeX + " sizeY=" + r.blockSizeY);
    }
}
