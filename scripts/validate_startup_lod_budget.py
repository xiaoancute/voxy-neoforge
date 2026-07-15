#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TRACKER = ROOT / "src/main/java/me/cortex/voxy/client/core/rendering/RenderDistanceTracker.java"


def main() -> int:
    text = TRACKER.read_text(encoding="utf-8")
    expected = "this.tracker.process(this.processRate, this::add, this::rem)"

    if expected not in text or "Integer.MAX_VALUE" in text or "firstProcess" in text:
        print("Startup LOD budget validation failed")
        return 1

    print("Startup LOD budget validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
