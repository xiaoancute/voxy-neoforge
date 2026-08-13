#!/usr/bin/env python3
import json
import sys
import zipfile
from pathlib import Path


REQUIRED_ENTRIES = {
    "META-INF/neoforge.mods.toml",
    "META-INF/jarjar/metadata.json",
    "me/cortex/voxy/server/VoxyServerLifecycle.class",
    "liblwjgl.so",
    "liblwjgl_lmdb.so",
    "liblwjgl_zstd.so",
    "lwjgl.dll",
    "lwjgl_lmdb.dll",
    "lwjgl_zstd.dll",
}

REQUIRED_JARJAR = {
    ("org.lwjgl", "lwjgl-lmdb"): "3.3.3",
    ("org.lwjgl", "lwjgl-zstd"): "3.3.3",
    ("org.rocksdb", "rocksdbjni"): "10.9.1",
    ("org.apache.commons", "commons-pool2"): "2.12.0",
    ("redis.clients", "jedis"): "5.1.0",
    ("org.xerial", "sqlite-jdbc"): "3.49.1.0",
}

LWJGL_CORE = ("org.lwjgl", "lwjgl")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] not in {"client", "server"}:
        print("Usage: validate_server_artifact.py <voxy.jar> <client|server>")
        return 2

    artifact = Path(sys.argv[1])
    distribution = sys.argv[2]
    failures = []
    if not artifact.is_file():
        print(f"Server artifact validation failed: missing {artifact}")
        return 1

    with zipfile.ZipFile(artifact) as jar:
        entries = set(jar.namelist())
        for entry in sorted(REQUIRED_ENTRIES - entries):
            failures.append(f"missing artifact entry {entry}")

        try:
            metadata = json.loads(jar.read("META-INF/jarjar/metadata.json"))
        except (KeyError, json.JSONDecodeError) as error:
            failures.append(f"invalid JarJar metadata: {error}")
            metadata = {"jars": []}

        bundled = {}
        for item in metadata.get("jars", []):
            identifier = item.get("identifier", {})
            version = item.get("version", {})
            key = (identifier.get("group"), identifier.get("artifact"))
            bundled[key] = (version.get("artifactVersion"), item.get("path"))

        for key, expected_version in REQUIRED_JARJAR.items():
            actual = bundled.get(key)
            label = ":".join(key)
            if actual is None:
                failures.append(f"missing bundled dependency {label}")
                continue
            actual_version, nested_path = actual
            if actual_version != expected_version:
                failures.append(
                    f"bundled dependency {label} is {actual_version}, expected {expected_version}")
            if nested_path not in entries:
                failures.append(f"bundled dependency {label} points to missing {nested_path}")
            elif jar.getinfo(nested_path).file_size == 0:
                failures.append(f"bundled dependency {label} is empty")

        if distribution == "client" and LWJGL_CORE in bundled:
            failures.append(
                "client artifact bundles org.lwjgl:lwjgl, which duplicates NeoForge's module")
        if distribution == "server" and LWJGL_CORE not in bundled:
            failures.append(
                "server artifact is missing org.lwjgl:lwjgl required by LMDB/Zstd")

    if failures:
        print("Server artifact validation failed:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print(f"{distribution.capitalize()} artifact validation passed: {artifact.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
