"""Font discovery for the payload's fonts/ directory.

Deliberately the same filename rules as the Android FontRegistry, so what Studio
reports is exactly what the device will offer. If Studio says a family has an
italic, the tablet has an italic.
"""

from __future__ import annotations

import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path

EXTENSIONS = {".ttf", ".otf"}


@dataclass
class Family:
    name: str
    dir_name: str
    regular: Path | None = None
    bold: Path | None = None
    italic: Path | None = None
    bold_italic: Path | None = None
    ignored: list[Path] = field(default_factory=list)

    @property
    def complete(self) -> bool:
        return all((self.regular, self.bold, self.italic, self.bold_italic))

    @property
    def usable(self) -> bool:
        """A family with no upright face has nothing to set body text in."""
        return self.regular is not None

    def summary(self) -> str:
        faces = [
            label for label, path in (
                ("regular", self.regular), ("bold", self.bold),
                ("italic", self.italic), ("bold italic", self.bold_italic),
            ) if path is not None
        ]
        if not faces:
            return "no usable faces"
        text = ", ".join(faces)
        if not self.regular:
            text += " \u2014 no regular face, will be skipped on the device"
        return text


def prettify(dir_name: str) -> str:
    """AtkinsonHyperlegible -> Atkinson Hyperlegible; DM_Sans -> DM Sans."""
    spaced = dir_name.replace("_", " ").replace("-", " ")
    spaced = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", spaced)
    spaced = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", " ", spaced)
    return " ".join(spaced.split())


def classify(path: Path) -> str:
    """Returns one of: regular, bold, italic, bold_italic."""
    name = path.stem.lower()
    is_bold = "bold" in name
    is_italic = "italic" in name or "oblique" in name
    if is_bold and is_italic:
        return "bold_italic"
    if is_bold:
        return "bold"
    if is_italic:
        return "italic"
    return "regular"


def scan(fonts_dir: Path) -> list[Family]:
    """One subdirectory per family; the directory name becomes the display name."""
    if not fonts_dir.is_dir():
        return []
    families: list[Family] = []
    for directory in sorted(p for p in fonts_dir.iterdir() if p.is_dir()):
        family = Family(name=prettify(directory.name), dir_name=directory.name)
        for file in sorted(directory.iterdir()):
            if not file.is_file():
                continue
            if file.suffix.lower() not in EXTENSIONS:
                family.ignored.append(file)
                continue
            slot = classify(file)
            if getattr(family, slot) is None:
                setattr(family, slot, file)
            else:
                family.ignored.append(file)
        if family.regular or family.bold or family.italic or family.bold_italic:
            families.append(family)
    return families


def copy_into_payload(fonts_dir: Path, payload_root: Path) -> list[str]:
    """Mirrors usable families into the payload, returning the names shipped."""
    target_root = payload_root / "fonts"
    if target_root.exists():
        shutil.rmtree(target_root)
    shipped: list[str] = []
    for family in scan(fonts_dir):
        if not family.usable:
            continue
        target = target_root / family.dir_name
        target.mkdir(parents=True, exist_ok=True)
        for path in (family.regular, family.bold, family.italic, family.bold_italic):
            if path is not None:
                shutil.copy2(path, target / path.name)
        shipped.append(family.name)
    return shipped
