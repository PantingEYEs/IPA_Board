# 旧 Mozc APK 接入来源归档

本文件只记录 Issue #50 迁移前的历史来源。当前官方源码构建接入见 [Mozc 接入说明](../mozc-upstream.md)。旧 APK、运行库、词典与部署目录不作为当前新引擎的回退来源。

归档基线为 IPA Board 提交 `07a4fdb0ddf07d50f55a284ebf5b36aea408723b`。原资源来自 [Libre Japanese Input v0.1.2](https://github.com/elizagamedev/android-libre-japanese-input/tree/v0.1.2)，其 Mozc revision 为 `ddd9730b068387631e3b4d212314ef0ed93befe0`。当时从发布 APK 提取两个 ABI 的 `libmozc.so` 和 `mozc.data`，JNI 加载类移除了应用前端依赖。保留的 Google 原始源码版权声明不能因为更换运行库而删除。

原 APK 地址：<https://github.com/elizagamedev/android-libre-japanese-input/releases/download/v0.1.2/app-release.apk>。

| 旧文件 | 字节数（有记录时） | SHA-256 |
| --- | ---: | --- |
| `app-release.apk` | — | `6b946fc78d333055027eebc92c9b28f3e4c55c539306c979158e7043ac5aa55b` |
| `arm64-v8a/libmozc.so` | 14,739,336 | `07dfa8131d8f0081baa771c771eea7198886860deab3ecff4ccd151fdf2eec2f` |
| `x86_64/libmozc.so` | 13,677,848 | `225729497d0f32fbd567517c8a88a51e8f028e1febd0df6788fbad1c688785ad` |
| `mozc.data` | 20,489,885 | `a905f88e772bafd4b070cebfe66fbb061fab04496f22166fd37875aa34713ccd` |
| `mozc-credits.html` | 41,175 | `25d6ecc9ee462302ef810cccfbd59218e582f1cde5351787d31b0cd20129a995` |
| `mozc.txt` | 5,734 | `44cdd923b91ea9199293abecc2762c70c87dbf1e581c027a94c416368d1a648c` |

旧库的 ELF LOAD 对齐为 4096 字节。原适配器将词典部署至 `noBackupFilesDir/mozc-v0.1.2`，文件存在时不重新复制；词典续写仅设置 `Request.zero_query_suggestion`。这些是旧版本行为，不是新源码构建的接入要求。

旧版本的完整文件仍可从上述基线提交及其 Git 历史取得，不在当前 assets 中再次打包。原许可证文本恰与本次固定官方源码的 Mozc 根许可证哈希相同，当前版本仍在新的 notices 目录完整保留；旧跨平台 credits 由新源码对应版本和构建专用依赖声明取代。

2026-09-28、2026-10-02、2026-10-03 等日期的原生引擎测试与旧 Release Notes 只证明当时的版本及范围，不随此次来源迁移改写。后续测试另行记录，不能把旧 ABI/API 结果直接套用到新构建。
