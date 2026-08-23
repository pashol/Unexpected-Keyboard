# Prediction Language Pack Fixtures

`build_language_pack.py` converts deterministic word-frequency and bigram TSV
sources to an AOSP LatinIME format-202 dictionary. It requires
`SOURCE_DATE_EPOCH` and source metadata containing non-empty `license` and
`provenance` fields. Pass `--compiler PATH` to select a different copy; it
must have the same pinned SHA-256. `DICTTOOL_AOSP_JAR` overrides the compiler
only for fixture regeneration.

The default compiler is the vendored `tools/prediction/dicttool_aosp.jar`,
the prebuilt artifact from
`https://github.com/remi0s/aosp-dictionary-tools` at commit
`1e69dd2c04258e5e9e04bfb13b46faccf6a435b0`. Its SHA-256 must be
`a8c5bd21f631ed0a92235d42d2fe83af5d70216172bf7e22781a9a946858237e`; the
builder validates this before use. See `dicttool_aosp.NOTICE` for its Apache
2.0 license and provenance. It is an AOSP dictionary-tool distribution whose
`makedict` command emits the format-202 dictionaries consumed by the pinned
AOSP LatinIME decoder.

The builder writes a sorted AOSP combined source with this fixed header:

```
dictionary=main:<locale>,locale=<locale>,version=1,date=<SOURCE_DATE_EPOCH>
```

It invokes:

```
java -jar COMPILER makedict -s INPUT.combined -d OUTPUT.dict
```

Build a fixture with `SOURCE_DATE_EPOCH=0` and then combine each generated
per-pack manifest into `test/fixtures/latinime/manifest.json`. To verify both
checked-in dictionaries and combined sources, run:

```
SOURCE_DATE_EPOCH=0 python3 tools/prediction/build_language_pack.py \
  --verify-fixtures test/fixtures/latinime
```
