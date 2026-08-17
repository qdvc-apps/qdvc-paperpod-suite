# QDVC Paperpod

A two-part suite for a 7" Kaleido 3 e-paper Android tablet: a reading and
organisation app for the device, and a desktop companion that prepares everything
it displays.

```
qdvc-paperpod/
  PAYLOAD-SPEC.md            the contract between the two halves
  qdvc-paperpod-android/     the tablet app (Kotlin, plain Views)
  qdvc-paperpod-studio/      the desktop app (Python, GTK4)
```

## The idea

The tablet is a beautiful thing to read on and a poor thing to compute on. Panel
refreshes cost hundreds of milliseconds, so every interaction that assumes 60fps —
panning a PDF, scrolling a feed, drilling into a file tree — feels broken.

So the work is split along that seam. **Studio does everything expensive, once, on
a machine with a fast screen**: fetching calendars, expanding recurrence rules,
converting papers, rasterising equations, scheduling cards, computing sunrise.
**Paperpod renders pre-baked artefacts and nothing else.** The device parses no
ICS, evaluates no recurrence rule, and lays out no equation.

Two consequences fall out of that, and both are the point:

- The tablet is correct even when it has not synced for a fortnight, because
  every day in the window is already a flat file on disk.
- A malformed calendar rule is a desktop bug you can debug in a REPL, rather than
  something you discover while glancing at the tablet in a corridor.

Sync is one-way. The app captures no data at all — no notes, no ticked checkboxes,
no reading state that needs to travel back. That constraint is why there is no
conflict resolution anywhere in the codebase.

## The four problems this solves

**A4 papers need constant panning.** Studio reflows papers to Markdown from the
best available source — arXiv LaTeX first, then JATS XML, then PDF text extraction
as a last resort — and the reader paginates them. A page turn is one refresh; a
drag is a stream of refreshes the panel cannot deliver. There is no scrolling in
the reader at all.

**Full calendar and task sync on the tablet is overkill.** Studio precomputes one
`days/YYYY-MM-DD.json` per date and one file per ISO week. The Day screen is a
render, not a query.

**No pipeline for mindful consumption.** The Dwell module shows a small number of
scheduled cards — photos, quotes, ideas, notes — and then says it is done. It
cannot show you a hundred things, and that is the feature.

**Multi-app workflows assume a fast screen.** Everything lives in one app behind a
permanently visible icon rail. No drawer, no gestures, no file browsing.

## Getting started

### Studio (desktop)

Needs Python 3.11+, GTK4, and PyGObject from your distribution's packages:

```bash
sudo apt install python3-gi gir1.2-gtk-4.0 pandoc poppler-utils rsync
cd qdvc-paperpod-studio
pip install -e .
paperpod-studio doctor          # report which optional tools are present
paperpod-studio                 # open the window
```

`pandoc` does the LaTeX and JATS conversion, `poppler-utils` provides the PDF
fallback and vector-figure rasterising, and `rsync` mirrors the payload. Studio
runs without them but will refuse the relevant conversions with a clear reason.

A project lives in a directory (default `~/Paperpod`) holding `project.json`,
your ingested `library/`, `dwell/assets/`, the `fonts/` you want bundled, and the
built payload under `out/payload`.

The CLI is what makes automation possible — a systemd timer running this each
morning is what keeps the tablet worth picking up:

```bash
paperpod-studio build --sync
paperpod-studio add-paper 1706.03762 --tags transformers
```

### Paperpod (tablet)

```bash
cd qdvc-paperpod-android
gradle wrapper          # or just open the directory in Android Studio
./gradlew assembleDebug
```

No `gradle-wrapper.jar` is committed, so generate the wrapper once or let Android
Studio do it.

Then: sideload, point your existing SMB sync helper at `/sdcard/QDVC-Paperpod`,
and grant all-files access from the Sync screen. The payload is a plain mirrored
directory that scoped storage cannot reach, so the app asks for
`MANAGE_EXTERNAL_STORAGE` and explains why at the point of asking.

## The rail

| Module | Primitive | What it shows |
| --- | --- | --- |
| Day | `agenda` | Today's events, tasks, sun times, with ±1 day navigation |
| Week | `week` | Seven rows, today's date cell inverted |
| Read | `library` | Reading list, filter box, paginated reader |
| Time | `clock` | World clock, redrawn on the minute |
| Soon | `countdown` | Days remaining, nearest first |
| Dwell | `deck` | Today's card, one at a time |
| Sync | `sync` | Reload, payload status, honest diagnostics |

Modules are declared in the payload manifest and dispatched by `primitive`, so a
new screen is usually a manifest row plus a Studio page rather than a new APK.
An unrecognised primitive shows a screen saying so, so a newer Studio cannot brick
an older app.

## Design constraints, and why

**Pure black on pure white, no greys.** Kaleido's colour filter array already
costs contrast; grey text is what the panel renders worst. Structure comes from
outlines and rules. Colour is semantic only — red for overdue, and it appears on
fills and bars rather than small text.

**Nothing animates.** Window animations, transitions, ripples and overscroll glow
are all disabled in the theme. Every animated pixel is a wasted refresh.

**Inverted blocks for emphasis.** A solid black fill is the one emphasis this
display renders unambiguously, so selection, "today" and "imminent" all use it.

**Pagination everywhere, scrolling nowhere it matters.** The reader has no scroll
view. Tapping the right 30% advances, the left 30% goes back, the centre toggles
chrome. Hardware volume and page keys work too, which turns a page without a
finger crossing the panel.

**A periodic full refresh.** Partial refreshes accumulate ghosting. The reader
blanks the panel every N page turns, configurable, because without a vendor SDK
this is the only lever available. Vendor SDKs are deliberately avoided.

**Fonts ship in the payload.** One directory per family under `fonts/`, with
style read from the filename. Adding a typeface is a sync, not a release.

## What is deliberately absent

No handwriting or ink capture — the stock note-taking app has the vendor's
low-latency stylus path, and reimplementing it without their SDK would be worse in
every respect. No outbox or write-back. No web browser. No notifications.

## Status

Version 0.1.0. Payload schema 1. The Studio pipeline is tested end to end; the
Android app is written but not yet run on hardware, so expect the first session
on the device to shake out layout and refresh behaviour.
