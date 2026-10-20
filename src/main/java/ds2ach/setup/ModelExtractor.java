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
 * Extrahiert pro Karte die echten Kartenmodelle mit Texturen: extracted/models/mAA_BB.bin.gz.
 * <ul>
 *   <li>Modelle: model/map/mAA_BB_00_00.mapbhd/.mapbdt (BXF4-Binder mit DCX-komprimierten FLVER2-Dateien)</li>
 *   <li>Texturen: model/map/tAA_BB_00_00.tpfbhd/.tpfbdt (TPF mit DDS), nur Farbtexturen (g_DiffuseTexture),
 *       DXT-Daten ab der Mipmap-Stufe mit höchstens 512 px</li>
 *   <li>Platzierung: Kartenstücke (MapPiece) aus dem Kartenlayout (MSB), Drehung X, dann Z, dann Y</li>
 *   <li>Weggelassen: Himmel und Kulisse (NoLit), Schattenkörper, Leuchteffekte, Gras, Wasser,
 *       riesige Platten (Kante über 60 m) und alles weit weg von begehbarer Fläche</li>
 *   <li>Je Dreieck die Höhe über dem höchsten Weg in der Umgebung; pro Textur-Gruppe danach sortiert (Schnitthöhe)</li>
 * </ul>
 * Formate nachgebaut nach SoulsFormats (BXF4, DCX, FLVER2, MSB2, TPF). Nur lesend.
 */
public final class ModelExtractor {
    static final float MAX_CUT = 40;     // höher über dem Weg wird nichts gespeichert (Regler geht bis 40 m)
    static final float MAX_EDGE = 60;    // längere Kanten gehören zu Kulissen-Platten
    static final int NAV_CELL = 4, NAV_REACH = 3; // Wege-Raster 4 m, Umkreis 3 Zellen
    static final int MAX_TEX = 512;      // Texturauflösung
    static final float UV_FACTOR = 2048; // DS2 (FLVER ab 0x2000F): Texturkoordinaten = int16 / 2048

    record Part(int model, float[] pos, float[] rot, float[] scale) {}

    /** Ein Mesh eines Modells: Positionen, Texturkoordinaten, Dreiecke (lokale Indizes), Farbtextur. */
    record MeshData(float[] pos, float[] uv, int[] tris, String tex, boolean alpha) {}

    /** DXT-Textur: Format 1 = DXT1, 5 = DXT5; Daten = Mipmap-Kette ab Stufe w x h. */
    record TexData(String name, int format, int w, int h, int mips, byte[] data) {}

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
            String tbase = "/model/map/t" + full.substring(1);
            byte[] tbhd = arc.read(tbase + ".tpfbhd"), tbdt = arc.read(tbase + ".tpfbdt");
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
                Map<String, byte[]> tpfs = new HashMap<>();
                if (tbhd != null && tbdt != null)
                    for (var e : readBxf4(tbhd, tbdt).entrySet()) tpfs.put(texKey(e.getKey()), e.getValue());
                int found = r.loadTextures(tpfs);
                Path out = outDir.resolve(m + ".bin.gz");
                r.write(out);
                System.out.printf(Locale.ROOT, "  %s: %d Kartenstücke, %d -> %d Dreiecke, %d/%d Texturen, %.1f MB (%.1f s)%n", m, r.pieces,
                        r.rawTris, r.triCount(), found, r.groups.size(), Files.size(out) / 1e6, (System.nanoTime() - t0) / 1e9);
                ok++;
            } catch (Exception e) {
                System.out.println("  " + m + ": Fehler beim Lesen der Modelle: " + e);
            }
        }
        return ok;
    }

    /** "m10_10_00_00\Ballista_01_d.tpf.dcx" bzw. "...\tex\Ballista_01_d.tga" -> "ballista_01_d". */
    static String texKey(String path) {
        String p = path.replace('\\', '/');
        p = p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = p.indexOf('.');
        return dot > 0 ? p.substring(0, dot) : p;
    }

    // ---------- Ergebnis ----------

    static final class Group {
        final String tex;
        final boolean alpha;
        final IntList tris = new IntList();
        final ShortList heights = new ShortList();
        TexData texture;

        Group(String tex, boolean alpha) {
            this.tex = tex;
            this.alpha = alpha;
        }
    }

    static final class Result {
        int pieces, rawTris;
        final FloatList verts = new FloatList(); // x, y, z, u, v
        final List<Group> groups = new ArrayList<>();

        int triCount() {
            int n = 0;
            for (Group g : groups) n += g.heights.size;
            return n;
        }

        int loadTextures(Map<String, byte[]> tpfs) {
            int found = 0;
            for (Group g : groups) {
                if (g.tex == null) continue;
                byte[] tpf = tpfs.get(g.tex);
                if (tpf == null) continue;
                try {
                    g.texture = readTpf(dcx(tpf), g.tex);
                    if (g.texture != null) found++;
                } catch (IOException ignored) {
                }
            }
            return found;
        }

        /**
         * Binärformat (GZIP, big endian): "DS2MODL2", Punkte (x, y, z, u, v je float), Gruppen
         * (Textur-Index, Alpha, Dreiecke a b c, Höhen in dm, aufsteigend), Texturen (Name, Format, w, h, Mipmaps, DXT-Daten).
         */
        void write(Path p) throws IOException {
            // Gruppen intern nach Höhe sortieren
            List<TexData> texs = new ArrayList<>();
            Map<String, Integer> texIndex = new HashMap<>();
            for (Group g : groups)
                if (g.texture != null && !texIndex.containsKey(g.tex)) {
                    texIndex.put(g.tex, texs.size());
                    texs.add(g.texture);
                }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(p), 1 << 16)))) {
                out.writeBytes("DS2MODL2");
                int vc = verts.size / 5;
                out.writeInt(vc);
                for (int i = 0; i < verts.size; i++) out.writeFloat(verts.data[i]);
                out.writeInt(groups.size());
                for (Group g : groups) {
                    int n = g.heights.size;
                    Integer[] order = new Integer[n];
                    for (int i = 0; i < n; i++) order[i] = i;
                    Arrays.sort(order, Comparator.comparingInt(i -> g.heights.data[i]));
                    out.writeInt(g.texture == null ? -1 : texIndex.get(g.tex));
                    out.writeBoolean(g.alpha);
                    out.writeInt(n);
                    for (int i = 0; i < n; i++) {
                        int o = order[i];
                        out.writeInt(g.tris.data[3 * o]);
                        out.writeInt(g.tris.data[3 * o + 1]);
                        out.writeInt(g.tris.data[3 * o + 2]);
                    }
                    for (int i = 0; i < n; i++) out.writeShort(g.heights.data[order[i]]);
                }
                out.writeInt(texs.size());
                for (TexData t : texs) {
                    out.writeUTF(t.name);
                    out.writeByte(t.format);
                    out.writeInt(t.w);
                    out.writeInt(t.h);
                    out.writeInt(t.mips);
                    out.writeInt(t.data.length);
                    out.write(t.data);
                }
            }
        }
    }

    static Result build(byte[] bhd, byte[] bdt, byte[] msb, float[] nav) throws Exception {
        Map<Long, Float> navTop = navTops(nav);
        Map<String, List<MeshData>> models = new HashMap<>();
        for (var e : readBxf4(bhd, bdt).entrySet()) {
            if (!e.getKey().endsWith(".flv.dcx")) continue;
            models.put(texKey(e.getKey()), readFlver(dcx(e.getValue())));
        }
        List<String> modelNames = new ArrayList<>();
        List<Part> pieces = readMapPieces(msb, modelNames);
        Result r = new Result();
        r.pieces = pieces.size();
        Map<String, Group> groupByKey = new LinkedHashMap<>();
        float[] w = new float[9];
        for (Part p : pieces) {
            List<MeshData> meshes = p.model < modelNames.size() ? models.get(modelNames.get(p.model).toLowerCase(Locale.ROOT)) : null;
            if (meshes == null) continue;
            for (MeshData md : meshes) {
                int vc = md.pos.length / 3;
                float[] world = new float[vc * 3];
                for (int v = 0; v < vc; v++) transform(md.pos[3 * v], md.pos[3 * v + 1], md.pos[3 * v + 2], p, world, 3 * v);
                int[] remap = new int[vc];
                Arrays.fill(remap, -1);
                Group g = groupByKey.computeIfAbsent(md.tex + (md.alpha ? "#a" : ""), k -> {
                    Group ng = new Group(md.tex, md.alpha);
                    r.groups.add(ng);
                    return ng;
                });
                for (int t = 0; t + 2 < md.tris.length; t += 3) {
                    r.rawTris++;
                    for (int k = 0; k < 3; k++) System.arraycopy(world, 3 * md.tris[t + k], w, 3 * k, 3);
                    if (edge(w, 0, 1) > MAX_EDGE || edge(w, 1, 2) > MAX_EDGE || edge(w, 0, 2) > MAX_EDGE) continue;
                    Float top = navTop.get(cell((w[0] + w[3] + w[6]) / 3, (w[2] + w[5] + w[8]) / 3));
                    if (top == null) continue; // weit weg von begehbarer Fläche
                    float h = Math.min(w[1], Math.min(w[4], w[7])) - top;
                    if (h > MAX_CUT) continue;
                    for (int k = 0; k < 3; k++) {
                        int li = md.tris[t + k];
                        if (remap[li] < 0) {
                            remap[li] = r.verts.size / 5;
                            r.verts.add(world[3 * li]);
                            r.verts.add(world[3 * li + 1]);
                            r.verts.add(world[3 * li + 2]);
                            r.verts.add(md.uv == null ? 0 : md.uv[2 * li]);
                            r.verts.add(md.uv == null ? 0 : md.uv[2 * li + 1]);
                        }
                        g.tris.add(remap[li]);
                    }
                    g.heights.add((short) Math.max(-32000, Math.min(32000, Math.round(h * 10))));
                }
            }
        }
        r.groups.removeIf(g -> g.heights.size == 0);
        return r;
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

    // ---------- TPF ----------

    /** TPF (PC): Eintrag bei 0x10 (Offset, Größe, ...); Inhalt eine DDS-Datei. Nur DXT1/DXT5, ab Stufe <= 512 px. */
    static TexData readTpf(byte[] t, String name) {
        if (t.length < 0x30 || t[0] != 'T' || t[1] != 'P' || t[2] != 'F') return null;
        ByteBuffer b = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN);
        int off = b.getInt(0x10), size = b.getInt(0x14);
        if (off < 0 || off + 0x80 > t.length || off + size > t.length) return null;
        ByteBuffer d = ByteBuffer.wrap(t, off, size).slice().order(ByteOrder.LITTLE_ENDIAN);
        if (d.getInt(0) != 0x20534444) return null; // "DDS "
        int h = d.getInt(0x0C), w = d.getInt(0x10), mips = Math.max(1, d.getInt(0x1C));
        String cc = new String(t, off + 0x54, 4, StandardCharsets.US_ASCII);
        int format = cc.equals("DXT1") ? 1 : cc.equals("DXT5") ? 5 : 0;
        if (format == 0) return null;
        int block = format == 1 ? 8 : 16, pos = 0x80;
        while (Math.max(w, h) > MAX_TEX && mips > 1) {
            pos += Math.max(1, (w + 3) / 4) * Math.max(1, (h + 3) / 4) * block;
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            mips--;
        }
        // Mipmap-Kette ab dieser Stufe übernehmen (nur so viel, wie in der Datei steht)
        int len = 0, mw = w, mh = h, n = 0;
        while (n < mips && pos + len + Math.max(1, (mw + 3) / 4) * Math.max(1, (mh + 3) / 4) * block <= size) {
            len += Math.max(1, (mw + 3) / 4) * Math.max(1, (mh + 3) / 4) * block;
            mw = Math.max(1, mw / 2);
            mh = Math.max(1, mh / 2);
            n++;
        }
        if (n == 0) return null;
        return new TexData(name, format, w, h, n, Arrays.copyOfRange(t, off + pos, off + pos + len));
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
        return mtd.contains("NoLit") || mtd.contains("Shadow") || mtd.contains("_Add") || mtd.contains("Grass") || mtd.contains("Water");
    }

    /** Meshes der höchsten Detailstufe mit Positionen, Texturkoordinaten und Farbtextur; ausgelassene Materialien fehlen. */
    static List<MeshData> readFlver(byte[] d) {
        List<MeshData> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length < 0x80 || !new String(d, 0, 5, StandardCharsets.US_ASCII).equals("FLVER")) return out;
        int dataOffset = b.getInt(0x0C);
        int dummyCount = b.getInt(0x14), matCount = b.getInt(0x18), boneCount = b.getInt(0x1C), meshCount = b.getInt(0x20), vbCount = b.getInt(0x24);
        int headerIndexSize = d[0x48] & 0xFF;
        int faceSetCount = b.getInt(0x50), layoutCount = b.getInt(0x54), texCount = b.getInt(0x58);
        int matStart = 0x80 + dummyCount * 0x40;
        int meshStart = matStart + matCount * 0x20 + boneCount * 0x80;
        int fsStart = meshStart + meshCount * 0x30, vbStart = fsStart + faceSetCount * 0x20, layoutStart = vbStart + vbCount * 0x20;
        int texStart = layoutStart + layoutCount * 16;

        int[] posOffset = new int[layoutCount], uvOffset = new int[layoutCount];
        Arrays.fill(posOffset, -1);
        Arrays.fill(uvOffset, -1);
        for (int l = 0; l < layoutCount; l++) {
            int lo = layoutStart + 16 * l, mc = b.getInt(lo), mo = b.getInt(lo + 12);
            for (int m = 0; m < mc; m++) {
                int mm = mo + 20 * m, so = b.getInt(mm + 4), type = b.getInt(mm + 8), sem = b.getInt(mm + 12);
                if (sem == 0 && type == 2) posOffset[l] = so; // Position, Float3
                if (sem == 5 && uvOffset[l] < 0 && (type == 0x12 || type == 0x13 || type == 0x15 || type == 0x16)) uvOffset[l] = so; // erste UV
            }
        }
        for (int mi = 0; mi < meshCount; mi++) {
            int mo = meshStart + 0x30 * mi;
            int mat = b.getInt(mo + 4);
            if (mat < 0 || mat >= matCount) continue;
            int mbase = matStart + mat * 0x20;
            String mtd = MapExtractor.utf16(d, b.getInt(mbase + 4));
            if (skipMaterial(mtd)) continue;
            String tex = null;
            int tc = b.getInt(mbase + 8), ti = b.getInt(mbase + 12);
            for (int k = 0; k < tc && ti + k < texCount; k++) {
                int to = texStart + 0x20 * (ti + k);
                if (MapExtractor.utf16(d, b.getInt(to + 4)).equals("g_DiffuseTexture")) tex = texKey(MapExtractor.utf16(d, b.getInt(to)));
            }
            boolean alpha = mtd.contains("Alp");
            int fsCount = b.getInt(mo + 0x20), fsOff = b.getInt(mo + 0x24), vbc = b.getInt(mo + 0x28), vbOff = b.getInt(mo + 0x2C);
            float[] pos = null, uv = null;
            for (int k = 0; k < vbc; k++) {
                int vo = vbStart + 0x20 * b.getInt(vbOff + 4 * k);
                int layout = b.getInt(vo + 4), vsize = b.getInt(vo + 8), vcount = b.getInt(vo + 12), bufOff = b.getInt(vo + 28);
                if (layout < 0 || layout >= layoutCount) continue;
                if (pos == null && posOffset[layout] >= 0) {
                    pos = new float[vcount * 3];
                    for (int v = 0; v < vcount; v++) {
                        int q = dataOffset + bufOff + v * vsize + posOffset[layout];
                        pos[3 * v] = b.getFloat(q);
                        pos[3 * v + 1] = b.getFloat(q + 4);
                        pos[3 * v + 2] = b.getFloat(q + 8);
                    }
                }
                if (uv == null && uvOffset[layout] >= 0) {
                    uv = new float[vcount * 2];
                    for (int v = 0; v < vcount; v++) {
                        int q = dataOffset + bufOff + v * vsize + uvOffset[layout];
                        uv[2 * v] = b.getShort(q) / UV_FACTOR;
                        uv[2 * v + 1] = b.getShort(q + 2) / UV_FACTOR;
                    }
                }
            }
            if (pos == null) continue;
            if (uv != null && uv.length / 2 != pos.length / 3) uv = null;
            IntList tris = new IntList();
            int vcount = pos.length / 3;
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
                        if (i0 != i1 && i1 != i2 && i0 != i2) addTri(tris, vcount, i0, flip ? i2 : i1, flip ? i1 : i2);
                        flip = !flip;
                    }
                } else for (int i = 0; i + 2 < idx.length; i += 3) addTri(tris, vcount, idx[i], idx[i + 1], idx[i + 2]);
            }
            if (tris.size > 0) out.add(new MeshData(pos, uv, Arrays.copyOf(tris.data, tris.size), tex, alpha));
        }
        return out;
    }

    private static void addTri(IntList out, int n, int a, int b, int c) {
        if (a >= n || b >= n || c >= n) return;
        out.add(a);
        out.add(b);
        out.add(c);
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
