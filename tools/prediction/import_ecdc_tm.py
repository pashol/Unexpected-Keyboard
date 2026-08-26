"""Transform an explicit ECDC Translation Memory ZIP into EN and DE TSV inputs."""

import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import sqlite3
import unicodedata
import xml.etree.ElementTree as element_tree
import zipfile

from tools.prediction import import_archimob
from tools.prediction import import_google_books_ngrams


MAX_SOURCE_INPUT_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 8
MAX_ARCHIVE_COMPRESSED_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 96 * 1024 * 1024
MAX_TMX_MEMBER_BYTES = 96 * 1024 * 1024
MAX_COMPRESSION_RATIO = 100
COPY_CHUNK_BYTES = 1024 * 1024
XML_LANGUAGE = "{http://www.w3.org/XML/1998/namespace}lang"
EXTERNAL_TMX_DTD = re.compile(rb'<!DOCTYPE\s+tmx\s+SYSTEM\s+["\']tmx14\.dtd["\']\s*>', re.IGNORECASE)


def _language(value):
    return value.lower().split("-", 1)[0] if isinstance(value, str) else None


def tokenize_sentences(text):
    return [
        import_archimob.tokenize(sentence)
        for sentence in import_archimob.SENTENCE_BOUNDARY.split(unicodedata.normalize("NFC", text))
        if import_archimob.tokenize(sentence)
    ]


class _SafeXmlReader:
    def __init__(self, source, limit):
        self._source = source
        self._limit = limit
        self._read = 0
        self._tail = b""

    def read(self, size=-1):
        data = self._source.read(size)
        self._read += len(data)
        if self._read > self._limit:
            raise ValueError("TMX XML exceeds size limit")
        inspected = self._tail + data
        without_allowed_dtd = EXTERNAL_TMX_DTD.sub(b"", inspected)
        if b"<!DOCTYPE" in without_allowed_dtd.upper() or b"<!ENTITY" in without_allowed_dtd.upper():
            raise ValueError("TMX XML must not declare DTD entities")
        self._tail = inspected[-64:]
        return data


def archive_sequences(source):
    try:
        with zipfile.ZipFile(source) as archive:
            members = archive.infolist()
            if len(members) > MAX_ARCHIVE_MEMBERS:
                raise ValueError("ECDC archive entry count exceeds limit")
            if any(
                    pathlib.PurePosixPath(member.filename).is_absolute()
                    or ".." in pathlib.PurePosixPath(member.filename).parts
                    for member in members):
                raise ValueError("ECDC archive contains an unsafe member path")
            if sum(member.compress_size for member in members) > MAX_ARCHIVE_COMPRESSED_BYTES or sum(
                    member.file_size for member in members) > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
                raise ValueError("ECDC archive exceeds size limit")
            tmx_members = [member for member in members if not member.is_dir() and member.filename.lower().endswith(".tmx")]
            if len(tmx_members) != 1:
                raise ValueError("ECDC archive must contain exactly one TMX file")
            member = tmx_members[0]
            if member.file_size > MAX_TMX_MEMBER_BYTES or (member.file_size and (
                    not member.compress_size or member.file_size > member.compress_size * MAX_COMPRESSION_RATIO)):
                raise ValueError("TMX XML exceeds size limit")
            sequences = {"en": [], "de": []}
            report = {"accepted_translation_units": 0, "rejected_translation_units": 0}
            with archive.open(member) as tmx:
                for _, element in element_tree.iterparse(_SafeXmlReader(tmx, MAX_TMX_MEMBER_BYTES), events=("end",)):
                    if element.tag.rsplit("}", 1)[-1] != "tu":
                        continue
                    translations = {}
                    for variant in element:
                        if variant.tag.rsplit("}", 1)[-1] != "tuv":
                            continue
                        language = _language(variant.get(XML_LANGUAGE) or variant.get("lang"))
                        if language not in {"en", "de"} or language in translations:
                            continue
                        segment = next((child for child in variant if child.tag.rsplit("}", 1)[-1] == "seg"), None)
                        if segment is not None:
                            translations[language] = "".join(segment.itertext())
                    if set(translations) == {"en", "de"}:
                        for language in ("en", "de"):
                            sequences[language].extend(tokenize_sentences(translations[language]))
                        report["accepted_translation_units"] += 1
                    else:
                        report["rejected_translation_units"] += 1
                    element.clear()
            return sequences, report
    except (element_tree.ParseError, zipfile.BadZipFile) as error:
        raise ValueError("malformed ECDC TMX archive") from error


def load_current_generation(words_output, ngrams_output):
    return import_archimob.load_current_generation(words_output, ngrams_output)


def _sha256_opened(source):
    digest = hashlib.sha256()
    for chunk in iter(lambda: source.read(COPY_CHUNK_BYTES), b""):
        digest.update(chunk)
    return digest.hexdigest()


def _publish(words, ngrams, words_output, ngrams_output, report_output, report):
    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(
            "CREATE TABLE selected (context TEXT NOT NULL, target TEXT NOT NULL, count INTEGER NOT NULL);"
            "CREATE TABLE word_counts (word TEXT PRIMARY KEY, count INTEGER NOT NULL);"
        )
        connection.executemany("INSERT INTO word_counts(word, count) VALUES (?, ?)", words)
        connection.executemany(
            "INSERT INTO selected(context, target, count) VALUES (?, ?, ?)", ngrams
        )
        import_google_books_ngrams._publish_generation(
            connection, words_output, ngrams_output,
            (report_output.name, (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")),
            report_output,
        )
    finally:
        connection.close()


def _validate_paths(arguments):
    outputs = []
    for language in ("en", "de"):
        words = getattr(arguments, language + "_words_output")
        ngrams = getattr(arguments, language + "_ngrams_output")
        report = getattr(arguments, language + "_report_output")
        import_google_books_ngrams.validate_paths(arguments.input, words, ngrams)
        if report.resolve() in {arguments.input.resolve(), words.resolve(), ngrams.resolve()}:
            raise ValueError("input, output, and report paths must differ")
        import_archimob._validate_report_output_path(report, words, ngrams)
        outputs.extend((words.resolve(), ngrams.resolve(), report.resolve()))
    if len(set(outputs)) != len(outputs):
        raise ValueError("EN and DE output paths must differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--source-sha256", required=True)
    for language in ("en", "de"):
        parser.add_argument("--" + language + "-words-output", required=True, type=pathlib.Path)
        parser.add_argument("--" + language + "-ngrams-output", required=True, type=pathlib.Path)
        parser.add_argument("--" + language + "-report-output", required=True, type=pathlib.Path)
    parser.add_argument("--minimum-count", required=True, type=import_archimob._positive_integer)
    parser.add_argument("--top-targets", required=True, type=import_archimob._positive_integer)
    arguments = parser.parse_args()
    import_archimob._validate_source_sha256(arguments.source_sha256)
    _validate_paths(arguments)
    with arguments.input.open("rb") as source:
        if os.fstat(source.fileno()).st_size > MAX_SOURCE_INPUT_BYTES:
            raise ValueError("source input exceeds size limit")
        actual_hash = _sha256_opened(source)
        if actual_hash != arguments.source_sha256:
            raise ValueError("source SHA-256 does not match --source-sha256")
        source.seek(0)
        sequences, report = archive_sequences(source)
    for language in ("en", "de"):
        words, ngrams, language_report = import_archimob.aggregate_sequences(
            sequences[language], arguments.minimum_count, arguments.top_targets, dict(report)
        )
        if not ngrams:
            raise ValueError("no retained " + language + " n-grams; lower --minimum-count or use another input")
        language_report["source_sha256"] = actual_hash
        _publish(
            words, ngrams, getattr(arguments, language + "_words_output"),
            getattr(arguments, language + "_ngrams_output"), getattr(arguments, language + "_report_output"),
            language_report,
        )


if __name__ == "__main__":
    main()
