"""Transform a local ArchiMob transcript export into dictionary TSV inputs."""

import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import sqlite3
import tempfile
import unicodedata
import xml.etree.ElementTree as element_tree
import zipfile

from tools.prediction import import_google_books_ngrams


TOKEN = re.compile(r"[^\W_]+(?:['’][^\W_]+)*(?:-[^\W_]+(?:['’][^\W_]+)*)*", re.UNICODE)
MARKUP = re.compile(r"<[^>]*>")
SPEAKER_ID = re.compile(r"^\s*[A-Za-z][A-Za-z0-9_-]*:\s*")
TIMESTAMP = re.compile(r"^\s*\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\s+")
ANNOTATION = re.compile(r"\[[^]]*\]")
URL = re.compile(r"(?:https?://|www\.)\S+", re.IGNORECASE)
SENTENCE_BOUNDARY = re.compile(r"[.!?]+")
TEI_NAMESPACE = "{http://www.tei-c.org/ns/1.0}"
ARCHIMOB_RELEASE_ARCHIVE = "Archimob_Release_2.zip"
TRANSCRIPT_MEMBER = re.compile(r"^Archimob_Release_2/\d+(?:_\d+)?\.xml$")
MAX_OUTER_COMPRESSED_BYTES = 64 * 1024 * 1024
MAX_OUTER_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
MAX_NESTED_ARCHIVE_COMPRESSED_BYTES = 64 * 1024 * 1024
MAX_NESTED_ARCHIVE_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
MAX_NESTED_COMPRESSED_BYTES = 64 * 1024 * 1024
MAX_NESTED_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
MAX_TRANSCRIPT_XML_MEMBERS = 64
MAX_TRANSCRIPT_XML_BYTES = 4 * 1024 * 1024
MAX_TRANSCRIPT_XML_TOTAL_BYTES = 60 * 1024 * 1024
MAX_COMPRESSION_RATIO = 100
COPY_CHUNK_BYTES = 1024 * 1024
MAX_SOURCE_INPUT_BYTES = 64 * 1024 * 1024
MAX_OUTER_ARCHIVE_MEMBERS = 16
MAX_NESTED_ARCHIVE_MEMBERS = 64


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
    report = {"accepted_lines": 0, "rejected_lines": 0}
    sequences = []
    for line in lines:
        filtered = filter_line(line.rstrip("\r\n"))
        if filtered is None:
            report["rejected_lines"] += 1
            continue
        report["accepted_lines"] += 1
        for sentence in SENTENCE_BOUNDARY.split(filtered):
            sequences.append(tokenize(sentence))
    return aggregate_sequences(sequences, minimum_count, top_targets, report)


def aggregate_sequences(sequences, minimum_count, top_targets, report):
    edge_counts = {}
    for tokens in sequences:
        for edge, count in count_bigrams(tokens).items():
            edge_counts[edge] = edge_counts.get(edge, 0) + count
    targets_by_context = {}
    for (context, target), count in edge_counts.items():
        if count >= minimum_count:
            targets_by_context.setdefault(context, []).append((target, count))
    selected = {}
    for context, candidates in sorted(targets_by_context.items()):
        targets = sorted(candidates, key=lambda item: (-item[1], item[0]))[:top_targets]
        for target, count in targets:
            selected[(context, target)] = count
    word_counts = {}
    for (context, target), count in selected.items():
        word_counts[context] = word_counts.get(context, 0) + count
        word_counts[target] = word_counts.get(target, 0) + count
    words, ngrams = _scored_counts(word_counts, selected)
    return words, ngrams, report


def archive_sequences(source):
    try:
        with zipfile.ZipFile(source) as outer:
            outer_members = outer.infolist()
            if len(outer_members) > MAX_OUTER_ARCHIVE_MEMBERS:
                raise ValueError("outer archive entry count exceeds limit")
            _validate_archive_size(outer_members, MAX_OUTER_COMPRESSED_BYTES, MAX_OUTER_UNCOMPRESSED_BYTES, "outer archive")
            members = [
                member for member in outer.infolist()
                if not member.is_dir() and member.filename == ARCHIMOB_RELEASE_ARCHIVE
            ]
            if len(members) != 1:
                raise ValueError("archive must contain exactly one " + ARCHIMOB_RELEASE_ARCHIVE)
            _validate_entry_size(
                members[0], MAX_NESTED_ARCHIVE_COMPRESSED_BYTES,
                MAX_NESTED_ARCHIVE_UNCOMPRESSED_BYTES, "nested archive",
            )
            with tempfile.TemporaryFile(prefix="archimob-nested-", suffix=".zip") as nested_file:
                with outer.open(members[0]) as nested_source:
                    _copy_limited(nested_source, nested_file, MAX_NESTED_ARCHIVE_UNCOMPRESSED_BYTES, "nested archive")
                nested_file.seek(0)
                with zipfile.ZipFile(nested_file) as nested:
                    members = nested.infolist()
                    if len(members) > MAX_NESTED_ARCHIVE_MEMBERS:
                        raise ValueError("nested archive entry count exceeds limit")
                    _validate_archive_size(
                        members, MAX_NESTED_COMPRESSED_BYTES, MAX_NESTED_UNCOMPRESSED_BYTES,
                        "nested archive",
                    )
                    if any(
                        pathlib.PurePosixPath(member.filename).is_absolute()
                        or ".." in pathlib.PurePosixPath(member.filename).parts
                        for member in members
                    ):
                        raise ValueError("nested archive contains an unsafe member path")
                    xml_members = [
                        member for member in members
                        if not member.is_dir() and TRANSCRIPT_MEMBER.fullmatch(member.filename)
                    ]
                    if not xml_members:
                        raise ValueError("nested archive contains no XML transcripts")
                    if len(xml_members) > MAX_TRANSCRIPT_XML_MEMBERS:
                        raise ValueError("transcript member count exceeds limit")
                    if sum(member.file_size for member in xml_members) > MAX_TRANSCRIPT_XML_TOTAL_BYTES:
                        raise ValueError("transcript XML total exceeds limit")
                    sequences = []
                    for member in sorted(xml_members, key=lambda item: item.filename):
                        _validate_entry_size(
                            member, MAX_TRANSCRIPT_XML_BYTES, MAX_TRANSCRIPT_XML_BYTES,
                            "transcript XML",
                        )
                        try:
                            with nested.open(member) as transcript:
                                root = element_tree.parse(transcript).getroot()
                        except element_tree.ParseError as error:
                            raise ValueError("malformed TEI XML transcript") from error
                        if root.tag != TEI_NAMESPACE + "TEI":
                            raise ValueError("transcript must use the TEI namespace")
                        for utterance in root.findall(
                            "./" + TEI_NAMESPACE + "text/" + TEI_NAMESPACE + "body/" + TEI_NAMESPACE + "u"
                        ):
                            tokens = []
                            for word in utterance.iter(TEI_NAMESPACE + "w"):
                                tokens.extend(tokenize(word.text or ""))
                            if tokens:
                                sequences.append(tokens)
                    return sequences
    except zipfile.BadZipFile as error:
        raise ValueError("malformed ArchiMob archive") from error


def _validate_archive_size(members, compressed_limit, uncompressed_limit, description):
    compressed_size = sum(member.compress_size for member in members)
    uncompressed_size = sum(member.file_size for member in members)
    if compressed_size > compressed_limit or uncompressed_size > uncompressed_limit:
        raise ValueError(description + " exceeds size limit")
    for member in members:
        _validate_compression_ratio(member, description)


def _validate_entry_size(member, compressed_limit, uncompressed_limit, description):
    if member.compress_size > compressed_limit or member.file_size > uncompressed_limit:
        raise ValueError(description + " exceeds size limit")
    _validate_compression_ratio(member, description)


def _validate_compression_ratio(member, description):
    if member.file_size and (
        not member.compress_size or member.file_size > member.compress_size * MAX_COMPRESSION_RATIO
    ):
        raise ValueError(description + " compression ratio exceeds limit")


def _copy_limited(source, destination, limit, description):
    copied = 0
    while chunk := source.read(COPY_CHUNK_BYTES):
        copied += len(chunk)
        if copied > limit:
            raise ValueError(description + " exceeds size limit")
        destination.write(chunk)


def _scored_counts(word_counts, selected):
    maximum = max(selected.values(), default=0)
    ngrams = [
        (context, target, import_google_books_ngrams.score(count, maximum))
        for (context, target), count in sorted(selected.items())
    ]
    return sorted(word_counts.items()), ngrams


def load_current_generation(words_output, ngrams_output):
    words, ngrams = import_google_books_ngrams.load_current_generation(words_output, ngrams_output)
    pointer = import_google_books_ngrams.current_manifest_path(words_output, ngrams_output)
    data = json.loads(pointer.read_text(encoding="utf-8"))
    report = data.get("report")
    if not isinstance(report, dict) or not isinstance(report.get("path"), str):
        raise ValueError("current generation does not contain a report")
    report_path = (pointer.parent / report["path"]).resolve()
    root = import_google_books_ngrams.generation_root_path(words_output, ngrams_output).resolve()
    if root not in report_path.parents or not report_path.is_file():
        raise ValueError("current generation report is outside the output directory")
    if _sha256(report_path) != report.get("sha256"):
        raise ValueError("current generation report hash does not match")
    return words, ngrams, report_path


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
    _validate_report_output_path(arguments.report_output, arguments.words_output, arguments.ngrams_output)
    with arguments.input.open("rb") as source:
        if os.fstat(source.fileno()).st_size > MAX_SOURCE_INPUT_BYTES:
            raise ValueError("source input exceeds size limit")
        actual_hash = _sha256_opened(source)
        if actual_hash != arguments.source_sha256:
            raise ValueError("source SHA-256 does not match --source-sha256")
        source.seek(0)
        if zipfile.is_zipfile(source):
            source.seek(0)
            sequences = archive_sequences(source)
            report = {"accepted_lines": len(sequences), "rejected_lines": 0}
            words, ngrams, report = aggregate_sequences(
                sequences, arguments.minimum_count, arguments.top_targets, report
            )
        else:
            source.seek(0)
            with io.TextIOWrapper(source, encoding="utf-8") as text_source:
                words, ngrams, report = aggregate(
                    text_source, arguments.minimum_count, arguments.top_targets
                )
    if not ngrams:
        raise ValueError("no retained n-grams; lower --minimum-count or use another input")
    report["source_sha256"] = actual_hash
    _publish_generation(
        words, ngrams, arguments.words_output, arguments.ngrams_output,
        arguments.report_output.name,
        (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
    )


def _validate_report_output_path(report_output, words_output, ngrams_output):
    report_output = report_output.resolve()
    if report_output.name in {words_output.name, ngrams_output.name}:
        raise ValueError("report name must differ from generation TSV names")
    pointer = import_google_books_ngrams.current_manifest_path(words_output, ngrams_output).resolve()
    generation_root = import_google_books_ngrams.generation_root_path(words_output, ngrams_output).resolve()
    if report_output == pointer or report_output == generation_root or generation_root in report_output.parents:
        raise ValueError("report output must not overlap active publication paths")


def _publish_generation(words, ngrams, words_output, ngrams_output, report_name, report_contents):
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
        import_google_books_ngrams._publish_generation(
            connection, words_output, ngrams_output, (report_name, report_contents)
        )
    finally:
        connection.close()


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_opened(source):
    digest = hashlib.sha256()
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
