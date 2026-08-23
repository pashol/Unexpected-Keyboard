import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE_DIR = ROOT / "test" / "fixtures" / "latinime"
BUILDER = ROOT / "tools" / "prediction" / "build_language_pack.py"
AOSP_COMMIT = "8081a1d8572f78488900438a6eaaec232b882bbf"


class BuildLanguagePackTest(unittest.TestCase):
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

    def test_builder_is_deterministic_against_checked_in_fixture(self):
        source = os.environ.get("AOSP_LATINIME_SOURCE")
        if not source:
            self.skipTest("AOSP_LATINIME_SOURCE must name the verified pinned checkout")

        source = pathlib.Path(source)
        self.assertEqual(
            subprocess.run(
                ["git", "-C", str(source), "rev-parse", "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip(),
            AOSP_COMMIT,
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_directory = pathlib.Path(temporary_directory)
            outputs = []
            for name in ("first", "second"):
                output = temporary_directory / (name + ".dict")
                manifest = temporary_directory / (name + ".json")
                subprocess.run(
                    [
                        sys.executable,
                        str(BUILDER),
                        "--source",
                        str(source),
                        "--input",
                        str(FIXTURE_DIR / "minimal_en.combined"),
                        "--output",
                        str(output),
                        "--manifest",
                        str(manifest),
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                outputs.append((output.read_bytes(), manifest.read_bytes()))

        self.assertEqual(outputs[0], outputs[1])
        self.assertEqual(outputs[0][0], (FIXTURE_DIR / "minimal_en.dict").read_bytes())
        self.assertEqual(outputs[0][1], (FIXTURE_DIR / "minimal_en.json").read_bytes())
        self.assertEqual(
            hashlib.sha256(outputs[0][0]).hexdigest(),
            json.loads(outputs[0][1])["output_sha256"],
        )


if __name__ == "__main__":
    unittest.main()
