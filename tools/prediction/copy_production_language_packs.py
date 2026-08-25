#!/usr/bin/env python3
"""Package reviewed ready language packs without their source inputs."""

import argparse
import pathlib
import shutil

from tools.prediction.build_language_pack import load_language_packs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.output.exists():
        shutil.rmtree(args.output)
    args.output.mkdir(parents=True)
    shutil.copyfile(args.registry, args.output / args.registry.name)
    for pack in load_language_packs(args.registry):
        for field in ("dictionary", "manifest", "attribution", "attestation"):
            shutil.copyfile(args.registry.parent / pack[field], args.output / pack[field])


if __name__ == "__main__":
    main()
