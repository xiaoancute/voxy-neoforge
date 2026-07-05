#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

CHECKS = {
    "src/main/java/me/cortex/voxy/client/config/VoxyNeoForgeConfig.java": [
        'define("autoLodRecovery", true)',
        "VoxyConfig.CONFIG.autoLodRecovery = AUTO_LOD_RECOVERY.get();",
    ],
    "src/main/java/me/cortex/voxy/client/VoxyClient.java": [
        "config={",
        "autoLodRecovery=",
        "sodiumBuilderThreads=",
        "storagePath=",
    ],
    "README.md": [
        "/voxy refresh",
        "/voxy status",
        "Auto LOD Recovery",
    ],
    "README.zh-CN.md": [
        "/voxy refresh",
        "/voxy status",
        "自动恢复 LOD",
    ],
    "build.gradle": [
        "task validateVoxyClientControls",
        "compileJava.dependsOn validateVoxyClientControls",
    ],
}


def main() -> int:
    missing = []
    for rel, needles in CHECKS.items():
        text = (ROOT / rel).read_text(encoding="utf-8")
        for needle in needles:
            if needle not in text:
                missing.append(f"{rel}: missing {needle!r}")

    if missing:
        print("Voxy client controls validation failed:")
        for item in missing:
            print(f"  - {item}")
        return 1

    print("Voxy client controls validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
