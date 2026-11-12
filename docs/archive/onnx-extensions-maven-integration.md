# 旧 ONNX Runtime Extensions Maven 接入来源归档

本文件记录 Issue #51 迁移前的历史来源，基线为 IPA Board 提交 `a2e3bc84cc9471831e0dd049c570edd8f4d96d1f`。当前源码构建、模型兼容范围和 16KB 验证边界见 [Extensions 接入说明](../onnx-extensions-upstream.md)。

旧运行库来自 [Maven Central 的 ONNX Runtime Extensions Android 0.13.0 原包](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-extensions-android/0.13.0/onnxruntime-extensions-android-0.13.0.aar)。原资源锁将下载缓存名记为 `extensions.aar`，应用使用文件名为 `app/libs/onnxruntime-extensions-android-0.13.0.aar`。

| 旧文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `onnxruntime-extensions-android-0.13.0.aar` | 9,040,811 | `cb98c6fbeac1a9707228c4b76cfbb395b4dc4d310900aefaa672561d17fd6d9d` |

旧 AAR 中当前使用的 `arm64-v8a`／`x86_64` 两种 ABI 的 `libortextensions.so` 与 `libonnxruntime_extensions4j_jni.so` 为 4KB ELF 对齐，是当时完整应用 16KB 运行的已知限制。原应用通过 `OrtxPackage.getLibraryPath()` 注册 SentencePiece 自定义算子，使用 ONNX Runtime 主库 1.23.2 和既有 E5 模型；本次只替换 Extensions，主库与模型不是旧资源清理对象。

独立 `ranking` 构建曾在隔离的 IPA Board 副本保留旧 AAR 作为输出基线，捕获 17 组分词结果与 22 个 E5 分数，再与新库对照。旧包和该对照的 4KB 环境属于迁移历史，不是当前应用的回退依赖或新增支持目标。具体记录保存在[固定构建的验证目录](https://github.com/PantingEYEs/IPA_Engine/tree/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/validation)。

原包及旧资源锁仍可从上述基线提交和 Git 历史取得，不在当前 `app/libs` 中重复保存。原许可证与来源声明随新构建保留适用材料，不因更换二进制删除第三方版权。2026-10-03 等日期的旧语义测试和已发布 Release Notes 继续说明当时版本；不改写为 #51 的通过记录。
