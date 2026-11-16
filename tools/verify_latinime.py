#!/usr/bin/env python3
"""Offline LatinIME integration checks; no network, native execution or build cache required."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
AAR = 'ipa-latinime-android-heliboard-4.1.aar'
ABIS = ('arm64-v8a', 'x86_64')


def check(data, row, label):
    if len(data) != row['sizeBytes'] or hashlib.sha256(data).hexdigest() != row['sha256']:
        raise ValueError('LatinIME hash/size mismatch: ' + label)


def entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(
                n.startswith('/') or '\\' in n or '..' in n.split('/') for n in names):
            raise ValueError('Unsafe or duplicate LatinIME AAR entry')
        return {n: archive.read(n) for n in names}


def verify(root=ROOT):
    root = Path(root)
    metadata = root / 'tools/latinime'
    origin = json.loads((metadata / 'integration-source.json').read_bytes())
    for name, row in origin['records'].items():
        if name.startswith('/') or '\\' in name or '..' in Path(name).parts:
            raise ValueError('Unsafe LatinIME metadata path')
        check((metadata / name).read_bytes(), row, name)
    libs = root / 'app/libs'
    if {p.name for p in libs.glob('*latinime*.aar')} != {AAR}:
        raise ValueError('Unexpected LatinIME AAR inventory')
    for path in (root / 'app/src').glob('*/jniLibs/**/*.so'):
        if path.name in {'libjni_latinime.so', 'libipa_latinime.so'}:
            raise ValueError('Legacy or duplicate LatinIME library: ' + path.name)
    for name in ('BinaryDictionary', 'DicTraverseSession', 'BinaryDictionaryUtils',
                 'WordInputEventForPersonalization', 'ProximityInfo'):
        if list((root / 'app/src/main/java/com/android/inputmethod').rglob(name + '.java')):
            raise ValueError('Old LatinIME JNI declaration remains: ' + name)
    data = (libs / AAR).read_bytes()
    archive = entries(data)
    manifest = json.loads((metadata / 'manifest.json').read_bytes())
    check(data, manifest['files']['artifacts/' + AAR], AAR)
    notices = root / 'app/src/main/assets/licenses/latinime-upstream'
    for name, source in [('sources.lock.json', 'sources.lock.json'),
                         ('dictionary-sources.lock.json', 'dictionary-sources.lock.json'),
                         ('native-files.json', 'source-inventory/native-files.json'),
                         ('bridge-api-review.json', 'source-inventory/bridge-api-review.json'),
                         ('artifacts.json', 'manifest.json')]:
        if (notices / name).read_bytes() != (metadata / source).read_bytes():
            raise ValueError('Packaged LatinIME metadata mismatch: ' + name)
    for path in (metadata / 'licenses').rglob('*'):
        if path.is_file() and (notices / path.relative_to(metadata / 'licenses')).read_bytes() != path.read_bytes():
            raise ValueError('Packaged LatinIME notice mismatch: ' + path.name)
    # Assemble the original verifier's expected layout from the actual shipped AAR/dictionary.
    # This exercises the unchanged source-build verifier, rather than trusting outer checksums.
    with tempfile.TemporaryDirectory(prefix='ipa-latinime-verify-') as directory:
        scratch = Path(directory)
        shutil.copytree(metadata, scratch, dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__'))
        artifacts = scratch / 'artifacts'
        artifacts.mkdir()
        for name in ('manifest.json', 'build-provenance.json'):
            shutil.copyfile(metadata / name, artifacts / name)
        (artifacts / AAR).write_bytes(data)
        (artifacts / 'classes.jar').write_bytes(archive['classes.jar'])
        for name, content in entries(archive['classes.jar']).items():
            target = artifacts / 'java' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        for abi in ABIS:
            target = artifacts / abi / 'libipa_latinime.so'
            target.parent.mkdir()
            target.write_bytes(archive[f'jni/{abi}/libipa_latinime.so'])
        (scratch / 'dictionary').mkdir()
        shutil.copyfile(root / 'app/src/main/assets/engines/english/main_en-US.dict',
                        scratch / 'dictionary/main_en-US.dict')
        names = ('build', 'package', 'elf')
        saved = {n: sys.modules.pop(n) for n in names if n in sys.modules}
        sys.path.insert(0, str(scratch / 'scripts'))
        try:
            spec = importlib.util.spec_from_file_location('latinime_source_verifier',
                                                        scratch / 'scripts/verify_artifacts.py')
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            result = module.inspect_package(scratch)
        finally:
            sys.path.pop(0)
            for name in names:
                sys.modules.pop(name, None)
            sys.modules.update(saved)
    return {**result, 'androidRuntimeExecuted': False, 'apkZipAlignmentChecked': False}


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
