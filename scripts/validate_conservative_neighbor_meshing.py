#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java"
TRACKER = ROOT / "src/main/java/me/cortex/voxy/common/world/ActiveSectionTracker.java"
SECTION = ROOT / "src/main/java/me/cortex/voxy/common/world/WorldSection.java"
UPDATER = ROOT / "src/main/java/me/cortex/voxy/common/world/WorldUpdater.java"
BUILD = ROOT / "build.gradle"


def method_body(source: str, signature: str, next_marker: str) -> str:
    start = source.find(signature)
    if start < 0:
        raise AssertionError(f"missing method {signature!r}")
    end = source.find(next_marker, start)
    if end < 0:
        raise AssertionError(f"missing marker after {signature!r}: {next_marker!r}")
    return source[start:end]


def require(text: str, needle: str, message: str) -> None:
    if needle not in text:
        raise AssertionError(message)


def reject(text: str, needle: str, message: str) -> None:
    if needle in text:
        raise AssertionError(message)


def main() -> int:
    try:
        source = SOURCE.read_text(encoding="utf-8")
        tracker = TRACKER.read_text(encoding="utf-8")
        section = SECTION.read_text(encoding="utf-8")
        updater = UPDATER.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")

        acquire_neighbor = method_body(
            source,
            "private void acquireNeighborData(WorldSection section, int msk)",
            "private static final long LM",
        )

        reject(
            acquire_neighbor,
            "this.world.acquire(section.lvl",
            "neighbor meshing must not create missing neighbors as air with world.acquire",
        )
        require(
            acquire_neighbor,
            "this.world.acquireIfExists",
            "neighbor meshing must probe optional neighbor sections with acquireIfExists",
        )
        require(
            acquire_neighbor,
            "copySelfBoundaryFace",
            "missing neighbor sections must be filled from the current boundary face",
        )
        require(
            source,
            "Arrays.fill(this.neighboringFaces, Mapper.AIR);",
            "neighbor face cache must be cleared before per-section reuse",
        )
        require(
            section,
            "volatile boolean storageLoadMissing;",
            "WorldSection must track storage-missing placeholder state",
        )
        require(
            section,
            "public boolean isStorageLoadMissing()",
            "WorldSection must expose storage-missing placeholder state to acquireIfExists",
        )
        require(
            tracker,
            "if (nullOnEmpty && section.isStorageLoadMissing())",
            "acquireIfExists must keep returning null for cached storage-missing placeholders",
        )
        require(
            tracker,
            "section.markStorageLoadMissing();",
            "missing storage loads must be marked on the placeholder section",
        )
        require(
            updater,
            "worldSection.markDataKnown();",
            "real world ingest/update must clear storage-missing placeholder state",
        )
        require(
            build,
            "task validateConservativeNeighborMeshing",
            "build must define the conservative neighbor meshing validation task",
        )
        require(
            build,
            "compileJava.dependsOn validateConservativeNeighborMeshing",
            "compileJava must depend on conservative neighbor meshing validation",
        )
    except AssertionError as error:
        print(f"Conservative neighbor meshing validation failed: {error}")
        return 1

    print("Conservative neighbor meshing validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
