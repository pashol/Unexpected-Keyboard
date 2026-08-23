import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ReleasePackagingTest(unittest.TestCase):
    def test_apk_asset_is_generated_from_the_vendored_latinime_notice(self):
        build_file = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn('from("vendor/latinime/NOTICE")', build_file)
        self.assertIn('generated-assets/latinime', build_file)
        self.assertIn("dependsOn(verifyReleasePackaging)", build_file)

    def test_native_verifier_checks_every_load_segment_for_every_abi(self):
        verifier = (ROOT / "tools" / "verify_latinime_native.sh").read_text(encoding="utf-8")

        self.assertIn('APP_ABI="armeabi-v7a arm64-v8a x86 x86_64"', verifier)
        self.assertIn('awk \'$1 == "LOAD" {', verifier)
        self.assertIn('if ($NF != "0x4000")', verifier)
        self.assertIn('if (loads == 0)', verifier)
        self.assertIn('grep -q \'Build ID:\'', verifier)


if __name__ == "__main__":
    unittest.main()
