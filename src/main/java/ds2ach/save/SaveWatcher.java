package ds2ach.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Prueft alle 2 Sekunden, ob sich der Spielstand geändert hat, und liest ihn dann neu ein.
 * Gelesen wird erst, wenn Groesse und Zeitstempel zwei Prüfungen lang gleich bleiben (Spiel ist fertig mit Schreiben).
 * Schlägt das Lesen fehl (z. B. Prüfsumme falsch, weil das Spiel gerade schreibt), wird es beim nächsten Mal erneut versucht.
 */
public final class SaveWatcher implements AutoCloseable {
    public record Update(Path file, SaveFile save, long modified) {}

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "save-watcher");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<Update> onUpdate;
    private final Consumer<String> onError;
    private long seenModified = -1, seenSize = -1, loadedModified = -1, loadedSize = -1;

    public SaveWatcher(Consumer<Update> onUpdate, Consumer<String> onError) {
        this.onUpdate = onUpdate;
        this.onError = onError;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
    }

    /** Neuestes DS2SOFS0000.sl2 unter %APPDATA%\DarkSoulsII\<SteamID>\. */
    public static Optional<Path> locate() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return Optional.empty();
        Path root = Path.of(appData, "DarkSoulsII");
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> s = Files.walk(root, 2)) {
            return s.filter(p -> p.getFileName().toString().equalsIgnoreCase("DS2SOFS0000.sl2"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void tick() {
        try {
            Optional<Path> p = locate();
            if (p.isEmpty()) {
                onError.accept("Kein Spielstand gefunden unter %APPDATA%\\DarkSoulsII");
                return;
            }
            Path f = p.get();
            long mod = Files.getLastModifiedTime(f).toMillis(), size = Files.size(f);
            boolean stable = mod == seenModified && size == seenSize;
            seenModified = mod;
            seenSize = size;
            if (!stable || (mod == loadedModified && size == loadedSize)) return;
            // Nur lesen: komplette Datei in den Speicher, die Originaldatei wird nie zum Schreiben geöffnet
            SaveFile save = SaveFile.parse(Files.readAllBytes(f));
            loadedModified = mod;
            loadedSize = size;
            onUpdate.accept(new Update(f, save, mod));
        } catch (Exception e) {
            onError.accept("Spielstand konnte nicht gelesen werden: " + e.getMessage() + " (neuer Versuch gleich)");
        }
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
