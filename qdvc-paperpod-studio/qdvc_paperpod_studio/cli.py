"""Command line interface.

The GUI is for curating; the CLI is for the parts that should happen without you.
A timer that runs `paperpod-studio build --sync` every morning is what makes the
tablet worth picking up: the calendar window rolls forward, the day files are
current, and today's Dwell card is already there.

    paperpod-studio                       open the window
    paperpod-studio build --sync          rebuild and mirror to the share
    paperpod-studio add-paper 1706.03762  fetch from arXiv and convert
    paperpod-studio doctor                report which tools are installed
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

DEFAULT_PROJECT = "~/Paperpod"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="paperpod-studio",
        description="Prepare payloads for the QDVC Paperpod tablet app.",
    )
    parser.add_argument(
        "--project", "-p", default=DEFAULT_PROJECT,
        help=f"project directory (default: {DEFAULT_PROJECT})",
    )
    sub = parser.add_subparsers(dest="command")

    build_cmd = sub.add_parser("build", help="build the payload")
    build_cmd.add_argument("--sync", action="store_true", help="mirror to the sync target afterwards")

    sub.add_parser("sync", help="mirror an existing payload to the sync target")
    sub.add_parser("doctor", help="report which external tools are available")
    sub.add_parser("gui", help="open the Studio window (the default)")

    paper = sub.add_parser("add-paper", help="ingest a paper")
    paper.add_argument("source", help="arXiv id or URL, or a path to a PDF, .md or .xml file")
    paper.add_argument("--title", default="")
    paper.add_argument("--authors", default="", help="comma-separated")
    paper.add_argument("--tags", default="", help="comma-separated")

    dwell = sub.add_parser("add-dwell", help="add a Dwell card")
    dwell.add_argument("--kind", default="note", choices=["photo", "quote", "idea", "note"])
    dwell.add_argument("--title", default="")
    dwell.add_argument("--body", default="")
    dwell.add_argument("--image", default="", help="path to a photo; copied into the project")
    dwell.add_argument("--attribution", default="")
    dwell.add_argument("--date", default="")

    args = parser.parse_args(argv)
    command = args.command or "gui"

    if command == "gui":
        from .ui.window import run
        return run(args.project)

    from .core.project import Project

    project = Project.load(args.project)
    project.ensure_dirs()

    if command == "doctor":
        return _doctor()
    if command == "build":
        return _build(project, sync=args.sync)
    if command == "sync":
        return _sync(project)
    if command == "add-paper":
        return _add_paper(project, args)
    if command == "add-dwell":
        return _add_dwell(project, args)

    parser.print_help()
    return 1


def _doctor() -> int:
    from .core import papers

    missing = 0
    for name, present, why in papers.doctor():
        mark = "ok  " if present else "MISS"
        print(f"[{mark}] {name:<16} {why}")
        if not present:
            missing += 1
    if missing:
        print(f"\n{missing} tool(s) missing. Conversions that need them will fail with a clear error.")
    return 0


def _build(project, sync: bool) -> int:
    from .core import build as build_core

    report = build_core.build(project, log=print)
    for warning in report.warnings:
        print(f"warning: {warning}", file=sys.stderr)
    if not report.ok:
        for error in report.errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    project.save()
    if sync:
        ok, message = build_core.mirror(project, log=print)
        print(message)
        return 0 if ok else 1
    return 0


def _sync(project) -> int:
    from .core import build as build_core

    ok, message = build_core.mirror(project, log=print)
    print(message)
    return 0 if ok else 1


def _add_paper(project, args) -> int:
    from .core import papers

    authors = [a.strip() for a in args.authors.split(",") if a.strip()]
    tags = [t.strip() for t in args.tags.split(",") if t.strip()]
    source = args.source

    try:
        path = Path(source).expanduser()
        if path.is_file():
            suffix = path.suffix.lower()
            if suffix == ".pdf":
                result = papers.ingest_pdf(project, path, args.title, authors, tags)
            elif suffix in {".md", ".markdown"}:
                result = papers.ingest_markdown(project, path, args.title, authors, tags)
            elif suffix in {".xml", ".nxml"}:
                result = papers.ingest_jats(project, path, tags)
            else:
                print(f"Unsupported file type: {suffix}", file=sys.stderr)
                return 1
        else:
            result = papers.ingest_arxiv(project, source, tags)
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    project.save()
    print(f"Imported \u201c{result.item.title}\u201d as {result.item.id} via {result.item.method}")
    for warning in result.warnings:
        print(f"warning: {warning}", file=sys.stderr)
    return 0


def _add_dwell(project, args) -> int:
    import shutil

    from .core.project import DwellItem

    image_ref = ""
    if args.image:
        source = Path(args.image).expanduser()
        if not source.is_file():
            print(f"error: no such image: {source}", file=sys.stderr)
            return 1
        project.dwell_assets_dir.mkdir(parents=True, exist_ok=True)
        target = project.dwell_assets_dir / source.name
        shutil.copy2(source, target)
        image_ref = str(target.relative_to(project.root))

    if not (args.title or args.body or image_ref):
        print("error: a card needs a title, a body or an image.", file=sys.stderr)
        return 1

    item = DwellItem(
        kind=args.kind, title=args.title, body=args.body, image=image_ref,
        attribution=args.attribution, date=args.date,
    )
    project.dwell.append(item)
    project.save()
    print(f"Added {item.id} ({item.kind})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
