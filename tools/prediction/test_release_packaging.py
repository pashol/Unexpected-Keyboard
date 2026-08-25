import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ReleasePackagingTest(unittest.TestCase):
    def test_generated_production_assets_include_the_ready_gsw_pack_and_not_raw_inputs(self):
        environment = os.environ | {
            "JAVA_HOME": "/usr/lib/jvm/java-17-openjdk-amd64",
            "PATH": "/usr/lib/jvm/java-17-openjdk-amd64/bin:" + os.environ["PATH"],
            "ANDROID_HOME": os.environ.get("ANDROID_HOME", "/home/pascal/Android/Sdk"),
        }
        result = subprocess.run(
            ["./gradlew", "--no-configuration-cache", "copyLatinimeProductionPacks"],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        generated = ROOT / "build" / "generated-assets" / "latinime" / "packs"
        self.assertTrue((generated / "language_packs.json").is_file())
        self.assertEqual(
            (ROOT / "assets" / "latinime" / "packs" / "gsw.dict").read_bytes(),
            (generated / "gsw.dict").read_bytes(),
        )
        self.assertEqual(
            (ROOT / "assets" / "latinime" / "packs" / "gsw.json").read_bytes(),
            (generated / "gsw.json").read_bytes(),
        )
        self.assertTrue((generated / "ATTRIBUTION.gsw.md").is_file())
        self.assertTrue((generated / "gsw.attestation.json").is_file())
        self.assertFalse((generated / "sources").exists())

    def test_debug_apk_contains_gsw_dictionary_manifest_and_attribution(self):
        environment = os.environ | {
            "JAVA_HOME": "/usr/lib/jvm/java-17-openjdk-amd64",
            "PATH": "/usr/lib/jvm/java-17-openjdk-amd64/bin:" + os.environ["PATH"],
            "ANDROID_HOME": os.environ.get("ANDROID_HOME", "/home/pascal/Android/Sdk"),
        }
        result = subprocess.run(
            ["./gradlew", "--no-configuration-cache", "assembleDebug"],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        apk = ROOT / "build" / "outputs" / "apk" / "debug" / "Unexpected-Keyboard-debug.apk"
        with zipfile.ZipFile(apk) as archive:
            for name in (
                "assets/latinime/packs/gsw.dict",
                "assets/latinime/packs/gsw.json",
                "assets/latinime/packs/ATTRIBUTION.gsw.md",
                "assets/latinime/packs/language_packs.json",
            ):
                self.assertIn(name, archive.namelist())

    def test_release_environment_precedes_release_packaging_tasks(self):
        environment = os.environ | {
            "JAVA_HOME": "/usr/lib/jvm/java-17-openjdk-amd64",
            "PATH": "/usr/lib/jvm/java-17-openjdk-amd64/bin:" + os.environ["PATH"],
            "ANDROID_HOME": os.environ.get("ANDROID_HOME", "/home/pascal/Android/Sdk"),
        }
        result = subprocess.run(
            ["./gradlew", "--no-configuration-cache", "verifyReleasePackaging", "--dry-run"],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        tasks = [line.removesuffix(" SKIPPED") for line in result.stdout.splitlines() if line.startswith(":")]
        environment = tasks.index(":verifyReleaseEnvironment")
        self.assertLess(environment, tasks.index(":packageRelease"))
        self.assertLess(environment, tasks.index(":assembleRelease"))

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
