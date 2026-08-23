#!/usr/bin/env python3
"""Build reproducible AOSP LatinIME format-202 dictionary fixtures."""

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile


COMPILER_COMMIT = "1e69dd2c04258e5e9e04bfb13b46faccf6a435b0"
COMPILER_SHA256 = "a8c5bd21f631ed0a92235d42d2fe83af5d70216172bf7e22781a9a946858237e"
COMPILER_REPOSITORY = "https://github.com/remi0s/aosp-dictionary-tools"
FORMAT = 202
BUNDLED_COMPILER = pathlib.Path(__file__).with_name("dicttool_aosp.jar")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_tsv(path, columns):
    records = []
    with path.open("r", encoding="utf-8", newline="") as source:
        lines = source.read().split("\n")
    if lines[-1] == "":
        lines.pop()
    for line_number, line in enumerate(lines, 1):
        values = line.split("\t")
        if len(values) != columns or not all(values):
            raise ValueError("%s:%d must contain %d tab-separated values" %
                             (path, line_number, columns))
        try:
            frequency = int(values[-1])
        except ValueError:
            raise ValueError("%s:%d has an invalid frequency" % (path, line_number))
        if frequency < 0:
            raise ValueError("%s:%d has a negative frequency" % (path, line_number))
        records.append(tuple(values[:-1]) + (frequency,))
    return records


def validate_combined_field(name, value):
    if any(character in ",=" or ord(character) < 32 or ord(character) == 127
           for character in value):
        raise ValueError("%s contains an AOSP combined record delimiter" % name)


def source_date_epoch():
    value = os.environ.get("SOURCE_DATE_EPOCH")
    if value is None:
        raise ValueError("SOURCE_DATE_EPOCH must be set")
    try:
        return int(value)
    except ValueError:
        raise ValueError("SOURCE_DATE_EPOCH must be an integer")


def source_metadata(path):
    metadata = json.loads(path.read_text(encoding="utf-8"))
    missing = [key for key in ("license", "provenance")
               if not isinstance(metadata.get(key), str) or not metadata[key].strip()]
    if missing:
        raise ValueError("source manifest is missing %s metadata" % " and ".join(missing))
    return metadata


def combined_text(locale, words, ngrams, timestamp):
    validate_combined_field("locale", locale)
    words = sorted(words, key=lambda record: record[0])
    ngrams = sorted(ngrams, key=lambda record: (record[0], record[1]))
    word_names = [word for word, _ in words]
    for word in word_names:
        validate_combined_field("word", word)
    for source, target, _ in ngrams:
        validate_combined_field("n-gram source", source)
        validate_combined_field("n-gram target", target)
    if len(word_names) != len(set(word_names)):
        raise ValueError("word-frequency TSV contains duplicate words")
    pairs = [(source, target) for source, target, _ in ngrams]
    for previous, current in zip(pairs, pairs[1:]):
        if previous == current:
            raise ValueError("duplicate n-gram pair: %s -> %s" % current)
    targets = {target for _, target, _ in ngrams}
    sources = {source for source, _, _ in ngrams}
    absent = sorted((targets | sources) - set(word_names))
    if absent:
        raise ValueError("n-gram words missing from word-frequency TSV: %s" % ", ".join(absent))

    attached = {}
    for source, target, frequency in ngrams:
        attached.setdefault(source, []).append((target, frequency))
    lines = ["dictionary=main:%s,locale=%s,version=1,date=%d" %
             (locale, locale, timestamp)]
    for word, frequency in words:
        lines.append("word=%s,f=%d" % (word, frequency))
        for target, bigram_frequency in attached.get(word, []):
            lines.append("bigram=%s,f=%d" % (target, bigram_frequency))
    return "\n".join(lines) + "\n"


def verify_compiler(path):
    if not path.is_file():
        raise ValueError("compiler does not exist: %s" % path)
    actual = sha256(path)
    if actual != COMPILER_SHA256:
        raise ValueError("compiler SHA-256 is %s, expected %s" %
                         (actual, COMPILER_SHA256))


def build(arguments):
    timestamp = source_date_epoch()
    words_path = pathlib.Path(arguments.words)
    ngrams_path = pathlib.Path(arguments.ngrams)
    source_manifest_path = pathlib.Path(arguments.source_manifest)
    output_path = pathlib.Path(arguments.output)
    combined_path = pathlib.Path(arguments.combined_output) if arguments.combined_output else \
        output_path.with_suffix(".combined")
    metadata = source_metadata(source_manifest_path)
    verify_compiler(pathlib.Path(arguments.compiler))
    words = read_tsv(words_path, 2)
    ngrams = read_tsv(ngrams_path, 3)
    combined_path.parent.mkdir(parents=True, exist_ok=True)
    combined_path.write_text(combined_text(arguments.locale, words, ngrams, timestamp),
                             encoding="utf-8")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["java", "-jar", arguments.compiler, "makedict", "-s",
                    str(combined_path), "-d", str(output_path)], check=True)
    manifest = {
        "locale": arguments.locale,
        "format": FORMAT,
        "source_licenses": [metadata["license"]],
        "source_provenance": [metadata["provenance"]],
        "source_hashes": {
            words_path.name: sha256(words_path),
            ngrams_path.name: sha256(ngrams_path),
            source_manifest_path.name: sha256(source_manifest_path),
        },
        "output_hash": sha256(output_path),
        "build_tool_commit": COMPILER_COMMIT,
        "build_timestamp": timestamp,
        "compiler": {
            "repository": COMPILER_REPOSITORY,
            "commit": COMPILER_COMMIT,
            "sha256": COMPILER_SHA256,
        },
    }
    manifest_path = pathlib.Path(arguments.manifest_output) if arguments.manifest_output else \
        output_path.with_suffix(".manifest.json")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2,
                                        sort_keys=True) + "\n", encoding="utf-8")


def verify_fixtures(fixtures):
    fixtures = pathlib.Path(fixtures)
    manifest = json.loads((fixtures / "manifest.json").read_text(encoding="utf-8"))
    compiler = os.environ.get("DICTTOOL_AOSP_JAR", str(BUNDLED_COMPILER))
    for locale in ("en", "gsw"):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = pathlib.Path(temporary)
            output = temporary_path / ("minimal_%s.dict" % locale)
            combined = temporary_path / ("minimal_%s.combined" % locale)
            generated_manifest = temporary_path / "manifest.json"
            build(argparse.Namespace(
                locale=locale,
                words=fixtures / ("minimal_%s.words.tsv" % locale),
                ngrams=fixtures / ("minimal_%s.ngrams.tsv" % locale),
                source_manifest=fixtures / ("minimal_%s.sources.json" % locale),
                output=output,
                combined_output=combined,
                compiler=compiler,
                manifest_output=generated_manifest))
            checked_in = fixtures / ("minimal_%s.dict" % locale)
            if sha256(output) != sha256(checked_in):
                raise ValueError("%s dictionary SHA-256 differs from regenerated fixture" % locale)
            if combined.read_bytes() != (fixtures / ("minimal_%s.combined" % locale)).read_bytes():
                raise ValueError("%s combined fixture differs from regenerated source" % locale)
            generated = json.loads(generated_manifest.read_text(encoding="utf-8"))
            if manifest.get(locale) != generated:
                raise ValueError("%s manifest differs from regenerated manifest" % locale)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-fixtures")
    parser.add_argument("--locale")
    parser.add_argument("--words")
    parser.add_argument("--ngrams")
    parser.add_argument("--source-manifest")
    parser.add_argument("--output")
    parser.add_argument("--combined-output")
    parser.add_argument("--manifest-output")
    parser.add_argument("--compiler", default=str(BUNDLED_COMPILER))
    arguments = parser.parse_args()
    try:
        if arguments.verify_fixtures:
            verify_fixtures(arguments.verify_fixtures)
        else:
            required = (arguments.locale, arguments.words, arguments.ngrams,
                        arguments.source_manifest, arguments.output)
            if not all(required):
                parser.error("locale, words, ngrams, source-manifest, output, and compiler are required")
            build(arguments)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, "error: %s\n" % error)


if __name__ == "__main__":
    main()
