# ArchiMob transcript source

The Swiss German (`gsw`) importer accepts only an explicitly acquired UTF-8
**text transcript export** from ArchiMob. It never downloads a corpus. Its
source scope excludes ArchiMob audio and annotations with separate terms. The
concrete source record is SwissuBase study `20154`, catalogue record `19410`:
<https://www.swissubase.ch/en/catalogue/studies/20154/19410/overview>. The
established public metadata identifies Release 2 (2019), dataset `2269`,
version `1.0` (legacy `1.0.0`), version DOI
`10.48656/496p-3w34`, and collection `30843` (Release 2 XML archive). Use
only a transcript export whose text rights are explicitly stated as CC
BY-NC-SA 4.0.

ArchiMob transcript-derived TSVs and any resulting `gsw` binary are licensed
under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
Every generated asset must carry this attribution statement:

> Source: ArchiMob text transcripts (SwissuBase study 20154, catalogue record
> 19410), https://www.swissubase.ch/en/catalogue/studies/20154/19410/overview,
> CC BY-NC-SA 4.0, https://creativecommons.org/licenses/by-nc-sa/4.0/.
> Modified by Unexpected Keyboard: filtered and transformed into word and
> bigram frequency data.

Release notices must preserve the ShareAlike requirement and remain
non-commercial. A commercial or otherwise incompatible `gsw` distribution is
not permitted.

## Acquisition lock

There is no anonymous transcript/export URL. Before running the importer, the
release record must contain the authenticated downloaded transcript/export
version, export date, exporter or source-file identity, and SHA-256, as well
as the catalogue URL. The source hash is an acquisition lock: pass that exact
value as `--source-sha256`, and the importer rejects a different input before
it decodes or processes transcript text.

The public DIP MD5 `ca7356cb1e10d128c41ef1f8f930b54b` is not a
transcript/export source lock and must not be used as `--source-sha256` or
recorded as a source SHA-256. This document intentionally does not invent an
authenticated export version, date, source-file identity, or SHA-256. No
`gsw` pack can be promoted to `ready` or built as a ready pack until the actual
authenticated downloaded transcript/export SHA-256 and release fields are
locked in its registry entry and provenance record.

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
