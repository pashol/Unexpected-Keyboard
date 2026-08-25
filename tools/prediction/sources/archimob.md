# ArchiMob transcript source

The Swiss German (`gsw`) importer accepts only an explicitly acquired UTF-8
**text transcript export** from the ArchiMob study. It never downloads a
corpus. The catalogue record is [ArchiMob on SwissuBase](https://www.swissubase.ch/en/catalogue/studies/20154/19410/overview).
Use only a transcript version whose text rights are explicitly stated as
CC BY-NC-SA 4.0. Do not use ArchiMob audio or annotations with separate terms.

ArchiMob transcript-derived TSVs and any resulting `gsw` binary are licensed
under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
Releases must attribute ArchiMob and the transcript version, state that this
project filtered and transformed the transcript into frequency data, preserve
the ShareAlike requirement, and remain non-commercial. A commercial or
otherwise incompatible `gsw` distribution is not permitted.

## Acquisition lock

Before running the importer, record the catalogue URL, exact transcript
version/export identity, retrieval date, and SHA-256 of the acquired export in
the source review record. The source hash is an acquisition lock: pass that
exact value as `--source-sha256`, and the importer rejects a different input
before it decodes or processes transcript text.

```bash
python3 -m tools.prediction.import_archimob \
  --input /approved/archimob-transcript.txt \
  --source-sha256 "$(sha256sum /approved/archimob-transcript.txt | cut -d' ' -f1)" \
  --words-output /review/gsw.words.tsv \
  --ngrams-output /review/gsw.ngrams.tsv \
  --report-output /review/gsw.import-report.json \
  --minimum-count 2 \
  --top-targets 8
```

The report records accepted and rejected line counts plus the verified source
hash. Intermediate TSVs, reports, transcript exports, and all corpus data are
build inputs and must not be committed to Git.
