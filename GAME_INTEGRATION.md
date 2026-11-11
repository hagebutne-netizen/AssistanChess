# Dein Spiel mit dem Schach-Assistenten verbinden (ohne Bildschirmaufnahme)

Samsung (oder dein Spiel über `FLAG_SECURE`) kann die Bildschirmaufnahme schwärzen. Dann im
Assistenten **„Direkt vom Spiel“** wählen. Dein Spiel schickt nach **jedem Zug** die Stellung:

| Extra | Pflicht | Inhalt |
|---|---|---|
| `fen` | ja | Stellung als FEN, z. B. `rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1` |
| `board` | empfohlen | Brett auf dem Bildschirm in Pixeln: `"links,oben,größe"` |
| `whiteBottom` | empfohlen | `true`, wenn Weiß unten ist (du spielst Weiß) |

Ohne `board` einmal im Assistenten auf **⌖** tippen und das Brett markieren.

## Android (Kotlin)
```kotlin
fun sendToAssistant(context: Context, fen: String, boardView: View, whiteBottom: Boolean) {
    val loc = IntArray(2)
    boardView.getLocationOnScreen(loc)
    context.sendBroadcast(
        Intent("de.schachassistent.FEN")
            .setPackage("de.schachassistent")
            .putExtra("fen", fen)
            .putExtra("board", "${loc[0]},${loc[1]},${boardView.width}")
            .putExtra("whiteBottom", whiteBottom)
    )
}
```
Aufrufen nach jedem Zug (deinem und dem des Gegners) und beim Start einer Partie.

## Unity (C#)
```csharp
void SendToAssistant(string fen, Rect boardScreenRect, bool whiteBottom) {
    using var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
    var activity = player.GetStatic<AndroidJavaObject>("currentActivity");
    var intent = new AndroidJavaObject("android.content.Intent", "de.schachassistent.FEN");
    intent.Call<AndroidJavaObject>("setPackage", "de.schachassistent");
    intent.Call<AndroidJavaObject>("putExtra", "fen", fen);
    // Unity: y=0 ist unten -> für Android in "oben" umrechnen
    int top = Screen.height - (int)(boardScreenRect.y + boardScreenRect.height);
    intent.Call<AndroidJavaObject>("putExtra", "board", $"{(int)boardScreenRect.x},{top},{(int)boardScreenRect.width}");
    intent.Call<AndroidJavaObject>("putExtra", "whiteBottom", whiteBottom);
    activity.Call("sendBroadcast", intent);
}
```

## Testen ohne Spiel (PC mit adb)
```
adb shell am broadcast -a de.schachassistent.FEN -p de.schachassistent --es fen "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1" --es board "0,600,1080" --ez whiteBottom true
```

## Warum ist die Aufnahme schwarz?
- Dein Spiel setzt `FLAG_SECURE` (z. B. `window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, …)`).
  Entfernen, dann funktioniert auch „Bild erkennen“.
- Samsung „Sicherer Ordner“, Game Booster-Datenschutz oder geschützte Videoinhalte (DRM).
