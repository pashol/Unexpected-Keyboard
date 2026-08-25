"""Transform AOSP combined-format word lists into dictionary TSV generations."""


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
            word, frequency = _parse_entry(stripped, WORD_PREFIX)
            context = word
            if frequency <= 0:
                dropped += 1
                context_retained = False
                continue
            words[word] = max(words.get(word, 0), frequency)
            context_retained = True
        elif stripped.startswith(BIGRAM_PREFIX):
            if context is None:
                raise ValueError("bigram entry precedes its context word")
            target, frequency = _parse_entry(stripped, BIGRAM_PREFIX)
            if frequency <= 0 or not context_retained:
                dropped += 1
                continue
            bigrams[(context, target)] = max(bigrams.get((context, target), 0), frequency)
        else:
            skipped += 1
    if not header_seen:
        raise ValueError("combined input lacks a dictionary header")
    return words, bigrams, {"skipped_lines": skipped, "dropped_entries": dropped}


def _parse_entry(entry, prefix):
    body = entry[len(prefix):]
    name, separator, frequency_text = body.rpartition(",f=")
    if not separator or not name:
        raise ValueError("malformed combined entry: " + prefix)
    try:
        return name, int(frequency_text)
    except ValueError as error:
        raise ValueError("malformed combined frequency: " + body) from error


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
