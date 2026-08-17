"""Assembling the payload.

This is the iTunes half of the arrangement: all the fetching, parsing, expanding,
scheduling and rasterising happens here, once, on a machine with a fast screen.
What lands on the tablet is a directory of small flat files that need no
computation to display.

The build is idempotent and writes into a staging directory before swapping it in,
so a failed build never leaves a half-written payload for the sync helper to
mirror onto the device.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import random
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from zoneinfo import ZoneInfo

from . import astro, calendars, fonts, tasks
from .project import Project, STUDIO_VERSION


@dataclass
class BuildReport:
    payload_dir: Path | None = None
    build_id: str = ""
    counts: dict[str, int] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    log: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors and self.payload_dir is not None

    def summary(self) -> str:
        if not self.ok:
            return "Build failed: " + "; ".join(self.errors[:3])
        parts = ", ".join(f"{v} {k}" for k, v in sorted(self.counts.items()) if v)
        return f"Built {self.build_id} ({parts})"


def build(project: Project, log=None) -> BuildReport:
    report = BuildReport()

    def emit(message: str) -> None:
        report.log.append(message)
        if log:
            log(message)

    project.ensure_dirs()
    tz = _zone(project.timezone, report)
    today = dt.datetime.now(tz).date()
    start = today - dt.timedelta(days=max(0, project.window_days_back))
    end = today + dt.timedelta(days=max(1, project.window_days))

    staging = project.root / "out" / ".staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    build_id = dt.datetime.now(tz).strftime("%Y-%m-%dT%H-%M-%S") + "-" + _short_hash(project.title)
    report.build_id = build_id

    emit(f"Building {build_id}")
    emit(f"Window: {start} to {end} ({(end - start).days + 1} days)")

    occurrences, calendar_errors = calendars.collect(project.calendars, start, end, tz, emit)
    report.warnings.extend(calendar_errors)

    task_list = []
    if project.tasks.enabled and project.tasks.path.strip():
        try:
            task_list = tasks.parse_file(project.tasks.path)
            emit(f"Tasks: {len(task_list)} parsed from {project.tasks.path}")
        except Exception as exc:
            report.warnings.append(f"tasks: {exc}")
            emit(f"  ! tasks: {exc}")

    counts = {
        "days": _write_days(project, staging, occurrences, task_list, start, end, today, tz, emit),
        "weeks": _write_weeks(staging, occurrences, task_list, start, end, today, emit),
        "documents": _write_library(project, staging, report, emit),
        "dwellCards": _write_dwell(project, staging, today, emit),
        "countdowns": _write_countdowns(project, staging, today, emit),
        "zones": _write_zones(project, staging, emit),
    }

    shipped = fonts.copy_into_payload(project.fonts_dir, staging)
    counts["fontFamilies"] = len(shipped)
    if shipped:
        emit("Fonts: " + ", ".join(shipped))
    else:
        report.warnings.append(
            f"No font families found in {project.fonts_dir}. Add one directory per "
            "family (e.g. fonts/AtkinsonHyperlegible/) and rebuild."
        )

    _write_manifest(project, staging, build_id, tz)
    _write_build_info(staging, build_id, counts, tz)

    # Swap the staged tree in only once it is complete.
    if project.payload_dir.exists():
        shutil.rmtree(project.payload_dir)
    project.payload_dir.parent.mkdir(parents=True, exist_ok=True)
    staging.replace(project.payload_dir)

    report.payload_dir = project.payload_dir
    report.counts = counts
    emit(report.summary())
    return report


# ----------------------------------------------------------------------- days


def _write_days(
    project: Project,
    staging: Path,
    occurrences: list[calendars.Occurrence],
    task_list: list[tasks.Task],
    start: dt.date,
    end: dt.date,
    today: dt.date,
    tz: ZoneInfo,
    emit,
) -> int:
    """One flat file per date, with recurrence and timezones already resolved."""
    days_dir = staging / "days"
    days_dir.mkdir(parents=True, exist_ok=True)

    by_date: dict[dt.date, list[calendars.Occurrence]] = {}
    for occurrence in occurrences:
        by_date.setdefault(occurrence.date, []).append(occurrence)

    written = 0
    date = start
    while date <= end:
        events = by_date.get(date, [])
        day_tasks = tasks.for_date(task_list, date, today, project.tasks.include_undated)

        sun = {}
        moon = ""
        if project.latitude is not None and project.longitude is not None:
            rise, set_ = astro.sun_times(date, project.latitude, project.longitude, tz)
            sun = {"rise": rise, "set": set_}
            moon = astro.moon_phase(date)

        payload = {
            "date": date.isoformat(),
            "weekday": date.strftime("%A"),
            "dayNote": "",
            "sun": sun,
            "moon": moon,
            "events": [e.to_json() for e in events],
            "tasks": day_tasks,
        }
        (days_dir / f"{date.isoformat()}.json").write_text(
            json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8"
        )
        written += 1
        date += dt.timedelta(days=1)

    emit(f"Days: {written} files")
    return written


def _write_weeks(
    staging: Path,
    occurrences: list[calendars.Occurrence],
    task_list: list[tasks.Task],
    start: dt.date,
    end: dt.date,
    today: dt.date,
    emit,
) -> int:
    weeks_dir = staging / "weeks"
    weeks_dir.mkdir(parents=True, exist_ok=True)

    by_date: dict[dt.date, list[calendars.Occurrence]] = {}
    for occurrence in occurrences:
        by_date.setdefault(occurrence.date, []).append(occurrence)

    # Walk whole ISO weeks so the file always has seven days in it.
    cursor = start - dt.timedelta(days=start.weekday())
    written = 0
    while cursor <= end:
        iso_year, iso_week, _ = cursor.isocalendar()
        week_id = f"{iso_year}-W{iso_week:02d}"
        days = []
        for offset in range(7):
            date = cursor + dt.timedelta(days=offset)
            events = by_date.get(date, [])
            days.append({
                "date": date.isoformat(),
                "weekday": date.strftime("%a"),
                "taskCount": len(tasks.for_date(task_list, date, today, False)),
                # The week view is a scan, not a schedule: title and time only.
                "events": [
                    {"start": e.start, "title": e.title, "allDay": e.all_day}
                    for e in events
                ],
            })
        payload = {
            "isoWeek": week_id,
            "start": cursor.isoformat(),
            "end": (cursor + dt.timedelta(days=6)).isoformat(),
            "days": days,
        }
        (weeks_dir / f"{week_id}.json").write_text(
            json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8"
        )
        written += 1
        cursor += dt.timedelta(days=7)

    emit(f"Weeks: {written} files")
    return written


# --------------------------------------------------------------------- library


def _write_library(project: Project, staging: Path, report: BuildReport, emit) -> int:
    library_dir = staging / "library"
    library_dir.mkdir(parents=True, exist_ok=True)
    entries = []

    for item in project.documents:
        if not item.enabled:
            continue
        source = project.library_dir / item.id
        if not (source / "text.md").is_file():
            report.warnings.append(
                f"{item.id}: no text.md in the project; re-run the conversion."
            )
            continue
        shutil.copytree(source, library_dir / item.id, dirs_exist_ok=True)
        entries.append({
            "id": item.id,
            "title": item.title,
            "authors": item.authors,
            "year": item.year,
            "venue": item.venue,
            "kind": item.kind,
            "tags": item.tags,
            "words": item.words,
            # 225 wpm is a reasonable rate for dense prose, and the number is
            # there to help you choose what to start, not to be exact.
            "readingMinutes": max(1, round(item.words / 225)) if item.words else 0,
            "addedAt": item.added_at,
            "sourceUrl": item.source_url,
            "path": f"library/{item.id}",
        })

    (library_dir / "index.json").write_text(
        json.dumps({"schema": 1, "documents": entries}, indent=1, ensure_ascii=False),
        encoding="utf-8",
    )
    emit(f"Library: {len(entries)} documents")
    return len(entries)


# ----------------------------------------------------------------------- dwell


def _write_dwell(project: Project, staging: Path, today: dt.date, emit) -> int:
    """Schedules the deck months ahead so the device only opens today's card.

    The scheduler is deterministic given the project seed, which matters: a
    rebuild should not reshuffle tomorrow's card, or the thing you were looking
    forward to quietly disappears.
    """
    dwell_dir = staging / "dwell"
    assets_dir = dwell_dir / "assets"
    dwell_dir.mkdir(parents=True, exist_ok=True)
    assets_dir.mkdir(parents=True, exist_ok=True)

    settings = project.dwell_settings
    cards = []
    for item in project.dwell:
        image_ref = ""
        if item.image:
            source = (project.root / item.image).expanduser()
            if source.is_file():
                shutil.copy2(source, assets_dir / source.name)
                image_ref = f"dwell/assets/{source.name}"
        cards.append({
            "id": item.id,
            "kind": item.kind,
            "title": item.title,
            "body": item.body,
            "image": image_ref,
            "attribution": item.attribution,
            "date": item.date,
        })

    schedule: dict[str, list[str]] = {}
    if cards:
        rng = random.Random(settings.seed)
        pool = [c["id"] for c in cards]
        rng.shuffle(pool)
        # A rotating queue, reshuffled each time it empties. Everything appears
        # before anything repeats, which is what makes this feel like a deck
        # rather than a random draw that keeps showing the same photograph. The
        # gap between repeats is therefore the size of the deck, and needs no
        # separate cooldown setting.
        queue = list(pool)
        for offset in range(settings.schedule_days):
            date = (today + dt.timedelta(days=offset)).isoformat()
            chosen: list[str] = []
            for _ in range(max(1, settings.per_day)):
                if not queue:
                    queue = list(pool)
                    rng.shuffle(queue)
                chosen.append(queue.pop(0))
            schedule[date] = chosen

    (dwell_dir / "deck.json").write_text(
        json.dumps({"schema": 1, "schedule": schedule, "cards": cards}, indent=1, ensure_ascii=False),
        encoding="utf-8",
    )
    emit(f"Dwell: {len(cards)} cards, {len(schedule)} days scheduled")
    return len(cards)


# ------------------------------------------------------------------ countdowns


def _write_countdowns(project: Project, staging: Path, today: dt.date, emit) -> int:
    soon_dir = staging / "soon"
    soon_dir.mkdir(parents=True, exist_ok=True)
    items = []
    for item in project.countdowns:
        if not item.date:
            continue
        date = item.date
        if item.annual:
            try:
                parsed = dt.date.fromisoformat(item.date)
                rolled = parsed.replace(year=today.year)
                if rolled < today:
                    rolled = rolled.replace(year=today.year + 1)
                date = rolled.isoformat()
            except ValueError:
                pass
        items.append({
            "id": item.id,
            "title": item.title,
            "date": date,
            "kind": item.kind,
            "annual": item.annual,
            "note": item.note,
        })
    items.sort(key=lambda x: x["date"])
    (soon_dir / "countdowns.json").write_text(
        json.dumps({"schema": 1, "items": items}, indent=1, ensure_ascii=False),
        encoding="utf-8",
    )
    emit(f"Soon: {len(items)} countdowns")
    return len(items)


def _write_zones(project: Project, staging: Path, emit) -> int:
    time_dir = staging / "time"
    time_dir.mkdir(parents=True, exist_ok=True)
    zones = [
        {"label": z.label or z.tz, "tz": z.tz, "primary": z.primary}
        for z in project.zones
    ]
    if zones and not any(z["primary"] for z in zones):
        zones[0]["primary"] = True
    (time_dir / "zones.json").write_text(
        json.dumps({"schema": 1, "zones": zones}, indent=1, ensure_ascii=False),
        encoding="utf-8",
    )
    emit(f"Time: {len(zones)} zones")
    return len(zones)


# -------------------------------------------------------------------- manifest


def _write_manifest(project: Project, staging: Path, build_id: str, tz: ZoneInfo) -> None:
    manifest = {
        "schema": 1,
        "bundleId": build_id,
        "generatedAt": dt.datetime.now(tz).isoformat(timespec="seconds"),
        "title": project.title,
        "typography": {
            "defaultFamily": project.typography.default_family,
            "defaultBodySizeSp": project.typography.default_body_size_sp,
            "defaultLineSpacing": project.typography.default_line_spacing,
        },
        "modules": project.modules,
    }
    (staging / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )


def _write_build_info(staging: Path, build_id: str, counts: dict[str, int], tz: ZoneInfo) -> None:
    """Per-file hashes let the device's Sync screen report what actually changed."""
    files: dict[str, str] = {}
    for path in sorted(staging.rglob("*")):
        if path.is_file() and path.name != "build.json":
            rel = path.relative_to(staging).as_posix()
            files[rel] = "sha256:" + _hash_file(path)
    payload = {
        "schema": 1,
        "buildId": build_id,
        "generatedAt": dt.datetime.now(tz).isoformat(timespec="seconds"),
        "studioVersion": STUDIO_VERSION,
        "counts": counts,
        "files": files,
    }
    (staging / "build.json").write_text(
        json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8"
    )


# ------------------------------------------------------------------------ sync


def mirror(project: Project, log=None) -> tuple[bool, str]:
    """Mirrors the payload to the mounted SMB share with rsync.

    Deleting extraneous files matters: without --delete a document you removed in
    Studio would linger on the device forever.
    """
    def emit(message: str) -> None:
        if log:
            log(message)

    target = project.sync_target.strip()
    if not target:
        return False, "No sync target set. Add the mounted SMB path in Settings."
    if not project.payload_dir.is_dir():
        return False, "No payload to mirror. Build first."
    if not shutil.which("rsync"):
        return False, "rsync is not installed."

    destination = target if target.endswith("/") else target + "/"
    command = [
        "rsync", "-rtv", "--delete", "--no-perms", "--no-owner", "--no-group",
        "--modify-window=2",  # SMB timestamps are coarse; without this, endless recopying
        str(project.payload_dir) + "/", destination,
    ]
    emit("$ " + " ".join(command))
    result = subprocess.run(command, capture_output=True, text=True)
    for line in result.stdout.splitlines()[-40:]:
        emit(line)
    if result.returncode != 0:
        return False, f"rsync failed: {result.stderr.strip()[:300]}"
    return True, f"Mirrored to {destination}"


# ----------------------------------------------------------------------- utils


def _zone(name: str, report: BuildReport) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except Exception:
        report.warnings.append(f"Unknown timezone {name!r}; using UTC.")
        return ZoneInfo("UTC")


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()[:16]


def _short_hash(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()[:4]
