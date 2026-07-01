#!/usr/bin/env python3
import re
import sys
from pathlib import Path


SHADER_ROOT = Path("src/main/resources/assets/voxy/shaders")
SHADER_SUFFIXES = {".comp", ".frag", ".fsh", ".vert", ".vsh"}
VERSION_RE = re.compile(r"^\s*#version\s+(\d+)\b")
BINDING_RE = re.compile(r"\blayout\s*\([^)]*\bbinding\s*=")
UNIFORM_LOCATION_RE = re.compile(r"\blayout\s*\([^)]*\blocation\s*=[^)]*\)\s*uniform\b")


def shader_version(lines):
    for line in lines:
        match = VERSION_RE.match(line)
        if match:
            return int(match.group(1))
    return None


def strip_line_comment(line):
    return line.split("//", 1)[0]


def main():
    failures = []

    for path in sorted(SHADER_ROOT.rglob("*")):
        if path.suffix not in SHADER_SUFFIXES:
            continue

        lines = path.read_text(encoding="utf-8").splitlines()
        version = shader_version(lines)
        if version is None:
            continue

        for line_no, line in enumerate(lines, 1):
            source = strip_line_comment(line)
            if version < 420 and BINDING_RE.search(source):
                failures.append((path, line_no, version, "layout(binding) requires GLSL 420+"))
            if version < 430 and UNIFORM_LOCATION_RE.search(source):
                failures.append((path, line_no, version, "layout(location) on uniforms requires GLSL 430+"))

    if failures:
        print("Invalid shader version declarations:")
        for path, line_no, version, message in failures:
            print(f"  {path}:{line_no}: #version {version}: {message}")
        return 1

    print("Shader version validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
