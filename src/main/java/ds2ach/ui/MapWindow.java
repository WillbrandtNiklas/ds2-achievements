package ds2ach.ui;

import ds2ach.data.AchievementDb;
import ds2ach.data.AchievementDb.Achievement;
import ds2ach.data.World;
import ds2ach.data.World.Area;
import ds2ach.logic.Warnings;
import ds2ach.logic.WorldStatus;
import ds2ach.save.CharacterSlot;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.util.*;

/**
 * Weltkarte als Verbindungsgraph in einem eigenen Fenster (für den zweiten Monitor).
 * Mausrad = zoomen, Ziehen = verschieben, Klick auf ein Gebiet = Details rechts.
 */
public final class MapWindow {
    static final double NODE_W = 164, NODE_H = 58;

    /** Status eines Achievements für die Seitenleiste: "erledigt", "offen" oder "verpasst (?)". */
    public record AchView(Achievement achievement, String status) {}

    private final AchievementDb db;
    private final World world;
    private final Warnings warnings;
    private final Stage stage = new Stage();
    private final Pane content = new Pane();
    private final Group zoomGroup = new Group(content);
    private final Pane viewport = new Pane(zoomGroup);
    private final VBox side = new VBox(8);
    private final Label header = new Label();
    private final CheckBox showDlc = new CheckBox("DLC-Gebiete zeigen");

    private CharacterSlot current;
    private WorldStatus status;
    private List<AchView> achViews = List.of();
    private List<Warnings.Hinweis> hints = List.of();
    private String selected;
    private double dragX, dragY;

    public MapWindow(AchievementDb db, Warnings warnings, String stylesheet) {
        this.db = db;
        this.world = db.world;
        this.warnings = warnings;

        showDlc.setSelected(true);
        showDlc.setOnAction(e -> {
            redraw();
            resetView();
        });
        Button reset = new Button("Ansicht zurücksetzen");
        reset.setOnAction(e -> resetView());
        HBox top = new HBox(12, header, spacer(), legend(), showDlc, reset);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8, 12, 8, 12));
        top.getStyleClass().add("map-top");

        viewport.getStyleClass().add("map-viewport");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
        viewport.setOnScroll(e -> {
            double f = e.getDeltaY() > 0 ? 1.12 : 1 / 1.12;
            double s = Math.max(0.3, Math.min(3, zoomGroup.getScaleX() * f));
            zoomGroup.setScaleX(s);
            zoomGroup.setScaleY(s);
        });
        viewport.setOnMousePressed(e -> {
            dragX = e.getSceneX() - zoomGroup.getTranslateX();
            dragY = e.getSceneY() - zoomGroup.getTranslateY();
        });
        viewport.setOnMouseDragged(e -> {
            zoomGroup.setTranslateX(e.getSceneX() - dragX);
            zoomGroup.setTranslateY(e.getSceneY() - dragY);
        });

        side.setPadding(new Insets(12));
        side.setPrefWidth(360);
        ScrollPane sideScroll = new ScrollPane(side);
        sideScroll.setFitToWidth(true);
        sideScroll.setPrefWidth(380);
        sideScroll.getStyleClass().add("map-side");

        BorderPane root = new BorderPane(viewport, top, sideScroll, null, null);
        Scene scene = new Scene(root, 1500, 900);
        scene.getStylesheets().add(stylesheet);
        stage.setScene(scene);
        stage.setTitle("DS2 Weltkarte");
        redraw();
        showDetails();
    }

    public void show() {
        stage.show();
        stage.toFront();
        resetView();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public Stage stage() {
        return stage;
    }

    public void select(String areaId) {
        selected = areaId;
        redraw();
        showDetails();
    }

    /** Wird bei jedem neuen Spielstand bzw. jeder Änderung in der Checkliste aufgerufen. */
    public void update(CharacterSlot c, List<AchView> achViews, List<Warnings.Hinweis> hints) {
        this.current = c;
        this.achViews = achViews;
        this.hints = hints;
        this.status = new WorldStatus(world, db.gameData, c);
        header.setText(c == null ? "Kein Charakter" : c.name() + " – " + world.mapName(c.mapId()));
        if (selected == null && c != null)
            world.areasOnMap(c.mapId()).stream().findFirst().ifPresent(a -> selected = a.id());
        redraw();
        showDetails();
    }

    /** Ganze Karte ins Fenster einpassen. */
    private void resetView() {
        javafx.application.Platform.runLater(() -> {
            double maxX = 0, maxY = 0;
            for (Area a : world.areas) {
                if (!visible(a)) continue;
                maxX = Math.max(maxX, a.x() + NODE_W);
                maxY = Math.max(maxY, a.y() + NODE_H);
            }
            double vw = viewport.getWidth() > 0 ? viewport.getWidth() : 1100, vh = viewport.getHeight() > 0 ? viewport.getHeight() : 840;
            double s = Math.min(1.2, Math.min((vw - 30) / (maxX + 20), (vh - 30) / (maxY + 20)));
            zoomGroup.setScaleX(s);
            zoomGroup.setScaleY(s);
            // Skalierung wirkt um die Mitte der Gruppe: Verschiebung so wählen, dass die linke obere Ecke bei 10/10 liegt
            double cw = content.getBoundsInLocal().getWidth(), ch = content.getBoundsInLocal().getHeight();
            zoomGroup.setTranslateX(10 - (cw - cw * s) / 2 - content.getBoundsInLocal().getMinX() * s);
            zoomGroup.setTranslateY(10 - (ch - ch * s) / 2 - content.getBoundsInLocal().getMinY() * s);
        });
    }

    // ---------- Zeichnen ----------

    private boolean visible(Area a) {
        return showDlc.isSelected() || a.dlc() == null;
    }

    private void redraw() {
        content.getChildren().clear();
        if (status == null) status = new WorldStatus(world, db.gameData, current);
        // Kanten zuerst, damit die Knoten darüber liegen
        for (WorldStatus.ConnState cs : status.connections) {
            Area a = world.area(cs.connection().von()), b = world.area(cs.connection().nach());
            if (!visible(a) || !visible(b)) continue;
            drawEdge(cs, a, b);
        }
        for (Area a : world.areas) if (visible(a)) content.getChildren().add(areaNode(a));
    }

    private void drawEdge(WorldStatus.ConnState cs, Area a, Area b) {
        double x1 = a.x() + NODE_W / 2, y1 = a.y() + NODE_H / 2, x2 = b.x() + NODE_W / 2, y2 = b.y() + NODE_H / 2;
        Line l = new Line(x1, y1, x2, y2);
        String typ = cs.connection().typ() == null ? "weg" : cs.connection().typ();
        l.getStyleClass().add("edge");
        if (!cs.open()) l.getStyleClass().add("edge-locked");
        else if (typ.equals("teleport")) l.getStyleClass().add("edge-teleport");
        content.getChildren().add(l);

        if (cs.connection().einbahn()) {
            // Pfeilspitze am Rand des Zielknotens
            double ang = Math.atan2(y2 - y1, x2 - x1);
            double tx = x2 - Math.cos(ang) * (NODE_H / 2 + 6), ty = y2 - Math.sin(ang) * (NODE_H / 2 + 6);
            Polygon p = new Polygon(tx, ty,
                    tx - 12 * Math.cos(ang - 0.4), ty - 12 * Math.sin(ang - 0.4),
                    tx - 12 * Math.cos(ang + 0.4), ty - 12 * Math.sin(ang + 0.4));
            p.getStyleClass().add(cs.open() ? "edge-arrow" : "edge-arrow-locked");
            content.getChildren().add(p);
        }

        boolean special = !typ.equals("weg") || cs.connection().voraussetzung() != null || cs.connection().unsicher() != null;
        if (special) {
            double mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
            Circle c = new Circle(9);
            c.getStyleClass().add(!cs.open() ? "edge-marker-locked" : cs.openBecauseVisited() ? "edge-marker-visited" : "edge-marker");
            Label t = new Label(!cs.open() ? "!" : symbol(typ));
            t.getStyleClass().add("edge-marker-text");
            StackPane m = new StackPane(c, t);
            m.setLayoutX(mx - 10);
            m.setLayoutY(my - 10);
            Tooltip.install(m, new Tooltip(connectionText(cs, null)));
            content.getChildren().add(m);
        }
    }

    private static String symbol(String typ) {
        return switch (typ) {
            case "aufzug" -> "↕";
            case "tuer" -> "▯";
            case "teleport" -> "✦";
            case "schiff" -> "≈";
            case "sprung" -> "↓";
            default -> "·";
        };
    }

    private Node areaNode(Area a) {
        WorldStatus.AreaState st = status.areas.get(a.id());
        Label name = new Label(a.name());
        name.getStyleClass().add("area-name");
        name.setWrapText(true);
        List<String> parts = new ArrayList<>();
        if (!a.bonfires().isEmpty()) parts.add("Feuer " + st.litBonfires() + "/" + a.bonfires().size());
        if (!a.bosses().isEmpty()) parts.add("Bosse " + st.bossesDown() + "/" + a.bosses().size());
        String info = String.join("  ·  ", parts);
        Label sub = new Label(info);
        sub.getStyleClass().add("area-sub");
        VBox box = new VBox(2, name, sub);
        box.setAlignment(Pos.CENTER);
        box.setPrefSize(NODE_W, NODE_H);
        box.setMaxSize(NODE_W, NODE_H);
        box.setLayoutX(a.x());
        box.setLayoutY(a.y());
        box.getStyleClass().add("area");
        box.getStyleClass().add(st.current() ? "area-current" : st.visited() ? "area-visited" : "area-unvisited");
        if (a.dlc() != null) box.getStyleClass().add("area-dlc");
        if (a.id().equals(selected)) box.getStyleClass().add("area-selected");
        box.setOnMouseClicked(e -> {
            select(a.id());
            e.consume();
        });
        Tooltip.install(box, new Tooltip(a.name() + (st.current() ? "  (du bist hier)" : "")));
        return box;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private Node legend() {
        HBox h = new HBox(10);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getChildren().addAll(legendItem("area-current", "Aktuell"), legendItem("area-visited", "Besucht"),
                legendItem("area-unvisited", "Nicht besucht"));
        Label locked = new Label("!  verschlossen");
        locked.getStyleClass().add("legend-locked");
        h.getChildren().add(locked);
        return h;
    }

    private static Node legendItem(String style, String text) {
        Region r = new Region();
        r.setPrefSize(14, 14);
        r.getStyleClass().addAll("area", style);
        Label l = new Label(text);
        HBox h = new HBox(4, r, l);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    // ---------- Seitenleiste ----------

    private void showDetails() {
        side.getChildren().clear();
        Area a = selected == null ? null : world.area(selected);
        if (a == null) {
            side.getChildren().add(wrap("Klick auf ein Gebiet, um Leuchtfeuer, Bosse, Ausgänge und Achievements zu sehen.", "muted"));
            return;
        }
        WorldStatus.AreaState st = status.areas.get(a.id());
        Label title = new Label(a.name());
        title.getStyleClass().add("side-title");
        side.getChildren().add(title);
        String where = st.current() ? "Du bist hier" : st.visited() ? "Besucht" : "Noch nicht besucht";
        if (a.dlc() != null) where += "  ·  DLC: " + a.dlc();
        side.getChildren().add(wrap(where, st.current() ? "side-current" : "muted"));
        if (a.karten() != null && !a.karten().isEmpty()) {
            List<String> k = new ArrayList<>();
            for (String id : a.karten()) {
                int m = AchievementDb.parseId(id);
                k.add(String.format("m%02d_%02d", (m >>> 24) & 0xFF, (m >>> 16) & 0xFF));
            }
            side.getChildren().add(wrap("Karte: " + String.join(", ", k), "muted"));
        }
        if (a.hinweis() != null) side.getChildren().add(wrap(a.hinweis(), null));
        if (a.unsicher() != null) for (String u : a.unsicher()) side.getChildren().add(wrap("⚠ Bitte prüfen: " + u, "uncertain"));

        section("Leuchtfeuer");
        if (a.bonfires().isEmpty()) side.getChildren().add(wrap("keine", "muted"));
        for (int b : a.bonfires()) {
            boolean lit = current != null && current.litBonfires().contains(b);
            side.getChildren().add(wrap((lit ? "✓ " : "○ ") + world.bonfireName(b), lit ? "done-text" : null));
        }

        section("Bosse");
        if (a.bosses().isEmpty()) side.getChildren().add(wrap("keine", "muted"));
        for (String b : a.bosses()) {
            boolean down = current != null && current.bossKills().containsKey(b);
            side.getChildren().add(wrap((down ? "✓ " : "○ ") + db.gameData.bossName(b) + (down ? " (besiegt, ?)" : ""), down ? "done-text" : null));
        }

        section("Ausgänge");
        for (WorldStatus.ConnState cs : status.connections) {
            String other;
            if (cs.connection().von().equals(a.id())) other = cs.connection().nach();
            else if (cs.connection().nach().equals(a.id())) other = cs.connection().von();
            else continue;
            if (cs.connection().einbahn() && cs.connection().nach().equals(a.id()))
                other = world.area(other).name() + " (nur hierher)";
            else other = world.area(other).name();
            Label l = wrap(connectionText(cs, other), cs.open() ? null : "locked-text");
            side.getChildren().add(l);
            if (cs.connection().unsicher() != null)
                for (String u : cs.connection().unsicher()) side.getChildren().add(wrap("   ⚠ Bitte prüfen: " + u, "uncertain"));
        }

        section("Achievements hier");
        int n = 0;
        for (AchView v : achViews) {
            if (!warnings.relevantFor(v.achievement(), a)) continue;
            n++;
            String style = switch (v.status()) {
                case "erledigt" -> "done-text";
                case "offen" -> null;
                default -> "locked-text";
            };
            side.getChildren().add(wrap((v.status().equals("erledigt") ? "✓ " : "○ ") + v.achievement().name() + "  –  " + v.status(), style));
            for (Warnings.Hinweis h : hints)
                if (h.achievement() == v.achievement() && h.stufe() != Warnings.Stufe.VERPASST)
                    side.getChildren().add(wrap("   " + (h.stufe() == Warnings.Stufe.JETZT ? "⚠ " : "› ") + h.text(),
                            h.stufe() == Warnings.Stufe.JETZT ? "warn-now" : "warn-next-side"));
        }
        if (n == 0) side.getChildren().add(wrap("keine", "muted"));
    }

    private String connectionText(WorldStatus.ConnState cs, String otherName) {
        World.Connection k = cs.connection();
        String typ = switch (k.typ() == null ? "weg" : k.typ()) {
            case "aufzug" -> "Aufzug";
            case "tuer" -> "Tür/Tor";
            case "teleport" -> "Teleport";
            case "schiff" -> "Schiff";
            case "sprung" -> "Abstieg";
            default -> "Weg";
        };
        StringBuilder sb = new StringBuilder();
        sb.append(otherName != null ? "→ " + otherName : world.area(k.von()).name() + " → " + world.area(k.nach()).name());
        sb.append("  (").append(typ).append(k.einbahn() ? ", Einbahn" : "").append(")");
        if (!cs.open()) sb.append("\n   Verschlossen. Fehlt: ").append(String.join("; ", cs.missing()));
        else if (cs.openBecauseVisited()) sb.append("\n   Offen (beide Gebiete schon besucht)");
        if (k.voraussetzung() != null && k.voraussetzung().text() != null) sb.append("\n   ").append(k.voraussetzung().text());
        if (k.hinweis() != null) sb.append("\n   ").append(k.hinweis());
        return sb.toString();
    }

    private void section(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("side-section");
        side.getChildren().add(l);
    }

    private static Label wrap(String text, String style) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setMinHeight(Region.USE_PREF_SIZE);
        if (style != null) l.getStyleClass().add(style);
        return l;
    }
}
