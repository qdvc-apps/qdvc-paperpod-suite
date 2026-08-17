"""todo.txt parsing.

The existing workflow already lives in a todo.txt file, so Studio reads it rather
than asking for the same information twice. Only what is relevant to a given day
reaches the payload: a daily summary that lists two hundred someday-maybe items
is not a summary.
"""

from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass
from pathlib import Path

PRIORITY = re.compile(r"^\(([A-Z])\)\s+")
DUE = re.compile(r"\bdue:(\d{4}-\d{2}-\d{2})\b")
THRESHOLD = re.compile(r"\bt:(\d{4}-\d{2}-\d{2})\b")
PROJECT = re.compile(r"(?:^|\s)\+(\S+)")
CONTEXT = re.compile(r"(?:^|\s)@(\S+)")
CREATED = re.compile(r"^(\d{4}-\d{2}-\d{2})\s+")


@dataclass
class Task:
    title: str
    priority: str = ""
    project: str = ""
    context: str = ""
    due: dt.date | None = None
    threshold: dt.date | None = None
    done: bool = False

    def to_json(self, today: dt.date) -> dict:
        return {
            "title": self.title,
            "project": self.project,
            "priority": self.priority,
            "due": self.due.isoformat() if self.due else None,
            "overdue": bool(self.due and self.due < today),
        }


def parse_file(path: str | Path) -> list[Task]:
    p = Path(path).expanduser()
    if not p.exists():
        raise FileNotFoundError(f"No todo.txt at {p}")
    return [t for t in (parse_line(line) for line in p.read_text(encoding="utf-8").splitlines()) if t]


def parse_line(line: str) -> Task | None:
    raw = line.strip()
    if not raw:
        return None

    done = False
    if raw.startswith("x "):
        done = True
        raw = raw[2:].strip()
        raw = CREATED.sub("", raw, count=1)  # completion date

    priority = ""
    m = PRIORITY.match(raw)
    if m:
        priority = m.group(1)
        raw = raw[m.end():]

    raw = CREATED.sub("", raw, count=1)

    due = _date(DUE, raw)
    threshold = _date(THRESHOLD, raw)
    project = _first(PROJECT, raw)
    context = _first(CONTEXT, raw)

    title = DUE.sub("", raw)
    title = THRESHOLD.sub("", title)
    title = re.sub(r"\b\w+:\S+", "", title)  # remaining key:value metadata
    title = PROJECT.sub(" ", title)
    title = CONTEXT.sub(" ", title)
    title = " ".join(title.split())

    if not title:
        return None
    return Task(title, priority, project, context, due, threshold, done)


def _date(pattern: re.Pattern, text: str) -> dt.date | None:
    m = pattern.search(text)
    if not m:
        return None
    try:
        return dt.date.fromisoformat(m.group(1))
    except ValueError:
        return None


def _first(pattern: re.Pattern, text: str) -> str:
    m = pattern.search(text)
    return m.group(1) if m else ""


def for_date(tasks: list[Task], date: dt.date, today: dt.date, include_undated: bool) -> list[dict]:
    """Selects the tasks worth showing on a given day.

    Anything overdue follows you forward onto today rather than staying on the
    day it was missed, because a summary that hides a missed deadline is worse
    than no summary.
    """
    out: list[dict] = []
    for t in tasks:
        if t.done:
            continue
        if t.due is None:
            if include_undated and date == today:
                out.append(t.to_json(today))
            continue
        if t.due == date:
            out.append(t.to_json(today))
        elif t.due < today and date == today:
            out.append(t.to_json(today))
    out.sort(key=lambda x: (not x["overdue"], x["priority"] or "Z", x["title"]))
    return out
