package ds2ach.data;

import ds2ach.logic.ItemStatus;
import ds2ach.save.CharacterSlot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ItemsTest {

    @Test
    void classifiesByIdRange() {
        assertEquals(Items.Kategorie.ESTUS, Items.classify(60525000, "Estus Flask Shard"));
        assertEquals(Items.Kategorie.RING, Items.classify(0x026A2230, "King's Ring"));
        assertEquals(Items.Kategorie.ZAUBER, Items.classify(0x01D9F020, "Heavy Homing Soul Arrow"));
        assertEquals(Items.Kategorie.SCHLUESSEL, Items.classify(0x03041840, "Soldier Key"));
        assertEquals(Items.Kategorie.SCHLUESSEL, Items.classify(0x039BB8A8, "Fragrant Branch of Yore"));
        assertEquals(Items.Kategorie.VERBRAUCH, Items.classify(51000000, "Simpleton's Spice"));
        assertEquals(Items.Kategorie.WAFFE, Items.classify(1000000, "Dagger"));
        assertEquals(Items.Kategorie.BOSSSEELE, Items.classify(0x03D15350, "Ruin Sentinel Soul"));
    }

    static CharacterSlot slot(Map<Integer, Integer> inv) {
        return new CharacterSlot(1, "T", 1, 0, 1, 0x0A020000, inv, Map.of(), Set.of(), Set.of(), "x");
    }

    @Test
    void statusFromInventoryWithoutGuessing() {
        Items.Lot dagger = new Items.Lot(10026070, "0x0A020000", "Things Betwixt", List.of(new Items.Item(1000000, "Dagger", 1, 0)), false);
        Items.Lot shard = new Items.Lot(10026100, "0x0A020000", "Things Betwixt", List.of(new Items.Item(60525000, "Estus Flask Shard", 1, 0)), false);
        Items.Lot branch = new Items.Lot(1, "0x0A020000", "", List.of(new Items.Item(0x039BB8A8, "Fragrant Branch of Yore", 1, 0)), false);
        assertEquals(ItemStatus.Status.OFFEN, ItemStatus.of(dagger, slot(Map.of()), Set.of()));
        assertEquals(ItemStatus.Status.EINGESAMMELT, ItemStatus.of(dagger, slot(Map.of(1000000, 1)), Set.of()));
        assertEquals(ItemStatus.Status.UNBEKANNT, ItemStatus.of(shard, slot(Map.of()), Set.of()));
        assertEquals(ItemStatus.Status.MANUELL, ItemStatus.of(shard, slot(Map.of()), Set.of(10026100L)));
        // Verbrauchte Schlüssel-Items dürfen nicht als "offen" gelten, nur weil sie nicht mehr im Inventar sind
        assertEquals(ItemStatus.Status.UNBEKANNT, ItemStatus.of(branch, slot(Map.of()), Set.of()));
        Items.Lot mixed = new Items.Lot(2, "0x0A0A0000", "", List.of(new Items.Item(40000000, "Life Ring", 1, 0),
                new Items.Item(61000000, "Large Titanite Shard", 1, 0)), false);
        assertEquals(ItemStatus.Status.OFFEN, ItemStatus.of(mixed, slot(Map.of()), Set.of()));
    }

    @Test
    void extractedLotsAllHaveAnArea() throws Exception {
        Optional<Items> items = Items.load(Path.of(""));
        assumeTrue(items.isPresent(), "setup.bat noch nicht gelaufen");
        AchievementDb db = AchievementDb.load(Path.of("data"));
        int missing = 0;
        for (Items.Lot l : items.get().lots) if (db.world.primaryArea(l.mapId()) == null) missing++;
        assertEquals(0, missing, "Fundorte ohne Gebiet");
        assertTrue(items.get().lots.size() > 1000);
    }
}
