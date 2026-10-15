# Basic Rnote Editor for Android – Projektnotizen für Claude

Fork von `KJ-Ly/Basic-Rnote-editor-Android` (upstream), gepflegt von Toni & Yannick
unter `schooltoyayes/Basic-Rnote-editor-Android` (origin). Kotlin + Jetpack Compose,
liest und schreibt `.rnote`-Dateien der Desktop-App Rnote (0.14-Format, 0.15 wird gelesen).
Was die App kann, steht im README.

Stand: 15.10.2026

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
- Nicht nach upstream gehen (außer über PR #4, s. u.): der CI-Workflow, die Release-Signatur
  und `lint`-Einstellung in `app/build.gradle.kts`, die PNG-Launcher-Icons. Ansonsten sollen `main` und `upstream-pr`
  denselben Code haben.
- KJ-Lys `c9a1780` ("Save lined and blank backgrounds under Rnote's names") ist in
  `upstream-pr`, aber noch nicht in `main` – daher weichen `RnoteNativeSerializer.kt` und
  `RnoteNativeRoundTripTest.kt` zwischen beiden ab.
- Sobald KJ-Ly PR #3 mergt: `upstream-pr` auf seinen neuen `main` zurücksetzen.
- `upstream-ci`: Quelle von PR #4 bei KJ-Ly (auf Wunsch von KJ-Ly): der Workflow auf Englisch
  (Push auf `main`, `pull_request`, von Hand) plus Signatur und `lint` in `app/build.gradle.kts`.
  Wird #4 gemergt, gilt die Ausnahme oben nur noch für die PNG-Launcher-Icons.

## Werkzeug-Eigenheiten

- `gh` ist in diesem Ordner auf `schooltoyayes/Basic-Rnote-editor-Android` als Standard
  gesetzt. Für KJ-Lys Repo immer `--repo KJ-Ly/Basic-Rnote-editor-Android` angeben.
- Git hat keine globale Identität. Commits von Claude mit
  `git -c user.name=Claude -c user.email=noreply@anthropic.com commit …`.
- Remote-Branches löschen: in der Cloud-Umgebung (Claude Code im Browser) schlägt es fehl
  (403 vom Git-Proxy), dann machen es Toni & Yannick auf GitHub. Lokal geht
  `git push origin --delete <branch>` nach Bestätigung. Trotzdem keine unnötigen Hilfs-Branches.

## Offen

- KJ-Ly hat am 15.10. per E-Mail nach dem CI-Workflow gefragt (der Kommentar ist auf GitHub
  nicht mehr zu sehen); beantwortet in PR #3, Workflow als PR #4. Warten auf Merge.

## Zurückgestellt (nur auf Wunsch)

- Alte URIs bleiben nach Umbenennen oder Löschen eines Ordners stehen.
- `CustomFonts.restore` läuft auf dem Hauptthread.

## Erledigt (zuletzt)

- Workflow auf `ubuntu-26.04` und `upload-artifact@v7` (vorher Warnungen zu Node.js 20 und
  `ubuntu-latest`); der Release-Schritt läuft darauf erst nach dem Merge in `main`.

- #32 "Öffnen mit" nutzt die laufende Instanz (`singleTask` + `onNewIntent`). Folge:
  "Zurück" führt nicht mehr in die aufrufende App. Am Tablet ausprobiert, geht.
- #28 Top-Bar-Menüs auf sichtbare Höhe begrenzt und scrollbar.
- #32 Menüs im Stiftstreifen, Dateibrowser und in den Seiteneinstellungen ebenso begrenzt
  (`menuMaxHeight()`, wie #28); alle Menüs gehen über `ScreenDropdownMenu` in `MenuHeight.kt`.
- #29 Tablet Layout für kleine Bildschirme (< 600 dp breit oder < 480 dp hoch).
- #30 Papiermuster blendet beim Herauszoomen zwischen 10 und 6 px aus (vorher Moiré);
  Laser-Spur blendet auch nach abgebrochenem Strich aus.
- Seitenränder: kein Code nötig, Page Settings → Display → "Show format borders".
