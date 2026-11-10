# Schach-Assistent (Android, Kotlin)

Zweite App, die über deinem **eigenen** Schachspiel läuft: Bildschirm aufnehmen, Brett erkennen,
besten Zug als Pfeil direkt über dem Brett anzeigen.

## Öffnen & starten
1. Android Studio → *Open* → diesen Ordner wählen. Gradle-Sync abwarten.
2. Samsung per USB (Entwickleroptionen + USB-Debugging an) → ▶ *Run*.
3. In der App **„Assistent starten“** tippen:
   - einmalig „Über anderen Apps anzeigen“ erlauben, dann zurück und nochmal tippen
   - Benachrichtigungen erlauben
   - im Aufnahme-Dialog **„Gesamten Bildschirm“** wählen (nicht „Einzelne App“)
4. Die App verschwindet, eine schwebende Leiste erscheint. **Dein Spiel öffnen und eine neue Partie
   starten** (Grundstellung). Ab da läuft alles automatisch:
   - das Brett wird automatisch gefunden
   - die Figuren werden aus der Grundstellung eingelernt (einmalig, wird gespeichert)
   - nach jedem Zug: Stellung erkennen → Stockfish → Pfeil
     - **grün** = dein bester Zug, **blau** = bester Zug des Gegners (wenn er dran ist)
   - wer am Zug ist, wird aus dem letzten Zug abgeleitet; deine Farbe = die Seite unten

### Knöpfe der Leiste
| Knopf | Funktion |
|---|---|
| ⠿ | Leiste verschieben |
| ● Live / ○ Live | Automatik an/aus |
| Auto | Brett neu suchen; bei Grundstellung Figuren neu einlernen |
| Zug | sofort einmal analysieren |
| Brett | Brett von Hand markieren (falls die Automatik es nicht findet) |
| # | 8x8-Raster ein/aus (zum Prüfen) |
| ✕ | alles beenden |

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
- **Live:** ein grober Fingerabdruck des Bretts wird ~3x pro Sekunde verglichen. Erst bei einer Änderung
  (und wenn Animationen fertig sind) läuft die volle Erkennung. Dafür werden die Pfeile kurz ausgeblendet.
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
