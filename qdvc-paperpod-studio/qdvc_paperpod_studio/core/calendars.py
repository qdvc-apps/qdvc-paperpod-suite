"""ICS ingestion and recurrence expansion.

All of the genuinely unpleasant calendar work happens here, on a machine with a
fast screen and a Python REPL: fetching, timezone resolution, RRULE expansion,
EXDATE exclusion and RECURRENCE-ID overrides. The device receives flat lists of
wall-clock times and never learns that recurrence rules exist.

Doing it this way also means a malformed rule is a desktop bug you can debug,
rather than something you discover when you glance at the tablet in a corridor.
"""

from __future__ import annotations

import datetime as dt
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo

try:
    from icalendar import Calendar
except ImportError:  # pragma: no cover - dependency reported by doctor()
    Calendar = None  # type: ignore

try:
    from dateutil.rrule import rrulestr
except ImportError:  # pragma: no cover
    rrulestr = None  # type: ignore

from .project import CalendarSource


@dataclass
class Occurrence:
    """One resolved event on one date, in the project's local timezone."""

    date: dt.date
    start: str | None       # "09:30" or None for all-day
    end: str | None
    all_day: bool
    title: str
    location: str
    calendar: str
    note: str

    def to_json(self) -> dict:
        return {
            "start": self.start,
            "end": self.end,
            "allDay": self.all_day,
            "title": self.title,
            "location": self.location,
            "calendar": self.calendar,
            "note": self.note,
        }


class CalendarError(RuntimeError):
    pass


def fetch_ics(source: str, timeout: int = 30) -> str:
    """Reads an .ics file from disk or over https."""
    src = source.strip()
    if not src:
        raise CalendarError("Empty calendar source.")
    if src.startswith("webcal://"):
        src = "https://" + src[len("webcal://"):]
    if src.startswith(("http://", "https://")):
        req = urllib.request.Request(src, headers={"User-Agent": "QDVC-Paperpod-Studio/0.1"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read().decode("utf-8", errors="replace")
    path = Path(src).expanduser()
    if not path.exists():
        raise CalendarError(f"No such calendar file: {path}")
    return path.read_text(encoding="utf-8", errors="replace")


def expand(
    ics_text: str,
    source: CalendarSource,
    start: dt.date,
    end: dt.date,
    tz: ZoneInfo,
) -> list[Occurrence]:
    """Expands a calendar into individual occurrences between start and end."""
    if Calendar is None:
        raise CalendarError(
            "The icalendar package is required. Install with: pip install icalendar python-dateutil"
        )
    if rrulestr is None:
        raise CalendarError(
            "The python-dateutil package is required. Install with: pip install python-dateutil"
        )

    cal = Calendar.from_ical(ics_text)
    window_start = dt.datetime.combine(start, dt.time.min, tzinfo=tz)
    window_end = dt.datetime.combine(end, dt.time.max, tzinfo=tz)

    # RECURRENCE-ID entries replace a single instance of a series, so collect
    # them first and let them shadow the expansion.
    overrides: dict[tuple[str, str], object] = {}
    masters: list[object] = []
    for component in cal.walk("VEVENT"):
        rid = component.get("RECURRENCE-ID")
        uid = str(component.get("UID", ""))
        if rid is not None:
            key = (uid, _stamp(_as_aware(rid.dt, tz)))
            overrides[key] = component
        else:
            masters.append(component)

    out: list[Occurrence] = []
    for component in masters:
        out.extend(_expand_component(component, source, window_start, window_end, tz, overrides))
    for (uid, _stamp_key), component in overrides.items():
        # An override may move an instance into the window from outside it.
        out.extend(_expand_component(component, source, window_start, window_end, tz, {}, single=True))

    excludes = [e.lower() for e in source.exclude if e.strip()]
    if excludes:
        out = [o for o in out if not any(x in o.title.lower() for x in excludes)]

    out.sort(key=lambda o: (o.date, "" if o.all_day else (o.start or "99:99")))
    return out


def _expand_component(
    component,
    source: CalendarSource,
    window_start: dt.datetime,
    window_end: dt.datetime,
    tz: ZoneInfo,
    overrides: dict,
    single: bool = False,
) -> list[Occurrence]:
    raw_start = component.get("DTSTART")
    if raw_start is None:
        return []
    start_value = raw_start.dt
    all_day = isinstance(start_value, dt.date) and not isinstance(start_value, dt.datetime)

    raw_end = component.get("DTEND")
    duration = None
    if raw_end is not None:
        duration = _as_aware(raw_end.dt, tz) - _as_aware(start_value, tz)
    elif component.get("DURATION") is not None:
        duration = component.get("DURATION").dt
    if duration is None:
        duration = dt.timedelta(days=1) if all_day else dt.timedelta(hours=1)

    title = str(component.get("SUMMARY", "")).strip() or "(untitled)"
    location = str(component.get("LOCATION", "")).strip()
    description = str(component.get("DESCRIPTION", "")).strip()
    uid = str(component.get("UID", ""))

    first = _as_aware(start_value, tz)
    starts: list[dt.datetime] = []

    rrule_prop = component.get("RRULE")
    if rrule_prop is not None and not single:
        rule_text = rrule_prop.to_ical().decode()
        try:
            rule = rrulestr(rule_text, dtstart=first.replace(tzinfo=first.tzinfo))
        except Exception as exc:  # a bad rule should not lose the whole calendar
            return [
                Occurrence(
                    date=first.date(),
                    start=None if all_day else first.strftime("%H:%M"),
                    end=None,
                    all_day=all_day,
                    title=title,
                    location=location,
                    calendar=source.name,
                    note=f"[unparsed recurrence: {exc}]",
                )
            ]
        # Ask for a bounded window; an unbounded rule would otherwise run forever.
        for occurrence in rule.between(
            window_start - duration, window_end, inc=True
        ):
            starts.append(_as_aware(occurrence, tz))
        for excluded in _exdates(component, tz):
            starts = [s for s in starts if _stamp(s) != _stamp(excluded)]
    else:
        starts = [first]

    results: list[Occurrence] = []
    for s in starts:
        if overrides.get((uid, _stamp(s))) is not None:
            continue  # replaced by an override handled separately
        e = s + duration
        if e < window_start or s > window_end:
            continue
        if all_day:
            # An all-day DTEND is exclusive, so a one-day event ends the next day.
            days = max(1, (e.date() - s.date()).days)
            for offset in range(days):
                d = s.date() + dt.timedelta(days=offset)
                if window_start.date() <= d <= window_end.date():
                    results.append(
                        Occurrence(d, None, None, True, title, location, source.name, "")
                    )
        else:
            results.append(
                Occurrence(
                    date=s.date(),
                    start=s.strftime("%H:%M"),
                    end=e.strftime("%H:%M"),
                    all_day=False,
                    title=title,
                    location=location,
                    calendar=source.name,
                    note=_short(description),
                )
            )
    return results


def _exdates(component, tz: ZoneInfo) -> list[dt.datetime]:
    raw = component.get("EXDATE")
    if raw is None:
        return []
    items = raw if isinstance(raw, list) else [raw]
    out: list[dt.datetime] = []
    for entry in items:
        for value in getattr(entry, "dts", []):
            out.append(_as_aware(value.dt, tz))
    return out


def _as_aware(value, tz: ZoneInfo) -> dt.datetime:
    """Normalises dates and floating times into the project timezone."""
    if isinstance(value, dt.datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=tz)
        return value.astimezone(tz)
    if isinstance(value, dt.date):
        return dt.datetime.combine(value, dt.time.min, tzinfo=tz)
    if isinstance(value, dt.timedelta):
        return dt.datetime.now(tz) + value
    raise CalendarError(f"Unhandled date value: {value!r}")


def _stamp(value: dt.datetime) -> str:
    return value.strftime("%Y%m%dT%H%M")


def _short(text: str, limit: int = 160) -> str:
    """Descriptions are usually joining links and boilerplate; keep a hint only."""
    cleaned = " ".join(text.split())
    if len(cleaned) <= limit:
        return cleaned
    return cleaned[: limit - 1].rstrip() + "\u2026"


def collect(
    sources: list[CalendarSource],
    start: dt.date,
    end: dt.date,
    tz: ZoneInfo,
    log=lambda msg: None,
) -> tuple[list[Occurrence], list[str]]:
    """Expands every enabled calendar, returning occurrences and any errors."""
    occurrences: list[Occurrence] = []
    errors: list[str] = []
    for source in sources:
        if not source.enabled or not source.source.strip():
            continue
        try:
            log(f"Fetching {source.name}\u2026")
            text = fetch_ics(source.source)
            found = expand(text, source, start, end, tz)
            occurrences.extend(found)
            log(f"  {source.name}: {len(found)} occurrences")
        except Exception as exc:
            message = f"{source.name}: {exc}"
            errors.append(message)
            log(f"  ! {message}")
    return occurrences, errors
