package ds2ach.setup;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Liest Dateien aus einem DS2-SotFS-Spielarchiv (z. B. GameDataEbl.bhd/.bdt). Nur lesend.
 * Der Index (.bhd) ist mit RSA verschlüsselt (öffentlicher Schlüssel *KeyCode.pem im Spielordner),
 * die Dateien teilweise mit AES-128-ECB (Schlüssel und Bereiche stehen im Index).
 * Format nach UXM/SoulsFormats (BHD5, Spielversion Dark Souls II).
 */
public final class ArchiveReader {
    record Entry(int size, long offset, byte[] aesKey, long[][] ranges) {}

    private final Path bdt;
    private final Map<Long, Entry> index = new HashMap<>();

    public ArchiveReader(Path gameDir, String archive) throws IOException {
        this.bdt = gameDir.resolve(archive + ".bdt");
        byte[] plain = rsaDecrypt(Files.readAllBytes(gameDir.resolve(archive + ".bhd")),
                Files.readString(gameDir.resolve(archive.replace("Ebl", "KeyCode") + ".pem")));
        parse(plain);
    }

    public int size() {
        return index.size();
    }

    /** Datei per Pfad, z. B. "/map/m10_02_00_00/m10_02_00_00.msb"; null, wenn nicht im Archiv. */
    public byte[] read(String path) throws IOException {
        Entry e = index.get(hash(path.toLowerCase().replace('\\', '/')));
        if (e == null) return null;
        byte[] data = new byte[e.size];
        try (RandomAccessFile f = new RandomAccessFile(bdt.toFile(), "r")) {
            f.seek(e.offset);
            f.readFully(data);
        }
        if (e.aesKey != null) {
            try {
                Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(e.aesKey, "AES"));
                for (long[] r : e.ranges) {
                    if (r[0] == -1 || r[1] == -1 || r[0] == r[1]) continue;
                    c.update(data, (int) r[0], (int) (r[1] - r[0]), data, (int) r[0]);
                }
            } catch (Exception ex) {
                throw new IOException("AES-Entschlüsselung fehlgeschlagen: " + path, ex);
            }
        }
        return data;
    }

    /** 32-Bit-Pfad-Hash wie im Spiel: h = h * 37 + Zeichen. */
    static long hash(String path) {
        int h = 0;
        for (char c : path.toCharArray()) h = h * 37 + c;
        return Integer.toUnsignedLong(h);
    }

    /** Rohes RSA (c^e mod n) mit dem öffentlichen PKCS#1-Schlüssel, Blöcke zu 256 Bytes -> 255 Bytes. */
    static byte[] rsaDecrypt(byte[] data, String pem) throws IOException {
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
        ByteBuffer b = ByteBuffer.wrap(der);
        asn1Length(b, 0x30);
        BigInteger n = new BigInteger(1, asn1Value(b));
        BigInteger e = new BigInteger(1, asn1Value(b));
        int inSize = (n.bitLength() + 7) / 8, outSize = (n.bitLength() - 1) / 8;
        ByteBuffer out = ByteBuffer.allocate(data.length / inSize * outSize);
        for (int off = 0; off + inSize <= data.length; off += inSize) {
            byte[] m = new BigInteger(1, Arrays.copyOfRange(data, off, off + inSize)).modPow(e, n).toByteArray();
            byte[] block = new byte[outSize];
            int len = Math.min(m.length, outSize);
            System.arraycopy(m, m.length - len, block, outSize - len, len);
            out.put(block);
        }
        byte[] res = out.array();
        if (!new String(res, 0, 4, StandardCharsets.US_ASCII).equals("BHD5")) throw new IOException("Archiv-Index ließ sich nicht entschlüsseln");
        return res;
    }

    private static int asn1Length(ByteBuffer b, int tag) throws IOException {
        if ((b.get() & 0xFF) != tag) throw new IOException("Unerwarteter Schlüsselaufbau in der .pem-Datei");
        int len = b.get() & 0xFF;
        if (len >= 0x80) {
            int k = len & 0x7F;
            len = 0;
            for (int i = 0; i < k; i++) len = (len << 8) | (b.get() & 0xFF);
        }
        return len;
    }

    private static byte[] asn1Value(ByteBuffer b) throws IOException {
        byte[] v = new byte[asn1Length(b, 0x02)];
        b.get(v);
        return v;
    }

    private void parse(byte[] d) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int buckets = b.getInt(0x10), bucketsOff = b.getInt(0x14);
        for (int i = 0; i < buckets; i++) {
            int fc = b.getInt(bucketsOff + 8 * i), fo = b.getInt(bucketsOff + 8 * i + 4);
            for (int j = 0; j < fc; j++) {
                int h = fo + 0x20 * j;
                long hash = Integer.toUnsignedLong(b.getInt(h));
                int size = b.getInt(h + 4);
                long offset = b.getLong(h + 8), aesOff = b.getLong(h + 0x18);
                byte[] key = null;
                long[][] ranges = new long[0][];
                if (aesOff != 0) {
                    key = Arrays.copyOfRange(d, (int) aesOff, (int) aesOff + 16);
                    int rc = b.getInt((int) aesOff + 16);
                    ranges = new long[rc][];
                    for (int r = 0; r < rc; r++)
                        ranges[r] = new long[]{b.getLong((int) aesOff + 20 + 16 * r), b.getLong((int) aesOff + 28 + 16 * r)};
                }
                index.put(hash, new Entry(size, offset, key, ranges));
            }
        }
    }
}
