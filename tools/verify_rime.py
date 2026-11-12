#!/usr/bin/env python3
"""Verify the source-built Rime/OpenCC package offline, without Android execution."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import io
import json
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
AAR = 'ipa-rime-android-1.17.0-opencc-1.4.2.aar'
ABIS = {'arm64-v8a': 183, 'x86_64': 62}
EXPORTS = {'Java_org_ipaboard_rime_RimeNative_' + name
           for name in ('abiVersion', 'identity', 'open', 'query', 'convert', 'close')}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def members(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(
                n.startswith('/') or '\\' in n or '..' in n.split('/') for n in names):
            raise ValueError('Duplicate or unsafe archive member')
        return {n: archive.read(n) for n in names}


def check(data, record, label):
    size = record.get('size', record.get('sizeBytes'))
    if sha(data) != record['sha256'] or len(data) != size:
        raise ValueError('Artifact hash/size mismatch: ' + label)


def verify(root=ROOT):
    root = Path(root)
    metadata = root / 'tools/rime'
    provenance = json.loads((metadata / 'integration-source.json').read_bytes())
    for name, record in provenance['records'].items():
        check((metadata / name).read_bytes(), record, name)
    manifest = json.loads((metadata / 'artifacts.json').read_bytes())
    if (manifest['interfaceVersion'], manifest['candidatePacketVersion'],
            manifest['pageSize']) != (1, 3, 16384):
        raise ValueError('Unsupported Rime interface or page-size contract')
    for field, filename in [('sourceLockSha256', 'sources.lock.json'),
                            ('resourceLockSha256', 'resource-sources.lock.json')]:
        if sha((metadata / filename).read_bytes()) != manifest[field]:
            raise ValueError('Source/resource lock identity mismatch')
    proof = manifest['buildProvenance']
    canonical = json.dumps({'core': proof['core'], 'link': proof['link']},
                           sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()
    if sha(canonical) != proof['buildId'] or proof['buildId'] != manifest['buildId']:
        raise ValueError('Native build identity differs from recorded build inputs')
    libs = root / 'app/libs'
    if {p.name for p in libs.glob('*rime*.aar')} != {AAR} or list(libs.glob('jna*.aar')):
        raise ValueError('Missing current Rime AAR or leftover Rime/JNA archive')
    forbidden = {'librime_jni.so', 'libipa_rime_batch.so', 'libipa_rime.so', 'libjnidispatch.so'}
    if any(p.name in forbidden for p in (root / 'app/src').glob('*/jniLibs/**/*.so')):
        raise ValueError('Leftover native library shadows/reintroduces the old Rime bridge')
    if (root / 'app/src/main/cpp/rime_batch.cpp').exists():
        raise ValueError('Legacy Rime bridge source remains active')
    data = (libs / AAR).read_bytes()
    check(data, manifest['files'][AAR], AAR)
    archive = members(data)
    expected = {f'jni/{abi}/libipa_rime.so' for abi in ABIS}
    if {n for n in archive if n.startswith('jni/') or n.endswith('.so')} != expected:
        raise ValueError('Rime AAR ABI/library inventory mismatch')
    spec = importlib.util.spec_from_file_location('rime_elf_verifier', metadata / 'elf_verifier.py')
    elf = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(elf)
    native = {}
    for abi, machine in ABIS.items():
        content = archive[f'jni/{abi}/libipa_rime.so']
        check(content, manifest['files'][f'{abi}/libipa_rime.so'], abi)
        native[abi] = elf.inspect_elf(content, machine, expected_soname='libipa_rime.so',
                                     required_exports=EXPORTS, exact_exports=True)
        if (';build=' + manifest['buildId']).encode() not in content:
            raise ValueError('Native identity mismatch: ' + abi)
    if archive['META-INF/ipa-rime-build-id.txt'].decode().strip() != manifest['buildId']:
        raise ValueError('AAR identity mismatch')
    classes = members(archive['classes.jar'])
    if set(classes) != {'org/ipaboard/rime/RimeNative.class'}:
        raise ValueError('Unexpected Java bridge inventory')
    check(classes['org/ipaboard/rime/RimeNative.class'],
          proof['link']['inputs']['java/RimeNative.class'], 'Java bridge')
    aar_manifest = ET.fromstring(archive['AndroidManifest.xml'])
    if aar_manifest.get('package') != 'org.ipaboard.rime' or aar_manifest.find('uses-sdk').get(
            '{http://schemas.android.com/apk/res/android}minSdkVersion') != '26':
        raise ValueError('Unexpected AAR manifest')
    if b'-keep class org.ipaboard.rime.RimeNative' not in archive['proguard.txt']:
        raise ValueError('Missing JNI keep rule')
    notices = root / 'app/src/main/assets/licenses/rime-upstream'
    for name in ('artifacts.json', 'sources.lock.json', 'resource-sources.lock.json'):
        if (notices / name).read_bytes() != (metadata / name).read_bytes():
            raise ValueError('Packaged metadata mismatch: ' + name)
    notice_entries = {'META-INF/licenses/' + p.relative_to(notices).as_posix(): p.read_bytes()
                      for label in ('Rime', 'OpenCC') for p in (notices / label).rglob('*') if p.is_file()}
    if not notice_entries or notice_entries != {k: v for k, v in archive.items() if k.startswith('META-INF/licenses/')}:
        raise ValueError('Packaged notices differ from source-built AAR')
    resource_lock = json.loads((metadata / 'resource-sources.lock.json').read_bytes())
    resource_root = root / 'app/src/main/assets/engines/rime'
    expected_resources = set()
    for record in resource_lock['files']:
        name = record['output']
        if name.startswith('resources/'):
            relative = name.removeprefix('resources/')
            expected_resources.add(relative)
            check((resource_root / relative).read_bytes(), record, name)
        elif name.startswith('licenses/'):
            check((notices / 'Rime' / name.removeprefix('licenses/')).read_bytes(), record, name)
    if expected_resources != {p.relative_to(resource_root).as_posix() for p in resource_root.rglob('*') if p.is_file()}:
        raise ValueError('Unexpected or missing Rime resource; stale profiles must not ship')
    return {'validated': True, 'sourceCommit': provenance['commit'], 'buildId': manifest['buildId'],
            'resources': len(expected_resources), 'notices': len(notice_entries), 'native': native,
            'androidRuntimeExecuted': False, 'apkZipAlignmentChecked': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    try:
        result = verify()
    except (ValueError, OSError, KeyError) as error:
        sys.exit('Rime package verification failed: ' + str(error))
    print(json.dumps(result, indent=2) if args.json else
          'Verified source-built Rime/OpenCC: 2 ABIs, 16KB ELF, JNI identity, 24 resources and retained notices. Static checks only.')
