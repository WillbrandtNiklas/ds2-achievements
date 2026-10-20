package ds2ach.save;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ausgelesene Werte eines Charakter-Slots. Offsets stammen aus dem Machbarkeitstest (probe/SaveProbe.java).
 * Felder mit "unbestätigt" im Kommentar sind noch nicht durch Vorher/Nachher-Vergleiche bestätigt.
 */
public record CharacterSlot(
        int index,
        String name,
        int level,
        int souls,
        int ngValue,                  // 1 = NG (bestätigt), höhere Werte vermutlich NG+
        int mapId,                    // aktuelles Gebiet, z. B. 0x0A040000 = Majula
        Map<Integer, Integer> items,  // Item-ID -> Anzahl (Inventar + Schlüsselitems)
        Map<String, Integer> bossKills, // unbestätigt: Kandidat für Boss-Zaehler
        Set<Integer> visitedMaps,     // Gebiete mit gespeicherten Weltdaten
        Set<Integer> litBonfires,     // entzündete Leuchtfeuer (IDs wie 4650 = The Far Fire)
        String fingerprint) {         // Hash über Charakter- und Weltdaten, für "welcher Slot wurde zuletzt gespeichert"

    static final int NAME = 0x3C0, LEVEL = 0x38, SOULS = 0x3C, NG = 0x404, MAP = 0x3BC;
    static final int INV_START = 0x1E2C, INV_END = 0x10E1C, KEY_START = 0x10E30, KEY_END = 0x11DF0;
    static final int MAP_BLOCK_START = 0x20, MAP_BLOCK_SIZE = 0xB10, MAP_BLOCK_COUNT = 42;
    static final int BOSS_BASE = 0x27B14; // unbestätigt
    /** Weltdaten: Liste aller Leuchtfeuer-IDs (uint16, endet mit 0), 0x200 Bytes später ein Byte pro Leuchtfeuer (1 = entzündet). */
    static final int BONFIRE_IDS = 0x282C8, BONFIRE_FLAGS = 0x284C8, BONFIRE_MAX = 256;

    /** Boss-Kennung -> Offset im Boss-Zaehler-Feld (laut SoulSplitter). */
    public static final Map<String, Integer> BOSS_OFFSETS = new LinkedHashMap<>();
    static {
        Object[][] b = {
            {"LastGiant", 0x7C}, {"Pursuer", 0x70}, {"Dragonrider", 0x4C}, {"OldDragonslayer", 0x50},
            {"FlexileSentry", 0x54}, {"RuinSentinels", 0x58}, {"LostSinner", 0x5C}, {"BelfryGargoyles", 0xA8},
            {"SkeletonLords", 0x38}, {"ExecutionersChariot", 0x34}, {"CovetousDemon", 0x3C}, {"Mytha", 0x40},
            {"SmelterDemon", 0x60}, {"OldIronKing", 0x30}, {"Najka", 0x44}, {"TheRotten", 0x68},
            {"RoyalRatVanguard", 0x64}, {"RoyalRatAuthority", 0x6C}, {"Freja", 0x2C}, {"LookingGlassKnight", 0x48},
            {"DemonOfSong", 0x28}, {"Velstadt", 0x8C}, {"Vendrick", 0x98}, {"GuardianDragon", 0x78},
            {"GiantLord", 0x80}, {"ThroneWatcherDefender", 0x88}, {"Nashandra", 0x84}, {"AncientDragon", 0x94},
            {"Darklurker", 0x9C}, {"TwinDragonriders", 0xA0}, {"Magnus", 0xA4}, {"Aldia", 0x118},
            {"Elana", 0xC8}, {"Sinh", 0xD4}, {"Graverobber", 0xF4}, {"BlueSmelterDemon", 0xFC}, {"FumeKnight", 0xCC},
        };
        for (Object[] e : b) BOSS_OFFSETS.put((String) e[0], (Integer) e[1]);
    }

    static CharacterSlot parse(int index, byte[] slot, byte[] world) {
        if (slot.length < KEY_END || world.length < BOSS_BASE + 0x120) return null;
        ByteBuffer b = SaveFile.le(slot);
        String name = new String(slot, NAME, 32, StandardCharsets.UTF_16LE);
        int z = name.indexOf('\0');
        if (z >= 0) name = name.substring(0, z);
        int level = b.getInt(LEVEL);
        // Leere Slots enthalten Zufallsdaten: Name muss lesbar und Level plausibel sein
        if (name.isBlank() || !name.chars().allMatch(ch -> ch >= 0x20 && ch < 0xFFF0) || level < 1 || level > 838) return null;

        Map<Integer, Integer> items = new LinkedHashMap<>();
        readItems(b, INV_START, INV_END, items);
        readItems(b, KEY_START, KEY_END, items);

        ByteBuffer w = SaveFile.le(world);
        Map<String, Integer> kills = new LinkedHashMap<>();
        BOSS_OFFSETS.forEach((k, off) -> {
            int v = w.getInt(BOSS_BASE + off);
            if (v > 0 && v < 100) kills.put(k, v);
        });
        Set<Integer> visited = new TreeSet<>();
        for (int k = 0; k < MAP_BLOCK_COUNT; k++) {
            int o = MAP_BLOCK_START + k * MAP_BLOCK_SIZE;
            if (w.getInt(o) != 0 && w.getInt(o + 4) > 0) visited.add(w.getInt(o));
        }

        return new CharacterSlot(index, name, level, b.getInt(SOULS), Short.toUnsignedInt(b.getShort(NG)), b.getInt(MAP),
                Collections.unmodifiableMap(items), Collections.unmodifiableMap(kills),
                Collections.unmodifiableSet(visited), readBonfires(world), hash(slot, world));
    }

    static Set<Integer> readBonfires(byte[] world) {
        Set<Integer> lit = new TreeSet<>();
        if (world.length < BONFIRE_FLAGS + BONFIRE_MAX) return lit;
        ByteBuffer w = SaveFile.le(world);
        int prev = 0;
        for (int i = 0; i < BONFIRE_MAX; i++) {
            int id = Short.toUnsignedInt(w.getShort(BONFIRE_IDS + 2 * i));
            if (id == 0) break;
            // Plausibilitaet: aufsteigend sortierte IDs im bekannten Bereich, sonst ist das nicht die Leuchtfeuer-Tabelle
            if (id <= prev || id < 1000 || id > 40000) return new TreeSet<>();
            prev = id;
            if (world[BONFIRE_FLAGS + i] != 0) lit.add(id);
        }
        return lit;
    }

    private static void readItems(ByteBuffer b, int from, int to, Map<Integer, Integer> out) {
        for (int o = from; o + 16 <= to; o += 16) {
            int id = b.getInt(o);
            if (id != 0 && id != -1) out.merge(id, Math.max(1, b.getInt(o + 8)), Integer::sum);
        }
    }

    private static String hash(byte[] a, byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(a);
            md.update(b);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean hasItem(int id) {
        return items.containsKey(id);
    }
}
