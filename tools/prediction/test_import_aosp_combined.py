import unittest

from tools.prediction import import_aosp_combined as importer


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
        self.assertEqual({"skipped_lines": 2, "dropped_entries": 0}, skipped)

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
        self.assertEqual({"skipped_lines": 0, "dropped_entries": 2}, dropped)


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
