#!/usr/bin/env python3
"""Offline verification of the source-built Extensions AAR integrated in IPA Board.

This verifies the published bytes, native layout/exports and Java interface.
It does not execute native code, run E5, or verify final APK ZIP alignment.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
VENDOR_DIRECTORY = Path(__file__).resolve().parent / "onnx-extensions"
AAR_NAME = "onnxruntime-extensions-android-0.13.0-ipa-e5-16kb.aar"
ABIS = ("arm64-v8a", "x86_64")
LIBRARIES = ("libortextensions.so", "libonnxruntime_extensions4j_jni.so")


class VerificationError(ValueError):
    """The bundled Extensions package violates its reviewed integration contract."""


def _load_vendor():
    provenance = json.loads((VENDOR_DIRECTORY / "verifier-source.json").read_text(encoding="utf-8"))
    source = VENDOR_DIRECTORY / "verify_artifacts.py"
    if hashlib.sha256(source.read_bytes()).hexdigest() != provenance["sha256"]:
        raise VerificationError("Vendored Extensions verifier differs from its recorded source hash")
    spec = importlib.util.spec_from_file_location("ipa_onnx_artifact_verifier", source)
    if spec is None or spec.loader is None:
        raise VerificationError("Cannot load the vendored Extensions verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, provenance


def _records(manifest: dict) -> dict[str, dict]:
    if manifest.get("schema_version") != 1:
        raise VerificationError("Unsupported Extensions artifact manifest schema")
    records = manifest.get("artifacts")
    if not isinstance(records, list):
        raise VerificationError("Extensions artifact manifest lacks its artifact list")
    expected = {f"{abi}/{library}" for abi in ABIS for library in LIBRARIES} | {AAR_NAME}
    indexed = {}
    for record in records:
        path = record.get("path")
        if path not in expected or path in indexed:
            raise VerificationError("Unexpected or duplicate artifact manifest path: " + repr(path))
        kind = "android_archive" if path == AAR_NAME else "shared_library"
        if record.get("kind") != kind:
            raise VerificationError("Artifact manifest kind mismatch: " + path)
        if not re.fullmatch(r"[0-9a-f]{64}", str(record.get("sha256", ""))):
            raise VerificationError("Invalid artifact SHA-256: " + path)
        if type(record.get("size_bytes")) is not int or record["size_bytes"] <= 0:
            raise VerificationError("Invalid artifact byte size: " + path)
        indexed[path] = record
    if set(indexed) != expected:
        raise VerificationError("Incomplete Extensions artifact manifest")
    return indexed


def _check_bytes(data: bytes, record: dict, label: str) -> None:
    if len(data) != record["size_bytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
        raise VerificationError(label + ": bytes differ from the source artifact manifest")


def verify(root: Path | None = None) -> dict:
    """Validate the app AAR and return a static-only report; no temporary extraction.

    `root` is the IPA Board checkout containing app/libs and tools/onnx-extensions.
    Invalid, missing or leftover artifacts raise VerificationError.
    """
    root = Path(root) if root is not None else ROOT
    try:
        vendor, provenance = _load_vendor()
        libs = root / "app/libs"
        candidates = {path.name for path in libs.glob("onnxruntime-extensions*.aar")}
        if candidates != {AAR_NAME}:
            raise VerificationError("Expected only the source-built Extensions AAR; missing or old/extra AARs: "
                                    + repr(sorted(candidates)))
        # These libraries are supplied solely by the AAR. A leftover jniLibs copy
        # could compete with it during Android packaging.
        shadows = [path.relative_to(root).as_posix()
                   for path in (root / "app/src/main/jniLibs").glob("*/*.so")
                   if path.name in LIBRARIES]
        if shadows:
            raise VerificationError("Loose native libraries shadow the Extensions AAR: " + ", ".join(shadows))
        manifest_path = root / "tools/onnx-extensions/artifacts.json"
        manifest_data = manifest_path.read_bytes()
        records = _records(json.loads(manifest_data))
        metadata, data = vendor.file_metadata(libs / AAR_NAME, keep_bytes=True)
        assert data is not None
        _check_bytes(data, records[AAR_NAME], AAR_NAME)
        members = vendor.archive_members(data, "Extensions AAR")
        expected_members = {f"jni/{abi}/{library}" for abi in ABIS for library in LIBRARIES}
        actual_members = {name for name in members if name.endswith(".so")}
        if actual_members != expected_members:
            raise VerificationError("Extensions AAR native member set mismatch")
        native_bytes, inspected = {}, []
        for abi in ABIS:
            for library in LIBRARIES:
                relative = f"{abi}/{library}"
                content = members["jni/" + relative]
                _check_bytes(content, records[relative], relative)
                result = vendor.Elf64(content).inspect(abi, library)
                native_bytes[relative] = content
                inspected.append({"path": relative, "sha256": records[relative]["sha256"], **result})
        for result in inspected:
            for dependency in set(result["needed"]) - vendor.ALLOWED_NEEDED:
                if f'{result["abi"]}/{dependency}' not in native_bytes:
                    raise VerificationError("Unresolved Extensions native dependency: " + dependency)
        archive_result = vendor.inspect_aar(data, native_bytes)
        return {"validated": True, "runtime_validation_included": False,
                "apk_zip_alignment_included": False,
                "validation_scope": "source artifact hashes, native ABI/16KB layout/dependencies/exports and AAR Java interface",
                "aar": {"path": "app/libs/" + AAR_NAME, **metadata},
                "source_artifact_manifest_sha256": hashlib.sha256(manifest_data).hexdigest(),
                "verifier_source": provenance, "native_libraries": inspected,
                "java_interfaces": archive_result["java_interfaces"]}
    except VerificationError:
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise VerificationError("Extensions artifact verification failed: " + str(error)) from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--json", action="store_true", help="Print the complete static validation report")
    args = parser.parse_args(argv)
    try:
        report = verify(args.root)
    except VerificationError as error:
        print(str(error), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print("Verified source-built ONNX Runtime Extensions: 2 ABIs, 4 native libraries, "
              "16KB ELF and Java/JNI interface. Static checks only; runtime and APK ZIP alignment are separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
