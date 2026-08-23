#!/usr/bin/env python3
"""Build a reproducible format-202 LatinIME test dictionary without Soong."""

import argparse
import hashlib
import json
import pathlib
import subprocess
import tempfile


AOSP_COMMIT = "8081a1d8572f78488900438a6eaaec232b882bbf"
FORMAT_MAGIC = bytes.fromhex("9bc13afe")
FORMAT_VERSION = 202


def run(command, **kwargs):
    subprocess.run(command, check=True, **kwargs)


def verified_checkout(source):
    source = source.resolve()
    commit = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if commit != AOSP_COMMIT:
        raise ValueError("AOSP checkout must be at " + AOSP_COMMIT)
    if subprocess.run(
        ["git", "-C", str(source), "status", "--porcelain"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout:
        raise ValueError("AOSP checkout must be clean")
    return source


def source_files(source):
    def files(pattern):
        return sorted(source.glob(pattern))

    required = [
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/Dicttool.java",
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/Makedict.java",
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/DictionaryMaker.java",
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/CombinedInputOutput.java",
        source / "java/src/com/android/inputmethod/latin/BinaryDictionary.java",
        source / "java/src/com/android/inputmethod/latin/DicTraverseSession.java",
        source / "java/src/com/android/inputmethod/latin/Dictionary.java",
        source / "java/src/com/android/inputmethod/latin/NgramContext.java",
        source / "java/src/com/android/inputmethod/latin/SuggestedWords.java",
        source / "java/src/com/android/inputmethod/latin/settings/SettingsValuesForSuggestion.java",
        source / "java/src/com/android/inputmethod/latin/utils/BinaryDictionaryUtils.java",
        source / "java/src/com/android/inputmethod/latin/utils/CombinedFormatUtils.java",
        source / "java/src/com/android/inputmethod/latin/utils/JniUtils.java",
        source / "java/src/com/android/inputmethod/latin/define/DebugFlags.java",
        source / "java/src/com/android/inputmethod/latin/define/DecoderSpecificConstants.java",
        source / "tests/src/com/android/inputmethod/latin/utils/ByteArrayDictBuffer.java",
    ]
    required.extend(files("common/src/**/*.java"))
    required.extend(files("java/src/com/android/inputmethod/latin/makedict/**/*.java"))
    required.extend(files("tools/dicttool/compat/**/*.java"))
    required.extend(
        path for path in files("tests/src/com/android/inputmethod/latin/makedict/*.java")
        if path.name != "BinaryDictDecoderEncoderTests.java"
    )
    required = [
        path for path in required
        if path.name not in {"AndroidTestCase.java", "LargeTest.java"}
    ]
    if not all(path.is_file() for path in required):
        raise ValueError("AOSP checkout lacks a required dicttool source")
    return required


def write_compatibility_sources(directory):
    annotations = directory / "javax/annotation"
    commands = directory / "com/android/inputmethod/latin/dicttool"
    annotations.mkdir(parents=True)
    commands.mkdir(parents=True)
    (annotations / "Nonnull.java").write_text(
        "package javax.annotation; public @interface Nonnull {}\n", encoding="utf-8"
    )
    (annotations / "Nullable.java").write_text(
        "package javax.annotation; public @interface Nullable {}\n", encoding="utf-8"
    )
    (commands / "CommandList.java").write_text(
        "package com.android.inputmethod.latin.dicttool; "
        "public class CommandList { public static void populate() { "
        "Dicttool.addCommand(\"makedict\", Makedict.class); } }\n",
        encoding="utf-8",
    )
    return sorted(directory.rglob("*.java"))


def build_dicttool(source, classes):
    with tempfile.TemporaryDirectory(prefix="latinime-dicttool-") as temporary_directory:
        compatibility = write_compatibility_sources(pathlib.Path(temporary_directory))
        run([
            "javac", "-encoding", "UTF-8", "-d", str(classes),
            *(str(path) for path in source_files(source)),
            *(str(path) for path in compatibility),
        ])


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_paths(input_path, output, manifest):
    paths = {
        "input": input_path.resolve(),
        "output": output.resolve(),
        "manifest": manifest.resolve(),
    }
    for first, second in (("input", "output"), ("input", "manifest"), ("output", "manifest")):
        if paths[first] == paths[second]:
            raise ValueError(first + " and " + second + " paths must differ")


def build(source, input_path, output, manifest):
    with tempfile.TemporaryDirectory(prefix="latinime-classes-") as temporary_directory:
        classes = pathlib.Path(temporary_directory) / "classes"
        classes.mkdir()
        build_dicttool(source, classes)
        run([
            "java", "-cp", str(classes), "com.android.inputmethod.latin.dicttool.Dicttool",
            "makedict", "-s", str(input_path), "-d", str(output),
        ])
    header = output.read_bytes()[:6]
    if header[:4] != FORMAT_MAGIC or int.from_bytes(header[4:6], "big") != FORMAT_VERSION:
        raise ValueError("dicttool did not produce a format-202 dictionary")
    manifest.write_text(json.dumps({
        "aosp_commit": AOSP_COMMIT,
        "format_version": FORMAT_VERSION,
        "input_sha256": sha256(input_path),
        "locale": "en",
        "output_sha256": sha256(output),
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("--input must name an existing .combined file")
    try:
        validate_paths(args.input, args.output, args.manifest)
    except ValueError as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    build(verified_checkout(args.source), args.input, args.output, args.manifest)


if __name__ == "__main__":
    main()
