#!/usr/bin/env python3
"""
whatsapp_to_dict.py - Create a Swiss German dictionary from WhatsApp exports

Privacy-preserving: only high-frequency words are included so rare, personal,
or context-specific words (which could reveal private information) are excluded.

Usage:
    python3 whatsapp_to_dict.py chat.txt [chat2.txt ...] -o wordlist.txt --stats

Output is a word list usable with cdict_tool:
    cdict_tool build -o de_CH_personal.dict "main:wordlist.txt"
    gzip -9 -c de_CH_personal.dict > de_CH_personal.dict.gz

Or use --user-dict to write directly to UserDictionary format (user_words.txt).
"""

import argparse
import re
import sys
from collections import Counter, defaultdict


# ---------------------------------------------------------------------------
# WhatsApp export parsing
# ---------------------------------------------------------------------------

# iOS / newer Android: [DD.MM.YYYY, HH:MM:SS] Name: message
_RE_IOS = re.compile(
    r"^\[(\d{1,2}[./]\d{1,2}[./]\d{2,4}),\s*\d{1,2}:\d{2}(?::\d{2})?\]\s*([^:]+):\s(.+)$"
)

# Older Android: DD.MM.YY, HH:MM - Name: message
_RE_ANDROID = re.compile(
    r"^(\d{1,2}[./]\d{1,2}[./]\d{2,4}),\s*\d{1,2}:\d{2}(?:\s*[APap][Mm])?\s*-\s*([^:]+):\s(.+)$"
)

# System/status lines that carry no useful text (match message portion)
_RE_SYSTEM = re.compile(
    r"^(<Media omitted>|image omitted|video omitted|audio omitted|"
    r"document omitted|sticker omitted|GIF omitted|"
    r"This message was deleted|You deleted this message|"
    r"Missed (voice|video) call|null)$",
    re.IGNORECASE,
)


def parse_whatsapp_file(path: str, senders: set = None) -> list:
    """Parse a WhatsApp .txt export and return a list of message strings.

    If senders is a non-empty set, only messages from those senders are kept.
    Sender names are matched case-insensitively after stripping whitespace.
    """
    messages = []
    current_message = None

    try:
        with open(path, encoding="utf-8-sig") as f:
            lines = f.readlines()
    except UnicodeDecodeError:
        with open(path, encoding="latin-1") as f:
            lines = f.readlines()

    # Normalise sender filter once
    sender_filter = {s.strip().lower() for s in senders} if senders else None

    for raw_line in lines:
        line = raw_line.rstrip("\n\r")

        m = _RE_IOS.match(line) or _RE_ANDROID.match(line)
        if m:
            # Save previous message
            if current_message is not None:
                messages.append(current_message)
            sender = m.group(2).strip()
            text = m.group(3)
            if sender_filter and sender.lower() not in sender_filter:
                current_message = None
            elif _RE_SYSTEM.match(text.strip()):
                current_message = None
            else:
                current_message = text
        elif current_message is not None:
            # Continuation of a multi-line message
            current_message += " " + line

    if current_message is not None:
        messages.append(current_message)

    return messages


# ---------------------------------------------------------------------------
# Text cleaning
# ---------------------------------------------------------------------------

_RE_URL = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
_RE_EMAIL = re.compile(r"\S+@\S+\.\S+")
_RE_PHONE = re.compile(r"\+?\d[\d\s\-()]{6,}\d")
_RE_HTML_ENTITY = re.compile(r"&[a-z]+;|&#\d+;", re.IGNORECASE)

# Emoji: cover the main Unicode emoji blocks
_RE_EMOJI = re.compile(
    "["
    "\U0001F300-\U0001FAFF"  # Misc symbols, emoticons, transport, etc.
    "\U00002600-\U000027BF"  # Misc symbols
    "\U0000FE00-\U0000FE0F"  # Variation selectors
    "\U00002702-\U000027B0"
    "\U0000200D"             # Zero-width joiner
    "\U000024C2-\U0001F251"
    "]+",
    flags=re.UNICODE,
)


def clean_message(text: str) -> str:
    """Remove URLs, emails, phone numbers, emoji, and HTML entities."""
    text = _RE_URL.sub(" ", text)
    text = _RE_EMAIL.sub(" ", text)
    text = _RE_PHONE.sub(" ", text)
    text = _RE_HTML_ENTITY.sub(" ", text)
    text = _RE_EMOJI.sub(" ", text)
    return text


# ---------------------------------------------------------------------------
# Tokenization
# ---------------------------------------------------------------------------

# Match sequences of letters including German umlauts and extended Latin
_RE_WORD = re.compile(r"[a-zA-Z\u00C0-\u024F]+")


def tokenize(text: str, min_length: int = 3, max_length: int = 30) -> list:
    """Extract word tokens from cleaned message text.

    Returns words with ß replaced by ss (Swiss German convention).
    Capitalization is preserved here — case folding happens later.
    """
    # Replace ß before tokenizing (Swiss German uses ss)
    text = text.replace("ß", "ss").replace("ẞ", "SS")
    tokens = []
    for word in _RE_WORD.findall(text):
        if min_length <= len(word) <= max_length:
            tokens.append(word)
    return tokens


# ---------------------------------------------------------------------------
# Word counting with canonical capitalization
# ---------------------------------------------------------------------------

def count_words(messages: list, min_length: int = 3, max_length: int = 30) -> tuple:
    """Count words case-insensitively, tracking most common casing and context count.

    Returns:
        counts    -- Counter keyed by word.lower()
        canonical -- dict mapping word.lower() → most common casing form
        contexts  -- dict mapping word.lower() → number of distinct messages it appeared in
    """
    counts = Counter()
    case_variants: dict = defaultdict(Counter)
    contexts: dict = defaultdict(int)

    for msg in messages:
        cleaned = clean_message(msg)
        tokens = tokenize(cleaned, min_length, max_length)
        # Track which unique word keys appear in this message (for context counting)
        seen_in_message = set()
        for word in tokens:
            key = word.lower()
            counts[key] += 1
            case_variants[key][word] += 1
            seen_in_message.add(key)
        for key in seen_in_message:
            contexts[key] += 1

    canonical = {
        key: variants.most_common(1)[0][0]
        for key, variants in case_variants.items()
    }
    return counts, canonical, contexts


# ---------------------------------------------------------------------------
# Privacy filtering
# ---------------------------------------------------------------------------

def apply_privacy_filters(
    counts: Counter,
    contexts: dict,
    min_count: int = 5,
    min_contexts: int = 2,
    top_percent: float = 70.0,
) -> list:
    """Return filtered (word_key, count) pairs, most frequent first.

    Three-stage filter:
      1. Hard minimum count: drop words appearing fewer than min_count times.
      2. Min contexts: drop words that appear in fewer than min_contexts
         distinct messages (catches words repeated many times in one message).
      3. Percentile cutoff: from the remaining words, keep only the top
         top_percent% (by frequency), dropping the least-frequent tail.
    """
    # Stage 1: hard minimum count
    passing = [(w, c) for w, c in counts.most_common() if c >= min_count]

    # Stage 2: minimum distinct messages (contexts)
    if min_contexts > 1:
        passing = [(w, c) for w, c in passing if contexts.get(w, 0) >= min_contexts]

    # Stage 3: percentile cutoff
    if passing and top_percent < 100.0:
        cutoff = max(1, int(len(passing) * top_percent / 100.0))
        passing = passing[:cutoff]

    return passing


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def write_output(
    words: list,
    canonical: dict,
    output_path: str,
    user_dict: bool = False,
) -> None:
    """Write the word list to a file.

    Default (cdict_tool) format: one word per line, most frequent first.
    UserDictionary format: same ordering (newest = most relevant first).
    """
    lines = [canonical.get(key, key) for key, _count in words]

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
        if lines:
            f.write("\n")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Build a Swiss German dictionary word list from WhatsApp exports.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Privacy notes:
  Words appearing fewer than --min-count times are always excluded.
  Words appearing in fewer than --min-contexts distinct messages are excluded.
  The bottom (100 - --top-percent)%% of remaining words are also excluded.
  Use --sender to extract only your own messages from group chats.

Next steps after running this script:
  # Build binary .dict (requires cdict_tool from https://github.com/Julow/cdict):
  cdict_tool build -o de_CH_personal.dict "main:wordlist.txt"
  gzip -9 -c de_CH_personal.dict > de_CH_personal.dict.gz

  # Or for direct use as UserDictionary, copy to app private storage:
  adb push user_words.txt /data/data/juloo.keyboard2.pashol/files/user_words.txt
""",
    )
    parser.add_argument(
        "chat_files",
        metavar="chat_file",
        nargs="+",
        help="WhatsApp .txt export file(s)",
    )
    parser.add_argument(
        "--sender",
        action="append",
        metavar="NAME",
        dest="senders",
        help="Only include messages from this sender (repeatable; useful for group chats "
             "to extract only your own vocabulary)",
    )
    parser.add_argument(
        "--min-count",
        type=int,
        default=5,
        metavar="N",
        help="Minimum occurrences to include a word (default: 5)",
    )
    parser.add_argument(
        "--min-contexts",
        type=int,
        default=2,
        metavar="N",
        help="Minimum distinct messages a word must appear in (default: 2); "
             "filters words repeated many times in a single message",
    )
    parser.add_argument(
        "--top-percent",
        type=float,
        default=70.0,
        metavar="P",
        help="Keep only top P%% of words by frequency (default: 70)",
    )
    parser.add_argument(
        "--min-length",
        type=int,
        default=3,
        metavar="L",
        help="Minimum word length in characters (default: 3)",
    )
    parser.add_argument(
        "--max-length",
        type=int,
        default=30,
        metavar="L",
        help="Maximum word length in characters (default: 30)",
    )
    parser.add_argument(
        "--user-dict",
        action="store_true",
        help="Output in UserDictionary format (for direct use as user_words.txt)",
    )
    parser.add_argument(
        "-o",
        "--output",
        default="wordlist.txt",
        metavar="OUTPUT",
        help="Output file path (default: wordlist.txt)",
    )
    parser.add_argument(
        "--stats",
        action="store_true",
        help="Print statistics to stderr",
    )

    args = parser.parse_args()

    if not (0.0 < args.top_percent <= 100.0):
        parser.error("--top-percent must be between 0 (exclusive) and 100 (inclusive)")
    if args.min_count < 1:
        parser.error("--min-count must be at least 1")
    if args.min_contexts < 1:
        parser.error("--min-contexts must be at least 1")

    senders = set(args.senders) if args.senders else None
    if args.stats and senders:
        print(f"Filtering to senders: {', '.join(sorted(senders))}", file=sys.stderr)

    # Parse all chat files
    all_messages = []
    for path in args.chat_files:
        try:
            msgs = parse_whatsapp_file(path, senders=senders)
            all_messages.extend(msgs)
            if args.stats:
                print(f"  {path}: {len(msgs)} messages", file=sys.stderr)
        except FileNotFoundError:
            print(f"Error: file not found: {path}", file=sys.stderr)
            sys.exit(1)

    if args.stats:
        print(f"Total messages: {len(all_messages)}", file=sys.stderr)

    # Count words
    counts, canonical, contexts = count_words(
        all_messages,
        min_length=args.min_length,
        max_length=args.max_length,
    )

    if args.stats:
        print(f"Unique words (raw): {len(counts)}", file=sys.stderr)

    # Apply privacy filters
    filtered = apply_privacy_filters(
        counts,
        contexts,
        min_count=args.min_count,
        min_contexts=args.min_contexts,
        top_percent=args.top_percent,
    )

    if args.stats:
        excluded = len(counts) - len(filtered)
        print(
            f"Words kept: {len(filtered)}  "
            f"(excluded {excluded} = "
            f"{excluded * 100 // max(len(counts), 1)}% of unique words)",
            file=sys.stderr,
        )
        if filtered:
            top5 = ", ".join(
                f"{canonical.get(w, w)}({c})" for w, c in filtered[:5]
            )
            print(f"Top words: {top5}", file=sys.stderr)

    # Write output
    write_output(filtered, canonical, args.output, user_dict=args.user_dict)

    if args.stats:
        mode = "UserDictionary" if args.user_dict else "cdict_tool wordlist"
        print(f"Written {len(filtered)} words to {args.output} ({mode} format)", file=sys.stderr)


if __name__ == "__main__":
    main()
