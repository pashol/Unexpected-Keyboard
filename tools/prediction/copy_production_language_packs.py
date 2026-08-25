#!/usr/bin/env python3
"""Package reviewed ready language packs without their source inputs."""

import argparse
import pathlib
import shutil
import tempfile

from tools.prediction.build_language_pack import load_language_packs


def copy_packs(registry, output):
    registry = registry.resolve()
    output = output.resolve()
    source_directory = registry.parent
    if output == source_directory or source_directory in output.parents or output in source_directory.parents:
        raise ValueError("output must not equal, contain, or be inside the registry source directory")
    packs = load_language_packs(registry)
    if not packs:
        raise ValueError("production registry must contain a ready pack")
    inputs = [registry]
    for pack in packs:
        inputs.extend((source_directory / pack[field]).resolve()
                      for field in ("dictionary", "manifest", "attribution", "attestation"))
    if output in inputs:
        raise ValueError("output must not alias an input file")
    if output.exists() and any(output.samefile(path) for path in inputs):
        raise ValueError("output must not alias an input file")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(tempfile.mkdtemp(prefix=output.name + ".", dir=output.parent))
    backup = output.with_name(output.name + ".previous")
    try:
        shutil.copyfile(registry, staging / registry.name)
        for pack in packs:
            for field in ("dictionary", "manifest", "attribution", "attestation"):
                shutil.copyfile(source_directory / pack[field], staging / pack[field])
        if backup.exists():
            raise ValueError("output backup path already exists")
        if output.exists():
            output.rename(backup)
        staging.rename(output)
        if backup.exists():
            shutil.rmtree(backup)
    except Exception:
        if not output.exists() and backup.exists():
            backup.rename(output)
        raise
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        copy_packs(args.registry, args.output)
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
