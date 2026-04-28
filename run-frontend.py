#!/usr/bin/env python3
"""Launch TalkBack PR Reviewer frontend."""
import os
import sys

# Qt WebEngine crashes on some Windows GPUs; disable GPU before any Qt import
if sys.platform == "win32":
    os.environ.setdefault(
        "QTWEBENGINE_CHROMIUM_FLAGS",
        "--disable-gpu --disable-gpu-compositing --no-sandbox "
        "--disable-features=GpuProcess,CanvasOopRasterization,VizDisplayCompositor",
    )
    os.environ.setdefault("QT_OPENGL", "software")

import traceback
from pathlib import Path

# Add repo root to path so frontend package resolves
sys.path.insert(0, str(Path(__file__).parent))

try:
    from frontend.app import main
except Exception:
    traceback.print_exc()
    raise

if __name__ == "__main__":
    main()
