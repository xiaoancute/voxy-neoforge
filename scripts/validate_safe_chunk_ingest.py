#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
CHUNK_CACHE_IFACE = ROOT / "src/main/java/me/cortex/voxy/client/ICheekyClientChunkCache.java"
CHUNK_CACHE_MIXIN = ROOT / "src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinClientChunkCache.java"
CLIENT_LEVEL_MIXIN = ROOT / "src/main/java/me/cortex/voxy/client/mixin/minecraft/MixinClientLevel.java"
SODIUM_RSM_MIXIN = ROOT / "src/main/java/me/cortex/voxy/client/mixin/sodium/MixinRenderSectionManager.java"
BUILD = ROOT / "build.gradle"


def require(text: str, needle: str, message: str) -> None:
    if needle not in text:
        raise AssertionError(message)


def reject(text: str, needle: str, message: str) -> None:
    if needle in text:
        raise AssertionError(message)


def main() -> int:
    try:
        iface = CHUNK_CACHE_IFACE.read_text(encoding="utf-8")
        chunk_cache = CHUNK_CACHE_MIXIN.read_text(encoding="utf-8")
        client_level = CLIENT_LEVEL_MIXIN.read_text(encoding="utf-8")
        sodium_rsm = SODIUM_RSM_MIXIN.read_text(encoding="utf-8")
        build = BUILD.read_text(encoding="utf-8")

        require(iface, "@Nullable", "cheeky chunk lookup contract must allow absent chunks")
        require(chunk_cache, "chunk.getPos().x == x && chunk.getPos().z == z", "cheeky chunk lookup must verify stored chunk coordinates")
        require(chunk_cache, "return null;", "cheeky chunk lookup must reject mismatched or absent chunks")

        require(client_level, "ChunkStatus.FULL", "client block-change ingest must request only full chunks")
        require(client_level, "getChunk(pos.getX()>>4, pos.getZ()>>4, ChunkStatus.FULL, false)", "client block-change ingest must not force chunk loading")
        require(client_level, "if (chunk != null)", "client block-change ingest must skip missing chunks")
        reject(client_level, "self.getChunk(pos).getSection", "client block-change ingest must not blindly fetch chunk by block pos")

        require(sodium_rsm, "ChunkStatus.FULL", "Sodium section unload ingest must request only full chunks")
        require(sodium_rsm, "getChunk(x, z, ChunkStatus.FULL, false)", "Sodium section unload ingest must not force chunk loading")
        require(sodium_rsm, "if (chunk != null)", "Sodium section unload ingest must skip missing chunks")
        reject(sodium_rsm, "this.level.getChunk(x,z).getSection", "Sodium section unload ingest must not blindly fetch by x/z")

        require(build, "task validateSafeChunkIngest", "build must define safe chunk ingest validation task")
        require(build, "compileJava.dependsOn validateSafeChunkIngest", "compileJava must depend on safe chunk ingest validation")
    except AssertionError as error:
        print(f"Safe chunk ingest validation failed: {error}")
        return 1

    print("Safe chunk ingest validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
