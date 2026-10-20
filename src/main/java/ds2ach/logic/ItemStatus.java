package ds2ach.logic;

import ds2ach.data.Items;
import ds2ach.save.CharacterSlot;

import java.util.Set;

/** Ob ein Fundort eingesammelt ist. Ohne entschlüsselte Aufheben-Flags wird nicht geraten. */
public final class ItemStatus {

    public enum Status {
        EINGESAMMELT("eingesammelt"), MANUELL("von dir abgehakt"), OFFEN("offen"), UNBEKANNT("unbekannt");

        public final String label;

        Status(String label) {
            this.label = label;
        }

        public boolean done() {
            return this == EINGESAMMELT || this == MANUELL;
        }
    }

    private ItemStatus() {
    }

    /**
     * Eingesammelt: ein eindeutiges Item des Fundorts liegt im Inventar.
     * Offen: der Fundort enthält eindeutige Items, aber keines davon ist im Inventar (ein Fundort gibt alles auf einmal).
     * Unbekannt: nur Verbrauchsgüter, Estus Flask Shards usw. oder Zufallstabellen (Aufheben-Flags noch nicht entschlüsselt).
     */
    public static Status of(Items.Lot lot, CharacterSlot c, Set<Long> manual) {
        if (manual != null && manual.contains(lot.id())) return Status.MANUELL;
        if (c == null || lot.zufall()) return Status.UNBEKANNT;
        boolean anyTrackable = false;
        for (Items.Item i : lot.items()) {
            if (!i.trackable()) continue;
            anyTrackable = true;
            if (c.hasItem(i.id())) return Status.EINGESAMMELT;
        }
        return anyTrackable ? Status.OFFEN : Status.UNBEKANNT;
    }
}
