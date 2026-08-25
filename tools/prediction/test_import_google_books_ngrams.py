import pathlib
import sqlite3
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

    def test_aggregate_preserves_apostrophes_and_hyphens(self):
        words, ngrams = importer.aggregate([
            "don't night's\t2000\t4\t1\n",
            "d'Frau well-known\t2000\t3\t1\n",
        ], minimum_count=1, top_targets=1)

        self.assertEqual(
            [("d'Frau", 3), ("don't", 4), ("night's", 4), ("well-known", 3)], words
        )
        self.assertEqual(
            [("d'Frau", "well-known", 192), ("don't", "night's", 255)], ngrams
        )

    def test_aggregate_uses_sqlite_for_many_input_rows(self):
        rows = ["hello world\t2000\t1\t1\n"] * 1000

        with mock.patch.object(importer.sqlite3, "connect", wraps=sqlite3.connect) as connect:
            words, ngrams = importer.aggregate(rows, minimum_count=1, top_targets=1)

        self.assertTrue(connect.called)
        self.assertEqual([("hello", 1000), ("world", 1000)], words)
        self.assertEqual([("hello", "world", 255)], ngrams)

    def test_aggregate_rejects_malformed_rows(self):
        with self.assertRaisesRegex(ValueError, "row 1"):
            importer.aggregate(["hello world\t2000\t4\n"], minimum_count=1, top_targets=1)

    def test_aggregate_requires_positive_filter_parameters(self):
        for minimum_count, top_targets in ((0, 1), (1, 0)):
            with self.subTest(minimum_count=minimum_count, top_targets=top_targets):
                with self.assertRaisesRegex(ValueError, "positive"):
                    importer.aggregate(
                        ["hello world\t2000\t1\t1\n"], minimum_count, top_targets
                    )

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

    def test_main_rejects_input_and_output_path_collisions(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.tsv"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text("hello world\t2000\t2\t1\n", encoding="utf-8")
            words_path.write_text("existing\t1\n", encoding="utf-8")
            ngrams_path.write_text("hello\tworld\t1\n", encoding="utf-8")

            for paths in (
                (input_path, input_path, ngrams_path),
                (input_path, words_path, input_path),
                (input_path, words_path, words_path),
                (input_path, directory / "." / "input.tsv", ngrams_path),
            ):
                with self.subTest(paths=paths), self.assertRaisesRegex(ValueError, "paths must differ"):
                    self._run_main(*paths)

    def test_main_rejects_existing_hard_link_output_collision(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.tsv"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text("hello world\t2000\t2\t1\n", encoding="utf-8")
            words_path.hardlink_to(input_path)

            with self.assertRaisesRegex(ValueError, "paths must differ"):
                self._run_main(input_path, words_path, ngrams_path)

    def test_main_keeps_existing_outputs_when_an_input_target_is_unsafe(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.tsv"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text(
                "hello world\t2000\t2\t1\nhello bad\x00target\t2000\t2\t1\n",
                encoding="utf-8",
            )
            words_path.write_text("old-word\t7\n", encoding="utf-8")
            ngrams_path.write_text("old\ttarget\t7\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "unsafe token"):
                self._run_main(input_path, words_path, ngrams_path)

            self.assertEqual("old-word\t7\n", words_path.read_text(encoding="utf-8"))
            self.assertEqual("old\ttarget\t7\n", ngrams_path.read_text(encoding="utf-8"))
            self.assertEqual([], list(directory.glob(".*.tmp")))

    def test_main_restores_outputs_when_replacing_the_second_output_fails(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.tsv"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text("hello world\t2000\t2\t1\n", encoding="utf-8")
            words_path.write_text("old-word\t7\n", encoding="utf-8")
            ngrams_path.write_text("old\ttarget\t7\n", encoding="utf-8")
            replace = importer.os.replace

            def fail_second_output(source, destination):
                if pathlib.Path(destination) == ngrams_path and str(source).endswith(".tmp"):
                    raise OSError("simulated replacement failure")
                replace(source, destination)

            with mock.patch.object(importer.os, "replace", side_effect=fail_second_output):
                with self.assertRaisesRegex(OSError, "simulated"):
                    self._run_main(input_path, words_path, ngrams_path)

            self.assertEqual("old-word\t7\n", words_path.read_text(encoding="utf-8"))
            self.assertEqual("old\ttarget\t7\n", ngrams_path.read_text(encoding="utf-8"))

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
