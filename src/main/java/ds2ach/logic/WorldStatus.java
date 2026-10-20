package ds2ach.logic;

import ds2ach.data.AchievementDb;
import ds2ach.data.World;
import ds2ach.data.World.Area;
import ds2ach.data.World.Connection;
import ds2ach.save.CharacterSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Status der Weltkarte für einen Charakter: welches Gebiet ist aktuell/besucht, welche Wege sind offen. */
public final class WorldStatus {

    public record AreaState(Area area, boolean current, boolean visited, int litBonfires, int bossesDown) {}

    /** missing: was zum Öffnen noch fehlt (leer, wenn offen). */
    public record ConnState(Connection connection, boolean open, boolean openBecauseVisited, List<String> missing) {}

    public final Map<String, AreaState> areas = new LinkedHashMap<>();
    public final List<ConnState> connections = new ArrayList<>();

    public WorldStatus(World world, AchievementDb.GameData gd, CharacterSlot c) {
        for (Area a : world.areas) {
            if (c == null) {
                areas.put(a.id(), new AreaState(a, false, false, 0, 0));
                continue;
            }
            int lit = (int) a.bonfires().stream().filter(c.litBonfires()::contains).count();
            int down = (int) a.bosses().stream().filter(c.bossKills()::containsKey).count();
            boolean current = a.onMap(c.mapId());
            // Gebiete ohne eigene Leuchtfeuer (z. B. Memories) gelten als besucht, wenn ihre Karte Weltdaten hat
            boolean mapVisited = a.bonfires().isEmpty() && a.karten() != null
                    && a.karten().stream().anyMatch(k -> c.visitedMaps().contains(AchievementDb.parseId(k)));
            boolean visited = current || lit > 0 || down > 0 || mapVisited;
            areas.put(a.id(), new AreaState(a, current, visited, lit, down));
        }
        for (Connection k : world.connections) {
            List<String> missing = missing(k, gd, c);
            boolean reqOk = missing.isEmpty();
            AreaState to = areas.get(k.nach()), from = areas.get(k.von());
            // Beide Enden besucht: der Weg war offensichtlich begehbar (z. B. Fragrant Branch schon verbraucht)
            boolean bothVisited = to != null && from != null && to.visited() && from.visited();
            connections.add(new ConnState(k, reqOk || bothVisited, !reqOk && bothVisited, reqOk ? List.of() : missing));
        }
    }

    private static List<String> missing(Connection k, AchievementDb.GameData gd, CharacterSlot c) {
        List<String> out = new ArrayList<>();
        World.Voraussetzung v = k.voraussetzung();
        if (v == null) return out;
        if (v.items() != null && !v.items().isEmpty()) {
            boolean have = c != null && v.items().stream().anyMatch(i -> c.hasItem(AchievementDb.parseId(i)));
            if (!have) {
                List<String> names = new ArrayList<>();
                for (String i : v.items()) names.add(gd.itemName(i));
                out.add((names.size() == 1 ? "Item: " : "Eines davon: ") + String.join(" oder ", names));
            }
        }
        if (v.alleItems() != null)
            for (String i : v.alleItems())
                if (c == null || !c.hasItem(AchievementDb.parseId(i))) out.add("Item: " + gd.itemName(i));
        if (v.bosse() != null)
            for (String b : v.bosse())
                if (c == null || !c.bossKills().containsKey(b)) out.add("Boss besiegen: " + gd.bossName(b));
        // Reine Text-Voraussetzung (z. B. Pilgrims of Dark) ist nicht prüfbar: gilt als offen, Text wird angezeigt
        return out;
    }
}
