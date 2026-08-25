# Google Books Ngrams v3

The English and German production pack inputs use local Google Books Ngram v3
2-gram shards. The dataset index is
https://storage.googleapis.com/books/ngrams/books/datasetsv3.html. The intended
v3 (20200217) file-list URLs are:

- English: https://storage.googleapis.com/books/ngrams/books/20200217/eng/eng-2-ngrams_exports.html
- German: https://storage.googleapis.com/books/ngrams/books/20200217/ger/ger-2-ngrams_exports.html

The English list has shards `2-00000-of-00589.gz` through
`2-00588-of-00589.gz`; the German list has shards `2-00000-of-00181.gz` through
`2-00180-of-00181.gz`. Select the needed shards from the appropriate list.

Google Books Ngrams is licensed under CC BY 3.0. Attribute the source as
"Google Books Ngram Viewer, Google" and include the license URL:
https://creativecommons.org/licenses/by/3.0/.

## Acquisition lock

Do not download from the importer and do not commit corpus shards or generated
intermediate TSV data. Acquire reviewed shards outside this repository. Before
building, the locale's source acquisition lock must record the selected file-list
URL, every specific compressed shard filename, each shard's acquired SHA-256, and
the acquisition date. Do not invent a checksum. Review those hashes and source
details, then change its acquisition lock from `unlocked` to `locked` before
generating or committing any dictionary asset. A changed source hash requires a
new review and lock record.

Each UTF-8 input row must be exactly:

```text
token token<TAB>year<TAB>match_count<TAB>volume_count
```

The importer aggregates `match_count` across years, not `volume_count`.
It uses a temporary SQLite database on local disk while aggregating and ranking
shards, so provide temporary-disk capacity for the input's distinct bigrams and
derived word counts. The temporary database is removed after the import.

Tokens may contain normal lexical punctuation, including apostrophes and
hyphens, such as `don't`, `night's`, `d'Frau`, and `well-known`. Whitespace and
Unicode control characters are rejected. Commas and equals signs are also
rejected because they are unescaped delimiters in the generated LatinIME combined
source format.

## Publication contract

`--words-output` and `--ngrams-output` must be in the same directory. Their
filenames define the two members of an immutable generation under a hidden
sibling `.WORDS.NGRAMS.generations/` directory; they are not mutable TSV files
at the supplied paths. After both generation files are written and hashed, the
importer atomically replaces
`.WORDS.NGRAMS.current.json` in that directory with a manifest containing both
relative paths and SHA-256 values. A failed import can leave an unselected
generation, but cannot select a mixed pair. Generation files, the manifest, and
their directories are synced before publication so a crash cannot make the
pointer durable ahead of either selected TSV. The importer syncs both TSVs,
then their generation directory, then the generation-root directory before it
atomically replaces the current manifest and syncs the manifest's directory.

Consumers must resolve the current manifest, open both files named by it, and
verify their SHA-256 values before accepting the pair. Python consumers can use
`load_current_generation(words_output, ngrams_output)` to perform that
validation and obtain the selected paths. That resolver also rejects a manifest
unless both paths are under the deterministic generation root and retain the
supplied word and n-gram output filenames.

## Command template

```sh
python3 tools/prediction/import_google_books_ngrams.py \
  --input /reviewed/google-books-v3-en-2gram-selected-shards.tsv \
  --locale en \
  --words-output /reviewed/en.words.tsv \
  --ngrams-output /reviewed/en.ngrams.tsv \
  --minimum-count 3 \
  --top-targets 8
```

Decompress shards outside the importer if necessary; the importer reads only
the explicit local UTF-8 `--input` file and never performs network access.
