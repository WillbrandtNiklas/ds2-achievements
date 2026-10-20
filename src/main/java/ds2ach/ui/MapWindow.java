package ds2ach.ui;

import ds2ach.data.AchievementDb;
import ds2ach.data.AchievementDb.Achievement;
import ds2ach.data.Items;
import ds2ach.logic.ItemStatus;
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
import java.util.function.Consumer;

/**
 * Weltkarte in einem eigenen Fenster (für den zweiten Monitor): Pergamentkarte oder Diagramm.
 * Mausrad = zoomen, Ziehen = verschieben, Klick auf ein Gebiet = Details und Items rechts.
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
    private final ToggleButton viewMap = new ToggleButton("Karte"), viewDiagram = new ToggleButton("Diagramm");
    private final ParchmentView parchment;
    private final Items items;
    private final Consumer<Long> onToggleLot;
    private final VBox itemsBox = new VBox(6);
    private final ComboBox<String> catFilter = new ComboBox<>();
    private final CheckBox onlyOpen = new CheckBox("nur nicht eingesammelte"), showNgPlus = new CheckBox("NG+-Fundorte");
    private final TabPane tabs = new TabPane();
    private final Set<Integer> achItemIds = new HashSet<>();
    private Set<Long> manualLots = Set.of();

    private CharacterSlot current;
    private WorldStatus status;
    private List<AchView> achViews = List.of();
    private List<Warnings.Hinweis> hints = List.of();
    private String selected;
    private double dragX, dragY;

    public MapWindow(AchievementDb db, Warnings warnings, Items items, String stylesheet, Consumer<Long> onToggleLot) {
        this.db = db;
        this.world = db.world;
        this.warnings = warnings;
        this.items = items;
        this.onToggleLot = onToggleLot;
        this.parchment = new ParchmentView(world);
        // Items mit Achievement-Bezug: Bedingungs-Items und alle Zauber der Master-Achievements
        for (Achievement a : db.achievements)
            if (a.bedingung() != null && a.bedingung().ids() != null)
                for (String id : a.bedingung().ids()) achItemIds.add(AchievementDb.parseId(id));
        db.gameData.spells().values().forEach(l -> l.forEach(s -> achItemIds.add(AchievementDb.parseId(s.id()))));

        ToggleGroup views = new ToggleGroup();
        viewMap.setToggleGroup(views);
        viewDiagram.setToggleGroup(views);
        viewMap.setSelected(true);
        views.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                ((ToggleButton) a).setSelected(true);
                return;
            }
            redraw();
            resetView();
        });
        parchment.node().setOnMouseClicked(e -> {
            if (!e.isStillSincePress()) return;
            parchment.hit(e.getX() / ParchmentView.RES, e.getY() / ParchmentView.RES, hiddenAreas()).ifPresent(a -> select(a.id()));
        });

        catFilter.getItems().add("Wichtige Items");
        for (Items.Kategorie k : Items.Kategorie.values()) catFilter.getItems().add(k.label);
        catFilter.getItems().add("Alle Items");
        catFilter.getSelectionModel().select(0);
        catFilter.setOnAction(e -> showItems());
        onlyOpen.setOnAction(e -> showItems());
        showNgPlus.setOnAction(e -> {
            redraw();
            showItems();
        });

        showDlc.setSelected(true);
        showDlc.setOnAction(e -> {
            redraw();
            resetView();
        });
        Button reset = new Button("Ansicht zurücksetzen");
        reset.setOnAction(e -> resetView());
        HBox top = new HBox(12, header, spacer(), legend(), viewMap, viewDiagram, showDlc, reset);
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
        ScrollPane sideScroll = new ScrollPane(side);
        sideScroll.setFitToWidth(true);
        sideScroll.getStyleClass().add("map-side");
        itemsBox.setPadding(new Insets(12));
        HBox filterRow = new HBox(8, catFilter, onlyOpen);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        VBox itemsTop = new VBox(6, filterRow, showNgPlus);
        itemsTop.setPadding(new Insets(10, 12, 4, 12));
        ScrollPane itemsScroll = new ScrollPane(itemsBox);
        itemsScroll.setFitToWidth(true);
        itemsScroll.getStyleClass().add("map-side");
        VBox.setVgrow(itemsScroll, Priority.ALWAYS);
        Tab areaTab = new Tab("Gebiet", sideScroll), itemTab = new Tab("Items", new VBox(itemsTop, itemsScroll));
        areaTab.setClosable(false);
        itemTab.setClosable(false);
        tabs.getTabs().addAll(areaTab, itemTab);
        tabs.setPrefWidth(400);

        BorderPane root = new BorderPane(viewport, top, tabs, null, null);
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

    /** Testhilfe: Reiter "Gebiet" oder "Items" anzeigen. */
    public void selectTab(String name) {
        for (Tab tb : tabs.getTabs()) if (tb.getText().equalsIgnoreCase(name)) tabs.getSelectionModel().select(tb);
    }

    public void select(String areaId) {
        selected = areaId;
        redraw();
        showDetails();
    }

    /** Wird bei jedem neuen Spielstand bzw. jeder Änderung in der Checkliste aufgerufen. */
    public void update(CharacterSlot c, List<AchView> achViews, List<Warnings.Hinweis> hints, Set<Long> manualLots) {
        this.current = c;
        this.manualLots = manualLots == null ? Set.of() : manualLots;
        if (c != null && !showNgPlus.isSelected() && c.ngValue() > 1) showNgPlus.setSelected(true);
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
            if (viewMap.isSelected()) {
                maxX = ParchmentView.W;
                maxY = ParchmentView.H;
            } else for (Area a : world.areas) {
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

    private Set<String> hiddenAreas() {
        Set<String> h = new HashSet<>();
        for (Area a : world.areas) if (!visible(a)) h.add(a.id());
        return h;
    }

    private void redraw() {
        content.getChildren().clear();
        if (status == null) status = new WorldStatus(world, db.gameData, current);
        if (viewMap.isSelected()) {
            Set<String> lit = new HashSet<>();
            if (current != null) current.litBonfires().forEach(b -> lit.add(String.valueOf(b)));
            parchment.setDownBosses(current == null ? Set.of() : current.bossKills().keySet());
            parchment.draw(status, lit, hiddenAreas(), selected, itemCounts());
            content.getChildren().add(parchment.node());
            return;
        }
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

    /** Wichtige Items pro Gebiet (offen / gesamt) für die Kartenbeschriftung. */
    private Map<String, ParchmentView.ItemCount> itemCounts() {
        Map<String, ParchmentView.ItemCount> m = new HashMap<>();
        if (items == null) return m;
        for (Area a : world.areas) {
            int total = 0, open = 0;
            for (Items.Lot l : items.lotsFor(world, a)) {
                if (!ngVisible(l) || l.zufall() || l.items().stream().noneMatch(i -> i.kategorie().important())) continue;
                total++;
                if (!ItemStatus.of(l, current, manualLots).done()) open++;
            }
            m.put(a.id(), new ParchmentView.ItemCount(open, total));
        }
        return m;
    }

    private boolean ngVisible(Items.Lot l) {
        return showNgPlus.isSelected() || !l.ngPlusOnly();
    }

    // ---------- Seitenleiste ----------

    private void showItems() {
        itemsBox.getChildren().clear();
        Area a = selected == null ? null : world.area(selected);
        if (items == null) {
            itemsBox.getChildren().add(wrap("Noch keine Item-Daten. Einmal setup.bat im App-Ordner ausführen: Es liest die "
                    + "Fundorte aus deiner Spielinstallation (nur lesend).", "uncertain"));
            return;
        }
        if (items.outdated())
            itemsBox.getChildren().add(wrap("⚠ Die Spieldatei hat sich seit dem Setup geändert. Bitte setup.bat neu ausführen.", "uncertain"));
        if (a == null) {
            itemsBox.getChildren().add(wrap("Klick auf ein Gebiet, um seine Items zu sehen.", "muted"));
            return;
        }
        Label title = new Label("Items: " + a.name());
        title.getStyleClass().add("side-title");
        itemsBox.getChildren().add(title);

        // Geteilte Karten: Items stehen beim ersten Gebiet der Karte, eine genauere Ortsangabe gibt es nicht
        Set<String> sharedWith = new LinkedHashSet<>();
        Area primary = null;
        if (a.karten() != null)
            for (String k : a.karten()) {
                int mapId = AchievementDb.parseId(k);
                Area p = world.primaryArea(mapId);
                if (p != null && !p.id().equals(a.id()) && primary == null) primary = p;
                for (Area o : world.areasOnMap(mapId)) if (!o.id().equals(a.id())) sharedWith.add(o.name());
            }
        List<Items.Lot> lots = items.lotsFor(world, a);
        if (lots.isEmpty() && primary != null) {
            itemsBox.getChildren().add(wrap("Dieses Gebiet teilt sich die Karte mit " + primary.name()
                    + ". Die Spieldaten unterscheiden die Fundorte nicht genauer, deshalb stehen alle Items dort.", "muted"));
            return;
        }
        if (!sharedWith.isEmpty())
            itemsBox.getChildren().add(wrap("Enthält auch die Items von " + String.join(", ", sharedWith)
                    + " (gleiche Karte, keine genauere Ortsangabe in den Spieldaten).", "muted"));

        String cat = catFilter.getValue();
        int done = 0, total = 0;
        List<Node> rows = new ArrayList<>();
        for (Items.Lot l : lots) {
            if (!ngVisible(l)) continue;
            boolean match = l.items().stream().anyMatch(i -> switch (cat) {
                case "Alle Items" -> true;
                case "Wichtige Items" -> i.kategorie().important();
                default -> i.kategorie().label.equals(cat);
            });
            if (!match) continue;
            ItemStatus.Status st = ItemStatus.of(l, current, manualLots);
            total++;
            if (st.done()) done++;
            if (onlyOpen.isSelected() && st.done()) continue;
            rows.add(lotRow(l, st));
        }
        itemsBox.getChildren().add(wrap(done + " von " + total + " Fundorten eingesammelt (" + cat + ")", "side-section"));
        itemsBox.getChildren().addAll(rows);
        if (rows.isEmpty()) itemsBox.getChildren().add(wrap("Keine passenden Fundorte.", "muted"));
        itemsBox.getChildren().add(wrap("Eingesammelt = eindeutiges Item (Schlüssel, Ring, Zauber, Waffe, Rüstung) liegt im Inventar. "
                + "Bei Verbrauchsgütern, Estus Flask Shards usw. steht „unbekannt“, bis die Aufheben-Flags entschlüsselt sind. "
                + "Mit dem Häkchen hakst du selbst ab. ★ = relevant für ein Achievement.", "muted"));
    }

    private Node lotRow(Items.Lot l, ItemStatus.Status st) {
        CheckBox cb = new CheckBox();
        cb.setSelected(st.done());
        cb.setDisable(current == null || st == ItemStatus.Status.EINGESAMMELT);
        cb.setOnAction(e -> onToggleLot.accept(l.id()));
        List<String> parts = new ArrayList<>();
        boolean ach = false;
        for (Items.Item i : l.items()) {
            parts.add(i.name() + (i.menge() > 1 ? " ×" + i.menge() : "") + (i.stufe() > 0 ? " +" + i.stufe() : ""));
            ach |= achItemIds.contains(i.id());
        }
        Label name = wrap((l.zufall() ? "Zufällig eines davon: " + String.join(" / ", parts) : String.join(", ", parts))
                + (ach ? "  ★" : ""), st.done() ? "done-text" : null);
        String cats = String.join(", ", l.items().stream().map(i -> i.kategorie().label).distinct().toList());
        String where = l.ort() == null ? "" : l.ort().replace(" - Chest", "").replace(" NG+", "");
        boolean special = !where.isEmpty() && world.areas.stream().noneMatch(ar -> where.startsWith(ar.name()) || ar.name().startsWith(where));
        Label sub = wrap((special ? where + " · " : "") + (l.chest() ? "Truhe · " : "") + cats + " · " + st.label + (l.ngPlusOnly() ? " · nur NG+" : ""), "muted");
        VBox v = new VBox(1, name, sub);
        HBox.setHgrow(v, Priority.ALWAYS);
        HBox row = new HBox(8, cb, v);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private void showDetails() {
        showItems();
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
