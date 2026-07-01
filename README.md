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
| Sodium | 0.8.12-beta.2+mc1.21.1 |
| Iris | 1.8.14-beta.1+1.21.1-neoforge |
| Forgified Fabric API | 0.116.7+2.2.0+1.21.1 |

## Supported

- LOD terrain rendering beyond vanilla render distance
- Sodium 0.8.12 render hooks
- Iris shaderpack rendering path

## Known Compatibility

| Mod / Scenario | Status | Notes |
|----------------|--------|-------|
| Sodium 0.8.12-beta.2 | Required, tested | Older Sodium versions are not a target |
| Iris 1.8.14-beta.1 | Tested | Shaderpacks work; Voxy LODs are not written into Iris shadow maps to avoid shadow pass crashes |
| Create / Create Aeronautics | Tested | Tested in a large Create/Aeronautics client modpack; still treated as client-side compatibility |
| Sable | Tested | Works with the current Voxy render path |
| Modern UI | Known conflict | Can cause Voxy cache to exist but not render; disable it first if LODs disappear |
| Dedicated server | Not supported, not needed | This is a client-side rendering mod; do not install it on a dedicated server |

Mods not listed here are not automatically incompatible; they are just not main verified targets. Small UI, cosmetic, and utility mods are intentionally not tracked one by one.

## Known Limitations

- Iris shader shadows do not include Voxy LOD terrain.
- Debug screen integration is currently disabled.

## Installation

1. Install Minecraft 1.21.1 with NeoForge.
2. Install Sodium and Forgified Fabric API.
3. Install Iris if you want shaderpack support.
4. Build this project and place the generated jar in the client `mods` folder.
5. Do not place it in a server `mods` folder.

## Build

```bash
git clone https://github.com/xiaoancute/voxy-neoforge.git
cd voxy-neoforge
./gradlew build
```

The jar is generated in `build/libs/`.

## Links

- Original Voxy: [github.com/MCRcortex/voxy](https://github.com/MCRcortex/voxy)
