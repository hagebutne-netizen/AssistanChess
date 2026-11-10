# Schach-Assistent (Android, Kotlin)

Zweite App, die über deinem **eigenen** Schachspiel läuft: Bildschirm aufnehmen, Brett erkennen,
besten Zug als Pfeil direkt über dem Brett anzeigen.

## Öffnen & starten
1. Android Studio → *Open* → diesen Ordner wählen. Gradle-Sync abwarten.
2. Samsung per USB (Entwickleroptionen + USB-Debugging an) → ▶ *Run*.
3. Optional: **„Mein Spiel wählen“** – dann öffnet sich dein Spiel nach dem Start automatisch.
4. **START** tippen. Beim ersten Mal „Über anderen Apps anzeigen“ erlauben. Danach die Bildschirmaufnahme
   bestätigen (Android 14+ nimmt automatisch den ganzen Bildschirm).
5. **Neue Partie beginnen.** Ab da läuft alles automatisch:
   - Brett wird gefunden, Figuren werden einmal aus der Grundstellung eingelernt (gespeichert)
   - nach jedem Zug verschwindet der Pfeil sofort; die Leiste zeigt **DU BIST DRAN** / **GEGNER IST DRAN**
   - bist du dran, erscheint sofort ein **grüner Pfeil**, der sich verbessert, während Stockfish weiterrechnet
   - daneben die Bewertung aus deiner Sicht (z. B. +1.3 oder M3)
   - Zug-Anzeige falsch? **Antippen** schaltet um.

### Leiste
| Knopf | Funktion |
|---|---|
| ⠿ | ziehen = verschieben, tippen = ein-/ausklappen |
| ▶ / ❚❚ | Automatik an/aus |
| ⟳ | Brett neu suchen; bei Grundstellung Figuren neu einlernen |
| ♞ | sofort neu lesen und rechnen |
| ⌖ | Brett von Hand markieren (falls die Automatik es nicht findet) |
| # | 8x8-Raster ein/aus |
| ✕ | beenden |

## Aufbau
```
chesscore/ (reines Kotlin, per JVM-Test geprüft)
  board/Square, Move         Felder und Züge (UCI-Notation)
  recognition/Fen            FEN bauen und prüfen
  vision/BoardDetector       findet das 8x8-Brett im Screenshot
  vision/PieceRecognizer     lernt Figuren aus der Grundstellung, erkennt jedes Feld
  vision/PositionTracker     leitet ab, wer am Zug ist
app/
capture/AssistantService   Foreground-Service, verbindet alles, Benachrichtigung mit „Beenden“
capture/ScreenCapturer     MediaProjection → VirtualDisplay → ImageReader, hält immer den letzten Screenshot
overlay/OverlayController  Overlay-Fenster: Pfeile/Raster (nicht berührbar), Steuerleiste, Brett-Markierung
overlay/ArrowOverlayView   zeichnet Raster und Pfeile
overlay/BoardSelectionView Brett einmal markieren (ziehen, verschieben, Ecken skalieren)
board/BoardRegion          Brettlage + Ausrichtung, Feld ↔ Bildschirmkoordinaten (8x8)
recognition/PositionSources  BoardVision (Bilderkennung) und direkte FEN-Übergabe
engine/UciEngine           spricht UCI mit Stockfish 17.1 (liegt in jniLibs/arm64-v8a)
pipeline/AnalysisPipeline  Live-Modus: Änderung erkennen → Stellung → Engine → Pfeil
```

## Direkte FEN-Übergabe (Alternative ohne Bilderkennung) – funktioniert schon
Dein Spiel schickt nach jedem Zug:
```kotlin
sendBroadcast(Intent("de.schachassistent.FEN")
    .putExtra("fen", fen)
    .setPackage("de.schachassistent"))
```
Test ohne Spiel:
`adb shell am broadcast -a de.schachassistent.FEN -p de.schachassistent --es fen "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"`
Sobald ein FEN ankommt, wird automatisch analysiert und der beste Zug als grüner Pfeil gezeichnet.

## Wie die Erkennung funktioniert
- **Brett:** sucht das typische Schachmuster (zwei Feldfarben im Wechsel) und richtet die Rasterlinien pixelgenau aus.
- **Figuren:** jedes Feld wird auf 32x32 Punkte verkleinert, der Feldhintergrund pro Feld herausgerechnet
  (dadurch egal ob helles/dunkles/markiertes Feld). Verglichen wird mit den Vorlagen aus der Grundstellung.
  Kleine Verschiebungen werden ausgeglichen.
- **Live:** ein grober Fingerabdruck des Bretts (40x40 Punkte) wird bei jedem neuen Bild verglichen. Erst bei
  einer Änderung (und wenn Animationen fertig sind) läuft die volle Erkennung (~50 ms).
- **Eigene Pfeile stören nicht:** alles, was die App selbst zeichnet (Pfeile, Leiste, Raster), wird als
  Maske aus der Erkennung herausgerechnet. Nichts muss ausgeblendet werden, nichts flackert.
- **Wer ist dran:** ergibt sich aus der Farbe der Figur, die zuletzt gezogen hat.
- Eigene Spiel-Grafik geändert? In der Grundstellung einfach **Auto** drücken.

## Bekannte Grenzen
- Rochade-Rechte werden geschätzt (König/Turm auf Grundfeld), En-passant ist unbekannt.
- Hochformat: bei Drehung des Bildschirms stimmt die Aufnahmegröße nicht mehr (später).
- Seit Android 12 lassen Overlays Berührungen nur durch, wenn sie höchstens 80 % deckend sind,
  daher sind die Pfeile leicht transparent.
- Die Aufnahme-Erlaubnis muss Android bei jedem Start neu bestätigt werden (Systemvorgabe).

## Lizenz Stockfish
Stockfish 17.1 (offizieller Build `stockfish-android-armv8`) steht unter GPL-3.0,
Lizenztext in `app/src/main/assets/licenses/`. Für den privaten Gebrauch unproblematisch; wer die App
weitergibt, muss den Quellcode (auch dieser App) unter GPL-3.0 mitliefern.
