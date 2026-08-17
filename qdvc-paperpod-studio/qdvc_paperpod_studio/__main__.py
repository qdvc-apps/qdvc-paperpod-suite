"""Entry point: `python -m qdvc_paperpod_studio` opens the window."""

import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
