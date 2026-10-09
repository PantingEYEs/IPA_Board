# IPA Board

可自定义的 Android 音标键盘，支持 IPA 输入、中英日混合候选与联想、注音、Emoji、颜文字、剪贴板和行内计算。

当前开发版本：**0.0.3-dev**，`versionCode = 3`，开发分支为 `0.0.3-dev`。最新正式版本为 **v0.0.2**。变更见 [更新日志](CHANGELOG.md)；正式安装包以 [GitHub Releases](https://github.com/PantingEYEs/IPA_Board/releases) 为准。

0.0.3 计划采用 Issue 驱动开发，推进鸿蒙 4.2.0.x 兼容、无候选问题与日志管理、仪表盘、按页绑定引擎、计算器、可编辑多功能栏及 IPA 键盘与自研基础引擎。范围、验收标准和建议见 [0.0.3 开发计划](docs/0.0.3-development-plan.md)；IPA 引擎已试接入，其余项目按计划推进。

独立 IPA 引擎的调用、输入、返回值及接入安全要求见 [引擎开发规范](docs/engine-specification.md)。

## 安装

最低 Android 15（API 35），原生引擎包含 `arm64-v8a` 和 `x86_64`。安装 APK 后，在系统设置中启用 IPA Board 并选择为当前输入法。

基础输入与候选在本地运行，首次使用需要后台部署内置词库。Emoji 目录更新、引擎最新版查询及 IPA 资源更新需要联网。

## 键盘与布局

在应用主页进入 **Keyboard → Keyboard Management**，点击预览中的按键编辑文本或功能。支持短按、长按、多项长按循环选择、持续退格、左右滑动，以及 Shift/Ctrl 快捷键。

- **Page Group** 管理页面归属与顺序；同一页面可属于多个组。颜色、高度、字号和格线按组保存，输入法页面面板可切换组。
- **Long-press time** 调整长按触发时间，范围 200–2000 毫秒，适用于所有键盘页；未设置时沿用系统默认值。
- 支持布局新建、重命名、删除、清空与撤销。Clear Keys 后，未切页或进行其他编辑时可用 Undo Clear 恢复。
- JSON 支持多文件导入，重名文件自动加后缀；导入页面加入当前组，空组临时使用内置模板。

布局导出为 `version: 4`，只包含 `layout`，不包含页组外观、剪贴板或应用全部设置。旧版带 `appearance` 的配置仍可导入，外观应用到当前组。配置文件上限 1 MB。

首次安装的预置内容来自 [键盘配置](app/src/main/assets/initialization/keyboards) 和 [颜文字配置](app/src/main/assets/initialization/kaomoji)，更新应用不会覆盖已有用户配置。当前不提供增删行列的布局编辑器。

## 输入与候选

普通文本框中的文字键进入预输入区，支持中文拼音、注音和日文罗马字，与英文、数字及符号一起输入时保留原文片段。例如 `ㄋㄧˇㄏㄠˇ` 可得到 `你好`。注音支持省略声调，`ˉˊˇˋ˙` 可用于明确声调。密码及非文本输入框使用直接输入，不读取上下文生成候选。

- 中文使用 Rime，支持简繁转换及词组续写；日文使用 Mozc，支持转换和下一词候选。
- 英文使用 LatinIME，支持补全、拼写纠错和短上下文预测；中文等上下文没有英文词时，回退到句首或常用词候选。
- 点击候选上屏，右侧按钮展开候选；空格选择首选词，英文追加空格。Enter 原样结束组合，再次按下执行编辑器动作。
- 候选按钮最小宽度随键盘按键宽度调整。切页保留组合文本，工具面板的 Return 或系统返回键返回键盘。

主页 **Engine → Engine Management** 提供各项输入能力的独立开关和关闭后的回退说明；全部候选引擎关闭时仍可原样上屏。展开类目会尝试并列显示当前版本与官方最新版本，现有 Rime、Mozc、LatinIME 与 E5 暂不提供手动替换。

**IPA Engine** 已实验接入，支持 IPA → 英文、简体/繁体中文和日文词条，例如 `həˈloʊ → hello`。当前上游包仅支持 `arm64-v8a`，不提供下一词预测。进入 **Engine Management → Manage IPA engine and dictionary**，可从上游仓库分别更新引擎或词典；词典由开发侧预先编译为压缩二进制，客户端不编译、不需要更新 APK 或连接电脑。先校验文件，再用当前引擎验证兼容性，成功后切换并删除旧词典；失败时保留旧资源。输入与上下文不上传。开发侧发布格式见 [IPA 更新接口](docs/ipa-updates.md)。

**Multilingual E5** 语义排序默认关闭。开启后参考光标前后文重排中英日候选，失败时保留普通候选；模型和分词资源约 123 MB。输入与上下文不上传，也不保存学习历史。中文续写、日文预测及 E5 排序均受现有词库范围限制，不提供整句生成或任意混输的自动语言分词。

**Debug diagnostics** 默认关闭，开启后输出引擎加载阶段与固定错误码；不记录输入、候选或私人配置。详见 [诊断说明](docs/diagnostics.md)、[引擎来源与许可](docs/engine-integration.md) 和 [语义排序说明](docs/context-ranking.md)。

## 工具栏与功能键

多功能栏顺序：**⧉ → ⊞ → 顔 → status → ◩ → ∑**，依次为剪贴板、键盘页、颜文字、状态区、全半角互换和计算器。

**◩ 全半角互换** 默认关闭，可绑定到按键。开启后互换输入字符的全角与半角形式，支持英文、数字、标点、空格和片假名；无对应形式的字符保持原样，剪贴板粘贴保留完整原文。

**∑ 计算器** 默认关闭。开启后混合输入中的四则运算结果以候选显示，支持小数和负数，最多显示四位小数；不支持括号或科学计算。

功能键还支持光标导航、选区、复制/剪切/粘贴、Emoji、候选展开及切页。Shortcut 页面可配置 Shift/Ctrl 映射；快捷键支持取决于接收应用，Ctrl+Backspace 执行普通退格。

## Emoji、颜文字与剪贴板

- **Emoji** 支持分类、常用列表和连续输入，可从 Unicode 官方来源更新目录；更新不会改变系统字体。详见 [目录说明](docs/emoji-catalog.md)。
- **颜文字** 支持编辑、搜索、标签、批量管理及 JSON 导入/导出，输入法面板可筛选、按使用频率排序及倒序查看。
- **剪贴板** 使用两列等大卡片，长文仅省略预览，粘贴仍使用完整文本。支持固定、左滑删除与撤销，未固定项最多 20 条。
- 剪贴板状态栏 **⇅** 切换倒序，开启后显示 **⇅ Rev**。刷新保留方向，重新打开恢复正常；倒序只改变显示顺序。
- Clipboard 设置可调整快捷粘贴的开关、保留时间和使用次数。剪贴板历史保存在本地，敏感标记不阻止保存，系统备份规则尚未排除历史数据。

短信验证码自动复制默认关闭。Broadcast 模式需短信接收权限；AutoFill 当前仅启动 SMS Retriever，尚未实现接收处理。

## 构建与测试

使用 Android Studio 或 Gradle Wrapper，配置 Android SDK 36.1；当前主机验证使用 JDK 21。依赖已缓存时可离线构建：

```sh
python3 tools/verify_engine_assets.py
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/regression.py validate
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --offline --console=plain
python3 tools/regression.py run --suite full --serial DEVICE_SERIAL --no-build
```

**设备回归前必须备份 IPA Board 软件与私有数据，结束后恢复、校验并删除备份。** 所有安装、instrumentation 和输入法切换检查均通过上述保护工具执行；恢复失败时保留备份并先恢复。套件选择、备份范围、恢复入口和验证记录见 [测试流程](docs/testing.md)。

Mozc 与语义分词 Extensions 原生库为 4KB 对齐，尚未验证 16KB 页设备兼容性。既有 debug 回归结果不代表正式签名 release 或其他设备已通过。
