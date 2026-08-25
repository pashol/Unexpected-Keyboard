"""Transform a local Google Books v3 2-gram shard into dictionary TSV inputs."""

import argparse
import hashlib
import json
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
            _publish_generation(connection, words_output, ngrams_output)
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
    if words_output.resolve().parent != ngrams_output.resolve().parent:
        raise ValueError("output paths must have the same parent directory")


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


def current_manifest_path(words_output, ngrams_output):
    words_output = pathlib.Path(words_output)
    ngrams_output = pathlib.Path(ngrams_output)
    return words_output.parent / f".{words_output.name}.{ngrams_output.name}.current.json"


def generation_root_path(words_output, ngrams_output):
    words_output = pathlib.Path(words_output)
    ngrams_output = pathlib.Path(ngrams_output)
    return words_output.parent / f".{words_output.name}.{ngrams_output.name}.generations"


def load_current_generation(words_output, ngrams_output):
    pointer = current_manifest_path(words_output, ngrams_output)
    data = json.loads(pointer.read_text(encoding="utf-8"))
    if data.get("format_version") != 1:
        raise ValueError("invalid current generation manifest")
    files = []
    expected_names = {"words": pathlib.Path(words_output).name, "ngrams": pathlib.Path(ngrams_output).name}
    root = generation_root_path(words_output, ngrams_output).resolve()
    for kind in ("words", "ngrams"):
        entry = data.get(kind)
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            raise ValueError("invalid current generation manifest")
        path = (pointer.parent / entry["path"]).resolve()
        if path.name != expected_names[kind] or root not in path.parents:
            raise ValueError("current generation names a wrong pair")
        if pointer.parent.resolve() not in path.parents:
            raise ValueError("current generation path is outside the output directory")
        if _sha256(path) != entry.get("sha256"):
            raise ValueError("current generation hash does not match")
        files.append(path)
    return tuple(files)


def _publish_generation(connection, words_output, ngrams_output, report=None, report_receipt_output=None):
    generation_root = generation_root_path(words_output, ngrams_output)
    generation_root.mkdir(exist_ok=True)
    generation = pathlib.Path(tempfile.mkdtemp(prefix="generation-", dir=generation_root))
    words_path = generation / words_output.name
    ngrams_path = generation / ngrams_output.name
    report_path = None
    pointer_temporary = None
    receipt_temporary = None
    try:
        word_maximum = connection.execute("SELECT MAX(count) FROM word_counts").fetchone()[0]
        with words_path.open("w", encoding="utf-8", newline="\n") as output:
            for word, count in connection.execute("SELECT word, count FROM word_counts ORDER BY word"):
                output.write(f"{word}\t{score(count, word_maximum)}\n")
            output.flush()
            os.fsync(output.fileno())
        ngram_maximum = _ngram_maximum(connection)
        with ngrams_path.open("w", encoding="utf-8", newline="\n") as output:
            for context, target, count in connection.execute(
                "SELECT context, target, count FROM selected ORDER BY context, target"
            ):
                output.write(f"{context}\t{target}\t{score(count, ngram_maximum)}\n")
            output.flush()
            os.fsync(output.fileno())
        if report is not None:
            report_name, report_contents = report
            report_path = generation / report_name
            with report_path.open("wb") as output:
                output.write(report_contents)
                output.flush()
                os.fsync(output.fileno())
        _sync_directory(generation)
        _sync_directory(generation_root)
        pointer = current_manifest_path(words_output, ngrams_output)
        manifest = {
            "format_version": 1,
            "words": {
                "path": str(words_path.relative_to(pointer.parent)),
                "sha256": _sha256(words_path),
            },
            "ngrams": {
                "path": str(ngrams_path.relative_to(pointer.parent)),
                "sha256": _sha256(ngrams_path),
            },
        }
        if report_path is not None:
            manifest["report"] = {
                "path": str(report_path.relative_to(pointer.parent)),
                "sha256": _sha256(report_path),
            }
        manifest_contents = json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n"
        if report_receipt_output is not None:
            if report_path is None:
                raise ValueError("a report receipt requires a report")
            receipt_output = pathlib.Path(report_receipt_output)
            receipt_output.parent.mkdir(parents=True, exist_ok=True)
            receipt = {
                "active_generation": {
                    "current_manifest_sha256": hashlib.sha256(
                        manifest_contents.encode("utf-8")
                    ).hexdigest(),
                    "report_sha256": manifest["report"]["sha256"],
                },
                "format_version": 1,
            }
            descriptor, temporary = tempfile.mkstemp(
                dir=receipt_output.parent, prefix=f".{receipt_output.name}.", suffix=".tmp", text=True
            )
            receipt_temporary = pathlib.Path(temporary)
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
                json.dump(receipt, output, sort_keys=True, separators=(",", ":"))
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
        descriptor, temporary = tempfile.mkstemp(
            dir=pointer.parent, prefix=f".{pointer.name}.", suffix=".tmp", text=True
        )
        pointer_temporary = pathlib.Path(temporary)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
            output.write(manifest_contents)
            output.flush()
            os.fsync(output.fileno())
        if receipt_temporary is not None:
            os.replace(receipt_temporary, receipt_output)
            receipt_temporary = None
            _sync_directory(receipt_output.parent)
        os.replace(pointer_temporary, pointer)
        _sync_directory(pointer.parent)
    finally:
        if pointer_temporary is not None:
            pointer_temporary.unlink(missing_ok=True)
        if receipt_temporary is not None:
            receipt_temporary.unlink(missing_ok=True)


def _ngram_maximum(connection):
    return connection.execute("SELECT MAX(count) FROM selected").fetchone()[0]


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sync_directory(directory):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _positive_integer(value):
    integer = int(value)
    if integer <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return integer


if __name__ == "__main__":
    main()
