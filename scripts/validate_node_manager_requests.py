#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
NODE_MANAGER = ROOT / "src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"


def main() -> int:
    text = NODE_MANAGER.read_text(encoding="utf-8")
    start = text.index("private void makeLeafChildRequest")
    end = text.index("//Enqueue a leaf expansion request", start)
    zero_child_path = text[text.index("if (childExistence == 0)", start):end]

    missing = []
    if "this.nodeData.unmarkRequestInFlight(nodeId);" not in zero_child_path:
        missing.append("zero-child leaf requests must unmark the CPU in-flight bit")
    if "return;" not in zero_child_path:
        missing.append("zero-child leaf requests must return before allocating a request")
    if "new NodeChildRequest" in zero_child_path:
        missing.append("zero-child leaf requests must not allocate NodeChildRequest")
    if "this.invalidateNode(nodeId)" in zero_child_path:
        missing.append("zero-child leaf requests must not clear the GPU requested bit immediately")

    if missing:
        print("NodeManager request validation failed:")
        for item in missing:
            print(f"  - {item}")
        return 1

    print("NodeManager request validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
