import hashlib
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

from tools.prediction import import_archimob as importer


class ImportArchiMobTest(unittest.TestCase):
    def test_tokenize_preserves_swiss_german_variants_and_builds_sentence_bigrams(self):
        self.assertEqual(
            ["Mär", "gönd", "nöd", "hei"],
            importer.tokenize("Mär gönd nöd hei."),
        )
        self.assertEqual(
            {("Mär", "gönd"): 1, ("gönd", "nöd"): 1, ("nöd", "hei"): 1},
            importer.count_bigrams(["Mär", "gönd", "nöd", "hei"]),
        )

    def test_filter_line_excludes_transcription_metadata_and_urls(self):
        self.assertEqual("Mär gönd hei.", importer.filter_line("SPK_01: Mär gönd hei."))
        for line in (
            '<u who="SPK_01">Mär gönd hei.</u>',
            "[unverständlich] Mär gönd hei.",
            "00:01:23 Mär gönd hei.",
            "Mär gönd https://example.invalid hei.",
            "Mär\x00 gönd hei.",
        ):
            with self.subTest(line=line):
                self.assertIsNone(importer.filter_line(line))

    def test_aggregate_limits_ranked_targets_and_scores_deterministically(self):
        words, ngrams, report = importer.aggregate(
            [
                "Mär gönd nöd hei. Mär gönd gärn hei!\n",
                "Mär gönd nöd hei.\n",
            ],
            minimum_count=2,
            top_targets=1,
        )

        self.assertEqual([("Mär", 3), ("gönd", 5), ("hei", 2), ("nöd", 4)], words)
        self.assertEqual(
            [("Mär", "gönd", 255), ("gönd", "nöd", 170), ("nöd", "hei", 170)],
            ngrams,
        )
        self.assertEqual({"accepted_lines": 2, "rejected_lines": 0}, report)

    def test_main_publishes_resolvable_tsvs_and_source_hash_report(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            input_path.write_text("Mär gönd nöd hei.\n[noise]\n", encoding="utf-8")

            self._run_main(input_path, words_path, ngrams_path, report_path)

            words, ngrams = importer.load_current_generation(words_path, ngrams_path)
            self.assertEqual("Mär\t128\ngönd\t255\nhei\t128\nnöd\t255\n", words.read_text(encoding="utf-8"))
            self.assertEqual(
                "Mär\tgönd\t255\ngönd\tnöd\t255\nnöd\thei\t255\n",
                ngrams.read_text(encoding="utf-8"),
            )
            self.assertEqual(
                {
                    "accepted_lines": 1,
                    "rejected_lines": 1,
                    "source_sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
                },
                json.loads(report_path.read_text(encoding="utf-8")),
            )

    def test_main_rejects_a_source_hash_mismatch_before_writing_outputs(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "source SHA-256"):
                self._run_main(input_path, words_path, ngrams_path, report_path, "0" * 64)

            self.assertFalse(words_path.exists())
            self.assertFalse(ngrams_path.exists())
            self.assertFalse(report_path.exists())

    def _run_main(self, input_path, words_path, ngrams_path, report_path, source_sha256=None):
        arguments = [
            "import_archimob.py", "--input", str(input_path),
            "--source-sha256", source_sha256 or hashlib.sha256(input_path.read_bytes()).hexdigest(),
            "--words-output", str(words_path), "--ngrams-output", str(ngrams_path),
            "--report-output", str(report_path), "--minimum-count", "1", "--top-targets", "1",
        ]
        with mock.patch.object(sys, "argv", arguments):
            importer.main()


if __name__ == "__main__":
    unittest.main()
