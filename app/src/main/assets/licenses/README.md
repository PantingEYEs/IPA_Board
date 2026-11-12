# 第三方来源与许可材料

当前中文引擎使用 [IPA_Engine chinese 固定构建 `073aabe`](https://github.com/PantingEYEs/IPA_Engine/tree/073aabe899fdcbdd195e2e9199fb25c43a574f47)，librime 1.17.0 与 OpenCC 1.4.2 组成同一原生库。完整原始通知、源码锁和资源锁保存在 `rime-upstream/`，其 `artifacts.json` 标识实际 AAR／ABI 和 build ID。

`rime-upstream/Rime/` 与 `rime-upstream/OpenCC/` 保留上游 licenses 目录原始结构和字节；这些原始说明中的相对路径按上述固定构建仓库解释，不能据本应用 assets 的存放位置重写通知原文。AAR 的 `META-INF/licenses/` 也保留相同材料。

Rime 方案、词典与 essay 的 LGPL v3 及附带 GPL v3 文字、CC-CEDICT 归属和适用 CC-BY-SA 条款继续有效。它们不应作为旧 Trime 运行库残留删除。新的独立构建及 JNI 文件保留 PantingEYEs 版权，尚未指定项目许可；上游材料维持各自许可。移除 Trime 组合库和 JNA 不代表 IPA Board 已完成整体协议迁移。

Mozc 的原始通知与来源在 `mozc/`，ONNX Runtime Extensions 的通知与来源在 `onnx-extensions/`；LatinIME/HeliBoard、英文词表、E5/ONNX Runtime 主库及 IPA Engine 的其他现有材料仍按各自声明保留。本文件不替代任何原始许可证或未完成的来源核验。

旧中文接入的历史归档见项目 `docs/archive/rime-trime-integration.md`；现行合同见 `docs/rime-upstream.md`。
