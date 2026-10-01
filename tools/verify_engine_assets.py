#!/usr/bin/env python3
"""Verify vendored engine resources against the reviewed lockfile; no network access."""
import hashlib
import json
import pathlib
import struct
import sys

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
for path in sorted((root / 'app/src/main/jniLibs').glob('*/*.so')):
    data = path.read_bytes()
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    alignment = [struct.unpack_from('<Q', data, offset + i * size + 48)[0]
                 for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
    print(f"{path.relative_to(root)}: minimum ELF load alignment {min(alignment)} bytes")
