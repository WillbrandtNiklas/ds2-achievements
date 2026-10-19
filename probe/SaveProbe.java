import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Machbarkeitstest: liest eine KOPIE des DS2-SotFS-Spielstands und gibt aus, was sich lesen laesst.
 * Die Originaldatei wird nur kopiert, nie geoeffnet zum Schreiben.
 *
 *   java SaveProbe.java snap               Kopie des aktuellen Spielstands nach snapshots/ + Bericht
 *   java SaveProbe.java report <datei.sl2> Bericht zu einer vorhandenen Kopie
 *   java SaveProbe.java diff <a.sl2> <b.sl2>  Was hat sich zwischen zwei Kopien geaendert?
 */
public class SaveProbe {
    static final byte[] KEY = HexFormat.of().parseHex("599F9B699640A55236EE2D70835EC744");
    static final int SLOT_COUNT = 10;
    static final int MAP_BLOCK_START = 0x20, MAP_BLOCK_SIZE = 0xB10, MAP_BLOCK_COUNT = 42;
    static final int INV_START = 0x1E2C, INV_END = 0x10E1C, KEY_START = 0x10E30, KEY_END = 0x11DF0;
    static final int BOSS_BASE_GUESS = 0x27B14; // unbestaetigt, siehe Bericht

    static final Map<Integer, String> MAPS = new LinkedHashMap<>();
    static final Map<Integer, String> BOSSES = new LinkedHashMap<>();
    static final Map<Integer, String> KEY_ITEMS = new HashMap<>();

    public static void main(String[] a) throws Exception {
        initTables();
        if (a.length == 0) { System.out.println("Aufruf: snap | report <datei> | diff <a> <b>"); return; }
        switch (a[0]) {
            case "snap" -> report(snapshot());
            case "report" -> report(Path.of(a[1]));
            case "diff" -> diff(Path.of(a[1]), Path.of(a[2]));
            default -> System.out.println("Unbekannter Befehl: " + a[0]);
        }
    }

    // ---------- Datei ----------

    static Path findSave() throws Exception {
        Path root = Path.of(System.getenv("APPDATA"), "DarkSoulsII");
        try (var s = Files.walk(root, 2)) {
            return s.filter(p -> p.getFileName().toString().equals("DS2SOFS0000.sl2"))
                    .max(Comparator.comparing(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("Kein DS2SOFS0000.sl2 unter " + root));
        }
    }

    static Path snapshot() throws Exception {
        Path src = findSave();
        Path dir = Path.of("snapshots");
        Files.createDirectories(dir);
        Path dst = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".sl2");
        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
        System.out.println("Kopie: " + src + " -> " + dst.toAbsolutePath());
        return dst;
    }

    /** Entschluesselte Eintraege USER_DATA000..022. */
    static List<byte[]> decrypt(Path file) throws Exception {
        byte[] f = Files.readAllBytes(file);
        ByteBuffer b = le(f);
        if (b.getInt(0) != 0x34444E42) throw new IllegalStateException("Kein BND4-Container");
        int count = b.getInt(12);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int h = 0x40 + i * 0x20;
            int size = (int) b.getLong(h + 8), off = b.getInt(h + 0x10);
            byte[] ent = Arrays.copyOfRange(f, off, off + size);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(Arrays.copyOfRange(ent, 16, ent.length));
            if (!Arrays.equals(md5, Arrays.copyOf(ent, 16)))
                throw new IllegalStateException("MD5 falsch in Eintrag " + i + " (Datei evtl. gerade beim Schreiben?)");
            // Eintrag = MD5(16) | IV(16) | Chiffrat; erster entschluesselter Block ist Muell, dann int32 Laenge
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(Arrays.copyOfRange(ent, 16, 32)));
            byte[] d = c.doFinal(ent, 16, ((ent.length - 16) / 16) * 16);
            int len = le(d).getInt(16);
            out.add(Arrays.copyOfRange(d, 20, 20 + len));
        }
        return out;
    }

    static ByteBuffer le(byte[] d) { return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN); }

    static String name(byte[] slot) {
        String s = new String(slot, 0x3C0, 32, StandardCharsets.UTF_16LE);
        int z = s.indexOf('\0');
        return z < 0 ? s : s.substring(0, z);
    }

    static boolean occupied(byte[] slot) {
        String n = name(slot);
        int sl = le(slot).getInt(0x38);
        return !n.isEmpty() && n.chars().allMatch(ch -> ch >= 0x20 && ch < 0xFFF0) && sl >= 1 && sl <= 838;
    }

    // ---------- Bericht ----------

    static void report(Path file) throws Exception {
        List<byte[]> e = decrypt(file);
        System.out.println("Datei: " + file + "  (" + e.size() + " Eintraege, alle MD5 ok)");
        for (int s = 1; s <= SLOT_COUNT; s++) {
            byte[] slot = e.get(s);
            if (!occupied(slot)) continue;
            byte[] world = e.get(s + SLOT_COUNT);
            ByteBuffer b = le(slot);
            System.out.println("\n================ Slot " + s + ": " + name(slot) + " ================");
            System.out.printf("Seelenlevel %d | Seelen %d | HP %d | NG-Wert (unbestaetigt) %d%n",
                b.getInt(0x38), b.getInt(0x3C), b.getInt(0x48), b.getShort(0x404));
            String[] st = {"Lebenskraft", "Ausdauer", "Vitalitaet", "Einstimmung", "Staerke", "Geschick", "Intelligenz", "Glaube", "Anpassung"};
            StringBuilder sb = new StringBuilder("Attribute: ");
            for (int i = 0; i < st.length; i++) sb.append(st[i]).append('=').append(b.getShort(0x20 + 2 * i)).append(' ');
            System.out.println(sb);
            int map = b.getInt(0x3BC);
            System.out.printf("Aktuelles Gebiet: %s  Position (%.1f, %.1f, %.1f)%n", mapName(map),
                b.getFloat(0x39C), b.getFloat(0x3A0), b.getFloat(0x3A4));

            Map<Integer, Integer> inv = items(slot, INV_START, INV_END), keys = items(slot, KEY_START, KEY_END);
            System.out.println("Inventar: " + inv.size() + " Eintraege, Schluessel-Bereich: " + keys.size() + " Eintraege");
            List<String> known = new ArrayList<>();
            for (var id : keys.keySet()) if (KEY_ITEMS.containsKey(id)) known.add(KEY_ITEMS.get(id));
            for (var id : inv.keySet()) if (KEY_ITEMS.containsKey(id)) known.add(KEY_ITEMS.get(id));
            System.out.println("Erkannte Schluesselitems: " + known);

            System.out.println("Weltdaten pro Gebiet (Anzahl gespeicherter IDs):");
            for (int k = 0; k < MAP_BLOCK_COUNT; k++) {
                int o = MAP_BLOCK_START + k * MAP_BLOCK_SIZE;
                int id = le(world).getInt(o);
                if (id == 0) continue;
                System.out.printf("  %-45s %4d%n", mapName(id), le(world).getInt(o + 4));
            }

            System.out.println("Boss-Zaehler (KANDIDAT, unbestaetigt, Basis 0x" + Integer.toHexString(BOSS_BASE_GUESS) + "):");
            ByteBuffer w = le(world);
            for (var en : BOSSES.entrySet()) {
                int v = w.getInt(BOSS_BASE_GUESS + en.getKey());
                if (v != 0) System.out.println("  " + en.getValue() + " x" + v);
            }
        }
    }

    static Map<Integer, Integer> items(byte[] slot, int from, int to) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        ByteBuffer b = le(slot);
        for (int o = from; o + 16 <= to; o += 16) {
            int id = b.getInt(o);
            if (id != 0) m.merge(id, Math.max(1, b.getInt(o + 8)), Integer::sum);
        }
        return m;
    }

    static String mapName(int id) {
        return MAPS.getOrDefault(id, "?") + String.format(" [m%02d_%02d]", (id >>> 24) & 0xFF, (id >>> 16) & 0xFF);
    }

    // ---------- Vergleich ----------

    static void diff(Path pa, Path pb) throws Exception {
        List<byte[]> a = decrypt(pa), b = decrypt(pb);
        for (int s = 1; s <= SLOT_COUNT; s++) {
            if (!occupied(b.get(s))) continue;
            byte[] sa = a.get(s), sb = b.get(s), wa = a.get(s + SLOT_COUNT), wb = b.get(s + SLOT_COUNT);
            if (Arrays.equals(sa, sb) && Arrays.equals(wa, wb)) continue;
            System.out.println("\n===== Slot " + s + ": " + name(sb) + " (geaendert) =====");

            Map<Integer, Integer> ia = items(sa, INV_START, INV_END), ib = items(sb, INV_START, INV_END);
            ia.putAll(items(sa, KEY_START, KEY_END)); ib.putAll(items(sb, KEY_START, KEY_END));
            for (var id : ib.keySet()) if (!ia.containsKey(id))
                System.out.printf("  + Item 0x%08X %s x%d%n", id, KEY_ITEMS.getOrDefault(id, ""), ib.get(id));
            for (var id : ia.keySet()) if (!ib.containsKey(id))
                System.out.printf("  - Item 0x%08X %s%n", id, KEY_ITEMS.getOrDefault(id, ""));

            for (int k = 0; k < MAP_BLOCK_COUNT; k++) {
                int o = MAP_BLOCK_START + k * MAP_BLOCK_SIZE;
                Set<Integer> la = list(wa, o), lb = list(wb, o);
                if (la.equals(lb)) continue;
                Set<Integer> add = new TreeSet<>(lb); add.removeAll(la);
                Set<Integer> rem = new TreeSet<>(la); rem.removeAll(lb);
                System.out.println("  Gebiet " + mapName(le(wb).getInt(o)) + ": neu " + add + (rem.isEmpty() ? "" : " weg " + rem));
            }
            rawDiff("Charakterdaten", sa, sb, 0, sa.length);
            rawDiff("Weltdaten (ab Gebietsbloecken)", wa, wb, MAP_BLOCK_START + MAP_BLOCK_COUNT * MAP_BLOCK_SIZE, wa.length);
        }
    }

    static Set<Integer> list(byte[] w, int o) {
        ByteBuffer b = le(w);
        int n = b.getInt(o + 4);
        Set<Integer> s = new TreeSet<>();
        for (int i = 0; i < n && i < 1400; i++) s.add(Short.toUnsignedInt(b.getShort(o + 0x10 + 2 * i)));
        return s;
    }

    /** Geaenderte int32-Werte, gruppiert; Positions-/Zeitwerte erzeugen viel Rauschen, daher max. 60 Zeilen. */
    static void rawDiff(String label, byte[] a, byte[] b, int from, int to) {
        ByteBuffer x = le(a), y = le(b);
        int shown = 0, total = 0;
        for (int o = from; o + 4 <= to; o += 4) {
            int va = x.getInt(o), vb = y.getInt(o);
            if (va == vb) continue;
            total++;
            if (shown++ < 60) System.out.printf("    %s +0x%05X: %d -> %d (0x%08X -> 0x%08X)%n", label, o, va, vb, va, vb);
        }
        if (total > 0) System.out.println("    " + label + ": " + total + " geaenderte int32-Werte");
    }

    // ---------- Tabellen ----------

    static void initTables() {
        // Kartennamen: aus Community-Wissen, noch nicht alle bestaetigt
        Object[][] maps = {
            {0x0A020000, "Zwischen den Dingen / Things Betwixt"}, {0x0A040000, "Majula"},
            {0x0A0A0000, "Wald der gefallenen Riesen"}, {0x0A0E0000, "Lichtsteinbucht Tseldora (unsicher)"},
            {0x0A0F0000, "Aldias Festung (unsicher)"}, {0x0A100000, "Verlorene Bastille / Suenderturm (unsicher)"},
            {0x0A110000, "Erntetal / Erdspitze (unsicher)"}, {0x0A120000, "Niemandswerft (unsicher)"},
            {0x0A130000, "Eisenfestung (unsicher)"}, {0x0A170000, "Jaegerdickicht / Untotenfegefeuer (unsicher)"},
            {0x0A190000, "Kanal / Schwarze Schlucht (unsicher)"}, {0x0A1B0000, "Drachenhorst (unsicher)"},
            {0x0A1D0000, "Weg zu Heides Turm / Schattenruinen (unsicher)"}, {0x0A1E0000, "Heides Flammenturm (unsicher)"},
            {0x0A1F0000, "Schattenwald / Pharros (unsicher)"}, {0x0A200000, "Schrein von Amana (unsicher)"},
            {0x0A210000, "Schloss Drangleic (unsicher)"}, {0x0A220000, "Untotengruft (unsicher)"},
            {0x140A0000, "Erinnerungen (unsicher)"}, {0x140B0000, "Drachenerinnerung (unsicher)"},
            {0x14150000, "Grab der Heiligen (unsicher)"}, {0x28030000, "Dunkle Kluft (unsicher)"},
            {0x32230000, "DLC Shulva (unsicher)"}, {0x32240000, "DLC Brume-Turm (unsicher)"},
            {0x32250000, "DLC Eleum Loyce (unsicher)"}, {0x32260000, "DLC (unsicher)"},
        };
        for (Object[] m : maps) MAPS.put((Integer) m[0], (String) m[1]);

        // Offsets der Boss-Zaehler laut SoulSplitter (Speicherstruktur); Lage im Spielstand unbestaetigt
        Object[][] bosses = {
            {0x7C, "Der letzte Riese"}, {0x70, "Der Verfolger"}, {0x4C, "Drachenreiter"}, {0x50, "Alter Drachentoeter"},
            {0x54, "Flexibler Waechter"}, {0x58, "Ruinenwaechter"}, {0x5C, "Verlorener Suender"}, {0xA8, "Glockenturm-Gargoyles"},
            {0x38, "Skelettlords"}, {0x34, "Wagen des Henkers"}, {0x3C, "Begehrlicher Daemon"}, {0x40, "Mytha"},
            {0x60, "Schmelzofendaemon"}, {0x30, "Alter Eisenkoenig"}, {0x44, "Skorpionin Najka"}, {0x68, "Der Verfaulte"},
            {0x64, "Koenigliche Rattenvorhut"}, {0x6C, "Koenigliche Rattenautoritaet"}, {0x2C, "Freja"}, {0x48, "Spiegelritter"},
            {0x28, "Daemon des Gesangs"}, {0x8C, "Velstadt"}, {0x98, "Vendrick"}, {0x78, "Waechterdrache"},
            {0x80, "Riesenfuerst"}, {0x88, "Thronwaechter & -verteidiger"}, {0x84, "Nashandra"}, {0x94, "Uralter Drache"},
            {0x9C, "Dunkellauerer"}, {0xA0, "Zwillings-Drachenreiter"}, {0xA4, "Magnus & Gemeinde"}, {0x118, "Aldia"},
            {0xC8, "Elana"}, {0xD4, "Sinh"}, {0xF4, "Grabraeuber/Varg/Cerah"}, {0xFC, "Blauer Schmelzofendaemon"},
            {0xCC, "Rauchritter"},
        };
        for (Object[] m : bosses) BOSSES.put((Integer) m[0], (String) m[1]);

        // Schluesselitems (IDs aus dem Save-Editor alfizari/Dark-Souls-2-Save-Editor-PS4-PC, little endian)
        String[][] keys = {
            {"0331DF00", "Auge der Priesterin"}, {"03041840", "Soldatenschluessel"}, {"03043F50", "Schluessel zum Koenigsweg"},
            {"0304B480", "Waffen-Schmiedekasten"}, {"0304DB90", "Ruestungs-Schmiedekasten"}, {"03072580", "Bastille-Schluessel"},
            {"03074C90", "Eisenschluessel"}, {"030773A0", "Vergessener Schluessel"}, {"03079AB0", "Lichtsteinschluessel"},
            {"0307C1C0", "Antiker Schluessel"}, {"0307E8D0", "Fangschluessel"}, {"03080FE0", "Hausschluessel"},
            {"030836F0", "Lenigrasts Schluessel"}, {"03085E00", "Glatter & seidiger Stein"}, {"03088510", "Rotunden-Sperrstein"},
            {"0308AC20", "Riesen-Verwandtschaft"}, {"0308D330", "Aschennebelherz"}, {"0308FA40", "Seele eines Riesen"},
            {"03092150", "Tseldora-Hoehlenschluessel"}, {"03094860", "Champions-Tafel"}, {"03096F70", "Leitermodell"},
            {"03099680", "Seelengefaess"}, {"0309BD90", "Untoten-Verlies-Schluessel"}, {"030A0BB0", "Matte Glut"},
            {"030A32C0", "Zerdrueckte Augenkugel"}, {"030A59D0", "Gewuerz des Einfaltspinsels"}, {"030A80E0", "Gewuerz des Skeptikers"},
            {"030AA7F0", "Aldia-Schluessel"}, {"03197500", "Drachenklaue"}, {"031AFBA0", "Schwerer Eisenschluessel"},
            {"031C8240", "Gefrorene Blume"}, {"031E08E0", "Schluessel zum ewigen Heiligtum"}, {"031F8F80", "Turmschluessel"},
            {"03211620", "Garnisonsschluessel"}, {"03236010", "Drachenstein"},
        };
        for (String[] k : keys) KEY_ITEMS.put(Integer.parseUnsignedInt(k[0], 16), k[1]);
    }
}
