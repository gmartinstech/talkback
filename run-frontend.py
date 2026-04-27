#!/usr/bin/env python3
"""Launch TalkBack PR Reviewer frontend."""
import sys
from pathlib import Path

# Add frontend to path
sys.path.insert(0, str(Path(__file__).parent / "frontend"))

from frontend.app import main

if __name__ == "__main__":
    main()
