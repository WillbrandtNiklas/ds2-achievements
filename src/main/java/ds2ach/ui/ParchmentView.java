package ds2ach.ui;

import ds2ach.data.World;
import ds2ach.data.World.Area;
import ds2ach.logic.WorldStatus;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.*;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.*;
import java.util.function.Consumer;

/**
 * Gezeichnete Karte im Stil der Pergamentkarte in Majula: Gebiete als Landflächen, Wege als Pfade,
 * Leuchtfeuer als Flammen, Bosse als Totenköpfe, unbesuchte Gebiete unter Nebel.
 * Alles prozedural gezeichnet, keine Spielgrafik. Positionen kommen aus world.json (Feld karte).
 */
public final class ParchmentView {
    static final double W = 1600, H = 1000, RES = 2; // doppelte Auflösung, damit es beim Zoomen scharf bleibt

    /** Zähler wichtiger Items pro Gebiet: offen / gesamt. */
    public record ItemCount(int open, int total) {}

    private final World world;
    private final Canvas canvas = new Canvas(W * RES, H * RES);
    private final Font title, label, small;
    private Canvas background;

    public ParchmentView(World world) {
        this.world = world;
        Font cinzel = Font.loadFont(ParchmentView.class.getResourceAsStream("/fonts/Cinzel.ttf"), 15);
        String fam = cinzel != null ? cinzel.getFamily() : "Serif";
        title = Font.font(fam, FontWeight.BOLD, 44);
        label = Font.font(fam, FontWeight.BOLD, 19);
        small = Font.font(fam, FontWeight.BOLD, 13);
        canvas.setScaleX(1 / RES);
        canvas.setScaleY(1 / RES);
        canvas.setTranslateX(-W * (RES - 1) / 2);
        canvas.setTranslateY(-H * (RES - 1) / 2);
    }

    public Canvas node() {
        return canvas;
    }

    /** Gebiet unter einem Punkt in Kartenkoordinaten (für Klicks). */
    public Optional<Area> hit(double x, double y, Set<String> hidden) {
        Area best = null;
        double bd = Double.MAX_VALUE;
        for (Area a : world.areas) {
            if (a.karte() == null || hidden.contains(a.id())) continue;
            double d = Math.hypot(x - a.karte().x(), y - a.karte().y());
            if (d < a.karte().r() * 1.15 && d < bd) {
                bd = d;
                best = a;
            }
        }
        return Optional.ofNullable(best);
    }

    public void draw(WorldStatus st, Set<String> litBonfires, Set<String> hidden, String selected, Map<String, ItemCount> items) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.save();
        g.setTransform(RES, 0, 0, RES, 0, 0);
        drawBackground(g);

        for (WorldStatus.ConnState cs : st.connections) {
            Area a = world.area(cs.connection().von()), b = world.area(cs.connection().nach());
            if (a.karte() == null || b.karte() == null || hidden.contains(a.id()) || hidden.contains(b.id())) continue;
            drawPath(g, cs, a, b);
        }
        for (Area a : world.areas) {
            if (a.karte() == null || hidden.contains(a.id())) continue;
            drawArea(g, a, st.areas.get(a.id()), a.id().equals(selected));
        }
        for (Area a : world.areas) {
            if (a.karte() == null || hidden.contains(a.id())) continue;
            drawLabel(g, a, st.areas.get(a.id()), litBonfires, items.get(a.id()));
        }
        g.restore();
    }

    // ---------- Hintergrund ----------

    private void drawBackground(GraphicsContext g) {
        // Pergament mit Flecken, Rand-Abdunklung, Meer, Unterwelt, Rahmen und Kompass (wird jedes Mal neu gezeichnet, ist schnell genug)
        g.setFill(Color.web("#e6d3a8"));
        g.fillRect(0, 0, W, H);
        Random r = new Random(7);
        for (int i = 0; i < 900; i++) {
            double x = r.nextDouble() * W, y = r.nextDouble() * H, s = 4 + r.nextDouble() * 40;
            g.setFill(Color.rgb(120 + r.nextInt(40), 90 + r.nextInt(30), 50, 0.025 + r.nextDouble() * 0.035));
            g.fillOval(x - s / 2, y - s / 2, s, s * (0.6 + r.nextDouble() * 0.8));
        }
        g.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.75, true, CycleMethod.NO_CYCLE,
                new Stop(0.55, Color.rgb(0, 0, 0, 0)), new Stop(1, Color.rgb(70, 40, 10, 0.45))));
        g.fillRect(0, 0, W, H);

        // Meer im Süden und Osten (Majula, Heide's Tower und die Lost Bastille liegen an der Küste)
        double[] sx = {745, 760, 830, 900, 1080, 1250, 1350, 1600, 1600, 860, 745, 745};
        double[] sy = {770, 760, 790, 770, 790, 700, 560, 520, 1000, 1000, 1000, 1000};
        g.setFill(Color.rgb(88, 118, 128, 0.22));
        g.fillPolygon(sx, sy, sx.length);
        g.setStroke(Color.rgb(70, 60, 45, 0.5));
        g.setLineWidth(1.6);
        g.strokePolyline(Arrays.copyOfRange(sx, 1, 8), Arrays.copyOfRange(sy, 1, 8), 7);
        g.setStroke(Color.rgb(70, 90, 100, 0.25));
        g.setLineWidth(1);
        for (int i = 0; i < 70; i++) { // Wellen
            double x = 760 + r.nextDouble() * 820, y = 820 + r.nextDouble() * 160;
            if (x < 900 && y < 840) continue;
            g.strokeArc(x, y, 14, 7, 20, 140, ArcType.OPEN);
        }

        // Unterwelt unter Majula (Grave of Saints, The Gutter, Black Gulch, Shulva)
        g.setFill(Color.rgb(50, 35, 20, 0.16));
        g.fillRoundRect(130, 765, 600, 222, 40, 40);
        g.setStroke(Color.rgb(60, 40, 20, 0.45));
        g.setLineDashes(6, 6);
        g.strokeRoundRect(130, 765, 600, 222, 40, 40);
        g.setLineDashes();
        g.setFont(small);
        g.setFill(Color.rgb(60, 40, 20, 0.75));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("Unter Majula", 148, 788);

        // Rahmen
        g.setStroke(Color.rgb(70, 45, 20, 0.85));
        g.setLineWidth(3);
        g.strokeRect(10, 10, W - 20, H - 20);
        g.setLineWidth(1);
        g.strokeRect(18, 18, W - 36, H - 36);

        // Titel und Kompass
        g.setFont(title);
        g.setFill(Color.rgb(60, 35, 15, 0.85));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("DRANGLEIC", 1230, 960);
        compass(g, 1520, 860, 44);
    }

    private void compass(GraphicsContext g, double cx, double cy, double s) {
        g.setStroke(Color.rgb(60, 35, 15, 0.8));
        g.setLineWidth(1.2);
        g.strokeOval(cx - s * 0.7, cy - s * 0.7, s * 1.4, s * 1.4);
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i;
            double[] xs = {cx + Math.cos(a) * s, cx + Math.cos(a + 0.35) * s * 0.25, cx, cx + Math.cos(a - 0.35) * s * 0.25};
            double[] ys = {cy - Math.sin(a) * s, cy - Math.sin(a + 0.35) * s * 0.25, cy, cy - Math.sin(a - 0.35) * s * 0.25};
            g.setFill(i % 2 == 0 ? Color.rgb(60, 35, 15, 0.85) : Color.rgb(160, 120, 70, 0.8));
            g.fillPolygon(xs, ys, 4);
        }
        g.setFont(small);
        g.setFill(Color.rgb(60, 35, 15, 0.9));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("N", cx, cy - s - 6);
    }

    // ---------- Wege ----------

    private void drawPath(GraphicsContext g, WorldStatus.ConnState cs, Area a, Area b) {
        double x1 = a.karte().x(), y1 = a.karte().y(), x2 = b.karte().x(), y2 = b.karte().y();
        // leicht gebogen, Richtung abhängig von den IDs, damit es nicht mechanisch aussieht
        double mx = (x1 + x2) / 2, my = (y1 + y2) / 2, dx = x2 - x1, dy = y2 - y1;
        double bend = ((a.id().hashCode() ^ b.id().hashCode()) & 1) == 0 ? 0.12 : -0.12;
        double cx = mx - dy * bend, cy = my + dx * bend;
        String typ = cs.connection().typ() == null ? "weg" : cs.connection().typ();
        g.setLineCap(StrokeLineCap.ROUND);
        if (!cs.open()) {
            g.setStroke(Color.rgb(150, 30, 25, 0.8));
            g.setLineDashes(9, 7);
            g.setLineWidth(2.2);
        } else if (typ.equals("teleport")) {
            g.setStroke(Color.rgb(60, 90, 140, 0.7));
            g.setLineDashes(2, 7);
            g.setLineWidth(2.4);
        } else {
            g.setStroke(Color.rgb(80, 55, 30, 0.75));
            g.setLineDashes(typ.equals("schiff") ? new double[]{12, 6} : new double[]{5, 5});
            g.setLineWidth(2);
        }
        g.beginPath();
        g.moveTo(x1, y1);
        g.quadraticCurveTo(cx, cy, x2, y2);
        g.stroke();
        g.setLineDashes();
        // Kurvenmitte (t = 0.5) für Schloss-Symbol
        double px = 0.25 * x1 + 0.5 * cx + 0.25 * x2, py = 0.25 * y1 + 0.5 * cy + 0.25 * y2;
        if (!cs.open()) padlock(g, px, py);
    }

    private static void padlock(GraphicsContext g, double x, double y) {
        g.setFill(Color.rgb(235, 220, 190));
        g.fillOval(x - 11, y - 11, 22, 22);
        g.setStroke(Color.rgb(150, 30, 25));
        g.setLineWidth(1.6);
        g.strokeOval(x - 11, y - 11, 22, 22);
        g.strokeArc(x - 4, y - 8, 8, 9, 0, 180, ArcType.OPEN);
        g.setFill(Color.rgb(150, 30, 25));
        g.fillRect(x - 5.5, y - 3, 11, 8);
    }

    // ---------- Gebiete ----------

    /** Unregelmäßige Landfläche um die Gebietsmitte, pro Gebiet immer gleich (Seed aus der ID). */
    private static double[][] blob(Area a, double grow) {
        Random r = new Random(a.id().hashCode());
        int n = 28;
        double[] xs = new double[n], ys = new double[n];
        double p1 = r.nextDouble() * 6, p2 = r.nextDouble() * 6, stretch = 0.75 + r.nextDouble() * 0.5;
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            double rr = a.karte().r() * grow * (1 + 0.13 * Math.sin(3 * t + p1) + 0.08 * Math.sin(5 * t + p2) + 0.04 * r.nextGaussian());
            xs[i] = a.karte().x() + Math.cos(t) * rr * (1.25 * stretch);
            ys[i] = a.karte().y() + Math.sin(t) * rr * 0.85;
        }
        return new double[][]{xs, ys};
    }

    private void drawArea(GraphicsContext g, Area a, WorldStatus.AreaState st, boolean selected) {
        double[][] p = blob(a, 1);
        boolean memory = a.id().startsWith("memory");
        if (st.current()) {
            double[][] glow = blob(a, 1.22);
            g.setFill(Color.rgb(255, 170, 40, 0.35));
            g.fillPolygon(glow[0], glow[1], glow[0].length);
        }
        Color fill = st.visited()
                ? (a.dlc() != null ? Color.rgb(150, 160, 120, 0.55) : Color.rgb(176, 150, 92, 0.55))
                : Color.rgb(150, 140, 125, 0.28);
        if (memory) fill = Color.rgb(140, 150, 175, st.visited() ? 0.5 : 0.25);
        g.setFill(fill);
        g.fillPolygon(p[0], p[1], p[0].length);
        if (!st.visited()) { // Nebel: Schraffur
            g.save();
            g.beginPath();
            g.moveTo(p[0][0], p[1][0]);
            for (int i = 1; i < p[0].length; i++) g.lineTo(p[0][i], p[1][i]);
            g.closePath();
            g.clip();
            g.setStroke(Color.rgb(110, 100, 90, 0.18));
            g.setLineWidth(1);
            double x0 = a.karte().x() - a.karte().r() * 2, x1 = a.karte().x() + a.karte().r() * 2;
            for (double x = x0; x < x1; x += 11) g.strokeLine(x, a.karte().y() - a.karte().r() * 1.5, x + a.karte().r() * 1.5, a.karte().y() + a.karte().r() * 1.5);
            g.restore();
        }
        g.setStroke(selected ? Color.rgb(30, 80, 160) : Color.rgb(80, 55, 30, 0.85));
        g.setLineWidth(selected ? 3.2 : 1.6);
        if (a.dlc() != null) g.setLineDashes(7, 4);
        g.strokePolygon(p[0], p[1], p[0].length);
        g.setLineDashes();
        terrain(g, a, st.visited());
    }

    /** Kleine Tuschezeichnungen je nach Gelände (world.json, Feld gelaende), um die Beschriftung herum. */
    private static void terrain(GraphicsContext g, Area a, boolean visited) {
        String typ = a.gelaende() == null ? "" : a.gelaende();
        Random r = new Random(a.id().hashCode() * 31L);
        double cx = a.karte().x(), cy = a.karte().y(), rad = a.karte().r();
        Color ink = Color.rgb(70, 50, 28, visited ? 0.55 : 0.28);
        g.setStroke(ink);
        g.setFill(ink);
        g.setLineWidth(1.3);
        int n = (int) Math.max(3, rad / 9);
        for (int i = 0; i < n; i++) {
            // Punkte auf einem Ring um die Mitte, damit die Schrift frei bleibt
            double ang = r.nextDouble() * Math.PI * 2, dist = rad * (0.62 + r.nextDouble() * 0.38);
            double x = cx + Math.cos(ang) * dist * 1.15, y = cy + Math.sin(ang) * dist * 0.72;
            if (Math.abs(y - cy) < 16 && Math.abs(x - cx) < rad * 0.9) continue;
            switch (typ) {
                case "wald" -> tree(g, x, y, 6 + r.nextDouble() * 3);
                case "berg" -> mountain(g, x, y, 9 + r.nextDouble() * 5);
                case "wasser" -> {
                    g.strokeArc(x - 7, y - 3, 14, 6, 20, 140, ArcType.OPEN);
                    g.strokeArc(x - 1, y + 1, 14, 6, 20, 140, ArcType.OPEN);
                }
                case "hoehle" -> g.strokeArc(x - 7, y - 5, 14, 12, 0, 180, ArcType.OPEN);
                case "erinnerung" -> {
                    g.strokeOval(x - 2.5, y - 2.5, 5, 5);
                    g.strokeLine(x - 6, y, x - 4, y);
                    g.strokeLine(x + 4, y, x + 6, y);
                }
                default -> { }
            }
        }
        if (typ.equals("burg") || typ.equals("turm") || typ.equals("stadt")) {
            double x = cx + rad * 0.95, y = cy - rad * 0.32;
            if (typ.equals("turm")) tower(g, x, y);
            else if (typ.equals("burg")) castle(g, x, y);
            else houses(g, x, y);
        }
    }

    private static void tree(GraphicsContext g, double x, double y, double s) {
        g.fillPolygon(new double[]{x, x - s * 0.7, x + s * 0.7}, new double[]{y - s * 1.4, y, y}, 3);
        g.strokeLine(x, y, x, y + s * 0.5);
    }

    private static void mountain(GraphicsContext g, double x, double y, double s) {
        g.strokePolyline(new double[]{x - s, x - s * 0.15, x + s * 0.2, x + s * 0.55, x + s},
                new double[]{y + s * 0.4, y - s * 0.7, y - s * 0.25, y - s * 0.5, y + s * 0.4}, 5);
        g.strokeLine(x - s * 0.15, y - s * 0.7, x, y + s * 0.4);
    }

    private static void castle(GraphicsContext g, double x, double y) {
        g.strokeRect(x - 9, y - 6, 18, 12);
        g.strokeRect(x - 13, y - 13, 6, 19);
        g.strokeRect(x + 7, y - 13, 6, 19);
        for (double dx : new double[]{-13, -9, 7, 11}) g.fillRect(x + dx, y - 16, 2, 3);
        g.strokeArc(x - 3, y, 6, 8, 0, 180, ArcType.OPEN);
    }

    private static void tower(GraphicsContext g, double x, double y) {
        g.strokePolygon(new double[]{x - 5, x + 5, x + 4, x - 4}, new double[]{y + 10, y + 10, y - 10, y - 10}, 4);
        g.fillPolygon(new double[]{x - 6, x + 6, x}, new double[]{y - 10, y - 10, y - 19}, 3);
    }

    private static void houses(GraphicsContext g, double x, double y) {
        for (int i = 0; i < 3; i++) {
            double hx = x - 10 + i * 9, hy = y + (i == 1 ? -4 : 0);
            g.strokeRect(hx - 3.5, hy - 3, 7, 7);
            g.strokePolyline(new double[]{hx - 4.5, hx, hx + 4.5}, new double[]{hy - 3, hy - 8, hy - 3}, 3);
        }
    }

    private void drawLabel(GraphicsContext g, Area a, WorldStatus.AreaState st, Set<String> lit, ItemCount items) {
        double x = a.karte().x(), y = a.karte().y();
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(label);
        String name = a.name().replace("Brightstone Cove Tseldora", "Brightstone Cove").replace("Memory of the Old Iron King", "Memory of the Iron King");
        // Lange Namen zweizeilig, damit sich benachbarte Beschriftungen nicht überlappen
        List<String> lines = new ArrayList<>();
        int mid = name.length() / 2, cut = -1;
        if (name.length() > 15)
            for (int d = 0; d < mid && cut < 0; d++) {
                if (mid + d < name.length() && name.charAt(mid + d) == ' ') cut = mid + d;
                else if (name.charAt(mid - d) == ' ') cut = mid - d;
            }
        if (cut > 0) {
            lines.add(name.substring(0, cut));
            lines.add(name.substring(cut + 1));
        } else lines.add(name);
        double ly = y - 4 - (lines.size() - 1) * 19;
        for (String line : lines) {
            g.setFill(Color.rgb(235, 220, 190, 0.75)); // Halo für Lesbarkeit
            for (double ox = -2; ox <= 2; ox += 1)
                for (double oy = -2; oy <= 2; oy += 1) g.fillText(line, x + ox, ly + oy);
            g.setFill(st.visited() ? Color.rgb(45, 25, 10) : Color.rgb(95, 80, 65));
            g.fillText(line, x, ly);
            ly += 19;
        }

        // Leuchtfeuer als Flammen, Bosse als Totenköpfe
        int nb = a.bonfires().size(), nk = a.bosses().size();
        double total = nb * 14 + nk * 15 + (nb > 0 && nk > 0 ? 8 : 0);
        double cx = x - total / 2 + 7;
        for (int b : a.bonfires()) {
            flame(g, cx, y + 14, lit.contains(String.valueOf(b)));
            cx += 14;
        }
        if (nb > 0 && nk > 0) cx += 8;
        for (String boss : a.bosses()) {
            skull(g, cx, y + 14, st.bossesDown() > 0 && isDown(st, boss));
            cx += 15;
        }
        if (items != null && items.total() > 0) {
            g.setFont(small);
            g.setFill(items.open() == 0 ? Color.rgb(40, 100, 40) : Color.rgb(80, 50, 20));
            String txt = "Items " + (items.total() - items.open()) + "/" + items.total();
            g.setFill(Color.rgb(235, 220, 190, 0.8));
            for (double ox = -1; ox <= 1; ox += 1) for (double oy = -1; oy <= 1; oy += 1) g.fillText(txt, x + ox, y + 38 + oy);
            g.setFill(items.open() == 0 ? Color.rgb(40, 100, 40) : Color.rgb(80, 50, 20));
            g.fillText(txt, x, y + 38);
        }
    }

    private Set<String> downBosses = Set.of();

    /** Vor draw() setzen: welche Bosse besiegt sind. */
    public void setDownBosses(Set<String> down) {
        this.downBosses = down;
    }

    private boolean isDown(WorldStatus.AreaState st, String boss) {
        return downBosses.contains(boss);
    }

    private static void flame(GraphicsContext g, double x, double y, boolean lit) {
        if (lit) {
            g.setFill(Color.rgb(255, 160, 40, 0.35));
            g.fillOval(x - 8, y - 10, 16, 18);
        }
        g.setFill(lit ? Color.rgb(230, 110, 20) : Color.rgb(140, 125, 110, 0.7));
        g.beginPath();
        g.moveTo(x, y - 9);
        g.bezierCurveTo(x + 6, y - 3, x + 6, y + 5, x, y + 6);
        g.bezierCurveTo(x - 6, y + 5, x - 6, y - 3, x, y - 9);
        g.closePath();
        g.fill();
        if (lit) {
            g.setFill(Color.rgb(255, 220, 90));
            g.fillOval(x - 2.2, y - 1, 4.4, 6);
        }
    }

    private static void skull(GraphicsContext g, double x, double y, boolean down) {
        Color c = down ? Color.rgb(120, 110, 100, 0.65) : Color.rgb(60, 35, 20);
        g.setFill(c);
        g.fillOval(x - 6, y - 8, 12, 11);
        g.fillRect(x - 4, y + 1, 8, 5);
        g.setFill(Color.rgb(230, 215, 185));
        g.fillOval(x - 4, y - 5, 3.4, 3.6);
        g.fillOval(x + 0.6, y - 5, 3.4, 3.6);
        if (down) { // durchgestrichen = besiegt
            g.setStroke(Color.rgb(150, 30, 25));
            g.setLineWidth(1.6);
            g.strokeLine(x - 7, y + 6, x + 7, y - 8);
        }
    }
}
