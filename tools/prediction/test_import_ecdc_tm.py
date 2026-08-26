import hashlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

from tools.prediction import import_ecdc_tm as importer


class ImportEcdcTmTest(unittest.TestCase):
    def test_archive_sequences_keeps_languages_and_translation_unit_boundaries(self):
        archive = self._archive(
            """<?xml version="1.0" encoding="UTF-8"?>
            <tmx version="1.4"><body>
            <tu><tuv xml:lang="en"><seg>Stay home. Stay safe.</seg></tuv>
                <tuv xml:lang="de"><seg>Bleiben Sie zu Hause. Bleiben Sie sicher.</seg></tuv></tu>
            <tu><tuv xml:lang="de-DE"><seg>Danke.</seg></tuv>
                <tuv xml:lang="en-US"><seg>Thank you.</seg></tuv></tu>
            <tu><tuv xml:lang="en"><seg>Ignored.</seg></tuv>
                <tuv xml:lang="fr"><seg>Ignore.</seg></tuv></tu>
            </body></tmx>"""
        )

        sequences, report = importer.archive_sequences(archive)

        self.assertEqual(
            {
                "en": [["Stay", "home"], ["Stay", "safe"], ["Thank", "you"]],
                "de": [["Bleiben", "Sie", "zu", "Hause"], ["Bleiben", "Sie", "sicher"], ["Danke"]],
            },
            sequences,
        )
        self.assertEqual({"accepted_translation_units": 2, "rejected_translation_units": 1}, report)

    def test_main_publishes_separate_deterministic_language_generations(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "ECDC-TM.zip"
            input_path.write_bytes(self._archive_bytes(
                """<tmx version="1.4"><body>
                <tu><tuv xml:lang="en"><seg>Stay home.</seg></tuv><tuv xml:lang="de"><seg>Bleiben Sie zuhause.</seg></tuv></tu>
                <tu><tuv xml:lang="en"><seg>Stay safe.</seg></tuv><tuv xml:lang="de"><seg>Bleiben Sie sicher.</seg></tuv></tu>
                </body></tmx>"""
            ))
            paths = {language: (directory / (language + ".words.tsv"), directory / (language + ".ngrams.tsv"), directory / (language + ".report.json")) for language in ("en", "de")}

            self._run_main(input_path, paths)

            en_words, en_ngrams, en_report = importer.load_current_generation(*paths["en"][:2])
            de_words, de_ngrams, de_report = importer.load_current_generation(*paths["de"][:2])
            self.assertEqual("Stay\t255\nhome\t128\nsafe\t128\n", en_words.read_text(encoding="utf-8"))
            self.assertEqual("Stay\thome\t255\nStay\tsafe\t255\n", en_ngrams.read_text(encoding="utf-8"))
            self.assertEqual("Bleiben\t128\nSie\t255\nsicher\t64\nzuhause\t64\n", de_words.read_text(encoding="utf-8"))
            self.assertEqual("Bleiben\tSie\t255\nSie\tsicher\t128\nSie\tzuhause\t128\n", de_ngrams.read_text(encoding="utf-8"))
            expected_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()
            self.assertEqual(expected_hash, json.loads(en_report.read_text(encoding="utf-8"))["source_sha256"])
            self.assertEqual(expected_hash, json.loads(de_report.read_text(encoding="utf-8"))["source_sha256"])

    def test_archive_rejects_xml_larger_than_the_member_limit(self):
        archive = self._archive("<tmx><body/></tmx>")

        with mock.patch.object(importer, "MAX_TMX_MEMBER_BYTES", 1):
            with self.assertRaisesRegex(ValueError, "TMX.*limit"):
                importer.archive_sequences(archive)

    def test_archive_accepts_the_standard_external_tmx_dtd_declaration(self):
        archive = self._archive(
            """<!DOCTYPE tmx SYSTEM "tmx14.dtd"><tmx version="1.4"><body>
            <tu><tuv xml:lang="en"><seg>Stay safe.</seg></tuv>
            <tuv xml:lang="de"><seg>Bleiben Sie sicher.</seg></tuv></tu>
            </body></tmx>"""
        )

        sequences, _ = importer.archive_sequences(archive)

        self.assertEqual([["Stay", "safe"]], sequences["en"])

    def test_archive_rejects_unsafe_or_ambiguous_members(self):
        for names in (("../ECDC-TM.tmx",), ("one.tmx", "two.tmx")):
            data = io.BytesIO()
            with zipfile.ZipFile(data, "w") as archive:
                for name in names:
                    archive.writestr(name, "<tmx><body/></tmx>")
            with self.subTest(names=names), self.assertRaisesRegex(ValueError, "unsafe|exactly one"):
                importer.archive_sequences(io.BytesIO(data.getvalue()))

    def test_archive_rejects_entities_and_malformed_zip(self):
        for contents in (b"not a zip", self._archive_bytes("<!DOCTYPE tmx [<!ENTITY x 'x'>]><tmx><body/></tmx>")):
            with self.subTest(contents=contents[:8]), self.assertRaisesRegex(ValueError, "malformed|DTD"):
                importer.archive_sequences(io.BytesIO(contents))

    def test_archive_rejects_excess_members_and_compression_ratio(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("ECDC-TM.tmx", "x" * 1000)
            archive.writestr("extra", "x")
        with mock.patch.object(importer, "MAX_ARCHIVE_MEMBERS", 1):
            with self.assertRaisesRegex(ValueError, "entry count"):
                importer.archive_sequences(io.BytesIO(data.getvalue()))
        with mock.patch.object(importer, "MAX_COMPRESSION_RATIO", 1):
            with self.assertRaisesRegex(ValueError, "TMX XML"):
                importer.archive_sequences(io.BytesIO(data.getvalue()))

    def _archive(self, contents):
        return io.BytesIO(self._archive_bytes(contents))

    @staticmethod
    def _archive_bytes(contents):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("ECDC-TM.tmx", contents)
        return data.getvalue()

    @staticmethod
    def _run_main(input_path, paths):
        arguments = [
            "import_ecdc_tm.py", "--input", str(input_path),
            "--source-sha256", hashlib.sha256(input_path.read_bytes()).hexdigest(),
            "--en-words-output", str(paths["en"][0]), "--en-ngrams-output", str(paths["en"][1]),
            "--en-report-output", str(paths["en"][2]),
            "--de-words-output", str(paths["de"][0]), "--de-ngrams-output", str(paths["de"][1]),
            "--de-report-output", str(paths["de"][2]), "--minimum-count", "1", "--top-targets", "8",
        ]
        with mock.patch.object(sys, "argv", arguments):
            importer.main()


if __name__ == "__main__":
    unittest.main()
