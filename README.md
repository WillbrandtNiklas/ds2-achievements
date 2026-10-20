# DS2 Achievement-Checkliste

Checkliste für einen Durchgang in **Dark Souls II: Scholar of the First Sin** (Steam). Der Fortschritt kommt aus deinem
**Spielstand**, nicht aus deinen Steam-Achievements. Die App liest nur. Sie verändert weder den Spielstand noch den
Spielspeicher.

Stand: **Weltkarte Stufe 3** (3D-Karten mit Spielerposition). Der Item-Geber folgt.

Alle Spielnamen (Gebiete, Bosse, NPCs, Items, Zauber, Bünde) sind englisch wie im Spiel, Erklärungen deutsch.

## Starten

1. Java 21 oder neuer muss installiert sein (`java -version`).
2. Einmalig `setup.bat` ausführen: liest Item-Fundorte und Karten aus deiner Spielinstallation (siehe „Setup“).
3. Doppelklick auf `start.bat`.

Beim ersten Start baut `build.bat` die App automatisch. Dafür wird Maven gesucht: im PATH, in der Umgebungsvariable
`MVN` oder im Maven-Wrapper-Cache unter `%USERPROFILE%\.m2\wrapper`. Wenn du Code oder Daten änderst, `build.bat` erneut
ausführen. Für Änderungen an der JSON-Datei reicht ein Neustart der App.

## Setup (Item-Daten aus deiner Installation)

`setup.bat` läuft einmalig und liest nur:

- Es sucht den Spielordner über die Steam-Bibliotheken. Ein anderer Ort lässt sich als Argument angeben:
  `setup.bat "D:\SteamLibrary\steamapps\common\Dark Souls II Scholar of the First Sin\Game"`.
- Es liest `Game\Param\ItemLotParam2_Other.param`, also alle Item-Fundorte mit Inhalt, Menge und Verstärkung.
  Die Karte steckt in der ID: `10026100` liegt in m10_02 (Things Betwixt).
- Für englische Item-Namen und Ortsangaben lädt es zwei Namenslisten aus dem Open-Source-Projekt
  [Paramdex](https://github.com/soulsmods/Paramdex), auf einen festen Stand gepinnt. Ohne Internet gibt es nur IDs.
- Ergebnis ist `extracted/items.json`. Der Ordner steht in der `.gitignore` und wird nicht committet.
- Ändert sich die Spieldatei (z. B. durch ein Update oder einen Mod), weist die App darauf hin. Dann `setup.bat` neu
  ausführen.

Liegen die Params in deiner Installation nicht als lose Dateien vor, wird die Item-Liste übersprungen.

**Karten (für die 3D-Ansicht):** Danach liest das Setup aus dem Spielarchiv `GameDataEbl.bdt` pro Karte das
Kartenlayout (`.msb`), das Navigationsnetz (`.ngp`) und zwei Params (Objekt-Instanzen, Event-Bereiche):
- Der Archiv-Index (`.bhd`) ist RSA-verschlüsselt. Entschlüsselt wird mit dem öffentlichen Schlüssel
  `GameDataKeyCode.pem` aus dem Spielordner. Die Dateien selbst sind teilweise AES-verschlüsselt, die Schlüssel stehen
  im Index. Das Format ist nachgebaut nach den Open-Source-Projekten UXM und SoulsFormats.
- Es wird nur gelesen, im Spielordner wird nichts entpackt oder verändert.
- Ergebnis sind 28 Dateien `extracted/maps/mAA_BB.json` (zusammen ca. 4 MB) mit begehbarer Fläche, Leuchtfeuern,
  Item-Fundorten und Gebietsübergängen samt Positionen. Das dauert etwa eine Sekunde.

**Kartenmodelle mit Texturen (echte Level-Geometrie):** Zuletzt liest das Setup pro Karte die Kartenmodelle
(`model/map/*.mapbhd/.mapbdt`, FLVER-Modelle) samt Farbtexturen (`model/map/t*.tpfbhd/.tpfbdt`, DXT1/DXT5) und setzt
sie laut Kartenlayout zusammen:
- Weggelassen werden Himmel und ferne Kulisse (Material `NoLit`), Schattenkörper, Leuchteffekte, Gras, Wasser,
  riesige Kulissen-Platten und alles weit weg von begehbarer Fläche. Blätter bleiben drin (mit Transparenz).
- Texturen: nur die Farbtextur je Material, ab der Mipmap-Stufe mit höchstens 512 px. Normal-, Glanz- und
  Lichtkarten fehlen, das Licht in der Ansicht ist einfach gehalten.
- Jedes Dreieck bekommt seine Höhe über dem nächsten Weg (für die Schnitthöhe), gespeichert wird bis 40 m darüber.
- Ergebnis: `extracted/models/mAA_BB.bin.gz`, 28 Dateien, zusammen ca. 510 MB. Je Gebiet sind es 100.000 bis
  2,2 Mio. Dreiecke und bis zu 140 Texturen, das Setup braucht dafür gut eine Minute.

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

## Warnungen (Points of no Return)

Der rote Kasten oben zeigt, was du **jetzt** beachten musst, und die nächsten kommenden Warnungen:

- **⚠ fett:** Gerade relevant. Du bist im passenden Gebiet (z. B. Aldias Festung: Navlaans Hebel nicht ziehen,
  Lucatiel nicht zu früh ansprechen), eine Boss-Seele liegt im Inventar und sollte bei Straid getauscht werden,
  oder der Endboss ist besiegt und vor NG+ ist noch etwas offen.
- **› normal:** Kommt später, sortiert nach grober Story-Reihenfolge, z. B. „Bevor du Boss X besiegst: Lucatiel
  beschwören“. Weitere Einträge lassen sich ausklappen.
- **verpasst (?)** an einem Achievement: Der Point of no Return ist überschritten, z. B. alle Bosse mit Lucatiels
  Zeichen besiegt oder eine Boss-Seele verbraucht. Darunter steht, ob und wie es noch geht (Leuchtfeuer-Asket, NG+).
  Das beruht auf der noch unbestätigten Boss-Erkennung. Stimmt es nicht, einfach abhaken oder ausblenden.

Die Warnungen stehen pro Achievement in `data/achievements.json` unter `warnungen` (Typen `vorBoss`, `seele`,
`gebiet`), die NG+-Infos unter `ngPlus`. Boss-Reihenfolge und Boss-Gebiete stehen in `data/gamedata.json`
(`bossOrder`, `bossMaps`).

Was die App **nicht** erkennen kann: ob ein NPC tot ist. Die meisten verpassbaren Achievements gehen nur verloren,
wenn du den falschen NPC tötest. Ein Leuchtfeuer-Asket belebt keine NPCs wieder.

## Weltkarte

Der Knopf **Weltkarte** öffnet ein eigenes Fenster, gedacht für den zweiten Monitor. Es aktualisiert sich live mit
dem Spielstand, genau wie die Checkliste. Oben schaltest du zwischen **Karte** und **Diagramm** um.

**Karte (Pergament):** Drangleic im Stil der Karte in Majula, prozedural gezeichnet, ohne Spielgrafik.
- Gebiete sind Landflächen mit Geländesymbolen (Wald, Berge, Burg, Turm, Wasser, Höhle). Unbesuchte Gebiete liegen
  unter Nebel, das aktuelle Gebiet leuchtet.
- Unter dem Namen stehen die Leuchtfeuer als Flammen (brennend = entzündet) und die Bosse als Totenköpfe
  (durchgestrichen = besiegt). Darunter „Items x/y“ = wichtige Fundorte eingesammelt / gesamt.
- Wege sind gestrichelte Pfade. Rot mit Schloss = verschlossen, blau gepunktet = Teleport.
- Grave of Saints, The Gutter, Black Gulch und Shulva liegen im Kasten „Unter Majula“.
- Positionen, Größe und Gelände stehen in `data/world.json` (Felder `karte` und `gelaende`) und lassen sich anpassen.

**Diagramm:**
- **Gebiete** sind Kästen, **Verbindungen** Linien. Mausrad zoomt zum Mauszeiger, Ziehen verschiebt, „Ansicht zurücksetzen“ passt alles
  ins Fenster. DLC-Gebiete haben einen gestrichelten Rand und lassen sich ausblenden.
- **Farben:** Gold mit dickem Rand = aktuelles Gebiet, braun = besucht, dunkel = noch nicht besucht.
- In jedem Kasten steht, wie viele **Leuchtfeuer** entzündet und **Bosse** besiegt sind.
- **Rote gestrichelte Linie mit !** = verschlossen. Beim Darüberfahren steht, was fehlt (Item, Boss) und wie es
  aufgeht. Andere Symbole: ↕ Aufzug, ▯ Tür, ✦ Teleport, ≈ Schiff, ↓ Abstieg. Ein Pfeil zeigt eine Einbahn.
- Ein Weg gilt als **offen**, wenn du die Voraussetzung erfüllst (z. B. Schlüssel im Inventar, Boss besiegt) oder
  beide Gebiete schon besucht hast.
- **Klick auf ein Gebiet** (in beiden Ansichten) zeigt im Reiter **Gebiet** Leuchtfeuer (✓ entzündet), Bosse, alle Ausgänge mit Voraussetzungen und die
  Achievements, die dort eine Rolle spielen, samt aktueller Warnungen.

Alle Gebiets- und Verbindungsdaten stehen in `data/world.json` und sind frei editierbar (Position, Leuchtfeuer,
Bosse, Voraussetzungen). Unsichere Einträge sind dort mit `unsicher` markiert und erscheinen in der App orange als
„Bitte prüfen“.

## 3D-Ansicht

Im Kartenfenster schaltet **3D** auf die Karte des gewählten Gebiets um, ohne Auswahl auf die Karte, auf der du stehst.

- **Geometrie:** das echte Level mit den Farbtexturen aus deinen Spieldateien. Es wird im Hintergrund geladen
  (große Gebiete einige Sekunden), bis dahin siehst du das Wege-Netz.
- **Texturen (T):** schaltet auf ein einfarbiges Tonmodell um, darin sind Formen oft leichter zu erkennen.
- **Schnitthöhe:** Der Regler oben rechts (oder Bild auf/ab) blendet alles aus, was mehr als x Meter über dem Weg
  liegt, wie bei einem Puppenhaus. Standard 6 m, ganz rechts = kein Schnitt. So verdecken Bäume, Dächer und
  Felssäulen die Wege nicht.
- **Wege-Netz (N):** blendet zusätzlich das Navigationsnetz (begehbare Flächen) ein.
- **Marker:**
  - Items: grün = eingesammelt, orange = offen, blau = unbekannt. Würfel = liegendes Item, breiter Kasten = Truhe,
    goldener Ring = Achievement-relevant.
  - Leuchtfeuer: Flamme, leuchtend und mit Licht, wenn entzündet.
  - Lila Säulen: Übergänge zu anderen Gebieten.
- **Spieler:** cyanfarbene Figur mit Blickrichtung und hohem Lichtstrahl, an der Position aus dem Spielstand.
- **Zwei Steuerungsmodi**, umschalten mit **F** oder dem Knopf „Flugmodus“. **H** blendet die Steuerungshilfe unten
  links aus und ein.
  - **Orbit (Standard):** links ziehen = drehen, rechts ziehen (oder Shift + links) = verschieben, Mausrad = zum
    Mauszeiger zoomen (am kleinsten Abstand geht es weiter durchs Level), Doppelklick auf Boden oder Marker =
    dorthin fokussieren, WASD = Drehpunkt verschieben. Kleinster/größter Abstand und Geschwindigkeiten werden aus der
    Größe des Gebiets berechnet.
  - **Flugmodus:** WASD = bewegen, Q/E = runter/hoch, Shift = schneller. Klick in die Ansicht fängt die Maus
    (Mauszeiger verschwindet), dann dreht die Maus den Blick. Esc gibt die Maus frei, ein zweites Esc wechselt zurück
    zum Orbit. Das Mausrad ändert die Fluggeschwindigkeit.
- **Knöpfe:** „Schräg“, „Draufsicht“, „Ganzes Gebiet“, „Zum Spieler“, „Vom Spieler aus“ (Flugmodus in Augenhöhe an
  deiner gespeicherten Position). Beim Laden eines Gebiets wird die Kamera automatisch so gesetzt, dass das ganze
  Gebiet sichtbar ist. Ein Klick auf einen Marker zeigt Details.

**Wie „live“ ist die Spielerposition?** Sie kommt aus dem Spielstand, nicht aus dem Spielspeicher (das ist bewusst
ausgeschlossen). Sie aktualisiert sich also bei jedem Speichern des Spiels: beim Aufheben von Items, an Leuchtfeuern,
bei Gebietswechseln und regelmäßig zwischendurch. Dazwischen steht die Figur dort, wo zuletzt gespeichert wurde.

## Items pro Gebiet

Der Reiter **Items** rechts im Kartenfenster listet alle Fundorte des gewählten Gebiets aus `extracted/items.json`.

- **Filter:** wichtige Items, einzelne Kategorien (Schlüsselitems, Ringe, Zauber, Estus Flask Shards, Sublime Bone Dust,
  Waffen & Schilde, Rüstung, Boss-Seelen, Bund-Items, Material, Verbrauchsgüter) oder alle. Dazu „nur nicht
  eingesammelte“. Fundorte, die es erst in NG+ gibt, werden ab NG+ automatisch gezeigt, vorher nur auf Wunsch.
- **Status:**
  - *eingesammelt*: ein eindeutiges Item des Fundorts (Schlüssel, Ring, Zauber, Waffe, Rüstung) liegt im Inventar
  - *offen*: der Fundort enthält so ein Item, aber es ist nicht im Inventar
  - *unbekannt*: nur Verbrauchsgüter, Estus Flask Shards, Bone Dust usw. Ob die aufgehoben sind, steht in Flags im
    Spielstand, die noch nicht entschlüsselt sind. Bis dahin hakst du sie selbst ab (Häkchen, pro Charakter in
    `state.json`).
- ★ = relevant für ein Achievement (z. B. Zauber für „Master of …“, King's Ring).
- **Geteilte Karten:** Lost Bastille, Belfry Luna und Sinners' Rise liegen auf einer Karte, die Spieldaten
  unterscheiden die Fundorte dort nicht. Die Items stehen dann beim ersten Gebiet der Karte, die anderen verweisen
  darauf. Das gilt ebenso für Earthen Peak/Harvest Valley, Iron Keep/Belfry Sol und andere.
- Zufallstabellen (z. B. Geschenke von Dyna & Tillo) erscheinen als „Zufällig eines davon“ und zählen nicht mit.

## Bedienung

- **Häkchen:** Ein Achievement von Hand als erledigt oder offen markieren. Das überschreibt die automatische
  Erkennung. Steht deine Markierung auf demselben Stand wie die Erkennung, wird sie wieder entfernt.
- **Ausblenden:** Zum Beispiel für Achievements, die du schon hast. Sie zählen dann nicht mehr zum Fortschritt.
  „Ausgeblendete zeigen“ holt sie zurück.
- **Filter:** Alle / Offen / Erledigt / Verpassbar.
- **Guide anzeigen:** Kurze Anleitung pro Achievement (Ort, Voraussetzungen, NPC-Schritte). Unsichere Angaben stehen
  orange darunter mit „Unsicher, bitte prüfen“.
- **Immer im Vordergrund:** Praktisch auf dem zweiten Monitor.
- Deine Einstellungen stehen in `state.json`, getrennt pro Charakter (Slot und Name).

## Daten selbst korrigieren

- `data/achievements.json` enthält alle 38 Achievements mit Bedingung, Sicherheit, dem Merkmal verpassbar, Guide
  (`guide`) und unsicheren Punkten (`unsicher`).
  Die erlaubten Bedingungstypen stehen oben in der Datei.
- `data/gamedata.json` enthält Bossnamen, Boss-Reihenfolge, Zauberlisten und Item-IDs.
- `data/world.json` enthält Gebiete, Karten-IDs, Leuchtfeuer, Bosse pro Gebiet und Verbindungen (siehe Weltkarte).
  Achievements verweisen über das Feld `gebiete` auf Gebiete dieser Datei.

Mit `"sicherheit": "unsicher"` markierte Erkennungen zeigt die App mit „(?, unbestätigt)“ an.

## Was erkannt wird und was nicht

| Daten | Status |
|---|---|
| Charaktername, Slot, Seelenlevel, Seelen | zuverlässig |
| Inventar (Items, Ringe, Zauber, Schlüsselitems) | zuverlässig |
| Aktuelles Gebiet | zuverlässig auf Karten-Ebene. Teilen sich mehrere Gebiete eine Karte (z. B. Lost Bastille, Belfry Luna, Sinners' Rise), werden alle als aktuell markiert. |
| Entzündete Leuchtfeuer | zuverlässig (Tabelle in den Weltdaten des Slots, mit Fortnite gegengeprüft) |
| Karten-IDs der Memories, Dark Chasm, Shrine of Winter, einiger DLC-Gebiete | geraten bzw. unbekannt, in `world.json` markiert |
| Geöffnete Türen und Abkürzungen | noch nicht erkannt. Die Karte zeigt nur, ob du die Voraussetzung erfüllst. |
| Item-Fundorte | aus deiner Installation (`setup.bat`), vollständig |
| Aufgehobene Items | eindeutige Items über das Inventar. Verbrauchsgüter noch nicht, da die Aufheben-Flags fehlen. |
| 3D-Positionen von Items und Leuchtfeuern | aus dem Kartenlayout. Auf allen Karten liegen sie im Median unter 1 m vom Navigationsnetz entfernt. |
| Spielerposition | aus dem Spielstand (letztes Speichern), mit Majula und Forest gegengeprüft |
| Gebietsübergänge | Event-Bereiche mit Zielkarte. In Majula vollständig, auf manchen Karten fehlen welche (anders kodiert). |
| Boss-Arenen in 3D | noch nicht erkannt |
| Kartenmodell | alle 28 Karten. Platzierung geprüft: unter über 90 % der Wege liegt der Modellboden (Test). Wasser und Gras fehlen bewusst. |
| Texturen | Farbtextur je Material, 512 px. Gefunden für fast alle Materialien (z. B. Forest 123 von 126), der Rest erscheint in Tonfarbe. Ohne Normal- und Lichtkarten wirkt es flacher als im Spiel. |
| NG-Zyklus | NG (Wert 1) bestätigt, NG+ (ab Wert 2) noch unbestätigt |
| Besiegte Bosse | Kandidat, passt zu den Boss-Seelen im Inventar, aber noch nicht vollständig bestätigt |
| Letzter Boss | nur Bosse, die besiegt wurden, während die App lief |
| Boss-Gebiete für „jetzt“-Warnungen | aus `world.json`, für die Hauptgebiete über die Leuchtfeuer-IDs abgesichert |
| Tote NPCs | nicht erkennbar |
| Zauber (Master of …) | zuverlässig, Listen vollständig inkl. DLC (31 / 28 / 24 / 23) |
| Covenants | über den Siegel-Ring im Inventar (nur nach Beitritt; nur „entdeckt“ bitte selbst abhaken) |
| NPC-Ausrüstung (Benhart, Lucatiel, Vengarl, Cale, McDuff) | über typische Items, unbestätigt |
| Ur-Leuchtfeuer (stehen nicht in der Leuchtfeuer-Tabelle), NPC-Quests ohne Item, Gesten, Tode, Waffenstufe, Bundrang | noch nicht erkannt, bitte selbst abhaken |

## Bekannte Einschränkungen

- Das Spielstand-Format ist nicht offiziell dokumentiert. Grundlage sind Open-Source-Projekte und eigene Vergleiche.
  Nach einem Spiel-Update kann sich das Format ändern.
- Guides beruhen u. a. auf einem Trophäenleitfaden für die Originalversion. Wo SotFS abweichen kann (NPC-Orte,
  Beschwörungszeichen), ist das als unsicher markiert. 
- 7 Zauber gibt es erst in NG+ bzw. NG++. Die vier „Master of …“-Achievements sind im ersten Durchgang deshalb nicht
  komplett machbar, außer über Bundränge.
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
- Leuchtfeuer-IDs und -Namen: [Atvaarks DS2 ID-Liste](https://gist.github.com/Atvaark/69c6dcca10b226895a12)
- Gebiete, Bosse, Leuchtfeuer und Schlüssel-Items pro Gebiet: [Fextralife Dark Souls 2 Wiki](https://darksouls2.wiki.fextralife.com/)
- Reihenfolge der Boss-Zähler: [FrankvdStam/SoulSplitter](https://github.com/FrankvdStam/SoulSplitter)
- Archiv- und Kartenformate (BHD5, MSB2, NGP): [UXM](https://github.com/JKAnderson/UXM), [SoulsFormats](https://github.com/JKAnderson/SoulsFormats)
- Guides (eigene Zusammenfassung): [PSNProfiles Dark Souls II Trophy Guide](https://psnprofiles.com/guide/357-dark-souls-ii-trophy-guide)
- NPC-Details für SotFS (Lucatiel, Benhart): [Fextralife Dark Souls 2 Wiki](https://darksouls2.wiki.fextralife.com/)
