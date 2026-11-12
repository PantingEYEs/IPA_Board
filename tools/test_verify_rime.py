"""Offline package migration contracts; corruptions use synthetic temporary copies."""
import importlib.util
from pathlib import Path
import shutil
import struct
import tempfile
import unittest

import verify_rime as verifier


class RimePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ipa-rime-package-')
        self.root = Path(self.temp.name)
        for directory in ('tools/rime', 'app/src/main/assets/engines/rime',
                          'app/src/main/assets/licenses/rime-upstream'):
            shutil.copytree(verifier.ROOT / directory, self.root / directory)
        (self.root / 'app/libs').mkdir(parents=True)
        shutil.copyfile(verifier.ROOT / 'app/libs' / verifier.AAR,
                        self.root / 'app/libs' / verifier.AAR)

    def tearDown(self):
        self.temp.cleanup()

    def test_complete_offline_package_has_both_abis_without_claiming_runtime(self):
        report = verifier.verify(self.root)
        self.assertEqual(set(verifier.ABIS), set(report['native']))
        self.assertEqual(24, report['resources'])
        self.assertFalse(report['androidRuntimeExecuted'])
        self.assertFalse(report['apkZipAlignmentChecked'])

    def test_old_jna_and_loose_native_libraries_cannot_shadow_new_package(self):
        legacy = self.root / 'app/libs/jna-5.17.0.aar'
        legacy.write_bytes(b'old')
        with self.assertRaisesRegex(ValueError, 'leftover'):
            verifier.verify(self.root)
        legacy.unlink()
        legacy = self.root / 'app/src/main/jniLibs/arm64-v8a/librime_jni.so'
        legacy.parent.mkdir(parents=True)
        legacy.write_bytes(b'old')
        with self.assertRaisesRegex(ValueError, 'Leftover'):
            verifier.verify(self.root)
        legacy.unlink()
        debug_shadow = self.root / 'app/src/debug/jniLibs/x86_64/libipa_rime_batch.so'
        debug_shadow.parent.mkdir(parents=True)
        debug_shadow.write_bytes(b'old debug bridge')
        with self.assertRaisesRegex(ValueError, 'Leftover'):
            verifier.verify(self.root)

    def test_stale_unused_schema_and_changed_dictionary_are_rejected(self):
        resources = self.root / 'app/src/main/assets/engines/rime'
        stale = resources / 'old.schema.yaml'
        stale.write_text('old schema')
        with self.assertRaisesRegex(ValueError, 'Unexpected or missing'):
            verifier.verify(self.root)
        stale.unlink()
        (resources / 'luna_pinyin.dict.yaml').write_bytes(b'changed dictionary')
        with self.assertRaisesRegex(ValueError, 'hash/size mismatch'):
            verifier.verify(self.root)

    def test_packaged_build_metadata_and_notices_must_match_source_artifact(self):
        notices = self.root / 'app/src/main/assets/licenses/rime-upstream'
        metadata = notices / 'artifacts.json'
        original = metadata.read_bytes()
        metadata.write_bytes(original + b' ')
        with self.assertRaisesRegex(ValueError, 'Packaged metadata mismatch'):
            verifier.verify(self.root)
        metadata.write_bytes(original)
        (notices / 'Rime/native/librime-LICENSE.txt').write_bytes(b'missing notice')
        with self.assertRaisesRegex(ValueError, 'Packaged notices differ'):
            verifier.verify(self.root)

    def test_verifier_and_build_manifest_cannot_silently_drift(self):
        path = self.root / 'tools/rime/elf_verifier.py'
        path.write_bytes(path.read_bytes() + b'\n# changed')
        with self.assertRaisesRegex(ValueError, 'hash/size mismatch'):
            verifier.verify(self.root)

    def test_elf_validator_checks_load_alignment_and_exports_beyond_hashes(self):
        spec = importlib.util.spec_from_file_location('test_rime_elf', self.root / 'tools/rime/elf_verifier.py')
        elf = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(elf)
        entries = verifier.members((self.root / 'app/libs' / verifier.AAR).read_bytes())
        original = entries['jni/x86_64/libipa_rime.so']
        def inspect(data):
            return elf.inspect_elf(data, 62, 'libipa_rime.so', verifier.EXPORTS, True)
        data = bytearray(original)
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            if struct.unpack_from('<I', data, offset + i * size)[0] == 1:
                struct.pack_into('<Q', data, offset + i * size + 48, 4096)
                break
        with self.assertRaisesRegex(ValueError, 'Invalid 16KB LOAD'):
            inspect(data)
        symbol = b'Java_org_ipaboard_rime_RimeNative_query\0'
        with self.assertRaisesRegex(ValueError, 'Missing C API exports'):
            inspect(original.replace(symbol, b'X' + symbol[1:]))


if __name__ == '__main__':
    unittest.main()
