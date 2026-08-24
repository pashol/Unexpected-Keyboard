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


class BuildLanguagePackIntegrationTest(unittest.TestCase):
    def test_builder_regenerates_the_checked_in_fixture(self):
        source = os.environ.get("AOSP_LATINIME_SOURCE")
        if not source:
            self.fail("AOSP_LATINIME_SOURCE must name the pinned AOSP LatinIME checkout")

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
            for locale in ("en", "gsw"):
                outputs = []
                for name in ("first", "second"):
                    output = temporary_directory / (locale + "-" + name + ".dict")
                    manifest = temporary_directory / (locale + "-" + name + ".json")
                    combined = temporary_directory / (locale + "-" + name + ".combined")
                    environment = os.environ | {"SOURCE_DATE_EPOCH": "0"}
                    sources = FIXTURE_DIR / "sources"
                    subprocess.run(
                        [
                            sys.executable,
                            str(BUILDER),
                            "--source", str(source),
                            "--locale", locale,
                            "--word-frequency-tsv", str(sources / (locale + ".words.tsv")),
                            "--ngram-tsv", str(sources / (locale + ".ngrams.tsv")),
                            "--provenance", str(sources / (locale + ".provenance.json")),
                            "--combined-output", str(combined),
                            "--output", str(output),
                            "--manifest", str(manifest),
                        ],
                        check=True,
                        capture_output=True,
                        text=True,
                        env=environment,
                    )
                    outputs.append((combined.read_bytes(), output.read_bytes(), manifest.read_bytes()))
                self.assertEqual(outputs[0], outputs[1])
                self.assertEqual(outputs[0][0], (FIXTURE_DIR / ("minimal_" + locale + ".combined")).read_bytes())
                self.assertEqual(outputs[0][1], (FIXTURE_DIR / ("minimal_" + locale + ".dict")).read_bytes())
                self.assertEqual(outputs[0][2], (FIXTURE_DIR / ("minimal_" + locale + ".json")).read_bytes())
                self.assertEqual(
                    hashlib.sha256(outputs[0][1]).hexdigest(),
                    json.loads(outputs[0][2])["output_sha256"],
                )


if __name__ == "__main__":
    unittest.main()
