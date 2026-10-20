package ds2ach.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Item-Fundorte aus extracted/items.json (erzeugt von setup.bat aus der eigenen Installation). */
public final class Items {

    public enum Kategorie {
        SCHLUESSEL("Schlüsselitems", true), RING("Ringe", true), ZAUBER("Zauber", true), ESTUS("Estus Flask Shards", false),
        BONE_DUST("Sublime Bone Dust", false), WAFFE("Waffen & Schilde", true), RUESTUNG("Rüstung", true),
        BOSSSEELE("Boss-Seelen", false), BUND("Bund-Items", false), MATERIAL("Material", false), VERBRAUCH("Verbrauchsgüter", false);

        public final String label;
        /** Eindeutige Items: ob eingesammelt, lässt sich am Inventar erkennen. */
        public final boolean unique;

        Kategorie(String label, boolean unique) {
            this.label = label;
            this.unique = unique;
        }

        /** Wichtig für die Kartenübersicht (Zähler pro Gebiet). */
        public boolean important() {
            return this != MATERIAL && this != VERBRAUCH && this != BUND;
        }
    }

    static final Set<String> CONSUMABLE_KEYS = Set.of("Fragrant Branch of Yore", "Pharros' Lockstone");
    static final Set<String> NOT_KEYS = Set.of("Simpleton's Spice", "Skeptic's Spice", "Small Smooth & Silky Stone",
            "Smooth & Silky Stone", "Smelter Wedge", "Soul Vessel");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(int id, String name, int menge, int stufe) {
        public Kategorie kategorie() {
            return classify(id, name);
        }

        /** Eindeutig und nicht verbraucht: dann bedeutet "im Inventar" sicher "eingesammelt". */
        public boolean trackable() {
            return kategorie().unique && !CONSUMABLE_KEYS.contains(name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lot(long id, String karte, String ort, List<Item> items, boolean zufall) {
        public int mapId() {
            return AchievementDb.parseId(karte);
        }

        /** Fundorte, die es erst ab NG+ gibt (laut Ortsangabe). */
        public boolean ngPlusOnly() {
            return ort != null && ort.contains("NG+");
        }

        public boolean chest() {
            return ort != null && ort.contains("Chest");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemsFile(Map<String, Object> quelle, List<Lot> lots) {}

    public final List<Lot> lots;
    public final Path sourceFile;
    public final long sourceSize, sourceModified;

    private Items(ItemsFile f) {
        this.lots = f.lots() == null ? List.of() : f.lots();
        Map<String, Object> q = f.quelle() == null ? Map.of() : f.quelle();
        this.sourceFile = q.get("datei") == null ? null : Path.of(q.get("datei").toString());
        this.sourceSize = q.get("groesse") instanceof Number n ? n.longValue() : -1;
        this.sourceModified = q.get("geaendert") instanceof Number n ? n.longValue() : -1;
    }

    /** Leer, wenn setup.bat noch nicht gelaufen ist. */
    public static Optional<Items> load(Path appDir) throws IOException {
        Path p = appDir.resolve("extracted").resolve("items.json");
        if (!Files.exists(p)) return Optional.empty();
        return Optional.of(new Items(AchievementDb.JSON.readValue(p.toFile(), ItemsFile.class)));
    }

    /** Hat sich die Spieldatei seit dem Setup geändert? Dann sollte setup.bat neu laufen. */
    public boolean outdated() {
        try {
            return sourceFile != null && Files.exists(sourceFile)
                    && (Files.size(sourceFile) != sourceSize || Files.getLastModifiedTime(sourceFile).toMillis() != sourceModified);
        } catch (IOException e) {
            return false;
        }
    }

    /** Fundorte eines Gebiets: alle Karten, deren Hauptgebiet es ist (bei geteilten Karten das erste Gebiet in world.json). */
    public List<Lot> lotsFor(World world, World.Area area) {
        List<Lot> out = new ArrayList<>();
        for (Lot l : lots) {
            World.Area p = world.primaryArea(l.mapId());
            if (p != null && p.id().equals(area.id())) out.add(l);
        }
        return out;
    }

    /** Kategorie über den ID-Bereich der Item-Parameter (z. B. 40 Mio. = Ringe, 31-35 Mio. = Zauber). */
    public static Kategorie classify(int id, String name) {
        int m = id / 1_000_000;
        if ("Estus Flask Shard".equals(name)) return Kategorie.ESTUS;
        if ("Sublime Bone Dust".equals(name)) return Kategorie.BONE_DUST;
        if (CONSUMABLE_KEYS.contains(name)) return Kategorie.SCHLUESSEL;
        if (m >= 1 && m <= 20) return Kategorie.WAFFE;
        if (m >= 21 && m <= 29) return Kategorie.RUESTUNG;
        if (m >= 31 && m <= 35) return Kategorie.ZAUBER;
        if (m == 40 || m == 41) return Kategorie.RING;
        if (m >= 50 && m <= 53) return NOT_KEYS.contains(name) ? Kategorie.VERBRAUCH : Kategorie.SCHLUESSEL;
        if (m == 61) return Kategorie.MATERIAL;
        if (m == 62) return Kategorie.BUND;
        if (m == 64) return Kategorie.BOSSSEELE;
        return Kategorie.VERBRAUCH;
    }
}
