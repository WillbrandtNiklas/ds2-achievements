package ds2ach.ui;

import ds2ach.data.Items;
import ds2ach.data.MapGeo;
import ds2ach.data.World;
import ds2ach.logic.ItemStatus;
import ds2ach.save.CharacterSlot;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.robot.Robot;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import java.util.*;

/**
 * 3D-Ansicht einer Karte: Navigationsnetz (begehbare Flächen, nach Höhe eingefärbt) mit Markern für Items,
 * Leuchtfeuer, Gebietsübergänge und die Spielerposition aus dem Spielstand.
 *
 * Zwei Steuerungsmodi (Taste F oder Knopf):
 * - Orbit: links ziehen = drehen, rechts ziehen = verschieben, Mausrad = zum Mauszeiger zoomen, Doppelklick = fokussieren.
 * - Flug: WASD/QE bewegen, Shift schneller, Maus umsehen (Mausfang per Klick, Esc beendet), Mausrad = Fluggeschwindigkeit.
 * Kamera: Drehpunkt (target) + Drehung (yaw, pitch) + Abstand (dist). Im Flugmodus ist der Abstand 0, die Kamera steht am Drehpunkt.
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
    private final Label title = new Label(), info = new Label(), modeLabel = new Label();
    private final VBox help = new VBox(2);
    private final List<Node> scaled = new ArrayList<>();
    private final ToggleButton flyBtn = new ToggleButton("Flugmodus (F)"), helpBtn = new ToggleButton("Steuerung (H)");
    private final ToggleButton netBtn = new ToggleButton("Wege-Netz (N)"), texBtn = new ToggleButton("Texturen (T)");
    private final javafx.scene.control.Slider cutSlider = new javafx.scene.control.Slider(1, 40, 6);
    private final Label cutLabel = new Label();
    private ds2ach.data.MapModel model;
    private final List<TriangleMesh> modelMeshes = new ArrayList<>();
    private final List<int[]> modelFaces = new ArrayList<>();
    private final List<MeshView> modelViews = new ArrayList<>();
    private final List<PhongMaterial> texMaterials = new ArrayList<>();
    private final PhongMaterial clay = clayMaterial();
    private Node navGroup = new Group();
    private String mapName = "";
    private volatile long loadToken;
    private final Set<KeyCode> keys = EnumSet.noneOf(KeyCode.class);
    private final Robot robot = new Robot();

    /** Kamerabereich, aus der Bounding Box des Gebiets berechnet (siehe applyBounds). */
    private double minDist = 2, maxDist = 600;
    private boolean fly, pointerLocked;
    private double flySpeed = 20; // m/s, aus der Gebietsgröße abgeleitet und per Mausrad änderbar
    private Point2D lockCenter;

    private MapGeo.Map3D map;
    private double[] center = new double[3];
    private double extent = 200, height = 30, mx, my;
    private double[] player;
    private double playerYaw;
    private Node playerNode;

    public Map3DView(World world) {
        this.world = world;
        Group camRig = new Group(cam);
        camRig.getTransforms().addAll(target, yaw, pitch, dist);
        cam.setFieldOfView(50);

        AmbientLight amb = new AmbientLight(Color.rgb(92, 87, 80));
        DirectionalLight sun = new DirectionalLight(Color.rgb(190, 180, 165));
        sun.setDirection(new Point3D(0.35, 1, 0.5)); // von oben (JavaFX: y nach unten)
        Group scene3d = new Group(content, markers, camRig, amb, sun);
        sub = new SubScene(scene3d, 800, 600, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(22, 20, 18));
        sub.setCamera(cam);
        sub.widthProperty().bind(root.widthProperty());
        sub.heightProperty().bind(root.heightProperty());
        sub.setFocusTraversable(true);

        installMouse();
        installKeys();

        Button oblique = new Button("Schräg"), top = new Button("Draufsicht"), all = new Button("Ganzes Gebiet"),
                me = new Button("Zum Spieler"), eyes = new Button("Vom Spieler aus");
        oblique.setOnAction(e -> {
            setFly(false);
            pitch.setAngle(-45);
        });
        top.setOnAction(e -> {
            setFly(false);
            pitch.setAngle(-89.5);
        });
        all.setOnAction(e -> fit());
        me.setOnAction(e -> focusPlayer());
        eyes.setOnAction(e -> playerView());
        flyBtn.setOnAction(e -> setFly(flyBtn.isSelected()));
        helpBtn.setSelected(true);
        helpBtn.setOnAction(e -> help.setVisible(helpBtn.isSelected()));
        for (Node b : List.of(oblique, top, all, me, eyes, flyBtn, helpBtn)) {
            b.setFocusTraversable(false);
            ((Region) b).setMinWidth(Region.USE_PREF_SIZE); // Knöpfe nie abschneiden, der Titel weicht aus
        }
        title.setMinWidth(0);
        title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        // Overlay: nur die Bedienelemente selbst fangen Mausereignisse. Abstandshalter und Texte sind
        // mausdurchlässig, sonst landen Ziehen und Mausrad nicht bei der 3D-Fläche (das war der Fehler).
        Region gap = passThroughSpacer();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(6, title, gap, oblique, top, all, me, eyes, flyBtn, helpBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        bar.setPickOnBounds(false);
        title.getStyleClass().add("map3d-title");
        title.setMouseTransparent(true);
        modeLabel.getStyleClass().add("map3d-mode");
        modeLabel.setMouseTransparent(true);
        info.getStyleClass().add("map3d-info");
        info.setWrapText(true);
        info.setMaxWidth(420);
        info.setVisible(false);
        info.setMouseTransparent(true);
        help.getStyleClass().add("map3d-help");
        help.setMouseTransparent(true);
        help.setMaxWidth(Region.USE_PREF_SIZE);
        Region fill = passThroughSpacer();
        VBox.setVgrow(fill, Priority.ALWAYS);
        VBox bottom = new VBox(6, info, help);
        bottom.setPickOnBounds(false);
        bottom.setMouseTransparent(true);
        // Zweite Zeile: Schnitthöhe des Modells, Texturen und Wege-Netz
        cutSlider.setPrefWidth(220);
        cutSlider.setFocusTraversable(false);
        cutSlider.setShowTickMarks(true);
        cutSlider.setMajorTickUnit(10);
        cutSlider.valueProperty().addListener((o, a, b) -> applyCut());
        Tooltip.install(cutSlider, new Tooltip("Alles, was mehr als diese Höhe über dem Weg liegt, wird ausgeblendet (ganz rechts = kein Schnitt)"));
        cutLabel.getStyleClass().add("map3d-mode");
        cutLabel.setMinWidth(110);
        cutLabel.setMouseTransparent(true);
        cutLabel.setText("Schnitt: 6 m");
        netBtn.setFocusTraversable(false);
        netBtn.setOnAction(e -> navGroup.setVisible(netBtn.isSelected() || model == null));
        texBtn.setFocusTraversable(false);
        texBtn.setSelected(true);
        texBtn.setOnAction(e -> applyMaterials());
        Tooltip.install(texBtn, new Tooltip("Aus: einfarbiges Tonmodell, Formen sind dann leichter zu erkennen"));
        HBox cutRow = new HBox(8, modeLabel, passThroughSpacer(), cutLabel, cutSlider, texBtn, netBtn);
        HBox.setHgrow(cutRow.getChildren().get(1), Priority.ALWAYS);
        cutRow.setAlignment(Pos.CENTER_LEFT);
        cutRow.setPadding(new Insets(0, 8, 0, 0));
        cutRow.setPickOnBounds(false);
        VBox overlay = new VBox(bar, cutRow, fill, bottom);
        overlay.setPadding(new Insets(0, 0, 8, 8));
        overlay.setPickOnBounds(false);
        root.getChildren().addAll(sub, overlay);
        root.setMinSize(0, 0);
        updateModeTexts();
    }

    private static Region passThroughSpacer() {
        Region r = new Region();
        r.setMouseTransparent(true);
        r.setPickOnBounds(false);
        return r;
    }

    // ---------- Maus ----------

    private void installMouse() {
        sub.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            mx = e.getScreenX();
            my = e.getScreenY();
            sub.requestFocus();
            if (fly && !pointerLocked && e.getButton() == MouseButton.PRIMARY) lockPointer();
        });
        sub.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (fly) {
                if (pointerLocked) look(e.getScreenX(), e.getScreenY());
                return;
            }
            double dx = e.getScreenX() - mx, dy = e.getScreenY() - my;
            mx = e.getScreenX();
            my = e.getScreenY();
            if (e.getButton() == MouseButton.PRIMARY && !e.isShiftDown()) { // drehen um den Drehpunkt
                yaw.setAngle(yaw.getAngle() + dx * 0.3);
                pitch.setAngle(clamp(pitch.getAngle() - dy * 0.3, -89.5, 5));
            } else if (e.getButton() == MouseButton.SECONDARY || e.getButton() == MouseButton.MIDDLE || e.isShiftDown()) {
                pan(dx, dy);
            }
        });
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (fly && pointerLocked) look(e.getScreenX(), e.getScreenY());
        });
        sub.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() == 0) return;
            if (fly) {
                flySpeed = clamp(flySpeed * (e.getDeltaY() > 0 ? 1.25 : 0.8), 1, extent);
                updateModeTexts();
            } else {
                zoomToCursor(e.getX(), e.getY(), e.getPickResult(), e.getDeltaY() > 0);
            }
            e.consume();
        });
        sub.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (fly || e.getButton() != MouseButton.PRIMARY) return;
            if (e.getClickCount() == 2) {
                Point3D p = pointUnderCursor(e.getX(), e.getY(), e.getPickResult());
                if (p != null) focusOn(p, Math.max(minDist * 6, extent * 0.06));
            } else if (e.isStillSincePress() && pickedPoint(e.getPickResult()) == null) info.setVisible(false);
        });
    }

    /** Verschieben in der Bildebene; Geschwindigkeit wächst mit dem Abstand. */
    private void pan(double dx, double dy) {
        double k = Math.max(-dist.getZ(), minDist) * 2 * Math.tan(Math.toRadians(cam.getFieldOfView() / 2)) / Math.max(1, sub.getHeight());
        Point3D right = yaw.deltaTransform(new Point3D(1, 0, 0));
        Point3D up = yaw.deltaTransform(pitch.deltaTransform(new Point3D(0, 1, 0)));
        move(right, -dx * k);
        move(up, -dy * k);
    }

    /**
     * Zoomen zum Mauszeiger: Drehpunkt und Abstand so ändern, dass der Punkt unter der Maus an seiner Stelle bleibt.
     * Am minimalen Abstand schiebt sich die Kamera weiter in Blickrichtung durchs Level, statt stehenzubleiben.
     */
    private void zoomToCursor(double sx, double sy, PickResult pr, boolean in) {
        Point3D p = pointUnderCursor(sx, sy, pr);
        double d = -dist.getZ(), f = in ? 0.8 : 1.25;
        double nd = clamp(d * f, minDist, maxDist);
        if (p != null) {
            double k = nd / d;
            target.setX(p.getX() + (target.getX() - p.getX()) * k);
            target.setY(p.getY() + (target.getY() - p.getY()) * k);
            target.setZ(p.getZ() + (target.getZ() - p.getZ()) * k);
        }
        if (in && d <= minDist * 1.01) move(rayDirection(sx, sy), Math.max(minDist, extent * 0.02)); // durchs Level fliegen
        dist.setZ(-nd);
        rescaleMarkers();
    }

    /** Blickstrahl der Kamera durch einen Bildschirmpunkt der 3D-Fläche (JavaFX-Weltkoordinaten). */
    private Point3D rayDirection(double sx, double sy) {
        double h = Math.max(1, sub.getHeight()), w = Math.max(1, sub.getWidth());
        double t = Math.tan(Math.toRadians(cam.getFieldOfView() / 2)); // Sichtfeld ist vertikal
        Point3D local = new Point3D((sx - w / 2) / (h / 2) * t, (sy - h / 2) / (h / 2) * t, 1);
        return yaw.deltaTransform(pitch.deltaTransform(local)).normalize();
    }

    /** Punkt unter der Maus: getroffenes Netz oder Marker, sonst Schnitt des Blickstrahls mit der Ebene des Drehpunkts. */
    private Point3D pointUnderCursor(double sx, double sy, PickResult pr) {
        Point3D hit = pickedPoint(pr); // Marker
        if (hit != null) return hit;
        hit = navRaycast(sx, sy); // Boden (Wege-Netz)
        if (hit != null) return hit;
        Point3D eye = eye(), dir = rayDirection(sx, sy);
        if (Math.abs(dir.getY()) < 1e-4) return null;
        double s = (target.getY() - eye.getY()) / dir.getY();
        return s > 0 ? eye.add(dir.multiply(s)) : null;
    }

    // ---------- Flugmodus: Mausfang (Pointer Lock) ----------

    /** Mauszeiger ausblenden und nach jeder Bewegung in die Mitte zurücksetzen, so dreht sich der Blick endlos. */
    private void lockPointer() {
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c == null) return;
        lockCenter = c;
        pointerLocked = true;
        sub.setCursor(Cursor.NONE);
        robot.mouseMove(c.getX(), c.getY());
        updateModeTexts();
    }

    private void unlockPointer() {
        pointerLocked = false;
        sub.setCursor(Cursor.DEFAULT);
        updateModeTexts();
    }

    private void look(double screenX, double screenY) {
        if (lockCenter == null) return;
        double dx = screenX - lockCenter.getX(), dy = screenY - lockCenter.getY();
        if (dx == 0 && dy == 0) return; // vom Zurücksetzen ausgelöst
        yaw.setAngle(yaw.getAngle() + dx * 0.15);
        pitch.setAngle(clamp(pitch.getAngle() - dy * 0.15, -89, 89));
        robot.mouseMove(lockCenter.getX(), lockCenter.getY());
    }

    // ---------- Tastatur ----------

    private void installKeys() {
        sub.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case F -> setFly(!fly);
                case H -> {
                    helpBtn.setSelected(!helpBtn.isSelected());
                    help.setVisible(helpBtn.isSelected());
                }
                case N -> {
                    netBtn.setSelected(!netBtn.isSelected());
                    navGroup.setVisible(netBtn.isSelected() || model == null);
                }
                case T -> {
                    texBtn.setSelected(!texBtn.isSelected());
                    applyMaterials();
                }
                case PAGE_UP, ADD, PLUS -> cutSlider.setValue(cutSlider.getValue() + 2);
                case PAGE_DOWN, SUBTRACT, MINUS -> cutSlider.setValue(cutSlider.getValue() - 2);
                case ESCAPE -> {
                    if (pointerLocked) unlockPointer();
                    else if (fly) setFly(false);
                }
                default -> keys.add(e.getCode());
            }
            e.consume();
        });
        sub.setOnKeyReleased(e -> keys.remove(e.getCode()));
        sub.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                keys.clear();
                if (pointerLocked) unlockPointer();
            }
        });
        new AnimationTimer() { // Flugmodus: fliegen; Orbit: Drehpunkt in der Ebene verschieben
            long last;

            @Override
            public void handle(long now) {
                double dt = last == 0 ? 0 : Math.min(0.05, (now - last) / 1e9);
                last = now;
                if (keys.isEmpty() || dt == 0) return;
                double speed = (keys.contains(KeyCode.SHIFT) ? 3 : 1) * (fly ? flySpeed : Math.max(minDist * 4, -dist.getZ() * 0.8)) * dt;
                Point3D fwd = fly ? forward() : flat(forward()), right = yaw.deltaTransform(new Point3D(1, 0, 0));
                if (keys.contains(KeyCode.W) || keys.contains(KeyCode.UP)) move(fwd, speed);
                if (keys.contains(KeyCode.S) || keys.contains(KeyCode.DOWN)) move(fwd, -speed);
                if (keys.contains(KeyCode.D) || keys.contains(KeyCode.RIGHT)) move(right, speed);
                if (keys.contains(KeyCode.A) || keys.contains(KeyCode.LEFT)) move(right, -speed);
                if (keys.contains(KeyCode.E) || keys.contains(KeyCode.SPACE)) move(new Point3D(0, -1, 0), speed); // JavaFX: y nach unten
                if (keys.contains(KeyCode.Q) || keys.contains(KeyCode.CONTROL)) move(new Point3D(0, 1, 0), speed);
            }
        }.start();
    }

    // ---------- Modi und Hilfe ----------

    /**
     * Flugmodus: Kamera steht an der aktuellen Augenposition (Abstand 0) und dreht sich um sich selbst.
     * Zurück zum Orbit: Drehpunkt vor die Kamera legen, die Ansicht bleibt gleich.
     */
    private void setFly(boolean on) {
        if (on == fly) return;
        Point3D eye = eye();
        fly = on;
        flyBtn.setSelected(on);
        if (on) {
            setTarget(eye);
            dist.setZ(0);
        } else {
            if (pointerLocked) unlockPointer();
            pitch.setAngle(clamp(pitch.getAngle(), -89.5, 5));
            double d = clamp(extent * 0.15, minDist * 4, maxDist);
            setTarget(eye.add(forward().multiply(d)));
            dist.setZ(-d);
        }
        keys.clear();
        sub.requestFocus();
        updateModeTexts();
        rescaleMarkers();
    }

    private void updateModeTexts() {
        help.getChildren().clear();
        if (fly) {
            modeLabel.setText(pointerLocked ? "Flugmodus · Maus gefangen (Esc gibt frei)" : "Flugmodus · in die Ansicht klicken, um die Maus zu fangen");
            addHelp("Flugmodus", "WASD bewegen · Q/E runter/hoch · Shift schneller",
                    "Klick fängt die Maus: Maus bewegen = umsehen · Esc gibt sie frei (zweimal Esc = Orbit)",
                    String.format(Locale.ROOT, "Mausrad = Fluggeschwindigkeit (jetzt %.0f m/s)", flySpeed),
                    "F = zurück zum Orbit · H = Hilfe aus/ein");
        } else {
            modeLabel.setText("Orbit");
            addHelp("Orbit", "Links ziehen = drehen · Rechts ziehen (oder Shift) = verschieben",
                    "Mausrad = zum Mauszeiger zoomen · Doppelklick = Punkt/Marker fokussieren",
                    "WASD = Drehpunkt verschieben · Marker anklicken = Details",
                    "Schnitthöhe: Regler oder Bild auf/ab · T = Texturen · N = Wege-Netz",
                    "F = Flugmodus · H = Hilfe aus/ein");
        }
    }

    private void addHelp(String head, String... lines) {
        Label h = new Label(head);
        h.getStyleClass().add("map3d-help-head");
        help.getChildren().add(h);
        for (String l : lines) help.getChildren().add(new Label(l));
    }

    // ---------- Kamera ----------

    public Region node() {
        return root;
    }

    public MapGeo.Map3D current() {
        return map;
    }

    /**
     * Neue Karte anzeigen. Das Wege-Netz erscheint sofort, das Modell wird samt Texturen im Hintergrund geladen
     * und dann eingesetzt (große Gebiete haben über eine Million Dreiecke und über hundert Texturen).
     */
    public void show(MapGeo.Map3D m, String name, java.util.function.Supplier<ds2ach.data.MapModel> modelLoader) {
        if (m == map) return;
        map = m;
        mapName = name;
        content.getChildren().clear();
        modelMeshes.clear();
        modelFaces.clear();
        modelViews.clear();
        texMaterials.clear();
        model = null;
        info.setVisible(false);
        if (m == null || m.netz() == null || m.netz().punkte().length == 0) {
            title.setText(name + "  ·  keine 3D-Daten (setup.bat ausführen)");
            return;
        }
        navGroup = buildMesh(m.netz());
        content.getChildren().add(navGroup);
        title.setText(name + "  ·  Modell wird geladen …");
        applyBounds();
        fit();
        Platform.runLater(this::fit); // nochmal, sobald die 3D-Fläche ihre endgültige Größe hat
        long token = ++loadToken;
        Thread t = new Thread(() -> {
            ds2ach.data.MapModel mm = modelLoader == null ? null : modelLoader.get();
            List<ds2ach.data.MapModel.Pixels> pixels = new ArrayList<>(); // DXT dekodieren, solange wir noch im Hintergrund sind
            if (mm != null) for (ds2ach.data.MapModel.Texture tx : mm.textures) {
                if (token != loadToken) return;
                pixels.add(tx.decode(512));
            }
            Platform.runLater(() -> {
                if (token != loadToken) return; // inzwischen andere Karte gewählt
                if (mm == null) {
                    title.setText(name + "  ·  Wege-Netz (kein Modell, setup.bat ausführen)");
                    return;
                }
                model = mm;
                Node mv = buildModel(mm, pixels);
                content.getChildren().add(0, mv);
                applyCut();
                navGroup.setVisible(netBtn.isSelected());
            });
        }, "modell-laden");
        t.setDaemon(true);
        t.start();
    }

    /** Schnitthöhe anwenden: je Textur-Gruppe nur Dreiecke bis zur eingestellten Höhe über dem Weg zeigen (Listen sind danach sortiert). */
    private void applyCut() {
        if (model == null || modelMeshes.isEmpty()) return;
        double cut = cutSlider.getValue();
        boolean all = cut >= cutSlider.getMax() - 0.01;
        int n = 0;
        for (int i = 0; i < modelMeshes.size(); i++) {
            ds2ach.data.MapModel.Group g = model.groups.get(i);
            int k = all ? g.triangleCount() : g.countUpTo(cut);
            modelMeshes.get(i).getFaces().setAll(modelFaces.get(i), 0, k * 6);
            modelViews.get(i).setVisible(k > 0);
            n += k;
        }
        cutLabel.setText(all ? "Schnitt: aus" : String.format(Locale.ROOT, "Schnitt: %.0f m", cut));
        title.setText(mapName + "  ·  Modell");
        Tooltip.install(title, new Tooltip(String.format(Locale.GERMANY, "%,d von %,d Dreiecken sichtbar, %d Texturen",
                n, model.triangleCount(), model.textures.size())));
    }

    /** Je Textur-Gruppe ein Mesh mit eigener Farbtextur; ohne gefundene Textur oder mit T ausgeschaltet: Tonfarbe. */
    private Node buildModel(ds2ach.data.MapModel mm, List<ds2ach.data.MapModel.Pixels> pixels) {
        for (int i = 0; i < mm.textures.size(); i++) {
            ds2ach.data.MapModel.Pixels px = pixels.get(i);
            WritableImage img = new WritableImage(px.width(), px.height());
            img.getPixelWriter().setPixels(0, 0, px.width(), px.height(), javafx.scene.image.PixelFormat.getIntArgbInstance(),
                    px.argb(), 0, px.width());
            PhongMaterial mat = new PhongMaterial(Color.WHITE);
            mat.setDiffuseMap(img);
            mat.setSpecularColor(Color.rgb(20, 18, 15));
            texMaterials.add(mat);
        }
        Group g3 = new Group();
        List<Node> opaque = new ArrayList<>(), transparent = new ArrayList<>();
        for (ds2ach.data.MapModel.Group g : mm.groups) {
            float[] v = g.verts;
            float[] pts = new float[v.length];
            for (int i = 0; i < v.length; i += 3) {
                pts[i] = v[i];
                pts[i + 1] = -v[i + 1];
                pts[i + 2] = v[i + 2];
            }
            int[] t = g.tris, faces = new int[t.length * 2];
            for (int i = 0; i < t.length; i++) {
                faces[2 * i] = t[i];
                faces[2 * i + 1] = t[i];
            }
            TriangleMesh mesh = new TriangleMesh();
            mesh.getPoints().setAll(pts);
            mesh.getTexCoords().setAll(g.uvs);
            MeshView mv = new MeshView(mesh);
            mv.setCullFace(CullFace.NONE);
            mv.setMouseTransparent(true); // Treffer berechnen wir selbst (navRaycast), JavaFX-Picking wäre bei 1 Mio. Dreiecken zu langsam
            modelMeshes.add(mesh);
            modelFaces.add(faces);
            modelViews.add(mv);
            (g.alpha ? transparent : opaque).add(mv);
        }
        // Durchsichtige Gruppen (Blätter, Gitter) zuletzt zeichnen, damit dahinter Liegendes schon da ist
        g3.getChildren().addAll(opaque);
        g3.getChildren().addAll(transparent);
        applyMaterials();
        return g3;
    }

    private void applyMaterials() {
        if (model == null) return;
        boolean tex = texBtn.isSelected();
        for (int i = 0; i < modelViews.size(); i++) {
            int t = model.groups.get(i).texture;
            modelViews.get(i).setMaterial(tex && t >= 0 && t < texMaterials.size() ? texMaterials.get(t) : clay);
        }
    }

    /** Tonfarbe: warmer Sand, die Form kommt über das Licht. */
    private static PhongMaterial clayMaterial() {
        PhongMaterial m = new PhongMaterial(Color.rgb(196, 172, 138));
        m.setSpecularColor(Color.rgb(25, 22, 18));
        return m;
    }

    /**
     * Strahl durch den Bildschirmpunkt gegen das Wege-Netz schneiden (Möller-Trumbore). Das Netz ist klein
     * (wenige tausend Dreiecke), daher schnell genug für jedes Mausrad-Ereignis.
     */
    private Point3D navRaycast(double sx, double sy) {
        if (map == null || map.netz() == null) return null;
        Point3D o = eye(), d = rayDirection(sx, sy);
        double[] p = map.netz().punkte();
        int[] t = map.netz().dreiecke();
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 2 < t.length; i += 3) {
            double ax = p[3 * t[i]], ay = -p[3 * t[i] + 1], az = p[3 * t[i] + 2];
            double e1x = p[3 * t[i + 1]] - ax, e1y = -p[3 * t[i + 1] + 1] - ay, e1z = p[3 * t[i + 1] + 2] - az;
            double e2x = p[3 * t[i + 2]] - ax, e2y = -p[3 * t[i + 2] + 1] - ay, e2z = p[3 * t[i + 2] + 2] - az;
            double px = d.getY() * e2z - d.getZ() * e2y, py = d.getZ() * e2x - d.getX() * e2z, pz = d.getX() * e2y - d.getY() * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-9) continue;
            double inv = 1 / det, tx = o.getX() - ax, ty = o.getY() - ay, tz = o.getZ() - az;
            double u = (tx * px + ty * py + tz * pz) * inv;
            if (u < 0 || u > 1) continue;
            double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            double w = (d.getX() * qx + d.getY() * qy + d.getZ() * qz) * inv;
            if (w < 0 || u + w > 1) continue;
            double dist2 = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (dist2 > 0 && dist2 < best) best = dist2;
        }
        return best == Double.MAX_VALUE ? null : o.add(d.multiply(best));
    }

    /** Marker neu setzen: wird bei jedem Spielstand bzw. jeder Änderung aufgerufen. */
    public void update(CharacterSlot c, Items items, Set<Long> manual, Set<Integer> achItems) {
        markers.getChildren().clear();
        scaled.clear();
        player = null;
        playerNode = null;
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
            playerNode = playerMarker(p[3]);
            place(playerNode, p[0], p[1], p[2], c.name() + " (Position beim letzten Speichern)");
        }
        rescaleMarkers();
    }

    // ---------- Selbstprüfung (Testhilfe, Startparameter --input-check) ----------

    /** Elemente über der 3D-Fläche, die an einem der 9 Prüfpunkte Mausereignisse abfangen würden (Knöpfe ausgenommen). */
    public List<String> mouseBlockers() {
        List<String> out = new ArrayList<>();
        Node overlay = root.getChildren().get(1);
        for (double fx : new double[]{0.2, 0.5, 0.8})
            for (double fy : new double[]{0.25, 0.5, 0.85}) {
                Point2D p = root.localToScene(root.getWidth() * fx, root.getHeight() * fy);
                collectBlockers(overlay, p, out);
            }
        return out;
    }

    private static void collectBlockers(Node n, Point2D scenePt, List<String> out) {
        if (!n.isVisible() || n.isMouseTransparent()) return;
        if (n instanceof javafx.scene.control.ButtonBase) return; // echte Bedienelemente dürfen klicken
        boolean catches = n.isPickOnBounds() || (n instanceof Region r && r.getBackground() != null && !r.getBackground().isEmpty());
        if (catches && n.localToScene(n.getBoundsInLocal()).contains(scenePt)) {
            String s = n.getClass().getSimpleName() + n.getStyleClass();
            if (!out.contains(s)) out.add(s);
        }
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collectBlockers(c, scenePt, out);
    }

    /** Schickt Maus-, Scroll- und Tastenereignisse an die 3D-Fläche und meldet, was sich an der Kamera ändert. */
    public String inputSelfTest() {
        StringBuilder r = new StringBuilder();
        double cx = sub.getWidth() / 2, cy = sub.getHeight() / 2;
        Point2D s = sub.localToScreen(cx, cy);
        double d0 = -dist.getZ(), y0 = yaw.getAngle(), p0 = pitch.getAngle();
        Point3D t0 = new Point3D(target.getX(), target.getY(), target.getZ());
        sub.fireEvent(new ScrollEvent(ScrollEvent.SCROLL, cx, cy, s.getX(), s.getY(), false, false, false, false, false, false,
                0, 40, 0, 40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
        r.append(String.format(Locale.ROOT, "Mausrad hinein: Abstand %.1f -> %.1f m (Grenzen %.1f bis %.0f m)%n", d0, -dist.getZ(), minDist, maxDist));
        Point3D t1 = new Point3D(target.getX(), target.getY(), target.getZ());
        fireMouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, cx, cy, s);
        fireMouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, cx + 80, cy + 30, s.add(80, 30));
        r.append(String.format(Locale.ROOT, "Links ziehen: Drehung %.1f° -> %.1f°, Neigung %.1f° -> %.1f°%n", y0, yaw.getAngle(), p0, pitch.getAngle()));
        Point3D t2 = new Point3D(target.getX(), target.getY(), target.getZ());
        fireMouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, cx, cy, s);
        fireMouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, cx + 120, cy, s.add(120, 0));
        r.append(String.format(Locale.ROOT, "Rechts ziehen: Drehpunkt %.1f m verschoben (Zoom zum Cursor hatte ihn %.1f m verschoben)%n",
                t2.distance(target.getX(), target.getY(), target.getZ()), t0.distance(t1)));
        setFly(true);
        Point3D e0 = eye();
        keys.add(KeyCode.W);
        long start = System.nanoTime();
        r.append(String.format(Locale.ROOT, "Flugmodus an, Geschwindigkeit %.1f m/s, W gedrückt ...%n", flySpeed));
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(Duration.millis(500));
        wait.setOnFinished(ev -> {
            keys.clear();
            System.out.printf(Locale.ROOT, "W %.2f s: Kamera %.1f m geflogen%n", (System.nanoTime() - start) / 1e9, e0.distance(eye()));
            setFly(false);
        });
        wait.play();
        return r.toString();
    }

    private void fireMouse(javafx.event.EventType<MouseEvent> type, MouseButton b, double x, double y, Point2D screen) {
        sub.fireEvent(new MouseEvent(type, x, y, screen.getX(), screen.getY(), b, 1, false, false, false, false,
                b == MouseButton.PRIMARY, false, b == MouseButton.SECONDARY, false, false, true, null));
    }

    /** Kamerabereich aus der Bounding Box: kleinster/größter Abstand und Fluggeschwindigkeit passend zur Gebietsgröße. */
    private void applyBounds() {
        minDist = Math.max(0.8, extent * 0.004);
        maxDist = extent * 3;
        flySpeed = clamp(extent / 15, 4, 80);
        cam.setNearClip(Math.max(0.1, minDist * 0.1));
        cam.setFarClip(extent * 12);
    }

    /** Ganzes Gebiet ins Bild: Abstand so, dass die Bounding Box ins Sichtfeld passt (Höhe und Breite). */
    public void fit() {
        setFly(false);
        setTarget(new Point3D(center[0], -center[1], center[2]));
        yaw.setAngle(30);
        pitch.setAngle(-55);
        double h = Math.max(1, sub.getHeight()), w = Math.max(1, sub.getWidth());
        double vFov = Math.toRadians(cam.getFieldOfView()), hFov = 2 * Math.atan(Math.tan(vFov / 2) * w / h);
        double radius = Math.hypot(extent / 2, height / 2) * 1.05; // Kugel um die Box
        double d = radius / Math.sin(Math.min(vFov, hFov) / 2);
        dist.setZ(-clamp(d, minDist, maxDist));
        rescaleMarkers();
    }

    private void setTarget(Point3D p) {
        target.setX(p.getX());
        target.setY(p.getY());
        target.setZ(p.getZ());
    }

    /** Orbit auf einen Punkt: Drehpunkt dorthin, Abstand d. */
    private void focusOn(Point3D p, double d) {
        setFly(false);
        setTarget(p);
        dist.setZ(-clamp(d, minDist, maxDist));
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
        updatePlayerVisibility();
    }

    /** Steht die Kamera im Spieler-Marker (Ansicht "Vom Spieler aus"), verdeckt er sonst das halbe Bild. */
    private void updatePlayerVisibility() {
        if (playerNode == null || player == null) return;
        double r = 3 * playerNode.getScaleY();
        playerNode.setVisible(eye().distance(player[0], -(player[1] + 1), player[2]) > r);
    }

    /** Augenposition = Drehpunkt + Abstand entgegen der Blickrichtung. */
    private Point3D eye() {
        return new Point3D(target.getX(), target.getY(), target.getZ()).add(forward().multiply(dist.getZ()));
    }

    /** Flugmodus in Augenhöhe an der gespeicherten Spielerposition, Blick in seine Richtung. */
    public void playerView() {
        if (player == null) {
            showInfo("Der Spieler ist nicht auf dieser Karte.");
            return;
        }
        setFly(true);
        setTarget(new Point3D(player[0], -(player[1] + 1.7), player[2]));
        yaw.setAngle(-Math.toDegrees(playerYaw));
        pitch.setAngle(-8);
        rescaleMarkers();
    }

    public void focusPlayer() {
        if (player == null) {
            showInfo("Der Spieler ist nicht auf dieser Karte.");
            return;
        }
        focusOn(new Point3D(player[0], -player[1], player[2]), Math.max(minDist * 10, extent * 0.08));
    }

    private void showInfo(String text) {
        info.setText(text);
        info.setVisible(true);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Getroffener Punkt in Weltkoordinaten (Netz oder Marker), null wenn ins Leere gezeigt. */
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

    /** Marker wachsen beim Herauszoomen mit, damit sie sichtbar bleiben. */
    private void rescaleMarkers() {
        double d = fly ? flySpeed * 2 : -dist.getZ();
        double s = clamp(d / 45, 1, 14); // im Modell müssen Marker deutlich größer sein als auf dem Wege-Netz
        for (Node n : scaled) {
            n.setScaleX(s);
            n.setScaleY(s);
            n.setScaleZ(s);
        }
        updatePlayerVisibility();
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
        height = Math.max(5, maxY - minY);

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
        mv.setMouseTransparent(true); // Treffer per navRaycast
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
            if (e.getButton() != MouseButton.PRIMARY || fly) return;
            if (e.getClickCount() == 2) focusOn(new Point3D(x, -y, z), Math.max(minDist * 6, extent * 0.04)); // Marker fokussieren
            else showInfo(text);
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
}
