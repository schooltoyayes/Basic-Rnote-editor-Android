# Third-Party Notices

## Icons (`app/src/main/java/io/github/kjly/brna/ui/icons/`)

The vector path data for the following icons is adapted from desktop Rnote's
own symbolic icon set:

- Source: https://github.com/flxzt/rnote
- Path: `crates/rnote-ui/data/icons/scalable/actions/*.svg`
- License: GPL-3.0 (see `LICENSE` at the root of this repository)
- Copyright: the Rnote contributors

**`CustomIcons.kt`** — the six `PenPicker` pen icons: Brush, Shaper,
Typewriter, Eraser, Selector, Tools (`pen-*-symbolic.svg`).

**`GeneratedIcons.kt`** — icons used in `ColorPicker` and `PenConfigStrip`:
Undo/Redo (`edit-{undo,redo}-symbolic.svg`), Stroke/Fill color pads and the
more-colors button (`stroke-color`, `fill-color`, `preferences-color-symbolic.svg`),
brush styles (`pen-brush-style-{marker,solid,textured}-symbolic.svg`), eraser
modes (`pen-eraser-{trash,split}-colliding-strokes-symbolic.svg`), the
selector polygon mode (`pen-selector-polygon-symbolic.svg`), the Tools pen's
Offset Camera, Zoom and Laser (`pen-tools-{offsetcameratool,zoomtool,laser}-symbolic.svg`),
and selection actions
(`selection-{select-all,deselect-all,duplicate,trash,invert-color,resize-lock-aspectratio}-symbolic.svg`).
The Offset Camera tool's cursor in `DrawingCanvas.kt` is its `CURSOR_PATH`, from
`crates/rnote-engine/src/pens/tools/offsetcamera.rs`.
Several of the source SVGs (stroke-color, fill-color, pen-brush-style-*)
contain a decorative background dot-texture pattern alongside the real glyph;
only the real glyph's path(s) were extracted (see `GeneratedIcons.kt`'s KDoc
for how they were distinguished).

Path coordinates were transcribed as-is from the original SVGs' `d`
attributes into Compose `ImageVector`s via `addPathNodes`.

## Launcher icon (`app/src/main/res/mipmap-*/ic_launcher_foreground.png`)

The launcher icon is a **derivative work** of desktop Rnote's own application
icon.

- Source: https://github.com/flxzt/rnote
- License: GPL-3.0 (see `LICENSE` at the root of this repository)
- Copyright: the Rnote contributors

What is whose: the notebook artwork — the bound cover, the ruled paper, the red
margin rule — is Rnote's, unchanged. The hand-drawn squiggle across the page is
an original addition by this project's author and is not part of the upstream
icon.

The composite was then adapted mechanically for Android's adaptive-icon format:
transparent margins trimmed, scaled to 56% of the 108dp foreground canvas so the
artwork survives a circular launcher mask, and rasterized once per density
bucket (mdpi through xxxhdpi). No recoloring or redrawing of the Rnote artwork
was done.

## Pen sounds (`app/src/main/res/raw/*.ogg`)

The pen sounds are desktop Rnote's own (`crates/rnote-engine/data/sounds/`),
re-encoded from WAV to Ogg Vorbis to keep the app small; nothing else was changed.
Rnote credits them in `crates/rnote-engine/data/sounds/LICENSES.md`, as below.

- **`brush.ogg`** — "Pencil, Writing, Close, A" by
  [InspectorJ](https://freesound.org/people/InspectorJ/), from
  [freesound.org](https://freesound.org/people/InspectorJ/sounds/398271/).
  License: [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/legalcode).
- **`marker_00.ogg` – `marker_14.ogg`** — License:
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/legalcode).
- **`typewriter_00.ogg` – `typewriter_29.ogg`** — cut from "Typewriter Machine" by
  [KVProds](https://freesound.org/people/KVProds/), from
  [freesound.org](https://freesound.org/people/KVProds/sounds/535891/). License:
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/legalcode).
- **`typewriter_thump.ogg`** — not listed in Rnote's sound credits; as part of
  Rnote it comes under Rnote's GPL-3.0, like this project.
- **`typewriter_bell.ogg`, `typewriter_linefeed.ogg`** — cut from "Typewriter bell &
  carriage reset" by [knufds](https://freesound.org/people/knufds/), from
  [freesound.org](https://freesound.org/people/knufds/sounds/345955/). License:
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/legalcode).

## `.rnote` parser (`storage/RnoteNativeParser.kt`)

The streaming `.rnote` reader was ported and adapted from **rnoteviewer-android**
by Intranox, an Android `.rnote` viewer.

- Source: https://github.com/Intranox/rnoteviewer-android
- File: `app/src/main/java/com/example/rnoteviewer/RnoteParser.kt`
- Author: Intranox

What was taken: the overall approach of streaming gzipped `.rnote` JSON through
Gson's `JsonReader` rather than materializing it, and the decomposition of that
work into per-element parse functions (`parseRoot`, `parseDocument`,
`parseFormatConfig`, `parseBgConfig`, `parseBrushstroke`, `parseShapestroke`,
`parseChronoComponents`, `parseColor`, `parseTransform`, and the individual
shape readers). Several shape type names in `model/RnoteNativeDocument.kt`
follow theirs as well.

What is not theirs: everything on the writing side. `RnoteNativeSerializer` has
no counterpart upstream — rnoteviewer-android only reads `.rnote` files — as
does the round-trip fidelity work that goes with it, along with this project's
rendering, export, and editing layers.

**Permission.** rnoteviewer-android publishes no LICENSE file, but its README
grants reuse in as many words:

> "If you find this project useful: Feel free to fork it, Improve it, Adapt it
> to your needs. Contributions are welcome, but there is no guarantee of review
> or updates."

Adapting the parser is exactly what that permits, and this project relies on
that grant. It is informal rather than a named license, so it does not spell out
redistribution or sublicensing terms the way MIT or GPL-3.0 would. Intranox is
welcome to get in touch if any of this misrepresents their intent.

## Fonts (`app/src/main/assets/fonts/`)

Fonts whose letters are as wide as those of Word's usual fonts, so that an
imported Word document breaks into lines and pages where Word breaks it (see
`storage/DocxImporter.kt`). They are only used to lay out imported Word
documents. The files are unchanged; each family's license is next to it.

- **Liberation Sans, Serif and Mono** 2.1.5 (for Arial, Times New Roman and
  Courier New): https://github.com/liberationfonts/liberation-fonts.
  Copyright (c) 2010 Google Corporation, with Reserved Font Arimo, Tinos and
  Cousine; copyright (c) 2012 Red Hat, Inc., with Reserved Font Name Liberation.
  License: SIL Open Font License 1.1 (`LICENSE-Liberation.txt`).
- **Carlito** (for Calibri): https://github.com/googlefonts/carlito, from
  https://github.com/google/fonts (`ofl/carlito`). Copyright 2013 The Carlito
  Project Authors, with Reserved Font Name "Carlito". License: SIL Open Font
  License 1.1 (`LICENSE-Carlito.txt`).
- **Caladea** (for Cambria): https://github.com/huertatipografica/Caladea, from
  https://github.com/google/fonts (`ofl/caladea`). Copyright 2012 The Caladea
  Project Authors. License: SIL Open Font License 1.1 (`LICENSE-Caladea.txt`).

The Open Font License lets the fonts be bundled with software under any license;
they stay under their own license, not this project's GPL-3.0.

## Scope

Beyond the icon path data and the launcher artwork described above, no assets
from the Rnote project are included in this repository, and no code from it is
copied or linked. The parser's ancestry is rnoteviewer-android, described above,
not Rnote itself.

Apart from the fonts above, which keep their own license, this repository is licensed GPL-3.0 (see `LICENSE`) so that
incorporating this GPL-3.0 material is fully compliant.
