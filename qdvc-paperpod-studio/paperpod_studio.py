#!/usr/bin/env python3
"""QDVC Paperpod Studio.

Run it directly, from anywhere:

    python paperpod_studio.py                       open the window
    python paperpod_studio.py build --sync          rebuild and mirror to the share
    python paperpod_studio.py add-paper 1706.03762  fetch from arXiv and convert
    python paperpod_studio.py doctor                report which tools are installed

The interesting code lives in the qdvc_paperpod_studio package next to this file;
this is only the front door. Nothing needs installing — Python puts this script's
directory on the import path, so the package is found wherever you invoke it from.
"""

import sys
from pathlib import Path

# Belt and braces for the case where the script is invoked through a symlink,
# which leaves sys.path[0] pointing at the link's directory rather than this one.
_here = str(Path(__file__).resolve().parent)
if _here not in sys.path:
    sys.path.insert(0, _here)

from qdvc_paperpod_studio.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
