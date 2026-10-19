package ds2ach.logic;

import ds2ach.data.AchievementDb;
import ds2ach.data.AchievementDb.Achievement;
import ds2ach.data.AchievementDb.Bedingung;
import ds2ach.save.CharacterSlot;

import java.util.ArrayList;
import java.util.List;

/** Leitet aus dem Spielstand den automatischen Status eines Achievements ab. Raet nicht: unbekannt bleibt "manuell". */
public final class Evaluator {

    public enum Auto { ERLEDIGT, OFFEN, MANUELL }

    /** detail: kurzer Text, woher der Status kommt (z. B. "12/31 Zauber"). */
    public record Result(Auto status, String detail) {}

    private final AchievementDb.GameData gd;

    public Evaluator(AchievementDb.GameData gd) {
        this.gd = gd;
    }

    public Result evaluate(Achievement a, CharacterSlot c) {
        Bedingung b = a.bedingung();
        if (b == null || b.typ() == null || c == null) return new Result(Auto.MANUELL, "");
        return switch (b.typ()) {
            case "immer" -> new Result(Auto.ERLEDIGT, "Charakter existiert");
            case "item" -> {
                for (String id : nn(b.ids()))
                    if (c.hasItem(AchievementDb.parseId(id)))
                        yield new Result(Auto.ERLEDIGT, "im Inventar: " + gd.items().getOrDefault(normalize(id), id));
                yield new Result(Auto.OFFEN, "Item nicht im Inventar");
            }
            case "alleItems" -> {
                long have = nn(b.ids()).stream().filter(id -> c.hasItem(AchievementDb.parseId(id))).count();
                yield new Result(have == nn(b.ids()).size() ? Auto.ERLEDIGT : Auto.OFFEN, have + "/" + nn(b.ids()).size() + " Items");
            }
            case "boss" -> {
                for (String boss : nn(b.bosse()))
                    if (c.bossKills().containsKey(boss))
                        yield new Result(Auto.ERLEDIGT, gd.bossName(boss) + " besiegt");
                yield new Result(Auto.OFFEN, "Boss noch nicht besiegt");
            }
            case "zauber" -> {
                var list = gd.spells().getOrDefault(b.kategorie(), List.of());
                List<String> missing = new ArrayList<>();
                for (var s : list) if (!c.hasItem(AchievementDb.parseId(s.id()))) missing.add(s.name());
                int have = list.size() - missing.size();
                String detail = have + "/" + list.size() + " erlernt";
                if (!missing.isEmpty() && missing.size() <= 6) detail += " (fehlt: " + String.join(", ", missing) + ")";
                yield new Result(!list.isEmpty() && missing.isEmpty() ? Auto.ERLEDIGT : Auto.OFFEN, detail);
            }
            default -> new Result(Auto.MANUELL, "");
        };
    }

    private static String normalize(String id) {
        return String.format("0x%08X", AchievementDb.parseId(id));
    }

    private static <T> List<T> nn(List<T> l) {
        return l == null ? List.of() : l;
    }
}
