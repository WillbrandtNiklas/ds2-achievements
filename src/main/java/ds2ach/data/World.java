package ds2ach.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Weltkarte aus data/world.json: Gebiete (Knoten) und Verbindungen (Kanten). */
public final class World {

    /** Position und Größe auf der Pergamentkarte (1600 x 1000). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pos(double x, double y, double r) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Area(String id, String name, List<String> karten, double x, double y, List<Integer> leuchtfeuer,
                       List<String> bosse, String dlc, String hinweis, List<String> unsicher, Pos karte,
                       List<String> itemKarten, String gelaende) {

        public boolean onMap(int mapId) {
            if (karten == null) return false;
            for (String k : karten) if (AchievementDb.parseId(k) == mapId) return true;
            return false;
        }

        public List<Integer> bonfires() {
            return leuchtfeuer == null ? List.of() : leuchtfeuer;
        }

        public List<String> bosses() {
            return bosse == null ? List.of() : bosse;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Voraussetzung(List<String> items, List<String> alleItems, List<String> bosse, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(String von, String nach, String typ, boolean einbahn, Voraussetzung voraussetzung,
                             String hinweis, List<String> unsicher) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorldFile(Map<String, String> leuchtfeuerNamen, List<Area> gebiete, List<Connection> verbindungen) {}

    public final List<Area> areas;
    public final List<Connection> connections;
    private final Map<String, Area> byId = new LinkedHashMap<>();
    private final Map<Integer, String> bonfireNames = new LinkedHashMap<>();

    private World(WorldFile f) throws IOException {
        this.areas = f.gebiete() == null ? List.of() : f.gebiete();
        this.connections = f.verbindungen() == null ? List.of() : f.verbindungen();
        for (Area a : areas) byId.put(a.id(), a);
        for (Connection c : connections)
            if (!byId.containsKey(c.von()) || !byId.containsKey(c.nach()))
                throw new IOException("world.json: Verbindung " + c.von() + " -> " + c.nach() + " verweist auf ein unbekanntes Gebiet");
        if (f.leuchtfeuerNamen() != null)
            f.leuchtfeuerNamen().forEach((k, v) -> bonfireNames.put(Integer.parseInt(k), v));
    }

    public static World load(Path file) throws IOException {
        return new World(AchievementDb.JSON.readValue(file.toFile(), WorldFile.class));
    }

    public Area area(String id) {
        return byId.get(id);
    }

    /** Alle Gebiete auf dieser Karte (mehrere Gebiete können eine Karte teilen, z. B. Lost Bastille und Belfry Luna). */
    public List<Area> areasOnMap(int mapId) {
        List<Area> out = new ArrayList<>();
        for (Area a : areas) if (a.onMap(mapId)) out.add(a);
        return out;
    }

    public List<Area> areasWithBoss(String boss) {
        List<Area> out = new ArrayList<>();
        for (Area a : areas) if (a.bosses().contains(boss)) out.add(a);
        return out;
    }

    /** Erstes Gebiet auf dieser Karte: bekommt bei geteilten Karten die Items zugeordnet. */
    public Area primaryArea(int mapId) {
        for (Area a : areas) if (a.onMap(mapId)) return a;
        for (Area a : areas)
            if (a.itemKarten() != null && a.itemKarten().stream().anyMatch(k -> AchievementDb.parseId(k) == mapId)) return a;
        return null;
    }

    public String bonfireName(int id) {
        return bonfireNames.getOrDefault(id, "Leuchtfeuer " + id);
    }

    /** Name des aktuellen Gebiets für die Kopfzeile, z. B. "The Lost Bastille / Belfry Luna". */
    public String mapName(int mapId) {
        List<String> names = new ArrayList<>();
        for (Area a : areasOnMap(mapId)) names.add(a.name());
        if (names.isEmpty()) return String.format("Unbekannt [m%02d_%02d]", (mapId >>> 24) & 0xFF, (mapId >>> 16) & 0xFF);
        return String.join(" / ", names);
    }
}
