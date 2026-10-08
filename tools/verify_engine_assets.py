#!/usr/bin/env python3
"""Verify vendored engine resources against the reviewed lockfile; no network access."""
import hashlib
import json
import pathlib
import struct
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'tools/engine-assets.lock.json').read_text())
errors = []
for item in manifest['files']:
    path = root / item['path']
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
        errors.append(item['path'])
if errors:
    sys.exit('Missing or modified resources:\n' + '\n'.join(errors))
print(f"Verified {len(manifest['files'])} engine resource hashes.")

def print_alignment(label, data):
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    alignment = [struct.unpack_from('<Q', data, offset + i * size + 48)[0]
                 for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
    print(f"{label}: minimum ELF load alignment {min(alignment)} bytes")

for path in sorted((root / 'app/src/main/jniLibs').glob('*/*.so')):
    print_alignment(path.relative_to(root), path.read_bytes())
for path in sorted((root / 'app/libs').glob('onnxruntime*.aar')):
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith('.so') and any(f'jni/{abi}/' in name for abi in ('arm64-v8a', 'x86_64')):
                print_alignment(f'{path.name}/{name}', archive.read(name))

semantic = root / 'app/src/main/assets/engines/semantic'
if semantic.is_dir():
    deployment = json.loads((semantic / 'manifest.json').read_text())
    digest = hashlib.sha256()
    total = 0
    for part in deployment['parts']:
        data = (semantic / part['name']).read_bytes()
        if len(data) != part['size'] or hashlib.sha256(data).hexdigest() != part['sha256']:
            sys.exit('Invalid semantic model part: ' + part['name'])
        digest.update(data)
        total += len(data)
    if total != deployment['modelSize'] or digest.hexdigest() != deployment['modelSha256']:
        sys.exit('Semantic model reassembly differs from the pinned upstream model')
    print(f'Verified semantic model reassembly: {total} bytes.')
