package ds2ach.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Laedt data/achievements.json und data/gamedata.json (beide vom Nutzer editierbar). */
public final class AchievementDb {
    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bedingung(String typ, List<String> ids, List<String> bosse, String kategorie) {}

    /** Point of no Return, siehe _hinweis in achievements.json. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warnung(String typ, List<String> bosse, String seele, String zauber, List<String> gebiete,
                          String text, String kommend, String folge) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Achievement(String id, String name, String beschreibung, Bedingung bedingung,
                              String sicherheit, boolean verpassbar, String erkennungHinweis,
                              String guide, List<String> unsicher, List<Warnung> warnungen, String ngPlus) {
        public boolean erkennungUnsicher() {
            return !"sicher".equals(sicherheit);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AchievementFile(List<Achievement> achievements) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapInfo(String id, String name, boolean sicher) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spell(String id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameData(List<MapInfo> maps, Map<String, String> bosses, Map<String, List<Spell>> spells,
                           Map<String, String> items, List<String> bossOrder, Map<String, String> bossMaps) {

        public String itemName(String id) {
            int n = parseId(id);
            if (spells != null)
                for (List<Spell> l : spells.values())
                    for (Spell s : l) if (parseId(s.id()) == n) return s.name();
            return items.getOrDefault(String.format("0x%08X", n), id);
        }

        /** Position in der groben Story-Reihenfolge, unbekannte Bosse ans Ende. */
        public int bossRank(String key) {
            int i = bossOrder == null ? -1 : bossOrder.indexOf(key);
            return i < 0 ? 999 : i;
        }

        public String mapName(int id) {
            for (MapInfo m : maps)
                if (parseId(m.id()) == id) return m.sicher() ? m.name() : m.name() + " (?)";
            return String.format("Unbekannt [m%02d_%02d]", (id >>> 24) & 0xFF, (id >>> 16) & 0xFF);
        }

        public String bossName(String key) {
            return bosses.getOrDefault(key, key);
        }
    }

    public final List<Achievement> achievements;
    public final GameData gameData;

    private AchievementDb(List<Achievement> achievements, GameData gameData) {
        this.achievements = achievements;
        this.gameData = gameData;
    }

    public static AchievementDb load(Path dataDir) throws IOException {
        AchievementFile a = JSON.readValue(dataDir.resolve("achievements.json").toFile(), AchievementFile.class);
        GameData g = JSON.readValue(dataDir.resolve("gamedata.json").toFile(), GameData.class);
        if (a.achievements() == null || a.achievements().isEmpty()) throw new IOException("achievements.json enthaelt keine Achievements");
        return new AchievementDb(a.achievements(), g);
    }

    /** "0x0308D330" -> int. */
    public static int parseId(String hex) {
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        return Integer.parseUnsignedInt(h, 16);
    }
}
