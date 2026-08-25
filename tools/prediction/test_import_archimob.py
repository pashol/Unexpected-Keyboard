import hashlib
import io
import json
import pathlib
import struct
import sys
import tempfile
import unittest
import zipfile
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

            words, ngrams, report = importer.load_current_generation(words_path, ngrams_path)
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
                json.loads(report.read_text(encoding="utf-8")),
            )
            pointer = json.loads(
                importer.import_google_books_ngrams.current_manifest_path(
                    words_path, ngrams_path
                ).read_text(encoding="utf-8")
            )
            self.assertEqual("report.json", pathlib.PurePath(pointer["report"]["path"]).name)
            self.assertEqual(
                hashlib.sha256(report.read_bytes()).hexdigest(), pointer["report"]["sha256"]
            )
            self.assertTrue(report_path.is_file())
            receipt = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(1, receipt["format_version"])
            self.assertEqual(
                hashlib.sha256(
                    importer.import_google_books_ngrams.current_manifest_path(
                        words_path, ngrams_path
                    ).read_bytes()
                ).hexdigest(),
                receipt["active_generation"]["current_manifest_sha256"],
            )
            self.assertEqual(pointer["report"]["sha256"], receipt["active_generation"]["report_sha256"])

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

    def test_main_rejects_report_output_at_active_publication_paths(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")
            publication_paths = (
                importer.import_google_books_ngrams.current_manifest_path(words_path, ngrams_path),
                importer.import_google_books_ngrams.generation_root_path(words_path, ngrams_path),
            )

            for report_path in publication_paths:
                with self.subTest(report_path=report_path):
                    with self.assertRaisesRegex(ValueError, "publication paths"):
                        self._run_main(input_path, words_path, ngrams_path, report_path)
                    self.assertFalse(words_path.exists())
                    self.assertFalse(ngrams_path.exists())
                    self.assertFalse(report_path.exists())

    def test_main_rejects_report_name_that_collides_with_generation_tsv(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "report name"):
                self._run_main(input_path, words_path, ngrams_path, directory / "other" / "words.tsv")

            self.assertFalse(words_path.exists())
            self.assertFalse(ngrams_path.exists())

    def test_report_staging_failure_does_not_advance_the_active_generation(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")
            self._run_main(input_path, words_path, ngrams_path, report_path)
            pointer = importer.import_google_books_ngrams.current_manifest_path(words_path, ngrams_path)
            original_pointer = pointer.read_bytes()
            input_path.write_text("Mär gönd gärn hei.\n", encoding="utf-8")
            path_open = pathlib.Path.open

            def fail_report_open(path, *args, **kwargs):
                if path.name == report_path.name and path.parent != directory:
                    raise OSError("simulated report staging failure")
                return path_open(path, *args, **kwargs)

            with mock.patch.object(pathlib.Path, "open", new=fail_report_open):
                with self.assertRaisesRegex(OSError, "report staging"):
                    self._run_main(input_path, words_path, ngrams_path, report_path)

            self.assertEqual(original_pointer, pointer.read_bytes())

    def test_active_manifest_failure_keeps_the_prior_report_receipt(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")
            self._run_main(input_path, words_path, ngrams_path, report_path)
            pointer = importer.import_google_books_ngrams.current_manifest_path(words_path, ngrams_path)
            original = (pointer.read_bytes(), report_path.read_bytes())
            input_path.write_text("Mär gönd gärn hei.\n", encoding="utf-8")
            replace = importer.import_google_books_ngrams.os.replace

            def fail_manifest_replace(source, destination):
                if pathlib.Path(destination) == pointer:
                    raise OSError("simulated active manifest failure")
                replace(source, destination)

            with mock.patch.object(
                importer.import_google_books_ngrams.os, "replace", side_effect=fail_manifest_replace
            ):
                with self.assertRaisesRegex(OSError, "active manifest"):
                    self._run_main(input_path, words_path, ngrams_path, report_path)

            self.assertEqual(original, (pointer.read_bytes(), report_path.read_bytes()))

    def test_receipt_failure_rolls_back_the_active_generation(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")
            self._run_main(input_path, words_path, ngrams_path, report_path)
            pointer = importer.import_google_books_ngrams.current_manifest_path(words_path, ngrams_path)
            original = (pointer.read_bytes(), report_path.read_bytes())
            input_path.write_text("Mär gönd gärn hei.\n", encoding="utf-8")
            replace = importer.import_google_books_ngrams.os.replace
            active_manifest_replaced = False

            def fail_receipt_after_manifest_replace(source, destination):
                nonlocal active_manifest_replaced
                if pathlib.Path(destination) == pointer:
                    active_manifest_replaced = True
                if pathlib.Path(destination) == report_path and active_manifest_replaced:
                    raise OSError("simulated receipt failure")
                replace(source, destination)

            with mock.patch.object(
                importer.import_google_books_ngrams.os,
                "replace",
                side_effect=fail_receipt_after_manifest_replace,
            ):
                with self.assertRaisesRegex(OSError, "receipt failure"):
                    self._run_main(input_path, words_path, ngrams_path, report_path)

            self.assertEqual(original, (pointer.read_bytes(), report_path.read_bytes()))

    def test_main_parses_the_same_descriptor_that_it_hashes(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            original = "Mär gönd nöd hei.\n"
            replacement = "Du bisch da.\n"
            input_path.write_text(original, encoding="utf-8")
            source_hash = hashlib.sha256(input_path.read_bytes()).hexdigest()
            source_hash_function = importer._sha256_opened

            def replace_after_hash(source):
                result = source_hash_function(source)
                replacement_path = directory / "replacement.txt"
                replacement_path.write_text(replacement, encoding="utf-8")
                replacement_path.replace(input_path)
                return result

            with mock.patch.object(importer, "_sha256_opened", side_effect=replace_after_hash):
                self._run_main(input_path, words_path, ngrams_path, report_path, source_hash)

            words, _, _ = importer.load_current_generation(words_path, ngrams_path)
            self.assertIn("Mär\t", words.read_text(encoding="utf-8"))
            self.assertNotIn("Du\t", words.read_text(encoding="utf-8"))

    def test_main_reads_nested_tei_archive_surface_words_per_utterance(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.zip"
            words_path = directory / "words.tsv"
            ngrams_path = directory / "ngrams.tsv"
            report_path = directory / "report.json"
            nested = io.BytesIO()
            with zipfile.ZipFile(nested, "w") as archive:
                archive.writestr(
                    "Archimob_Release_2/1007.xml",
                    """<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><text><body>
                    <u who=\"speaker\"><w normalised=\"Wir\">Mär</w><w normalised=\"gehen\">gönd</w></u>
                    <u who=\"speaker\"><w normalised=\"nicht\">nöd</w><w normalised=\"heim\">hei</w></u>
                    </body></text><teiHeader><w>ignored</w></teiHeader></TEI>""",
                )
                archive.writestr("Archimob_Release_2/Metadata.txt", "not a transcript")
            with zipfile.ZipFile(input_path, "w") as archive:
                archive.writestr("Archimob_Release_2.zip", nested.getvalue())
                archive.writestr("release_notes.pdf", b"not a transcript")

            self._run_main(input_path, words_path, ngrams_path, report_path)

            words, ngrams, report = importer.load_current_generation(words_path, ngrams_path)
            self.assertEqual("Mär\t255\ngönd\t255\nhei\t255\nnöd\t255\n", words.read_text(encoding="utf-8"))
            self.assertEqual("Mär\tgönd\t255\nnöd\thei\t255\n", ngrams.read_text(encoding="utf-8"))
            self.assertNotIn("Wir", words.read_text(encoding="utf-8"))
            self.assertEqual({"accepted_lines": 2, "rejected_lines": 0, "source_sha256": hashlib.sha256(input_path.read_bytes()).hexdigest()}, json.loads(report.read_text(encoding="utf-8")))

    def test_archive_sequences_rejects_an_oversized_nested_archive(self):
        archive = self._nested_archive([("1007.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"/>")])

        with mock.patch.multiple(
            importer,
            MAX_NESTED_ARCHIVE_COMPRESSED_BYTES=1,
            MAX_NESTED_ARCHIVE_UNCOMPRESSED_BYTES=1,
            create=True,
        ):
            with self.assertRaisesRegex(ValueError, "nested archive.*limit"):
                importer.archive_sequences(archive)

    def test_archive_sequences_rejects_too_many_transcript_members(self):
        archive = self._nested_archive([
            ("1007.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"/>"),
            ("1008.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"/>"),
        ])

        with mock.patch.object(importer, "MAX_TRANSCRIPT_XML_MEMBERS", 1, create=True):
            with self.assertRaisesRegex(ValueError, "transcript.*limit"):
                importer.archive_sequences(archive)

    def test_archive_sequences_rejects_an_excessive_compression_ratio(self):
        archive = self._nested_archive(
            [("1007.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><text><body><u><w>" + "a" * 1000 + "</w></u></body></text></TEI>")],
            compression=zipfile.ZIP_DEFLATED,
        )

        with mock.patch.object(importer, "MAX_COMPRESSION_RATIO", 1, create=True):
            with self.assertRaisesRegex(ValueError, "compression ratio"):
                importer.archive_sequences(archive)

    def test_archive_sequences_rejects_too_many_outer_empty_entries(self):
        archive = self._nested_archive(
            [("1007.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"/>")], outer_empty_entries=2
        )

        with mock.patch.object(importer, "MAX_OUTER_ARCHIVE_MEMBERS", 1, create=True):
            with self.assertRaisesRegex(ValueError, "outer archive.*entry.*limit"):
                importer.archive_sequences(archive)

    def test_archive_sequences_rejects_too_many_nested_empty_entries(self):
        archive = self._nested_archive(
            [("1007.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"/>")], nested_empty_entries=2
        )

        with mock.patch.object(importer, "MAX_NESTED_ARCHIVE_MEMBERS", 1, create=True):
            with self.assertRaisesRegex(ValueError, "nested archive.*entry.*limit"):
                importer.archive_sequences(archive)

    def test_main_rejects_an_oversized_input_before_hashing(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "archimob.txt"
            input_path.write_text("Mär gönd nöd hei.\n", encoding="utf-8")

            with mock.patch.object(importer, "MAX_SOURCE_INPUT_BYTES", 1, create=True):
                with mock.patch.object(importer, "_sha256_opened", side_effect=AssertionError):
                    with self.assertRaisesRegex(ValueError, "source input.*limit"):
                        self._run_main(
                            input_path, directory / "words.tsv", directory / "ngrams.tsv",
                            directory / "report.json",
                        )

    def test_zip_preflight_rejects_an_oversized_declared_entry_count(self):
        archive = io.BytesIO(self._minimal_eocd(entry_count=2))

        with mock.patch.object(importer, "MAX_OUTER_ARCHIVE_MEMBERS", 1):
            with mock.patch.object(importer.zipfile, "ZipFile", side_effect=AssertionError):
                with self.assertRaisesRegex(ValueError, "entry count exceeds limit"):
                    importer.archive_sequences(archive)

    def test_zip_preflight_accepts_a_normal_archive(self):
        archive = self._nested_archive([("1007.xml", "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"/>")])

        importer._preflight_zip(
            archive, maximum_members=importer.MAX_OUTER_ARCHIVE_MEMBERS,
            description="outer archive",
        )

    def test_archive_sequences_collects_body_utterances_nested_in_divisions(self):
        archive = self._nested_archive([(
            "1007.xml",
            "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><teiHeader><u><w>header</w></u></teiHeader>"
            "<text><body><div><u><w normalised=\"Standard\">Mär</w><w>gönd</w></u></div>"
            "<u><w>nöd</w><w>hei</w></u></body></text></TEI>",
        )])

        self.assertEqual(
            [["Mär", "gönd"], ["nöd", "hei"]], importer.archive_sequences(archive)
        )

    def _minimal_eocd(self, entry_count):
        return struct.pack(
            "<4s4H2LH", b"PK\x05\x06", 0, 0, entry_count, entry_count, 0, 0, 0
        )

    def _nested_archive(
            self, transcripts, compression=zipfile.ZIP_STORED,
            outer_empty_entries=0, nested_empty_entries=0):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w", compression=compression) as archive:
            for name, contents in transcripts:
                archive.writestr("Archimob_Release_2/" + name, contents)
            for index in range(nested_empty_entries):
                archive.writestr("Archimob_Release_2/empty-" + str(index), b"")
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w", compression=compression) as archive:
            archive.writestr("Archimob_Release_2.zip", nested.getvalue())
            for index in range(outer_empty_entries):
                archive.writestr("empty-" + str(index), b"")
        outer.seek(0)
        return outer

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
