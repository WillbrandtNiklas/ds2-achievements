package ds2ach.data;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Tonmodell einer Karte aus extracted/models/mAA_BB.bin.gz (erzeugt von setup.bat).
 * Dreiecke sind nach ihrer Höhe über dem Weg sortiert: für eine Schnitthöhe h zeigt man die ersten countUpTo(h).
 */
public final class MapModel {
    public final float[] verts;   // x, y, z (Spielkoordinaten, y nach oben)
    public final int[] tris;      // a, b, c
    public final short[] heights; // Dezimeter über dem höchsten Weg in der Umgebung, aufsteigend

    private MapModel(float[] verts, int[] tris, short[] heights) {
        this.verts = verts;
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

    public static MapModel read(Path p) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(p), 1 << 16), 1 << 16))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("DS2MODEL")) throw new IOException("Keine Modelldatei: " + p);
            if (in.readInt() != 1) throw new IOException("Unbekannte Version: " + p);
            int vc = in.readInt(), tc = in.readInt();
            float[] v = new float[vc * 3];
            for (int i = 0; i < v.length; i++) v[i] = in.readFloat();
            int[] t = new int[tc * 3];
            for (int i = 0; i < t.length; i++) t[i] = in.readInt();
            short[] h = new short[tc];
            for (int i = 0; i < tc; i++) h[i] = in.readShort();
            return new MapModel(v, t, h);
        }
    }
}
