# ECDC Translation Memory 2012

The EN, DE, and `de-CH` production packs use the single official ECDC Translation
Memory archive, `ECDC-TM.zip`:

- Archive: https://wt-public.emm4u.eu/Resources/ECDC-TM/ECDC-TM.zip
- Description: https://joint-research-centre.ec.europa.eu/language-technology-resources/ecdc-translation-memory_en
- Terms: https://wt-public.emm4u.eu/Resources/ECDC-TM/2012_10_Terms-of-Use_ECDC-TM.pdf
- Version: October 2012
- Acquired archive SHA-256: `cfd2dec349e4e5c9faab56596b708f4808a614733f6f763f49b2d6f74c9e86d8`

The EU/ECDC Reuse Licence, implementing Commission Reuse Decision 2011/833/EU,
grants worldwide, royalty-free, perpetual, non-exclusive reuse, including
commercial reuse. Unmodified distributions must retain the copyright notice and
no-warranty disclaimer. Derived products must not use the owner names to endorse
or promote themselves without prior written permission.

`import_ecdc_tm.py` accepts only an explicit local ZIP and its SHA-256. It bounds
ZIP and XML resources, accepts only translation units with both EN and DE
variants, and emits independent external EN and DE generations. Build `de-CH`
from the DE generation separately so its dictionary and manifest retain the
`de-CH` locale; it must never stand in for `gsw`.

The corpus comprises professionally translated ECDC public-health material. Its
vocabulary and next-word distributions are public-health biased, so these compact
packs are not general-purpose language models. Never commit the archive, TMX,
generated TSVs, combined source, or generation reports.
