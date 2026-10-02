# 原生引擎接入与资源来源

当前实现是离线、逐词混输版本。IME 工具栏和候选栏是独立原生 View，不参与 KeyboardLayout JSON、应用布局预览、颜色/尺寸配置。键盘高度设置只改变下面的按键区。

## 固定资源

所有文件已随源码保存，正常 Gradle 构建不下载引擎。运行 `python3 tools/verify_engine_assets.py` 检查资源 SHA-256；`tools/engine-assets.lock.json` 记录发布包地址、原始包哈希及最终资源哈希。更换引擎或词库时必须重新执行真实候选测试，并更新资源目录版本，避免读取旧部署结果。

| 组件 | 精确来源 | 本项目接入方式 |
| --- | --- | --- |
| Mozc JNI + mozc.data | [Libre Japanese Input v0.1.2](https://github.com/elizagamedev/android-libre-japanese-input/tree/v0.1.2)，其 Mozc revision 为 `ddd9730b068387631e3b4d212314ef0ed93befe0` | 从原始 APK 取出 arm64-v8a/x86_64 的 libmozc.so 和 mozc.data，修改上游 Java JNI 加载器以移除前端依赖；JNI 方法签名保持原样 |
| Rime + OpenCC | [Trime v3.3.12](https://github.com/osfans/trime/tree/v3.3.12)，librime submodule `33e78140250125871856cdc5b42ddc6a5fcd3cd4` | 取对应 ABI 的 librime_jni.so，经 JNA 调用其中公开的 Rime C API 和 OpenCC C API，不启动 Trime Java 前端 |
| 中文资源 | 上述 Trime APK 的 assets/shared | 保留拼音、词典、OpenCC 数据；本项目修改 t2s.json 使用文本词典，并增加禁用学习的 schema patch |
| LatinIME 英文核心与词库 | [HeliBoard 4.1，revision `9f5bb635c2e8609dcd95dc7506c0c58fba82a52c`](https://github.com/HeliBorg/HeliBoard/tree/9f5bb635c2e8609dcd95dc7506c0c58fba82a52c) | 从官方 APK 原样提取两个 ABI 的 libjni_latinime.so 与 main_en-US.dict；保留四个 JNI 注册类的方法签名，使用本项目的最小只读适配器，替换 easy_en |
| JNA | [5.17.0](https://github.com/java-native-access/jna/tree/5.17.0) | 原始 Android AAR；ABI 过滤只保留 arm64-v8a/x86_64 |

Engine Management 展示已接入的输入功能及版本，也单独标记计划项，不代表瞬时原生加载状态。LatinIME 英文补全、拼写纠错与短上下文下一词预测已接入，个人学习仍属后续计划。已打包的 librime 版本为 1.17.0：x86_64 二进制的 `RimeGetVersion()` 返回该字符串；arm64 库同样包含 1.17.0 版本字符串。下述 1.14.0 是适配器采用的 C API 表定义来源，不是已打包引擎版本。Mozc 使用固定源码 revision 与 Libre Japanese Input 来源版本；OpenCC 的独立版本未在当前锁定资源中确认，界面明确标注来源为 Trime 3.3.12。方案/词典版本来自资源声明，内置排序、计算和验证码规则模块随应用版本显示。

语义排序设置接口为 `ContextRankingSettings`：使用现有 `ipa_board_prefs` 的 `semantic_context_enabled` 布尔值，默认 false，每侧上下文读取上限 256 字符。Engine Management 提供开关和延迟提示。接口先在 `6f4bc71` 提交并推送，再接入实现，保持同一键与默认值。关闭时不绑定语义服务、不部署或加载模型；关闭已启用的开关会取消请求、解绑并释放模型。普通候选始终先显示。详见 [实验性上下文联想](context-ranking.md)。

Rime function table顺序来自 librime 1.14.0 的 rime_api.h。适配器限定 64 位 ABI，并在访问函数前检查表大小。Mozc 的小型 wire reader 对应上述 revision 的 commands.proto / candidates.proto，支持该协议使用的 group、嵌套消息、未知字段跳过及输入长度限制。

Rime 的原生候选可能只覆盖原串前缀。本版本在禁用用户学习的 session 中重放、选择并检查实际 commit 和剩余编码，只把完整消费原串的结果交给 UI；这比直接展示全部返回值更慢，但不会把未消费编码丢掉。

## 生命周期与隐私

`:rime`、`:mozc` 和 `:english` 为独立、非导出的进程。每个进程串行执行原生调用，查询按 revision 合并；主进程丢弃过期结果，点击候选也校验视图快照 revision。原生崩溃时剩余引擎和原样上屏仍可使用。

英文查询最多接收游标前 256 字符，去掉当前组合文本后提取连续英文后缀（最多三个前词，遇到其他文字或句末标点停止）。en_US 词库头声明 `version=54`、`date=1414726260`、格式 202，实际预测使用词库 bigram 数据；这是静态词库预测，不是长上下文神经模型。词表来源见打包的 `English-dictionary-source.txt`，指向 OpenBoard v1.4.5 原始词表。原生每次最多返回 18 个候选，当前英文词最长 47 个字母；保留原生分数、来源顺序与候选类型，跨语言采用有界启发式排序。没有实际触点坐标，英文纠错使用完整编辑距离，不假设用户的自定义键盘是 QWERTY。

英文词库只读打开，不创建或更新用户词典；JNI 中未使用的学习方法声明仅用于上游注册完整性。预测查询允许空编码，选择预测时重新检查上下文与选区，只向当前游标插入词语，已有文本不参与替换。密码和其他直接输入框的查询编码与上下文为空。Rime 部署目录已升级到 v3，取消 easy_en session 与初始化方案，旧部署文件不会重新启用 easy_en。

LatinIME JNI 从优先队列按低分到高分输出数组；数组下标不能直接作为来源排名。适配器先按原生分数降序排序，同分时按码点长度与文字排序，去重后重新生成 rank，再交给跨语言混排。这与上游前端的排序方向一致，同时适用于补全、纠错和预测。

用户数据与部署资源放在 noBackupFilesDir。首版不写个人输入学习记录；Rime 禁用 user_dict，Mozc 使用 incognito 配置。上述限制仅针对输入引擎。当前应用具有联网权限，用于手动更新 Emoji 目录；输入法会在系统允许读取时保存本地剪贴板历史（包括敏感标记内容），未固定项最多 20 条。应用启用了系统备份，现有规则尚未排除剪贴板历史。短信自动复制可选，Broadcast 模式需短信接收权限。

## 构建、检查与当前限制

```sh
python3 tools/verify_engine_assets.py
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest
# Gradle 的设备测试依赖可用时：
./gradlew connectedDebugAndroidTest
# 也可直接安装两个 APK 后执行：
adb shell am instrument -w com.example.ipa_board.test/androidx.test.runner.AndroidJUnitRunner
```

- 首次 Rime 部署需编译词典，界面保持可用并显示加载状态；后续复用部署结果。
- 候选当前为有界列表：Rime 检查前 40 个原生候选，Mozc 最多 60 个，LatinIME 最多 18 个。展开显示这一批结果，尚无滚动到末尾继续向引擎分页。
- 排序采用来源内排名、原文匹配、具体 bigram 关联与候选类型等启发式特征，另有可选的实验性语义重排；不是经过跨语言语料校准的概率。尚无持久化个性学习，也没有无分隔整串跨语言分词。
- Rime 的两个二进制为 16KB ELF 对齐；当前 Mozc 上游发布库和 ONNX Runtime Extensions 0.13.0 AAR 中的原生库为 **4KB 对齐**。此次真机验证不等于 16KB 页设备兼容验证；面向此类设备发布前，应按上游 source revision 重编译 Mozc 与 Extensions 并验证。不要通过修改 ELF 头伪造兼容性。
- 当前使用 Trime 发布的组合 JNI 库，其中包含 GPL 代码，不能仅将其视为 BSD 的 librime。许可证文本和原始声明在 app/src/main/assets/licenses。对外分发需按实际组合依赖提供相应源码/许可材料；若要求宽松许可发行，需独立构建纯 librime/OpenCC 并替换该二进制。本次未发布软件，也未给项目自有代码指定新许可证。
- HeliBoard 发布核心及适配的上游声明包含 Apache-2.0 与 GPL-3.0；两份许可证已保存，官方源码归档的 SHA-256 与固定 revision 已记录。未包含其图标或外部手势库。LatinIME 两个 ABI 的 ELF LOAD 对齐均为 16KB；这不改变 Mozc 的上述兼容限制。

上游原生构建说明：[Trime 源码](https://github.com/osfans/trime/tree/v3.3.12)、[Libre Japanese Input 构建说明](https://github.com/elizagamedev/android-libre-japanese-input/tree/v0.1.2#building-the-mozc-jni-library-and-dataset)。

## 英文升级验证记录（2026-10-02）

- 59 项单元测试通过；调试应用、设备测试 APK 与 lint 检查通过（存在既有非阻断警告）。
- ARM64 Android 15 真机：四项原生引擎测试通过，确认 `hel → hello`、`helo → hello`、`thank → you`，以及 Rime 简繁和 Mozc 日文候选。
- ARM64 API 35 只读模拟器：13 项相关设备测试通过，涵盖 Engine Management、组合输入安全、三个原生引擎及实际键盘端到端混输。实际输入得到 `你好hello日本語thank you `，随后空编码空格和英文补全仍正常。测试恢复输入法与当前键盘页。
- 预测不会替换已有前文或选区；相同候选文字在新 revision 中出现时，旧快照仍无法提交。资源共 66 项 SHA-256 检查通过。
- 尚未验证 x86_64 运行、16KB 页设备、广泛编辑器兼容性或个人学习；上下文预测质量受静态 en_US v54 词库限制。
- 排序回归修复：真机复现旧适配器把 `wha` 的低分 `whale's` 排在高分 `what` 之前。修复后 `what` 为英文首选及混排首选；新增回归覆盖多组前缀、纠错和预测的分数降序与连续来源排名。五项原生引擎设备测试通过。

## 本次验证记录（2026-09-28）

- `testDebugUnitTest`：16 项通过；`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过（lint 存在非阻断警告）。
- 已连接 ARM64 设备通过 ADB instrumentation 运行完整套件：47 项通过。
- 实际 IME 窗口验证：同一键盘页连续输入 `你好hello汉语漢語日本語`；切页保留 `nihao` 编码；候选展开屏蔽底层键盘；返回先关面板再隐藏键盘；剪贴板面板正常打开/关闭。测试使用空白测试编辑器，并在 finally 恢复原输入法与布局。
- 仅测试自身视图的折叠/展开 PNG 已检查，顶部与按键区保持独立、窗口高度一致。
- 原生资源 SHA-256 检查通过。未验证 x86_64 运行、16KB 页设备、跨应用大规模兼容性、概率校准或性能 P95 指标。
