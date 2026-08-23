import hashlib
import io
import json
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
            input_path = directory / "input.combined"
            input_path.write_text("input", encoding="utf-8")
            collision = directory / "generated" / "output.dict"
            arguments = [
                str(BUILDER), "--source", str(directory), "--input", str(input_path),
                "--output", str(collision), "--manifest", str(collision),
            ]

            with mock.patch.object(sys, "argv", arguments), \
                    mock.patch.object(pathlib.Path, "mkdir") as mkdir, \
                    mock.patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit) as error:
                    build_language_pack.main()

            self.assertEqual(error.exception.code, 2)
            mkdir.assert_not_called()

    def test_builder_requires_an_explicit_source_and_never_clones(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = pathlib.Path(temporary_directory)
            input_path = directory / "input.combined"
            input_path.write_text("input", encoding="utf-8")
            arguments = [
                str(BUILDER), "--input", str(input_path), "--output", str(directory / "output.dict"),
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
        self.assertEqual(
            json.loads(manifest.read_text(encoding="utf-8")),
            {
                "aosp_commit": "8081a1d8572f78488900438a6eaaec232b882bbf",
                "format_version": 202,
                "input_sha256": "1eef46e0e80f357d6196b184f3067ed6c1881ae7dfcc6693d8009353fc712c5b",
                "locale": "en",
                "output_sha256": "5e645ef5c29b9169b55b6566467cdd7a5ad9da76eba6ccd3e437ddb20b081d4d",
            },
        )
        self.assertEqual(
            json.loads(manifest.read_text(encoding="utf-8"))["output_sha256"],
            output_sha256,
        )

if __name__ == "__main__":
    unittest.main()
