#!/usr/bin/env python3
from pathlib import Path


SOURCE = Path("src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java")


def method_body(source: str, signature: str) -> str:
    start = source.find(signature)
    if start < 0:
        raise AssertionError(f"Missing method signature: {signature}")

    brace = source.find("{", start)
    if brace < 0:
        raise AssertionError(f"Missing method body: {signature}")

    depth = 0
    for index in range(brace, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[brace + 1:index]

    raise AssertionError(f"Unclosed method body: {signature}")


def require_order(body: str, method: str, *needles: str) -> None:
    cursor = -1
    for needle in needles:
        position = body.find(needle, cursor + 1)
        if position < 0:
            raise AssertionError(f"{method} must contain `{needle}` after the previous marker")
        cursor = position


def main() -> int:
    source = SOURCE.read_text()

    render_terrain = method_body(source, "private void renderTerrain(")
    require_order(
        render_terrain,
        "renderTerrain",
        "glEnable(GL_DEPTH_TEST);",
        "glDepthFunc(GL_LEQUAL);",
        "this.terrainShader.bind();",
    )

    render_translucent = method_body(source, "public void renderTranslucent(")
    require_order(
        render_translucent,
        "renderTranslucent",
        "glEnable(GL_DEPTH_TEST);",
        "glDepthFunc(GL_LEQUAL);",
        "this.translucentTerrainShader.bind();",
    )

    build_draw_calls = method_body(source, "public void buildDrawCalls(")
    cull_start = build_draw_calls.find("this.cullShader.bind();")
    if cull_start < 0:
        raise AssertionError("buildDrawCalls must bind the cull shader")
    require_order(
        build_draw_calls[cull_start:],
        "buildDrawCalls cull pass",
        "this.cullShader.bind();",
        "glEnable(GL_DEPTH_TEST);",
        "glDepthFunc(GL_LEQUAL);",
        "glColorMask(false, false, false, false);",
    )

    print("MDIC depth state validation passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"MDIC depth state validation failed: {error}")
        raise SystemExit(1)
