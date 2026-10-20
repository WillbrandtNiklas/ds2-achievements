package ds2ach.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Prüft das von setup.bat extrahierte Tonmodell von Things Betwixt (übersprungen, wenn es fehlt). */
class MapModelTest {
    static final Path FILE = Path.of("extracted", "models", "m10_02.bin.gz");

    @Test
    void heightsAreSortedForTheCut() throws Exception {
        assumeTrue(Files.exists(FILE), "setup.bat noch nicht gelaufen");
        MapModel m = MapModel.read(FILE);
        assertTrue(m.triangleCount() > 100_000, "zu wenige Dreiecke: " + m.triangleCount());
        for (int i = 1; i < m.heights.length; i++) assertTrue(m.heights[i - 1] <= m.heights[i], "Höhen nicht sortiert bei " + i);
        assertTrue(m.countUpTo(2) <= m.countUpTo(6) && m.countUpTo(6) <= m.countUpTo(40));
        assertEquals(m.triangleCount(), m.countUpTo(40));
        for (int t : m.tris) assertTrue(t >= 0 && t < m.verts.length / 3);
    }

    /** Unter den Wegen (Navigationsnetz) muss fast überall Boden des Modells liegen, sonst stimmt die Platzierung nicht. */
    @Test
    void modelFloorLiesUnderTheWalkableArea() throws Exception {
        assumeTrue(Files.exists(FILE), "setup.bat noch nicht gelaufen");
        MapModel m = MapModel.read(FILE);
        MapGeo.Map3D geo = new MapGeo(Path.of("")).load(0x0A020000).orElseThrow();
        Map<Long, List<Integer>> grid = new HashMap<>();
        int n = m.countUpTo(2); // nur Dreiecke nahe der Weghöhe
        for (int i = 0; i < n; i++) {
            float x = 0, z = 0;
            for (int k = 0; k < 3; k++) {
                x += m.verts[3 * m.tris[3 * i + k]] / 3;
                z += m.verts[3 * m.tris[3 * i + k] + 2] / 3;
            }
            grid.computeIfAbsent(key(x, z), q -> new ArrayList<>()).add(i);
        }
        double[] p = geo.netz().punkte();
        int checked = 0, hit = 0;
        for (int v = 0; v < p.length; v += 3 * 8) {
            checked++;
            boolean found = false;
            for (int dx = -1; dx <= 1 && !found; dx++)
                for (int dz = -1; dz <= 1 && !found; dz++)
                    for (int t : grid.getOrDefault(key((float) p[v] + dx * 4, (float) p[v + 2] + dz * 4), List.of()))
                        if (under(m, t, p[v], p[v + 1], p[v + 2])) {
                            found = true;
                            break;
                        }
            if (found) hit++;
        }
        assertTrue(hit >= checked * 0.9, "nur " + hit + " von " + checked + " Wegpunkten haben Boden darunter");
    }

    static long key(float x, float z) {
        return ((long) Math.floor(x / 4) << 32) ^ ((int) Math.floor(z / 4) & 0xFFFFFFFFL);
    }

    /** Liegt der Punkt (in x/z) im Dreieck und höchstens 0,5 m über bzw. unter dessen Fläche? */
    static boolean under(MapModel m, int t, double x, double y, double z) {
        int a = m.tris[3 * t], b = m.tris[3 * t + 1], c = m.tris[3 * t + 2];
        double ax = m.verts[3 * a], az = m.verts[3 * a + 2], bx = m.verts[3 * b], bz = m.verts[3 * b + 2], cx = m.verts[3 * c], cz = m.verts[3 * c + 2];
        double d = (bz - cz) * (ax - cx) + (cx - bx) * (az - cz);
        if (Math.abs(d) < 1e-9) return false;
        double l1 = ((bz - cz) * (x - cx) + (cx - bx) * (z - cz)) / d, l2 = ((cz - az) * (x - cx) + (ax - cx) * (z - cz)) / d, l3 = 1 - l1 - l2;
        if (l1 < -0.02 || l2 < -0.02 || l3 < -0.02) return false;
        double fy = l1 * m.verts[3 * a + 1] + l2 * m.verts[3 * b + 1] + l3 * m.verts[3 * c + 1];
        return Math.abs(fy - y) < 0.5;
    }
}
