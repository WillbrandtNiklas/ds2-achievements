package ds2ach;

import ds2ach.ui.App;

/** Einstieg ohne JavaFX-Application-Unterklasse, damit das Fat-JAR ohne Modulpfad startet. */
public final class Launcher {
    public static void main(String[] args) {
        javafx.application.Application.launch(App.class, args);
    }
}
