#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

CHECKS = {
    "src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java": [
        "maybeLogRuntimeTelemetry",
        "Voxy telemetry:",
        "chunkBoundRenderer.getTrackedSectionCount()",
        "nodeManager.getDebugSummary()",
    ],
    "src/main/java/me/cortex/voxy/client/core/rendering/ChunkBoundRenderer.java": [
        "getTrackedSectionCount",
        "getPendingAddCount",
        "getPendingRemoveCount",
    ],
    "src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java": [
        "getDebugSummary",
        "manager.getDebugSummary()",
        "requestBatchQueue.size()",
    ],
    "src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java": [
        "getTopNodeCount",
        "getLastRequestCount",
    ],
    "src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java": [
        "getDebugSummary",
        "activeSectionMap.size()",
        "activeNodeRequestCount",
    ],
}


def main() -> int:
    missing = []
    for rel, needles in CHECKS.items():
        path = ROOT / rel
        text = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle not in text:
                missing.append(f"{rel}: missing {needle!r}")

    if missing:
        print("Voxy runtime telemetry validation failed:")
        for item in missing:
            print(f"  - {item}")
        return 1

    print("Voxy runtime telemetry validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
