package ds2ach.setup;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Extrahiert pro Karte aus dem Spielarchiv: Navigationsnetz (begehbare Flächen), Leuchtfeuer, Item-Fundorte und
 * Gebietsübergänge mit Positionen. Ergebnis: extracted/maps/mAA_BB.json. Alle Positionen im Koordinatensystem des
 * Kartenlayouts (MSB), in dem auch die Spielerposition im Spielstand steht.
 */
public final class MapExtractor {
    /** Alle Karten mit Layout und Navigationsnetz (laut Archivverzeichnis). */
    static final String[] MAPS = {"m10_02", "m10_04", "m10_10", "m10_14", "m10_15", "m10_16", "m10_17", "m10_18", "m10_19",
            "m10_23", "m10_25", "m10_27", "m10_29", "m10_30", "m10_31", "m10_32", "m10_33", "m10_34", "m20_10", "m20_11",
            "m20_21", "m20_24", "m20_26", "m40_03", "m50_35", "m50_36", "m50_37", "m50_38"};
    static final int INSTANCE_BONFIRE = 100;

    record Part(int type, String name, float x, float y, float z, int instanceId) {}

    public static int extractAll(Path game, Path outDir) throws IOException {
        ArchiveReader arc = new ArchiveReader(game, "GameDataEbl");
        Files.createDirectories(outDir);
        ObjectMapper json = new ObjectMapper();
        int ok = 0;
        for (String m : MAPS) {
            String full = m + "_00_00";
            byte[] msb = arc.read("/map/" + full + "/" + full + ".msb");
            byte[] ngp = arc.read("/map/" + full + "/" + full + ".ngp");
            byte[] inst = arc.read("/param/mapobjectinstanceparam_" + full + ".param");
            byte[] evloc = arc.read("/param/eventlocation_" + full + ".param");
            if (msb == null || ngp == null) {
                System.out.println("  " + m + ": nicht im Archiv, übersprungen");
                continue;
            }
            Map<String, Object> doc = extract(m, msb, ngp, inst, evloc);
            json.writeValue(outDir.resolve(m + ".json").toFile(), doc);
            @SuppressWarnings("unchecked") Map<String, Object> net = (Map<String, Object>) doc.get("netz");
            System.out.printf("  %s: %d Dreiecke, %d Leuchtfeuer, %d Item-Fundorte, %d Übergänge%n", m,
                    ((int[]) net.get("dreiecke")).length / 3, ((List<?>) doc.get("leuchtfeuer")).size(),
                    ((List<?>) doc.get("items")).size(), ((List<?>) doc.get("uebergaenge")).size());
            ok++;
        }
        return ok;
    }

    static Map<String, Object> extract(String map, byte[] msb, byte[] ngp, byte[] inst, byte[] evloc) {
        int area = Integer.parseInt(map.substring(1, 3)), num = Integer.parseInt(map.substring(4, 6));
        int mapId = (area << 24) | (num << 16);
        List<Part> parts = parts(msb);
        List<Part> navParts = parts.stream().filter(p -> p.type == 4).toList();

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("karte", String.format("0x%08X", mapId));
        doc.put("netz", navmesh(ngp, navParts));

        Map<Integer, int[]> instances = instances(inst); // ID -> {Typ, Feld0, Item-Lot}
        List<Map<String, Object>> bonfires = new ArrayList<>(), items = new ArrayList<>(), exits = new ArrayList<>();
        for (Part p : parts) {
            if (p.type != 1) continue;
            int[] in = instances.get(p.instanceId);
            if (in == null) continue;
            if (in[0] == INSTANCE_BONFIRE && in[1] > 0) bonfires.add(point("id", in[1], p.x, p.y, p.z));
            if (in[2] != 0) {
                Map<String, Object> it = point("lot", in[2], p.x, p.y, p.z);
                it.put("typ", in[0]); // 120 = Truhe, 130 = liegendes Item / Leiche
                items.add(it);
            }
        }
        // Übergänge: Event-Bereiche, deren letztes Feld eine andere Karten-ID ist (z. B. 10040000 = Majula)
        float[] origin = navParts.isEmpty() ? new float[3] : new float[]{navParts.get(0).x, navParts.get(0).y, navParts.get(0).z};
        if (evloc != null) {
            ByteBuffer b = ByteBuffer.wrap(evloc).order(ByteOrder.LITTLE_ENDIAN);
            int rows = Short.toUnsignedInt(b.getShort(0x0A));
            int self = area * 1_000_000 + num * 10_000;
            for (int i = 0; i < rows; i++) {
                int off = (int) b.getLong(0x40 + 24 * i + 8);
                if (off + 48 > evloc.length) continue;
                int target = b.getInt(off + 44);
                boolean mapLike = target >= 10_000_000 && target <= 59_999_999 && target % 10_000 == 0 && target != self;
                if (!mapLike) continue;
                Map<String, Object> e = point("ziel", target, b.getFloat(off) + origin[0], b.getFloat(off + 4) + origin[1], b.getFloat(off + 8) + origin[2]);
                e.put("karte", String.format("0x%08X", ((target / 1_000_000) << 24) | (((target / 10_000) % 100) << 16)));
                exits.add(e);
            }
        }
        doc.put("leuchtfeuer", bonfires);
        doc.put("items", items);
        doc.put("uebergaenge", exits);
        return doc;
    }

    static Map<String, Object> point(String key, int id, float x, float y, float z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, id);
        m.put("x", round(x));
        m.put("y", round(y));
        m.put("z", round(z));
        return m;
    }

    static double round(float v) {
        return Math.round(v * 100) / 100.0;
    }

    /** MSB (DS2): Parameterlisten nacheinander, PARTS_PARAM_ST enthält Kartenstücke, Objekte, Navmesh-Teile usw. */
    static List<Part> parts(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        List<Part> out = new ArrayList<>();
        long pos = new String(d, 0, 4, StandardCharsets.US_ASCII).equals("MSB ") ? 0x10 : 0;
        for (int guard = 0; guard < 32; guard++) {
            int p = (int) pos;
            int count = b.getInt(p + 4);
            String name = utf16(d, (int) b.getLong(p + 8));
            long next = b.getLong(p + 16 + 8 * (count - 1));
            if (name.startsWith("PARTS_PARAM")) {
                for (int i = 0; i < count - 1; i++) {
                    int o = (int) b.getLong(p + 16 + 8 * i);
                    int type = d[o + 8];
                    int instance = 0;
                    if (type == 1) instance = b.getInt(o + (int) b.getLong(o + 0x70)); // Objekt: MapObjectInstanceParam-ID
                    out.add(new Part(type, utf16(d, o + (int) b.getLong(o)), b.getFloat(o + 16), b.getFloat(o + 20), b.getFloat(o + 24), instance));
                }
            }
            if (next == 0) break;
            pos = next;
        }
        return out;
    }

    /** Navigationsnetz (NVG2, Scholar): Teilnetze mit Punkten relativ zum Ursprung des jeweiligen Navmesh-Teils. */
    static Map<String, Object> navmesh(byte[] d, List<Part> navParts) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int meshCount = b.getInt(8);
        List<Double> pts = new ArrayList<>();
        List<Integer> tris = new ArrayList<>();
        int base = 0;
        for (int m = 0; m < meshCount; m++) {
            int o = (int) b.getLong(0x40 + 8 * m);
            int vc = b.getInt(o + 40);
            int fc = Short.toUnsignedInt(b.getShort(o + 44));
            long vOff = b.getLong(o + 64), fOff = b.getLong(o + 80);
            // Ursprung: Navmesh-Teil gleicher Reihenfolge, sonst der erste
            Part origin = navParts.isEmpty() ? null : navParts.get(m < navParts.size() ? m : 0);
            float ox = origin == null ? 0 : origin.x, oy = origin == null ? 0 : origin.y, oz = origin == null ? 0 : origin.z;
            for (int i = 0; i < vc; i++) {
                int v = (int) vOff + 12 * i;
                pts.add(round(b.getFloat(v) + ox));
                pts.add(round(b.getFloat(v + 4) + oy));
                pts.add(round(b.getFloat(v + 8) + oz));
            }
            for (int i = 0; i < fc; i++) {
                int f = (int) fOff + 12 * i;
                int a = b.getShort(f), c = b.getShort(f + 2), e = b.getShort(f + 4);
                if (a < 0 || c < 0 || e < 0 || a >= vc || c >= vc || e >= vc) continue;
                tris.add(base + a);
                tris.add(base + c);
                tris.add(base + e);
            }
            base += vc;
        }
        Map<String, Object> net = new LinkedHashMap<>();
        net.put("punkte", pts.stream().mapToDouble(Double::doubleValue).toArray());
        net.put("dreiecke", tris.stream().mapToInt(Integer::intValue).toArray());
        return net;
    }

    /** mapobjectinstanceparam: Zeilen-ID -> {Instanz-Typ (+20), Feld 0 (+0, bei Leuchtfeuern die ID), Item-Lot (+36)}. */
    static Map<Integer, int[]> instances(byte[] d) {
        Map<Integer, int[]> out = new HashMap<>();
        if (d == null) return out;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int rows = Short.toUnsignedInt(b.getShort(0x0A));
        for (int i = 0; i < rows; i++) {
            int id = (int) b.getLong(0x40 + 24 * i), off = (int) b.getLong(0x40 + 24 * i + 8);
            if (off + 40 > d.length) continue;
            out.put(id, new int[]{b.getInt(off + 20), b.getInt(off), b.getInt(off + 36)});
        }
        return out;
    }

    static String utf16(byte[] d, int off) {
        int end = off;
        while (end + 1 < d.length && (d[end] != 0 || d[end + 1] != 0)) end += 2;
        return new String(d, off, end - off, StandardCharsets.UTF_16LE);
    }
}
