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
4. Die App verschwindet, eine schwebende Leiste erscheint. Dein Spiel öffnen.
5. **Brett** → Quadrat genau auf die Brettkanten ziehen → *Weiß unten/Schwarz unten* prüfen (a1/h8 sind beschriftet) → *Übernehmen*.
   Die Markierung wird gespeichert, das muss man nur einmal machen.
6. **Analyse** → im Moment erscheint ein orangefarbener **Demo-Pfeil e2→e4**. Liegt er genau auf
   e2/e4, passen Raster und Overlay. **Raster** blendet das 8x8-Gitter ein/aus. **✕** beendet alles.

## Aufbau
```
capture/AssistantService   Foreground-Service, verbindet alles, Benachrichtigung mit „Beenden“
capture/ScreenCapturer     MediaProjection → VirtualDisplay → ImageReader, hält immer den letzten Screenshot
overlay/OverlayController  Overlay-Fenster: Pfeile/Raster (nicht berührbar), Steuerleiste, Brett-Markierung
overlay/ArrowOverlayView   zeichnet Raster und Pfeile
overlay/BoardSelectionView Brett einmal markieren (ziehen, verschieben, Ecken skalieren)
board/BoardRegion          Brettlage + Ausrichtung, Feld ↔ Bildschirmkoordinaten (8x8)
board/Square, Move         Felder und Züge (UCI-Notation)
recognition/BoardSlicer    Screenshot → 64 Feldbilder
recognition/BoardRecognizer  Schnittstelle Figurenerkennung (noch Platzhalter)
recognition/Fen            FEN bauen und prüfen
recognition/PositionSources  Stellung aus Screenshot ODER direkt per FEN aus deinem Spiel
engine/UciEngine           spricht UCI mit Stockfish 17.1 (liegt in jniLibs/arm64-v8a)
pipeline/AnalysisPipeline  Stellung → Engine → Pfeil
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

## Nächste Schritte
1. **OpenCV-Erkennung:** OpenCV-Abhängigkeit aktivieren, `TemplateMatchingRecognizer : BoardRecognizer`
   schreiben (Figurenbilder aus deinem Spiel in `assets/pieces/`), in `AssistantService` statt
   `UnimplementedBoardRecognizer` einsetzen.
2. Optional: Live-Modus (automatisch analysieren, wenn sich das Brett ändert), Drehung des Bildschirms.

## Bekannte Grenzen
- Hochformat: bei Drehung des Bildschirms stimmt die Aufnahmegröße nicht mehr (später).
- Seit Android 12 lassen Overlays Berührungen nur durch, wenn sie höchstens 80 % deckend sind,
  daher sind die Pfeile leicht transparent.
- Die Aufnahme-Erlaubnis muss Android bei jedem Start neu bestätigt werden (Systemvorgabe).

## Lizenz Stockfish
Stockfish 17.1 (offizieller Build `stockfish-android-armv8`) steht unter GPL-3.0,
Lizenztext in `app/src/main/assets/licenses/`. Für den privaten Gebrauch unproblematisch; wer die App
weitergibt, muss den Quellcode (auch dieser App) unter GPL-3.0 mitliefern.
