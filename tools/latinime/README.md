# Pinned LatinIME source-build integration

The metadata, bridge/build recipe, source/notice inventory and ELF verifier here
are unchanged copies from `PantingEYEs/IPA_Engine`, independent `english` branch,
commit `ad79fe388ced2d9944d81a159f72b5ef6a972fce`. `integration-source.json`
records their hashes. HeliBoard v4.1 is pinned to
`9f5bb635c2e8609dcd95dc7506c0c58fba82a52c`.

The production artifact is `app/libs/ipa-latinime-android-heliboard-4.1.aar`.
The original dictionary remains under `app/src/main/assets/engines/english/`;
it is neither rebuilt nor duplicated into this metadata directory. The wrapper
verifier reconstructs the upstream verifier's expected layout in a temporary
host directory from the actual shipped AAR and dictionary:

```sh
python3 tools/verify_latinime.py
python3 -m unittest discover -s tools -p test_verify_latinime.py
```

This checks both ABIs, exact JNI exports and bytecode, source/recipe provenance,
ELF LOAD/RELRO/dependencies, notices and the unchanged dictionary. It rejects old
registration Java classes and loose LatinIME native libraries. It does not run
Android or certify APK ZIP alignment.

To rebuild, use the original complete pinned workspace on Linux x86_64, not this
application metadata directory. NDK 29.0.14206865, CMake 3.31.6, Python 3.10+,
GNU Make and JDK 21 are required; Java bytecode targets release 11:

```sh
git clone --single-branch --branch english https://github.com/PantingEYEs/IPA_Engine.git LatinIME
cd LatinIME
git checkout --detach ad79fe388ced2d9944d81a159f72b5ef6a972fce
python3 scripts/bootstrap.py
python3 scripts/build.py --ndk /path/to/android-ndk-r29 \
  --cmake /path/to/cmake-3.31.6/bin/cmake --javac /path/to/jdk-21/bin/javac --jobs 8
python3 scripts/package.py
python3 scripts/verify_artifacts.py
python3 source-inventory/verify.py
python3 -m unittest discover -s tests
```

After fetching pinned inputs, `scripts/bootstrap.py --offline` verifies the cache.
The build selects 82 Apache-noticed core units and 139 available headers; the old
upstream JNI registration compilation units and mixed-license Java wrappers are
excluded, not relabeled. The project-authored narrow bridge retains its original
copyright pending the owner's license decision. Dictionary licensing remains a
separate unresolved review recorded in `dictionary-sources.lock.json` and the
source inventory. See [application integration](../../docs/latinime-upstream.md).
