/*
 * SchachAssistent.exe – kleiner Starter für die PC-Version.
 * Startet  <Ordner>\runtime\bin\javaw.exe -jar <Ordner>\app\SchachAssistent.jar  ohne Konsolenfenster.
 * Gebaut mit: x86_64-w64-mingw32-gcc -municode -mwindows -O2 launcher.c launcher.res -o SchachAssistent.exe
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>

static void fail(const wchar_t *msg) {
    MessageBoxW(NULL, msg, L"Schach-Assistent", MB_OK | MB_ICONERROR);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR cmdLine, int show) {
    (void)hInst; (void)hPrev; (void)show;
    wchar_t dir[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(NULL, dir, MAX_PATH * 2);
    if (n == 0 || n >= MAX_PATH * 2) { fail(L"Programmordner nicht gefunden."); return 1; }
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;

    wchar_t java[MAX_PATH * 3], jar[MAX_PATH * 3], cmd[MAX_PATH * 8];
    _snwprintf(java, MAX_PATH * 3, L"%ls\\runtime\\bin\\javaw.exe", dir);
    _snwprintf(jar, MAX_PATH * 3, L"%ls\\app\\SchachAssistent.jar", dir);
    if (GetFileAttributesW(java) == INVALID_FILE_ATTRIBUTES || GetFileAttributesW(jar) == INVALID_FILE_ATTRIBUTES) {
        fail(L"Dateien fehlen. Bitte den Schach-Assistenten neu installieren.");
        return 1;
    }
    _snwprintf(cmd, MAX_PATH * 8,
        L"\"%ls\" -Xmx768m -XX:+UseSerialGC -Dsun.java2d.d3d=false -Dsun.java2d.noddraw=true -Dfile.encoding=UTF-8 "
        L"-Djna.boot.library.path=\"%ls\\app\" -Djna.nounpack=true -Dflatlaf.useNativeLibrary=false -jar \"%ls\" %ls",
        java, dir, jar, cmdLine ? cmdLine : L"");

    STARTUPINFOW si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si); si.cb = sizeof si;
    ZeroMemory(&pi, sizeof pi);
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        fail(L"Java konnte nicht gestartet werden.");
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
