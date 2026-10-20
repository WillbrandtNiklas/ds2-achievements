package ds2ach.data;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Kartenmodell mit Texturen aus extracted/models/mAA_BB.bin.gz (erzeugt von setup.bat).
 * Eine Gruppe pro Farbtextur, jede mit eigenen Punkten. Die Dreiecke einer Gruppe sind nach ihrer Höhe über
 * dem Weg sortiert: für eine Schnitthöhe h zeigt man die ersten countUpTo(h).
 */
public final class MapModel {
    /** Teil des Modells mit einer Textur (texture = -1: keine gefunden, Tonfarbe). */
    public static final class Group {
        public final int texture;
        public final boolean alpha;   // Material mit Transparenz (Blätter, Gitter)
        public final float[] verts;   // x, y, z (Spielkoordinaten, y nach oben)
        public final float[] uvs;     // u, v
        public final int[] tris;      // a, b, c (Indizes in verts dieser Gruppe)
        public final short[] heights; // Dezimeter über dem höchsten Weg in der Umgebung, aufsteigend

        Group(int texture, boolean alpha, float[] verts, float[] uvs, int[] tris, short[] heights) {
            this.texture = texture;
            this.alpha = alpha;
            this.verts = verts;
            this.uvs = uvs;
            this.tris = tris;
            this.heights = heights;
        }

        public int triangleCount() {
            return heights.length;
        }

        /** Anzahl Dreiecke mit Höhe über dem Weg höchstens cutMeters (binäre Suche, Liste ist sortiert). */
        public int countUpTo(double cutMeters) {
            int limit = (int) Math.floor(cutMeters * 10), lo = 0, hi = heights.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (heights[mid] <= limit) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }
    }

    /** Farbtextur als DXT1/DXT5 (format 1 oder 5), höchste gespeicherte Stufe w x h vorn in data. */
    public record Texture(String name, int format, int width, int height, byte[] data) {
        /** Erste Mipmap-Stufe als ARGB-Pixel. DXT1 mit c0 <= c1: Index 3 ist durchsichtig. */
        public int[] decode() {
            int w = width, h = height, block = format == 1 ? 8 : 16;
            int[] px = new int[w * h];
            int bw = Math.max(1, (w + 3) / 4), bh = Math.max(1, (h + 3) / 4);
            int[] pal = new int[4], alpha = new int[8];
            for (int by = 0; by < bh; by++)
                for (int bx = 0; bx < bw; bx++) {
                    int o = (by * bw + bx) * block;
                    if (o + block > data.length) continue;
                    long abits = 0;
                    if (block == 16) { // DXT5: Alpha-Block (2 Stützwerte + 16 x 3 Bit)
                        int a0 = data[o] & 0xFF, a1 = data[o + 1] & 0xFF;
                        alpha[0] = a0;
                        alpha[1] = a1;
                        for (int k = 1; k < 7; k++) {
                            if (a0 > a1) alpha[k + 1] = ((7 - k) * a0 + k * a1) / 7;
                            else if (k < 5) alpha[k + 1] = ((5 - k) * a0 + k * a1) / 5;
                        }
                        if (a0 <= a1) {
                            alpha[6] = 0;
                            alpha[7] = 255;
                        }
                        for (int k = 0; k < 6; k++) abits |= (long) (data[o + 2 + k] & 0xFF) << (8 * k);
                        o += 8;
                    }
                    int c0 = (data[o] & 0xFF) | (data[o + 1] & 0xFF) << 8, c1 = (data[o + 2] & 0xFF) | (data[o + 3] & 0xFF) << 8;
                    int bits = (data[o + 4] & 0xFF) | (data[o + 5] & 0xFF) << 8 | (data[o + 6] & 0xFF) << 16 | (data[o + 7] & 0xFF) << 24;
                    pal[0] = rgb565(c0);
                    pal[1] = rgb565(c1);
                    if (c0 > c1 || block == 16) {
                        pal[2] = mix(pal[0], pal[1], 2, 1);
                        pal[3] = mix(pal[0], pal[1], 1, 2);
                    } else {
                        pal[2] = mix(pal[0], pal[1], 1, 1);
                        pal[3] = 0; // durchsichtig
                    }
                    for (int py = 0; py < 4; py++)
                        for (int pxl = 0; pxl < 4; pxl++) {
                            int x = bx * 4 + pxl, y = by * 4 + py;
                            if (x >= w || y >= h) continue;
                            int i = py * 4 + pxl, c = pal[(bits >>> (2 * i)) & 3];
                            if (block == 16) c = (c & 0xFFFFFF) | alpha[(int) (abits >>> (3 * i)) & 7] << 24;
                            px[y * w + x] = c;
                        }
                }
            return px;
        }

        /** Dekodiert und halbiert, bis die Seiten höchstens maxSize sind (ein paar Spieltexturen haben keine Mipmaps). */
        public Pixels decode(int maxSize) {
            int w = width, h = height;
            int[] px = decode();
            while ((w > maxSize || h > maxSize) && w > 1 && h > 1) {
                int nw = w / 2, nh = h / 2;
                int[] out = new int[nw * nh];
                for (int y = 0; y < nh; y++)
                    for (int x = 0; x < nw; x++) {
                        int a = px[2 * y * w + 2 * x], b = px[2 * y * w + 2 * x + 1], c = px[(2 * y + 1) * w + 2 * x], d = px[(2 * y + 1) * w + 2 * x + 1];
                        int r = 0;
                        for (int s = 0; s < 32; s += 8)
                            r |= ((((a >>> s) & 0xFF) + ((b >>> s) & 0xFF) + ((c >>> s) & 0xFF) + ((d >>> s) & 0xFF) + 2) / 4) << s;
                        out[y * nw + x] = r;
                    }
                px = out;
                w = nw;
                h = nh;
            }
            return new Pixels(w, h, px);
        }

        private static int rgb565(int c) {
            int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
            return 0xFF000000 | ((r * 255 / 31) << 16) | ((g * 255 / 63) << 8) | (b * 255 / 31);
        }

        private static int mix(int a, int b, int wa, int wb) {
            int s = wa + wb;
            int r = (((a >> 16) & 0xFF) * wa + ((b >> 16) & 0xFF) * wb) / s, g = (((a >> 8) & 0xFF) * wa + ((b >> 8) & 0xFF) * wb) / s,
                    bl = ((a & 0xFF) * wa + (b & 0xFF) * wb) / s;
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
    }

    /** Dekodierte Textur als ARGB. */
    public record Pixels(int width, int height, int[] argb) {}

    public final List<Group> groups;
    public final List<Texture> textures;

    private MapModel(List<Group> groups, List<Texture> textures) {
        this.groups = groups;
        this.textures = textures;
    }

    public int triangleCount() {
        int n = 0;
        for (Group g : groups) n += g.triangleCount();
        return n;
    }

    public int countUpTo(double cutMeters) {
        int n = 0;
        for (Group g : groups) n += g.countUpTo(cutMeters);
        return n;
    }

    public static MapModel read(Path p) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(p), 1 << 16), 1 << 16))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("DS2MODL2"))
                throw new IOException("Altes oder unbekanntes Modellformat (setup.bat neu ausführen): " + p);
            int vc = in.readInt();
            float[] all = new float[vc * 5];
            for (int i = 0; i < all.length; i++) all[i] = in.readFloat();
            int gc = in.readInt();
            List<Group> groups = new ArrayList<>(gc);
            int[] local = new int[vc];
            for (int g = 0; g < gc; g++) {
                int tex = in.readInt();
                boolean alpha = in.readBoolean();
                int tc = in.readInt();
                int[] t = new int[tc * 3];
                for (int i = 0; i < t.length; i++) t[i] = in.readInt();
                short[] h = new short[tc];
                for (int i = 0; i < tc; i++) h[i] = in.readShort();
                // Punkte dieser Gruppe herauslösen (jedes Teilnetz bekommt nur seine eigenen)
                java.util.Arrays.fill(local, -1);
                int n = 0;
                for (int i = 0; i < t.length; i++) {
                    if (t[i] < 0 || t[i] >= vc) throw new IOException("Defekte Modelldatei: " + p);
                    if (local[t[i]] < 0) local[t[i]] = n++;
                }
                float[] v = new float[n * 3], uv = new float[n * 2];
                for (int i = 0; i < vc; i++) {
                    int l = local[i];
                    if (l < 0) continue;
                    System.arraycopy(all, 5 * i, v, 3 * l, 3);
                    uv[2 * l] = all[5 * i + 3];
                    uv[2 * l + 1] = all[5 * i + 4];
                }
                for (int i = 0; i < t.length; i++) t[i] = local[t[i]];
                groups.add(new Group(tex, alpha, v, uv, t, h));
            }
            int texCount = in.readInt();
            List<Texture> texs = new ArrayList<>(texCount);
            for (int i = 0; i < texCount; i++) {
                String name = in.readUTF();
                int format = in.readByte(), w = in.readInt(), h = in.readInt();
                in.readInt(); // Mipmap-Anzahl (die Ansicht nutzt nur die erste Stufe)
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                texs.add(new Texture(name, format, w, h, data));
            }
            return new MapModel(groups, texs);
        }
    }
}
