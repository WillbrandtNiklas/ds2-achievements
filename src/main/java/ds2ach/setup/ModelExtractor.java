package ds2ach.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

/**
 * Extrahiert pro Karte die echten Kartenmodelle als Tonmodell: extracted/models/mAA_BB.bin.gz.
 * <ul>
 *   <li>Modelle: model/map/mAA_BB_00_00.mapbhd/.mapbdt (BXF4-Binder mit DCX-komprimierten FLVER2-Dateien)</li>
 *   <li>Platzierung: Kartenstücke (MapPiece) aus dem Kartenlayout (MSB) mit Position, Drehung (X, dann Z, dann Y) und Skalierung</li>
 *   <li>Weggelassen: Himmel und Kulisse (Material NoLit), Schattenkörper, Leuchteffekte, Gras, Blätter, Wasser,
 *       riesige Platten (Kante über 60 m) und alles weit weg von begehbarer Fläche</li>
 *   <li>Vereinfacht: Punkte näher als 15 cm werden zusammengelegt</li>
 *   <li>Je Dreieck die Höhe über dem höchsten Weg in der Umgebung, Dreiecke danach sortiert (für die Schnitthöhe)</li>
 * </ul>
 * Formate nachgebaut nach SoulsFormats (BXF4, DCX, FLVER2, MSB2). Nur lesend.
 */
public final class ModelExtractor {
    static final float MAX_CUT = 40;     // höher über dem Weg wird nichts gespeichert (Regler geht bis 40 m)
    static final float WELD = 0.15f;     // Punkte in diesem Raster werden zusammengelegt
    static final float MAX_EDGE = 60;    // längere Kanten gehören zu Kulissen-Platten
    static final int NAV_CELL = 4, NAV_REACH = 3; // Wege-Raster 4 m, Umkreis 3 Zellen

    record Part(int model, float[] pos, float[] rot, float[] scale) {}

    public static int extractAll(Path game, Path mapsDir, Path outDir) throws IOException {
        ArchiveReader arc = new ArchiveReader(game, "GameDataEbl");
        Files.createDirectories(outDir);
        ObjectMapper json = new ObjectMapper();
        int ok = 0;
        for (String m : MapExtractor.MAPS) {
            String full = m + "_00_00";
            Path navFile = mapsDir.resolve(m + ".json");
            byte[] bhd = arc.read("/model/map/" + full + ".mapbhd"), bdt = arc.read("/model/map/" + full + ".mapbdt");
            byte[] msb = arc.read("/map/" + full + "/" + full + ".msb");
            if (bhd == null || bdt == null || msb == null || !Files.exists(navFile)) {
                System.out.println("  " + m + ": keine Modelle, übersprungen");
                continue;
            }
            try {
                long t0 = System.nanoTime();
                JsonNode nav = json.readTree(navFile.toFile()).path("netz").path("punkte");
                float[] navPts = new float[nav.size()];
                for (int i = 0; i < navPts.length; i++) navPts[i] = (float) nav.get(i).asDouble();
                Result r = build(bhd, bdt, msb, navPts);
                r.write(outDir.resolve(m + ".bin.gz"));
                System.out.printf(Locale.ROOT, "  %s: %d Modelle, %d Kartenstücke, %d -> %d Dreiecke (%.1f s)%n", m, r.models, r.pieces,
                        r.rawTris, r.triCount, (System.nanoTime() - t0) / 1e9);
                ok++;
            } catch (Exception e) {
                System.out.println("  " + m + ": Fehler beim Lesen der Modelle: " + e);
            }
        }
        return ok;
    }

    // ---------- Ergebnis ----------

    static final class Result {
        int models, pieces, rawTris, triCount, vertCount;
        float[] verts;
        int[] tris;
        short[] heights; // Dezimeter über dem Weg

        /** Binärformat (GZIP, big endian): "DS2MODEL", Version, Punkte, Dreiecke, Punkte xyz, Dreiecke abc, Höhen. */
        void write(Path p) throws IOException {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(p), 1 << 16)))) {
                out.writeBytes("DS2MODEL");
                out.writeInt(1);
                out.writeInt(vertCount);
                out.writeInt(triCount);
                for (int i = 0; i < vertCount * 3; i++) out.writeFloat(verts[i]);
                for (int i = 0; i < triCount * 3; i++) out.writeInt(tris[i]);
                for (int i = 0; i < triCount; i++) out.writeShort(heights[i]);
            }
        }
    }

    static Result build(byte[] bhd, byte[] bdt, byte[] msb, float[] nav) throws Exception {
        Map<Long, Float> navTop = navTops(nav);
        Map<String, float[]> models = new HashMap<>();
        for (var e : readBxf4(bhd, bdt).entrySet()) {
            if (!e.getKey().endsWith(".flv.dcx")) continue;
            String name = e.getKey().replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).replace(".flv.dcx", "");
            models.put(name, readFlver(dcx(e.getValue())));
        }
        List<String> modelNames = new ArrayList<>();
        List<Part> pieces = readMapPieces(msb, modelNames);

        Result r = new Result();
        r.models = models.size();
        r.pieces = pieces.size();
        Map<Long, Integer> weld = new HashMap<>();
        FloatList verts = new FloatList();
        IntList tris = new IntList();
        ShortList heights = new ShortList();
        float[] w = new float[9];
        for (Part p : pieces) {
            float[] mt = p.model < modelNames.size() ? models.get(modelNames.get(p.model)) : null;
            if (mt == null) continue;
            for (int t = 0; t < mt.length; t += 9) {
                r.rawTris++;
                for (int k = 0; k < 3; k++) transform(mt[t + 3 * k], mt[t + 3 * k + 1], mt[t + 3 * k + 2], p, w, 3 * k);
                if (edge(w, 0, 1) > MAX_EDGE || edge(w, 1, 2) > MAX_EDGE || edge(w, 0, 2) > MAX_EDGE) continue;
                Float top = navTop.get(cell((w[0] + w[3] + w[6]) / 3, (w[2] + w[5] + w[8]) / 3));
                if (top == null) continue; // weit weg von begehbarer Fläche
                float h = Math.min(w[1], Math.min(w[4], w[7])) - top;
                if (h > MAX_CUT) continue;
                int a = vertex(weld, verts, w, 0), b = vertex(weld, verts, w, 3), c = vertex(weld, verts, w, 6);
                if (a == b || b == c || a == c) continue; // durch das Zusammenlegen entartet
                tris.add(a);
                tris.add(b);
                tris.add(c);
                heights.add((short) Math.max(-32000, Math.min(32000, Math.round(h * 10))));
            }
        }
        // Nach Höhe sortieren, damit die Schnitthöhe nur die ersten n Dreiecke anzeigen muss
        int n = heights.size;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(i -> heights.data[i]));
        r.triCount = n;
        r.tris = new int[n * 3];
        r.heights = new short[n];
        for (int i = 0; i < n; i++) {
            int o = order[i];
            System.arraycopy(tris.data, o * 3, r.tris, i * 3, 3);
            r.heights[i] = heights.data[o];
        }
        r.vertCount = verts.size / 3;
        r.verts = Arrays.copyOf(verts.data, verts.size);
        return r;
    }

    private static int vertex(Map<Long, Integer> weld, FloatList verts, float[] w, int o) {
        long key = ((long) Math.round(w[o] / WELD) & 0x1FFFFF) << 42 | ((long) Math.round(w[o + 1] / WELD) & 0x1FFFFF) << 21
                | ((long) Math.round(w[o + 2] / WELD) & 0x1FFFFF);
        Integer idx = weld.get(key);
        if (idx != null) return idx;
        int i = verts.size / 3;
        verts.add(w[o]);
        verts.add(w[o + 1]);
        verts.add(w[o + 2]);
        weld.put(key, i);
        return i;
    }

    /** Höchster Navmesh-Punkt je 4-m-Zelle, auf die Nachbarzellen im Umkreis verteilt. */
    static Map<Long, Float> navTops(float[] nav) {
        Map<Long, Float> top = new HashMap<>();
        for (int i = 0; i + 2 < nav.length; i += 3) {
            int cx = (int) Math.floor(nav[i] / NAV_CELL), cz = (int) Math.floor(nav[i + 2] / NAV_CELL);
            for (int dx = -NAV_REACH; dx <= NAV_REACH; dx++)
                for (int dz = -NAV_REACH; dz <= NAV_REACH; dz++) top.merge(key2(cx + dx, cz + dz), nav[i + 1], Math::max);
        }
        return top;
    }

    static long cell(float x, float z) {
        return key2((int) Math.floor(x / NAV_CELL), (int) Math.floor(z / NAV_CELL));
    }

    static long key2(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    static double edge(float[] t, int i, int j) {
        double dx = t[3 * i] - t[3 * j], dy = t[3 * i + 1] - t[3 * j + 1], dz = t[3 * i + 2] - t[3 * j + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Skalieren, um X, dann Z, dann Y drehen (Grad), verschieben. Mit dem Navigationsnetz geprüft (98 % Boden unter den Wegen). */
    static void transform(float x, float y, float z, Part p, float[] out, int o) {
        double vx = x * p.scale[0], vy = y * p.scale[1], vz = z * p.scale[2];
        double rx = Math.toRadians(p.rot[0]), ry = Math.toRadians(p.rot[1]), rz = Math.toRadians(p.rot[2]);
        double y1 = vy * Math.cos(rx) - vz * Math.sin(rx), z1 = vy * Math.sin(rx) + vz * Math.cos(rx);
        double x2 = vx * Math.cos(rz) - y1 * Math.sin(rz), y2 = vx * Math.sin(rz) + y1 * Math.cos(rz);
        double x3 = x2 * Math.cos(ry) + z1 * Math.sin(ry), z3 = -x2 * Math.sin(ry) + z1 * Math.cos(ry);
        out[o] = (float) x3 + p.pos[0];
        out[o + 1] = (float) y2 + p.pos[1];
        out[o + 2] = (float) z3 + p.pos[2];
    }

    // ---------- BXF4 und DCX ----------

    /** BHF4-Kopf (Dateiliste) + BDF4-Daten. Eintrag 0x24 Bytes: Flags, -1, Größe (64), unkomprimiert (64), Offset (32), ID, Name. */
    static Map<String, byte[]> readBxf4(byte[] bhd, byte[] bdt) {
        ByteBuffer b = ByteBuffer.wrap(bhd).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.getInt(0x0C);
        int headersOff = (int) b.getLong(0x10), entrySize = (int) b.getLong(0x20);
        boolean unicode = bhd[0x30] != 0;
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int o = headersOff + i * entrySize;
            int size = (int) b.getLong(o + 8);
            long off = Integer.toUnsignedLong(b.getInt(o + 24));
            int nameOff = b.getInt(o + 32);
            String name = unicode ? MapExtractor.utf16(bhd, nameOff) : ascii(bhd, nameOff);
            if (size <= 0 || off + size > bdt.length) continue;
            out.put(name, Arrays.copyOfRange(bdt, (int) off, (int) off + size));
        }
        return out;
    }

    static String ascii(byte[] d, int off) {
        int e = off;
        while (e < d.length && d[e] != 0) e++;
        return new String(d, off, e - off, StandardCharsets.US_ASCII);
    }

    /** DCX mit DFLT: zlib-Strom nach dem Kopf. */
    static byte[] dcx(byte[] d) throws IOException {
        int z = -1;
        for (int i = 4; i < Math.min(d.length - 1, 0x100); i++) {
            int a = d[i] & 0xFF, c = d[i + 1] & 0xFF;
            if (a == 0x78 && (c == 0xDA || c == 0x9C || c == 0x01)) {
                z = i;
                break;
            }
        }
        if (z < 0) throw new IOException("DCX ohne zlib-Daten");
        Inflater inf = new Inflater();
        inf.setInput(d, z, d.length - z);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(d.length * 4);
        byte[] buf = new byte[1 << 16];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bo.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("DCX defekt", e);
        } finally {
            inf.end();
        }
        return bo.toByteArray();
    }

    // ---------- FLVER2 ----------

    static boolean skipMaterial(String mtd) {
        return mtd.contains("NoLit") || mtd.contains("Shadow") || mtd.contains("_Add") || mtd.contains("Grass")
                || mtd.contains("Alp") || mtd.contains("Water");
    }

    /** Dreiecke (je 9 Floats, Modellkoordinaten) der höchsten Detailstufe, ohne ausgelassene Materialien. */
    static float[] readFlver(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length < 0x80 || !new String(d, 0, 5, StandardCharsets.US_ASCII).equals("FLVER")) return new float[0];
        int dataOffset = b.getInt(0x0C);
        int dummyCount = b.getInt(0x10 + 4), matCount = b.getInt(0x18), boneCount = b.getInt(0x1C), meshCount = b.getInt(0x20), vbCount = b.getInt(0x24);
        int headerIndexSize = d[0x48] & 0xFF;
        int faceSetCount = b.getInt(0x50), layoutCount = b.getInt(0x54);
        int matStart = 0x80 + dummyCount * 0x40;
        int meshStart = matStart + matCount * 0x20 + boneCount * 0x80;
        int fsStart = meshStart + meshCount * 0x30, vbStart = fsStart + faceSetCount * 0x20, layoutStart = vbStart + vbCount * 0x20;

        int[] posOffset = new int[layoutCount];
        Arrays.fill(posOffset, -1);
        for (int l = 0; l < layoutCount; l++) {
            int lo = layoutStart + 16 * l, mc = b.getInt(lo), mo = b.getInt(lo + 12);
            for (int m = 0; m < mc; m++) {
                int mm = mo + 20 * m;
                if (b.getInt(mm + 12) == 0 && b.getInt(mm + 8) == 2) posOffset[l] = b.getInt(mm + 4); // Position, Float3
            }
        }
        FloatList out = new FloatList();
        for (int mi = 0; mi < meshCount; mi++) {
            int mo = meshStart + 0x30 * mi;
            int mat = b.getInt(mo + 4);
            if (mat < 0 || mat >= matCount) continue;
            String mtd = MapExtractor.utf16(d, b.getInt(matStart + mat * 0x20 + 4));
            if (skipMaterial(mtd)) continue;
            int fsCount = b.getInt(mo + 0x20), fsOff = b.getInt(mo + 0x24), vbc = b.getInt(mo + 0x28), vbOff = b.getInt(mo + 0x2C);
            float[] verts = null;
            for (int k = 0; k < vbc && verts == null; k++) {
                int vo = vbStart + 0x20 * b.getInt(vbOff + 4 * k);
                int layout = b.getInt(vo + 4), vsize = b.getInt(vo + 8), vcount = b.getInt(vo + 12), bufOff = b.getInt(vo + 28);
                if (layout < 0 || layout >= layoutCount || posOffset[layout] < 0) continue;
                verts = new float[vcount * 3];
                for (int v = 0; v < vcount; v++) {
                    int q = dataOffset + bufOff + v * vsize + posOffset[layout];
                    verts[3 * v] = b.getFloat(q);
                    verts[3 * v + 1] = b.getFloat(q + 4);
                    verts[3 * v + 2] = b.getFloat(q + 8);
                }
            }
            if (verts == null) continue;
            for (int f = 0; f < fsCount; f++) {
                int fo = fsStart + 0x20 * b.getInt(fsOff + 4 * f);
                if ((b.getInt(fo) & 0xFF000000) != 0) continue; // nur höchste Detailstufe, keine Bewegungsunschärfe
                boolean strip = d[fo + 4] != 0;
                int idxCount = b.getInt(fo + 8), idxOff = b.getInt(fo + 12), idxSize = b.getInt(fo + 24);
                if (idxSize == 0) idxSize = headerIndexSize;
                if (idxSize != 16 && idxSize != 32) continue;
                int[] idx = new int[idxCount];
                for (int i = 0; i < idxCount; i++) {
                    int q = dataOffset + idxOff + i * (idxSize / 8);
                    idx[i] = idxSize == 32 ? b.getInt(q) : Short.toUnsignedInt(b.getShort(q));
                }
                int restart = idxSize == 32 ? -1 : 0xFFFF;
                if (strip) {
                    boolean flip = false;
                    for (int i = 0; i + 2 < idx.length; i++) {
                        int i0 = idx[i], i1 = idx[i + 1], i2 = idx[i + 2];
                        if (i0 == restart || i1 == restart || i2 == restart) {
                            flip = false;
                            continue;
                        }
                        if (i0 != i1 && i1 != i2 && i0 != i2) {
                            if (flip) addTri(out, verts, i0, i2, i1);
                            else addTri(out, verts, i0, i1, i2);
                        }
                        flip = !flip;
                    }
                } else for (int i = 0; i + 2 < idx.length; i += 3) addTri(out, verts, idx[i], idx[i + 1], idx[i + 2]);
            }
        }
        return Arrays.copyOf(out.data, out.size);
    }

    private static void addTri(FloatList out, float[] v, int a, int b, int c) {
        int n = v.length / 3;
        if (a >= n || b >= n || c >= n) return;
        for (int i : new int[]{a, b, c}) {
            out.add(v[3 * i]);
            out.add(v[3 * i + 1]);
            out.add(v[3 * i + 2]);
        }
    }

    // ---------- MSB: Modelle und Kartenstücke ----------

    static List<Part> readMapPieces(byte[] d, List<String> modelNames) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        List<Part> out = new ArrayList<>();
        long pos = new String(d, 0, 4, StandardCharsets.US_ASCII).equals("MSB ") ? 0x10 : 0;
        for (int guard = 0; guard < 32; guard++) {
            int p = (int) pos, count = b.getInt(p + 4);
            String pname = MapExtractor.utf16(d, (int) b.getLong(p + 8));
            for (int i = 0; i < count - 1; i++) {
                int o = (int) b.getLong(p + 16 + 8 * i);
                if (pname.startsWith("MODEL_PARAM")) modelNames.add(MapExtractor.utf16(d, o + (int) b.getLong(o)));
                if (pname.startsWith("PARTS_PARAM") && d[o + 8] == 0) { // Kartenstück
                    float[] f = new float[9];
                    for (int k = 0; k < 9; k++) f[k] = b.getFloat(o + 16 + 4 * k);
                    out.add(new Part(b.getShort(o + 12), Arrays.copyOfRange(f, 0, 3), Arrays.copyOfRange(f, 3, 6), Arrays.copyOfRange(f, 6, 9)));
                }
            }
            long next = b.getLong(p + 16 + 8 * (count - 1));
            if (next == 0) break;
            pos = next;
        }
        return out;
    }

    // ---------- Primitive Listen (Millionen Einträge ohne Boxing) ----------

    static final class FloatList {
        float[] data = new float[1 << 16];
        int size;

        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
    }

    static final class IntList {
        int[] data = new int[1 << 16];
        int size;

        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
    }

    static final class ShortList {
        short[] data = new short[1 << 16];
        int size;

        void add(short v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }
    }
}
