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

`:rime`、`:mozc`、`:english` 和 `:ipa` 为独立、非导出的进程。每个进程串行执行原生调用，查询按 revision 合并；主进程丢弃过期结果，点击候选也校验视图快照 revision。原生崩溃时剩余引擎和原样上屏仍可使用。

英文查询最多接收游标前 256 字符，去掉当前组合文本后提取连续英文后缀（最多三个前词，遇到其他文字或句末标点停止）。en_US 词库头声明 `version=54`、`date=1414726260`、格式 202，实际预测使用词库 bigram 数据；这是静态词库预测，不是长上下文神经模型。词表来源见打包的 `English-dictionary-source.txt`，指向 OpenBoard v1.4.5 原始词表。原生每次最多返回 18 个候选，当前英文词最长 47 个字母；保留原生分数、来源顺序与候选类型，跨语言采用有界启发式排序。没有实际触点坐标，英文纠错使用完整编辑距离，不假设用户的自定义键盘是 QWERTY。

英文词库只读打开，不创建或更新用户词典；JNI 中未使用的学习方法声明仅用于上游注册完整性。预测查询允许空编码，选择预测时重新检查上下文与选区，只向当前游标插入词语，已有文本不参与替换。密码和其他直接输入框的查询编码与上下文为空。Rime 部署目录已升级到 v4，部署朙月拼音与注音两个隔离 session；继续排除 easy_en，旧部署文件不会重新启用它。

LatinIME JNI 从优先队列按低分到高分输出数组；数组下标不能直接作为来源排名。适配器先按原生分数降序排序，同分时按码点长度与文字排序，去重后重新生成 rank，再交给跨语言混排。这与上游前端的排序方向一致，同时适用于补全、纠错和预测。

用户数据与部署资源放在 noBackupFilesDir。首版不写个人输入学习记录；Rime 禁用 user_dict，Mozc 使用 incognito 配置。上述限制仅针对输入引擎。当前应用具有联网权限，用于手动更新 Emoji 目录、查询版本与下载 IPA 资源；输入法会在系统允许读取时保存本地剪贴板历史（包括敏感标记内容），未固定项最多 20 条。应用启用了系统备份，现有规则尚未排除剪贴板历史。短信自动复制可选，Broadcast 模式需短信接收权限。

## 构建、检查与当前限制

```sh
python3 tools/verify_engine_assets.py
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest --offline --console=plain
python3 tools/regression.py run --suite engines --serial DEVICE_SERIAL --no-build
python3 tools/regression.py run --suite full --serial DEVICE_SERIAL --no-build
```

设备运行由脚本先备份软件状态/配置，测试结束后恢复校验并删除备份。不得直接安装/调用 instrumentation。详见 [测试流程](testing.md)；下列旧日期记录为历史结果，不代替当前完整回归。

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

## 混合预输入与中日联想（2026-10-03）

候选服务先保留普通字母编码的原生查询，再对数字、标点、Emoji 或中日文字之间的字母片段分别查询。每个引擎最多查询最后 8 个片段，候选组合最多 60 项；未查询或未识别的片段原样保留，简繁体分别组合。连续字母内部的跨语言边界仍不自动识别。

英文继续使用上下文 bigram；没有英文上下文时尝试词库句首候选，无结果时提供经词库概率验证的常用词。中文利用已打包的 Rime essay 词频表，按最长中文末尾词组提供后缀，未匹配时回退到高频词。这是词组续写，而非通用下一词语言模型。词库按需加载一次，以语料文本和行偏移构建紧凑索引。

日文使用固定 Mozc 协议的 `Request.zero_query_suggestion`，在独立隐身会话中逆转换上下文并内部提交，读取下一词候选；该提交不会写入编辑框，不保存历史。协议行为对应 [固定源码中的 Session](https://github.com/google/mozc/blob/ddd9730b068387631e3b4d212314ef0ed93befe0/src/session/session.cc)。中日文预测只插入候选，不追加英文空格；空上下文仍不显示预测，旧版本和旧游标候选仍会被拒绝。

验证：79 项单元测试及 16 项定向设备测试通过（组合输入 8 项、原生候选 8 项）。设备示例包括 `nihao123, → 你好123,`、`中文123hel! → 中文123hello!`、`nihongo123! → 日本語123!`、`天气123， → 预报/預報` 和 `ありがとう, → ございます`。81 个固定引擎资源哈希校验通过。

## 引擎开关及版本查询（2026-10-03）

Engine Management 保存 13 个功能开关，除语义排序仍默认关闭外，其他现有功能默认开启。主进程读取配置，将不可变策略随每次 Messenger 请求传入独立的引擎进程，避免跨进程 SharedPreferences 缓存问题。设置改变时立即清除旧候选并重新查询；另以请求序号拒绝相同组合版本下的旧策略回复。某语言的转换、补全/纠错及预测都关闭后，不再绑定或查询该语言服务；已加载的进程级原生库允许缓存，不尝试不安全的 dlclose。

关闭英文补全保留纠错，反之亦然；过滤发生在片段候选组合之前。关闭简化不生成简体候选，但中文词组查询仍可进行内部简转繁上下文归一化。关闭评分采用稳定来源顺序和去重；关闭分段不查询含数字、标点的组合，仍保留原样提交路径。所有候选关闭不会丢弃预输入。计算和本地短信解析均有独立门控，拼音纠错是 Rime 方案规则的一部分，当前未实现独立切换。

版本查询仅在展开类目时读取固定官方 GitHub Releases/Hugging Face 模型元数据。每个条目显示当前版本及最新来源版本/模型修订，不提供资产下载或引擎替换路径。内置/尚未集成条目明确显示无独立发行版或未集成。连接、读取均设 5 秒超时，响应限制 1 MiB，并在后台解析；成功缓存 5 分钟，失败缓存 30 秒。关闭页面时丢弃回调，限时 HTTPS 请求自行在后台结束，避免中断套接字拖慢主线程重建。


### 拼音纠错的归属与选型

`luna_pinyin.schema.yaml` 的 `speller/algebra` 引用 `pinyin:/spelling_correction` 和 `pinyin:/key_correction`；规则定义在 `pinyin.yaml`，与简拼一起参与 Rime 的拼音解码。它们不是独立库、服务或模型。规则可通过修改方案并重新部署调整，“固化在 Rime 中”不表示 Rime 无法配置它们。当前应用未实现规则切换及重新部署。

Engine Management 删除重复的拼音纠错条目及 `Pinyin Correction Engines` 类目，在中文 Rime 条目中说明内置能力；保留现有纠错规则与输入行为。

开源方案中，[ibus-libpinyin](https://github.com/libpinyin/ibus-libpinyin/wiki/ibus-libpinyin-features) 提供常见拼音错拼、模糊音和分段支持；[libime](https://github.com/fcitx/libime/blob/master/src/libime/pinyin/pinyincorrectionprofile.h) 提供独立的纠错配置模块，但属于完整拼音引擎的一部分，并不是开箱即用、可替换任意引擎的纠错服务。[KNPTC](https://arxiv.org/abs/1805.00741) 等研究探索神经拼音纠错，但论文不能直接等同于成熟的 Android 离线可接入引擎。

本项目现阶段优先补充 Rime 规则并以真实错拼样例评估召回率、正确拼音的误纠率及延迟。另造纠错引擎需要处理音节歧义、汉字词频和上下文，还会增加与中英混合输入的意图冲突；没有明确测试收益时不值得承担重复维护与运行成本。若以后需要跨中文引擎共享纠错、适配触摸邻键，或现有规则无法覆盖大量漏字/多字/换位错误，可先评估可选的纠错候选生成模块，再决定是否恢复独立类目。


### 注音转换

中文进程按原始输入字符选择 `luna_pinyin` 或 `bopomofo` session。`ZhuyinInput` 把 Unicode 基础注音符号和五声映射到官方大千式按键；输入框和预输入区仍显示原始符号。轻声可使用前置点，编码时转成 Rime 的音节后置键。不是先将注音转换成无调拼音：注音方案使用带声调的 Terra Pinyin 字典，保留声调筛选，允许省略声调及声母简写。

方案与拼写规则来自 [rime-bopomofo revision 6085c9a38a4a728047862b33d67eee18aa86f3b9](https://github.com/rime/rime-bopomofo/tree/6085c9a38a4a728047862b33d67eee18aa86f3b9)，字典来自 [rime-terra-pinyin revision 723e51bc266cf9464530c1ddedb856aa18e3da34](https://github.com/rime/rime-terra-pinyin/tree/723e51bc266cf9464530c1ddedb856aa18e3da34)。官方文件不改写，通过 `bopomofo.custom.yaml` 禁用学习与补全，并使用共享 OpenCC 设置控制简体候选。查询 session 开启 Rime 的 `_auto_commit`，使完整选词产生内部 commit；应用仍只在用户点击候选后上屏，部分选择不提交。查询后检查原生保留的编码与原始映射一致，防止忽略非法前置声调后误替换整个输入。许可证与 CC-CEDICT 原始归属随资源保存。

混输分段由各引擎声明输入语法，中文引擎可处理拼音和注音片段，英文/日文引擎只查询各自支持的字母片段。数字、汉字、表情及标点仍原样保留。关闭混输分段后，完整注音串仍可转换；含其他文字的组合只允许原样提交。中文转换关闭同时停用拼音与注音候选，不影响单独开启的中文联想。布局初始化保留用户既有配置，已有注音页面会直接获得汉字候选。

验证（2026-10-03）：87 项单元测试、9 项注音设备测试、11 项原有多语引擎设备测试通过；Android 15 ARM64 真机确认带调/无调转换、轻声、简繁、混输保留、独立开关、删除重输与汉字提交，非法前置声调不被静默删除。Debug 构建与 lint 检查通过（0 error），87 项引擎资源 SHA-256 检查通过。

## IPA 实验接入（0.0.3-dev）

IPA_Engine v0.0.1-dev 已以独立 `:ipa` 服务接入，当前只提供 ARM64 的 IPA → 中英日文字候选；引擎开关可独立关闭。内置资源固定于上游发布提交 `53eed9518f932934a28aceb4e03a453d545f80b8`，包含只读 DEX、原始 JNI 和 IPADWG01 v2 压缩词典。资源哈希、原始 AAR 来源与字典数据声明均已保留。

Engine Management 提供独立资源页，支持引擎和预编译词典分别更新。更新路径无输入或上下文上传；客户端没有词库编译器。活动指针原子切换，旧请求通过资源 generation 拒绝；词典更新用当前引擎在 `:ipa_check` 中验证，失败保留旧资源。具体发布接口见 [IPA 更新接口](ipa-updates.md)。
