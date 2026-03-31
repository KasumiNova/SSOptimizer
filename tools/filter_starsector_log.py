#!/usr/bin/env python3
"""Filter noisy Starsector log lines for faster debugging.

Usage:
    python tools/filter_starsector_log.py /path/to/starsector.log
    cat starsector.log | python tools/filter_starsector_log.py -
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

KEEP_PATTERNS = [
    re.compile(r"\[SSOptimizer\]"),
    re.compile(r"\[SSOptimizer-Native\]"),
    re.compile(r"\bERROR\b"),
    re.compile(r"\bWARN\b"),
    re.compile(r"Exception"),
    re.compile(r"Caused by:"),
    re.compile(r"Traceback \(most recent call last\):"),
]


def should_keep(line: str) -> bool:
    return any(pattern.search(line) for pattern in KEEP_PATTERNS)


def iter_lines(source: str):
    if source == "-":
        yield from sys.stdin
    else:
        path = Path(source)
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            yield from fh


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: filter_starsector_log.py <log-file>|-", file=sys.stderr)
        return 2

    try:
        for raw_line in iter_lines(argv[1]):
            line = raw_line.rstrip("\n")
            if should_keep(line):
                print(line)
    except BrokenPipeError:
        try:
            sys.stdout.close()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
