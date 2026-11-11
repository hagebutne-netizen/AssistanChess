#!/usr/bin/env bash
# Baut den Windows-Installer  release/SchachAssistent-Setup-<VERSION>.exe  (läuft unter Linux).
# Benötigt: JDK 21 (jlink) für Linux, die jmods eines Windows-JDK 21 GLEICHER Version,
#           Stockfish für Windows, mingw-w64 (x86_64-w64-mingw32-gcc/windres), NSIS (makensis), zip.
#   LINUX_JDK=/pfad/jdk-21.0.5+11  WIN_JDK=/pfad/jdk-21.0.5+11-windows  STOCKFISH_DIR=/pfad/stockfish \
#   desktop/packaging/build-windows.sh 1.0.0
set -euo pipefail
VERSION="${1:-1.0.0}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${WORK:-$(mktemp -d)}"
STAGE="$WORK/stage"
: "${LINUX_JDK:?}" "${WIN_JDK:?}" "${STOCKFISH_DIR:?}"
SF_EXE="${STOCKFISH_EXE:-$(ls "$STOCKFISH_DIR"/stockfish-windows-x86-64-sse41-popcnt.exe)}"

rm -rf "$STAGE"; mkdir -p "$STAGE/app" "$STAGE/engine"

echo "== App (JAR)"
(cd "$ROOT" && ./gradlew --no-daemon -q :desktop:fatJar)
cp "$ROOT/desktop/build/libs/SchachAssistent.jar" "$STAGE/app/"
# JNA: nur die Windows-Bibliotheken behalten
zip -q -d "$STAGE/app/SchachAssistent.jar" 'com/sun/jna/linux-*' 'com/sun/jna/darwin*' 'com/sun/jna/sunos-*' \
  'com/sun/jna/freebsd-*' 'com/sun/jna/openbsd-*' 'com/sun/jna/aix-*' 'com/sun/jna/dragonflybsd-*' 'com/sun/jna/win32-x86/*' \
  'com/formdev/flatlaf/natives/*linux*' 'com/formdev/flatlaf/natives/*macos*' || true

# JNA-Bibliothek daneben legen (wird dann nicht bei jedem Start in den Temp-Ordner entpackt)
(cd "$STAGE/app" && unzip -q -j SchachAssistent.jar 'com/sun/jna/win32-x86-64/jnidispatch.dll')

echo "== Java-Laufzeit (jlink, Windows)"
"$LINUX_JDK/bin/jlink" --module-path "$WIN_JDK/jmods" \
  --add-modules java.base,java.desktop,java.prefs,jdk.unsupported \
  --strip-debug --no-header-files --no-man-pages --compress=zip-0 --output "$STAGE/runtime"

echo "== Starter (SchachAssistent.exe)"
(cd "$HERE" && x86_64-w64-mingw32-windres launcher.rc -O coff -o "$WORK/launcher.res")
x86_64-w64-mingw32-gcc -municode -mwindows -O2 -s "$HERE/launcher.c" "$WORK/launcher.res" -o "$STAGE/SchachAssistent.exe"

echo "== Stockfish"
cp "$SF_EXE" "$STAGE/engine/stockfish.exe"
cp "$STOCKFISH_DIR/Copying.txt" "$STOCKFISH_DIR/AUTHORS" "$STAGE/engine/"
cp "$HERE/LIESMICH.txt" "$STAGE/"

echo "== Installer (NSIS)"
mkdir -p "$ROOT/release"
OUT="$ROOT/release/SchachAssistent-Setup-$VERSION.exe"
(cd "$HERE" && makensis -V2 -DSTAGE="$STAGE" -DOUT="$OUT" -DVERSION="$VERSION" installer.nsi)
ls -la "$OUT"
