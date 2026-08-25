# Google Books Ngrams v3

The English and German production pack inputs use local Google Books Ngram v3
2-gram shards. The dataset index is
https://storage.googleapis.com/books/ngrams/books/datasetsv3.html. Select the
English or German 2-gram file-list URL from that index and record that exact
file-list URL with the selected shard name before acquisition.

Google Books Ngrams is licensed under CC BY 3.0. Attribute the source as
"Google Books Ngram Viewer, Google" and include the license URL:
https://creativecommons.org/licenses/by/3.0/.

## Acquisition lock

Do not download from the importer and do not commit corpus shards or generated
intermediate TSV data. Acquire a reviewed shard outside this repository, then
record its selected file-list URL, shard filename, acquisition date, and
`sha256sum` in the locale's source metadata. Review the hash and source details,
then change its acquisition lock from `unlocked` to `locked` before generating
or committing any dictionary asset. A changed source hash requires a new review
and lock record.

Each UTF-8 input row must be exactly:

```text
token token<TAB>year<TAB>match_count<TAB>volume_count
```

The importer aggregates `match_count` across years, not `volume_count`.

## Command template

```sh
python3 tools/prediction/import_google_books_ngrams.py \
  --input /reviewed/googlebooks-eng-all-2gram-20120701-0.gz.decoded.tsv \
  --locale en \
  --words-output /reviewed/en.words.tsv \
  --ngrams-output /reviewed/en.ngrams.tsv \
  --minimum-count 3 \
  --top-targets 8
```

Decompress shards outside the importer if necessary; the importer reads only
the explicit local UTF-8 `--input` file and never performs network access.
