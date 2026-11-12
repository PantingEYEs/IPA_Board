# 旧 Trime／JNA 中文引擎接入归档

状态：历史记录，对应 IPA Board `51072bf` 及此前实现，不适用于当前 #49 源码构建接入。当前说明见 [Rime／OpenCC 官方源码接入](../rime-upstream.md)。

旧实现从 Trime v3.3.12 APK 提取 `librime_jni.so`，通过 JNA 调用 librime/OpenCC，并将 `libipa_rime_batch.so` 作为独立只读候选桥接。librime 运行库是 1.17.0，旧 JNA 函数表的声明依据 1.14.0；两者不能混为引擎版本。旧中文资源部分来自 APK，OpenCC 的 t2s 配置被改为文本词典。

本次迁移移除旧二进制、JNA、指针／函数表调用和应用仓库的外置桥接构建。已验证的候选前缀范围、字节偏移映射、单次输入与无学习合同继续保留，不将历史测试改写为新构建的结果。旧 Trime 组合二进制及接入声明的许可义务属于当时版本；当前资源保留的 LGPL/GPL 和 CC-BY-SA 材料不是旧运行库残留。

旧源码与许可可在 [固定基线树](https://github.com/PantingEYEs/IPA_Board/tree/51072bf3599ea680a50debed527efa02912f96e1) 查看。以下保留当时的桥接说明，路径、工具版本与命令均属于历史版本。

---

# Rime candidate batch bridge

This library extends the pinned Rime runtime already packaged in IPA Board. It does not
initialize another Rime instance, link another copy of its implementation, select or highlight
candidates, or read a hypothetical commit preview. `RimeEngine` submits the spelling once.

`ipa_rime_read_candidates` returns an owned, bounded C packet containing candidate text,
source order and the candidate's native UTF-8 byte range. It also records the end of the
already converted prefix and the number of input bytes already committed during spelling.
The returned text includes those existing prefix segments/output. Kotlin accepts a candidate
only when its span continues that prefix and maps to an exact boundary in the supplied
spelling. Full coverage remains a whole-composition candidate; shorter coverage carries
explicit raw-input consumption metadata for segmented selection. Displayed Zhuyin UTF-16 offsets are never compared to native encoded byte offsets.
`ipa_rime_free_candidates` releases the snapshot; no C++ object pointers leave this library.
Malformed packets, invalid sessions and ABI/load failures follow the existing engine error
fallback. An input mismatch without a valid committed prefix yields no conversions, retaining
the existing protection for ignored leading Zhuyin tones.

The reader uses actual `Service`, `Session`, `Context`, `Composition`, `Menu` and `Candidate`
declarations from librime revision `33e78140250125871856cdc5b42ddc6a5fcd3cd4`. It preserves
input, caret, highlight and pending commit output. Candidate enumeration can populate Rime's
lazy candidate cache. No internal object offsets or vtables are reconstructed manually.

Build dependencies are NDK `28.0.13004108` (matching the original libraries' ELF notes) and
CMake `3.22.1`, installed through Android SDK Manager. All required source headers are vendored;
normal builds are offline. The bridge is built with static libc++, as the pinned Trime binary
was, and requests 16KB ELF page alignment. The original `librime_jni.so` remains unchanged.

- `vendor/librime/manifest.json` pins the original library hashes independently of the engine
  asset lock, as well as the unmodified upstream headers and license.
- `vendor/boost/manifest.json` pins the compiler-used Boost 1.89.0 headers for both supported
  Android ABIs. The source archive matches Trime's own SHA-256:
  `67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74`.
  Source: https://github.com/boostorg/boost/releases/download/boost-1.89.0/boost-1.89.0-cmake.tar.xz
- `include/rime/build_config.h` is generated from the pinned `build_config.h.in` with logging
  disabled for this bridge's header compilation. It does not alter the existing Rime binary.
- Source archive: https://codeload.github.com/rime/librime/tar.gz/33e78140250125871856cdc5b42ddc6a5fcd3cd4
  SHA-256: `d3f48c2c58f718402229031d8d95fde9cac07ababa8fecf7d18b91946f27fee6`.

CMake and `tools/verify_engine_assets.py` verify these inputs. Replacing the runtime or headers
requires an explicit ABI review and a new result-equivalence/device run; updating only the
general engine asset lock does not approve a replacement for this bridge.

Tests and same-runtime replay/batch benchmarks run through:

```sh
python3 tools/regression.py run --suite rime-batch --serial DEVICE_SERIAL --no-build
```

The legacy replay algorithm exists only in instrumentation tests. A leading Pinyin quote now
uses the single real input's quote direction consistently, as approved by the maintainer;
the old replay path could toggle its direction once per enumerated candidate.
