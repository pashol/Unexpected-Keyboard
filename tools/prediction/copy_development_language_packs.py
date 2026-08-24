#!/usr/bin/env python3
"""Package only registry-approved development language packs."""

import argparse
import pathlib
import shutil

from build_language_pack import load_language_packs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for stale in args.output.glob("development_*_fixture.dict"):
        stale.unlink()
    for pack in load_language_packs(args.registry, development_only=True):
        source = args.registry.parent / pack["dictionary"]
        shutil.copyfile(source, args.output / ("development_" + pack["locale"] + "_fixture.dict"))


if __name__ == "__main__":
    main()
