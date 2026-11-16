# 旧 LatinIME APK 接入来源（归档）

此记录描述 #52 迁移前的 `71b19ee` 基线，现行方式见 [LatinIME 源码构建接入](../latinime-upstream.md)。

旧运行库从 HeliBoard 4.1 官方 APK 提取，源码修订为
`9f5bb635c2e8609dcd95dc7506c0c58fba82a52c`，APK SHA-256 为
`eb9c06685ebd5b7307491da9ef15fb5e29694077a934a8699b9b6772c6f76075`。
旧 `libjni_latinime.so` 的 ARM64 哈希为
`a97b09fd3de5395023df9fb4ddc2b1eef44162bd6e987b975897d74fc3ee7081`，x86_64 为
`6f85b8fcb378ce0e53bf6df12ec2ad69286c0e61bda21cb2eb565dcbeb2fd0d0`。

`BinaryDictionary`、`DicTraverseSession`、`BinaryDictionaryUtils`、`ProximityInfo` 的最小注册声明保留
`Apache-2.0 AND GPL-3.0-only` 标识，另有 personalization 签名占位类。新构建已整体替换该注册面；
本记录不指导删除标识或继续加载旧库。旧源码及声明仍可在 Git 历史核查。

词典 `main_en-US.dict` v54 没有因运行库迁移更换，原始 GPL／Apache 许可文字与独立来源证据继续保留。
历史 ARM64 测试及旧静态 16KB 对齐只说明当时产物，不自动成为新接口／新库的验收结果。
