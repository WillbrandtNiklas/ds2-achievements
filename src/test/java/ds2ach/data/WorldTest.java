package ds2ach.data;

import ds2ach.logic.WorldStatus;
import ds2ach.save.CharacterSlot;
import ds2ach.save.SaveFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prüft data/world.json auf Konsistenz und die Kartenlogik mit künstlichen und echten Spielständen. */
class WorldTest {
    static AchievementDb db;

    @BeforeAll
    static void load() throws Exception {
        db = AchievementDb.load(Path.of("data"));
    }

    @Test
    void dataIsConsistent() {
        World w = db.world;
        Set<Integer> bonfires = new HashSet<>();
        for (World.Area a : w.areas) {
            for (int b : a.bonfires()) {
                assertTrue(bonfires.add(b), "Leuchtfeuer doppelt vergeben: " + b);
                assertFalse(w.bonfireName(b).startsWith("Leuchtfeuer "), "Name fehlt für " + b);
            }
            for (String b : a.bosses()) assertTrue(db.gameData.bosses().containsKey(b), "Unbekannter Boss " + b + " in " + a.id());
        }
        for (World.Connection c : w.connections)
            if (c.voraussetzung() != null && c.voraussetzung().bosse() != null)
                for (String b : c.voraussetzung().bosse()) assertTrue(db.gameData.bosses().containsKey(b), b);
        // Alle Gebietsverweise in achievements.json existieren
        for (AchievementDb.Achievement a : db.achievements) {
            if (a.gebiete() != null) for (String g : a.gebiete()) assertNotNull(w.area(g), a.id() + ": " + g);
            if (a.warnungen() != null)
                for (AchievementDb.Warnung wa : a.warnungen())
                    if (wa.gebiete() != null) for (String g : wa.gebiete()) assertNotNull(w.area(g), a.id() + ": " + g);
        }
    }

    @Test
    void lockedUntilRequirementMet() {
        var c = new CharacterSlot(1, "T", 1, 0, 1, 0x0A040000, Map.of(), Map.of(), Set.of(), Set.of(4650), "x");
        WorldStatus s = new WorldStatus(db.world, db.gameData, c);
        assertTrue(s.areas.get("majula").current());
        var toBastille = s.connections.stream().filter(k -> k.connection().von().equals("forest") && k.connection().nach().equals("lost_bastille")).findFirst().orElseThrow();
        assertFalse(toBastille.open());
        assertTrue(toBastille.missing().get(0).contains("Pursuer"));

        var after = new CharacterSlot(1, "T", 1, 0, 1, 0x0A040000, Map.of(), Map.of("Pursuer", 1), Set.of(), Set.of(4650), "x");
        assertTrue(new WorldStatus(db.world, db.gameData, after).connections.stream()
                .filter(k -> k.connection().nach().equals("lost_bastille") && k.connection().von().equals("forest"))
                .allMatch(WorldStatus.ConnState::open));
    }

    @Test
    void fortniteSnapshotHasLitBonfiresInKnownAreas() throws Exception {
        Path dir = Path.of("probe", "snapshots");
        assumeTrue(Files.isDirectory(dir), "keine Snapshots vorhanden");
        Path snap;
        try (Stream<Path> s = Files.list(dir)) {
            snap = s.filter(p -> p.toString().endsWith(".sl2")).sorted().findFirst().orElse(null);
        }
        assumeTrue(snap != null, "keine Snapshots vorhanden");
        CharacterSlot c = SaveFile.read(snap).slots().get(0);
        assertFalse(c.litBonfires().isEmpty());
        Set<Integer> known = new HashSet<>();
        db.world.areas.forEach(a -> known.addAll(a.bonfires()));
        assertTrue(known.containsAll(c.litBonfires()), "unbekannte Leuchtfeuer: " + c.litBonfires());
        System.out.println(c.name() + ": " + c.litBonfires().size() + " Leuchtfeuer entzündet");
    }
}
