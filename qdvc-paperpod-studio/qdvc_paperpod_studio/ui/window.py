"""The Studio window.

Plain GTK4, no libadwaita: one less dependency to install on a machine whose only
job is to prepare a payload, and the widget set is more than sufficient for what
amounts to a set of list editors and a build button.

Unlike the tablet, this side of the pair assumes a fast screen — so it is fine for
it to be busy, with forms, logs and everything visible at once.
"""

from __future__ import annotations

import threading
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk  # noqa: E402

from ..core import build as build_core
from ..core.project import Project
from . import pages


class StudioWindow(Gtk.ApplicationWindow):

    def __init__(self, app: Gtk.Application, project: Project):
        super().__init__(application=app, title="QDVC Paperpod Studio")
        self.project = project
        self.set_default_size(1180, 780)

        header = Gtk.HeaderBar()
        header.set_title_widget(Gtk.Label(label="QDVC Paperpod Studio"))
        self.set_titlebar(header)

        self.build_button = Gtk.Button(label="Build")
        self.build_button.add_css_class("suggested-action")
        self.build_button.connect("clicked", self.on_build)
        header.pack_start(self.build_button)

        self.sync_button = Gtk.Button(label="Build and sync")
        self.sync_button.connect("clicked", self.on_build_and_sync)
        header.pack_start(self.sync_button)

        save_button = Gtk.Button(label="Save project")
        save_button.connect("clicked", lambda *_: self.save())
        header.pack_end(save_button)

        self.status = Gtk.Label(label="", xalign=0)
        header.pack_end(self.status)

        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.NONE)

        sidebar = Gtk.StackSidebar()
        sidebar.set_stack(self.stack)
        sidebar.set_size_request(190, -1)

        split = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        split.append(sidebar)
        split.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL))
        self.stack.set_hexpand(True)
        split.append(self.stack)
        self.set_child(split)

        self.overview = pages.OverviewPage(self)
        self._add("overview", "Overview", self.overview)
        self._add("calendars", "Calendars", pages.CalendarsPage(self))
        self._add("tasks", "Tasks", pages.TasksPage(self))
        self._add("library", "Library", pages.LibraryPage(self))
        self._add("dwell", "Dwell", pages.DwellPage(self))
        self._add("soon", "Soon", pages.SoonPage(self))
        self._add("time", "Time", pages.TimePage(self))
        self._add("fonts", "Fonts", pages.FontsPage(self))
        self._add("modules", "Modules", pages.ModulesPage(self))
        self._add("settings", "Settings", pages.SettingsPage(self))

    def _add(self, name: str, title: str, widget: Gtk.Widget) -> None:
        scroller = Gtk.ScrolledWindow()
        scroller.set_child(widget)
        scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self.stack.add_titled(scroller, name, title)

    # -------------------------------------------------------------------- misc

    def save(self) -> None:
        try:
            path = self.project.save()
            self.set_status(f"Saved {path}")
        except Exception as exc:
            self.set_status(f"Could not save: {exc}")

    def set_status(self, text: str) -> None:
        self.status.set_text(text)

    def toast(self, text: str) -> None:
        self.set_status(text)
        self.overview.log(text)

    def refresh_pages(self) -> None:
        for child in self.stack.get_pages():
            widget = self.stack.get_child_by_name(child.get_property("name"))
            inner = widget.get_child() if isinstance(widget, Gtk.ScrolledWindow) else widget
            if hasattr(inner, "reload"):
                inner.reload()

    # ------------------------------------------------------------------- build

    def on_build(self, *_args) -> None:
        self._run_build(and_sync=False)

    def on_build_and_sync(self, *_args) -> None:
        self._run_build(and_sync=True)

    def _run_build(self, and_sync: bool) -> None:
        self.save()
        self.build_button.set_sensitive(False)
        self.sync_button.set_sensitive(False)
        self.overview.clear_log()
        self.set_status("Building\u2026")

        def work() -> None:
            try:
                report = build_core.build(
                    self.project,
                    log=lambda m: GLib.idle_add(self.overview.log, m),
                )
                if and_sync and report.ok:
                    ok, message = build_core.mirror(
                        self.project, log=lambda m: GLib.idle_add(self.overview.log, m)
                    )
                    GLib.idle_add(self.overview.log, message)
                for warning in report.warnings:
                    GLib.idle_add(self.overview.log, f"warning: {warning}")
                GLib.idle_add(self._build_finished, report.summary())
            except Exception as exc:
                GLib.idle_add(self.overview.log, f"error: {exc}")
                GLib.idle_add(self._build_finished, f"Build failed: {exc}")

        threading.Thread(target=work, daemon=True).start()

    def _build_finished(self, message: str) -> None:
        self.build_button.set_sensitive(True)
        self.sync_button.set_sensitive(True)
        self.set_status(message)
        return False

    # ------------------------------------------------------------------ ingest

    def ingest_async(self, fn, *args, **kwargs) -> None:
        """Runs an ingestion off the main loop; conversions can take a minute."""
        self.set_status("Working\u2026")

        def work() -> None:
            try:
                result = fn(self.project, *args, **kwargs)
                for warning in result.warnings:
                    GLib.idle_add(self.overview.log, f"warning: {warning}")
                GLib.idle_add(self._ingest_done, result.item.title, result.item.method)
            except Exception as exc:
                GLib.idle_add(self.overview.log, f"error: {exc}")
                GLib.idle_add(self.set_status, f"Import failed: {exc}")

        threading.Thread(target=work, daemon=True).start()

    def _ingest_done(self, title: str, method: str) -> None:
        self.save()
        self.refresh_pages()
        self.set_status(f"Imported \u201c{title}\u201d via {method}")
        return False


class StudioApp(Gtk.Application):

    def __init__(self, project_path: str | Path):
        super().__init__(application_id="com.qdvc.paperpod.studio")
        self.project_path = Path(project_path).expanduser()
        self.window: StudioWindow | None = None

    def do_activate(self) -> None:  # noqa: N802 (GTK naming)
        if self.window is None:
            project = Project.load(self.project_path)
            project.ensure_dirs()
            self.window = StudioWindow(self, project)
        self.window.present()


def run(project_path: str | Path) -> int:
    app = StudioApp(project_path)
    return app.run(None)
