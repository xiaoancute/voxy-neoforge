#!/usr/bin/env python3
"""
Validate that optional Distant Horizons import support does not load XZ classes
while registering general Voxy client commands.
"""

from pathlib import Path
import sys

SRC_ROOT = Path("src/main/java")
FORBIDDEN_TOKEN = "DHImporter.HasRequiredLibraries"


def main() -> int:
    violations = []

    for path in SRC_ROOT.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if FORBIDDEN_TOKEN in line:
                violations.append((path, line_no, line.strip()))

    if violations:
        print("Optional Distant Horizons boundary validation failed:")
        for path, line_no, line in violations:
            print(f"  {path}:{line_no}: {line}")
        print()
        print("Do not read DHImporter.HasRequiredLibraries from command registration.")
        print("Probe optional libraries with Class.forName string names instead.")
        return 1

    print("Optional Distant Horizons boundary validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
