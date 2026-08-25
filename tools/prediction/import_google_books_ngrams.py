"""Transform a local Google Books v3 2-gram shard into dictionary TSV inputs."""

import argparse
from collections import defaultdict
import unicodedata


def score(count, maximum):
    return max(1, (count * 255 + maximum - 1) // maximum)


def aggregate(rows, minimum_count, top_targets):
    if minimum_count <= 0:
        raise ValueError("minimum_count must be positive")
    if top_targets <= 0:
        raise ValueError("top_targets must be positive")

    counts = defaultdict(int)
    for line_number, row in enumerate(rows, 1):
        fields = row.rstrip("\r\n").split("\t")
        if len(fields) != 4:
            raise ValueError(f"malformed row {line_number}: expected four tab-separated fields")
        bigram, year, match_count, volume_count = fields
        tokens = bigram.split(" ")
        if len(tokens) != 2 or not all(tokens):
            raise ValueError(f"malformed row {line_number}: expected two space-separated tokens")
        context, target = (unicodedata.normalize("NFC", token) for token in tokens)
        if not all(value.isdecimal() and int(value) >= 0 for value in (year, match_count, volume_count)):
            raise ValueError(f"malformed row {line_number}: numeric fields must be non-negative integers")
        if not all(_safe_token(token) for token in (context, target)):
            continue
        counts[(context, target)] += int(match_count)

    retained = defaultdict(list)
    for (context, target), count in counts.items():
        if count >= minimum_count:
            retained[context].append((target, count))

    edges = []
    for context, targets in retained.items():
        for target, count in sorted(targets, key=lambda item: (-item[1], item[0]))[:top_targets]:
            edges.append((context, target, count))
    if not edges:
        return [], []

    maximum = max(count for _, _, count in edges)
    word_counts = defaultdict(int)
    for context, target, count in edges:
        word_counts[context] += count
        word_counts[target] += count
    words = sorted(word_counts.items())
    ngrams = [(context, target, score(count, maximum)) for context, target, count in sorted(edges)]
    return words, ngrams


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

    with open(arguments.input, encoding="utf-8") as source:
        words, ngrams = aggregate(source, arguments.minimum_count, arguments.top_targets)
    if not ngrams:
        raise ValueError("no retained n-grams; lower --minimum-count or use another input")

    maximum = max(count for _, count in words)
    with open(arguments.words_output, "w", encoding="utf-8", newline="\n") as output:
        for word, count in words:
            output.write(f"{word}\t{score(count, maximum)}\n")
    with open(arguments.ngrams_output, "w", encoding="utf-8", newline="\n") as output:
        for context, target, frequency in ngrams:
            output.write(f"{context}\t{target}\t{frequency}\n")


def _positive_integer(value):
    integer = int(value)
    if integer <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return integer


def _safe_token(token):
    return all(character.isalpha() or unicodedata.category(character).startswith("M") for character in token)


if __name__ == "__main__":
    main()
