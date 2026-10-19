# DS2 Achievement-Checkliste

Checkliste für einen Durchgang in **Dark Souls II: Scholar of the First Sin** (Steam). Der Fortschritt kommt aus deinem
**Spielstand**, nicht aus deinen Steam-Achievements. Die App liest nur. Sie verändert weder den Spielstand noch den
Spielspeicher.

Stand: **Schritt 1 (Grundgerüst)**. Guides (Schritt 2), Warnungen vor Points of no Return (Schritt 3) und der
Item-Geber (Schritt 4) folgen.

## Starten

1. Java 21 oder neuer muss installiert sein (`java -version`).
2. Doppelklick auf `start.bat`.

Beim ersten Start baut `build.bat` die App automatisch. Dafür wird Maven gesucht: im PATH, in der Umgebungsvariable
`MVN` oder im Maven-Wrapper-Cache unter `%USERPROFILE%\.m2\wrapper`. Wenn du Code oder Daten änderst, `build.bat` erneut
ausführen. Für Änderungen an der JSON-Datei reicht ein Neustart der App.

## Spielstand

- Ort: `%APPDATA%\DarkSoulsII\<SteamID>\DS2SOFS0000.sl2`. Die App findet die Datei selbst. Bei mehreren Steam-IDs
  nimmt sie die zuletzt geänderte.
- Die App prüft alle 2 Sekunden, ob sich die Datei geändert hat, und liest sie dann komplett in den Speicher. Die
  Originaldatei wird nie zum Schreiben geöffnet.
- **Backups** liegen in `backups/`:
  - `erstes-backup_*.sl2` beim allerersten Start, wird nie gelöscht
  - `backup_*.sl2` höchstens einmal pro Tag, die neuesten 10 bleiben erhalten
- **Slot:** „Automatisch“ zeigt den Slot, dessen Daten sich bei der letzten Speicherung geändert haben, also den
  Charakter, den du gerade spielst. Über die Auswahl oben kannst du einen Slot fest einstellen.

## Bedienung

- **Häkchen:** Ein Achievement von Hand als erledigt oder offen markieren. Das überschreibt die automatische
  Erkennung. Steht deine Markierung auf demselben Stand wie die Erkennung, wird sie wieder entfernt.
- **Ausblenden:** Zum Beispiel für Achievements, die du schon hast. Sie zählen dann nicht mehr zum Fortschritt.
  „Ausgeblendete zeigen“ holt sie zurück.
- **Filter:** Alle / Offen / Erledigt / Verpassbar.
- **Immer im Vordergrund:** Praktisch auf dem zweiten Monitor.
- Deine Einstellungen stehen in `state.json`, getrennt pro Charakter (Slot und Name).

## Daten selbst korrigieren

- `data/achievements.json` enthält alle 38 Achievements mit Bedingung, Sicherheit und dem Merkmal verpassbar.
  Die erlaubten Bedingungstypen stehen oben in der Datei.
- `data/gamedata.json` enthält Gebietsnamen, Bossnamen, Zauberlisten und Item-IDs.

Mit `"sicherheit": "unsicher"` markierte Erkennungen zeigt die App mit „(?, unbestätigt)“ an.

## Was erkannt wird und was nicht

| Daten | Status |
|---|---|
| Charaktername, Slot, Seelenlevel, Seelen | zuverlässig |
| Inventar (Items, Ringe, Zauber, Schlüsselitems) | zuverlässig |
| Aktuelles Gebiet | zuverlässig. Die Namen einiger Gebiete sind noch unbestätigt (mit „(?)“ markiert). |
| NG-Zyklus | Wert wird gelesen, die Bedeutung ist noch unbestätigt |
| Besiegte Bosse | Kandidat, noch unbestätigt |
| Letzter Boss | nur Bosse, die besiegt wurden, während die App lief |
| Ur-Leuchtfeuer, Covenants, NPC-Quests, Gesten, Tode, Waffenstufe | noch nicht erkannt, bitte selbst abhaken |

## Bekannte Einschränkungen

- Das Spielstand-Format ist nicht offiziell dokumentiert. Grundlage sind Open-Source-Projekte und eigene Vergleiche.
  Nach einem Spiel-Update kann sich das Format ändern.
- Die Zauberlisten für „Master of …“ stammen aus einem Save-Editor und sind evtl. unvollständig (DLC-Zauber prüfen).
- Achievement-Namen sind die offiziellen englischen Namen. Die deutschen Beschreibungen sind eigene Übersetzungen.
- Die DLCs haben keine eigenen Achievements.

## Erkennung verbessern: probe/SaveProbe.java

Werkzeug, mit dem neue Erkennungen gefunden werden (braucht nur Java):

```bat
cd probe
java SaveProbe.java snap
java SaveProbe.java diff snapshots\A.sl2 snapshots\B.sl2
```

- `snap` kopiert den aktuellen Spielstand nach `probe/snapshots/` und zeigt, was lesbar ist.
- `diff` zeigt, was sich zwischen zwei Kopien geändert hat. Leg eine Kopie direkt vor und eine direkt nach einem
  Ereignis an (Boss, Leuchtfeuer, Covenant beitreten, NPC-Gespräch) und notiere, was passiert ist.

## Quellen

- Entschlüsselung (AES-128-CBC, MD5 pro Eintrag): [mi5hmash/SL2Bonfire](https://github.com/mi5hmash/SL2Bonfire)
- Offsets für Werte und Inventar, Item-IDs (MIT): [alfizari/Dark-Souls-2-Save-Editor-PS4-PC](https://github.com/alfizari/Dark-Souls-2-Save-Editor-PS4-PC)
- Reihenfolge der Boss-Zähler: [FrankvdStam/SoulSplitter](https://github.com/FrankvdStam/SoulSplitter)
