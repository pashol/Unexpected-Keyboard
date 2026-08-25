import contextlib
import gzip
import hashlib
import io
import json
import pathlib
import tempfile
import unittest

from tools.prediction import import_aosp_combined as importer
from tools.prediction import import_archimob
from tools.prediction import import_google_books_ngrams


PARSE_INPUT = [
    "dictionary=main:de,locale=de_DE,description=x,date=1704207611,version=18",
    " word=Straße,f=222",
    "  bigram=Fuß,f=3",
    "  bigram=Haus,f=1",
    " word=Haus,f=200",
    " shortcut=foo,f=2",
    "",
    " word=Straße,f=210",
]


class ParseCombinedTest(unittest.TestCase):
    def test_parses_words_bigrams_and_repeats_by_maximum(self):
        words, bigrams, skipped = importer.parse_combined(PARSE_INPUT)
        self.assertEqual({"Straße": 222, "Haus": 200}, words)
        self.assertEqual({("Straße", "Fuß"): 3, ("Straße", "Haus"): 1}, bigrams)
        self.assertEqual({"skipped_lines": 2, "dropped_entries": 0, "dropped_not_a_word": 0}, skipped)

    def test_rejects_missing_header(self):
        with self.assertRaisesRegex(ValueError, "header"):
            importer.parse_combined([" word=x,f=1"])

    def test_rejects_malformed_frequency(self):
        with self.assertRaisesRegex(ValueError, "frequency"):
            importer.parse_combined(["dictionary=main:x", " word=x,f=high"])

    def test_drops_non_positive_frequencies(self):
        words, bigrams, dropped = importer.parse_combined(
            ["dictionary=main:x", " word=x,f=0", " word=y,f=2", "  bigram=z,f=-1"]
        )
        self.assertEqual({"y": 2}, words)
        self.assertEqual({}, bigrams)
        self.assertEqual({"skipped_lines": 0, "dropped_entries": 2, "dropped_not_a_word": 0}, dropped)

    def test_bigram_after_dropped_word_is_counted_as_dropped(self):
        words, bigrams, stats = importer.parse_combined(
            [
                "dictionary=main:x",
                " word=a,f=5",
                "  bigram=T,f=2",
                " word=b,f=0",
                "  bigram=T,f=4",
            ]
        )
        self.assertEqual({"a": 5}, words)
        self.assertEqual({("a", "T"): 2}, bigrams)
        self.assertEqual({"skipped_lines": 0, "dropped_entries": 2, "dropped_not_a_word": 0}, stats)

    def test_rejects_bigram_before_any_word(self):
        with self.assertRaisesRegex(ValueError, "precedes"):
            importer.parse_combined(["dictionary=main:x", "  bigram=T,f=1"])

    def test_keeps_words_and_bigrams_with_ignorable_attributes(self):
        words, bigrams, stats = importer.parse_combined(
            [
                "dictionary=main:x",
                " word=sex,f=135,possibly_offensive=true",
                "  bigram=y,f=3,whatever=1",
                " word=der,f=216,flags=,originalFreq=216",
            ]
        )
        self.assertEqual({"sex": 135, "der": 216}, words)
        self.assertEqual({("sex", "y"): 3}, bigrams)
        self.assertEqual(
            {"skipped_lines": 0, "dropped_entries": 0, "dropped_not_a_word": 0}, stats)

    def test_drops_not_a_word_words_and_their_bigrams(self):
        words, bigrams, stats = importer.parse_combined(
            [
                "dictionary=main:x",
                " word=keep,f=10",
                "  bigram=z,f=1",
                " word=heres,f=55,not_a_word=true",
                "  bigram=x,f=2",
            ]
        )
        self.assertEqual({"keep": 10}, words)
        self.assertEqual({("keep", "z"): 1}, bigrams)
        self.assertEqual(
            {"skipped_lines": 0, "dropped_entries": 1, "dropped_not_a_word": 1}, stats)

    def test_rejects_malformed_attribute_token(self):
        with self.assertRaisesRegex(ValueError, "malformed combined attribute"):
            importer.parse_combined(["dictionary=main:x", " word=x,f=1,badtoken"])

    def test_rejects_empty_input_without_header(self):
        with self.assertRaisesRegex(ValueError, "header"):
            importer.parse_combined([])


class ApplyMapsTest(unittest.TestCase):
    def test_maps_characters_and_merges_collisions_by_maximum(self):
        words = {"Straße": 222, "Strasse": 100, "Haus": 50}
        bigrams = {("Straße", "Fuß"): 3, ("Strasse", "Fuss"): 2}
        mapped_words, mapped_bigrams, replacements = importer.apply_word_maps(
            words, bigrams, [("ß", "ss")]
        )
        self.assertEqual({"Strasse": 222, "Haus": 50}, mapped_words)
        self.assertEqual({("Strasse", "Fuss"): 3}, mapped_bigrams)
        self.assertEqual(1, replacements)


class MergeOverlayTest(unittest.TestCase):
    def test_union_maximum_merge_counts_contributions(self):
        words, bigrams, report = importer.merge_overlay(
            {"Strasse": 222},
            {("Strasse", "Haus"): 3},
            {"Strasse": 10, "Velo": 90, "Billett": 80},
            {},
        )
        self.assertEqual({"Strasse": 222, "Velo": 90, "Billett": 80}, words)
        self.assertEqual({("Strasse", "Haus"): 3}, bigrams)
        self.assertEqual(2, report["overlay_added_words"])
        self.assertEqual(1, report["overlay_merged_words"])
        self.assertEqual(0, report["overlay_added_bigrams"])


class SelectNgramsTest(unittest.TestCase):
    def test_filters_endpoints_threshold_and_caps_targets(self):
        selected, capped, below = importer.select_ngrams(
            {"a": 9, "b": 8, "c": 7, "x": 5},
            {("a", "b"): 4, ("a", "c"): 3, ("a", "x"): 2, ("b", "a"): 5, ("x", "a"): 1},
            minimum_count=2,
            top_targets=2,
        )
        self.assertEqual(
            {("a", "b"): 4, ("a", "c"): 3, ("b", "a"): 5},
            selected,
        )
        self.assertEqual(1, capped)
        self.assertEqual(1, below)

    def test_rejects_non_positive_minimum_count_and_top_targets(self):
        with self.assertRaisesRegex(ValueError, "minimum_count"):
            importer.select_ngrams({}, {}, minimum_count=0, top_targets=2)
        with self.assertRaisesRegex(ValueError, "top_targets"):
            importer.select_ngrams({}, {}, minimum_count=2, top_targets=0)


BASE = "\n".join([
    "dictionary=main:de,locale=de_DE,description=x,version=18",
    " word=Straße,f=222",
    "  bigram=Fuß,f=3",
    " word=Haus,f=200",
    "  bigram=Straße,f=2",
]) + "\n"
OVERLAY = "\n".join([
    "dictionary=main:de_CH,vendor=openboard",
    " word=Velo,f=90",
    " word=Haus,f=150",
]) + "\n"


class CliTest(unittest.TestCase):
    def _run(self, arguments):
        importer.main(arguments)

    def test_publishes_scored_generations_with_report_and_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text(BASE, encoding="utf-8")
            overlay = root / "overlay.combined"
            overlay.write_text(OVERLAY, encoding="utf-8")
            words_out = root / "de.words.tsv"
            ngrams_out = root / "de.ngrams.tsv"
            report_out = root / "de.import-report.json"
            self._run([
                "--input", str(base), "--input-sha256", _sha(base),
                "--overlay", str(overlay), "--overlay-sha256", _sha(overlay),
                "--map", "ß:ss",
                "--words-output", str(words_out), "--ngrams-output", str(ngrams_out),
                "--report-output", str(report_out),
                "--minimum-count", "1", "--top-targets", "8",
            ])
            pointer = json.loads(
                import_google_books_ngrams.current_manifest_path(words_out, ngrams_out)
                .read_text(encoding="utf-8"))
            self.assertEqual(1, pointer["format_version"])
            words_path, _, published_report = import_archimob.load_current_generation(
                words_out, ngrams_out)
            words = dict(
                line.split("\t") for line in
                words_path.read_text(encoding="utf-8").splitlines())
            self.assertEqual({"Strasse", "Haus", "Velo"}, set(words))
            self.assertEqual("255", words["Strasse"])  # highest f stretches to 255
            self.assertEqual("230", words["Haus"])
            self.assertEqual("104", words["Velo"])
            report = json.loads(published_report.read_text(encoding="utf-8"))
            self.assertEqual(1, report["overlay_added_words"])
            self.assertEqual(1, report["overlay_merged_words"])
            self.assertEqual(1, report["mapped_words"])
            # (Strasse, Fuss) is dropped: Fuss only occurs as a bigram target,
            # and select_ngrams requires both endpoints to be known words.
            self.assertEqual(1, report["accepted_bigrams"])
            receipt = json.loads(report_out.read_text(encoding="utf-8"))
            self.assertEqual(1, receipt["format_version"])
            self.assertIn("active_generation", receipt)

    def test_rejects_input_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text(BASE, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                self._run([
                    "--input", str(base), "--input-sha256", "0" * 64,
                    "--words-output", str(root / "w.tsv"),
                    "--ngrams-output", str(root / "n.tsv"),
                    "--report-output", str(root / "r.json"),
                    "--minimum-count", "1", "--top-targets", "8",
                ])

    def test_rejects_report_output_colliding_with_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "base.combined"
            source.write_text(BASE, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "paths must differ"):
                self._run([
                    "--input", str(source), "--input-sha256", _sha(source),
                    "--words-output", str(root / "w.tsv"),
                    "--ngrams-output", str(root / "n.tsv"),
                    "--report-output", str(source),
                    "--minimum-count", "1", "--top-targets", "8",
                ])
            self.assertEqual(BASE, source.read_text(encoding="utf-8"))

    def test_report_keeps_base_and_overlay_parse_stats_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text("\n".join([
                "dictionary=main:de",
                " word=Straße,f=222",
                " word=Haus,f=200",
                "  bigram=Haus,f=2",
                " shortcut=foo,f=2",
                " word=Gelöscht,f=0",
            ]) + "\n", encoding="utf-8")
            overlay = root / "overlay.combined"
            overlay.write_text("\n".join([
                "dictionary=main:de_CH",
                " word=Velo,f=90",
            ]) + "\n", encoding="utf-8")
            words_out = root / "de.words.tsv"
            ngrams_out = root / "de.ngrams.tsv"
            self._run([
                "--input", str(base), "--input-sha256", _sha(base),
                "--overlay", str(overlay), "--overlay-sha256", _sha(overlay),
                "--words-output", str(words_out), "--ngrams-output", str(ngrams_out),
                "--report-output", str(root / "de.import-report.json"),
                "--minimum-count", "1", "--top-targets", "8",
            ])
            _, _, published_report = import_archimob.load_current_generation(
                words_out, ngrams_out)
            report = json.loads(published_report.read_text(encoding="utf-8"))
            self.assertEqual(1, report["skipped_lines"])
            self.assertEqual(0, report["overlay_skipped_lines"])
            self.assertEqual(1, report["dropped_entries"])
            self.assertEqual(0, report["overlay_dropped_entries"])

    def test_rejects_empty_selection_even_at_minimum_count_one(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text("\n".join([
                "dictionary=main:de",
                " word=Solo,f=7",
            ]) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "no retained n-grams"):
                self._run([
                    "--input", str(base), "--input-sha256", _sha(base),
                    "--words-output", str(root / "w.tsv"),
                    "--ngrams-output", str(root / "n.tsv"),
                    "--report-output", str(root / "r.json"),
                    "--minimum-count", "1", "--top-targets", "8",
                ])

    def test_accepts_gzip_compressed_input_end_to_end(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            compressed = root / "base.combined.gz"
            with open(compressed, "wb") as raw:
                with gzip.GzipFile(fileobj=raw, mode="wb") as packed:
                    packed.write(BASE.encode("utf-8"))
            words_out = root / "de.words.tsv"
            ngrams_out = root / "de.ngrams.tsv"
            self._run([
                "--input", str(compressed), "--input-sha256", _sha(compressed),
                "--map", "ß:ss",
                "--words-output", str(words_out), "--ngrams-output", str(ngrams_out),
                "--report-output", str(root / "de.import-report.json"),
                "--minimum-count", "1", "--top-targets", "8",
            ])
            words_path, ngrams_path, _ = import_archimob.load_current_generation(
                words_out, ngrams_out)
            self.assertEqual(
                {"Strasse": "255", "Haus": "230"},
                dict(line.split("\t") for line in
                     words_path.read_text(encoding="utf-8").splitlines()))
            self.assertEqual(
                "Haus\tStrasse\t255\n", ngrams_path.read_text(encoding="utf-8"))

    def test_rejects_overlay_hash_without_overlay(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            base = root / "base.combined"
            base.write_text(BASE, encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit):
                    self._run([
                        "--input", str(base), "--input-sha256", _sha(base),
                        "--overlay-sha256", "a" * 64,
                        "--words-output", str(root / "w.tsv"),
                        "--ngrams-output", str(root / "n.tsv"),
                        "--report-output", str(root / "r.json"),
                        "--minimum-count", "1", "--top-targets", "8",
                    ])
            self.assertIn("--overlay", stderr.getvalue())


def _sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()
