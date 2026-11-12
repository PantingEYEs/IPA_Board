# Pinned Mozc build records

`sources.lock.json` and `artifacts.json` are unchanged copies from
`PantingEYEs/IPA_Engine` commit `76a612924530188bce6fa1c770b1e2e096e27ed8`
on the independent `japanese` branch. The source revision is Google Mozc
`a4541df29db443b49f81cf54665873b68fde9efa`.

The complete build workspace is maintained in that repository, not duplicated
in IPA Board. Rebuild on Linux x86_64 using the documented prerequisites:

```sh
git clone --single-branch --branch japanese https://github.com/PantingEYEs/IPA_Engine.git Mozc
git -C Mozc checkout --detach 76a612924530188bce6fa1c770b1e2e096e27ed8
cd Mozc
bash scripts/build.sh
python3 scripts/verify_artifacts.py --artifacts artifacts --output artifacts/manifest.json
python3 -m unittest discover -s tests
```

Review the source lock, native compatibility patch, matching runtime/data hashes,
notices and application protocol when adopting a different build. Copying an
arbitrary replacement library does not update the application compatibility
contract. The `runtime_validation_included: false` field in `artifacts.json`
describes the static verifier's scope, not the separate runtime record.

See [the app integration guide](../../docs/mozc-upstream.md) for the data
generation, prediction request and validation boundaries. The complete notices
and build-input evidence are packaged under `app/src/main/assets/licenses/mozc/`.
