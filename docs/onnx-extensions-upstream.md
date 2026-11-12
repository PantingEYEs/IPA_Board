# ONNX Runtime Extensions 官方源码接入

Issue #51 将 E5 分词使用的旧 Maven Extensions 原包替换为官方源码构建的 SentencePiece 专用 AAR。本次保持 ONNX Runtime 主库、模型资源和语义排序合同不变；资源下载与项目许可证迁移仍按独立任务推进。

## 固定来源与产物

| 项目 | 固定值 |
| --- | --- |
| Extensions 官方源码 | [microsoft/onnxruntime-extensions v0.13.0](https://github.com/microsoft/onnxruntime-extensions/tree/9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432)，提交 `9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432` |
| 独立构建 | [IPA_Engine `ranking`](https://github.com/PantingEYEs/IPA_Engine/tree/dd6f7fb09530a6e0daff5ac8d5987d855a76142e)，提交 `dd6f7fb09530a6e0daff5ac8d5987d855a76142e` |
| 当前 AAR | `app/libs/onnxruntime-extensions-android-0.13.0-ipa-e5-16kb.aar`，1,509,753 字节 |
| AAR SHA-256 | `20620659f2695dc5e36cca7f339e64c606f36413b9a42c144eac0aace73cecc6` |
| ABI / 构建基线 | `arm64-v8a`、`x86_64`；Android NDK r29 `29.0.14206865`、API 26、静态 C++ 运行库 |
| 算子范围 | SentencePiece 分词／解码；不是完整 Extensions 算子发行版 |

每个 ABI 同时保留 `libortextensions.so` 与 `libonnxruntime_extensions4j_jni.so`。四份库的精确摘要、ELF 段、动态依赖、导出及 AAR 内容检查见固定构建的 [artifacts/manifest.json](https://github.com/PantingEYEs/IPA_Engine/blob/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/artifacts/manifest.json)。Gradle 只引用上述新 AAR，旧 `onnxruntime-extensions-android-0.13.0.aar` 不再作为运行依赖或回退包。

应用资源锁记录当前 AAR 的固定来源与摘要；`tools/onnx-extensions/` 保存构建产物清单、源码锁及静态校验器的固定来源，供资源检查核对实际 APK／AAR 内容。它们不承担在应用仓库重新编译引擎的工作。

源码锁、工具版本、依赖和构建入口均由独立仓库维护，见 [重建说明](https://github.com/PantingEYEs/IPA_Engine/tree/dd6f7fb09530a6e0daff5ac8d5987d855a76142e#重建)。IPA Board 只消费固定产物，正常 Gradle 构建不下载或重新编译 Extensions。

## 为什么只需 SentencePiece

对当前实际资源的[算子清单](https://github.com/PantingEYEs/IPA_Engine/blob/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/validation/graph-operator-inventory.md)确认：

- `tokenizer.onnx` 只有一个 `ai.onnx.contrib::SentencepieceTokenizer` 自定义算子。
- 重建后的完整 E5 图有 1630 个节点、21 种算子，全部使用标准 ONNX 域，没有额外的 Extensions 自定义算子。
- 两份图都没有本地函数、外部张量或训练信息；现有 SentencePiece 模型内嵌在分词图中。

因此只启用 `OCOS_ENABLE_SPM_TOKENIZER` 即可覆盖当前图所需的扩展能力，附带的 `SentencepieceDecoder` 当前未使用。标准 E5 运算仍由未裁剪的 ONNX Runtime 主库执行。图像、音频、其他分词器等可选扩展不在此包内；以后更换模型或分词图时，应重新核验算子依赖和实际推理。

ONNX Runtime 官方 Android 主库仍为 **1.23.2**，SHA-256 为 `82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827`。E5 仍使用 `intfloat/multilingual-e5-small` revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`，完整权重为 118,346,824 字节。原模型分块、`tokenizer.onnx`、`centering.json` 和语义资源清单保持不变，具体摘要见应用资源锁及独立构建的[兼容资源清单](https://github.com/PantingEYEs/IPA_Engine/blob/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/integration/ipa-board-resources.lock.json)。不额外生成分词词典，也不因为更换运行库清理用户数据或重新部署相同模型。

## 接口与行为

`SemanticEngine` 继续通过 `OrtxPackage.getLibraryPath()` 注册自定义算子库，Java 类名、公开方法签名及 JNI 库名保持兼容。独立构建沿用上游 SentencePiece 0.1.96、protobuf 21.12、GSL 4.0.0 与上游依赖补丁，不修改分词实现。

构建中的一处 [JNI 符号补丁](https://github.com/PantingEYEs/IPA_Engine/blob/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/patches/java-jni-symbol.patch)修复上游 `OrtxLibrary` Java 声明与 C 导出的类名不一致，使公开注册地址接口可用。原应用使用的 `getLibraryPath()` 路径不变；没有通过改名 `.so` 冒充 JNI 包装库。

E5 仍默认关闭，启用后保留普通候选先显示、两侧上下文、异步过期结果拒绝、关闭后解绑、失败沿用普通候选和仅在 RAM 缓存向量的现有行为。XLM-R SentencePiece 规则、fairseq token 编号、模型输入、attention-mask 均值池化与排序门槛均不因换库改变。详细产品合同见[语义排序](context-ranking.md)。

Engine Management 区分模型修订、ONNX Runtime 主库版本与 Extensions `0.13.0 · IPA build dd6f7fb`，并说明 SentencePiece 专用范围；现有 E5 和 ONNX Runtime 最新版本查询继续保留，不额外加入 Extensions 网络查询。当前没有需要新增给用户的底层配置：算子裁剪、线程数、JNI 装载和页对齐属于实现配置；现有 E5 开关继续承担用户选择入口。

## 16KB 与验证边界

**本轮以真实 16KB 页进程为验收目标，4KB 不属于新增支持或验收条件。** 构建使用 NDK r29 从源码重新链接，四份库均检查 `PT_LOAD` 对齐、偏移／地址同余、RELRO、导出和动态依赖；不是修改 ELF 头或仅改变 ZIP。C++ 运行库静态链接，动态依赖仅为 Android 系统的 `liblog.so`、`libm.so`、`libdl.so` 和 `libc.so`。API 26 链接基线不表示完整应用在 API 26 已验证。

固定 `ranking` 构建曾使用 IPA Board `a2e3bc84cc9471831e0dd049c570edd8f4d96d1f` 的隔离副本，在 **API 36／x86_64／16384 字节页**进程执行 12 项运行与语义集成检查，全部通过。该副本禁用页大小兼容模式；17 组分词 token 与旧包逐项一致，22 个 E5 分数在 `0.0001` 误差阈值内，最大实测绝对差为 `3.78955841706663e-09`。两份 debug APK 均通过 16KB ZIP 对齐检查，20 个原生条目通过 ELF LOAD／RELRO 检查，未为此次 16KB 验证排除其他原生库。

上述是**独立构建副本的既有结果**，不是当前 #51 工作树的接入验收。其测试使用合成输入和专用模拟器，并通过项目保护入口恢复、校验和删除备份；完整范围及历史新旧包对照见[独立验证记录](https://github.com/PantingEYEs/IPA_Engine/blob/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/validation/README.md)。原有 4KB 对照记录只保留历史事实，不要求本轮重复执行。

当前分支的主机检查、实际 16KB 分词／E5、多语言混排与回滚结果统一记录在[测试记录](testing.md)。不能以静态 ELF 检查代替真实进程运行，或把 x86_64 执行结果当作 ARM64 真机验证；正式签名 release、未执行的 ABI/API、广泛编辑器与输入语料也不因上述专项自动获得验证。上述自动回归中的安装和 instrumentation 均通过受保护的回归入口完成。

## 许可与旧资料处理

新 AAR 的 `META-INF/licenses/` 与应用 [assets/licenses/onnx-extensions/](../app/src/main/assets/licenses/onnx-extensions/) 保留固定构建的 12 份许可／来源原文及 `sources.json`，包含 Extensions、SentencePiece、其内置 Abseil／Darts-clone、protobuf-lite、GSL、ORT API 头文件及 NDK 运行时材料。应用目录另以 `SOURCE.txt` 与 `README.md` 说明固定构建来源；审计脚本不打包进 APK。实际源码版本、链接成员和许可证对应关系见[构建专用许可审计](https://github.com/PantingEYEs/IPA_Engine/blob/dd6f7fb09530a6e0daff5ac8d5987d855a76142e/licenses/README.md)。上游广泛的 ThirdPartyNotices 继续原样保留，不把其中未启用的组件误报为实际链接依赖；各组件继续遵循原有许可。

旧 assets 根目录中的两份 Extensions 许可由上述新目录的同版原文取代。原 `SentencePiece-Apache-2.0.txt` 仍用于现有分词图生成工具的 SentencePiece 0.2.0 来源；新原生运行库采用上游锁定的 SentencePiece 0.1.96，两者职责与版本分别记录，不能因数值不同就改写现有分词图或删除适用许可。

旧 Maven 原包、资源哈希和 4KB 对齐限制移入[历史来源归档](archive/onnx-extensions-maven-integration.md)，不在当前资源锁中继续作为活动来源。既有发布说明与有日期的历史测试保留当时事实；新的接入验证另行记录，不把旧通过结果改写为新构建已通过。构建缓存、旧二进制及 E5 模型没有重复带入此源码构建说明。
