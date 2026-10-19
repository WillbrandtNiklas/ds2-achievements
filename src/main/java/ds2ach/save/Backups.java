package ds2ach.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sicherungen des Spielstands in backups/:
 * - erstes-backup_*.sl2 beim allerersten Start (wird nie gelöscht)
 * - backup_*.sl2 höchstens einmal pro Tag, die neuesten 10 bleiben erhalten
 */
public final class Backups {
    static final int KEEP = 10;
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;

    public Backups(Path dir) {
        this.dir = dir;
    }

    /** Legt bei Bedarf eine Sicherung an. Gibt den neuen Tag zurück, falls gesichert wurde, sonst null. */
    public String backupIfDue(Path save, String lastBackupDay) throws IOException {
        Files.createDirectories(dir);
        String today = LocalDate.now().toString();
        boolean first;
        try (Stream<Path> s = Files.list(dir)) {
            first = s.noneMatch(p -> p.getFileName().toString().startsWith("erstes-backup_"));
        }
        if (!first && today.equals(lastBackupDay)) return null;
        String name = (first ? "erstes-backup_" : "backup_") + LocalDateTime.now().format(TS) + ".sl2";
        Files.copy(save, dir.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
        prune();
        return today;
    }

    private void prune() throws IOException {
        List<Path> daily;
        try (Stream<Path> s = Files.list(dir)) {
            daily = s.filter(p -> p.getFileName().toString().startsWith("backup_"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (int i = KEEP; i < daily.size(); i++) Files.deleteIfExists(daily.get(i));
    }
}
