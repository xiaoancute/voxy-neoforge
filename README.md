# Voxy NeoForge 1.21.1

> Unofficial NeoForge 1.21.1 port of Voxy.

[中文说明](README.zh-CN.md)

## Overview

Voxy is an LOD distant terrain renderer created by [MCRcortex](https://github.com/MCRcortex). The core LOD design and original implementation belong to the original author; this fork does not try to rewrite Voxy, but keeps it usable on the modern NeoForge 1.21.1 client rendering stack.

This fork focuses on:

- NeoForge 1.21.1 compatibility
- Sodium 0.8.12 render hook changes
- Iris shaderpack rendering
- compatibility fallbacks and crash guards for large client modpacks
- GitHub Actions builds and client smoke tests

The original Voxy mod is licensed under All Rights Reserved. Please respect the original author's licensing terms; this repository is a community port for personal use.

## Status

Alpha, but usable on the tested client stack.

| Component | Tested Version |
|-----------|----------------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.234 |
| Sodium | 0.6.13-neoforge and 0.8.12-beta.2+mc1.21.1 |
| Iris | 1.8.14-beta.1+1.21.1-neoforge |
| Forgified Fabric API | 0.116.7+2.2.0+1.21.1 |

## Supported

- LOD terrain rendering beyond vanilla render distance
- Sodium 0.6.13 and 0.8.12 render hooks
- Iris shaderpack rendering path
- Auto LOD Recovery for cases where Sodium section sync temporarily collapses Voxy bounds
- Dedicated-server companion that ingests newly generated chunks into a server-side LOD cache

## Client Commands

| Command | Purpose |
|---------|---------|
| `/voxy refresh` | Rebuilds the client renderer and re-syncs Sodium chunk sections into Voxy bounds |
| `/voxy status` | Prints the current config, storage path, renderer telemetry, Sodium sync state, and LOD recovery counters |
| `/voxy reload` | Recreates the Voxy client instance after config or storage changes |

Auto LOD Recovery is enabled by default. If distant chunks become overly simple after reconnecting or changing worlds, Voxy can queue the same renderer refresh automatically instead of requiring a manual toggle.

## Dedicated Server

The same jar can now be installed on a NeoForge 1.21.1 dedicated server. The server does not load Sodium, Iris, or OpenGL rendering code. It converts complete chunks into Voxy LOD data in the background and stores them under `<world>/voxy/server/`.

Server settings live in the world's `serverconfig/voxy-server.toml`:

- `ingestGeneratedChunks` is enabled by default and processes newly generated chunks.
- `ingestLoadedChunks` is disabled by default; enabling it also processes existing chunks at additional CPU and disk cost.
- `serviceThreads` controls the background worker count.
- `maxIngestQueue` applies backpressure during fast chunk generation.

Administrators can run `/voxy server status` to inspect the cache path, queue, and ingestion counters. This first server phase prepares and persists LOD data but does not stream it to clients yet; clients still maintain their own local cache.

## Known Compatibility

| Mod / Scenario | Status | Notes |
|----------------|--------|-------|
| Sodium 0.6.13-neoforge to 0.8.12-beta.2 | Required, tested in CI | 0.6.13 and 0.8.12 have separate render hook signatures |
| Iris 1.8.14-beta.1 | Tested | Shaderpacks work; Voxy LODs are not written into Iris shadow maps to avoid shadow pass crashes |
| C2ME 0.4.0 alpha (NeoForge) | Compatible, indirect | Only accelerates integrated-server chunk work; Voxy now budgets initial client LOD loading per frame to avoid world-join contention. C2ME does not accelerate Voxy on remote servers |
| Create / Create Aeronautics | Tested | Tested in a large Create/Aeronautics client modpack; still treated as client-side compatibility |
| Sable | Tested | Works with the current Voxy render path |
| Modern UI | Known conflict | Can cause Voxy cache to exist but not render; disable it first if LODs disappear |
| Dedicated server | Companion support | Optional; builds a server LOD cache without loading client rendering code |

Mods not listed here are not automatically incompatible; they are just not main verified targets. Small UI, cosmetic, and utility mods are intentionally not tracked one by one.

## Known Limitations

- Iris shader shadows do not include Voxy LOD terrain.
- Debug screen integration is currently disabled.
- Server-side LOD data is not streamed to clients yet.

## Installation

1. Install Minecraft 1.21.1 with NeoForge.
2. Install Sodium and Forgified Fabric API.
3. Install Iris if you want shaderpack support.
4. Build this project and place the generated jar in the client `mods` folder.
5. The server companion is optional; place the same jar in the server `mods` folder when server-side LOD pre-generation is desired.

## Build

```bash
git clone https://github.com/xiaoancute/voxy-neoforge.git
cd voxy-neoforge
./gradlew build
```

The jar is generated in `build/libs/`.

## Links

- Original Voxy: [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)
