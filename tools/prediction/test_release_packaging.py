import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ReleasePackagingTest(unittest.TestCase):
    def test_release_environment_verifier_reports_missing_signing_variables(self):
        environment = os.environ.copy()
        for variable in (
            "RELEASE_KEYSTORE",
            "RELEASE_KEYSTORE_PASSWORD",
            "RELEASE_KEY_ALIAS",
            "RELEASE_KEY_PASSWORD",
        ):
            environment.pop(variable, None)

        result = subprocess.run(
            ["/bin/sh", "tools/verify_release_environment.sh"],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RELEASE_KEYSTORE", result.stderr)

    def test_release_notice_verifier_compares_apk_asset_bytes(self):
        notice = (ROOT / "vendor" / "latinime" / "NOTICE").read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "app-release.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/latinime/NOTICE", notice)

            result = subprocess.run(
                [sys.executable, "tools/verify_release_notice.py", str(apk)],
                cwd=ROOT,
                capture_output=True,
                text=True,
            )

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_release_notice_verifier_rejects_different_apk_asset_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "app-release.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/latinime/NOTICE", b"wrong notice")

            result = subprocess.run(
                [sys.executable, "tools/verify_release_notice.py", str(apk)],
                cwd=ROOT,
                capture_output=True,
                text=True,
            )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match", result.stderr)

    def test_native_verifier_checks_existing_library_for_every_abi(self):
        abis = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            apk = directory / "build" / "outputs" / "apk" / "release" / "app-release.apk"
            apk.parent.mkdir(parents=True)
            log = directory / "readelf.log"
            with zipfile.ZipFile(apk, "w") as archive:
                for abi in abis:
                    archive.writestr(f"lib/{abi}/libjni_latinime.so", b"native library")

            readelf = directory / "llvm-readelf"
            readelf.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$@\" >> \"$READELF_LOG\"\n"
                "if [ \"$1\" = -lW ]; then\n"
                "  printf '  LOAD 0x000000 0x000000 0x000000 0x000000 0x000000 R E 0x4000\\n'\n"
                "fi\n",
                encoding="utf-8",
            )
            readelf.chmod(0o755)
            environment = os.environ | {
                "READELF": str(readelf),
                "READELF_LOG": str(log),
            }

            result = subprocess.run(
                [str(ROOT / "tools" / "verify_latinime_native.sh")],
                cwd=directory,
                env=environment,
                capture_output=True,
                text=True,
            )

            calls = log.read_text(encoding="utf-8") if log.exists() else ""

        self.assertEqual(result.returncode, 0, result.stderr)
        for abi in abis:
            self.assertIn(f"lib/{abi}/libjni_latinime.so", calls)


if __name__ == "__main__":
    unittest.main()
