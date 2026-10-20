package ds2ach.data;

import ds2ach.save.CharacterSlot;
import ds2ach.save.SaveFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prüft die von setup.bat extrahierten Karten (übersprungen, wenn sie fehlen). */
class MapGeoTest {
    static final MapGeo GEO = new MapGeo(Path.of(""));

    static double nearestXZ(MapGeo.Net net, double x, double z) {
        double[] p = net.punkte();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < p.length; i += 3) best = Math.min(best, Math.hypot(p[i] - x, p[i + 2] - z));
        return best;
    }

    @Test
    void itemsAndBonfiresLieOnTheNavmesh() {
        assumeTrue(GEO.available(), "setup.bat noch nicht gelaufen");
        Optional<MapGeo.Map3D> tb = GEO.load(0x0A020000);
        assertTrue(tb.isPresent());
        MapGeo.Map3D m = tb.get();
        assertEquals(12, m.items().size(), "Things Betwixt hat 12 Fundorte mit Position");
        assertEquals(2650, m.leuchtfeuer().get(0).id());
        for (MapGeo.ItemSpot s : m.items()) assertTrue(nearestXZ(m.netz(), s.x(), s.z()) < 5, "Fundort " + s.lot() + " liegt nicht auf dem Netz");
        assertTrue(m.netz().dreiecke().length / 3 > 1000);
    }

    @Test
    void majulaExitsPointToNeighbours() {
        assumeTrue(GEO.available(), "setup.bat noch nicht gelaufen");
        MapGeo.Map3D m = GEO.load(0x0A040000).orElseThrow();
        var targets = m.uebergaenge().stream().map(MapGeo.Exit::targetMapId).toList();
        assertTrue(targets.contains(0x0A020000), "Things Betwixt");
        assertTrue(targets.contains(0x0A0A0000), "Forest of Fallen Giants");
        assertTrue(targets.contains(0x0A1F0000), "Heide's Tower of Flame");
    }

    @Test
    void savedPlayerPositionIsOnTheMap() throws Exception {
        assumeTrue(GEO.available(), "setup.bat noch nicht gelaufen");
        Path dir = Path.of("probe", "snapshots");
        assumeTrue(Files.isDirectory(dir), "keine Snapshots vorhanden");
        Path snap;
        try (Stream<Path> s = Files.list(dir)) {
            snap = s.filter(p -> p.toString().endsWith(".sl2")).sorted().findFirst().orElse(null);
        }
        assumeTrue(snap != null, "keine Snapshots vorhanden");
        CharacterSlot c = SaveFile.read(snap).slots().get(0);
        MapGeo.Map3D m = GEO.load(c.mapId()).orElseThrow();
        assertTrue(nearestXZ(m.netz(), c.position()[0], c.position()[2]) < 3, "Spielerposition liegt nicht auf dem Netz");
    }
}
