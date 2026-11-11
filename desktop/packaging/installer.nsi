; Installer für die PC-Version des Schach-Assistenten (NSIS 3, Unicode).
; Wird von build-windows.sh aufgerufen:  makensis -DSTAGE=<Ordner> -DOUT=<Datei> -DVERSION=x.y.z installer.nsi
Unicode true
SetCompressor /SOLID lzma
SetCompressorDictSize 64
RequestExecutionLevel user
ManifestDPIAware true

!include "MUI2.nsh"

!define APPNAME "Schach-Assistent"
!define APPKEY  "SchachAssistent"
Name "${APPNAME}"
OutFile "${OUT}"
InstallDir "$LOCALAPPDATA\Programs\${APPKEY}"
InstallDirRegKey HKCU "Software\${APPKEY}" "InstallDir"
BrandingText "${APPNAME} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1031 "ProductName" "${APPNAME}"
VIAddVersionKey /LANG=1031 "FileDescription" "${APPNAME} – Installation"
VIAddVersionKey /LANG=1031 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1031 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1031 "LegalCopyright" "App: privat · Stockfish: GPL-3.0"

!define MUI_ICON "app.ico"
!define MUI_UNICON "app.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Schach-Assistent ${VERSION}"
!define MUI_WELCOMEPAGE_TEXT "Zeigt dir mit Pfeilen den besten Zug direkt über dem Brett deines Schachspiels – berechnet von Stockfish.$\r$\n$\r$\nDie Installation braucht keine Administratorrechte und dauert nur einen Moment.$\r$\n$\r$\nKlicke auf Weiter."
!define MUI_FINISHPAGE_RUN "$INSTDIR\SchachAssistent.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Schach-Assistent jetzt starten"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "German"

Section "Install"
  ; laufende Version beenden, damit Dateien ersetzt werden können
  nsExec::Exec 'taskkill /F /IM stockfish.exe'
  SetOutPath "$INSTDIR"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  File /r "${STAGE}\*.*"
  WriteUninstaller "$INSTDIR\Deinstallieren.exe"

  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\SchachAssistent.exe" "" "$INSTDIR\SchachAssistent.exe" 0
  CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME} deinstallieren.lnk" "$INSTDIR\Deinstallieren.exe"
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\SchachAssistent.exe" "" "$INSTDIR\SchachAssistent.exe" 0

  WriteRegStr HKCU "Software\${APPKEY}" "InstallDir" "$INSTDIR"
  !define UNKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPKEY}"
  WriteRegStr HKCU "${UNKEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNKEY}" "Publisher" "Schach-Assistent"
  WriteRegStr HKCU "${UNKEY}" "DisplayIcon" "$INSTDIR\SchachAssistent.exe"
  WriteRegStr HKCU "${UNKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNKEY}" "UninstallString" '"$INSTDIR\Deinstallieren.exe"'
  WriteRegDWORD HKCU "${UNKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNKEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  nsExec::Exec 'taskkill /F /IM stockfish.exe'
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\engine"
  Delete "$INSTDIR\SchachAssistent.exe"
  Delete "$INSTDIR\LIESMICH.txt"
  Delete "$INSTDIR\Deinstallieren.exe"
  RMDir "$INSTDIR"
  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$SMPROGRAMS\${APPNAME}"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPKEY}"
  DeleteRegKey HKCU "Software\${APPKEY}"
SectionEnd
