#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    checks = {
        "src/main/java/me/cortex/voxy/Voxy.java": [
            '@Mod("voxy")',
            "VoxyServerConfig.register(container)",
        ],
        "src/main/java/me/cortex/voxy/client/VoxyClientBootstrap.java": [
            '@Mod(value = "voxy", dist = Dist.CLIENT)',
        ],
        "src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java": [
            "Dist.DEDICATED_SERVER",
            "ServerStartedEvent",
            "ServerStoppingEvent",
            "ChunkEvent.Load",
        ],
        "src/main/resources/META-INF/neoforge.mods.toml": [
            'side="BOTH"',
            'modId="sodium"',
            'side="CLIENT"',
        ],
    }
    forbidden = {
        "src/main/java/me/cortex/voxy/Voxy.java": [
            "net.minecraft.client",
            "net.neoforged.neoforge.client",
            "me.cortex.voxy.client",
        ],
        "src/main/java/me/cortex/voxy/server/VoxyServerConfig.java": ["net.minecraft.client", "me.cortex.voxy.client"],
        "src/main/java/me/cortex/voxy/server/VoxyServerInstance.java": ["net.minecraft.client", "me.cortex.voxy.client"],
        "src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java": ["net.minecraft.client", "me.cortex.voxy.client"],
        "src/main/java/me/cortex/voxy/server/VoxyServerCommands.java": ["net.minecraft.client", "me.cortex.voxy.client"],
    }

    failures = []
    for rel, needles in checks.items():
        text = (ROOT / rel).read_text(encoding="utf-8")
        for needle in needles:
            if needle not in text:
                failures.append(f"{rel}: missing {needle!r}")

    for rel, needles in forbidden.items():
        text = (ROOT / rel).read_text(encoding="utf-8")
        for needle in needles:
            if needle in text:
                failures.append(f"{rel}: contains client-only reference {needle!r}")

    if failures:
        print("Voxy server boundary validation failed:")
        for item in failures:
            print(f"  - {item}")
        return 1

    print("Voxy server boundary validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
