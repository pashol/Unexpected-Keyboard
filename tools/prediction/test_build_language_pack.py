import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools" / "prediction" / "build_language_pack.py"
FIXTURES = ROOT / "test" / "fixtures" / "latinime"
BUNDLED_COMPILER = ROOT / "tools" / "prediction" / "dicttool_aosp.jar"
COMPILER = pathlib.Path(os.environ.get("DICTTOOL_AOSP_JAR", BUNDLED_COMPILER))
COMPILER_COMMIT = "1e69dd2c04258e5e9e04bfb13b46faccf6a435b0"
COMPILER_SHA256 = "a8c5bd21f631ed0a92235d42d2fe83af5d70216172bf7e22781a9a946858237e"
COMPILER_REPOSITORY = "https://github.com/remi0s/aosp-dictionary-tools"


class NormalizeGswTest(unittest.TestCase):
    def test_preserves_regional_variants_diacritics_apostrophes_and_case(self):
        from tools.prediction.normalize_gsw import normalize

        words = ["n\u00f6d", "nid", "ned", "Chunsch", "chumm", "isch", "guet",
                 "\u00e4s", "\u00f6ppis", "\u00fcber", "d'Chind"]

        self.assertEqual(words, [normalize(word) for word in words])


class BuildLanguagePackTest(unittest.TestCase):
    def test_rejects_aosp_combined_record_delimiters(self):
        cases = (
            ("locale", "en,evil", "hello", "world"),
            ("locale", "en=evil", "hello", "world"),
            ("locale", "en\n", "hello", "world"),
            ("word", "en", "hel\x00lo", "world"),
            ("word", "en", "hel=lo", "world"),
            ("target", "en", "hello", "wor,ld"),
            ("target", "en", "hello", "wor\rld"),
        )
        for kind, locale, word, target in cases:
            with self.subTest(kind=kind, value=(locale, word, target)):
                result = self.build_with(
                    locale, word + "\t10\n" + target + "\t10\n",
                    word + "\t" + target + "\t3\n")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("AOSP combined record delimiter", result.stderr)

    def test_rejects_duplicate_bigram_pairs_in_any_order(self):
        for ngrams in (
            "hello\tworld\t3\nhello\tworld\t4\n",
            "hello\tworld\t4\nhello\tworld\t3\n",
        ):
            with self.subTest(ngrams=ngrams):
                result = self.build_with("en", "hello\t10\nworld\t10\n", ngrams)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("duplicate n-gram pair: hello -> world", result.stderr)

    def test_emits_sorted_aosp_combined_records(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = pathlib.Path(temporary)
            words = temporary_path / "words.tsv"
            ngrams = temporary_path / "ngrams.tsv"
            manifest = temporary_path / "sources.json"
            output = temporary_path / "pack.dict"
            combined = temporary_path / "pack.combined"
            words.write_text("world\t20\nhello\t30\nthere\t10\n", encoding="utf-8")
            ngrams.write_text("hello\tthere\t2\nhello\tworld\t3\n", encoding="utf-8")
            manifest.write_text(
                json.dumps({"license": "CC0-1.0", "provenance": "test"}),
                encoding="utf-8")

            result = subprocess.run(
                ["python3", str(TOOL), "--locale", "en", "--words", str(words),
                 "--ngrams", str(ngrams), "--source-manifest", str(manifest),
                 "--output", str(output), "--combined-output", str(combined),
                 "--compiler", str(COMPILER)],
                text=True, capture_output=True, env=dict(os.environ,
                                                          SOURCE_DATE_EPOCH="0"))

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                "dictionary=main:en,locale=en,version=1,date=0\n"
                "word=hello,f=30\n"
                "bigram=there,f=2\n"
                "bigram=world,f=3\n"
                "word=there,f=10\n"
                "word=world,f=20\n",
                combined.read_text(encoding="utf-8"))
            self.assert_manifest(
                json.loads(output.with_suffix(".manifest.json").read_text(encoding="utf-8")),
                "en", words, ngrams, manifest, output)

    def test_rejects_source_without_license_or_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = pathlib.Path(temporary)
            words = temporary_path / "words.tsv"
            ngrams = temporary_path / "ngrams.tsv"
            manifest = temporary_path / "sources.json"
            output = temporary_path / "pack.dict"
            words.write_text("hello\t10\n", encoding="utf-8")
            ngrams.write_text("hello\tworld\t3\n", encoding="utf-8")
            manifest.write_text("{}", encoding="utf-8")

            result = subprocess.run(
                ["python3", str(TOOL), "--locale", "en", "--words", str(words),
                 "--ngrams", str(ngrams), "--source-manifest", str(manifest),
                 "--output", str(output), "--compiler", "/compiler/pinned"],
                text=True, capture_output=True, env=dict(os.environ,
                                                          SOURCE_DATE_EPOCH="0"))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("license", result.stderr.lower())
        self.assertIn("provenance", result.stderr.lower())

    def test_rejects_compiler_with_unpinned_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = pathlib.Path(temporary)
            words = temporary_path / "words.tsv"
            ngrams = temporary_path / "ngrams.tsv"
            manifest = temporary_path / "sources.json"
            compiler = temporary_path / "dicttool_aosp.jar"
            words.write_text("hello\t10\nworld\t10\n", encoding="utf-8")
            ngrams.write_text("hello\tworld\t3\n", encoding="utf-8")
            manifest.write_text(
                json.dumps({"license": "CC0-1.0", "provenance": "test"}),
                encoding="utf-8")
            compiler.write_bytes(b"not the pinned compiler")

            result = subprocess.run(
                ["python3", str(TOOL), "--locale", "en", "--words", str(words),
                 "--ngrams", str(ngrams), "--source-manifest", str(manifest),
                 "--output", str(temporary_path / "pack.dict"), "--compiler", str(compiler)],
                text=True, capture_output=True, env=dict(os.environ,
                                                          SOURCE_DATE_EPOCH="0"))

        self.assertNotEqual(0, result.returncode)
        self.assertIn("a8c5bd21f631ed0a92235d42d2fe83af5d70216172bf7e22781a9a946858237e",
                      result.stderr)

    def test_regeneration_matches_checked_in_fixture_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                ["python3", str(TOOL), "--verify-fixtures", str(FIXTURES)],
                text=True, capture_output=True, env=dict(os.environ,
                                                          SOURCE_DATE_EPOCH="0"))

        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
        for locale in ("en", "gsw"):
            words = FIXTURES / ("minimal_" + locale + ".words.tsv")
            ngrams = FIXTURES / ("minimal_" + locale + ".ngrams.tsv")
            sources = FIXTURES / ("minimal_" + locale + ".sources.json")
            dictionary = FIXTURES / ("minimal_" + locale + ".dict")
            self.assert_manifest(manifest[locale], locale, words, ngrams, sources, dictionary)

    def test_rejects_tampered_fixture_manifest(self):
        for field, value in (
            ("source_provenance", ["tampered"]),
            ("compiler.repository", "https://example.invalid/tampered"),
        ):
            with self.subTest(field=field):
                with tempfile.TemporaryDirectory() as temporary:
                    copied_fixtures = pathlib.Path(temporary) / "fixtures"
                    shutil.copytree(FIXTURES, copied_fixtures)
                    manifest_path = copied_fixtures / "manifest.json"
                    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
                    if field == "source_provenance":
                        manifest["en"][field] = value
                    else:
                        manifest["en"]["compiler"]["repository"] = value
                    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

                    result = subprocess.run(
                        ["python3", str(TOOL), "--verify-fixtures", str(copied_fixtures)],
                        text=True, capture_output=True, env=dict(os.environ,
                                                                  SOURCE_DATE_EPOCH="0"))

                self.assertNotEqual(0, result.returncode)
                self.assertIn("manifest differs", result.stderr)

    def test_rejects_missing_source_date_epoch_without_traceback(self):
        environment = dict(os.environ)
        environment.pop("SOURCE_DATE_EPOCH", None)
        result = self.build_with("en", "hello\t10\nworld\t10\n",
                                 "hello\tworld\t3\n", environment)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("SOURCE_DATE_EPOCH must be set", result.stderr)
        self.assertNotIn("KeyError", result.stderr)

    def build_with(self, locale, words_text, ngrams_text, environment=None):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = pathlib.Path(temporary)
            words = temporary_path / "words.tsv"
            ngrams = temporary_path / "ngrams.tsv"
            manifest = temporary_path / "sources.json"
            words.write_text(words_text, encoding="utf-8")
            ngrams.write_text(ngrams_text, encoding="utf-8")
            manifest.write_text(
                json.dumps({"license": "CC0-1.0", "provenance": "test"}),
                encoding="utf-8")
            return subprocess.run(
                ["python3", str(TOOL), "--locale", locale, "--words", str(words),
                 "--ngrams", str(ngrams), "--source-manifest", str(manifest),
                 "--output", str(temporary_path / "pack.dict"), "--compiler", str(COMPILER)],
                text=True, capture_output=True,
                env=environment or dict(os.environ, SOURCE_DATE_EPOCH="0"))

    def assert_manifest(self, manifest, locale, words, ngrams, sources, output):
        self.assertEqual(locale, manifest["locale"])
        self.assertEqual(202, manifest["format"])
        self.assertEqual(["CC0-1.0"], manifest["source_licenses"])
        self.assertEqual(
            {
                words.name: hashlib.sha256(words.read_bytes()).hexdigest(),
                ngrams.name: hashlib.sha256(ngrams.read_bytes()).hexdigest(),
                sources.name: hashlib.sha256(sources.read_bytes()).hexdigest(),
            },
            manifest["source_hashes"])
        self.assertEqual(hashlib.sha256(output.read_bytes()).hexdigest(),
                         manifest["output_hash"])
        self.assertEqual(COMPILER_COMMIT, manifest["build_tool_commit"])
        self.assertEqual(COMPILER_REPOSITORY, manifest["compiler"]["repository"])
        self.assertEqual(COMPILER_COMMIT, manifest["compiler"]["commit"])
        self.assertEqual(COMPILER_SHA256, manifest["compiler"]["sha256"])
        self.assertEqual(0, manifest["build_timestamp"])


if __name__ == "__main__":
    unittest.main()
