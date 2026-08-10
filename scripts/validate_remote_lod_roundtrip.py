#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(path, needle, description, failures):
    content = (ROOT / path).read_text(encoding="utf-8")
    if needle not in content:
        failures.append(f"{path}: missing {description}")


def main():
    failures = []
    require(
        "src/main/java/me/cortex/voxy/commonImpl/mixin/minecraft/MixinServerWorld.java",
        "VoxyServerLifecycle.queueBlockUpdate(level, pos)",
        "dedicated-server block update hook",
        failures)
    require(
        "src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java",
        "DIRTY_SECTION_SET.add(dirty)",
        "section-level update coalescing",
        failures)
    require(
        "src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java",
        "VoxelIngestService.rawIngest(",
        "live section snapshot ingestion",
        failures)
    require(
        "src/main/java/me/cortex/voxy/client/network/RemoteLodCiProbe.java",
        "engine.acquireIfExists(key)",
        "real section storage read",
        failures)
    require(
        "src/main/java/me/cortex/voxy/client/network/RemoteLodCiProbe.java",
        "VOXY_REMOTE_LOD_PROBE success",
        "roundtrip success marker",
        failures)
    require(
        "scripts/ci_remote_lod_roundtrip.sh",
        "setblock ${block_x} ${block_y} ${block_z}",
        "RCON block mutation",
        failures)
    require(
        "scripts/ci_remote_lod_roundtrip.sh",
        "onboardAccessibility:false",
        "first-run onboarding bypass",
        failures)
    require(
        ".github/workflows/remote-lod-roundtrip.yml",
        "bash scripts/ci_remote_lod_roundtrip.sh",
        "GitHub Actions roundtrip execution",
        failures)

    if failures:
        print("Remote LOD roundtrip validation failed:")
        for failure in failures:
            print(f"- {failure}")
        raise SystemExit(1)
    print("Remote LOD roundtrip validation passed")


if __name__ == "__main__":
    main()
