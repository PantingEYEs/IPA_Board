"""Offline integration-contract tests using temporary copies of the reviewed AAR.

Malformed native cases deliberately update fixture hashes, so their failures
must come from real ELF/export validation rather than an outer checksum check.
No native library is loaded and no device or application data is touched.
"""
from contextlib import redirect_stderr, redirect_stdout
import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import verify_onnx_extensions as verify


def archive_bytes(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in sorted(entries.items()):
            archive.writestr(name, content)
    return output.getvalue()


def archive_entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


class ExtensionsIntegrationVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original_aar = (verify.ROOT / "app/libs" / verify.AAR_NAME).read_bytes()
        cls.original_manifest = json.loads((verify.ROOT / "tools/onnx-extensions/artifacts.json").read_bytes())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ipa-ortx-contract-")
        self.root = Path(self.temporary.name)
        self.aar = self.root / "app/libs" / verify.AAR_NAME
        self.manifest_file = self.root / "tools/onnx-extensions/artifacts.json"
        self.aar.parent.mkdir(parents=True)
        self.manifest_file.parent.mkdir(parents=True)
        self.aar.write_bytes(self.original_aar)
        self.manifest = copy.deepcopy(self.original_manifest)
        self.save_manifest()

    def tearDown(self):
        self.temporary.cleanup()

    def save_manifest(self):
        self.manifest_file.write_text(json.dumps(self.manifest), encoding="utf-8")

    def record_bytes(self, path, data):
        entry = next(item for item in self.manifest["artifacts"] if item["path"] == path)
        entry.update(size_bytes=len(data), sha256=hashlib.sha256(data).hexdigest())

    def change_archive(self, entries):
        data = archive_bytes(entries)
        self.aar.write_bytes(data)
        self.record_bytes(verify.AAR_NAME, data)
        self.save_manifest()

    def change_native(self, relative, change, update_native_hash=True):
        entries = archive_entries(self.aar.read_bytes())
        data = change(entries["jni/" + relative])
        entries["jni/" + relative] = data
        if update_native_hash:
            self.record_bytes(relative, data)
        self.change_archive(entries)

    def assert_invalid(self, message):
        with self.assertRaisesRegex(verify.VerificationError, message):
            verify.verify(self.root)

    def test_reviewed_aar_passes_actual_static_checks_for_both_abis(self):
        report = verify.verify(self.root)
        self.assertTrue(report["validated"])
        self.assertFalse(report["runtime_validation_included"])
        self.assertFalse(report["apk_zip_alignment_included"])
        self.assertEqual(len(report["native_libraries"]), 4)
        self.assertEqual({item["abi"] for item in report["native_libraries"]}, set(verify.ABIS))
        self.assertTrue(all(item["minimum_load_alignment"] >= 16384 for item in report["native_libraries"]))
        self.assertEqual(len(report["java_interfaces"]), 2)

    def test_missing_and_old_extra_aars_are_rejected(self):
        self.aar.unlink()
        self.assert_invalid("Expected only the source-built")
        self.aar.write_bytes(self.original_aar)
        (self.aar.parent / "onnxruntime-extensions-android-0.13.0.aar").write_bytes(b"old AAR")
        self.assert_invalid("old/extra AARs")

    def test_loose_native_copy_cannot_shadow_the_aar(self):
        path = self.root / "app/src/main/jniLibs/arm64-v8a/libortextensions.so"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"obsolete library")
        self.assert_invalid("Loose native libraries shadow")

    def test_aar_digest_and_individual_library_digest_are_both_checked(self):
        self.aar.write_bytes(self.original_aar + b"unexpected trailing bytes")
        self.assert_invalid("bytes differ from the source artifact manifest")
        self.aar.write_bytes(self.original_aar)
        self.change_native("arm64-v8a/libortextensions.so", lambda value: value + b"change", update_native_hash=False)
        self.assert_invalid("arm64-v8a/libortextensions.so: bytes differ")

    def test_missing_native_library_and_extra_abi_rejected_even_with_matching_aar_hash(self):
        entries = archive_entries(self.original_aar)
        entries.pop("jni/x86_64/libortextensions.so")
        self.change_archive(entries)
        self.assert_invalid("native member set mismatch")
        entries = archive_entries(self.original_aar)
        entries["jni/armeabi-v7a/libortextensions.so"] = b"unexpected ABI"
        self.change_archive(entries)
        self.assert_invalid("native member set mismatch")

    def test_wrong_machine_rejected_even_when_both_hash_layers_match(self):
        def change(data):
            value = bytearray(data)
            struct.pack_into("<H", value, 18, 62)  # x86_64 mislabeled as arm64-v8a.
            return bytes(value)
        self.change_native("arm64-v8a/libortextensions.so", change)
        self.assert_invalid("wrong ELF machine")

    @staticmethod
    def change_program_header(data, kind, field_offset, value):
        result = bytearray(data)
        offset = struct.unpack_from("<Q", result, 32)[0]
        entry_size, count = struct.unpack_from("<HH", result, 54)
        for index in range(count):
            position = offset + index * entry_size
            if struct.unpack_from("<I", result, position)[0] == kind:
                struct.pack_into("<Q", result, position + field_offset, value)
                return bytes(result)
        raise AssertionError("Required fixture program header is missing")

    def test_4kb_load_alignment_rejected_despite_matching_hashes(self):
        self.change_native("x86_64/libonnxruntime_extensions4j_jni.so",
                           lambda data: self.change_program_header(data, 1, 48, 4096))
        self.assert_invalid("alignment 4096")

    def test_bad_relro_boundary_rejected_despite_matching_hashes(self):
        def change(data):
            offset = struct.unpack_from("<Q", data, 32)[0]
            entry_size, count = struct.unpack_from("<HH", data, 54)
            for index in range(count):
                position = offset + index * entry_size
                if struct.unpack_from("<I", data, position)[0] == 0x6474E552:
                    memory_size = struct.unpack_from("<Q", data, position + 40)[0]
                    return self.change_program_header(data, 0x6474E552, 40, memory_size + 4096)
            raise AssertionError("Fixture has no RELRO")
        self.change_native("arm64-v8a/libortextensions.so", change)
        self.assert_invalid("RELRO end is not 16KB aligned")

    def test_required_jni_and_register_symbols_are_actually_checked(self):
        for symbol in (b"Java_ai_onnxruntime_extensions_OrtxLibrary_getNativeExtensionOperatorRegister",
                       b"RegisterCustomOps"):
            with self.subTest(symbol=symbol):
                self.aar.write_bytes(self.original_aar)
                self.manifest = copy.deepcopy(self.original_manifest)
                self.change_native("arm64-v8a/libonnxruntime_extensions4j_jni.so",
                                   lambda data: data.replace(symbol + b"\0", b"X" + symbol[1:] + b"\0"))
                self.assert_invalid("missing defined, visible dynamic function exports")

    def test_unbundled_dependency_is_rejected_after_digest_update(self):
        self.change_native("x86_64/libortextensions.so",
                           lambda data: data.replace(b"liblog.so\0", b"badbad.so\0"))
        self.assert_invalid("unpackaged/non-system DT_NEEDED")

    def test_java_native_signature_checked_after_digest_update(self):
        entries = archive_entries(self.original_aar)
        classes = archive_entries(entries["classes.jar"])
        path = "ai/onnxruntime/extensions/OrtxLibrary.class"
        self.assertIn(b"()J", classes[path])
        classes[path] = classes[path].replace(b"()J", b"()I")
        entries["classes.jar"] = archive_bytes(classes)
        self.change_archive(entries)
        self.assert_invalid("Java interface missing or wrong modifiers")

    def test_manifest_must_include_exactly_one_record_for_each_artifact(self):
        self.manifest["artifacts"].pop()
        self.save_manifest()
        self.assert_invalid("Incomplete Extensions artifact manifest")
        self.manifest = copy.deepcopy(self.original_manifest)
        self.manifest["artifacts"].append(copy.deepcopy(self.manifest["artifacts"][0]))
        self.save_manifest()
        self.assert_invalid("duplicate artifact manifest path")

    def test_cli_failure_reports_error_without_touching_input_files(self):
        self.aar.write_bytes(b"bad artifact")
        with redirect_stderr(io.StringIO()) as error, redirect_stdout(io.StringIO()) as output:
            result = verify.main(["--root", str(self.root), "--json"])
        self.assertEqual(result, 1)
        self.assertIn("bytes differ", error.getvalue())
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(self.aar.read_bytes(), b"bad artifact")


if __name__ == "__main__":
    unittest.main()
