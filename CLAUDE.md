# Basic Rnote Editor for Android – Projektnotizen für Claude

Fork von `KJ-Ly/Basic-Rnote-editor-Android` (upstream), gepflegt von Toni & Yannick
unter `schooltoyayes/Basic-Rnote-editor-Android` (origin). Kotlin + Jetpack Compose,
liest und schreibt `.rnote`-Dateien der Desktop-App Rnote (0.14-Format, 0.15 wird gelesen).
Was die App kann, steht im README.

Stand: 13.10.2026

## Code

- Paket `io.github.kjly.brna` unter `app/src/main/java/`: `model`, `render`, `storage`,
  `export`, `audio`, `ui` (`ui/canvas/DrawingCanvas.kt` ist die Zeichenfläche,
  `ui/canvas/PaperBackgroundRenderer.kt` das Papiermuster).
- Verhalten folgt dem Code von `flxzt/rnote` (0.14.2, 0.15 wo vermerkt); Kommentare nennen
  die Rnote-Stelle. Neue Logik möglichst als reine Funktion mit Unit-Test unter `app/src/test/`.
- Kommentare und Commit-Messages auf Englisch, im Stil der vorhandenen.
- `tests/*.rnote` sind Beispieldateien mit echten Stiftstrichen.

## Bauen und Testen

- Lokal (Termux auf dem Tablet) gibt es kein Gradle/JDK. Gebaut und getestet wird nur in
  GitHub Actions: `.github/workflows/build-apk.yml` ("APK bauen") läuft bei Push auf `main`
  und `claude/**`, macht `assembleRelease` und `./gradlew test`.
- Nur `main` aktualisiert das Release `latest` (die APK für das Tablet).
- Nach jedem Push den Lauf mit `gh run watch <id> --exit-status` abwarten; Compile-Fehler
  zeigen sich erst dort (`gh run view <id> --log-failed`).

## Branches und Ablauf

- `main`: Stand des Forks. Änderungen kommen per PR aus `claude/new-session-zdx2bt`
  (Arbeits-Branch). Mergen machen Toni & Yannick selbst auf GitHub.
- `upstream-pr`: Quelle von PR #3 bei KJ-Ly (Draft, offen). Lineare Commits auf
  KJ-Lys `main` (`c9a1780`); neue Fixes aus `main` werden per Cherry-Pick übernommen,
  Fix-ups dabei zu einem Commit zusammengefasst. Hat bewusst keinen Workflow.
- Nicht nach upstream gehen: der CI-Workflow, die Release-Signatur und `lint`-Einstellung in
  `app/build.gradle.kts`, die PNG-Launcher-Icons. Ansonsten sollen `main` und `upstream-pr`
  denselben Code haben.
- KJ-Lys `c9a1780` ("Save lined and blank backgrounds under Rnote's names") ist in
  `upstream-pr`, aber noch nicht in `main` – daher weichen `RnoteNativeSerializer.kt` und
  `RnoteNativeRoundTripTest.kt` zwischen beiden ab.
- Sobald KJ-Ly PR #3 mergt: `upstream-pr` auf seinen neuen `main` zurücksetzen.

## Werkzeug-Eigenheiten

- `gh` ist in diesem Ordner auf `schooltoyayes/Basic-Rnote-editor-Android` als Standard
  gesetzt. Für KJ-Lys Repo immer `--repo KJ-Ly/Basic-Rnote-editor-Android` angeben.
- Git hat keine globale Identität. Commits von Claude mit
  `git -c user.name=Claude -c user.email=noreply@anthropic.com commit …`.
- Remote-Branches löschen per Git schlägt fehl (403, Organisationsrichtlinie) – das machen
  Toni & Yannick auf GitHub. Darum keine unnötigen Hilfs-Branches anlegen.

## Offen

- Antwort von KJ-Ly auf PR #3.

## Zurückgestellt (nur auf Wunsch)

- Menü-Höhe begrenzen auch im Stiftstreifen, Dateibrowser und in den Seiteneinstellungen
  (Top-Bar-Menüs sind seit #28 erledigt).
- Kein `singleTask`/`onNewIntent` für "Öffnen mit".
- Alte URIs bleiben nach Umbenennen oder Löschen eines Ordners stehen.
- `CustomFonts.restore` läuft auf dem Hauptthread.

## Erledigt (zuletzt)

- #28 Top-Bar-Menüs auf sichtbare Höhe begrenzt und scrollbar.
- #29 Tablet Layout für kleine Bildschirme (< 600 dp breit oder < 480 dp hoch).
- #30 Papiermuster blendet beim Herauszoomen zwischen 10 und 6 px aus (vorher Moiré);
  Laser-Spur blendet auch nach abgebrochenem Strich aus.
- Seitenränder: kein Code nötig, Page Settings → Display → "Show format borders".
