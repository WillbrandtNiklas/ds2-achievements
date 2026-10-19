package ds2ach.save;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Liest DS2SOFS0000.sl2 (BND4-Container, 23 Einträge USER_DATA000..022).
 * Jeder Eintrag: MD5(16) | IV(16) | AES-128-CBC-Chiffrat. Klartext: Müllblock(16) | int32 Laenge | Daten.
 * USER_DATA001..010 = Charakterdaten der Slots 1..10, USER_DATA011..020 = Weltdaten derselben Slots.
 *
 * Diese Klasse liest nur. Die Datei wird komplett in den Speicher gelesen, nie zum Schreiben geöffnet.
 */
public final class SaveFile {
    static final byte[] KEY = HexFormat.of().parseHex("599F9B699640A55236EE2D70835EC744");
    public static final int SLOT_COUNT = 10;

    private final List<byte[]> entries;

    private SaveFile(List<byte[]> entries) {
        this.entries = entries;
    }

    public static SaveFile read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static SaveFile parse(byte[] f) throws IOException {
        ByteBuffer b = le(f);
        if (f.length < 0x40 || b.getInt(0) != 0x34444E42) throw new IOException("Kein BND4-Container (falsche Datei?)");
        int count = b.getInt(12);
        List<byte[]> out = new ArrayList<>(count);
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            for (int i = 0; i < count; i++) {
                int h = 0x40 + i * 0x20;
                int size = (int) b.getLong(h + 8), off = b.getInt(h + 0x10);
                if (off < 0 || size < 48 || off + size > f.length) throw new IOException("Eintrag " + i + " außerhalb der Datei");
                md5.update(f, off + 16, size - 16);
                if (!Arrays.equals(md5.digest(), Arrays.copyOfRange(f, off, off + 16)))
                    throw new IOException("Prüfsumme falsch in Eintrag " + i + " (Datei wird evtl. gerade geschrieben)");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(f, off + 16, 16));
                byte[] d = c.doFinal(f, off + 16, ((size - 16) / 16) * 16);
                int len = le(d).getInt(16);
                if (len < 0 || 20 + len > d.length) throw new IOException("Eintrag " + i + " hat ungültige Laenge");
                out.add(Arrays.copyOfRange(d, 20, 20 + len));
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Entschlüsselung fehlgeschlagen", e);
        }
        if (out.size() < 1 + 2 * SLOT_COUNT) throw new IOException("Zu wenige Einträge: " + out.size());
        return new SaveFile(out);
    }

    /** Alle belegten Slots (1..10). */
    public List<CharacterSlot> slots() {
        List<CharacterSlot> list = new ArrayList<>();
        for (int s = 1; s <= SLOT_COUNT; s++) {
            CharacterSlot cs = CharacterSlot.parse(s, entries.get(s), entries.get(s + SLOT_COUNT));
            if (cs != null) list.add(cs);
        }
        return list;
    }

    static ByteBuffer le(byte[] d) {
        return ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
    }
}
