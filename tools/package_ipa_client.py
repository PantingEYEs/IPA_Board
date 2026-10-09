#!/usr/bin/env python3
"""Prepare immutable IPA Board update artifacts from an upstream AAR and optional precompiled dictionary.

No lexical-source compilation. Publish the output directory on the engine development branch;
copy client.json to board-update.json with artifactCommit set to that published commit.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import struct
import subprocess
import tempfile
import zipfile


def package(aar, output, remote_path, engine_version, dictionary_version, d8, dictionary=None):
    output = pathlib.Path(output)
    if output.exists():
        raise ValueError('Use a new immutable artifact directory')
    if not remote_path or remote_path.startswith('/') or any(x in ('', '..') for x in remote_path.split('/')):
        raise ValueError('Invalid repository-relative artifact path')
    with tempfile.TemporaryDirectory() as scratch:
        scratch = pathlib.Path(scratch)
        with zipfile.ZipFile(aar) as archive:
            for entry, name in [('classes.jar', 'classes.jar'),
                                ('jni/arm64-v8a/libipa_graph_android.so', 'libipa_graph_android.so'),
                                ('assets/ipa/lexicon.ipad', 'lexicon.ipad')]:
                (scratch / name).write_bytes(archive.read(entry))
            (scratch / 'lexicon.manifest.json').write_bytes(archive.read('assets/ipa/lexicon.manifest.json'))
        subprocess.run([str(d8), '--min-api', '26', '--output', str(scratch), str(scratch / 'classes.jar')], check=True)
        if dictionary:
            shutil.copyfile(dictionary, scratch / 'lexicon.ipad')
        data = (scratch / 'lexicon.ipad').read_bytes()
        header = struct.unpack_from('<8s7I9Q', data)
        if header[0] != b'IPADWG01' or header[1] != 2 or header[-1] != len(data):
            raise ValueError('Expected complete IPADWG01 version 2 binary dictionary')
        metadata = dict(schemaVersion=1, engineVersion=engine_version, dictionaryVersion=dictionary_version,
                        abi='arm64-v8a', minSdk=26, entryPoint='com.ipaengine.graph.GraphEngine',
                        dictionaryFormat=2, dictionaryKeys=header[4])
        output.mkdir(parents=True)
        sums = []
        for prefix, name in [('dex', 'classes.dex'), ('native', 'libipa_graph_android.so'), ('dictionary', 'lexicon.ipad')]:
            content = (scratch / name).read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            metadata.update({prefix + 'Path': remote_path + '/' + name,
                             prefix + 'Bytes': len(content), prefix + 'Sha256': digest})
            (output / name).write_bytes(content)
            sums.append(digest + '  ' + name)
        # Preserve upstream source notices when the dictionary is unchanged. New dictionaries need their own notices.
        if not dictionary:
            shutil.copyfile(scratch / 'lexicon.manifest.json', output / 'lexicon.manifest.json')
        (output / 'client.json').write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + '\n')
        (output / 'SHA256SUMS').write_text('\n'.join(sums) + '\n')
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aar', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--remote-path', required=True)
    parser.add_argument('--engine-version', required=True)
    parser.add_argument('--dictionary-version', required=True)
    parser.add_argument('--d8', type=pathlib.Path, required=True)
    parser.add_argument('--dictionary', type=pathlib.Path, help='Developer-compiled compatible replacement binary')
    args = parser.parse_args()
    package(args.aar, args.output, args.remote_path, args.engine_version, args.dictionary_version, args.d8, args.dictionary)
