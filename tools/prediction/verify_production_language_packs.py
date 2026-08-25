#!/usr/bin/env python3
"""Validate committed production pack cryptographic chains without source corpus data."""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))
from tools.prediction.build_language_pack import load_language_packs


ROOT = pathlib.Path(__file__).resolve().parents[2]


def main():
    load_language_packs(ROOT / "assets" / "latinime" / "packs" / "language_packs.json")


if __name__ == "__main__":
    main()
