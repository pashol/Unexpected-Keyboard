#!/usr/bin/env python3
"""Build a reproducible format-202 LatinIME test dictionary without Soong."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unicodedata
from datetime import datetime, timezone

from tools.prediction import import_google_books_ngrams


AOSP_COMMIT = "8081a1d8572f78488900438a6eaaec232b882bbf"
FORMAT_MAGIC = bytes.fromhex("9bc13afe")
FORMAT_VERSION = 202
SHA256_HEX_LENGTH = 64
GENERATED_SOURCE_NAMES = {"ngram_tsv", "word_frequency_tsv"}
PINNED_JAVAC_VERSION = "javac 17.0.19"
PINNED_JAVA_VERSION = "17.0.19"


def run(command, **kwargs):
    subprocess.run(command, check=True, **kwargs)


def verified_checkout(source):
    source = source.resolve()
    commit = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if commit != AOSP_COMMIT:
        raise ValueError("AOSP checkout must be at " + AOSP_COMMIT)
    if subprocess.run(
        ["git", "-C", str(source), "status", "--porcelain"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout:
        raise ValueError("AOSP checkout must be clean")
    return source


def source_files(source):
    def files(pattern):
        return sorted(source.glob(pattern))

    required = [
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/Dicttool.java",
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/Makedict.java",
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/DictionaryMaker.java",
        source / "tools/dicttool/src/com/android/inputmethod/latin/dicttool/CombinedInputOutput.java",
        source / "java/src/com/android/inputmethod/latin/BinaryDictionary.java",
        source / "java/src/com/android/inputmethod/latin/DicTraverseSession.java",
        source / "java/src/com/android/inputmethod/latin/Dictionary.java",
        source / "java/src/com/android/inputmethod/latin/NgramContext.java",
        source / "java/src/com/android/inputmethod/latin/SuggestedWords.java",
        source / "java/src/com/android/inputmethod/latin/settings/SettingsValuesForSuggestion.java",
        source / "java/src/com/android/inputmethod/latin/utils/BinaryDictionaryUtils.java",
        source / "java/src/com/android/inputmethod/latin/utils/CombinedFormatUtils.java",
        source / "java/src/com/android/inputmethod/latin/utils/JniUtils.java",
        source / "java/src/com/android/inputmethod/latin/define/DebugFlags.java",
        source / "java/src/com/android/inputmethod/latin/define/DecoderSpecificConstants.java",
        source / "tests/src/com/android/inputmethod/latin/utils/ByteArrayDictBuffer.java",
    ]
    required.extend(files("common/src/**/*.java"))
    required.extend(files("java/src/com/android/inputmethod/latin/makedict/**/*.java"))
    required.extend(files("tools/dicttool/compat/**/*.java"))
    required.extend(
        path for path in files("tests/src/com/android/inputmethod/latin/makedict/*.java")
        if path.name != "BinaryDictDecoderEncoderTests.java"
    )
    required = [
        path for path in required
        if path.name not in {"AndroidTestCase.java", "LargeTest.java"}
    ]
    if not all(path.is_file() for path in required):
        raise ValueError("AOSP checkout lacks a required dicttool source")
    return required


def write_compatibility_sources(directory):
    annotations = directory / "javax/annotation"
    commands = directory / "com/android/inputmethod/latin/dicttool"
    annotations.mkdir(parents=True)
    commands.mkdir(parents=True)
    (annotations / "Nonnull.java").write_text(
        "package javax.annotation; public @interface Nonnull {}\n", encoding="utf-8"
    )
    (annotations / "Nullable.java").write_text(
        "package javax.annotation; public @interface Nullable {}\n", encoding="utf-8"
    )
    (commands / "CommandList.java").write_text(
        "package com.android.inputmethod.latin.dicttool; "
        "public class CommandList { public static void populate() { "
        "Dicttool.addCommand(\"makedict\", Makedict.class); } }\n",
        encoding="utf-8",
    )
    return sorted(directory.rglob("*.java"))


def compiler_inputs(source, compatibility):
    return [
        ("aosp/" + str(path.relative_to(source)), path)
        for path in source_files(source)
    ] + [
        ("generated/" + str(path.relative_to(compatibility)), path)
        for path in sorted(compatibility.rglob("*.java"))
    ]


def jdk_identity():
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise ValueError("dicttool requires java and javac on PATH")
    javac = pathlib.Path(javac).resolve()
    java = pathlib.Path(java).resolve()
    if java != javac.with_name("java"):
        raise ValueError("java and javac must come from the same JDK")
    javac_version = subprocess.run(
        [str(javac), "-version"], check=True, capture_output=True, text=True
    )
    java_version = subprocess.run(
        [str(java), "-version"], check=True, capture_output=True, text=True
    )
    match = re.search(r'(?:openjdk|java) version "([^"]+)"', java_version.stdout + java_version.stderr)
    if not match:
        raise ValueError("java did not report a version")
    return {
        "java_path": str(java),
        "java_version": match.group(1),
        "javac_path": str(javac),
        "javac_version": (javac_version.stdout + javac_version.stderr).strip(),
    }


def verified_jdk_identity():
    identity = jdk_identity()
    if identity["javac_version"] != PINNED_JAVAC_VERSION:
        raise ValueError("dicttool requires pinned JDK " + PINNED_JAVAC_VERSION)
    if identity["java_version"] != PINNED_JAVA_VERSION:
        raise ValueError("dicttool requires pinned Java " + PINNED_JAVA_VERSION)
    if pathlib.Path(identity["java_path"]) != pathlib.Path(identity["javac_path"]).with_name("java"):
        raise ValueError("java and javac must come from the same JDK")
    return identity


def compiler_identity(inputs):
    digest = hashlib.sha256()
    for name, path in inputs:
        digest.update(name.encode("utf-8") + b"\0")
        digest.update(path.read_bytes())
    builder = pathlib.Path(__file__).resolve()
    jdk = verified_jdk_identity()
    return {
        "aosp_revision": AOSP_COMMIT,
        "builder": {"path": builder.name, "sha256": sha256(builder)},
        "input_sha256": digest.hexdigest(),
        "jdk": {
            "java_version": jdk["java_version"],
            "javac_version": jdk["javac_version"],
        },
    }


def build_dicttool(source, classes):
    with tempfile.TemporaryDirectory(prefix="latinime-dicttool-") as temporary_directory:
        compatibility = write_compatibility_sources(pathlib.Path(temporary_directory))
        identity = compiler_identity(compiler_inputs(source, pathlib.Path(temporary_directory)))
        jdk = verified_jdk_identity()
        run([
            jdk["javac_path"], "-encoding", "UTF-8", "-d", str(classes),
            *(str(path) for path in source_files(source)),
            *(str(path) for path in compatibility),
        ])
    return identity, jdk["java_path"]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize_word(locale, word):
    # Swiss German is intentionally only Unicode-normalized, never dialect-normalized.
    return unicodedata.normalize("NFC", word)


def tsv_rows(path, columns):
    rows = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        fields = line.split("\t")
        if len(fields) != columns:
            raise ValueError(str(path) + ":" + str(line_number) + " has wrong column count")
        try:
            frequency = int(fields[-1])
        except ValueError as error:
            raise ValueError(str(path) + ":" + str(line_number) + " has invalid frequency") from error
        if not 1 <= frequency <= 255:
            raise ValueError(str(path) + ":" + str(line_number) + " frequency must be 1..255")
        rows.append((fields[:-1], frequency))
    return rows


def combined_source(locale, word_frequency_tsv, ngram_tsv):
    word_frequency_tsv, ngram_tsv = resolve_tsv_inputs(word_frequency_tsv, ngram_tsv)
    words = {}
    for fields, frequency in tsv_rows(word_frequency_tsv, 2):
        word = normalize_word(locale, fields[0])
        words[word] = max(words.get(word, 0), frequency)
    bigrams = {}
    for fields, frequency in tsv_rows(ngram_tsv, 3):
        context, target = (normalize_word(locale, word) for word in fields)
        if context not in words or target not in words:
            raise ValueError("every n-gram word must appear in the word-frequency TSV")
        bigrams[(context, target)] = max(bigrams.get((context, target), 0), frequency)
    lines = ["dictionary=main,locale=" + locale]
    for word in sorted(words):
        lines.append("word=" + word + ",f=" + str(words[word]))
        for (context, target), frequency in sorted(bigrams.items()):
            if context == word:
                lines.append("bigram=" + target + ",f=" + str(frequency))
    return "\n".join(lines) + "\n"


def resolve_tsv_inputs(word_frequency_tsv, ngram_tsv):
    word_frequency_tsv = pathlib.Path(word_frequency_tsv)
    ngram_tsv = pathlib.Path(ngram_tsv)
    if import_google_books_ngrams.current_manifest_path(word_frequency_tsv, ngram_tsv).is_file():
        return import_google_books_ngrams.load_current_generation(word_frequency_tsv, ngram_tsv)
    return word_frequency_tsv, ngram_tsv


def source_hash(path):
    return {"path": path.name, "sha256": sha256(path)}


def acquisition_lock_sha256(lock):
    if not isinstance(lock, dict):
        raise ValueError("acquisition_lock must be an object")
    url = lock.get("url")
    version = lock.get("version")
    state = lock.get("state")
    shards = lock.get("shards")
    if not isinstance(url, str) or not url or not isinstance(version, str) or not version:
        raise ValueError("acquisition_lock requires URL and version")
    if not isinstance(shards, list) or not shards:
        raise ValueError("acquisition_lock requires nonempty shards")
    if state not in {"locked", "pending"}:
        raise ValueError("acquisition_lock requires locked or pending state")
    normalized_shards = []
    names = set()
    for shard in shards:
        if not isinstance(shard, dict) or not isinstance(shard.get("name"), str):
            raise ValueError("acquisition_lock shards require relative names")
        name = pathlib.PurePath(shard["name"])
        if name.is_absolute() or pathlib.PureWindowsPath(shard["name"]).is_absolute() or ".." in name.parts:
            raise ValueError("acquisition_lock shards require relative names")
        if str(name) in names:
            raise ValueError("acquisition_lock shard names must be unique")
        shard_hash = shard.get("sha256")
        if not isinstance(shard_hash, str) or len(shard_hash) != SHA256_HEX_LENGTH:
            raise ValueError("acquisition_lock shards require SHA-256 hashes")
        try:
            int(shard_hash, 16)
        except ValueError as error:
            raise ValueError("acquisition_lock shards require SHA-256 hashes") from error
        names.add(str(name))
        normalized_shards.append({"name": str(name), "sha256": shard_hash})
    canonical = {
        "shards": sorted(normalized_shards, key=lambda shard: shard["name"]),
        "state": state, "url": url, "version": version,
    }
    return hashlib.sha256(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def validate_provenance(
        provenance, provenance_directory=None, declared_inputs=None, external_source_sha256=None,
        external_source_url=None, external_source_version=None):
    if not isinstance(provenance, dict) or not provenance:
        raise ValueError("provenance must be a nonempty object")
    if GENERATED_SOURCE_NAMES.intersection(provenance):
        raise ValueError("provenance must not use reserved generated source names")
    external_corpora = []
    for name, source in provenance.items():
        if not isinstance(name, str) or not isinstance(source, dict):
            raise ValueError("provenance sources must be objects")
        license_name = source.get("license")
        source_sha256 = source.get("source_sha256")
        if not isinstance(license_name, str) or not license_name:
            raise ValueError("provenance sources must include a license")
        if not isinstance(source_sha256, str) or len(source_sha256) != SHA256_HEX_LENGTH:
            raise ValueError("provenance sources must include a SHA-256 source hash")
        try:
            int(source_sha256, 16)
        except ValueError as error:
            raise ValueError("provenance sources must include a SHA-256 source hash") from error
        if source.get("type") == "external_corpus":
            if not isinstance(source.get("url"), str) or not source["url"]:
                raise ValueError("external corpus provenance must include a URL")
            if not isinstance(source.get("version"), str) or not source["version"]:
                raise ValueError("external corpus provenance must include a version")
            if "source_path" in source:
                raise ValueError("external corpus provenance must not declare source_path")
            external_corpora.append(source)
            continue
        if "type" in source:
            raise ValueError("provenance source type must be external_corpus")
        if provenance_directory is not None:
            source_path = source.get("source_path")
            if not isinstance(source_path, str) or not source_path:
                raise ValueError("provenance sources must declare a source_path")
            stable_path = (provenance_directory / source_path).resolve()
            if isinstance(declared_inputs, dict):
                declared = {
                    pathlib.Path(stable).resolve(): pathlib.Path(resolved).resolve()
                    for stable, resolved in declared_inputs.items()
                }
            else:
                declared = {path.resolve(): path.resolve() for path in declared_inputs}
            path = declared.get(stable_path)
            if path is None:
                raise ValueError("provenance source_path must name a declared input file")
            if not path.is_file() or sha256(path) != source_sha256:
                raise ValueError("provenance source hash does not match its declared input file")
    if external_source_sha256 is not None:
        if len(external_corpora) != 1:
            raise ValueError("ready pack provenance must contain exactly one external corpus")
        if external_corpora[0]["source_sha256"] != external_source_sha256:
            raise ValueError("external corpus hash must match the acquisition lock")
        if external_corpora[0]["url"] != external_source_url:
            raise ValueError("external corpus source URL must match the acquisition lock")
        if external_corpora[0]["version"] != external_source_version:
            raise ValueError("external corpus source version must match the acquisition lock")
    return provenance


def validate_registry_entry(pack, fixture_only=False):
    if not isinstance(pack, dict) or not isinstance(pack.get("development_supported"), bool):
        raise ValueError("language pack registry entries must declare development_supported")
    required = ("dictionary", "locale", "manifest", "ngram_tsv", "output_sha256", "provenance", "word_frequency_tsv")
    if any(not isinstance(pack.get(field), str) or not pack[field] for field in required):
        raise ValueError("language pack registry entries are incomplete")
    for field in ("asset_license", "attribution"):
        if not isinstance(pack.get(field), str) or not pack[field]:
            raise ValueError("language pack registry entries must declare " + field)
    if fixture_only:
        if pack["asset_license"] != "CC0-1.0" or not pack["dictionary"].startswith("minimal_"):
            raise ValueError("fixture-only language pack registry entries must be CC0 minimal fixtures")
    else:
        if pack.get("locale") == "gsw":
            for field in ("attestation", "attestation_sha256"):
                if not isinstance(pack.get(field), str) or not pack[field]:
                    raise ValueError("ready language pack registry entries require " + field)
            if len(pack["attestation_sha256"]) != SHA256_HEX_LENGTH:
                raise ValueError("ready language pack attestation_sha256 must be a SHA-256 hash")
            try:
                int(pack["attestation_sha256"], 16)
            except ValueError as error:
                raise ValueError("ready language pack attestation_sha256 must be a SHA-256 hash") from error
        source_sha256 = pack.get("source_sha256")
        if not isinstance(source_sha256, str) or len(source_sha256) != SHA256_HEX_LENGTH:
            raise ValueError("ready language pack registry entries with acquisition metadata require source_sha256")
        try:
            int(source_sha256, 16)
        except ValueError as error:
            raise ValueError("ready language pack registry entries with acquisition metadata require source_sha256") from error
        lock = pack.get("acquisition_lock")
        if not isinstance(lock, dict) or lock.get("state") != "locked":
            raise ValueError("ready language pack acquisition_lock state must be locked")
        lock_hash = acquisition_lock_sha256(lock)
        if source_sha256 != lock_hash:
            raise ValueError("ready language pack source_sha256 does not match its acquisition_lock")
    return pack


def validate_ready_pack_assets(pack, manifest, attribution):
    lock = pack["acquisition_lock"]
    sources = manifest.get("sources")
    if not isinstance(sources, dict):
        raise ValueError("ready pack manifest must declare provenance sources")
    validate_provenance(
        {name: source for name, source in sources.items() if name not in GENERATED_SOURCE_NAMES},
        external_source_sha256=pack["source_sha256"],
        external_source_url=lock["url"],
        external_source_version=lock["version"],
    )
    if pack["locale"] == "gsw":
        required_notice = (
            "CC BY-NC-SA 4.0",
            "https://creativecommons.org/licenses/by-nc-sa/4.0/",
            "non-commercial",
            "ShareAlike",
        )
        if pack["asset_license"] != "CC BY-NC-SA 4.0" or any(
                term not in attribution for term in required_notice):
            raise ValueError("ready GSW packs require CC BY-NC-SA 4.0 non-commercial ShareAlike attribution")
    return pack


def validate_ready_pack_attestation(pack, dictionary, manifest_path, manifest, attestation_path):
    if sha256(attestation_path) != pack["attestation_sha256"]:
        raise ValueError("ready pack attestation hash does not match its registry")
    try:
        attestation = json.loads(attestation_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError("ready pack attestation must be JSON") from error
    required = {
        "acquisition_lock_sha256", "combined_source_sha256", "compiler", "final_assets",
        "format_version", "generated_inputs", "importer_reports", "locale", "source_provenance",
    }
    if not isinstance(attestation, dict) or set(attestation) != required or attestation["format_version"] != 1:
        raise ValueError("ready pack attestation has an invalid format")
    if attestation["locale"] != pack["locale"]:
        raise ValueError("ready pack attestation locale does not match its registry")
    if attestation["acquisition_lock_sha256"] != acquisition_lock_sha256(pack["acquisition_lock"]):
        raise ValueError("ready pack attestation acquisition lock does not match its registry")
    if attestation["combined_source_sha256"] != manifest.get("combined_source_sha256"):
        raise ValueError("ready pack attestation combined source does not match its manifest")
    if attestation["compiler"] != manifest.get("compiler"):
        raise ValueError("ready pack attestation compiler does not match its manifest")
    if attestation["source_provenance"] != manifest.get("sources"):
        raise ValueError("ready pack attestation source provenance does not match its manifest")
    final_assets = attestation["final_assets"]
    if not isinstance(final_assets, dict) or final_assets.get("dictionary_sha256") != sha256(dictionary) or final_assets.get("manifest_sha256") != sha256(manifest_path):
        raise ValueError("ready pack attestation final assets do not match checked-in assets")
    generated = attestation["generated_inputs"]
    sources = manifest.get("sources")
    if not isinstance(generated, dict) or not isinstance(sources, dict):
        raise ValueError("ready pack attestation requires generated input evidence")
    for name, manifest_name in (("words", "word_frequency_tsv"), ("ngrams", "ngram_tsv")):
        evidence = generated.get(name)
        source = sources.get(manifest_name)
        if not isinstance(evidence, dict) or not isinstance(source, dict) or evidence.get("sha256") != source.get("sha256"):
            raise ValueError("ready pack attestation generated input evidence does not match its manifest")
    reports = attestation["importer_reports"]
    if not isinstance(reports, dict) or not all(
            isinstance(reports.get(name), str) and len(reports[name]) == SHA256_HEX_LENGTH
            for name in ("generation_manifest_sha256", "generation_report_sha256")):
        raise ValueError("ready pack attestation requires importer report hashes")
    return attestation


def validate_source_pending_registry_entry(pack):
    required = ("acquisition_lock", "asset_license", "attribution", "dictionary", "locale", "manifest", "source_metadata", "source_revision", "source_sha256", "source_url", "source_version", "state")
    nullable = {"source_revision", "source_sha256", "source_version"}
    if not isinstance(pack, dict) or any(
        field not in pack or (field not in nullable and (not isinstance(pack[field], str) or not pack[field]))
        or (field in nullable and pack[field] is not None and (not isinstance(pack[field], str) or not pack[field]))
        for field in required if field != "acquisition_lock"
    ):
        raise ValueError("source-pending language pack registry entries must declare acquisition_lock, asset_license, attribution, dictionary, locale, manifest, source_metadata, source_revision, source_sha256, source_url, source_version, and state")
    if pack["state"] != "source_pending":
        raise ValueError("source-pending language pack registry entries must declare state source_pending")
    source_sha256 = pack["source_sha256"]
    lock = pack.get("acquisition_lock")
    if isinstance(lock, dict):
        if lock.get("url") != pack["source_url"] or lock.get("version") != pack["source_version"]:
            raise ValueError("source-pending acquisition_lock must match known source URL and version")
        if lock.get("state") != "pending" or not isinstance(lock.get("shards"), list):
            raise ValueError("source-pending acquisition_lock must declare state and shards")
    elif not isinstance(lock, str):
        raise ValueError("source-pending acquisition_lock must be a string or object")
    if source_sha256 is None:
        if isinstance(lock, dict):
            invalid_unlocked_lock = lock["state"] != "pending" or lock["shards"]
        else:
            invalid_unlocked_lock = lock != "pending"
        if invalid_unlocked_lock:
            raise ValueError("source-pending language pack acquisition_lock must be unlocked without source_sha256")
    else:
        if len(source_sha256) != SHA256_HEX_LENGTH:
            raise ValueError("source-pending language pack source_sha256 must be a SHA-256 hash")
        try:
            int(source_sha256, 16)
        except ValueError as error:
            raise ValueError("source-pending language pack source_sha256 must be a SHA-256 hash") from error
        if isinstance(lock, dict):
            if lock["state"] != "pending" or acquisition_lock_sha256(lock) != source_sha256:
                raise ValueError("source-pending language pack acquisition_lock must be locked with source_sha256")
        elif lock != "locked":
            raise ValueError("source-pending language pack acquisition_lock must be locked with source_sha256")
    artifact_fields = {"ngram_tsv", "output_sha256", "provenance", "word_frequency_tsv"}
    if artifact_fields.intersection(pack):
        raise ValueError("source-pending language pack registry entries must not declare artifacts")
    return pack


def registry_file_path(registry_directory, value, field):
    path = pathlib.PurePath(value)
    if path.is_absolute() or pathlib.PureWindowsPath(value).is_absolute():
        raise ValueError("language pack registry " + field + " path must be relative and remain under the registry directory")
    resolved = (registry_directory / path).resolve()
    try:
        resolved.relative_to(registry_directory)
    except ValueError as error:
        raise ValueError("language pack registry " + field + " path must remain under the registry directory") from error
    return resolved


def load_language_packs(registry_path, development_only=False):
    registry_path = registry_path.resolve()
    try:
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("language pack registry must be JSON") from error
    if not isinstance(registry, dict) or registry.get("format_version") != FORMAT_VERSION or not isinstance(registry.get("packs"), list):
        raise ValueError("language pack registry has an invalid format")
    fixture_only = registry.get("fixture_only", False)
    if not isinstance(fixture_only, bool):
        raise ValueError("language pack registry fixture_only must be a boolean")
    registry_directory = registry_path.parent
    validated = []
    locales = set()
    for pack in registry["packs"]:
        if not isinstance(pack, dict):
            raise ValueError("language pack registry entry must be an object")
        state = pack.get("state", "ready")
        if state == "source_pending":
            validate_source_pending_registry_entry(pack)
        elif state == "ready":
            validate_registry_entry(pack, fixture_only)
        else:
            raise ValueError("language pack registry entry has an invalid state")
        if pack["locale"] in locales:
            raise ValueError("language pack registry has a duplicate locale")
        locales.add(pack["locale"])
        validated.append((pack, state))
    packs = []
    for pack, state in validated:
        if state == "source_pending":
            registry_file_path(registry_directory, pack["dictionary"], "dictionary")
            registry_file_path(registry_directory, pack["manifest"], "manifest")
            registry_file_path(registry_directory, pack["source_metadata"], "source_metadata")
            attribution = registry_file_path(registry_directory, pack["attribution"], "attribution")
            if not attribution.is_file():
                raise ValueError("language pack registry attribution file must exist")
            continue
        dictionary = registry_file_path(registry_directory, pack["dictionary"], "dictionary")
        manifest_path = registry_file_path(registry_directory, pack["manifest"], "manifest")
        attribution = registry_file_path(registry_directory, pack["attribution"], "attribution")
        attestation = None if fixture_only or pack["locale"] != "gsw" else registry_file_path(
            registry_directory, pack["attestation"], "attestation"
        )
        for field in ("word_frequency_tsv", "ngram_tsv", "provenance"):
            registry_file_path(registry_directory, pack[field], field)
        if not attribution.is_file() or (attestation is not None and not attestation.is_file()):
            raise ValueError("language pack registry attribution file must exist")
        if not dictionary.is_file() or not manifest_path.is_file():
            raise ValueError("language pack registry entry files must exist")
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise ValueError("language pack manifest must be JSON") from error
        if manifest.get("locale") != pack.get("locale") or manifest.get("output_sha256") != pack.get("output_sha256"):
            raise ValueError("language pack registry entry does not match its manifest")
        if sha256(dictionary) != pack.get("output_sha256"):
            raise ValueError("language pack registry output hash does not match its dictionary")
        if not fixture_only:
            validate_ready_pack_assets(pack, manifest, attribution.read_text(encoding="utf-8"))
            if attestation is not None:
                validate_ready_pack_attestation(pack, dictionary, manifest_path, manifest, attestation)
        if not development_only or pack["development_supported"]:
            packs.append(pack)
    return packs


def manifest_data(locale, word_frequency_tsv, ngram_tsv, combined, output, provenance, epoch, compiler):
    return {
        "compiler": compiler,
        "combined_source_sha256": sha256(combined),
        "format_version": FORMAT_VERSION,
        "locale": locale,
        "output_sha256": sha256(output),
        "sources": {
            **provenance,
            "ngram_tsv": source_hash(ngram_tsv),
            "word_frequency_tsv": source_hash(word_frequency_tsv),
        },
        "timestamp": datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def validate_paths(input_path, output, manifest):
    paths = {
        "input": input_path.resolve(),
        "output": output.resolve(),
        "manifest": manifest.resolve(),
    }
    for first, second in (("input", "output"), ("input", "manifest"), ("output", "manifest")):
        try:
            same_file = paths[first].samefile(paths[second])
        except FileNotFoundError:
            same_file = False
        if paths[first] == paths[second] or same_file:
            raise ValueError(first + " and " + second + " paths must differ")


def build(source, input_path, output, manifest, metadata):
    with tempfile.TemporaryDirectory(prefix="latinime-classes-") as temporary_directory:
        classes = pathlib.Path(temporary_directory) / "classes"
        classes.mkdir()
        compiler, java = build_dicttool(source, classes)
        run([
            java, "-cp", str(classes), "com.android.inputmethod.latin.dicttool.Dicttool",
            "makedict", "-s", str(input_path), "-d", str(output),
        ])
    header = output.read_bytes()[:6]
    if header[:4] != FORMAT_MAGIC or int.from_bytes(header[4:6], "big") != FORMAT_VERSION:
        raise ValueError("dicttool did not produce a format-202 dictionary")
    manifest.write_text(json.dumps(metadata(compiler), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=pathlib.Path)
    parser.add_argument("--locale", required=True)
    parser.add_argument("--word-frequency-tsv", required=True, type=pathlib.Path)
    parser.add_argument("--ngram-tsv", required=True, type=pathlib.Path)
    parser.add_argument("--provenance", required=True, type=pathlib.Path)
    parser.add_argument("--registry", required=True, type=pathlib.Path)
    parser.add_argument("--combined-output", type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        word_frequency_tsv, ngram_tsv = resolve_tsv_inputs(
            args.word_frequency_tsv, args.ngram_tsv
        )
    except (OSError, ValueError) as error:
        parser.error("TSV inputs must resolve to a valid current generation: " + str(error))
    if not word_frequency_tsv.is_file() or not ngram_tsv.is_file() or not args.provenance.is_file():
        parser.error("TSV inputs and provenance must name existing files")
    try:
        registry = load_language_packs(args.registry)
        matches = [pack for pack in registry if pack["locale"] == args.locale]
        if len(matches) != 1:
            raise ValueError("language pack registry must declare the requested locale exactly once")
        pack = matches[0]
        registry_directory = args.registry.resolve().parent
        expected_inputs = {
            "word_frequency_tsv": registry_directory / pack["word_frequency_tsv"],
            "ngram_tsv": registry_directory / pack["ngram_tsv"],
            "provenance": registry_directory / pack["provenance"],
        }
        actual_inputs = {
            "word_frequency_tsv": args.word_frequency_tsv.resolve(),
            "ngram_tsv": args.ngram_tsv.resolve(),
            "provenance": args.provenance.resolve(),
        }
        if actual_inputs != {name: path.resolve() for name, path in expected_inputs.items()}:
            raise ValueError("builder inputs must match the language pack registry")
        lock = pack.get("acquisition_lock")
        provenance = validate_provenance(
            json.loads(args.provenance.read_text(encoding="utf-8")),
            args.provenance.parent,
            {
                args.word_frequency_tsv: word_frequency_tsv,
                args.ngram_tsv: ngram_tsv,
            },
            external_source_sha256=pack.get("source_sha256"),
            external_source_url=lock.get("url") if isinstance(lock, dict) else None,
            external_source_version=lock.get("version") if isinstance(lock, dict) else None,
        )
        epoch = int(os.environ["SOURCE_DATE_EPOCH"])
    except (json.JSONDecodeError, KeyError, ValueError) as error:
        parser.error("provenance must be JSON and SOURCE_DATE_EPOCH must be an integer: " + str(error))
    input_path = args.combined_output or args.output.with_suffix(".combined")
    metadata = lambda compiler_hash: manifest_data(
        args.locale, word_frequency_tsv, ngram_tsv, input_path, args.output, provenance, epoch, compiler_hash
    )
    try:
        validate_paths(input_path, args.output, args.manifest)
    except ValueError as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    input_path.parent.mkdir(parents=True, exist_ok=True)
    input_path.write_text(combined_source(args.locale, word_frequency_tsv, ngram_tsv), encoding="utf-8")
    build(verified_checkout(args.source), input_path, args.output, args.manifest, metadata)


if __name__ == "__main__":
    main()
