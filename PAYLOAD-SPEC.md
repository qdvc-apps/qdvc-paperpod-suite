# QDVC Paperpod — Payload Specification

Schema version: **1**

The payload is the only interface between Studio (desktop) and Paperpod (Android).
Studio writes it; the sync helper mirrors it; Paperpod reads it. The Android app
never computes anything it could have been handed.

Sync is **one-way** (desktop → device). The device writes nothing into the payload.

## Directory layout

```
paperpod/                        <- payload root, mirrored to the tablet
  manifest.json                  <- module list + typography defaults (required)
  build.json                     <- build id, timestamp, per-file hashes (required)
  days/2026-08-17.json           <- one file per date, pre-resolved
  weeks/2026-W34.json            <- one file per ISO week
  library/index.json             <- reading list
  library/<docId>/doc.json       <- per-document metadata
  library/<docId>/text.md        <- reflowed body text
  library/<docId>/assets/*       <- figures (png/jpg) and equations (png)
  dwell/deck.json                <- dated card schedule + card bodies
  dwell/assets/*                 <- photos
  soon/countdowns.json
  time/zones.json
  fonts/<FamilyDir>/*.ttf|otf    <- bundled typefaces, discovered at runtime
```

Every path inside the payload is POSIX-style and relative to the payload root.

## manifest.json

```json
{
  "schema": 1,
  "bundleId": "2026-08-17T06-00-00Z-a91f",
  "generatedAt": "2026-08-17T06:00:00Z",
  "title": "Paperpod",
  "typography": {
    "defaultFamily": "Atkinson Hyperlegible",
    "defaultBodySizeSp": 19,
    "defaultLineSpacing": 1.35
  },
  "modules": [
    { "id": "day",   "label": "Day",   "icon": "day",   "primitive": "agenda",    "source": "days" },
    { "id": "week",  "label": "Week",  "icon": "week",  "primitive": "week",      "source": "weeks" },
    { "id": "read",  "label": "Read",  "icon": "read",  "primitive": "library",   "source": "library/index.json" },
    { "id": "time",  "label": "Time",  "icon": "time",  "primitive": "clock",     "source": "time/zones.json" },
    { "id": "soon",  "label": "Soon",  "icon": "soon",  "primitive": "countdown", "source": "soon/countdowns.json" },
    { "id": "dwell", "label": "Dwell", "icon": "dwell", "primitive": "deck",      "source": "dwell/deck.json" },
    { "id": "sync",  "label": "Sync",  "icon": "sync",  "primitive": "sync",      "source": null }
  ]
}
```

`label` is what appears in the rail and should stay **≤ 5 characters** so the rail
stays narrow. `icon` names a drawable compiled into the app; unknown names fall
back to a generic glyph. `primitive` selects the renderer — adding a module means
adding a manifest entry, not Kotlin, as long as an existing primitive fits.

Primitives implemented in schema 1: `agenda`, `week`, `library`, `clock`,
`countdown`, `deck`, `sync`.

## build.json

```json
{
  "schema": 1,
  "buildId": "2026-08-17T06-00-00Z-a91f",
  "generatedAt": "2026-08-17T06:00:00Z",
  "studioVersion": "0.1.0",
  "counts": { "days": 30, "weeks": 5, "documents": 12, "dwellCards": 90 },
  "files": { "days/2026-08-17.json": "sha256:…" }
}
```

`files` lets the Sync screen report what arrived since the last successful read.

## days/YYYY-MM-DD.json

Fully resolved: no recurrence rules, no timezone maths, no floating times. All
clock times are local wall-clock strings in the payload's build timezone.

```json
{
  "date": "2026-08-17",
  "weekday": "Monday",
  "dayNote": "Deep work day — no meetings before 11.",
  "sun": { "rise": "05:57", "set": "20:31" },
  "moon": "Waxing gibbous",
  "events": [
    { "start": "09:30", "end": "10:00", "allDay": false,
      "title": "Reading group: attention sinks",
      "location": "Zoom", "calendar": "Work", "note": "" }
  ],
  "tasks": [
    { "title": "Send referee report", "project": "reviews",
      "priority": "A", "due": "2026-08-17", "overdue": false }
  ]
}
```

A missing day file is not an error: the app shows a quiet empty state.

## weeks/YYYY-Www.json

```json
{
  "isoWeek": "2026-W34",
  "start": "2026-08-17",
  "end": "2026-08-23",
  "days": [
    { "date": "2026-08-17", "weekday": "Mon", "taskCount": 3,
      "events": [ { "start": "09:30", "title": "Reading group", "allDay": false } ] }
  ]
}
```

## library/index.json

```json
{
  "schema": 1,
  "documents": [
    { "id": "vaswani-2017-attention", "title": "Attention Is All You Need",
      "authors": ["Vaswani, A.", "Shazeer, N."], "year": 2017,
      "venue": "NeurIPS", "kind": "paper", "tags": ["transformers"],
      "words": 7400, "readingMinutes": 33, "addedAt": "2026-08-10",
      "sourceUrl": "https://arxiv.org/abs/1706.03762",
      "path": "library/vaswani-2017-attention" }
  ]
}
```

`kind` ∈ `paper` | `book` | `article` | `note`.

## library/&lt;docId&gt;/doc.json

```json
{
  "id": "vaswani-2017-attention",
  "title": "Attention Is All You Need",
  "authors": ["Vaswani, A."],
  "abstract": "…",
  "text": "text.md",
  "provenance": { "method": "arxiv-latex", "convertedAt": "2026-08-10T12:00:00Z" }
}
```

## text.md — the reader dialect

Studio must emit only what the reader renders. **No maths markup and no tables
reach the device**: Studio rasterises both to PNG and emits them as images.

Supported: ATX headings `#`–`######`; paragraphs; `>` blockquote; fenced code;
`-`/`*`/`1.` lists (one nesting level); `---` horizontal rule; images
`![caption](assets/fig1.png)`; inline `**bold**`, `*italic*`, `` `code` ``,
`[text](url)` (rendered as text, URL dropped).

Figures are emitted as their own block-level image with the caption in the alt
text. The reader shows them scaled to the text column and opens them full-screen
on tap, so a figure is a deliberate stop rather than something you pan into.

## dwell/deck.json

Scheduling is precomputed months ahead so the device only opens today's card.

```json
{
  "schema": 1,
  "schedule": { "2026-08-17": ["card-0007"], "2026-08-18": ["card-0031"] },
  "cards": [
    { "id": "card-0007", "kind": "photo",
      "title": "Cornwall, August 2019",
      "body": "The morning we walked to the lighthouse before anyone else was awake.",
      "image": "dwell/assets/cornwall-2019.jpg",
      "attribution": "", "date": "2019-08-14" }
  ]
}
```

`kind` ∈ `photo` | `quote` | `idea` | `note`. A quote uses `body` for the text
and `attribution` for the source. If today has no scheduled card the app picks
deterministically from the deck using the date as seed, so the module is never
empty.

## soon/countdowns.json

```json
{
  "schema": 1,
  "items": [
    { "id": "nia-birthday", "title": "Nia's birthday", "date": "2026-09-04",
      "kind": "birthday", "annual": true, "note": "" }
  ]
}
```

`kind` ∈ `birthday` | `deadline` | `trip` | `event`. `annual: true` means Studio
rolls the date forward each build. The device sorts by days remaining.

## time/zones.json

```json
{
  "schema": 1,
  "zones": [
    { "label": "Home", "tz": "Europe/London", "primary": true },
    { "label": "Tokyo", "tz": "Asia/Tokyo", "primary": false }
  ]
}
```

`tz` is an IANA identifier resolved on-device by `java.time.ZoneId`.

## fonts/

One directory per family; the directory name is the display name (camel case is
split, so `AtkinsonHyperlegible` shows as "Atkinson Hyperlegible"). Style comes
from the filename, matched case-insensitively:

| filename contains | style |
| --- | --- |
| bold **and** italic (or `bolditalic`) | bold italic |
| bold, not italic | bold |
| italic or oblique, not bold | italic |
| neither | regular |

A family with no regular face is skipped. Both `.ttf` and `.otf` are accepted.
