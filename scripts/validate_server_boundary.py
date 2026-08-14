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
            "ServerAboutToStartEvent",
            "ServerStartedEvent",
            "ServerStoppingEvent",
            "ChunkEvent.Load",
        ],
        "src/main/java/me/cortex/voxy/server/VoxyServerNetwork.java": [
            "Dist.DEDICATED_SERVER",
            ".optional()",
            "maxRemoteRequestsPerSecond()",
            "maxRemoteResponseBytes()",
            "queueInvalidation",
            "VoxyPayloads.Invalidate",
        ],
        "src/main/java/me/cortex/voxy/network/VoxyPayloads.java": [
            "PROTOCOL_VERSION",
            "MAX_REQUEST_SECTIONS",
            "MAX_INVALIDATION_SECTIONS",
            "MAX_RESPONSE_BYTES",
        ],
        "src/main/resources/META-INF/neoforge.mods.toml": [
            'side="BOTH"',
            'modId="sodium"',
            'side="CLIENT"',
        ],
        "build.gradle": [
            "build.dependsOn validateServerArtifact",
            "scripts/validate_server_artifact.py",
            'compileOnly "org.lwjgl:lwjgl:$lwjglVersion"',
            "lwjglBindingsRaw",
            "exclude 'META-INF/**'",
            "jarJar(implementation('org.apache.commons:commons-pool2:2.12.0'))",
        ],
        "src/main/java/me/cortex/voxy/server/VoxyServerInstance.java": [
            "new ServerSectionStorage",
            '.resolve("storage-v1")',
        ],
        "src/main/java/me/cortex/voxy/server/ServerSectionStorage.java": [
            "ByteBuffer.allocate",
            "DeflaterOutputStream",
            "RocksDB.open",
        ],
        "scripts/ci_server_smoke.sh": [
            "validateServerArtifact",
            "--installServer",
            "bash run.sh nogui",
            '"voxy server status"',
            'grep -Eq "ingested=[1-9][0-9]*"',
        ],
        ".github/workflows/server-smoke.yml": [
            "Run packaged dedicated-server smoke test",
            "build/server-smoke/packaged-server/logs/**",
        ],
    }
    forbidden = {
        "src/main/java/me/cortex/voxy/Voxy.java": [
            "net.minecraft.client",
            "net.neoforged.neoforge.client",
            "me.cortex.voxy.client",
        ],
        "src/main/java/me/cortex/voxy/server/VoxyServerConfig.java": ["net.minecraft.client", "me.cortex.voxy.client"],
        "src/main/java/me/cortex/voxy/server/VoxyServerInstance.java": [
            "net.minecraft.client", "me.cortex.voxy.client", "StorageConfigUtil"],
        "src/main/java/me/cortex/voxy/server/ServerSectionStorage.java": [
            "org.lwjgl", "MemoryBuffer", "SaveLoadSystem3"],
        "src/main/java/me/cortex/voxy/server/VoxyServerLifecycle.java": ["net.minecraft.client", "me.cortex.voxy.client"],
        "src/main/java/me/cortex/voxy/server/VoxyServerCommands.java": ["net.minecraft.client", "me.cortex.voxy.client"],
        "src/main/java/me/cortex/voxy/server/VoxyServerNetwork.java": [
            "net.minecraft.client",
            "me.cortex.voxy.client",
            "EventBusSubscriber.Bus",
        ],
        "src/main/java/me/cortex/voxy/network/VoxyPayloads.java": ["net.minecraft.client", "me.cortex.voxy.client"],
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
