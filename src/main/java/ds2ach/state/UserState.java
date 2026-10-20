package ds2ach.state;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Was der Nutzer selbst einstellt (abhaken, ausblenden) plus Merkwerte, gespeichert in state.json. */
public final class UserState {
    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Pro Charakter (Schlüssel "Slot 1: Name"). */
    public static final class CharState {
        /** Achievement-ID -> "erledigt" oder "offen"; ueberschreibt die automatische Erkennung. */
        public Map<String, String> override = new HashMap<>();
        public Set<String> hidden = new HashSet<>();
        public String lastBoss;
        /** Item-Fundorte, die du selbst abgehakt hast (Lot-IDs aus extracted/items.json). */
        public Set<Long> collectedLots = new HashSet<>();
        public Map<String, Integer> knownKills;
    }

    public Map<String, CharState> characters = new LinkedHashMap<>();
    /** Slot -> Fingerabdruck beim letzten Lesen, um den zuletzt gespeicherten Slot zu erkennen. */
    public Map<Integer, String> fingerprints = new HashMap<>();
    public Integer lastActiveSlot;
    /** null = automatisch, sonst fester Slot. */
    public Integer pinnedSlot;
    public boolean alwaysOnTop;
    public boolean showHidden;
    public String lastBackupDay;

    private transient Path file;

    public static UserState load(Path file) {
        UserState s = null;
        if (Files.exists(file)) {
            try {
                s = JSON.readValue(file.toFile(), UserState.class);
            } catch (IOException e) {
                System.err.println("state.json defekt, starte neu: " + e.getMessage());
            }
        }
        if (s == null) s = new UserState();
        s.file = file;
        return s;
    }

    public CharState character(String key) {
        return characters.computeIfAbsent(key, k -> new CharState());
    }

    public void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), this);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("state.json konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
