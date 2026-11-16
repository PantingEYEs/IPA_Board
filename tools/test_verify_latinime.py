"""Pinned LatinIME packaging regressions; all mutations use private temporary copies."""
from pathlib import Path
import shutil
import tempfile
import unittest

import verify_latinime as verifier


class LatinImePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ipa-latinime-test-')
        self.root = Path(self.temp.name)
        for directory in ('tools/latinime', 'app/src/main/assets/licenses/latinime-upstream',
                          'app/src/main/assets/engines/english'):
            shutil.copytree(verifier.ROOT / directory, self.root / directory,
                            ignore=shutil.ignore_patterns('__pycache__'))
        (self.root / 'app/libs').mkdir(parents=True)
        shutil.copyfile(verifier.ROOT / 'app/libs' / verifier.AAR,
                        self.root / 'app/libs' / verifier.AAR)

    def tearDown(self):
        self.temp.cleanup()

    def test_complete_package_checks_both_abis_without_claiming_runtime(self):
        report = verifier.verify(self.root)
        self.assertEqual(set(verifier.ABIS), set(report['native']))
        self.assertFalse(report['androidRuntimeExecuted'])
        self.assertFalse(report['apkZipAlignmentChecked'])

    def test_changed_dictionary_is_rejected_without_recompilation_or_substitution(self):
        path = self.root / 'app/src/main/assets/engines/english/main_en-US.dict'
        path.write_bytes(path.read_bytes()[:-1] + b'\x01')
        with self.assertRaises(ValueError): verifier.verify(self.root)

    def test_old_or_duplicate_native_cannot_shadow_the_source_built_aar(self):
        path = self.root / 'app/src/debug/jniLibs/x86_64/libjni_latinime.so'
        path.parent.mkdir(parents=True); path.write_bytes(b'legacy')
        with self.assertRaisesRegex(ValueError, 'Legacy or duplicate'): verifier.verify(self.root)

    def test_old_java_registration_is_rejected(self):
        path = self.root / 'app/src/main/java/com/android/inputmethod/latin/BinaryDictionary.java'
        path.parent.mkdir(parents=True); path.write_text('old registration')
        with self.assertRaisesRegex(ValueError, 'Old LatinIME JNI'): verifier.verify(self.root)

    def test_missing_or_changed_source_aar_is_rejected(self):
        path = self.root / 'app/libs' / verifier.AAR
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'inventory'): verifier.verify(self.root)

    def test_changed_build_recipe_and_packaged_notices_are_rejected(self):
        path = self.root / 'tools/latinime/bridge/latinime_jni.cpp'
        original = path.read_bytes(); path.write_bytes(original + b'\n')
        with self.assertRaisesRegex(ValueError, 'hash/size'): verifier.verify(self.root)
        path.write_bytes(original)
        path = self.root / 'app/src/main/assets/licenses/latinime-upstream/native/HeliBoard-Apache-2.0.txt'
        path.write_bytes(b'changed notice')
        with self.assertRaisesRegex(ValueError, 'notice mismatch'): verifier.verify(self.root)
