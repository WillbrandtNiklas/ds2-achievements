package ds2ach.ui;

import ds2ach.data.Items;
import ds2ach.data.MapGeo;
import ds2ach.data.World;
import ds2ach.logic.ItemStatus;
import ds2ach.save.CharacterSlot;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.PickResult;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import java.util.*;

/**
 * 3D-Ansicht einer Karte: Navigationsnetz (begehbare Flächen, nach Höhe eingefärbt) mit Markern für Items,
 * Leuchtfeuer, Gebietsübergänge und die Spielerposition aus dem Spielstand.
 * Linke Maustaste ziehen = drehen, rechte = verschieben, Mausrad = zoomen.
 * Koordinaten: Spiel (y nach oben) -> JavaFX (y nach unten), also y gespiegelt.
 */
public final class Map3DView {
    private final World world;
    private final StackPane root = new StackPane();
    private final Group content = new Group(), markers = new Group();
    private final SubScene sub;
    private final PerspectiveCamera cam = new PerspectiveCamera(true);
    private final Rotate yaw = new Rotate(30, Rotate.Y_AXIS), pitch = new Rotate(-45, Rotate.X_AXIS);
    private final Translate target = new Translate(), dist = new Translate(0, 0, -300);
    private final Label title = new Label(), info = new Label(), hint = new Label();
    private final List<Node> scaled = new ArrayList<>();

    static final String ORBIT_HINT = "Ziehen = drehen · Rechts/Shift ziehen = verschieben · Mausrad = zum Mauszeiger zoomen · "
            + "Doppelklick = Drehpunkt setzen · WASD = verschieben · Marker anklicken = Details";
    private final ToggleButton freeBtn = new ToggleButton("Freie Kamera");
    private final Set<KeyCode> keys = EnumSet.noneOf(KeyCode.class);
    private boolean freeCam;

    private MapGeo.Map3D map;
    private double[] center = new double[3];
    private double extent = 200, mx, my;
    private double[] player;
    private double playerYaw;

    public Map3DView(World world) {
        this.world = world;
        Group camRig = new Group(cam);
        camRig.getTransforms().addAll(target, yaw, pitch, dist);
        cam.setNearClip(0.3);
        cam.setFarClip(20000);
        cam.setFieldOfView(45);

        AmbientLight amb = new AmbientLight(Color.rgb(70, 66, 60));
        DirectionalLight sun = new DirectionalLight(Color.rgb(175, 165, 150));
        sun.setDirection(new Point3D(0.35, 1, 0.5)); // von oben (JavaFX: y nach unten)
        Group scene3d = new Group(content, markers, camRig, amb, sun);
        sub = new SubScene(scene3d, 800, 600, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(22, 20, 18));
        sub.setCamera(cam);
        sub.widthProperty().bind(root.widthProperty());
        sub.heightProperty().bind(root.heightProperty());

        sub.setFocusTraversable(true);
        sub.setOnMousePressed(e -> {
            mx = e.getSceneX();
            my = e.getSceneY();
            sub.requestFocus();
        });
        sub.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - mx, dy = e.getSceneY() - my;
            mx = e.getSceneX();
            my = e.getSceneY();
            if (freeCam) { // Umsehen: Kamera dreht sich um sich selbst
                yaw.setAngle(yaw.getAngle() + dx * 0.25);
                pitch.setAngle(clamp(pitch.getAngle() - dy * 0.25, -89, 89));
            } else if (e.isPrimaryButtonDown() && !e.isShiftDown()) { // Orbit um den Drehpunkt
                yaw.setAngle(yaw.getAngle() + dx * 0.3);
                pitch.setAngle(clamp(pitch.getAngle() - dy * 0.3, -89.5, 10));
            } else { // Verschieben in der Bodenebene, abhängig von der Blickrichtung
                double k = -dist.getZ() / 900, a = Math.toRadians(yaw.getAngle());
                target.setX(target.getX() - (dx * Math.cos(a) - dy * Math.sin(a)) * k);
                target.setZ(target.getZ() + (dx * Math.sin(a) + dy * Math.cos(a)) * k);
            }
        });
        sub.setOnScroll(e -> {
            if (e.getDeltaY() == 0) return;
            if (freeCam) { // vor/zurück in Blickrichtung
                move(forward(), Math.signum(e.getDeltaY()) * extent / 40);
                return;
            }
            double f = e.getDeltaY() > 0 ? 0.85 : 1 / 0.85;
            double nd = clamp(-dist.getZ() * f, 3, extent * 4);
            f = nd / -dist.getZ();
            // Zoom zum Mauszeiger: der Punkt unter der Maus bleibt an seiner Stelle
            Point3D hit = pickedPoint(e.getPickResult());
            if (hit != null) {
                target.setX(hit.getX() + (target.getX() - hit.getX()) * f);
                target.setY(hit.getY() + (target.getY() - hit.getY()) * f);
                target.setZ(hit.getZ() + (target.getZ() - hit.getZ()) * f);
            }
            dist.setZ(-nd);
            rescaleMarkers();
        });
        sub.setOnMouseClicked(e -> { // Doppelklick: neuer Drehpunkt (umsehen um diese Stelle)
            if (e.getClickCount() != 2 || freeCam) return;
            Point3D hit = pickedPoint(e.getPickResult());
            if (hit == null) return;
            target.setX(hit.getX());
            target.setY(hit.getY());
            target.setZ(hit.getZ());
            dist.setZ(-Math.min(-dist.getZ(), 80));
            rescaleMarkers();
        });
        sub.setOnKeyPressed(e -> keys.add(e.getCode()));
        sub.setOnKeyReleased(e -> keys.remove(e.getCode()));
        new AnimationTimer() { // WASD/QE: fliegen (freie Kamera) bzw. Drehpunkt verschieben (Orbit)
            long last;

            @Override
            public void handle(long now) {
                double dt = last == 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
                last = now;
                if (keys.isEmpty() || dt == 0) return;
                double speed = (keys.contains(KeyCode.SHIFT) ? 3 : 1) * Math.max(8, extent / 12) * dt;
                Point3D fwd = freeCam ? forward() : flat(forward()), right = yaw.deltaTransform(new Point3D(1, 0, 0));
                if (keys.contains(KeyCode.W) || keys.contains(KeyCode.UP)) move(fwd, speed);
                if (keys.contains(KeyCode.S) || keys.contains(KeyCode.DOWN)) move(fwd, -speed);
                if (keys.contains(KeyCode.D) || keys.contains(KeyCode.RIGHT)) move(right, speed);
                if (keys.contains(KeyCode.A) || keys.contains(KeyCode.LEFT)) move(right, -speed);
                if (keys.contains(KeyCode.E) || keys.contains(KeyCode.SPACE)) move(new Point3D(0, -1, 0), speed); // JavaFX: y nach unten
                if (keys.contains(KeyCode.Q) || keys.contains(KeyCode.CONTROL)) move(new Point3D(0, 1, 0), speed);
            }
        }.start();

        Button oblique = new Button("Schräg"), top = new Button("Draufsicht"), all = new Button("Ganze Karte"),
                me = new Button("Zum Spieler"), eyes = new Button("Vom Spieler aus");
        oblique.setOnAction(e -> {
            setFreeCam(false);
            pitch.setAngle(-45);
        });
        top.setOnAction(e -> {
            setFreeCam(false);
            pitch.setAngle(-89.5);
        });
        all.setOnAction(e -> {
            setFreeCam(false);
            fit();
        });
        me.setOnAction(e -> {
            setFreeCam(false);
            focusPlayer();
        });
        eyes.setOnAction(e -> playerView());
        freeBtn.setOnAction(e -> setFreeCam(freeBtn.isSelected()));
        HBox bar = new HBox(6, title, spacer(), oblique, top, all, me, eyes, freeBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        bar.setPickOnBounds(false);
        title.getStyleClass().add("map3d-title");
        info.getStyleClass().add("map3d-info");
        info.setWrapText(true);
        info.setMaxWidth(420);
        info.setVisible(false);
        hint.getStyleClass().add("map3d-hint");
        hint.setText(ORBIT_HINT);
        VBox overlay = new VBox(bar, spacer(), info, hint);
        VBox.setVgrow(overlay.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);
        overlay.setPadding(new Insets(0, 0, 8, 8));
        overlay.setPickOnBounds(false);
        root.getChildren().addAll(sub, overlay);
        root.setMinSize(0, 0);
    }

    public Region node() {
        return root;
    }

    public MapGeo.Map3D current() {
        return map;
    }

    /** Neue Karte anzeigen (Netz einmal aufbauen). */
    public void show(MapGeo.Map3D m, String name) {
        if (m == map) return;
        map = m;
        content.getChildren().clear();
        title.setText(name + "  ·  3D (Navigationsnetz)");
        info.setVisible(false);
        if (m == null || m.netz() == null || m.netz().punkte().length == 0) {
            title.setText(name + "  ·  keine 3D-Daten (setup.bat ausführen)");
            return;
        }
        content.getChildren().add(buildMesh(m.netz()));
        fit();
    }

    /** Marker neu setzen: wird bei jedem Spielstand bzw. jeder Änderung aufgerufen. */
    public void update(CharacterSlot c, Items items, Set<Long> manual, Set<Integer> achItems) {
        markers.getChildren().clear();
        scaled.clear();
        player = null;
        if (map == null) return;
        Map<Long, Items.Lot> lots = new HashMap<>();
        if (items != null) for (Items.Lot l : items.lots) lots.put(l.id(), l);

        for (MapGeo.ItemSpot s : map.items()) {
            Items.Lot lot = lots.get(s.lot());
            ItemStatus.Status st = lot == null ? ItemStatus.Status.UNBEKANNT : ItemStatus.of(lot, c, manual);
            boolean ach = lot != null && lot.items().stream().anyMatch(i -> achItems.contains(i.id()));
            Color col = st.done() ? Color.rgb(90, 190, 90) : st == ItemStatus.Status.OFFEN ? Color.rgb(240, 150, 40) : Color.rgb(120, 145, 190);
            Node n = s.typ() == 120 ? box(1.6, 1.0, 1.0, col) : diamond(col);
            if (ach) n = new Group(n, ring(Color.rgb(255, 210, 60)));
            String text = (lot == null ? "Fundort " + s.lot() : describe(lot)) + "\n" + (s.typ() == 120 ? "Truhe · " : "") + st.label + (ach ? " · ★ Achievement" : "");
            place(n, s.x(), s.y() + 1.2, s.z(), text);
        }
        for (MapGeo.Bonfire b : map.leuchtfeuer()) {
            boolean lit = c != null && c.litBonfires().contains(b.id());
            Group g = new Group(cyl(0.7, 0.5, Color.rgb(90, 80, 70)), flame(lit));
            place(g, b.x(), b.y(), b.z(), "Leuchtfeuer: " + world.bonfireName(b.id()) + (lit ? " (entzündet)" : " (noch nicht entzündet)"));
            if (lit) {
                PointLight pl = new PointLight(Color.rgb(255, 150, 60));
                pl.setMaxRange(25);
                pl.setTranslateX(b.x());
                pl.setTranslateY(-(b.y() + 3));
                pl.setTranslateZ(b.z());
                markers.getChildren().add(pl);
            }
        }
        for (MapGeo.Exit e : map.uebergaenge()) {
            Node n = box(0.6, 5, 0.6, Color.rgb(170, 110, 220));
            place(n, e.x(), e.y() + 2.5, e.z(), "Übergang nach " + world.mapName(e.targetMapId()));
        }
        if (c != null && c.mapId() == map.mapId() && c.position() != null) {
            float[] p = c.position();
            player = new double[]{p[0], p[1], p[2]};
            playerYaw = p[3];
            place(playerMarker(p[3]), p[0], p[1], p[2], c.name() + " (Position beim letzten Speichern)");
        }
        rescaleMarkers();
    }

    // ---------- Kamera ----------

    private void fit() {
        setFreeCam(false);
        target.setX(center[0]);
        target.setY(-center[1]);
        target.setZ(center[2]);
        dist.setZ(-extent * 1.15);
        pitch.setAngle(-50);
        rescaleMarkers();
    }

    /** Blickrichtung der Kamera (JavaFX-Koordinaten). */
    private Point3D forward() {
        return yaw.deltaTransform(pitch.deltaTransform(new Point3D(0, 0, 1)));
    }

    private static Point3D flat(Point3D v) {
        Point3D f = new Point3D(v.getX(), 0, v.getZ());
        return f.magnitude() < 1e-6 ? new Point3D(0, 0, 1) : f.normalize();
    }

    private void move(Point3D dir, double amount) {
        target.setX(target.getX() + dir.getX() * amount);
        target.setY(target.getY() + dir.getY() * amount);
        target.setZ(target.getZ() + dir.getZ() * amount);
    }

    /** Augenposition = Drehpunkt + Abstand entlang der Blickrichtung. */
    private Point3D eye() {
        return new Point3D(target.getX(), target.getY(), target.getZ()).add(forward().multiply(dist.getZ()));
    }

    /**
     * Freie Kamera: die Kamera steht an der aktuellen Augenposition und dreht sich um sich selbst (Abstand 0).
     * Zurück zum Orbit: Drehpunkt 40 m vor die Kamera legen, Ansicht bleibt gleich.
     */
    private void setFreeCam(boolean on) {
        if (on == freeCam) return;
        Point3D eye = eye();
        freeCam = on;
        freeBtn.setSelected(on);
        if (on) {
            target.setX(eye.getX());
            target.setY(eye.getY());
            target.setZ(eye.getZ());
            dist.setZ(0);
            hint.setText("Freie Kamera: Maus ziehen = umsehen · WASD = bewegen · Q/E = runter/hoch · Shift = schneller · Mausrad = vor/zurück");
        } else {
            pitch.setAngle(clamp(pitch.getAngle(), -89.5, 10));
            Point3D t = eye.add(forward().multiply(40));
            target.setX(t.getX());
            target.setY(t.getY());
            target.setZ(t.getZ());
            dist.setZ(-40);
            hint.setText(ORBIT_HINT);
        }
        sub.requestFocus();
        rescaleMarkers();
    }

    /** In Augenhöhe an der gespeicherten Spielerposition stehen und in seine Blickrichtung schauen. */
    public void playerView() {
        if (player == null) {
            info.setText("Der Spieler ist nicht auf dieser Karte.");
            info.setVisible(true);
            return;
        }
        setFreeCam(true);
        target.setX(player[0]);
        target.setY(-(player[1] + 1.7));
        target.setZ(player[2]);
        yaw.setAngle(-Math.toDegrees(playerYaw));
        pitch.setAngle(-8);
        rescaleMarkers();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Getroffener Punkt in Weltkoordinaten (Netz oder Marker), null wenn ins Leere geklickt. */
    private Point3D pickedPoint(PickResult pr) {
        if (pr == null || pr.getIntersectedNode() == null || pr.getIntersectedPoint() == null) return null;
        Node n = pr.getIntersectedNode();
        Point3D p = pr.getIntersectedPoint();
        while (n != null && n != content && n != markers) {
            p = n.localToParent(p);
            n = n.getParent();
        }
        return n == null ? null : p;
    }

    public void focusPlayer() {
        if (player == null) {
            info.setText("Der Spieler ist nicht auf dieser Karte.");
            info.setVisible(true);
            return;
        }
        target.setX(player[0]);
        target.setY(-player[1]);
        target.setZ(player[2]);
        dist.setZ(-60);
        rescaleMarkers();
    }

    /** Marker wachsen beim Herauszoomen mit, damit sie sichtbar bleiben. */
    private void rescaleMarkers() {
        double s = Math.max(0.8, Math.min(6, -dist.getZ() / 90));
        for (Node n : scaled) {
            n.setScaleX(s);
            n.setScaleY(s);
            n.setScaleZ(s);
        }
    }

    // ---------- Geometrie ----------

    private Node buildMesh(MapGeo.Net net) {
        double[] p = net.punkte();
        int n = p.length / 3;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double[] min = {Double.MAX_VALUE, 0, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, 0, -Double.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            minY = Math.min(minY, p[3 * i + 1]);
            maxY = Math.max(maxY, p[3 * i + 1]);
            min[0] = Math.min(min[0], p[3 * i]);
            max[0] = Math.max(max[0], p[3 * i]);
            min[2] = Math.min(min[2], p[3 * i + 2]);
            max[2] = Math.max(max[2], p[3 * i + 2]);
        }
        center = new double[]{(min[0] + max[0]) / 2, (minY + maxY) / 2, (min[2] + max[2]) / 2};
        extent = Math.max(60, Math.max(max[0] - min[0], max[2] - min[2]));

        TriangleMesh mesh = new TriangleMesh();
        float[] pts = new float[n * 3], tex = new float[n * 2];
        for (int i = 0; i < n; i++) {
            pts[3 * i] = (float) p[3 * i];
            pts[3 * i + 1] = (float) -p[3 * i + 1];
            pts[3 * i + 2] = (float) p[3 * i + 2];
            tex[2 * i] = (float) ((p[3 * i + 1] - minY) / Math.max(1, maxY - minY)) * 0.98f + 0.01f;
            tex[2 * i + 1] = 0.5f;
        }
        mesh.getPoints().setAll(pts);
        mesh.getTexCoords().setAll(tex);
        int[] t = net.dreiecke();
        int[] faces = new int[t.length * 2];
        for (int i = 0; i < t.length; i++) {
            faces[2 * i] = t[i];
            faces[2 * i + 1] = t[i];
        }
        mesh.getFaces().setAll(faces);
        MeshView mv = new MeshView(mesh);
        mv.setCullFace(CullFace.NONE);
        PhongMaterial mat = new PhongMaterial();
        mat.setDiffuseMap(heightGradient());
        mat.setSpecularColor(Color.rgb(20, 20, 20));
        mv.setMaterial(mat);
        mv.setOnMouseClicked(e -> info.setVisible(false));
        // Dunkle Kanten machen Wege und Stufen lesbarer
        MeshView edges = new MeshView(mesh);
        edges.setDrawMode(DrawMode.LINE);
        edges.setCullFace(CullFace.NONE);
        edges.setMaterial(new PhongMaterial(Color.rgb(35, 30, 22)));
        edges.setMouseTransparent(true);
        edges.setTranslateY(-0.03);
        return new Group(mv, edges);
    }

    /** Höhenverlauf: tief = dunkles Grün, mittel = Ocker, hoch = helles Pergament. */
    private static WritableImage heightGradient() {
        WritableImage img = new WritableImage(256, 2);
        Color[] stops = {Color.rgb(46, 74, 60), Color.rgb(96, 120, 70), Color.rgb(170, 150, 90), Color.rgb(225, 210, 170)};
        for (int x = 0; x < 256; x++) {
            double t = x / 255.0 * (stops.length - 1);
            int i = Math.min(stops.length - 2, (int) t);
            Color c = stops[i].interpolate(stops[i + 1], t - i);
            img.getPixelWriter().setColor(x, 0, c);
            img.getPixelWriter().setColor(x, 1, c);
        }
        return img;
    }

    private void place(Node n, double x, double y, double z, String text) {
        Group g = new Group(n);
        g.setTranslateX(x);
        g.setTranslateY(-y);
        g.setTranslateZ(z);
        Tooltip.install(g, new Tooltip(text));
        g.setOnMouseClicked(e -> {
            info.setText(text);
            info.setVisible(true);
            e.consume();
        });
        g.setCursor(Cursor.HAND);
        markers.getChildren().add(g);
        scaled.add(g);
    }

    /** Marker leuchten leicht selbst, damit die Statusfarbe unabhängig vom Licht gut erkennbar bleibt. */
    private static PhongMaterial mat(Color c) {
        PhongMaterial m = new PhongMaterial(c);
        m.setSpecularColor(Color.rgb(40, 40, 40));
        WritableImage glow = new WritableImage(1, 1);
        glow.getPixelWriter().setColor(0, 0, c.deriveColor(0, 1, 0.45, 1));
        m.setSelfIlluminationMap(glow);
        return m;
    }

    private static Node box(double w, double h, double d, Color c) {
        Box b = new Box(w, h, d);
        b.setMaterial(mat(c));
        return b;
    }

    private static Node diamond(Color c) {
        Box b = new Box(0.9, 0.9, 0.9);
        b.setMaterial(mat(c));
        b.getTransforms().addAll(new Rotate(45, Rotate.Y_AXIS), new Rotate(45, Rotate.X_AXIS));
        return b;
    }

    private static Node ring(Color c) {
        Cylinder r = new Cylinder(1.0, 0.12);
        r.setMaterial(mat(c));
        r.setTranslateY(0.9);
        return r;
    }

    private static Node cyl(double r, double h, Color c) {
        Cylinder cy = new Cylinder(r, h);
        cy.setMaterial(mat(c));
        cy.setTranslateY(-h / 2);
        return cy;
    }

    private static Node flame(boolean lit) {
        Sphere s = new Sphere(0.55);
        s.setMaterial(mat(lit ? Color.rgb(255, 140, 40) : Color.rgb(110, 105, 100)));
        s.setScaleY(1.7);
        s.setTranslateY(-1.4);
        if (lit) {
            PhongMaterial m = (PhongMaterial) s.getMaterial();
            WritableImage glow = new WritableImage(1, 1);
            glow.getPixelWriter().setColor(0, 0, Color.rgb(255, 170, 60));
            m.setSelfIlluminationMap(glow);
        }
        return s;
    }

    /** Spieler: leuchtende Säule mit Kopf und Richtungspfeil, dazu ein hoher Lichtstrahl zum Wiederfinden. */
    private static Node playerMarker(float yawRad) {
        Color cyan = Color.rgb(80, 220, 255);
        Cylinder body = new Cylinder(0.45, 1.8);
        body.setMaterial(mat(cyan));
        body.setTranslateY(-0.9);
        Sphere head = new Sphere(0.45);
        head.setMaterial(mat(Color.WHITE));
        head.setTranslateY(-2.1);
        Box arrow = new Box(0.3, 0.3, 1.6);
        arrow.setMaterial(mat(cyan));
        arrow.setTranslateY(-1.0);
        arrow.setTranslateZ(0.9);
        Group facing = new Group(arrow);
        facing.getTransforms().add(new Rotate(-Math.toDegrees(yawRad), Rotate.Y_AXIS));
        Cylinder beam = new Cylinder(0.12, 40);
        PhongMaterial bm = new PhongMaterial(Color.rgb(80, 220, 255, 0.35));
        beam.setMaterial(bm);
        beam.setTranslateY(-22);
        Group g = new Group(beam, body, head, facing);
        ScaleTransition pulse = new ScaleTransition(Duration.millis(900), head);
        pulse.setFromX(1);
        pulse.setFromY(1);
        pulse.setFromZ(1);
        pulse.setToX(1.5);
        pulse.setToY(1.5);
        pulse.setToZ(1.5);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
        return g;
    }

    private static String describe(Items.Lot lot) {
        List<String> parts = new ArrayList<>();
        for (Items.Item i : lot.items())
            parts.add(i.name() + (i.menge() > 1 ? " ×" + i.menge() : "") + (i.stufe() > 0 ? " +" + i.stufe() : ""));
        return (lot.zufall() ? "Zufällig eines davon: " + String.join(" / ", parts) : String.join(", ", parts));
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }
}
