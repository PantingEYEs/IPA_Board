# 实验性上下文联想

设置接口先在 `6f4bc71` 提交并推送，再接入以下实现，始终使用 `ContextRankingSettings` 的 `semantic_context_enabled`，默认 false。Engine Management 开关告知候选延迟和内存开销，关闭时不部署、绑定或加载语义模型。

## 输入与评分

原生查询同时接收光标前后文，每侧最多 256 字符，前文移除当前 composing 编码。当前输入由原生候选的完整匹配、来源排名、纠错/补全类型和原文匹配约束；语义层只重排这些候选，不生成额外词语。

LatinIME 继续使用前词 bigram，并读取候选与后词的 bigram；相对独立词概率存在实际提升时，才给具体候选加分。Mozc 使用锁定协议的 `Input.context=6`、`preceding_text=1`、`following_text=2`。Rime 保持完整编码转换，其候选同样进入语义重排。原先“前文有英文就给所有英文候选加分”的规则已删除。

模型为 [Multilingual E5 small](https://huggingface.co/intfloat/multilingual-e5-small/tree/614241f622f53c4eeff9890bdc4f31cfecc418b3)，revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`，使用上游 `model_qint8_avx512_vnni.onnx`，118,346,824 字节。该名称是量化预设，图使用通用 CPU 运算，不是 x86 机器码。Android 运行库固定为 ONNX Runtime 1.23.2 和 Extensions 0.13.0，AAR 随源码保存。

分词使用原模型的 XLM-R SentencePiece 规则及 fairseq token 编号，通过原生 SentencePiece custom op 执行，未自研近似分词器。模型、图、校准向量和运行库的 SHA-256 均锁定。模型分成三个不超过 40 MiB 的资源块，首次使用在 noBackupFilesDir 校验、合并并原子替换；构建无需网络。

E5 原始相似度带有明显共同语言偏差，不能直接作为输入概率。用中英日各 24 个固定、对应的广泛概念取中心向量，按最近中心减去基线并重新归一化，减弱这种偏差。上下文和候选均使用 `query:` 前缀及 attention-mask 均值池化。生成工具为 `tools/build_semantic_tokenizer.py`，校准不使用用户文本。前后两侧各保留最多 30 个 token，避免长前文完全挤掉后文。

每种语言选择基础排名的前 18 个候选推理，总计最多 54 项，覆盖 LatinIME 一次返回的全部候选。只有相似度跨度 ≥ 0.10，且超过 0.20 及候选中位数 + 0.08，才加有界分值；没有充分证据时沿用基础顺序。有实际语义重排时，每种现有语言的最佳候选保留在前六项，最强整体候选仍可居首。门槛是保守启发式，尚未经过大规模输入语料校准。

## 运行与边界

`:semantic` 是非导出的独立进程，串行推理，等待 150 ms 合并候选更新，用 revision 和完整候选快照 token 拒绝过期结果，逐词取消过期计算。主线程不等待模型，普通候选先显示，语义结果随后更新。推理失败时继续使用普通候选。

缓存最多 256 个向量，只存 RAM；输入结束、进入直接输入/密码框、开关关闭或 IME 销毁时释放语义服务。候选选中前重新检查前后文，外部编辑后不能提交旧排序。关闭开关也会取消待处理请求和解绑，已在执行的单次原生计算结束后释放资源。

输入法不会为该功能写入上下文文件、学习记录或上传文本。模型和分词资源约 123 MB，启用后还会增加 RAM 与首次排序延迟。该检索式模型不是下一词生成模型；关联弱、候选未进入短名单、语义模糊或模型的跨语言误差都可能导致排序没有改善。当前并不宣称语义质量或语言公平性已获得广泛验证。

## 验证

按当前开发偏好不自动运行单元测试。构建与设备检查分别执行，可按需运行指定类：

```sh
python3 tools/verify_engine_assets.py
./gradlew assembleDebug assembleDebugAndroidTest --offline
adb shell am instrument -w -e class com.example.ipa_board.SemanticContextIntegrationTest com.example.ipa_board.test/androidx.test.runner.AndroidJUnitRunner
```

ARM64 API 35 只读模拟器上，默认关闭不绑定语义服务、真实模型的光标前/后文查询、候选外部编辑后的失效检查已通过。固定示例 `I would like to drink` 更关联 `coffee`，后文 `coffee` 使较低基础排名的 `coffee` 居首，同时保留中日候选。首次部署、加载及两次五词查询合计约 1.36 秒；这个单次记录不是性能 P95，也不代表所有设备或所有输入的语义质量。

2026-10-03 补充检查：中文“我想喝杯咖啡 / 来提提神”对英文 `coffee` 的关联高于无关中日候选；中文“请问现在几点，想知道”没有改变固定示例的基础排序。完整协调器中 `wha + 后文 whale` 将 `whale` 升至首位，实时关闭恢复以 `what` 为首的原生排序。17 项相关设备检查分别通过，包含既有三语原生查询、组合提交、实际键盘混输和开关保存；没有运行单元测试套件。语义层始终受原生候选范围限制，不会绕过原生词库批次生成新词。尚未验证 x86_64 运行、16KB 页设备或广泛编辑器/输入语料。
