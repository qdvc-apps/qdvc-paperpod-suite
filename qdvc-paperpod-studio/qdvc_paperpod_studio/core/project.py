"""The Studio project: everything needed to build a payload, in one JSON file.

A project directory looks like this::

    ~/Paperpod/
      project.json          <- this model
      library/<docId>/      <- ingested documents (doc.json, text.md, assets/)
      dwell/assets/         <- photos
      fonts/<Family>/       <- typefaces to bundle
      out/payload/          <- build output, mirrored to the device

Keeping the project as small files rather than a database is deliberate: rsync
stays incremental, git can version it, and when something looks wrong you can
open the offending file and read it.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any

SCHEMA = 1
STUDIO_VERSION = "0.1.0"


def new_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


@dataclass
class CalendarSource:
    name: str = "Calendar"
    # An https URL (webcal is rewritten) or a local .ics path.
    source: str = ""
    enabled: bool = True
    # Events matching these substrings are dropped, for the standing noise
    # every shared calendar accumulates.
    exclude: list[str] = field(default_factory=list)


@dataclass
class TaskSource:
    # todo.txt is the only format supported so far; it is a line-per-task file,
    # which makes it trivially parseable and hard to corrupt.
    path: str = ""
    enabled: bool = False
    # Tasks with no due date are noise on a daily summary, so they are opt-in.
    include_undated: bool = False


@dataclass
class CountdownItem:
    id: str = field(default_factory=lambda: new_id("cd"))
    title: str = ""
    date: str = ""          # ISO date
    kind: str = "event"     # birthday | deadline | trip | event
    annual: bool = False
    note: str = ""


@dataclass
class ZoneItem:
    label: str = ""
    tz: str = "UTC"
    primary: bool = False


@dataclass
class DwellItem:
    id: str = field(default_factory=lambda: new_id("card"))
    kind: str = "note"      # photo | quote | idea | note
    title: str = ""
    body: str = ""
    # Path relative to the project directory, e.g. dwell/assets/cornwall.jpg
    image: str = ""
    attribution: str = ""
    date: str = ""
    tags: list[str] = field(default_factory=list)
    # Set by the scheduler so cards rotate rather than repeat.
    last_scheduled: str = ""


@dataclass
class DocumentItem:
    id: str = ""
    title: str = ""
    authors: list[str] = field(default_factory=list)
    year: int | None = None
    venue: str = ""
    kind: str = "paper"
    tags: list[str] = field(default_factory=list)
    words: int = 0
    added_at: str = ""
    source_url: str = ""
    # Conversion route actually used: arxiv-latex | jats | pdf-text | markdown
    method: str = ""
    enabled: bool = True


@dataclass
class Typography:
    default_family: str = "Atkinson Hyperlegible"
    default_body_size_sp: int = 19
    default_line_spacing: float = 1.35


@dataclass
class DwellSettings:
    # Cards shown per day. One is the point; more is a feed.
    per_day: int = 1
    # How far ahead to schedule, so the device works without a recent sync.
    schedule_days: int = 120
    # Changing the seed reshuffles the whole deck; leaving it alone means a
    # rebuild will not move tomorrow's card.
    seed: int = 20260817


@dataclass
class Project:
    schema: int = SCHEMA
    title: str = "Paperpod"
    # Rolling window of pre-resolved day files.
    window_days: int = 35
    window_days_back: int = 2
    timezone: str = "Europe/London"
    # Used for sunrise and sunset; blank disables them.
    latitude: float | None = None
    longitude: float | None = None

    typography: Typography = field(default_factory=Typography)
    dwell_settings: DwellSettings = field(default_factory=DwellSettings)

    calendars: list[CalendarSource] = field(default_factory=list)
    tasks: TaskSource = field(default_factory=TaskSource)
    countdowns: list[CountdownItem] = field(default_factory=list)
    zones: list[ZoneItem] = field(default_factory=list)
    dwell: list[DwellItem] = field(default_factory=list)
    documents: list[DocumentItem] = field(default_factory=list)

    # Where the sync helper's SMB share is mounted locally. Blank means
    # "build only"; the mirror step is then somebody else's job.
    sync_target: str = ""
    # Module rows written into the manifest, in rail order.
    modules: list[dict[str, Any]] = field(default_factory=lambda: default_modules())

    _path: Path | None = field(default=None, repr=False, compare=False)

    # ------------------------------------------------------------------ paths

    @property
    def root(self) -> Path:
        if self._path is None:
            raise RuntimeError("Project has no path yet; save it first.")
        return self._path.parent

    @property
    def library_dir(self) -> Path:
        return self.root / "library"

    @property
    def dwell_assets_dir(self) -> Path:
        return self.root / "dwell" / "assets"

    @property
    def fonts_dir(self) -> Path:
        return self.root / "fonts"

    @property
    def payload_dir(self) -> Path:
        return self.root / "out" / "payload"

    def ensure_dirs(self) -> None:
        for d in (self.library_dir, self.dwell_assets_dir, self.fonts_dir, self.payload_dir):
            d.mkdir(parents=True, exist_ok=True)

    # ---------------------------------------------------------------- load/save

    @classmethod
    def load(cls, path: str | Path) -> "Project":
        # Resolve by suffix, not by what happens to exist yet. Testing is_dir()
        # here means the very first run — where the project directory has not been
        # created — mistakes the directory for the JSON file and scatters library/,
        # fonts/ and out/ into its parent.
        path = _project_file(path)
        if not path.exists():
            project = cls()
            project._path = path
            return project
        raw = json.loads(path.read_text(encoding="utf-8"))
        project = cls._from_dict(raw)
        project._path = path
        return project

    @classmethod
    def _from_dict(cls, raw: dict[str, Any]) -> "Project":
        p = cls()
        p.schema = raw.get("schema", SCHEMA)
        p.title = raw.get("title", p.title)
        p.window_days = raw.get("window_days", p.window_days)
        p.window_days_back = raw.get("window_days_back", p.window_days_back)
        p.timezone = raw.get("timezone", p.timezone)
        p.latitude = raw.get("latitude")
        p.longitude = raw.get("longitude")
        p.sync_target = raw.get("sync_target", "")
        p.modules = raw.get("modules") or default_modules()

        t = raw.get("typography", {})
        p.typography = Typography(
            default_family=t.get("default_family", p.typography.default_family),
            default_body_size_sp=t.get("default_body_size_sp", p.typography.default_body_size_sp),
            default_line_spacing=t.get("default_line_spacing", p.typography.default_line_spacing),
        )

        d = raw.get("dwell_settings", {})
        p.dwell_settings = DwellSettings(
            per_day=d.get("per_day", 1),
            schedule_days=d.get("schedule_days", 120),
            seed=d.get("seed", 20260817),
        )

        p.calendars = [CalendarSource(**_pick(c, CalendarSource)) for c in raw.get("calendars", [])]
        p.tasks = TaskSource(**_pick(raw.get("tasks", {}), TaskSource))
        p.countdowns = [CountdownItem(**_pick(c, CountdownItem)) for c in raw.get("countdowns", [])]
        p.zones = [ZoneItem(**_pick(z, ZoneItem)) for z in raw.get("zones", [])]
        p.dwell = [DwellItem(**_pick(x, DwellItem)) for x in raw.get("dwell", [])]
        p.documents = [DocumentItem(**_pick(x, DocumentItem)) for x in raw.get("documents", [])]
        return p

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "title": self.title,
            "window_days": self.window_days,
            "window_days_back": self.window_days_back,
            "timezone": self.timezone,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "sync_target": self.sync_target,
            "modules": self.modules,
            "typography": asdict(self.typography),
            "dwell_settings": asdict(self.dwell_settings),
            "calendars": [asdict(c) for c in self.calendars],
            "tasks": asdict(self.tasks),
            "countdowns": [asdict(c) for c in self.countdowns],
            "zones": [asdict(z) for z in self.zones],
            "dwell": [asdict(x) for x in self.dwell],
            "documents": [asdict(x) for x in self.documents],
        }

    def save(self, path: str | Path | None = None) -> Path:
        target = _project_file(path) if path else self._path
        if target is None:
            raise RuntimeError("No path to save to.")
        target.parent.mkdir(parents=True, exist_ok=True)
        # Write via a temporary file so an interrupted save cannot leave a
        # half-written project behind.
        tmp = target.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(self.to_dict(), indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(target)
        self._path = target
        return target


def _project_file(path: str | Path) -> Path:
    """Accepts either a project directory or the project.json inside it."""
    resolved = Path(path).expanduser()
    if resolved.suffix.lower() == ".json":
        return resolved
    return resolved / "project.json"


def _pick(raw: dict[str, Any], cls: type) -> dict[str, Any]:
    """Drops unknown keys so an older Studio can open a newer project file."""
    fields = {f for f in cls.__dataclass_fields__ if not f.startswith("_")}
    return {k: v for k, v in raw.items() if k in fields}


def default_modules() -> list[dict[str, Any]]:
    """The rail, in order. Labels stay short so the bar stays narrow."""
    return [
        {"id": "day", "label": "Day", "icon": "day", "primitive": "agenda", "source": "days"},
        {"id": "week", "label": "Week", "icon": "week", "primitive": "week", "source": "weeks"},
        {"id": "read", "label": "Read", "icon": "read", "primitive": "library",
         "source": "library/index.json"},
        {"id": "time", "label": "Time", "icon": "time", "primitive": "clock",
         "source": "time/zones.json"},
        {"id": "soon", "label": "Soon", "icon": "soon", "primitive": "countdown",
         "source": "soon/countdowns.json"},
        {"id": "dwell", "label": "Dwell", "icon": "dwell", "primitive": "deck",
         "source": "dwell/deck.json"},
        {"id": "sync", "label": "Sync", "icon": "sync", "primitive": "sync", "source": None},
    ]
