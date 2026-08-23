import hashlib
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE_DIR = ROOT / "test" / "fixtures" / "latinime"


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


if __name__ == "__main__":
    unittest.main()
