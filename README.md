# Video Downloader (Android)

Eine einfache Android-App mit integriertem Browser (WebView). Auf Knopfdruck werden
Videos und GIFs der aktuell geladenen Seite erkannt, in einer Liste mit Dateiname,
Dateigröße und Checkbox angezeigt und die ausgewählten Dateien in die Fotos-App
(Album „VideoDownloader“) heruntergeladen.

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

## Setup / Bauen

Voraussetzung: Android Studio (aktuelle Version) mit installiertem Android SDK
(`compileSdk`/`targetSdk` 34). Projekt einfach in Android Studio öffnen – die
Gradle-Sync-Funktion lädt die restlichen Abhängigkeiten automatisch herunter.

Alternativ über die Kommandozeile (mit installiertem Android SDK und gesetztem
`ANDROID_HOME`/`local.properties`):

```
./gradlew assembleDebug
```

**Hinweis zur Entstehung dieses Projekts:** Der Code wurde in einer Sandbox-Umgebung
ohne Android-SDK und ohne Netzwerkzugriff auf die Google-Repositories erstellt. Das
Gradle-Projekt und der Kotlin-/XML-Code wurden sorgfältig geschrieben und die
XML-Dateien auf Wohlgeformtheit geprüft, ein echter Gradle/AGP-Build (`assembleDebug`)
konnte in dieser Umgebung aber **nicht** ausgeführt werden. Vor dem produktiven Einsatz
sollte einmal ein Sync/Build in Android Studio erfolgen, um eventuelle Versions- oder
Tippfehler zu finden.

## Minimale SDK-Version

`minSdk = 29` (Android 10), da ab dieser Version das Speichern in der Mediathek über
`MediaStore` ganz ohne Laufzeit-Berechtigungen (`WRITE_EXTERNAL_STORAGE`) funktioniert.
