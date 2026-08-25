import pathlib
import sys
import tempfile
import unittest
from unittest import mock

from tools.prediction import import_google_books_ngrams as importer


class ImportGoogleBooksNgramsTest(unittest.TestCase):
    def test_aggregate_two_gram_rows_sums_years_and_keeps_top_targets(self):
        rows = [
            "hello world\t2000\t4\t1\n",
            "hello world\t2001\t6\t2\n",
            "hello there\t2000\t3\t1\n",
            "bad-token! world\t2000\t99\t1\n",
        ]

        words, ngrams = importer.aggregate(rows, minimum_count=3, top_targets=1)

        self.assertEqual([("hello", 10), ("world", 10)], words)
        self.assertEqual([("hello", "world", 255)], ngrams)

    def test_aggregate_breaks_target_count_ties_by_token(self):
        words, ngrams = importer.aggregate([
            "hello zebra\t2000\t5\t1\n",
            "hello alpha\t2000\t5\t1\n",
        ], minimum_count=1, top_targets=1)

        self.assertEqual([("alpha", 5), ("hello", 5)], words)
        self.assertEqual([("hello", "alpha", 255)], ngrams)

    def test_aggregate_normalizes_german_unicode_to_nfc(self):
        words, ngrams = importer.aggregate(
            ["gru\u0308ße schön\t2000\t4\t1\n"], minimum_count=1, top_targets=1
        )

        self.assertEqual([("grüße", 4), ("schön", 4)], words)
        self.assertEqual([("grüße", "schön", 255)], ngrams)

    def test_aggregate_rejects_malformed_rows(self):
        with self.assertRaisesRegex(ValueError, "row 1"):
            importer.aggregate(["hello world\t2000\t4\n"], minimum_count=1, top_targets=1)

    def test_aggregate_does_not_hide_malformed_numeric_fields_in_unsafe_tokens(self):
        with self.assertRaisesRegex(ValueError, "row 1"):
            importer.aggregate(
                ["bad-token! world\t2000\tnot-a-number\t1\n"],
                minimum_count=1,
                top_targets=1,
            )

    def test_main_writes_deterministically_scored_tsv(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.tsv"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text(
                "zeta beta\t2000\t5\t1\nalpha delta\t2000\t10\t1\n",
                encoding="utf-8",
            )

            self._run_main(input_path, words_path, ngrams_path)

            self.assertEqual(
                "alpha\t255\nbeta\t128\ndelta\t255\nzeta\t128\n",
                words_path.read_text(encoding="utf-8"),
            )
            self.assertEqual(
                "alpha\tdelta\t255\nzeta\tbeta\t128\n",
                ngrams_path.read_text(encoding="utf-8"),
            )

    def test_main_rejects_empty_retained_result(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.tsv"
            input_path.write_text("hello world\t2000\t1\t1\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "no retained"):
                self._run_main(input_path, directory / "words.tsv", directory / "ngrams.tsv")

    def _run_main(self, input_path, words_path, ngrams_path):
        arguments = [
            "import_google_books_ngrams.py", "--input", str(input_path), "--locale", "de",
            "--words-output", str(words_path), "--ngrams-output", str(ngrams_path),
            "--minimum-count", "2", "--top-targets", "1",
        ]
        with mock.patch.object(sys, "argv", arguments):
            importer.main()


if __name__ == "__main__":
    unittest.main()
