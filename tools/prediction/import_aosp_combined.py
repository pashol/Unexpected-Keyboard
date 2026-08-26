"""Transform AOSP combined-format word lists into dictionary TSV generations."""

import argparse
import gzip
import hashlib
import json
import pathlib
import sqlite3

from tools.prediction import import_archimob
from tools.prediction import import_google_books_ngrams


MAX_INPUT_BYTES = 64 * 1024 * 1024
MAX_OVERLAY_BYTES = 8 * 1024 * 1024

HEADER_PREFIX = "dictionary="
WORD_PREFIX = "word="
BIGRAM_PREFIX = "bigram="


def parse_combined(lines):
    """Parse header, ``word=`` and ``bigram=`` entries; return words, bigrams, stats."""
    header_seen = False
    words = {}
    bigrams = {}
    skipped = 0
    dropped = 0
    dropped_not_a_word = 0
    context = None
    context_retained = False
    for raw_line in lines:
        line = raw_line.rstrip("\r\n")
        if not header_seen:
            if line.startswith(HEADER_PREFIX):
                header_seen = True
            elif line.strip():
                raise ValueError("combined input lacks a dictionary header")
            continue
        stripped = line.strip()
        if not stripped:
            skipped += 1
            continue
        if stripped.startswith(WORD_PREFIX):
            word, frequency, attributes = _parse_entry(stripped, WORD_PREFIX)
            context = word
            if frequency <= 0 or attributes.get("not_a_word") == "true":
                if frequency > 0:
                    dropped_not_a_word += 1
                else:
                    dropped += 1
                context_retained = False
                continue
            words[word] = max(words.get(word, 0), frequency)
            context_retained = True
        elif stripped.startswith(BIGRAM_PREFIX):
            if context is None:
                raise ValueError("bigram entry precedes its context word")
            target, frequency, _ = _parse_entry(stripped, BIGRAM_PREFIX)
            if frequency <= 0 or not context_retained:
                dropped += 1
                continue
            bigrams[(context, target)] = max(bigrams.get((context, target), 0), frequency)
        else:
            skipped += 1
    if not header_seen:
        raise ValueError("combined input lacks a dictionary header")
    return words, bigrams, {
        "skipped_lines": skipped,
        "dropped_entries": dropped,
        "dropped_not_a_word": dropped_not_a_word,
    }


def _parse_entry(entry, prefix):
    body = entry[len(prefix):]
    name, separator, remainder = body.partition(",f=")
    if not separator or not name:
        raise ValueError("malformed combined entry: " + prefix)
    tokens = remainder.split(",")
    try:
        frequency = int(tokens[0])
    except ValueError as error:
        raise ValueError("malformed combined frequency: " + body) from error
    attributes = {}
    for token in tokens[1:]:
        key, assigner, value = token.partition("=")
        if not assigner or not key:
            raise ValueError("malformed combined attribute: " + token)
        attributes[key] = value
    return name, frequency, attributes


def apply_word_maps(words, bigrams, maps):
    """Apply literal old:new replacements; collisions keep the maximum frequency. Maps apply sequentially per word, so their order matters."""
    def map_word(word):
        for old, new in maps:
            word = word.replace(old, new)
        return word

    mapped_words = {}
    replacements = 0
    for word, frequency in words.items():
        mapped = map_word(word)
        if mapped != word:
            replacements += 1
        mapped_words[mapped] = max(mapped_words.get(mapped, 0), frequency)
    mapped_bigrams = {}
    for (context, target), frequency in bigrams.items():
        edge = (map_word(context), map_word(target))
        mapped_bigrams[edge] = max(mapped_bigrams.get(edge, 0), frequency)
    return mapped_words, mapped_bigrams, replacements


def merge_overlay(words, bigrams, overlay_words, overlay_bigrams):
    """Union-max merge of overlay vocabulary and bigrams; returns merged data and counts."""
    merged = dict(words)
    added_words = 0
    merged_words = 0
    for word, frequency in overlay_words.items():
        if word in merged:
            if frequency > merged[word]:
                merged[word] = frequency
            merged_words += 1
        else:
            merged[word] = frequency
            added_words += 1
    merged_bigrams = dict(bigrams)
    added_bigrams = 0
    merged_bigram_edges = 0
    for edge, frequency in overlay_bigrams.items():
        if edge in merged_bigrams:
            if frequency > merged_bigrams[edge]:
                merged_bigrams[edge] = frequency
            merged_bigram_edges += 1
        else:
            merged_bigrams[edge] = frequency
            added_bigrams += 1
    report = {
        "overlay_added_words": added_words,
        "overlay_merged_words": merged_words,
        "overlay_added_bigrams": added_bigrams,
        "overlay_merged_bigrams": merged_bigram_edges,
    }
    return merged, merged_bigrams, report


def select_ngrams(words, bigrams, minimum_count, top_targets):
    """Keep bigrams whose endpoints are known, above the threshold, best-per-context."""
    if minimum_count <= 0:
        raise ValueError("minimum_count must be positive")
    if top_targets <= 0:
        raise ValueError("top_targets must be positive")
    candidates = {}
    below = 0
    for (context, target), frequency in bigrams.items():
        if context not in words or target not in words:
            continue
        if frequency < minimum_count:
            below += 1
            continue
        candidates.setdefault(context, []).append((target, frequency))
    selected = {}
    capped = 0
    for context in sorted(candidates):
        ranked = sorted(candidates[context], key=lambda item: (-item[1], item[0]))
        for target, frequency in ranked[:top_targets]:
            selected[(context, target)] = frequency
        capped += max(0, len(ranked) - top_targets)
    return selected, capped, below


def _sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_hex(value, description):
    if len(value) != 64:
        raise ValueError(description + " must be a SHA-256 hash")
    try:
        int(value, 16)
    except ValueError as error:
        raise ValueError(description + " must be a SHA-256 hash") from error


def _read_source(path, limit, description):
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError(description + " exceeds size limit")
    if data[:2] == b"\x1f\x8b":
        with gzip.open(path, "rt", encoding="utf-8") as text_source:
            return text_source.readlines(), data
    with path.open("r", encoding="utf-8") as text_source:
        return text_source.read().splitlines(keepends=True), data


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--input-sha256", required=True)
    parser.add_argument("--overlay", type=pathlib.Path)
    parser.add_argument("--overlay-sha256")
    parser.add_argument("--map", dest="maps", action="append", default=[],
                        metavar="OLD:NEW",
                        help="literal character replacement, repeatable")
    parser.add_argument("--words-output", required=True, type=pathlib.Path)
    parser.add_argument("--ngrams-output", required=True, type=pathlib.Path)
    parser.add_argument("--report-output", required=True, type=pathlib.Path)
    parser.add_argument("--minimum-count", required=True,
                        type=import_archimob._positive_integer)
    parser.add_argument("--top-targets", required=True,
                        type=import_archimob._positive_integer)
    arguments = parser.parse_args(argv)

    maps = []
    for specification in arguments.maps:
        old, separator, new = specification.partition(":")
        if not separator or not old:
            parser.error("--map must take the form OLD:NEW")
        maps.append((old, new))

    _validate_hex(arguments.input_sha256, "--input-sha256")
    if arguments.overlay is not None:
        if arguments.overlay_sha256 is None:
            parser.error("--overlay requires --overlay-sha256")
        _validate_hex(arguments.overlay_sha256, "--overlay-sha256")
    elif arguments.overlay_sha256 is not None:
        parser.error("--overlay-sha256 requires --overlay")

    import_google_books_ngrams.validate_paths(
        arguments.input, arguments.words_output, arguments.ngrams_output
    )
    import_archimob._validate_report_output_path(
        arguments.report_output, arguments.words_output, arguments.ngrams_output
    )
    sources = {arguments.input.resolve()}
    if arguments.overlay is not None:
        sources.add(arguments.overlay.resolve())
    outputs = {
        arguments.words_output.resolve(),
        arguments.ngrams_output.resolve(),
        arguments.report_output.resolve(),
    }
    if sources & outputs:
        raise ValueError("input, output, and report paths must differ")

    actual_input_hash = _sha256_file(arguments.input)
    if actual_input_hash != arguments.input_sha256:
        raise ValueError("source SHA-256 does not match --input-sha256")
    lines, _ = _read_source(arguments.input, MAX_INPUT_BYTES, "combined input")
    words, bigrams, stats = parse_combined(lines)
    report = {
        "input_sha256": actual_input_hash,
        "accepted_words": len(words),
        "accepted_bigrams_before_selection": len(bigrams),
        "minimum_count": arguments.minimum_count,
        "top_targets": arguments.top_targets,
        "maps": [old + ":" + new for old, new in maps],
        **stats,
    }

    if arguments.overlay is not None:
        actual_overlay_hash = _sha256_file(arguments.overlay)
        if actual_overlay_hash != arguments.overlay_sha256:
            raise ValueError("source SHA-256 does not match --overlay-sha256")
        overlay_lines, _ = _read_source(
            arguments.overlay, MAX_OVERLAY_BYTES, "combined overlay")
        overlay_words, overlay_bigrams, overlay_stats = parse_combined(overlay_lines)
        report["overlay_sha256"] = actual_overlay_hash
        report.update(
            {"overlay_" + name: value for name, value in overlay_stats.items()}
        )
    else:
        overlay_words, overlay_bigrams = {}, {}

    if maps:
        words, bigrams, replacements = apply_word_maps(words, bigrams, maps)
        report["mapped_words"] = replacements
        overlay_words, overlay_bigrams, _ = apply_word_maps(
            overlay_words, overlay_bigrams, maps)

    words, bigrams, overlay_report = merge_overlay(
        words, bigrams, overlay_words, overlay_bigrams)
    report.update(overlay_report)
    report["accepted_words_final"] = len(words)

    bigrams, capped, below = select_ngrams(
        words, bigrams, arguments.minimum_count, arguments.top_targets)
    report["capped_target_bigrams"] = capped
    report["below_minimum_count_bigrams"] = below
    report["accepted_bigrams"] = len(bigrams)

    if not bigrams:
        raise ValueError("no retained n-grams")

    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(
            "CREATE TABLE selected "
            "(context TEXT NOT NULL, target TEXT NOT NULL, count INTEGER NOT NULL);"
            "CREATE TABLE word_counts (word TEXT PRIMARY KEY, count INTEGER NOT NULL);"
        )
        connection.executemany(
            "INSERT INTO word_counts(word, count) VALUES (?, ?)", sorted(words.items())
        )
        connection.executemany(
            "INSERT INTO selected(context, target, count) VALUES (?, ?, ?)",
            [(context, target, count) for (context, target), count in sorted(bigrams.items())],
        )
        report_contents = (
            json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode("utf-8")
        import_google_books_ngrams._publish_generation(
            connection, arguments.words_output, arguments.ngrams_output,
            (arguments.report_output.name, report_contents), arguments.report_output,
        )
    finally:
        connection.close()


if __name__ == "__main__":
    main()
