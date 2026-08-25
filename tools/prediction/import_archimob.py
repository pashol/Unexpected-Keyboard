"""Transform a local ArchiMob transcript export into dictionary TSV inputs."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import sqlite3
import tempfile
import unicodedata

from tools.prediction import import_google_books_ngrams


TOKEN = re.compile(r"[^\W_]+(?:['’][^\W_]+)*(?:-[^\W_]+(?:['’][^\W_]+)*)*", re.UNICODE)
MARKUP = re.compile(r"<[^>]*>")
SPEAKER_ID = re.compile(r"^\s*[A-Za-z][A-Za-z0-9_-]*:\s*")
TIMESTAMP = re.compile(r"^\s*\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\s+")
ANNOTATION = re.compile(r"\[[^]]*\]")
URL = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
SENTENCE_BOUNDARY = re.compile(r"[.!?]+")


def tokenize(sentence):
    return TOKEN.findall(unicodedata.normalize("NFC", sentence))


def filter_line(line):
    if (
        MARKUP.search(line)
        or ANNOTATION.search(line)
        or URL.search(line)
        or TIMESTAMP.match(line)
        or any(unicodedata.category(character) == "Cc" for character in line)
    ):
        return None
    return SPEAKER_ID.sub("", unicodedata.normalize("NFC", line)).strip()


def count_bigrams(tokens):
    counts = {}
    for context, target in zip(tokens, tokens[1:]):
        counts[(context, target)] = counts.get((context, target), 0) + 1
    return counts


def aggregate(lines, minimum_count, top_targets):
    if minimum_count <= 0:
        raise ValueError("minimum_count must be positive")
    if top_targets <= 0:
        raise ValueError("top_targets must be positive")
    edge_counts = {}
    report = {"accepted_lines": 0, "rejected_lines": 0}
    for line in lines:
        filtered = filter_line(line.rstrip("\r\n"))
        if filtered is None:
            report["rejected_lines"] += 1
            continue
        report["accepted_lines"] += 1
        for sentence in SENTENCE_BOUNDARY.split(filtered):
            for edge, count in count_bigrams(tokenize(sentence)).items():
                edge_counts[edge] = edge_counts.get(edge, 0) + count
    selected = {}
    for context in sorted({context for context, _ in edge_counts}):
        targets = sorted(
            (
                (target, count) for (candidate, target), count in edge_counts.items()
                if candidate == context and count >= minimum_count
            ),
            key=lambda item: (-item[1], item[0]),
        )[:top_targets]
        for target, count in targets:
            selected[(context, target)] = count
    word_counts = {}
    for (context, target), count in selected.items():
        word_counts[context] = word_counts.get(context, 0) + count
        word_counts[target] = word_counts.get(target, 0) + count
    words, ngrams = _scored_counts(word_counts, selected)
    return words, ngrams, report


def _scored_counts(word_counts, selected):
    maximum = max(selected.values(), default=0)
    ngrams = [
        (context, target, import_google_books_ngrams.score(count, maximum))
        for (context, target), count in sorted(selected.items())
    ]
    return sorted(word_counts.items()), ngrams


def load_current_generation(words_output, ngrams_output):
    return import_google_books_ngrams.load_current_generation(words_output, ngrams_output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--words-output", required=True, type=pathlib.Path)
    parser.add_argument("--ngrams-output", required=True, type=pathlib.Path)
    parser.add_argument("--report-output", required=True, type=pathlib.Path)
    parser.add_argument("--minimum-count", required=True, type=_positive_integer)
    parser.add_argument("--top-targets", required=True, type=_positive_integer)
    arguments = parser.parse_args()
    _validate_source_sha256(arguments.source_sha256)
    import_google_books_ngrams.validate_paths(
        arguments.input, arguments.words_output, arguments.ngrams_output
    )
    if arguments.report_output.resolve() in {
        arguments.input.resolve(), arguments.words_output.resolve(), arguments.ngrams_output.resolve(),
    }:
        raise ValueError("input, output, and report paths must differ")
    actual_hash = _sha256(arguments.input)
    if actual_hash != arguments.source_sha256:
        raise ValueError("source SHA-256 does not match --source-sha256")
    with arguments.input.open(encoding="utf-8") as source:
        words, ngrams, report = aggregate(source, arguments.minimum_count, arguments.top_targets)
    if not ngrams:
        raise ValueError("no retained n-grams; lower --minimum-count or use another input")
    _publish_generation(words, ngrams, arguments.words_output, arguments.ngrams_output)
    report["source_sha256"] = actual_hash
    _publish_report(report, arguments.report_output)


def _publish_generation(words, ngrams, words_output, ngrams_output):
    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(
            "CREATE TABLE selected (context TEXT NOT NULL, target TEXT NOT NULL, count INTEGER NOT NULL);"
            "CREATE TABLE word_counts (word TEXT PRIMARY KEY, count INTEGER NOT NULL);"
        )
        connection.executemany(
            "INSERT INTO word_counts(word, count) VALUES (?, ?)", words
        )
        connection.executemany(
            "INSERT INTO selected(context, target, count) VALUES (?, ?, ?)",
            [(context, target, score) for context, target, score in ngrams],
        )
        import_google_books_ngrams._publish_generation(connection, words_output, ngrams_output)
    finally:
        connection.close()


def _publish_report(report, output_path):
    output_path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        dir=output_path.parent, prefix=f".{output_path.name}.", suffix=".tmp", text=True
    )
    temporary_path = pathlib.Path(temporary)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            json.dump(report, output, sort_keys=True, separators=(",", ":"))
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary_path, output_path)
        import_google_books_ngrams._sync_directory(output_path.parent)
    finally:
        temporary_path.unlink(missing_ok=True)


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_source_sha256(value):
    if len(value) != 64:
        raise ValueError("--source-sha256 must be a SHA-256 hash")
    try:
        int(value, 16)
    except ValueError as error:
        raise ValueError("--source-sha256 must be a SHA-256 hash") from error


def _positive_integer(value):
    integer = int(value)
    if integer <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return integer


if __name__ == "__main__":
    main()
