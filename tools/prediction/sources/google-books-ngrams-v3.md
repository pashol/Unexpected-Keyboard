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
