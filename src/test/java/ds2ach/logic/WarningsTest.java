package ds2ach.logic;

import ds2ach.data.AchievementDb;
import ds2ach.save.CharacterSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft die Points of no Return mit künstlichen Spielständen gegen die echte data/achievements.json. */
class WarningsTest {
    static AchievementDb db;
    static Warnings w;

    @BeforeAll
    static void load() throws Exception {
        db = AchievementDb.load(Path.of("data"));
        w = new Warnings(db.gameData, db.world);
    }

    static CharacterSlot slot(int map, Map<Integer, Integer> items, Map<String, Integer> kills) {
        return new CharacterSlot(1, "Test", 10, 0, 1, map, items, kills, Set.of(), Set.of(), "x", new float[4]);
    }

    static List<Warnings.Hinweis> hints(CharacterSlot c, String id) {
        return w.compute(db.achievements, c, a -> false, a -> false).stream()
                .filter(h -> h.achievement().id().equals(id)).toList();
    }

    @Test
    void lucatielWarnsBeforeEachRemainingBoss() {
        var h = hints(slot(0x0A040000, Map.of(), Map.of("FlexileSentry", 1)), "lucatiel");
        assertEquals(3, h.size());
        assertTrue(h.stream().allMatch(x -> x.stufe() == Warnings.Stufe.KOMMEND));
        assertFalse(h.stream().anyMatch(x -> x.text().contains("Flexile")), "besiegter Boss darf nicht mehr auftauchen");
    }

    @Test
    void lucatielMissedWhenAllBossesDead() {
        var h = hints(slot(0x0A040000, Map.of(),
                Map.of("FlexileSentry", 1, "SmelterDemon", 1, "TheRotten", 1, "LostSinner", 1)), "lucatiel");
        assertEquals(1, h.size());
        assertEquals(Warnings.Stufe.VERPASST, h.get(0).stufe());
    }

    @Test
    void bossInCurrentAreaIsUrgent() {
        // In der Niemandswerft (m10_18) ist der Flexible Wächter "jetzt"
        var h = hints(slot(0x0A120000, Map.of(), Map.of()), "lucatiel");
        assertTrue(h.stream().anyMatch(x -> x.stufe() == Warnings.Stufe.JETZT && x.text().contains("Flexile")));
    }

    @Test
    void soulInInventoryWarnsToTradeAndConsumedSoulIsMissed() {
        int ruinSoul = AchievementDb.parseId("0x03D15350");
        var have = hints(slot(0x0A040000, Map.of(ruinSoul, 1), Map.of("RuinSentinels", 1)), "master_sorcery");
        assertTrue(have.stream().anyMatch(x -> x.stufe() == Warnings.Stufe.JETZT && x.text().contains("Straid")));

        var gone = hints(slot(0x0A040000, Map.of(), Map.of("RuinSentinels", 1)), "master_sorcery");
        assertTrue(gone.stream().anyMatch(x -> x.stufe() == Warnings.Stufe.VERPASST));

        int spell = AchievementDb.parseId("0x01D9F020");
        var traded = hints(slot(0x0A040000, Map.of(spell, 1), Map.of("RuinSentinels", 1)), "master_sorcery");
        assertFalse(traded.stream().anyMatch(x -> x.text().contains("Ruin Sentinel")), "Zauber schon da: keine Warnung");
    }

    @Test
    void areaWarningOnlyInArea() {
        assertTrue(hints(slot(0x0A0F0000, Map.of(), Map.of()), "lucatiel").stream()
                .anyMatch(x -> x.stufe() == Warnings.Stufe.JETZT && x.text().contains("Aldia's Keep")));
        assertFalse(hints(slot(0x0A040000, Map.of(), Map.of()), "lucatiel").stream()
                .anyMatch(x -> x.text().contains("Aldia's Keep")));
    }

    @Test
    void doneOrHiddenAchievementsHaveNoWarnings() {
        var c = slot(0x0A0F0000, Map.of(), Map.of());
        assertTrue(w.compute(db.achievements, c, a -> true, a -> false).isEmpty());
        assertTrue(w.compute(db.achievements, c, a -> false, a -> true).isEmpty());
    }

    @Test
    void afterEndbossRemindsBeforeNgPlus() {
        var h = w.compute(db.achievements, slot(0x0A040000, Map.of(), Map.of("Nashandra", 1)), a -> false, a -> false);
        assertTrue(h.stream().anyMatch(x -> x.text().startsWith("Bevor du NG+ startest")));
    }
}
