import hashlib
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

from tools.prediction import build_language_pack

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE_DIR = ROOT / "test" / "fixtures" / "latinime"
BUILDER = ROOT / "tools" / "prediction" / "build_language_pack.py"


class BuildLanguagePackTest(unittest.TestCase):
    def test_gsw_normalization_preserves_dialect_unicode_apostrophes_and_casing(self):
        self.assertEqual("nöd", build_language_pack.normalize_word("gsw", "nöd"))
        self.assertEqual("nid", build_language_pack.normalize_word("gsw", "nid"))
        self.assertEqual("ned", build_language_pack.normalize_word("gsw", "ned"))
        self.assertEqual("Chäs", build_language_pack.normalize_word("gsw", "Chäs"))
        self.assertEqual("d'Frau", build_language_pack.normalize_word("gsw", "d'Frau"))
        self.assertEqual("MÄR", build_language_pack.normalize_word("gsw", "MÄR"))

    def test_tsv_inputs_are_normalized_sorted_and_rendered_as_combined_source(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            words = directory / "words.tsv"
            ngrams = directory / "ngrams.tsv"
            words.write_text("z'Morge\t7\nChäs\t10\n", encoding="utf-8")
            ngrams.write_text("z'Morge\tChäs\t9\n", encoding="utf-8")

            self.assertEqual(
                "dictionary=main,locale=gsw\n"
                "word=Chäs,f=10\n"
                "word=z'Morge,f=7\n"
                "bigram=Chäs,f=9\n",
                build_language_pack.combined_source("gsw", words, ngrams),
            )

    def test_duplicate_tsv_rows_use_the_highest_frequency_independent_of_order(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            words = directory / "words.tsv"
            ngrams = directory / "ngrams.tsv"
            words.write_text("there\t20\nhello\t30\nhello\t10\n", encoding="utf-8")
            ngrams.write_text("hello\tthere\t9\nhello\tthere\t7\n", encoding="utf-8")

            combined = build_language_pack.combined_source("en", words, ngrams)

        self.assertIn("word=hello,f=30", combined)
        self.assertIn("bigram=there,f=9", combined)

    def test_manifest_records_provenance_hashes_compiler_and_epoch_timestamp(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            words = directory / "words.tsv"
            ngrams = directory / "ngrams.tsv"
            output = directory / "pack.dict"
            words.write_text("hello\t10\n", encoding="utf-8")
            ngrams.write_text("hello\tworld\t9\n", encoding="utf-8")
            output.write_bytes(b"dictionary")

            manifest = build_language_pack.manifest_data(
                "en", words, ngrams, words, output,
                {"corpus": {"license": "CC0-1.0", "source_sha256": "a" * 64}},
                42, {
                    "aosp_revision": build_language_pack.AOSP_COMMIT,
                    "input_sha256": "compiler-hash",
                    "jdk": {"javac_version": "javac test"},
                },
            )
            words_hash = hashlib.sha256(words.read_bytes()).hexdigest()
            ngrams_hash = hashlib.sha256(ngrams.read_bytes()).hexdigest()
            output_hash = hashlib.sha256(output.read_bytes()).hexdigest()

        self.assertEqual("en", manifest["locale"])
        self.assertEqual(202, manifest["format_version"])
        self.assertEqual("1970-01-01T00:00:42Z", manifest["timestamp"])
        self.assertEqual("CC0-1.0", manifest["sources"]["corpus"]["license"])
        self.assertEqual(words_hash, manifest["sources"]["word_frequency_tsv"]["sha256"])
        self.assertEqual(ngrams_hash, manifest["sources"]["ngram_tsv"]["sha256"])
        self.assertEqual(output_hash, manifest["output_sha256"])
        self.assertEqual(build_language_pack.AOSP_COMMIT, manifest["compiler"]["aosp_revision"])
        self.assertEqual("compiler-hash", manifest["compiler"]["input_sha256"])
        self.assertEqual("javac test", manifest["compiler"]["jdk"]["javac_version"])

    def test_provenance_requires_nonempty_licensed_sources_with_hashes(self):
        for provenance in (
            {},
            {"corpus": {}},
            {"corpus": {"license": "CC0-1.0"}},
            {"corpus": {"source_sha256": "a" * 64}},
            {"corpus": {"license": "", "source_sha256": "a" * 64}},
            {"corpus": {"license": "CC0-1.0", "source_sha256": "not-a-hash"}},
        ):
            with self.assertRaisesRegex(ValueError, "provenance"):
                build_language_pack.validate_provenance(provenance)

        self.assertEqual(
            {"corpus": {"license": "CC0-1.0", "source_sha256": "a" * 64}},
            build_language_pack.validate_provenance(
                {"corpus": {"license": "CC0-1.0", "source_sha256": "a" * 64}}
            ),
        )

    def test_provenance_hash_must_match_a_declared_input_file(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            words = directory / "words.tsv"
            ngrams = directory / "ngrams.tsv"
            words.write_text("hello\t1\n", encoding="utf-8")
            ngrams.write_text("", encoding="utf-8")
            provenance = {
                "fixture": {
                    "license": "CC0-1.0",
                    "source_path": "words.tsv",
                    "source_sha256": hashlib.sha256(words.read_bytes()).hexdigest(),
                }
            }

            self.assertEqual(
                provenance,
                build_language_pack.validate_provenance(provenance, directory, [words, ngrams]),
            )
            provenance["fixture"]["source_sha256"] = "a" * 64
            with self.assertRaisesRegex(ValueError, "does not match"):
                build_language_pack.validate_provenance(provenance, directory, [words, ngrams])

    def test_compiler_requires_the_pinned_jdk_identity(self):
        with mock.patch.object(
            build_language_pack, "jdk_identity", return_value={
                "java_version": "openjdk 21.0.0",
                "javac_version": "javac 21.0.0",
            }
        ):
            with self.assertRaisesRegex(ValueError, "pinned JDK"):
                build_language_pack.verified_jdk_identity()

    def test_compiler_rejects_java_from_a_different_jdk_than_javac(self):
        with mock.patch.object(
            build_language_pack, "jdk_identity", return_value={
                "java_path": "/other-jdk/bin/java",
                "java_version": "17.0.19",
                "javac_path": "/pinned-jdk/bin/javac",
                "javac_version": "javac 17.0.19",
            }
        ):
            with self.assertRaisesRegex(ValueError, "same JDK"):
                build_language_pack.verified_jdk_identity()

    def test_compiler_requires_the_pinned_java_runtime_version(self):
        with mock.patch.object(
            build_language_pack, "jdk_identity", return_value={
                "java_path": "/pinned-jdk/bin/java",
                "java_version": "21.0.0",
                "javac_path": "/pinned-jdk/bin/javac",
                "javac_version": "javac 17.0.19",
            }
        ):
            with self.assertRaisesRegex(ValueError, "pinned Java"):
                build_language_pack.verified_jdk_identity()

    def test_provenance_cannot_replace_generated_input_hashes(self):
        for reserved_name in ("ngram_tsv", "word_frequency_tsv"):
            with self.assertRaisesRegex(ValueError, "reserved"):
                build_language_pack.validate_provenance({
                    reserved_name: {"license": "CC0-1.0", "source_sha256": "a" * 64}
                })

    def test_compiler_identity_hashes_aosp_and_generated_java_inputs(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            aosp = directory / "aosp" / "Dicttool.java"
            generated = directory / "generated" / "CommandList.java"
            aosp.parent.mkdir()
            generated.parent.mkdir()
            aosp.write_text("class Dicttool {}\n", encoding="utf-8")
            generated.write_text("class CommandList {}\n", encoding="utf-8")

            with mock.patch.object(
                build_language_pack, "jdk_identity", return_value={
                    "java_path": "/pinned-jdk/bin/java",
                    "java_version": "17.0.19",
                    "javac_path": "/pinned-jdk/bin/javac",
                    "javac_version": "javac 17.0.19",
                }
            ):
                identity = build_language_pack.compiler_identity(
                    [("aosp/Dicttool.java", aosp), ("generated/CommandList.java", generated)]
                )
                generated.write_text("class CommandList { int changed; }\n", encoding="utf-8")
                changed_identity = build_language_pack.compiler_identity(
                    [("aosp/Dicttool.java", aosp), ("generated/CommandList.java", generated)]
                )

        self.assertEqual(build_language_pack.AOSP_COMMIT, identity["aosp_revision"])
        self.assertIn("builder", identity)
        self.assertEqual(
            {"java_version": "17.0.19", "javac_version": "javac 17.0.19"}, identity["jdk"]
        )
        self.assertNotEqual(identity["input_sha256"], changed_identity["input_sha256"])

    def test_builder_rejects_input_output_collision(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            input_path = pathlib.Path(temporary_directory) / "input.combined"
            input_path.write_text("input", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "input and output"):
                build_language_pack.validate_paths(input_path, input_path, input_path.with_suffix(".json"))

    def test_builder_rejects_input_manifest_collision(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            input_path = pathlib.Path(temporary_directory) / "input.combined"
            input_path.write_text("input", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "input and manifest"):
                build_language_pack.validate_paths(input_path, input_path.with_suffix(".dict"), input_path)

    def test_builder_rejects_output_manifest_collision(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = pathlib.Path(temporary_directory) / "output.dict"

            with self.assertRaisesRegex(ValueError, "output and manifest"):
                build_language_pack.validate_paths(output.with_suffix(".combined"), output, output)

    def test_builder_rejects_resolved_path_aliases(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.combined"
            input_path.write_text("input", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "input and output"):
                build_language_pack.validate_paths(
                    input_path, directory / "." / "input.combined", directory / "manifest.json"
                )

    def test_builder_rejects_existing_hard_link_aliases(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.combined"
            output = directory / "output.dict"
            input_path.write_text("input", encoding="utf-8")
            output.hardlink_to(input_path)

            with self.assertRaisesRegex(ValueError, "input and output"):
                build_language_pack.validate_paths(input_path, output, directory / "manifest.json")

    def test_builder_rejects_collisions_before_creating_output_directories(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            words = directory / "words.tsv"
            ngrams = directory / "ngrams.tsv"
            provenance = directory / "provenance.json"
            words.write_text("hello\t1\n", encoding="utf-8")
            ngrams.write_text("", encoding="utf-8")
            provenance.write_text("{}", encoding="utf-8")
            collision = directory / "generated" / "output.dict"
            arguments = [
                str(BUILDER), "--source", str(directory), "--locale", "en",
                "--word-frequency-tsv", str(words), "--ngram-tsv", str(ngrams),
                "--provenance", str(provenance),
                "--output", str(collision), "--manifest", str(collision),
            ]

            with mock.patch.object(sys, "argv", arguments), \
                    mock.patch.object(pathlib.Path, "mkdir") as mkdir, \
                    mock.patch.dict(os.environ, {"SOURCE_DATE_EPOCH": "0"}), \
                    mock.patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit) as error:
                    build_language_pack.main()

            self.assertEqual(error.exception.code, 2)
            mkdir.assert_not_called()

    def test_builder_requires_an_explicit_source_and_never_clones(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            words = directory / "words.tsv"
            ngrams = directory / "ngrams.tsv"
            provenance = directory / "provenance.json"
            words.write_text("hello\t1\n", encoding="utf-8")
            ngrams.write_text("", encoding="utf-8")
            provenance.write_text("{}", encoding="utf-8")
            arguments = [
                str(BUILDER), "--locale", "en", "--word-frequency-tsv", str(words),
                "--ngram-tsv", str(ngrams), "--provenance", str(provenance),
                "--output", str(directory / "output.dict"),
                "--manifest", str(directory / "output.json"),
            ]

            with mock.patch.object(sys, "argv", arguments), \
                    mock.patch.object(build_language_pack, "run") as run, \
                    mock.patch.object(build_language_pack, "verified_checkout"), \
                    mock.patch.object(build_language_pack, "build"), \
                    mock.patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit) as error:
                    build_language_pack.main()

            self.assertEqual(error.exception.code, 2)
            run.assert_not_called()

    def test_canonical_combined_input_is_stable(self):
        combined = FIXTURE_DIR / "minimal_en.combined"

        self.assertEqual(
            combined.read_text(encoding="utf-8"),
            "dictionary=main,locale=en\n"
            "word=hello,f=255\n"
            "bigram=there,f=90\n"
            "bigram=world,f=100\n"
            "word=there,f=180\n"
            "word=world,f=200\n",
        )
        self.assertEqual(
            hashlib.sha256(combined.read_bytes()).hexdigest(),
            "1eef46e0e80f357d6196b184f3067ed6c1881ae7dfcc6693d8009353fc712c5b",
        )

    def test_generated_fixture_has_fixed_manifest_and_format_202_header(self):
        output = FIXTURE_DIR / "minimal_en.dict"
        manifest = FIXTURE_DIR / "minimal_en.json"

        self.assertEqual(output.read_bytes()[:6], b"\x9b\xc1\x3a\xfe\x00\xca")
        output_sha256 = hashlib.sha256(output.read_bytes()).hexdigest()
        data = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual("en", data["locale"])
        self.assertEqual(202, data["format_version"])
        self.assertEqual("1970-01-01T00:00:00Z", data["timestamp"])
        self.assertEqual(build_language_pack.AOSP_COMMIT, data["compiler"]["aosp_revision"])
        self.assertEqual("17.0.19", data["compiler"]["jdk"]["java_version"])
        self.assertEqual("javac 17.0.19", data["compiler"]["jdk"]["javac_version"])
        self.assertEqual(output_sha256, data["output_sha256"])
        self.assertEqual("CC0-1.0", data["sources"]["fixture"]["license"])

    def test_registry_accepts_only_supported_development_pack_with_matching_manifest_and_hash(self):
        registry = build_language_pack.load_language_packs(
            FIXTURE_DIR / "language_packs.json", development_only=True
        )

        self.assertEqual(["en"], [pack["locale"] for pack in registry])
        self.assertEqual("minimal_en.dict", registry[0]["dictionary"])
        self.assertEqual("CC0-1.0", registry[0]["asset_license"])
        self.assertEqual("ATTRIBUTION.en.md", registry[0]["attribution"])

    def test_registry_rejects_a_production_style_entry_without_asset_metadata(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            registry_path = pathlib.Path(temporary_directory) / "language_packs.json"
            registry = json.loads((FIXTURE_DIR / "language_packs.json").read_text(encoding="utf-8"))
            registry["packs"] = [registry["packs"][0]]
            registry["packs"][0]["dictionary"] = str((FIXTURE_DIR / "minimal_en.dict").resolve())
            registry["packs"][0]["manifest"] = str((FIXTURE_DIR / "minimal_en.json").resolve())
            registry["packs"][0].pop("asset_license")
            registry["packs"][0].pop("attribution")
            registry_path.write_text(json.dumps(registry), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "asset_license"):
                build_language_pack.load_language_packs(registry_path)

    def test_registry_rejects_an_artifact_pack_with_a_missing_attribution_file(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            registry_path = pathlib.Path(temporary_directory) / "language_packs.json"
            registry = json.loads((FIXTURE_DIR / "language_packs.json").read_text(encoding="utf-8"))
            registry["packs"] = [registry["packs"][0]]
            registry["packs"][0]["dictionary"] = str((FIXTURE_DIR / "minimal_en.dict").resolve())
            registry["packs"][0]["manifest"] = str((FIXTURE_DIR / "minimal_en.json").resolve())
            registry["packs"][0]["attribution"] = "missing-attribution.md"
            registry_path.write_text(json.dumps(registry), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "attribution file must exist"):
                build_language_pack.load_language_packs(registry_path)

    def test_production_registry_lists_source_pending_packs_without_artifact_references(self):
        registry_path = ROOT / "assets" / "latinime" / "packs" / "language_packs.json"
        registry = json.loads(registry_path.read_text(encoding="utf-8"))

        self.assertEqual([], build_language_pack.load_language_packs(registry_path))
        self.assertEqual(["en", "de", "de-CH", "gsw"], [pack["locale"] for pack in registry["source_pending"]])
        for pack in registry["source_pending"]:
            self.assertIsInstance(pack["asset_license"], str)
            self.assertTrue(pack["asset_license"])
            self.assertTrue((registry_path.parent / pack["attribution"]).is_file())
            self.assertFalse({"dictionary", "manifest", "ngram_tsv", "output_sha256", "provenance", "word_frequency_tsv"}.intersection(pack))

    def test_gsw_attribution_names_generated_asset_license_obligations(self):
        attribution = (ROOT / "assets" / "latinime" / "packs" / "ATTRIBUTION.gsw.md").read_text(encoding="utf-8")

        self.assertIn("CC BY-NC-SA 4.0 requires attribution", attribution)
        self.assertIn("ShareAlike", attribution)
        self.assertIn("generated GSW asset", attribution)

    def test_registry_rejects_an_entry_with_a_stale_output_hash(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            registry_path = pathlib.Path(temporary_directory) / "language_packs.json"
            registry = json.loads((FIXTURE_DIR / "language_packs.json").read_text(encoding="utf-8"))
            registry["packs"][0]["dictionary"] = str((FIXTURE_DIR / "minimal_en.dict").resolve())
            registry["packs"][0]["manifest"] = str((FIXTURE_DIR / "minimal_en.json").resolve())
            registry["packs"][0]["attribution"] = str((FIXTURE_DIR / "ATTRIBUTION.en.md").resolve())
            registry["packs"][0]["output_sha256"] = "0" * 64
            registry_path.write_text(json.dumps(registry), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "does not match its manifest"):
                build_language_pack.load_language_packs(registry_path)

if __name__ == "__main__":
    unittest.main()
