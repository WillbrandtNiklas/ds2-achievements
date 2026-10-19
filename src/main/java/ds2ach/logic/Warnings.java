package ds2ach.logic;

import ds2ach.data.AchievementDb;
import ds2ach.data.AchievementDb.Achievement;
import ds2ach.data.AchievementDb.Warnung;
import ds2ach.save.CharacterSlot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Berechnet aus den Points of no Return in achievements.json, was für den aktuellen Fortschritt zu tun ist.
 * Grundlage sind Boss-Zähler (unbestätigt), Inventar und aktuelles Gebiet.
 */
public final class Warnings {

    public enum Stufe {
        /** Gerade relevant: du bist im Gebiet, hast die Seele im Inventar oder der nächste Boss ist hier. */
        JETZT,
        /** Kommt später im Spiel. */
        KOMMEND,
        /** Point of no Return überschritten, in diesem Durchgang wohl nicht mehr machbar. */
        VERPASST
    }

    /** sortKey: kleinere Werte zuerst (Story-Reihenfolge des Bosses). */
    public record Hinweis(Achievement achievement, Stufe stufe, String text, int sortKey) {}

    private final AchievementDb.GameData gd;

    public Warnings(AchievementDb.GameData gd) {
        this.gd = gd;
    }

    /**
     * @param done   ob ein Achievement (inkl. manueller Markierung) als erledigt gilt
     * @param hidden ob ein Achievement ausgeblendet ist (dann keine Warnungen)
     */
    public List<Hinweis> compute(List<Achievement> all, CharacterSlot c, Predicate<Achievement> done, Predicate<Achievement> hidden) {
        List<Hinweis> out = new ArrayList<>();
        if (c == null) return out;
        String currentMap = String.format("0x%08X", c.mapId());
        boolean endbossDown = c.bossKills().containsKey("Nashandra");

        for (Achievement a : all) {
            if (done.test(a) || hidden.test(a)) continue;
            if (a.warnungen() != null)
                for (Warnung w : a.warnungen()) evaluate(a, w, c, currentMap, out);
            // Allgemein: nach dem Endboss vor dem Start von NG+ warnen
            if (a.verpassbar() && endbossDown)
                out.add(new Hinweis(a, Stufe.JETZT, "Bevor du NG+ startest: " + a.name() + " ist noch offen."
                        + (a.ngPlus() != null ? " (" + a.ngPlus() + ")" : ""), -1));
        }
        out.sort(Comparator.comparing(Hinweis::stufe).thenComparingInt(Hinweis::sortKey));
        return out;
    }

    private void evaluate(Achievement a, Warnung w, CharacterSlot c, String currentMap, List<Hinweis> out) {
        List<String> bosse = w.bosse() == null ? List.of() : w.bosse();
        switch (w.typ() == null ? "" : w.typ()) {
            case "vorBoss" -> {
                List<String> offen = bosse.stream().filter(b -> !c.bossKills().containsKey(b))
                        .sorted(Comparator.comparingInt(gd::bossRank)).toList();
                if (offen.isEmpty()) {
                    out.add(new Hinweis(a, Stufe.VERPASST, fill(w.folge(), null, w), 0));
                    return;
                }
                for (String b : offen) {
                    boolean here = currentMap.equalsIgnoreCase(gd.bossMaps() == null ? "" : gd.bossMaps().getOrDefault(b, ""));
                    out.add(new Hinweis(a, here ? Stufe.JETZT : Stufe.KOMMEND, fill(w.text(), b, w), gd.bossRank(b)));
                }
            }
            case "seele" -> {
                if (w.zauber() != null && c.hasItem(AchievementDb.parseId(w.zauber()))) return;
                String boss = bosse.isEmpty() ? null : bosse.get(0);
                int rank = boss == null ? 999 : gd.bossRank(boss);
                if (w.seele() != null && c.hasItem(AchievementDb.parseId(w.seele())))
                    out.add(new Hinweis(a, Stufe.JETZT, fill(w.text(), boss, w), rank));
                else if (boss != null && c.bossKills().containsKey(boss))
                    out.add(new Hinweis(a, Stufe.VERPASST, fill(w.folge(), boss, w), rank));
                else if (w.kommend() != null)
                    out.add(new Hinweis(a, Stufe.KOMMEND, fill(w.kommend(), boss, w), rank));
            }
            case "gebiet" -> {
                if (w.gebiete() != null && w.gebiete().stream().anyMatch(currentMap::equalsIgnoreCase))
                    out.add(new Hinweis(a, Stufe.JETZT, fill(w.text(), null, w), -1));
            }
            default -> { }
        }
    }

    private String fill(String s, String boss, Warnung w) {
        if (s == null) return "";
        if (boss != null) s = s.replace("{boss}", gd.bossName(boss));
        if (w.seele() != null) s = s.replace("{seele}", gd.itemName(w.seele()));
        if (w.zauber() != null) s = s.replace("{zauber}", gd.itemName(w.zauber()));
        return s;
    }
}
