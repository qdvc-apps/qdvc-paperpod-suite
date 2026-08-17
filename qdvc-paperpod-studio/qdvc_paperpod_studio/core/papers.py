"""Turning research papers into something readable on a 7" panel.

The design principle is a **source-priority ladder**. A PDF is a description of
ink on A4 paper; recovering structure from it is guesswork. So we only fall back
to the PDF when nothing better exists:

1. **arXiv LaTeX source** — real headings, real equations, real figure files.
   Overwhelmingly the best route, and available for most of what gets read.
2. **JATS XML** (PubMed Central and friends) — structured markup, nearly as good.
3. **PDF text extraction** — a last resort, with reflow heuristics that undo
   column breaks and hyphenation as best they can.
4. **Hand-written Markdown** — for when you would rather just fix it yourself.

Everything the device cannot render is rasterised here: equations and tables
become PNGs, figures are extracted as separate assets. The tablet gets headings,
paragraphs and images, and needs no maths renderer at all.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .project import DocumentItem, Project

ARXIV_ID = re.compile(r"(\d{4}\.\d{4,5})(v\d+)?")
INLINE_MATH = re.compile(r"(?<!\$)\$([^$\n]+?)\$(?!\$)")
DISPLAY_MATH = re.compile(r"\$\$(.+?)\$\$", re.S)
PIPE_TABLE = re.compile(
    r"(?m)^\|.+\|[ \t]*\n\|[ \t:\-|]+\|[ \t]*\n(?:\|.*\|[ \t]*\n?)+"
)
IMAGE_REF = re.compile(r"!\[(.*?)]\((.+?)\)")
SECTION_NUMBER = re.compile(r"^\d+(?:\.\d+)*\.?\s*")


class IngestError(RuntimeError):
    pass


@dataclass
class IngestResult:
    item: DocumentItem
    warnings: list[str]


def have(tool: str) -> bool:
    return shutil.which(tool) is not None


def doctor() -> list[tuple[str, bool, str]]:
    """Reports which optional tools are present, for the Overview page."""
    checks = [
        ("pandoc", have("pandoc"), "LaTeX and JATS conversion"),
        ("pdftotext", have("pdftotext"), "PDF fallback (poppler-utils)"),
        ("rsync", have("rsync"), "mirroring the payload to the SMB share"),
    ]
    try:
        import matplotlib  # noqa: F401
        checks.append(("matplotlib", True, "rasterising equations and tables"))
    except ImportError:
        checks.append(("matplotlib", False, "rasterising equations and tables"))
    try:
        import icalendar  # noqa: F401
        checks.append(("icalendar", True, "ICS parsing"))
    except ImportError:
        checks.append(("icalendar", False, "ICS parsing"))
    try:
        import dateutil  # noqa: F401
        checks.append(("python-dateutil", True, "recurrence expansion"))
    except ImportError:
        checks.append(("python-dateutil", False, "recurrence expansion"))
    return checks


# --------------------------------------------------------------------- helpers


def slugify(text: str, limit: int = 60) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return (s[:limit].rstrip("-")) or "document"


def doc_id_for(title: str, authors: list[str], year: int | None) -> str:
    lead = slugify(authors[0].split(",")[0]) if authors else ""
    parts = [p for p in (lead, str(year) if year else "", slugify(title, 40)) if p]
    return "-".join(parts)


def _write_document(
    project: Project,
    doc_id: str,
    markdown: str,
    meta: dict,
    assets: list[Path],
    method: str,
) -> DocumentItem:
    target = project.library_dir / doc_id
    assets_dir = target / "assets"
    target.mkdir(parents=True, exist_ok=True)
    assets_dir.mkdir(parents=True, exist_ok=True)

    for asset in assets:
        if asset.is_file():
            shutil.copy2(asset, assets_dir / asset.name)

    (target / "text.md").write_text(markdown, encoding="utf-8")
    doc_json = {
        "id": doc_id,
        "title": meta.get("title", doc_id),
        "authors": meta.get("authors", []),
        "abstract": meta.get("abstract", ""),
        "text": "text.md",
        "provenance": {
            "method": method,
            "convertedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
            "source": meta.get("source_url", ""),
        },
    }
    (target / "doc.json").write_text(json.dumps(doc_json, indent=2, ensure_ascii=False), encoding="utf-8")

    words = len(re.findall(r"\w+", markdown))
    return DocumentItem(
        id=doc_id,
        title=meta.get("title", doc_id),
        authors=meta.get("authors", []),
        year=meta.get("year"),
        venue=meta.get("venue", ""),
        kind=meta.get("kind", "paper"),
        tags=meta.get("tags", []),
        words=words,
        added_at=dt.date.today().isoformat(),
        source_url=meta.get("source_url", ""),
        method=method,
    )


def _upsert(project: Project, item: DocumentItem) -> None:
    for i, existing in enumerate(project.documents):
        if existing.id == item.id:
            item.enabled = existing.enabled
            item.tags = item.tags or existing.tags
            project.documents[i] = item
            return
    project.documents.append(item)


# ------------------------------------------------------------------- rasterising


def rasterise_math(latex: str, out_path: Path, display: bool, dpi: int = 200) -> bool:
    """Renders one equation to PNG.

    Doing this on the desktop keeps a maths renderer off the device entirely.
    matplotlib's mathtext covers ordinary paper notation; anything exotic falls
    through and is left as literal text rather than silently dropped.
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return False

    body = latex.strip().strip("$")
    if not body:
        return False
    fontsize = 20 if display else 15
    fig = plt.figure(figsize=(0.01, 0.01))
    try:
        fig.text(0, 0, f"${body}$", fontsize=fontsize, color="black")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(
            out_path, dpi=dpi, transparent=False, facecolor="white",
            bbox_inches="tight", pad_inches=0.06,
        )
        return True
    except Exception:
        return False
    finally:
        plt.close(fig)


def rasterise_table(markdown_table: str, out_path: Path, dpi: int = 170) -> bool:
    """Renders a pipe table to PNG.

    A real table layout engine on the device would be a lot of code for something
    that appears a handful of times per paper, and tables are the one element that
    genuinely does not reflow.
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return False

    rows: list[list[str]] = []
    for line in markdown_table.strip().splitlines():
        if re.fullmatch(r"\|[\s:\-|]+\|", line.strip()):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows.append(cells)
    if len(rows) < 2:
        return False

    width = max(len(r) for r in rows)
    rows = [r + [""] * (width - len(r)) for r in rows]
    header, body = rows[0], rows[1:]

    fig, ax = plt.subplots(figsize=(min(9, 1.5 * width), 0.4 * (len(body) + 1) + 0.4))
    ax.axis("off")
    try:
        table = ax.table(cellText=body, colLabels=header, loc="center", cellLoc="left")
        table.auto_set_font_size(False)
        table.set_fontsize(9)
        table.scale(1, 1.3)
        # Hard hairlines and no fills: the same visual language as the app.
        for cell in table.get_celld().values():
            cell.set_edgecolor("black")
            cell.set_linewidth(0.7)
            cell.set_facecolor("white")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(out_path, dpi=dpi, facecolor="white", bbox_inches="tight", pad_inches=0.05)
        return True
    except Exception:
        return False
    finally:
        plt.close(fig)


def postprocess(markdown: str, assets_dir: Path, warnings: list[str]) -> str:
    """Replaces everything the reader cannot render with image references."""
    counter = {"eq": 0, "tbl": 0}

    def display_repl(match: re.Match) -> str:
        counter["eq"] += 1
        name = f"eq{counter['eq']:03d}.png"
        if rasterise_math(match.group(1), assets_dir / name, display=True):
            return f"\n\n![]({'assets/' + name})\n\n"
        warnings.append(f"Could not render display equation {counter['eq']}; left as text.")
        return f"\n\n`{match.group(1).strip()}`\n\n"

    def inline_repl(match: re.Match) -> str:
        counter["eq"] += 1
        name = f"eq{counter['eq']:03d}.png"
        if rasterise_math(match.group(1), assets_dir / name, display=False):
            # Inline maths becomes its own block: mixing image baselines into a
            # text line is worse than a short interruption.
            return f"\n\n![]({'assets/' + name})\n\n"
        return f"`{match.group(1).strip()}`"

    def table_repl(match: re.Match) -> str:
        counter["tbl"] += 1
        name = f"tbl{counter['tbl']:03d}.png"
        if rasterise_table(match.group(0), assets_dir / name):
            return f"\n\n![Table {counter['tbl']}]({'assets/' + name})\n\n"
        warnings.append(f"Could not render table {counter['tbl']}; left as text.")
        return match.group(0)

    text = DISPLAY_MATH.sub(display_repl, markdown)
    text = INLINE_MATH.sub(inline_repl, text)
    text = PIPE_TABLE.sub(table_repl, text)

    # Collapse the blank-line storms that substitution leaves behind.
    text = re.sub(r"\n{4,}", "\n\n\n", text)
    return text.strip() + "\n"


def _run_pandoc(args: list[str]) -> str:
    if not have("pandoc"):
        raise IngestError(
            "pandoc is not installed. It is the difference between a readable paper "
            "and a wall of text; install it with your package manager."
        )
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        raise IngestError(f"pandoc failed: {result.stderr.strip()[:400]}")
    return result.stdout


# ----------------------------------------------------------------- ingest: arXiv


def ingest_arxiv(project: Project, identifier: str, tags: list[str] | None = None) -> IngestResult:
    """Fetches an arXiv e-print's LaTeX source and converts it.

    This is the good path. Converting real LaTeX gives correct sectioning,
    equations that can be rendered properly, and figures as separate files —
    none of which survive a trip through the PDF.
    """
    match = ARXIV_ID.search(identifier)
    if not match:
        raise IngestError(f"Could not find an arXiv id in {identifier!r}.")
    arxiv_id = match.group(1)
    warnings: list[str] = []

    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        archive = tmp_dir / "source.tar.gz"
        url = f"https://arxiv.org/e-print/{arxiv_id}"
        req = urllib.request.Request(url, headers={"User-Agent": "QDVC-Paperpod-Studio/0.1"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            archive.write_bytes(resp.read())

        extract_dir = tmp_dir / "src"
        extract_dir.mkdir()
        try:
            with tarfile.open(archive) as tar:
                tar.extractall(extract_dir, filter="data")
        except tarfile.ReadError:
            # Some older submissions are a single gzipped .tex rather than a tar.
            import gzip
            (extract_dir / "main.tex").write_bytes(gzip.decompress(archive.read_bytes()))

        main = _find_main_tex(extract_dir)
        if main is None:
            raise IngestError("No .tex file with \\documentclass found in the source archive.")

        meta = _tex_metadata(main.read_text(encoding="utf-8", errors="replace"))
        meta["source_url"] = f"https://arxiv.org/abs/{arxiv_id}"
        meta["tags"] = tags or []

        markdown = _run_pandoc([
            "pandoc", "-f", "latex", "-t", "gfm",
            "--wrap=none", "--markdown-headings=atx",
            str(main),
        ])

        doc_id = doc_id_for(meta.get("title", arxiv_id), meta.get("authors", []), meta.get("year"))
        target_assets = project.library_dir / doc_id / "assets"
        target_assets.mkdir(parents=True, exist_ok=True)

        markdown, figure_warnings = _relocate_figures(markdown, extract_dir, target_assets)
        warnings.extend(figure_warnings)
        markdown = postprocess(markdown, target_assets, warnings)

        item = _write_document(project, doc_id, markdown, meta, [], "arxiv-latex")
        _upsert(project, item)
        return IngestResult(item, warnings)


def _find_main_tex(root: Path) -> Path | None:
    candidates = sorted(root.rglob("*.tex"))
    for path in candidates:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if "\\documentclass" in text:
            return path
    return candidates[0] if candidates else None


def _tex_metadata(tex: str) -> dict:
    def grab(command: str) -> str:
        m = re.search(r"\\" + command + r"\s*\{", tex)
        if not m:
            return ""
        depth, start = 1, m.end()
        i = start
        while i < len(tex) and depth:
            if tex[i] == "{":
                depth += 1
            elif tex[i] == "}":
                depth -= 1
            i += 1
        return _detex(tex[start:i - 1])

    title = grab("title")
    authors_raw = grab("author")
    authors: list[str] = []
    if authors_raw:
        for chunk in re.split(r"\\and|,|\band\b", authors_raw):
            name = chunk.strip()
            if name and len(name) < 60:
                authors.append(name)

    abstract = ""
    m = re.search(r"\\begin\{abstract\}(.+?)\\end\{abstract\}", tex, re.S)
    if m:
        abstract = _detex(m.group(1))

    return {"title": title or "Untitled", "authors": authors[:12], "abstract": abstract}


def _detex(text: str) -> str:
    out = re.sub(r"\\(?:thanks|footnote|label|cite\w*)\{[^{}]*\}", "", text)
    out = re.sub(r"\\[a-zA-Z]+\s*", " ", out)
    out = out.replace("{", "").replace("}", "").replace("\\\\", " ")
    out = re.sub(r"\s*\n\s*", " ", out)
    return " ".join(out.split()).strip()


def _relocate_figures(markdown: str, source_root: Path, assets_dir: Path) -> tuple[str, list[str]]:
    """Copies referenced figures next to the text and rewrites the references."""
    warnings: list[str] = []
    index = {p.name.lower(): p for p in source_root.rglob("*") if p.is_file()}
    raster = {".png", ".jpg", ".jpeg"}

    def repl(match: re.Match) -> str:
        caption, ref = match.group(1), match.group(2).strip()
        name = Path(ref).name
        found = index.get(name.lower())
        if found is None:
            # LaTeX often omits the extension; try the usual candidates.
            stem = Path(ref).stem.lower()
            for ext in (".pdf", ".png", ".jpg", ".jpeg", ".eps"):
                if stem + ext in index:
                    found = index[stem + ext]
                    break
        if found is None:
            warnings.append(f"Figure not found in source: {ref}")
            return f"*[figure missing: {caption or name}]*"

        if found.suffix.lower() in raster:
            shutil.copy2(found, assets_dir / found.name)
            return f"![{caption}](assets/{found.name})"

        converted = _convert_vector(found, assets_dir)
        if converted:
            return f"![{caption}](assets/{converted})"
        warnings.append(
            f"Could not rasterise {found.name}; install pdftoppm (poppler-utils) "
            "to include vector figures."
        )
        return f"*[figure: {caption or name}]*"

    return IMAGE_REF.sub(repl, markdown), warnings


def _convert_vector(path: Path, assets_dir: Path) -> str | None:
    """PDF and EPS figures are common in LaTeX sources; the device needs raster."""
    out_stem = assets_dir / path.stem
    if path.suffix.lower() == ".pdf" and have("pdftoppm"):
        result = subprocess.run(
            ["pdftoppm", "-png", "-r", "200", "-singlefile", str(path), str(out_stem)],
            capture_output=True,
        )
        if result.returncode == 0 and out_stem.with_suffix(".png").exists():
            return out_stem.with_suffix(".png").name
    if path.suffix.lower() in {".eps", ".ps"} and have("gs"):
        target = out_stem.with_suffix(".png")
        result = subprocess.run(
            ["gs", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-sDEVICE=png16m", "-r200",
             f"-sOutputFile={target}", str(path)],
            capture_output=True,
        )
        if result.returncode == 0 and target.exists():
            return target.name
    return None


# ------------------------------------------------------------------ ingest: JATS


def ingest_jats(project: Project, path: str | Path, tags: list[str] | None = None) -> IngestResult:
    """Converts a JATS XML article, as served by PubMed Central."""
    src = Path(path).expanduser()
    if not src.is_file():
        raise IngestError(f"No such file: {src}")
    warnings: list[str] = []

    markdown = _run_pandoc([
        "pandoc", "-f", "jats", "-t", "gfm", "--wrap=none",
        "--markdown-headings=atx", str(src),
    ])
    xml = src.read_text(encoding="utf-8", errors="replace")
    title = _xml_text(xml, "article-title") or src.stem
    authors = re.findall(r"<surname>(.*?)</surname>", xml)[:12]
    meta = {
        "title": title,
        "authors": authors,
        "abstract": _xml_text(xml, "abstract"),
        "tags": tags or [],
    }

    doc_id = doc_id_for(title, authors, None)
    assets_dir = project.library_dir / doc_id / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    markdown, fig_warnings = _relocate_figures(markdown, src.parent, assets_dir)
    warnings.extend(fig_warnings)
    markdown = postprocess(markdown, assets_dir, warnings)

    item = _write_document(project, doc_id, markdown, meta, [], "jats")
    _upsert(project, item)
    return IngestResult(item, warnings)


def _xml_text(xml: str, tag: str) -> str:
    m = re.search(rf"<{tag}[^>]*>(.*?)</{tag}>", xml, re.S)
    if not m:
        return ""
    return " ".join(re.sub(r"<[^>]+>", " ", m.group(1)).split())


# ------------------------------------------------------------------- ingest: PDF


def ingest_pdf(
    project: Project,
    path: str | Path,
    title: str = "",
    authors: list[str] | None = None,
    tags: list[str] | None = None,
) -> IngestResult:
    """Last-resort extraction from a PDF.

    Column order, hyphenation and running heads all have to be guessed at, so the
    output is never as good as a LaTeX conversion. It is still far better than
    panning around an A4 page on a 7" screen, which is the alternative.
    """
    src = Path(path).expanduser()
    if not src.is_file():
        raise IngestError(f"No such file: {src}")
    if not have("pdftotext"):
        raise IngestError(
            "pdftotext is not installed (it ships with poppler-utils). Without it "
            "there is no PDF fallback."
        )
    warnings = ["Extracted from PDF; headings and figure placement are approximate."]

    raw = subprocess.run(
        ["pdftotext", "-layout", "-nopgbrk", str(src), "-"],
        capture_output=True, text=True,
    )
    if raw.returncode != 0:
        raise IngestError(f"pdftotext failed: {raw.stderr.strip()[:300]}")

    markdown = reflow_pdf_text(raw.stdout)
    resolved_title = title or _guess_title(raw.stdout) or src.stem
    meta = {
        "title": resolved_title,
        "authors": authors or [],
        "abstract": "",
        "tags": tags or [],
        "source_url": str(src),
    }
    doc_id = doc_id_for(resolved_title, meta["authors"], None)
    assets_dir = project.library_dir / doc_id / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    markdown = postprocess(markdown, assets_dir, warnings)

    item = _write_document(project, doc_id, markdown, meta, [], "pdf-text")
    _upsert(project, item)
    return IngestResult(item, warnings)


def reflow_pdf_text(text: str) -> str:
    """Undoes print layout: hard wraps, hyphenation, page furniture."""
    lines = [line.rstrip() for line in text.replace("\f", "\n").splitlines()]

    # Lines repeated on many pages are running heads or footers, not content.
    counts: dict[str, int] = {}
    for line in lines:
        key = line.strip()
        if 3 < len(key) < 90:
            counts[key] = counts.get(key, 0) + 1
    furniture = {k for k, v in counts.items() if v >= 4}

    out: list[str] = []
    buffer = ""

    def flush() -> None:
        nonlocal buffer
        if buffer.strip():
            out.append(buffer.strip())
            out.append("")
        buffer = ""

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped in furniture or re.fullmatch(r"\d{1,4}", stripped):
            flush()
            continue

        heading = _heading_level(stripped)
        if heading:
            flush()
            # Drop the printed section number: the heading level already encodes
            # the depth, and "2.1" in the text is noise once it is a real heading.
            text = SECTION_NUMBER.sub("", stripped)
            out.append(("#" * heading) + " " + text)
            out.append("")
            continue

        if buffer.endswith("-"):
            buffer = buffer[:-1] + stripped
        elif buffer:
            buffer += " " + stripped
        else:
            buffer = stripped

        # A short line that ends a sentence is almost always a paragraph end.
        if len(stripped) < 45 and stripped.endswith((".", "!", "?", ":")):
            flush()

    flush()
    return "\n".join(out)


def _heading_level(line: str) -> int | None:
    if len(line) > 80:
        return None
    numbered = re.match(r"^(\d+(?:\.\d+)*)\.?\s+\S", line)
    if numbered:
        return min(6, 1 + numbered.group(1).count("."))
    words = line.split()
    if 1 <= len(words) <= 8 and line == line.upper() and any(c.isalpha() for c in line):
        return 2
    if line.lower() in {
        "abstract", "introduction", "related work", "method", "methods",
        "results", "discussion", "conclusion", "conclusions", "references",
        "background", "experiments", "evaluation", "limitations",
    }:
        return 2
    return None


def _guess_title(text: str) -> str:
    for line in text.splitlines():
        candidate = line.strip()
        if 15 < len(candidate) < 140 and not candidate.lower().startswith(("arxiv", "doi")):
            return candidate
    return ""


# --------------------------------------------------------------- ingest: markdown


def ingest_markdown(
    project: Project,
    path: str | Path,
    title: str = "",
    authors: list[str] | None = None,
    tags: list[str] | None = None,
    kind: str = "article",
) -> IngestResult:
    """Imports hand-written or hand-fixed Markdown, still rasterising maths."""
    src = Path(path).expanduser()
    if not src.is_file():
        raise IngestError(f"No such file: {src}")
    warnings: list[str] = []

    body = src.read_text(encoding="utf-8", errors="replace")
    resolved_title = title
    if not resolved_title:
        m = re.search(r"^#\s+(.+)$", body, re.M)
        resolved_title = m.group(1).strip() if m else src.stem

    doc_id = doc_id_for(resolved_title, authors or [], None)
    assets_dir = project.library_dir / doc_id / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    body, fig_warnings = _relocate_figures(body, src.parent, assets_dir)
    warnings.extend(fig_warnings)
    body = postprocess(body, assets_dir, warnings)

    meta = {
        "title": resolved_title,
        "authors": authors or [],
        "abstract": "",
        "tags": tags or [],
        "kind": kind,
        "source_url": str(src),
    }
    item = _write_document(project, doc_id, body, meta, [], "markdown")
    _upsert(project, item)
    return IngestResult(item, warnings)
