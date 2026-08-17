"""Studio pages.

Each page edits one slice of the project and nothing else. The recurring shape is
a heading, a list of existing items with a Remove button, and a form to add
another — repetitive on purpose, because the alternative is remembering which
page hid its controls where.
"""

from __future__ import annotations

import datetime as dt
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from ..core import fonts as fonts_core
from ..core import papers
from ..core.project import (
    CalendarSource, CountdownItem, DwellItem, ZoneItem, default_modules,
)


# ---------------------------------------------------------------------- helpers


def heading(text: str, subtitle: str = "") -> Gtk.Widget:
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
    label = Gtk.Label(label=text, xalign=0)
    label.add_css_class("title-2")
    box.append(label)
    if subtitle:
        sub = Gtk.Label(label=subtitle, xalign=0, wrap=True)
        sub.add_css_class("dim-label")
        box.append(sub)
    box.append(Gtk.Separator())
    return box


def section(text: str) -> Gtk.Widget:
    label = Gtk.Label(label=text, xalign=0)
    label.add_css_class("heading")
    label.set_margin_top(12)
    return label


def page_box() -> Gtk.Box:
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
    box.set_margin_top(16)
    box.set_margin_bottom(24)
    box.set_margin_start(18)
    box.set_margin_end(18)
    return box


def field(label: str, placeholder: str = "", text: str = "") -> tuple[Gtk.Widget, Gtk.Entry]:
    row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
    name = Gtk.Label(label=label, xalign=0)
    name.set_size_request(150, -1)
    entry = Gtk.Entry()
    entry.set_placeholder_text(placeholder)
    entry.set_text(text)
    entry.set_hexpand(True)
    row.append(name)
    row.append(entry)
    return row, entry


def frame(child: Gtk.Widget) -> Gtk.Frame:
    f = Gtk.Frame()
    child.set_margin_top(10)
    child.set_margin_bottom(10)
    child.set_margin_start(10)
    child.set_margin_end(10)
    f.set_child(child)
    return f


def item_row(title: str, subtitle: str, on_remove) -> Gtk.Widget:
    row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
    text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    label = Gtk.Label(label=title, xalign=0, wrap=True)
    label.add_css_class("heading")
    text.append(label)
    if subtitle:
        sub = Gtk.Label(label=subtitle, xalign=0, wrap=True)
        sub.add_css_class("dim-label")
        text.append(sub)
    text.set_hexpand(True)
    row.append(text)
    remove = Gtk.Button(label="Remove")
    remove.connect("clicked", lambda *_: on_remove())
    row.append(remove)
    return frame(row)


class BasePage(Gtk.Box):

    def __init__(self, window):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.window = window
        self.project = window.project
        self.container = page_box()
        self.append(self.container)
        self.reload()

    def reload(self) -> None:
        child = self.container.get_first_child()
        while child is not None:
            nxt = child.get_next_sibling()
            self.container.remove(child)
            child = nxt
        self.build()

    def build(self) -> None:  # overridden
        raise NotImplementedError

    def commit(self) -> None:
        self.window.save()
        self.reload()


# --------------------------------------------------------------------- overview


class OverviewPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Overview",
            "Studio prepares a payload; the tablet only displays it. Everything "
            "expensive happens here."
        ))

        self.container.append(section("Project"))
        info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        try:
            root = str(self.project.root)
        except RuntimeError:
            root = "(unsaved)"
        for key, value in [
            ("Directory", root),
            ("Payload", str(self.project.payload_dir)),
            ("Sync target", self.project.sync_target or "not set"),
            ("Window", f"{self.project.window_days_back} days back, "
                       f"{self.project.window_days} days ahead"),
            ("Contents", f"{len(self.project.calendars)} calendars, "
                         f"{len(self.project.documents)} documents, "
                         f"{len(self.project.dwell)} dwell cards, "
                         f"{len(self.project.countdowns)} countdowns"),
        ]:
            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
            name = Gtk.Label(label=key, xalign=0)
            name.set_size_request(120, -1)
            name.add_css_class("dim-label")
            row.append(name)
            row.append(Gtk.Label(label=value, xalign=0, wrap=True, selectable=True))
            info.append(row)
        self.container.append(frame(info))

        self.container.append(section("Tools"))
        tools = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        for name, present, why in papers.doctor():
            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
            mark = Gtk.Label(label="\u2713" if present else "\u2717", xalign=0)
            mark.set_size_request(20, -1)
            row.append(mark)
            label = Gtk.Label(label=name, xalign=0)
            label.set_size_request(130, -1)
            row.append(label)
            note = Gtk.Label(label=why if present else f"{why} \u2014 missing", xalign=0)
            note.add_css_class("dim-label")
            row.append(note)
            tools.append(row)
        self.container.append(frame(tools))

        self.container.append(section("Build log"))
        self.log_buffer = Gtk.TextBuffer()
        view = Gtk.TextView(buffer=self.log_buffer)
        view.set_editable(False)
        view.set_monospace(True)
        view.set_size_request(-1, 260)
        scroller = Gtk.ScrolledWindow()
        scroller.set_child(view)
        scroller.set_size_request(-1, 260)
        self.container.append(frame(scroller))

    def log(self, message: str) -> bool:
        if not hasattr(self, "log_buffer"):
            return False
        end = self.log_buffer.get_end_iter()
        self.log_buffer.insert(end, message + "\n")
        return False

    def clear_log(self) -> None:
        if hasattr(self, "log_buffer"):
            self.log_buffer.set_text("")


# -------------------------------------------------------------------- calendars


class CalendarsPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Calendars",
            "ICS sources are fetched and expanded here \u2014 recurrence rules, "
            "timezones and all-day quirks included \u2014 then written as one flat "
            "file per date. The tablet never sees an RRULE."
        ))

        for index, source in enumerate(self.project.calendars):
            state = "enabled" if source.enabled else "disabled"
            excluded = f", excluding {len(source.exclude)} pattern(s)" if source.exclude else ""
            self.container.append(item_row(
                source.name,
                f"{source.source}\n{state}{excluded}",
                lambda i=index: self._remove(i),
            ))

        self.container.append(section("Add a calendar"))
        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        name_row, self.name_entry = field("Name", "Work")
        source_row, self.source_entry = field(
            "ICS URL or path", "https://\u2026/basic.ics or ~/cal/work.ics"
        )
        exclude_row, self.exclude_entry = field(
            "Exclude (comma-separated)", "Lunch, Focus time"
        )
        form.append(name_row)
        form.append(source_row)
        form.append(exclude_row)
        add = Gtk.Button(label="Add calendar")
        add.connect("clicked", self._add)
        add.set_halign(Gtk.Align.START)
        form.append(add)
        self.container.append(frame(form))

    def _add(self, *_args) -> None:
        source = self.source_entry.get_text().strip()
        if not source:
            self.window.set_status("A calendar needs a URL or file path.")
            return
        self.project.calendars.append(CalendarSource(
            name=self.name_entry.get_text().strip() or "Calendar",
            source=source,
            exclude=[x.strip() for x in self.exclude_entry.get_text().split(",") if x.strip()],
        ))
        self.commit()

    def _remove(self, index: int) -> None:
        del self.project.calendars[index]
        self.commit()


class TasksPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Tasks",
            "todo.txt is read straight from disk. Only what is due reaches the "
            "payload; overdue items follow forward onto today rather than staying "
            "on the day they were missed."
        ))
        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        path_row, self.path_entry = field("todo.txt path", "~/todo/todo.txt", self.project.tasks.path)
        form.append(path_row)

        self.enabled = Gtk.CheckButton(label="Include tasks in the payload")
        self.enabled.set_active(self.project.tasks.enabled)
        form.append(self.enabled)

        self.undated = Gtk.CheckButton(label="Also show tasks with no due date on today")
        self.undated.set_active(self.project.tasks.include_undated)
        form.append(self.undated)

        save = Gtk.Button(label="Save")
        save.set_halign(Gtk.Align.START)
        save.connect("clicked", self._save)
        form.append(save)
        self.container.append(frame(form))

        if self.project.tasks.path:
            self.container.append(section("Preview"))
            preview = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            try:
                from ..core import tasks as tasks_core
                parsed = tasks_core.parse_file(self.project.tasks.path)
                today = dt.date.today()
                due_today = tasks_core.for_date(parsed, today, today, self.project.tasks.include_undated)
                preview.append(Gtk.Label(
                    label=f"{len(parsed)} tasks parsed; {len(due_today)} would show today.",
                    xalign=0,
                ))
                for task in due_today[:12]:
                    preview.append(Gtk.Label(label=f"  \u00b7 {task['title']}", xalign=0))
            except Exception as exc:
                preview.append(Gtk.Label(label=str(exc), xalign=0, wrap=True))
            self.container.append(frame(preview))

    def _save(self, *_args) -> None:
        self.project.tasks.path = self.path_entry.get_text().strip()
        self.project.tasks.enabled = self.enabled.get_active()
        self.project.tasks.include_undated = self.undated.get_active()
        self.commit()


# ---------------------------------------------------------------------- library


class LibraryPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Library",
            "Papers are converted from the best available source: arXiv LaTeX "
            "first, then JATS XML, then PDF extraction as a last resort. Equations "
            "and tables are rasterised here so the tablet needs no maths renderer."
        ))

        for index, doc in enumerate(self.project.documents):
            meta = " \u00b7 ".join(x for x in [
                doc.authors[0] if doc.authors else "",
                str(doc.year) if doc.year else "",
                doc.method,
                f"{doc.words} words" if doc.words else "",
            ] if x)
            self.container.append(item_row(doc.title, meta, lambda i=index: self._remove(i)))

        self.container.append(section("Add from arXiv"))
        arxiv_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        row, self.arxiv_entry = field("arXiv id or URL", "1706.03762")
        arxiv_box.append(row)
        note = Gtk.Label(
            label="Fetches the LaTeX source, not the PDF. This is the route that "
                  "produces real headings, real equations and figures as separate files.",
            xalign=0, wrap=True,
        )
        note.add_css_class("dim-label")
        arxiv_box.append(note)
        fetch = Gtk.Button(label="Fetch and convert")
        fetch.set_halign(Gtk.Align.START)
        fetch.connect("clicked", self._add_arxiv)
        arxiv_box.append(fetch)
        self.container.append(frame(arxiv_box))

        self.container.append(section("Add from a file"))
        file_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        title_row, self.file_title = field("Title (optional)", "inferred if blank")
        authors_row, self.file_authors = field("Authors (comma-separated)", "Vaswani, A.")
        tags_row, self.file_tags = field("Tags (comma-separated)", "transformers")
        file_box.append(title_row)
        file_box.append(authors_row)
        file_box.append(tags_row)

        buttons = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        for label, handler in [
            ("Import PDF\u2026", self._add_pdf),
            ("Import Markdown\u2026", self._add_markdown),
            ("Import JATS XML\u2026", self._add_jats),
        ]:
            button = Gtk.Button(label=label)
            button.connect("clicked", handler)
            buttons.append(button)
        file_box.append(buttons)
        self.container.append(frame(file_box))

    def _meta(self) -> tuple[str, list[str], list[str]]:
        title = self.file_title.get_text().strip()
        authors = [a.strip() for a in self.file_authors.get_text().split(",") if a.strip()]
        tags = [t.strip() for t in self.file_tags.get_text().split(",") if t.strip()]
        return title, authors, tags

    def _add_arxiv(self, *_args) -> None:
        identifier = self.arxiv_entry.get_text().strip()
        if not identifier:
            self.window.set_status("Enter an arXiv id such as 1706.03762.")
            return
        _, _, tags = self._meta()
        self.window.ingest_async(papers.ingest_arxiv, identifier, tags=tags)

    def _choose(self, patterns: list[tuple[str, str]], on_chosen) -> None:
        dialog = Gtk.FileChooserNative(
            title="Choose a file", transient_for=self.window,
            action=Gtk.FileChooserAction.OPEN,
            accept_label="Import", cancel_label="Cancel",
        )
        for name, pattern in patterns:
            f = Gtk.FileFilter()
            f.set_name(name)
            f.add_pattern(pattern)
            dialog.add_filter(f)

        def responded(d, response):
            if response == Gtk.ResponseType.ACCEPT:
                file = d.get_file()
                if file is not None:
                    on_chosen(Path(file.get_path()))
            d.destroy()

        dialog.connect("response", responded)
        dialog.show()

    def _add_pdf(self, *_args) -> None:
        title, authors, tags = self._meta()
        self._choose(
            [("PDF", "*.pdf")],
            lambda path: self.window.ingest_async(
                papers.ingest_pdf, path, title=title, authors=authors, tags=tags
            ),
        )

    def _add_markdown(self, *_args) -> None:
        title, authors, tags = self._meta()
        self._choose(
            [("Markdown", "*.md")],
            lambda path: self.window.ingest_async(
                papers.ingest_markdown, path, title=title, authors=authors, tags=tags
            ),
        )

    def _add_jats(self, *_args) -> None:
        _, _, tags = self._meta()
        self._choose(
            [("JATS XML", "*.xml")],
            lambda path: self.window.ingest_async(papers.ingest_jats, path, tags=tags),
        )

    def _remove(self, index: int) -> None:
        doc = self.project.documents[index]
        del self.project.documents[index]
        # The converted files stay on disk: a conversion is slow, and removing a
        # document from the payload should not throw the work away.
        self.window.toast(
            f"Removed {doc.title} from the payload. Its files remain in "
            f"library/{doc.id} if you want it back."
        )
        self.commit()


# ------------------------------------------------------------------------ dwell


class DwellPage(BasePage):

    def build(self) -> None:
        settings = self.project.dwell_settings
        self.container.append(heading(
            "Dwell",
            "Cards are scheduled here, months ahead and deterministically, so the "
            "tablet only ever opens today's. A rebuild will not reshuffle tomorrow."
        ))

        self.container.append(section("Scheduling"))
        schedule_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        per_row, self.per_day = field("Cards per day", "1", str(settings.per_day))
        ahead_row, self.schedule_days = field("Schedule days ahead", "120", str(settings.schedule_days))
        seed_row, self.seed = field("Shuffle seed", "20260817", str(settings.seed))
        schedule_box.append(per_row)
        schedule_box.append(ahead_row)
        schedule_box.append(seed_row)
        note = Gtk.Label(
            label="One card a day is the point. A screen that can show you twenty "
                  "is a feed, and a feed is the thing this device exists to avoid.",
            xalign=0, wrap=True,
        )
        note.add_css_class("dim-label")
        schedule_box.append(note)
        save = Gtk.Button(label="Save scheduling")
        save.set_halign(Gtk.Align.START)
        save.connect("clicked", self._save_settings)
        schedule_box.append(save)
        self.container.append(frame(schedule_box))

        self.container.append(section(f"Cards ({len(self.project.dwell)})"))
        for index, card in enumerate(self.project.dwell):
            bits = [card.kind]
            if card.image:
                bits.append(Path(card.image).name)
            if card.date:
                bits.append(card.date)
            body = card.body[:110] + ("\u2026" if len(card.body) > 110 else "")
            self.container.append(item_row(
                card.title or body or card.id,
                " \u00b7 ".join(bits) + (f"\n{body}" if card.title and body else ""),
                lambda i=index: self._remove(i),
            ))

        self.container.append(section("Add a card"))
        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)

        kind_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        kind_label = Gtk.Label(label="Kind", xalign=0)
        kind_label.set_size_request(150, -1)
        self.kind = Gtk.DropDown.new_from_strings(["photo", "quote", "idea", "note"])
        kind_row.append(kind_label)
        kind_row.append(self.kind)
        form.append(kind_row)

        title_row, self.title_entry = field("Title", "Cornwall, August 2019")
        form.append(title_row)

        body_label = Gtk.Label(label="Body", xalign=0)
        form.append(body_label)
        self.body_view = Gtk.TextView()
        self.body_view.set_wrap_mode(Gtk.WrapMode.WORD)
        self.body_view.set_size_request(-1, 100)
        form.append(frame(self.body_view))

        attribution_row, self.attribution_entry = field("Attribution", "for a quote's source")
        date_row, self.date_entry = field("Date", "2019-08-14")
        form.append(attribution_row)
        form.append(date_row)

        image_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        image_label = Gtk.Label(label="Image", xalign=0)
        image_label.set_size_request(150, -1)
        self.image_label = Gtk.Label(label="none", xalign=0, wrap=True)
        self.image_label.set_hexpand(True)
        choose = Gtk.Button(label="Choose\u2026")
        choose.connect("clicked", self._choose_image)
        image_row.append(image_label)
        image_row.append(self.image_label)
        image_row.append(choose)
        form.append(image_row)
        self._pending_image: Path | None = None

        add = Gtk.Button(label="Add card")
        add.set_halign(Gtk.Align.START)
        add.connect("clicked", self._add)
        form.append(add)
        self.container.append(frame(form))

    def _choose_image(self, *_args) -> None:
        dialog = Gtk.FileChooserNative(
            title="Choose a photo", transient_for=self.window,
            action=Gtk.FileChooserAction.OPEN,
            accept_label="Use", cancel_label="Cancel",
        )
        f = Gtk.FileFilter()
        f.set_name("Images")
        for pattern in ("*.jpg", "*.jpeg", "*.png"):
            f.add_pattern(pattern)
        dialog.add_filter(f)

        def responded(d, response):
            if response == Gtk.ResponseType.ACCEPT and d.get_file():
                self._pending_image = Path(d.get_file().get_path())
                self.image_label.set_text(self._pending_image.name)
            d.destroy()

        dialog.connect("response", responded)
        dialog.show()

    def _add(self, *_args) -> None:
        buffer = self.body_view.get_buffer()
        body = buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False).strip()
        kinds = ["photo", "quote", "idea", "note"]
        kind = kinds[self.kind.get_selected()]

        image_ref = ""
        if self._pending_image is not None:
            self.project.dwell_assets_dir.mkdir(parents=True, exist_ok=True)
            target = self.project.dwell_assets_dir / self._pending_image.name
            if self._pending_image.resolve() != target.resolve():
                import shutil
                shutil.copy2(self._pending_image, target)
            image_ref = str(target.relative_to(self.project.root))

        if not body and not image_ref and not self.title_entry.get_text().strip():
            self.window.set_status("A card needs a title, a body or an image.")
            return

        self.project.dwell.append(DwellItem(
            kind=kind,
            title=self.title_entry.get_text().strip(),
            body=body,
            image=image_ref,
            attribution=self.attribution_entry.get_text().strip(),
            date=self.date_entry.get_text().strip(),
        ))
        self._pending_image = None
        self.commit()

    def _save_settings(self, *_args) -> None:
        settings = self.project.dwell_settings
        settings.per_day = _int(self.per_day.get_text(), settings.per_day)
        settings.schedule_days = _int(self.schedule_days.get_text(), settings.schedule_days)
        settings.seed = _int(self.seed.get_text(), settings.seed)
        self.commit()

    def _remove(self, index: int) -> None:
        del self.project.dwell[index]
        self.commit()


# ------------------------------------------------------------------------- soon


class SoonPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Soon",
            "Countdowns to the dates that matter. Annual entries roll forward on "
            "every build, so a birthday never shows as having passed."
        ))

        today = dt.date.today()
        for index, item in enumerate(self.project.countdowns):
            days = ""
            try:
                target = dt.date.fromisoformat(item.date)
                if item.annual:
                    target = target.replace(year=today.year)
                    if target < today:
                        target = target.replace(year=today.year + 1)
                days = f"{(target - today).days} days"
            except ValueError:
                days = "invalid date"
            meta = " \u00b7 ".join(x for x in [item.date, item.kind, days,
                                              "annual" if item.annual else ""] if x)
            self.container.append(item_row(item.title, meta, lambda i=index: self._remove(i)))

        self.container.append(section("Add a countdown"))
        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        title_row, self.title_entry = field("Title", "Grant deadline")
        date_row, self.date_entry = field("Date (YYYY-MM-DD)", "2026-09-04")
        form.append(title_row)
        form.append(date_row)

        kind_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        kind_label = Gtk.Label(label="Kind", xalign=0)
        kind_label.set_size_request(150, -1)
        self.kind = Gtk.DropDown.new_from_strings(["deadline", "birthday", "trip", "event"])
        kind_row.append(kind_label)
        kind_row.append(self.kind)
        form.append(kind_row)

        self.annual = Gtk.CheckButton(label="Repeats every year")
        form.append(self.annual)

        note_row, self.note_entry = field("Note", "optional")
        form.append(note_row)

        add = Gtk.Button(label="Add countdown")
        add.set_halign(Gtk.Align.START)
        add.connect("clicked", self._add)
        form.append(add)
        self.container.append(frame(form))

    def _add(self, *_args) -> None:
        date = self.date_entry.get_text().strip()
        try:
            dt.date.fromisoformat(date)
        except ValueError:
            self.window.set_status("Dates must be YYYY-MM-DD.")
            return
        kinds = ["deadline", "birthday", "trip", "event"]
        self.project.countdowns.append(CountdownItem(
            title=self.title_entry.get_text().strip() or "Untitled",
            date=date,
            kind=kinds[self.kind.get_selected()],
            annual=self.annual.get_active(),
            note=self.note_entry.get_text().strip(),
        ))
        self.commit()

    def _remove(self, index: int) -> None:
        del self.project.countdowns[index]
        self.commit()


# ------------------------------------------------------------------------- time


class TimePage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Time",
            "Zones for the world clock. The device resolves IANA names itself, so "
            "daylight saving is handled without a rebuild."
        ))

        for index, zone in enumerate(self.project.zones):
            self.container.append(item_row(
                zone.label or zone.tz,
                zone.tz + (" \u00b7 primary" if zone.primary else ""),
                lambda i=index: self._remove(i),
            ))

        self.container.append(section("Add a zone"))
        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        label_row, self.label_entry = field("Label", "Tokyo")
        tz_row, self.tz_entry = field("IANA timezone", "Asia/Tokyo")
        form.append(label_row)
        form.append(tz_row)
        self.primary = Gtk.CheckButton(label="Use as the primary clock")
        form.append(self.primary)
        add = Gtk.Button(label="Add zone")
        add.set_halign(Gtk.Align.START)
        add.connect("clicked", self._add)
        form.append(add)
        self.container.append(frame(form))

    def _add(self, *_args) -> None:
        tz = self.tz_entry.get_text().strip()
        try:
            from zoneinfo import ZoneInfo
            ZoneInfo(tz)
        except Exception:
            self.window.set_status(f"Unknown timezone: {tz}")
            return
        if self.primary.get_active():
            for zone in self.project.zones:
                zone.primary = False
        self.project.zones.append(ZoneItem(
            label=self.label_entry.get_text().strip() or tz,
            tz=tz,
            primary=self.primary.get_active(),
        ))
        self.commit()

    def _remove(self, index: int) -> None:
        del self.project.zones[index]
        self.commit()


# ------------------------------------------------------------------------ fonts


class FontsPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Fonts",
            "Families in the project's fonts/ directory are bundled with the "
            "payload, and the app offers them in Settings automatically. Style is "
            "read from the filename, so no configuration file is needed."
        ))

        families = fonts_core.scan(self.project.fonts_dir)
        if not families:
            empty = Gtk.Label(
                label=f"No families found in {self.project.fonts_dir}.\n\n"
                      "Create one directory per family, for example:\n"
                      "  fonts/AtkinsonHyperlegible/atkinsonhyperlegible_regular.ttf\n"
                      "  fonts/AtkinsonHyperlegible/atkinsonhyperlegible_bold.ttf\n"
                      "  fonts/DMSans/DMSans-Italic.ttf",
                xalign=0, wrap=True, selectable=True,
            )
            self.container.append(frame(empty))
        else:
            for family in families:
                detail = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
                title = Gtk.Label(label=f"{family.name}  ({family.dir_name}/)", xalign=0)
                title.add_css_class("heading")
                detail.append(title)
                detail.append(Gtk.Label(label=family.summary(), xalign=0, wrap=True))
                for label, path in [
                    ("regular", family.regular), ("bold", family.bold),
                    ("italic", family.italic), ("bold italic", family.bold_italic),
                ]:
                    text = path.name if path else "\u2014"
                    row = Gtk.Label(label=f"    {label}: {text}", xalign=0)
                    row.add_css_class("dim-label")
                    detail.append(row)
                if family.ignored:
                    ignored = Gtk.Label(
                        label="    ignored: " + ", ".join(p.name for p in family.ignored),
                        xalign=0, wrap=True,
                    )
                    ignored.add_css_class("dim-label")
                    detail.append(ignored)
                self.container.append(frame(detail))

        self.container.append(section("Naming rules"))
        rules = Gtk.Label(
            label="A filename containing both \u201cbold\u201d and \u201citalic\u201d is the "
                  "bold italic face; \u201cbold\u201d alone is bold; \u201citalic\u201d or "
                  "\u201coblique\u201d alone is italic; anything else is the regular face. "
                  "Matching is case-insensitive, so both atkinsonhyperlegible_bold_italic.ttf "
                  "and DMSans-BoldItalic.ttf work.",
            xalign=0, wrap=True,
        )
        self.container.append(frame(rules))


# ---------------------------------------------------------------------- modules


class ModulesPage(BasePage):

    def build(self) -> None:
        self.container.append(heading(
            "Modules",
            "The rail, in order. Each row names a primitive the app knows how to "
            "render, so adding a screen is usually a row here rather than a new "
            "build of the app."
        ))

        for index, module in enumerate(self.project.modules):
            meta = f"primitive: {module.get('primitive')} \u00b7 source: {module.get('source') or '\u2014'}"
            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            title = Gtk.Label(label=f"{module.get('label')}  ({module.get('id')})", xalign=0)
            title.add_css_class("heading")
            text.append(title)
            sub = Gtk.Label(label=meta, xalign=0)
            sub.add_css_class("dim-label")
            text.append(sub)
            text.set_hexpand(True)
            row.append(text)

            up = Gtk.Button(label="\u2191")
            up.connect("clicked", lambda *_a, i=index: self._move(i, -1))
            down = Gtk.Button(label="\u2193")
            down.connect("clicked", lambda *_a, i=index: self._move(i, +1))
            remove = Gtk.Button(label="Remove")
            remove.connect("clicked", lambda *_a, i=index: self._remove(i))
            for button in (up, down, remove):
                row.append(button)
            self.container.append(frame(row))

        reset = Gtk.Button(label="Reset to defaults")
        reset.set_halign(Gtk.Align.START)
        reset.connect("clicked", self._reset)
        self.container.append(reset)

        self.container.append(section("Label length"))
        note = Gtk.Label(
            label="Labels should stay at five characters or fewer, or the rail grows "
                  "and eats the reading column on a 7\u2033 panel.",
            xalign=0, wrap=True,
        )
        note.add_css_class("dim-label")
        self.container.append(note)

    def _move(self, index: int, delta: int) -> None:
        target = index + delta
        if 0 <= target < len(self.project.modules):
            modules = self.project.modules
            modules[index], modules[target] = modules[target], modules[index]
            self.commit()

    def _remove(self, index: int) -> None:
        del self.project.modules[index]
        self.commit()

    def _reset(self, *_args) -> None:
        self.project.modules = default_modules()
        self.commit()


# --------------------------------------------------------------------- settings


class SettingsPage(BasePage):

    def build(self) -> None:
        self.container.append(heading("Settings"))

        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        title_row, self.title_entry = field("Payload title", "Paperpod", self.project.title)
        tz_row, self.tz_entry = field("Build timezone", "Europe/London", self.project.timezone)
        ahead_row, self.ahead_entry = field("Days ahead", "35", str(self.project.window_days))
        back_row, self.back_entry = field("Days back", "2", str(self.project.window_days_back))
        lat_row, self.lat_entry = field(
            "Latitude", "51.5074",
            "" if self.project.latitude is None else str(self.project.latitude)
        )
        lon_row, self.lon_entry = field(
            "Longitude", "-0.1278",
            "" if self.project.longitude is None else str(self.project.longitude)
        )
        sync_row, self.sync_entry = field(
            "Sync target", "/mnt/smb/paperpod", self.project.sync_target
        )
        for row in (title_row, tz_row, ahead_row, back_row, lat_row, lon_row, sync_row):
            form.append(row)

        note = Gtk.Label(
            label="Latitude and longitude are only used for sunrise, sunset and moon "
                  "phase, all computed offline. Leave them blank to omit them.",
            xalign=0, wrap=True,
        )
        note.add_css_class("dim-label")
        form.append(note)
        self.container.append(frame(form))

        self.container.append(section("Typography defaults"))
        type_form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        family_row, self.family_entry = field(
            "Default family", "Atkinson Hyperlegible", self.project.typography.default_family
        )
        size_row, self.size_entry = field(
            "Body size (sp)", "19", str(self.project.typography.default_body_size_sp)
        )
        spacing_row, self.spacing_entry = field(
            "Line spacing", "1.35", str(self.project.typography.default_line_spacing)
        )
        for row in (family_row, size_row, spacing_row):
            type_form.append(row)
        hint = Gtk.Label(
            label="The family name must match a directory in fonts/, prettified: "
                  "fonts/AtkinsonHyperlegible/ becomes \u201cAtkinson Hyperlegible\u201d. "
                  "These are defaults only; the device can override them.",
            xalign=0, wrap=True,
        )
        hint.add_css_class("dim-label")
        type_form.append(hint)
        self.container.append(frame(type_form))

        save = Gtk.Button(label="Save settings")
        save.set_halign(Gtk.Align.START)
        save.connect("clicked", self._save)
        self.container.append(save)

    def _save(self, *_args) -> None:
        p = self.project
        p.title = self.title_entry.get_text().strip() or "Paperpod"
        p.timezone = self.tz_entry.get_text().strip() or "UTC"
        p.window_days = _int(self.ahead_entry.get_text(), p.window_days)
        p.window_days_back = _int(self.back_entry.get_text(), p.window_days_back)
        p.latitude = _float_or_none(self.lat_entry.get_text())
        p.longitude = _float_or_none(self.lon_entry.get_text())
        p.sync_target = self.sync_entry.get_text().strip()
        p.typography.default_family = self.family_entry.get_text().strip()
        p.typography.default_body_size_sp = _int(
            self.size_entry.get_text(), p.typography.default_body_size_sp
        )
        try:
            p.typography.default_line_spacing = float(self.spacing_entry.get_text())
        except ValueError:
            pass
        self.commit()


def _int(text: str, fallback: int) -> int:
    try:
        return int(text.strip())
    except (ValueError, AttributeError):
        return fallback


def _float_or_none(text: str) -> float | None:
    text = (text or "").strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None
