#!/usr/bin/env python3
"""
Validate that optional Iris API references stay behind the Iris boundary.

Voxy must run with Sodium and without Iris installed. Direct references to
net.irisshaders.* from general client/core code can turn into NoClassDefFoundError
before IrisUtil has a chance to check ModList.
"""

from pathlib import Path
import sys

SRC_ROOT = Path("src/main/java")

ALLOWED_PREFIXES = (
    "me/cortex/voxy/client/iris/",
    "me/cortex/voxy/client/mixin/iris/",
    "me/cortex/voxy/client/core/util/IrisBridge.java",
)

FORBIDDEN_TOKENS = (
    "import net.irisshaders.",
    "net.irisshaders.",
    "import me.cortex.voxy.client.iris.",
    "me.cortex.voxy.client.iris.",
)


def is_allowed(path: Path) -> bool:
    rel = path.relative_to(SRC_ROOT).as_posix()
    return any(rel.startswith(prefix) for prefix in ALLOWED_PREFIXES)


def main() -> int:
    violations = []

    for path in SRC_ROOT.rglob("*.java"):
        if is_allowed(path):
            continue

        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if any(token in line for token in FORBIDDEN_TOKENS):
                violations.append((path, line_no, line.strip()))

    if violations:
        print("Optional Iris boundary validation failed:")
        for path, line_no, line in violations:
            print(f"  {path}:{line_no}: {line}")
        print()
        print("Move Iris-only usage into me.cortex.voxy.client.iris or IrisBridge.")
        return 1

    print("Optional Iris boundary validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
