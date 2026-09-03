# Video Downloader (Android)

Eine einfache Android-App mit integriertem Browser (WebView). Auf Knopfdruck werden
Videos und GIFs der aktuell geladenen Seite erkannt, in einer Liste mit Dateiname,
Dateigröße und Checkbox angezeigt und die ausgewählten Dateien in die Fotos-App
(Album „VideoDownloader“) heruntergeladen.

Die App ist eine **native, eigenständige Android-App** (Kotlin/Android-Studio-Projekt,
keine Expo-/React-Native-Abhängigkeit). Sie läuft komplett unabhängig, ohne dass ein
Entwicklungsserver, Termux oder eine Begleit-App wie Expo Go dafür laufen muss.

## Fertige APK herunterladen (empfohlen)

Ein GitHub-Actions-Workflow baut bei jedem Push auf diesen Branch automatisch eine
installierbare Debug-APK und veröffentlicht sie als GitHub-Release:

1. Im Browser (auch auf dem Handy) zu
   `https://github.com/CarstenKeller/Video-Downloader/releases/tag/latest-debug-build`
   gehen.
2. Die Datei `app-debug.apk` herunterladen.
3. Beim ersten Mal fragt Android nach der Erlaubnis „Installation aus unbekannten
   Quellen“ für die App, mit der die Datei geöffnet wurde (z. B. Chrome/Dateien) –
   einmalig erlauben.
4. APK antippen → installieren → fertig. Die App erscheint als eigenes Icon
   „Video Downloader“ und lässt sich wie jede andere App starten.

Der Build-Fortschritt lässt sich unter dem Reiter **Actions** des Repositories
verfolgen; ein Push auf den Branch löst automatisch einen neuen Build aus, der das
Release überschreibt.

## Funktionsweise

- **Browser**: `MainActivity` hostet eine `WebView` mit Adressleiste, Zurück/Vorwärts/Neu-laden.
- **Scan**: Der Such-Button injiziert JavaScript (`MediaScanner.SCAN_JS`) in die geladene
  Seite. Es werden gesammelt:
  - `<video>`-Elemente (`src`/`currentSrc` sowie verschachtelte `<source>`-Tags)
  - `<img>`-Tags mit `.gif`-Endung
  - `<a href="...">`-Links, die direkt auf Video- oder GIF-Dateien zeigen
- **Liste**: `MediaListBottomSheet` zeigt die Funde mit Dateiname, Größe (per HTTP
  `HEAD`-Request ermittelt) und Checkbox. „Alle auswählen“/„Alle abwählen“ sowie ein
  Download-Button mit Live-Zähler sind enthalten.
- **Download**: `DownloadCoordinator` lädt die ausgewählten Dateien (max. 3 parallel)
  per OkHttp herunter. `MediaSaver` schreibt sie über `MediaStore` in die Foto-Mediathek:
  - GIFs → `Pictures/VideoDownloader`
  - Videos → `Movies/VideoDownloader`

  Der Ordner (das Album) wird von `MediaStore` beim ersten Insert automatisch angelegt,
  ein separater "Album erstellen"-Schritt ist auf Android 10+ nicht nötig.

Da es sich um eine echte, eigenständige App handelt (kein Expo-Go-Container), gelten
dabei die normalen Android-Berechtigungen ohne Einschränkungen – anders als bei einer
früheren Zwischenversion dieses Projekts, die versuchsweise über Expo Go lief: dort
verweigert Expo Go auf Android grundsätzlich vollen Foto-/Video-Bibliothekszugriff.

## Bekannte Einschränkungen (bewusst, keine Bugs)

- **`blob:`-URLs werden nicht erfasst.** Viele Video-Plattformen (u. a. YouTube, oft
  auch Instagram/Twitter) laden Video über die Media Source Extensions als `blob:`-URL.
  Diese lässt sich nicht per einfachem HTTP-Request nachladen – eine zuverlässige
  Umgehung würde Netzwerk-Interception/Screen-Recording erfordern, was hier bewusst
  nicht umgesetzt ist.
- **DRM-geschützte Inhalte** (Streaming-Dienste mit Widevine o. ä.) sind grundsätzlich
  nicht herunterladbar.
- Lazy-Loading: Manche Seiten laden das `<video>`-Element erst nach Klick auf „Play“.
  Ggf. erst abspielen, dann erneut scannen.
- Manche Server blocken `HEAD`-Requests oder liefern keine `Content-Length` – in dem
  Fall wird „Größe unbekannt“ angezeigt; der Download selbst funktioniert trotzdem.
- Da Videos unter `Movies/VideoDownloader` und GIFs unter `Pictures/VideoDownloader`
  liegen (zwei unterschiedliche MediaStore-Sammlungen), zeigt z. B. Google Photos dies
  ggf. als zwei separate, aber gleichnamige Alben „VideoDownloader“ an – ein
  MediaStore-technisch bedingtes Detail, keine getrennte "Video"/"GIF"-App-Logik.

## Selbst bauen (alternativ zur fertigen APK)

Voraussetzung: Android Studio (aktuelle Version) mit installiertem Android SDK
(`compileSdk`/`targetSdk` 34). Projekt einfach in Android Studio öffnen – die
Gradle-Sync-Funktion lädt die restlichen Abhängigkeiten automatisch herunter.

Alternativ über die Kommandozeile (mit installiertem Android SDK und gesetztem
`ANDROID_HOME`/`local.properties`):

```
./gradlew assembleDebug
```

**Hinweis zur Entstehung dieses Projekts:** Der Code wurde in einer Sandbox-Umgebung
ohne Android-SDK und ohne Netzwerkzugriff auf die Google-Repositories erstellt, daher
konnte ein Gradle/AGP-Build hier nicht direkt ausgeführt werden. Verifiziert wurde
stattdessen über den beigefügten GitHub-Actions-Workflow (`.github/workflows/build-apk.yml`),
der auf einem GitHub-Runner mit echtem Android-SDK baut – der Actions-Lauf für den
letzten Push ist der eigentliche Nachweis, dass der Build funktioniert (siehe Reiter
„Actions“ im Repository für das Ergebnis des letzten Laufs).

## Minimale SDK-Version

`minSdk = 29` (Android 10), da ab dieser Version das Speichern in der Mediathek über
`MediaStore` ganz ohne Laufzeit-Berechtigungen (`WRITE_EXTERNAL_STORAGE`) funktioniert.
