# Video Downloader (Expo / React Native)

Eine App mit integriertem Browser (WebView). Auf Knopfdruck werden Videos und GIFs der
aktuell geladenen Seite erkannt, in einer Liste mit Dateiname, Dateigröße und Checkbox
angezeigt und die ausgewählten Dateien in die Fotos-App (Album „VideoDownloader“)
heruntergeladen.

Das Projekt ist als **Expo/React-Native-App** gebaut, damit es sich direkt über
**Termux + Expo Go** auf dem Smartphone entwickeln und live testen lässt – ganz ohne
Android Studio, Android-SDK oder PC. Eine frühere native Kotlin/Android-Studio-Version
dieses Projekts wurde deswegen verworfen; Expo Go kann nur JavaScript/React-Native-Apps
laden, keine nativen Android-Projekte.

## Warum das funktioniert

Alle benötigten Fähigkeiten sind über Standard-Expo-SDK-Module abgedeckt, die **ohne
eigenen Custom-Native-Code auskommen** und deshalb direkt in Expo Go laufen (kein
„Development Build“/EAS Build nötig):

- `react-native-webview` – der Browser samt JavaScript-Injection zum Scannen der Seite
- `expo-file-system` – Herunterladen der Dateien mit Fortschrittsanzeige
- `expo-media-library` – Speichern in der Fotomediathek inkl. automatischer
  Album-Erstellung
- `@expo/vector-icons` + `expo-font` – Icons für die Toolbar

## Funktionsweise

- **Browser** (`App.tsx`): `WebView` mit Adressleiste, Zurück/Vorwärts/Neu-laden und
  Ladefortschrittsbalken.
- **Scan** (`src/mediaScanner.ts`): Der Such-Button injiziert JavaScript in die geladene
  Seite. Es werden gesammelt:
  - `<video>`-Elemente (`src`/`currentSrc` sowie verschachtelte `<source>`-Tags)
  - `<img>`-Tags mit `.gif`-Endung
  - `<a href="...">`-Links, die direkt auf Video- oder GIF-Dateien zeigen

  Das Ergebnis wird per `window.ReactNativeWebView.postMessage(...)` an die App
  zurückgemeldet.
- **Liste** (`src/MediaListModal.tsx`): zeigt die Funde mit Dateiname, Größe (per HTTP
  `HEAD`-Request ermittelt, `src/fetchContentLength.ts`) und Checkbox. „Alle
  auswählen“/„Alle abwählen“ sowie ein Download-Button mit Live-Zähler sind enthalten.
- **Download** (`src/downloadCoordinator.ts`): lädt die ausgewählten Dateien (max. 3
  parallel) über `expo-file-system` in den Cache-Ordner herunter und speichert sie
  danach über `src/mediaSaver.ts` per `expo-media-library` in die Fotomediathek, gruppiert
  in ein Album „VideoDownloader“. Das Album wird beim ersten Download automatisch
  angelegt.

## Bekannte Einschränkungen (bewusst, keine Bugs)

- **`blob:`-URLs werden nicht erfasst.** Viele Video-Plattformen (u. a. YouTube, oft
  auch Instagram/Twitter) laden Video über die Media Source Extensions als `blob:`-URL.
  Diese lässt sich nicht per einfachem HTTP-Request nachladen – eine zuverlässige
  Umgehung würde Netzwerk-Interception/Screen-Recording erfordern, was hier bewusst
  nicht umgesetzt ist.
- **DRM-geschützte Inhalte** sind grundsätzlich nicht herunterladbar.
- Lazy-Loading: Manche Seiten laden das `<video>`-Element erst nach Klick auf „Play“.
  Ggf. erst abspielen, dann erneut scannen.
- Manche Server blocken `HEAD`-Requests oder liefern keine `Content-Length` – in dem
  Fall wird „Größe unbekannt“ angezeigt; der Download selbst funktioniert trotzdem.

## Ungeklärter Punkt, ehrlich benannt

`expo-media-library` legt ein Album technisch als einen einzelnen Ordner an, und
Android ordnet Bilder (GIFs) standardmäßig `Pictures/…` und Videos standardmäßig
`Movies/…` zu. Ob die Bibliothek beim Mischen beider Typen unter demselben Album-Namen
tatsächlich **einen gemeinsamen** Ordner verwendet oder intern zwei getrennte Ordner
(die in Google Photos dann als zwei gleichnamige Alben auftauchen würden), konnte ich
**ohne echtes Gerät nicht verifizieren** – das ist eine Interpretation aus dem
Bibliotheks-Quellcode, kein getesteter Fakt. Der Code in `src/mediaSaver.ts` ist deshalb
defensiv geschrieben: Er versucht, jede Datei in das gemeinsame Album „VideoDownloader“
einzusortieren, stürzt aber nicht ab, falls das für einen Medientyp fehlschlägt – die
Datei landet dann trotzdem sicher in der Fotomediathek, nur eventuell nicht im Album
gruppiert. Bitte nach den ersten echten Downloads in Google Photos/Fotos kontrollieren,
ob GIFs und Videos im selben Album landen.

## Setup: Entwicklung direkt auf dem Smartphone (Termux + Expo Go)

1. In Termux:
   ```
   pkg update && pkg install nodejs-lts git
   ```
2. Projekt klonen und Abhängigkeiten installieren:
   ```
   git clone <repo-url>
   cd Video-Downloader
   npm install
   ```
3. Dev-Server starten:
   ```
   npx expo start
   ```
   Das startet den Metro-Bundler (Standardport 8081) und zeigt einen QR-Code sowie eine
   `exp://…`-Adresse an.
4. In der **Expo Go**-App (auf demselben Handy) auf „Enter URL manually“ tippen und
   `exp://127.0.0.1:8081` eingeben (Termux und Expo Go laufen zwar in getrennten
   Android-Sandboxes, teilen sich aber dieselbe Loopback-Schnittstelle – das
   funktioniert zuverlässig auf einem Gerät). Alternativ den QR-Code mit einem zweiten
   Gerät scannen, wenn eines zur Verfügung steht.
5. Falls die lokale Verbindung Probleme macht (z. B. wegen einer Firewall-App): 
   ```
   npx expo start --tunnel
   ```
   nutzt einen Cloudflare/ngrok-Tunnel und funktioniert unabhängig vom lokalen Netzwerk,
   braucht aber Internetzugang.

Jede Codeänderung wird per Fast Refresh sofort in Expo Go sichtbar – ein Neustart des
Servers ist nur nach Änderungen an `app.json` oder neu installierten Paketen nötig.

## Expo SDK Version

Das Projekt ist exakt auf **`expo@54.0.8`** fixiert (React Native 0.81.4, ohne `^`/`~`
vor der Expo-Version in `package.json`), passend zur installierten Expo-Go-Version.

Wichtig dabei: Es reicht nicht, nur die grobe SDK-Zahl (z. B. „SDK 54“) zu treffen.
Expo Go wird selbst nicht regelmäßig aktualisiert (Play Store bietet oft kein Update
mehr an) und bleibt dann auf einem frühen Patch-Release einer SDK-Version stehen (hier:
`54.0.8`). Ein neuerer `expo`-Patch derselben SDK-Version (z. B. `54.0.37`) kann eine
neuere native Laufzeit voraussetzen, als der installierte Expo-Go-Client bietet, und
löst dann „Project is incompatible with this version of Expo Go – this project
requires a **newer** version of Expo Go“ aus – obwohl beide offiziell „SDK 54“ sind.
Deshalb ist `expo` hier ohne Versions-Bereich (kein `^54.0.8`) gepinnt, damit ein
`npm install` nicht versehentlich auf einen neueren 54.x-Patch hochzieht.

Falls das auf einem anderen Gerät/mit einer anderen Expo-Go-Version wieder passiert:
In Expo Go unter „Profile“/„Settings“ die exakte unterstützte SDK-Version nachsehen,
dann testweise mit der App-Versionsnummer von Expo Go selbst beginnen (hier stimmten
Expo-Go-App-Version und benötigter `expo`-Patch zufällig überein: `54.0.8`), per
`npm install expo@<version>` gefolgt von `npx expo install --fix` (im Zweifel mit
`EXPO_OFFLINE=1`, falls die Kompatibilitätsabfrage von Expo online fehlschlägt) darauf
umstellen, danach `rm -rf node_modules package-lock.json && npm install` für einen
sauberen Stand.

Wichtig: Zwischen SDK-Versionen ändern sich teils auch die APIs von `expo-file-system`
und `expo-media-library` (nicht nur Versionsnummern) – siehe Kommentare in
`src/downloadCoordinator.ts` (nutzt bewusst `expo-file-system/legacy` für den
Downloadfortschritt, da die neuere `File`/`Paths`-API in SDK 54 noch kein
`onProgress` unterstützt) und `src/mediaSaver.ts` (nutzt die klassische
`createAssetAsync`/`getAlbumAsync`/`createAlbumAsync`-API statt der neueren
`Asset`/`Album`-Klassen).

## Verifikation in dieser Sandbox

In der Umgebung, in der dieser Code entstanden ist, gab es keinen Zugriff auf ein
echtes Android-Gerät/Emulator. Was tatsächlich geprüft wurde:

- `npx tsc --noEmit` – TypeScript kompiliert ohne Fehler
- `npx expo export --platform android` – Metro bündelt das komplette Projekt
  erfolgreich zu einem lauffähigen Android-Bundle
- `npx expo-doctor` – keine Abhängigkeits- oder Konfigurationsprobleme (die zwei
  verbleibenden Warnungen sind reine Netzwerk-Timeouts beim Erreichen von
  Expo-eigenen Online-Diensten, kein Projektfehler)

Ein echter Lauf in Expo Go auf einem physischen Gerät (Download-Flow, Berechtigungen,
Album-Verhalten) steht noch aus und sollte als erster Schritt erfolgen.

## Minimale Android-Version

Für einen künftigen eigenständigen Build (EAS Build/`expo prebuild`) ist
`android.package` auf `de.carstenkeller.videodownloader` gesetzt. Für die Entwicklung
über Expo Go spielt das keine Rolle – dort läuft die App im Expo-Go-Container.
