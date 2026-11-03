# Basic Rnote Editor for Android – project notes for Claude

Fork of `KJ-Ly/Basic-Rnote-editor-Android` (upstream), maintained by Toni & Yannick
as `schooltoyayes/Basic-Rnote-editor-Android` (origin). Kotlin + Jetpack Compose,
reads and writes `.rnote` files from the Rnote desktop app (0.14 format, 0.15 is read).
The README lists what the app can do.

Toni & Yannick prefer replies in German.

As of: 03.11.2026

## Code

- Package `io.github.kjly.brna` under `app/src/main/java/`: `model`, `render`, `storage`,
  `export`, `audio`, `ui` (`ui/canvas/DrawingCanvas.kt` is the drawing surface,
  `ui/canvas/PaperBackgroundRenderer.kt` the paper pattern).
- Behaviour follows the code of `flxzt/rnote` (0.14.2, 0.15 where noted); comments name
  the Rnote source location. New logic preferably as a pure function with a unit test under `app/src/test/`.
- Comments and commit messages in English, in the style of the existing ones.
- `tests/*.rnote` are sample files with real pen strokes.

## Building and testing

- Locally (Termux on the tablet) there is no Gradle/JDK. Building and testing happen only in
  GitHub Actions: `.github/workflows/build-apk.yml` ("APK bauen") runs on pushes to `main`
  and `claude/**`, does `assembleRelease` and `./gradlew test`.
- Only `main` updates the `latest` release (the APK for the tablet).
- After every push, wait for the run with `gh run watch <id> --exit-status`; compile errors
  only show up there (`gh run view <id> --log-failed`).

## Branches and workflow

- `main`: the fork's current state. Changes come in by PR from `claude/new-session-zdx2bt`
  (the working branch). Toni & Yannick merge on GitHub themselves.
- `upstream-pr`: source of PR #3 at KJ-Ly (draft, open). Linear commits on
  `a00e629`, the last commit of PR #2 (merged at KJ-Ly as `8541b1e`). KJ-Ly's own
  `05c9c65` and `c9a1780` and the merge of PR #4 (`2fce4e1`, his `main`) came after it and
  are not in `upstream-pr`; GitHub still reports #3 as mergeable. New fixes from `main`
  are cherry-picked, with fix-ups squashed into one commit. Deliberately has no workflow.
- Not going upstream: the PNG launcher icons. The CI workflow, the release signing
  and the `lint` setting in `app/build.gradle.kts` are at KJ-Ly too since PR #4 (there
  with the workflow in English). Otherwise `main` and `upstream-pr` should have the same code.
- KJ-Ly's `c9a1780` ("Save lined and blank backgrounds under Rnote's names") is the same
  fix as `main`'s own `f7eb629` (which also adds a test), but neither is in `upstream-pr`:
  on its own, `upstream-pr` still writes "ruled"/"blank", which desktop Rnote refuses.
  That is why `RnoteNativeSerializer.kt` and `RnoteNativeRoundTripTest.kt` differ between
  `main` and `upstream-pr`. Harmless for PR #3: `upstream-pr` never touches those lines, so
  merging it keeps KJ-Ly's fix.
- As soon as KJ-Ly merges PR #3: reset `upstream-pr` to KJ-Ly's new `main`.
- `upstream-ci` (deleted on 25.10.): source of PR #4 at KJ-Ly (at KJ-Ly's request), the
  workflow in English plus signing and `lint` in `app/build.gradle.kts`. Merged on 20.10.

## Tooling quirks

- `gh` has `schooltoyayes/Basic-Rnote-editor-Android` set as default in this folder.
  For KJ-Ly's repo always pass `--repo KJ-Ly/Basic-Rnote-editor-Android`.
- Git has no global identity. Commits by Claude with
  `git -c user.name=Claude -c user.email=noreply@anthropic.com commit …`.
- Deleting remote branches: fails in the cloud environment (Claude Code in the browser)
  (403 from the Git proxy); then Toni & Yannick do it on GitHub. Locally,
  `git push origin --delete <branch>` works after confirmation. Still, no unnecessary helper branches.

## Open

- PR #3 at KJ-Ly is still a draft (updated with the Word import on 03.11.). Until it is
  merged, `RnoteNativeSerializer.kt` and `RnoteNativeRoundTripTest.kt` stay different
  between `main` and `upstream-pr` (see above).

## Deferred (only on request)

- Old URIs stay listed after a folder is renamed or deleted.
- `CustomFonts.restore` runs on the main thread.

## Done (recently)

- #38/#39 Word import (`.docx`), tried on the tablet with two worksheets: `DocxReader` reads
  the document into a small model (unit-tested), `DocxImporter` sets it as PDF pages with
  `StaticLayout` (20 layout units per point), which then go through the PDF import dialog
  from `cache/docx/import.pdf`. Menu "Import Word document…", Ctrl+Shift+I, drop/paste,
  `FolderListing.Kind.WORD` in the workspace browser. Left out on purpose: headers/footers,
  footnotes, text boxes, columns, floating shapes; old binary `.doc`.
  Fonts with Word's letter widths in `assets/fonts` (Liberation, Carlito, Caladea, ~7 MB,
  OFL; `DocxReader.metricCompatible`). Checked against the English worksheets from Yannick's
  Drive ("Part I/II - Post reading"): answer lines are 280 Arial underscores, double-spaced.

- PR #4 at KJ-Ly (CI workflow) merged on 20.10. KJ-Ly had asked for it by email on 15.10.
  (the comment no longer shows on GitHub), answered in PR #3.

- Workflow on `ubuntu-26.04` and `upload-artifact@v7` (previously warnings about Node.js 20 and
  `ubuntu-latest`); the release step only runs on it after the merge into `main`.

- #32 "Open with" reuses the running instance (`singleTask` + `onNewIntent`). Consequence:
  "Back" no longer returns to the calling app. Tried on the tablet, works.
- #28 Top bar menus limited to the visible height and scrollable.
- #32 Menus in the pen strip, file browser and page settings limited the same way
  (`menuMaxHeight()`, like #28); all menus go through `ScreenDropdownMenu` in `MenuHeight.kt`.
- #29 Tablet Layout for small screens (< 600 dp wide or < 480 dp high).
- #30 Paper pattern fades out between 10 and 6 px when zooming out (previously moiré);
  the laser trail also fades after a cancelled stroke.
- Page borders: no code needed, Page Settings → Display → "Show format borders".
