# Language-pack builder

`build_language_pack.py` turns a locale-specific word-frequency TSV and n-gram TSV
into an AOSP LatinIME format-202 dictionary using a caller-provided, clean checkout
at the pinned AOSP revision. It does not download sources or data.

```sh
SOURCE_DATE_EPOCH=0 python3 tools/prediction/build_language_pack.py \
  --source /path/to/LatinIME \
  --locale gsw \
  --word-frequency-tsv test/fixtures/latinime/sources/gsw.words.tsv \
  --ngram-tsv test/fixtures/latinime/sources/gsw.ngrams.tsv \
  --provenance test/fixtures/latinime/sources/gsw.provenance.json \
  --output test/fixtures/latinime/minimal_gsw.dict \
  --manifest test/fixtures/latinime/minimal_gsw.json
```

The manifest records source licenses/provenance and hashes, output hash, format,
locale, pinned AOSP compiler revision and source hash, plus the timestamp derived
from `SOURCE_DATE_EPOCH`. Input rows and rendered combined source are Unicode NFC
normalized and sorted deterministically. The `gsw` normalizer deliberately preserves
dialect variants such as `nöd`, `nid`, and `ned`, umlauts, apostrophes, and casing.

The checked-in English and Swiss German packs are deliberately tiny test fixtures,
not production-quality corpora or language packs.
