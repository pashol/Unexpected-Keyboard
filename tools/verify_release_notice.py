import argparse
import pathlib
import sys
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
NOTICE_ENTRY = "assets/latinime/NOTICE"


def release_apk(path):
    if path is not None:
        return path

    apks = sorted((ROOT / "build" / "outputs" / "apk" / "release").glob("*.apk"))
    if len(apks) != 1:
        raise ValueError("expected exactly one assembled release APK, found " + str(len(apks)))
    return apks[0]


def verify(apk):
    expected = (ROOT / "vendor" / "latinime" / "NOTICE").read_bytes()
    with zipfile.ZipFile(apk) as archive:
        try:
            actual = archive.read(NOTICE_ENTRY)
        except KeyError as error:
            raise ValueError(f"{apk} does not contain {NOTICE_ENTRY}") from error
    if actual != expected:
        raise ValueError(f"{NOTICE_ENTRY} in {apk} does not match vendor/latinime/NOTICE")


def main():
    parser = argparse.ArgumentParser(description="Verify the LatinIME NOTICE in a release APK.")
    parser.add_argument("apk", nargs="?", type=pathlib.Path)
    args = parser.parse_args()
    try:
        verify(release_apk(args.apk))
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
