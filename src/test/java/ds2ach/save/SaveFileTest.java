package ds2ach.save;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Liest eine Spielstand-Kopie aus probe/snapshots/ (nicht im Repo; Test wird uebersprungen, wenn keine da ist). */
class SaveFileTest {

    @Test
    void readsSnapshot() throws Exception {
        Path dir = Path.of("probe", "snapshots");
        assumeTrue(Files.isDirectory(dir), "keine Snapshots vorhanden");
        Path snap;
        try (Stream<Path> s = Files.list(dir)) {
            snap = s.filter(p -> p.toString().endsWith(".sl2")).sorted().findFirst().orElse(null);
        }
        assumeTrue(snap != null, "keine Snapshots vorhanden");

        var slots = SaveFile.read(snap).slots();
        assertFalse(slots.isEmpty());
        for (CharacterSlot c : slots) {
            assertTrue(c.level() >= 1 && c.level() <= 838);
            assertFalse(c.name().isBlank());
            assertFalse(c.items().isEmpty());
            System.out.println(c.index() + " " + c.name() + " SL" + c.level() + " map=" + Integer.toHexString(c.mapId())
                    + " items=" + c.items().size() + " bosse=" + c.bossKills());
        }
    }

    @Test
    void rejectsGarbage() {
        assertThrows(Exception.class, () -> SaveFile.parse(new byte[256]));
    }
}
