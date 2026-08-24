#!/usr/bin/env python3
"""Build a reproducible format-202 LatinIME test dictionary without Soong."""

import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import unicodedata
from datetime import datetime, timezone


AOSP_COMMIT = "8081a1d8572f78488900438a6eaaec232b882bbf"
FORMAT_MAGIC = bytes.fromhex("9bc13afe")
FORMAT_VERSION = 202
SHA256_HEX_LENGTH = 64
GENERATED_SOURCE_NAMES = {"ngram_tsv", "word_frequency_tsv"}


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


def compiler_inputs(source, compatibility):
    return [
        ("aosp/" + str(path.relative_to(source)), path)
        for path in source_files(source)
    ] + [
        ("generated/" + str(path.relative_to(compatibility)), path)
        for path in sorted(compatibility.rglob("*.java"))
    ]


def jdk_identity():
    def version(command):
        completed = subprocess.run(command, check=True, capture_output=True, text=True)
        return (completed.stdout + completed.stderr).strip()

    return {"java_version": version(["java", "-version"]), "javac_version": version(["javac", "-version"])}


def compiler_identity(inputs):
    digest = hashlib.sha256()
    for name, path in inputs:
        digest.update(name.encode("utf-8") + b"\0")
        digest.update(path.read_bytes())
    builder = pathlib.Path(__file__).resolve()
    return {
        "aosp_revision": AOSP_COMMIT,
        "builder": {"path": builder.name, "sha256": sha256(builder)},
        "input_sha256": digest.hexdigest(),
        "jdk": jdk_identity(),
    }


def build_dicttool(source, classes):
    with tempfile.TemporaryDirectory(prefix="latinime-dicttool-") as temporary_directory:
        compatibility = write_compatibility_sources(pathlib.Path(temporary_directory))
        identity = compiler_identity(compiler_inputs(source, pathlib.Path(temporary_directory)))
        run([
            "javac", "-encoding", "UTF-8", "-d", str(classes),
            *(str(path) for path in source_files(source)),
            *(str(path) for path in compatibility),
        ])
    return identity


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize_word(locale, word):
    # Swiss German is intentionally only Unicode-normalized, never dialect-normalized.
    return unicodedata.normalize("NFC", word)


def tsv_rows(path, columns):
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        fields = line.split("\t")
        if len(fields) != columns:
            raise ValueError(str(path) + ":" + str(line_number) + " has wrong column count")
        try:
            frequency = int(fields[-1])
        except ValueError as error:
            raise ValueError(str(path) + ":" + str(line_number) + " has invalid frequency") from error
        if not 1 <= frequency <= 255:
            raise ValueError(str(path) + ":" + str(line_number) + " frequency must be 1..255")
        rows.append((fields[:-1], frequency))
    return rows


def combined_source(locale, word_frequency_tsv, ngram_tsv):
    words = {}
    for fields, frequency in tsv_rows(word_frequency_tsv, 2):
        word = normalize_word(locale, fields[0])
        words[word] = max(words.get(word, 0), frequency)
    bigrams = {}
    for fields, frequency in tsv_rows(ngram_tsv, 3):
        context, target = (normalize_word(locale, word) for word in fields)
        if context not in words or target not in words:
            raise ValueError("every n-gram word must appear in the word-frequency TSV")
        bigrams[(context, target)] = max(bigrams.get((context, target), 0), frequency)
    lines = ["dictionary=main,locale=" + locale]
    for word in sorted(words):
        lines.append("word=" + word + ",f=" + str(words[word]))
        for (context, target), frequency in sorted(bigrams.items()):
            if context == word:
                lines.append("bigram=" + target + ",f=" + str(frequency))
    return "\n".join(lines) + "\n"


def source_hash(path):
    return {"path": path.name, "sha256": sha256(path)}


def validate_provenance(provenance):
    if not isinstance(provenance, dict) or not provenance:
        raise ValueError("provenance must be a nonempty object")
    if GENERATED_SOURCE_NAMES.intersection(provenance):
        raise ValueError("provenance must not use reserved generated source names")
    for name, source in provenance.items():
        if not isinstance(name, str) or not isinstance(source, dict):
            raise ValueError("provenance sources must be objects")
        license_name = source.get("license")
        source_sha256 = source.get("source_sha256")
        if not isinstance(license_name, str) or not license_name:
            raise ValueError("provenance sources must include a license")
        if not isinstance(source_sha256, str) or len(source_sha256) != SHA256_HEX_LENGTH:
            raise ValueError("provenance sources must include a SHA-256 source hash")
        try:
            int(source_sha256, 16)
        except ValueError as error:
            raise ValueError("provenance sources must include a SHA-256 source hash") from error
    return provenance


def manifest_data(locale, word_frequency_tsv, ngram_tsv, combined, output, provenance, epoch, compiler):
    return {
        "compiler": compiler,
        "combined_source_sha256": sha256(combined),
        "format_version": FORMAT_VERSION,
        "locale": locale,
        "output_sha256": sha256(output),
        "sources": {
            **provenance,
            "ngram_tsv": source_hash(ngram_tsv),
            "word_frequency_tsv": source_hash(word_frequency_tsv),
        },
        "timestamp": datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def validate_paths(input_path, output, manifest):
    paths = {
        "input": input_path.resolve(),
        "output": output.resolve(),
        "manifest": manifest.resolve(),
    }
    for first, second in (("input", "output"), ("input", "manifest"), ("output", "manifest")):
        try:
            same_file = paths[first].samefile(paths[second])
        except FileNotFoundError:
            same_file = False
        if paths[first] == paths[second] or same_file:
            raise ValueError(first + " and " + second + " paths must differ")


def build(source, input_path, output, manifest, metadata):
    with tempfile.TemporaryDirectory(prefix="latinime-classes-") as temporary_directory:
        classes = pathlib.Path(temporary_directory) / "classes"
        classes.mkdir()
        compiler = build_dicttool(source, classes)
        run([
            "java", "-cp", str(classes), "com.android.inputmethod.latin.dicttool.Dicttool",
            "makedict", "-s", str(input_path), "-d", str(output),
        ])
    header = output.read_bytes()[:6]
    if header[:4] != FORMAT_MAGIC or int.from_bytes(header[4:6], "big") != FORMAT_VERSION:
        raise ValueError("dicttool did not produce a format-202 dictionary")
    manifest.write_text(json.dumps(metadata(compiler), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--locale", required=True)
    parser.add_argument("--word-frequency-tsv", required=True, type=pathlib.Path)
    parser.add_argument("--ngram-tsv", required=True, type=pathlib.Path)
    parser.add_argument("--provenance", required=True, type=pathlib.Path)
    parser.add_argument("--combined-output", type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not args.word_frequency_tsv.is_file() or not args.ngram_tsv.is_file() or not args.provenance.is_file():
        parser.error("TSV inputs and provenance must name existing files")
    try:
        provenance = validate_provenance(json.loads(args.provenance.read_text(encoding="utf-8")))
        epoch = int(os.environ["SOURCE_DATE_EPOCH"])
    except (json.JSONDecodeError, KeyError, ValueError) as error:
        parser.error("provenance must be JSON and SOURCE_DATE_EPOCH must be an integer: " + str(error))
    input_path = args.combined_output or args.output.with_suffix(".combined")
    metadata = lambda compiler_hash: manifest_data(
        args.locale, args.word_frequency_tsv, args.ngram_tsv, input_path, args.output, provenance, epoch, compiler_hash
    )
    try:
        validate_paths(input_path, args.output, args.manifest)
    except ValueError as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    input_path.parent.mkdir(parents=True, exist_ok=True)
    input_path.write_text(combined_source(args.locale, args.word_frequency_tsv, args.ngram_tsv), encoding="utf-8")
    build(verified_checkout(args.source), input_path, args.output, args.manifest, metadata)


if __name__ == "__main__":
    main()
