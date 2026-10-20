package ds2ach.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Extrahierte Kartendaten (extracted/maps/mAA_BB.json, erzeugt von setup.bat) für die 3D-Ansicht. */
public final class MapGeo {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Net(double[] punkte, int[] dreiecke) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bonfire(int id, double x, double y, double z) {}

    /** typ: 120 = Truhe, 130 = liegendes Item / Leiche, sonst andere Objekte. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemSpot(long lot, double x, double y, double z, int typ) {}

    /** Gebietsübergang (Event-Bereich mit Zielkarte). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Exit(int ziel, String karte, double x, double y, double z) {
        public int targetMapId() {
            return AchievementDb.parseId(karte);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Map3D(String karte, Net netz, List<Bonfire> leuchtfeuer, List<ItemSpot> items, List<Exit> uebergaenge) {
        public int mapId() {
            return AchievementDb.parseId(karte);
        }
    }

    private final Path dir;
    private final Map<Integer, Optional<Map3D>> cache = new HashMap<>();

    public MapGeo(Path appDir) {
        this.dir = appDir.resolve("extracted").resolve("maps");
    }

    public boolean available() {
        return Files.isDirectory(dir);
    }

    private final Map<Integer, Optional<MapModel>> models = new HashMap<>();

    /** Modell mit Texturen laden (zwischengespeichert, die letzten zwei Karten); leer, wenn nicht extrahiert. */
    public synchronized Optional<MapModel> loadModel(int mapId) {
        if (!models.containsKey(mapId) && models.size() >= 2) models.clear(); // Speicher: Texturen sind groß, nur zwei Modelle halten
        return models.computeIfAbsent(mapId, id -> {
            Path p = dir.getParent().resolve("models").resolve(String.format("m%02d_%02d.bin.gz", (id >>> 24) & 0xFF, (id >>> 16) & 0xFF));
            if (!Files.exists(p)) return Optional.empty();
            try {
                return Optional.of(MapModel.read(p));
            } catch (IOException e) {
                System.err.println("Modell nicht lesbar: " + p + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Karte laden (zwischengespeichert); leer, wenn setup.bat sie nicht extrahiert hat. */
    public Optional<Map3D> load(int mapId) {
        return cache.computeIfAbsent(mapId, id -> {
            Path p = dir.resolve(String.format("m%02d_%02d.json", (id >>> 24) & 0xFF, (id >>> 16) & 0xFF));
            if (!Files.exists(p)) return Optional.empty();
            try {
                return Optional.of(AchievementDb.JSON.readValue(p.toFile(), Map3D.class));
            } catch (IOException e) {
                System.err.println("Karte nicht lesbar: " + p + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }
}
