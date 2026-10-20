package ds2ach.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Einmaliges Setup: liest Item-Fundorte aus der eigenen Spielinstallation und schreibt extracted/items.json.
 * Spieldateien werden nur gelesen. Ergebnis bleibt lokal (extracted/ steht in .gitignore).
 *
 *   java -cp target/ds2-achievements.jar ds2ach.setup.Setup [Spielordner\Game]
 */
public final class Setup {
    static final String GAME_SUBDIR = "steamapps\\common\\Dark Souls II Scholar of the First Sin\\Game";
    /** Paramdex (soulsmods) für englische Item-Namen und Ortsangaben, fest auf einen Stand gepinnt. */
    static final String PARAMDEX = "https://raw.githubusercontent.com/soulsmods/Paramdex/ff7245e524329bc3eab00036723d2bd53384cedf/DS2S/Names/";
    static final int LOT_ROW_SIZE = 124, EMPTY_ITEM = 60510000;

    public static void main(String[] args) throws Exception {
        Path appDir = Path.of("").toAbsolutePath();
        Path game = args.length > 0 ? Path.of(args[0]) : findGame().orElse(null);
        if (game == null || !Files.isDirectory(game)) {
            System.err.println("Spielordner nicht gefunden. Bitte als Argument angeben, z. B.:");
            System.err.println("  setup.bat \"D:\\SteamLibrary\\" + GAME_SUBDIR + "\"");
            System.exit(1);
        }
        Path out = appDir.resolve("extracted");
        Files.createDirectories(out);
        System.out.println("Spielordner: " + game);

        // 1) Item-Fundorte aus den losen Params
        Path lotFile = game.resolve("Param").resolve("ItemLotParam2_Other.param");
        if (Files.isRegularFile(lotFile)) {
            Map<Integer, String> itemNames = loadNames(out, "ItemParam.txt");
            Map<Integer, String> lotNames = loadNames(out, "ItemLotParam2_Other.txt");
            byte[] data = Files.readAllBytes(lotFile); // nur lesen
            List<Map<String, Object>> lots = parseLots(data, itemNames, lotNames);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_hinweis", "Erzeugt von setup.bat aus der eigenen Spielinstallation. Nicht ins Repo committen.");
            doc.put("quelle", Map.of("datei", lotFile.toString(), "groesse", Files.size(lotFile),
                    "geaendert", Files.getLastModifiedTime(lotFile).toMillis()));
            doc.put("namen", itemNames.isEmpty() ? "ohne Paramdex (nur IDs)" : "Paramdex " + PARAMDEX);
            doc.put("lots", lots);
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.resolve("items.json").toFile(), doc);
            System.out.println(lots.size() + " Fundorte nach " + out.resolve("items.json") + " geschrieben.");
        } else {
            System.err.println("Nicht gefunden: " + lotFile + " (Item-Liste wird übersprungen)");
        }

        // 2) Karten (Navigationsnetz, Positionen) aus dem Spielarchiv
        System.out.println("Lese Karten aus GameDataEbl (nur lesend) ...");
        try {
            int n = MapExtractor.extractAll(game, out.resolve("maps"));
            System.out.println(n + " Karten nach " + out.resolve("maps") + " geschrieben.");
        } catch (Exception e) {
            System.err.println("Karten konnten nicht gelesen werden: " + e.getMessage());
        }
    }

    /** DS2-PARAM (64-Bit-Zeilentabelle): Zeilenzahl bei 0x0A, Zeilen ab 0x40 je 24 Bytes (ID, Datenoffset, Namensoffset). */
    static List<Map<String, Object>> parseLots(byte[] d, Map<Integer, String> itemNames, Map<Integer, String> lotNames) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        String type = new String(d, 0x0C, 16, StandardCharsets.US_ASCII).trim();
        if (!type.startsWith("ITEM_LOT_PARAM2")) throw new IOException("Unerwarteter Param-Typ: " + type);
        int rows = Short.toUnsignedInt(b.getShort(0x0A));
        Pattern loc = Pattern.compile("^\\[([^\\]]+)\\]");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            long id = b.getLong(0x40 + 24 * i);
            int off = (int) b.getLong(0x40 + 24 * i + 8);
            if (off <= 0 || off + LOT_ROW_SIZE > d.length) throw new IOException("Zeile " + i + " außerhalb der Datei");
            // Nur Fundorte mit Karten-ID (8 Stellen, z. B. 10026100 = m10_02)
            if (id < 10_000_000 || id > 99_999_999) continue;
            int area = (int) (id / 1_000_000), map = (int) ((id / 10_000) % 100);
            if (area != 10 && area != 20 && area != 40 && area != 50) continue;
            List<Map<String, Object>> items = new ArrayList<>();
            boolean random = false;
            for (int k = 0; k < 10; k++) {
                int amount = Byte.toUnsignedInt(d[off + 4 + k]);
                int reinforce = Byte.toUnsignedInt(d[off + 14 + k]);
                int item = b.getInt(off + 44 + 4 * k);
                float chance = b.getFloat(off + 84 + 4 * k);
                if (item == 0 || item == EMPTY_ITEM || amount == 0) continue;
                if (chance < 0.999f) random = true;
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("id", item);
                it.put("name", itemNames.getOrDefault(item, String.format("Item %d", item)));
                it.put("menge", amount);
                if (reinforce > 0) it.put("stufe", reinforce);
                items.add(it);
            }
            if (items.isEmpty()) continue;
            String ort = "";
            Matcher m = loc.matcher(lotNames.getOrDefault((int) id, ""));
            if (m.find()) ort = m.group(1);
            Map<String, Object> lot = new LinkedHashMap<>();
            lot.put("id", id);
            lot.put("karte", String.format("0x%08X", (area << 24) | (map << 16)));
            lot.put("ort", ort);
            if (random) lot.put("zufall", true); // Zufallstabelle (z. B. Geschenke von Dyna & Tillo): es gibt nur eines davon
            lot.put("items", items);
            out.add(lot);
        }
        return out;
    }

    /** "ID Name" je Zeile; wird einmal heruntergeladen und in extracted/paramdex/ zwischengespeichert. */
    static Map<Integer, String> loadNames(Path out, String file) {
        Path cache = out.resolve("paramdex").resolve(file);
        try {
            if (!Files.exists(cache)) {
                Files.createDirectories(cache.getParent());
                System.out.println("Lade " + file + " von Paramdex ...");
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(URI.create(PARAMDEX + file)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
                Files.write(cache, r.body());
            }
            Map<Integer, String> m = new HashMap<>();
            for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                try {
                    m.put(Integer.parseInt(line.substring(0, sp)), line.substring(sp + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return m;
        } catch (Exception e) {
            System.err.println("Namen aus " + file + " nicht verfügbar (" + e.getMessage() + "), es werden nur IDs gespeichert.");
            return Map.of();
        }
    }

    /** Spielordner über die Steam-Bibliotheken (libraryfolders.vdf) suchen. */
    static Optional<Path> findGame() {
        List<Path> roots = new ArrayList<>();
        for (String s : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles")})
            if (s != null) roots.add(Path.of(s, "Steam"));
        List<Path> libs = new ArrayList<>(roots);
        for (Path r : roots) {
            Path vdf = r.resolve("steamapps").resolve("libraryfolders.vdf");
            try {
                Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(Files.readString(vdf));
                while (m.find()) libs.add(Path.of(m.group(1).replace("\\\\", "\\")));
            } catch (IOException ignored) {
            }
        }
        for (Path l : libs) {
            Path g = l.resolve(GAME_SUBDIR);
            if (Files.isDirectory(g)) return Optional.of(g);
        }
        return Optional.empty();
    }
}
