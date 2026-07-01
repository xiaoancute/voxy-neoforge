#!/usr/bin/env python3
from pathlib import Path


SOURCE = Path("src/main/resources/assets/voxy/shaders/lod/hierarchical/screenspace.glsl")


def require(source: str, needle: str, message: str) -> None:
    if needle not in source:
        raise AssertionError(message)


def main() -> int:
    source = SOURCE.read_text()

    require(
        source,
        "any(lessThan(abs(maxBB.xy-minBB.xy-vec2(1.0f)), vec2(0.000001f)))",
        "full-screen HiZ bounds must use epsilon matching instead of exact vec2 equality",
    )
    require(
        source,
        "ivec2 mxbb = min(ivec2(ceil(maxBB.xy*ssize)),ssize-1);",
        "HiZ max texel coordinate must use ceil() for conservative coverage",
    )
    require(
        source,
        "ivec2 mnbb = ivec2(floor(minBB.xy*ssize));",
        "HiZ min texel coordinate must use floor() for conservative coverage",
    )
    require(
        source,
        "return pointSample<minBB.z-0.000001f;",
        "HiZ depth comparison must leave epsilon slack and avoid culling equal-depth nodes",
    )

    print("HiZ conservative culling validation passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"HiZ conservative culling validation failed: {error}")
        raise SystemExit(1)
