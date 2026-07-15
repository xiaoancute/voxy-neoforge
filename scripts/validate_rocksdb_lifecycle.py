#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/main/java/me/cortex/voxy/common/config/storage/rocksdb/RocksDBStorageBackend.java"
BUILD = ROOT / "build.gradle"


def main() -> int:
    source = SOURCE.read_text(encoding="utf-8")
    build = BUILD.read_text(encoding="utf-8")
    missing = []

    if source.count("try (var iter") + source.count("try (var iterator") < 2:
        missing.append("RocksDB iterators must use try-with-resources")
    if "this.closeList.add(this.db);" in source:
        missing.append("RocksDB must not be closed through the generic close list")
    if "this.db.closeE();" not in source:
        missing.append("RocksDB close must wait for native shutdown with closeE")
    if "prefer '10.9.1'" not in build:
        missing.append("RocksDB 10.9.1 must remain the preferred bundled version")

    if missing:
        print("RocksDB lifecycle validation failed:")
        for item in missing:
            print(f"  - {item}")
        return 1

    print("RocksDB lifecycle validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
