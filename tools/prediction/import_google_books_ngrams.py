"""Transform a local Google Books v3 2-gram shard into dictionary TSV inputs."""

import argparse
import os
import pathlib
import sqlite3
import tempfile
import unicodedata


def score(count, maximum):
    return max(1, (count * 255 + maximum - 1) // maximum)


def aggregate(rows, minimum_count, top_targets):
    if minimum_count <= 0:
        raise ValueError("minimum_count must be positive")
    if top_targets <= 0:
        raise ValueError("top_targets must be positive")
    with tempfile.TemporaryDirectory(prefix="google-books-ngrams-") as directory:
        connection = _aggregate_to_database(
            rows, pathlib.Path(directory) / "ngrams.sqlite", minimum_count, top_targets
        )
        try:
            return (
                list(connection.execute("SELECT word, count FROM word_counts ORDER BY word")),
                [
                    (context, target, score(count, _ngram_maximum(connection)))
                    for context, target, count in connection.execute(
                        "SELECT context, target, count FROM selected ORDER BY context, target"
                    )
                ],
            )
        finally:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="local UTF-8 Google Books 2-gram shard")
    parser.add_argument("--locale", required=True)
    parser.add_argument("--words-output", required=True)
    parser.add_argument("--ngrams-output", required=True)
    parser.add_argument("--minimum-count", required=True, type=_positive_integer)
    parser.add_argument("--top-targets", required=True, type=_positive_integer)
    arguments = parser.parse_args()
    if not arguments.locale.strip():
        parser.error("--locale must not be empty")

    input_path = pathlib.Path(arguments.input)
    words_output = pathlib.Path(arguments.words_output)
    ngrams_output = pathlib.Path(arguments.ngrams_output)
    validate_paths(input_path, words_output, ngrams_output)
    with tempfile.TemporaryDirectory(prefix="google-books-ngrams-") as directory:
        with input_path.open(encoding="utf-8") as source:
            connection = _aggregate_to_database(
                source, pathlib.Path(directory) / "ngrams.sqlite",
                arguments.minimum_count, arguments.top_targets,
            )
        try:
            if not _ngram_maximum(connection):
                raise ValueError("no retained n-grams; lower --minimum-count or use another input")
            _write_outputs(connection, words_output, ngrams_output)
        finally:
            connection.close()


def validate_paths(input_path, words_output, ngrams_output):
    paths = (pathlib.Path(input_path), pathlib.Path(words_output), pathlib.Path(ngrams_output))
    resolved = [path.resolve() for path in paths]
    if len(set(resolved)) != len(resolved):
        raise ValueError("input and output paths must differ")
    for index, first in enumerate(paths):
        for second in paths[index + 1:]:
            if first.exists() and second.exists() and os.path.samefile(first, second):
                raise ValueError("input and output paths must differ")


def _aggregate_to_database(rows, database_path, minimum_count, top_targets):
    connection = sqlite3.connect(database_path)
    try:
        connection.execute(
            "CREATE TABLE edges (context TEXT NOT NULL, target TEXT NOT NULL, "
            "count INTEGER NOT NULL, PRIMARY KEY (context, target))"
        )
        for line_number, row in enumerate(rows, 1):
            context, target, match_count = _parse_row(row, line_number)
            connection.execute(
                "INSERT INTO edges(context, target, count) VALUES (?, ?, ?) "
                "ON CONFLICT(context, target) DO UPDATE SET count = count + excluded.count",
                (context, target, match_count),
            )
        connection.executescript(
            "CREATE TEMP TABLE selected (context TEXT NOT NULL, target TEXT NOT NULL, "
            "count INTEGER NOT NULL, PRIMARY KEY (context, target));"
            "CREATE TEMP TABLE word_counts (word TEXT PRIMARY KEY, count INTEGER NOT NULL);"
        )
        connection.execute(
            "INSERT INTO selected(context, target, count) "
            "WITH retained AS (SELECT context, target, count FROM edges WHERE count >= ?), "
            "ranked AS (SELECT context, target, count, ROW_NUMBER() OVER "
            "(PARTITION BY context ORDER BY count DESC, target ASC) AS position FROM retained) "
            "SELECT context, target, count FROM ranked WHERE position <= ?",
            (minimum_count, top_targets),
        )
        connection.execute(
            "INSERT INTO word_counts(word, count) "
            "SELECT word, SUM(count) FROM "
            "(SELECT context AS word, count FROM selected UNION ALL "
            "SELECT target AS word, count FROM selected) GROUP BY word"
        )
        connection.commit()
        return connection
    except Exception:
        connection.close()
        raise


def _parse_row(row, line_number):
    fields = row.rstrip("\r\n").split("\t")
    if len(fields) != 4:
        raise ValueError(f"malformed row {line_number}: expected four tab-separated fields")
    bigram, year, match_count, volume_count = fields
    tokens = bigram.split(" ")
    if len(tokens) != 2 or not all(tokens):
        raise ValueError(f"malformed row {line_number}: expected two space-separated tokens")
    if not all(value.isdecimal() and int(value) >= 0 for value in (year, match_count, volume_count)):
        raise ValueError(f"malformed row {line_number}: numeric fields must be non-negative integers")
    context, target = (unicodedata.normalize("NFC", token) for token in tokens)
    for token in (context, target):
        if any(character.isspace() or unicodedata.category(character) == "Cc" for character in token):
            raise ValueError(f"unsafe token in row {line_number}")
        if "," in token or "=" in token:
            raise ValueError(f"unsafe token in row {line_number}")
    return context, target, int(match_count)


def _write_outputs(connection, words_output, ngrams_output):
    temporary_paths = []
    backups = []
    replaced = []
    try:
        for output in (words_output, ngrams_output):
            descriptor, temporary_path = tempfile.mkstemp(
                dir=output.parent, prefix=f".{output.name}.", suffix=".tmp", text=True
            )
            os.close(descriptor)
            temporary_paths.append(pathlib.Path(temporary_path))
        word_maximum = connection.execute("SELECT MAX(count) FROM word_counts").fetchone()[0]
        with temporary_paths[0].open("w", encoding="utf-8", newline="\n") as output:
            for word, count in connection.execute("SELECT word, count FROM word_counts ORDER BY word"):
                output.write(f"{word}\t{score(count, word_maximum)}\n")
        ngram_maximum = _ngram_maximum(connection)
        with temporary_paths[1].open("w", encoding="utf-8", newline="\n") as output:
            for context, target, count in connection.execute(
                "SELECT context, target, count FROM selected ORDER BY context, target"
            ):
                output.write(f"{context}\t{target}\t{score(count, ngram_maximum)}\n")
        for output in (words_output, ngrams_output):
            if output.exists():
                descriptor, backup = tempfile.mkstemp(
                    dir=output.parent, prefix=f".{output.name}.", suffix=".bak", text=True
                )
                os.close(descriptor)
                backup_path = pathlib.Path(backup)
                os.replace(output, backup_path)
            else:
                backup_path = None
            backups.append((output, backup_path))
        for temporary_path, output in zip(temporary_paths, (words_output, ngrams_output)):
            os.replace(temporary_path, output)
            replaced.append(output)
    except Exception:
        for output in replaced:
            output.unlink(missing_ok=True)
        for output, backup_path in backups:
            if backup_path is not None and backup_path.exists():
                os.replace(backup_path, output)
        raise
    finally:
        for temporary_path in temporary_paths:
            temporary_path.unlink(missing_ok=True)
        for _, backup_path in backups:
            if backup_path is not None:
                backup_path.unlink(missing_ok=True)


def _ngram_maximum(connection):
    return connection.execute("SELECT MAX(count) FROM selected").fetchone()[0]


def _positive_integer(value):
    integer = int(value)
    if integer <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return integer


if __name__ == "__main__":
    main()
