package ds2ach.ui;

import ds2ach.data.AchievementDb;
import ds2ach.data.AchievementDb.Achievement;
import ds2ach.logic.Evaluator;
import ds2ach.save.Backups;
import ds2ach.save.CharacterSlot;
import ds2ach.save.SaveWatcher;
import ds2ach.state.UserState;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class App extends Application {
    enum Filter { ALLE, OFFEN, ERLEDIGT, VERPASSBAR }

    private Path appDir;
    private AchievementDb db;
    private Evaluator evaluator;
    private UserState state;
    private Backups backups;
    private SaveWatcher watcher;

    private List<CharacterSlot> slots = List.of();
    private CharacterSlot current;
    private Filter filter = Filter.ALLE;
    private boolean updatingCombo;

    private final ComboBox<String> slotBox = new ComboBox<>();
    private final Label charLabel = new Label("–"), ngLabel = new Label("–"), areaLabel = new Label("–"),
            bossLabel = new Label("–"), statusLabel = new Label("Suche Spielstand …"), progressLabel = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final VBox list = new VBox(6);

    @Override
    public void start(Stage stage) {
        appDir = findAppDir();
        try {
            db = AchievementDb.load(appDir.resolve("data"));
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "data/achievements.json oder data/gamedata.json konnte nicht gelesen werden:\n"
                    + e.getMessage() + "\n\nOrdner: " + appDir.resolve("data")).showAndWait();
            Platform.exit();
            return;
        }
        evaluator = new Evaluator(db.gameData);
        state = UserState.load(appDir.resolve("state.json"));
        backups = new Backups(appDir.resolve("backups"));

        stage.setTitle("DS2 Achievement-Checkliste");
        stage.setScene(new Scene(buildUi(stage), 560, 900));
        stage.getScene().getStylesheets().add(Objects.requireNonNull(App.class.getResource("style.css")).toExternalForm());
        stage.setAlwaysOnTop(state.alwaysOnTop);
        stage.show();
        render();

        watcher = new SaveWatcher(u -> Platform.runLater(() -> onSave(u)),
                msg -> Platform.runLater(() -> statusLabel.setText(msg)));
        watcher.start();

        // Testhilfe: --snapshot=datei.png speichert nach 6 s ein Bild des Fensters und beendet die App
        String snap = getParameters().getNamed().get("snapshot");
        if (snap != null) {
            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(6));
            t.setOnFinished(e -> {
                writePng(stage.getScene().snapshot(null), Path.of(snap));
                Platform.exit();
            });
            t.play();
        }
    }

    private static void writePng(javafx.scene.image.WritableImage img, Path out) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
        try {
            javax.imageio.ImageIO.write(bi, "png", out.toFile());
        } catch (java.io.IOException ex) {
            System.err.println("Snapshot fehlgeschlagen: " + ex.getMessage());
        }
    }

    @Override
    public void stop() {
        if (watcher != null) watcher.close();
        if (state != null) state.save();
    }

    // ---------- Aufbau ----------

    private Parent buildUi(Stage stage) {
        slotBox.setMaxWidth(Double.MAX_VALUE);
        slotBox.setOnAction(e -> {
            if (updatingCombo) return;
            int i = slotBox.getSelectionModel().getSelectedIndex();
            state.pinnedSlot = i <= 0 ? null : slots.get(i - 1).index();
            state.save();
            pickCurrent();
            render();
        });
        CheckBox onTop = new CheckBox("Immer im Vordergrund");
        onTop.setSelected(state.alwaysOnTop);
        onTop.setOnAction(e -> {
            state.alwaysOnTop = onTop.isSelected();
            stage.setAlwaysOnTop(state.alwaysOnTop);
            state.save();
        });
        HBox top = new HBox(10, new Label("Slot:"), slotBox, onTop);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slotBox, Priority.ALWAYS);

        GridPane info = new GridPane();
        info.setHgap(10);
        info.setVgap(3);
        info.getStyleClass().add("info");
        String[] labels = {"Charakter", "NG-Zyklus", "Gebiet", "Letzter Boss"};
        Label[] values = {charLabel, ngLabel, areaLabel, bossLabel};
        for (int i = 0; i < labels.length; i++) {
            Label l = new Label(labels[i]);
            l.getStyleClass().add("muted");
            info.addRow(i, l, values[i]);
        }

        progress.setMaxWidth(Double.MAX_VALUE);
        ToggleGroup g = new ToggleGroup();
        HBox filters = new HBox(4);
        for (Filter f : Filter.values()) {
            ToggleButton b = new ToggleButton(switch (f) {
                case ALLE -> "Alle"; case OFFEN -> "Offen"; case ERLEDIGT -> "Erledigt"; case VERPASSBAR -> "Verpassbar";
            });
            b.setToggleGroup(g);
            b.setSelected(f == filter);
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true);
                filter = f;
                render();
            });
            filters.getChildren().add(b);
        }
        CheckBox showHidden = new CheckBox("Ausgeblendete zeigen");
        showHidden.setSelected(state.showHidden);
        showHidden.setOnAction(e -> {
            state.showHidden = showHidden.isSelected();
            state.save();
            render();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        filters.getChildren().addAll(spacer, showHidden);
        filters.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        statusLabel.getStyleClass().add("muted");
        statusLabel.setWrapText(true);

        VBox root = new VBox(10, top, info, new VBox(3, progress, progressLabel), filters, scroll, statusLabel);
        root.setPadding(new Insets(12));
        return root;
    }

    // ---------- Spielstand ----------

    private void onSave(SaveWatcher.Update u) {
        slots = u.save().slots();
        // Zuletzt gespeicherter Slot = der, dessen Daten sich seit dem letzten Lesen geändert haben
        for (CharacterSlot s : slots) {
            String old = state.fingerprints.get(s.index());
            if (old != null && !old.equals(s.fingerprint())) state.lastActiveSlot = s.index();
            state.fingerprints.put(s.index(), s.fingerprint());
        }
        if (state.lastActiveSlot == null && !slots.isEmpty()) state.lastActiveSlot = slots.get(slots.size() - 1).index();
        for (CharacterSlot s : slots) trackBosses(s);

        String backupNote = "";
        try {
            String day = backups.backupIfDue(u.file(), state.lastBackupDay);
            if (day != null) {
                state.lastBackupDay = day;
                backupNote = " | Backup angelegt";
            }
        } catch (Exception e) {
            backupNote = " | Backup fehlgeschlagen: " + e.getMessage();
        }
        state.save();

        updatingCombo = true;
        List<String> items = new ArrayList<>();
        items.add("Automatisch (zuletzt gespeicherter Slot)");
        for (CharacterSlot s : slots) items.add(slotKey(s) + "  (SL " + s.level() + ")");
        slotBox.getItems().setAll(items);
        int sel = 0;
        for (int i = 0; i < slots.size(); i++) if (Objects.equals(state.pinnedSlot, slots.get(i).index())) sel = i + 1;
        slotBox.getSelectionModel().select(sel);
        updatingCombo = false;

        pickCurrent();
        String time = DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(u.modified()).atZone(ZoneId.systemDefault()));
        statusLabel.setText("Spielstand gelesen, zuletzt gespeichert " + time + backupNote);
        statusLabel.setTooltip(new Tooltip(u.file().toString()));
        render();
    }

    /** Merkt sich neu besiegte Bosse, um "Letzter Boss" anzeigen zu koennen. */
    private void trackBosses(CharacterSlot s) {
        UserState.CharState cs = state.character(slotKey(s));
        if (cs.knownKills != null) {
            for (var e : s.bossKills().entrySet())
                if (e.getValue() > cs.knownKills.getOrDefault(e.getKey(), 0)) cs.lastBoss = e.getKey();
        }
        cs.knownKills = new HashMap<>(s.bossKills());
    }

    private void pickCurrent() {
        Integer want = state.pinnedSlot != null ? state.pinnedSlot : state.lastActiveSlot;
        current = slots.stream().filter(s -> Objects.equals(s.index(), want)).findFirst()
                .orElse(slots.isEmpty() ? null : slots.get(0));
    }

    static String slotKey(CharacterSlot s) {
        return "Slot " + s.index() + ": " + s.name();
    }

    // ---------- Darstellung ----------

    private void render() {
        list.getChildren().clear();
        if (current == null) {
            charLabel.setText("–");
            ngLabel.setText("–");
            areaLabel.setText("–");
            bossLabel.setText("–");
        } else {
            charLabel.setText(current.name() + "  (Slot " + current.index() + ", SL " + current.level() + ", " + current.souls() + " Seelen)");
            ngLabel.setText(current.ngValue() <= 1 ? "NG (Wert " + current.ngValue() + ", unbestätigt)"
                    : "NG+" + (current.ngValue() - 1) + " (Wert " + current.ngValue() + ", unbestätigt)");
            areaLabel.setText(db.gameData.mapName(current.mapId()));
            String last = state.character(slotKey(current)).lastBoss;
            bossLabel.setText(last != null ? db.gameData.bossName(last) + " (?)"
                    : current.bossKills().size() + " Bosse erkannt (?), letzter seit Start der App: –");
        }

        UserState.CharState cs = current == null ? new UserState.CharState() : state.character(slotKey(current));
        int done = 0, total = 0, hiddenCount = 0;
        for (Achievement a : db.achievements) {
            boolean hidden = cs.hidden.contains(a.id());
            Evaluator.Result r = evaluator.evaluate(a, current);
            boolean isDone = effectiveDone(a, r, cs);
            if (hidden) hiddenCount++;
            else {
                total++;
                if (isDone) done++;
            }
            if (hidden && !state.showHidden) continue;
            boolean show = switch (filter) {
                case ALLE -> true;
                case OFFEN -> !isDone;
                case ERLEDIGT -> isDone;
                case VERPASSBAR -> a.verpassbar() && !isDone;
            };
            if (show) list.getChildren().add(card(a, r, isDone, hidden, cs));
        }
        progress.setProgress(total == 0 ? 0 : (double) done / total);
        progressLabel.setText(done + " / " + total + " erledigt" + (hiddenCount > 0 ? "  (" + hiddenCount + " ausgeblendet)" : ""));
    }

    private static boolean effectiveDone(Achievement a, Evaluator.Result r, UserState.CharState cs) {
        String o = cs.override.get(a.id());
        if (o != null) return o.equals("erledigt");
        return r.status() == Evaluator.Auto.ERLEDIGT;
    }

    private Node card(Achievement a, Evaluator.Result r, boolean isDone, boolean hidden, UserState.CharState cs) {
        CheckBox check = new CheckBox();
        check.setSelected(isDone);
        check.setDisable(current == null);
        check.setOnAction(e -> {
            String want = check.isSelected() ? "erledigt" : "offen";
            boolean autoDone = r.status() == Evaluator.Auto.ERLEDIGT;
            // Override nur speichern, wenn er von der automatischen Erkennung abweicht
            if ((want.equals("erledigt") && autoDone) || (want.equals("offen") && r.status() == Evaluator.Auto.OFFEN))
                cs.override.remove(a.id());
            else cs.override.put(a.id(), want);
            state.save();
            render();
        });

        Label name = new Label(a.name());
        name.getStyleClass().add("ach-name");
        Label badge = new Label(isDone ? "erledigt" : "offen");
        badge.getStyleClass().addAll("badge", isDone ? "badge-done" : "badge-open");
        HBox title = new HBox(8, check, name, badge);
        title.setAlignment(Pos.CENTER_LEFT);
        if (a.verpassbar()) {
            Label m = new Label("verpassbar");
            m.getStyleClass().addAll("badge", "badge-missable");
            title.getChildren().add(m);
        }
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button hide = new Button(hidden ? "Einblenden" : "Ausblenden");
        hide.getStyleClass().add("small");
        hide.setDisable(current == null);
        hide.setOnAction(e -> {
            if (hidden) cs.hidden.remove(a.id());
            else cs.hidden.add(a.id());
            state.save();
            render();
        });
        title.getChildren().addAll(sp, hide);

        Label desc = new Label(a.beschreibung());
        desc.setWrapText(true);

        String source = switch (r.status()) {
            case ERLEDIGT, OFFEN -> "Automatisch" + (a.unsicher() ? " (?, unbestätigt)" : "") + ": " + r.detail();
            case MANUELL -> "Nicht automatisch erkennbar: bitte selbst abhaken.";
        };
        String o = cs.override.get(a.id());
        if (o != null) source += "  |  Von dir als " + o + " markiert.";
        if (a.erkennungHinweis() != null) source += "\n" + a.erkennungHinweis();
        Label src = new Label(source);
        src.setWrapText(true);
        src.getStyleClass().add("muted");

        VBox box = new VBox(4, title, desc, src);
        box.getStyleClass().addAll("card", isDone ? "card-done" : "card-open");
        if (hidden) box.setOpacity(0.5);
        return box;
    }

    private static Path findAppDir() {
        try {
            Path p = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(p) ? p : p.getParent();
            // target/ds2-achievements.jar oder target/classes -> Projektordner
            while (dir != null && !Files.isDirectory(dir.resolve("data"))) dir = dir.getParent();
            if (dir != null) return dir;
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        return Path.of("").toAbsolutePath();
    }
}
