# IPA Board

可自定义的 Android 音标键盘，使用 Kotlin 和原生 View，支持 IPA 直接输入、中文/英文/日文混合候选、Emoji、颜文字、剪贴板和行内计算。

当前开发版本：**0.0.2-dev**（`versionCode = 2`），开发分支为 `0.0.2-dev`。已发布版本为 [0.0.1](https://github.com/PantingEYEs/IPA_Board/releases/tag/v0.0.1)。版本变化见 [更新日志](CHANGELOG.md)。

## 0.0.2 开发计划

本版计划推进以下四项工作，目前尚未实现：

1. 联想输入。
2. 可插拔输入法引擎。
3. 更流畅的符号、数字、音节混合输入。
4. 更合理的单元测试，并集中排查 0.0.1 的设备测试问题。

详细范围、验收方向与历史测试记录见 [0.0.2 开发计划](docs/0.0.2-development-plan.md)。下文功能说明描述现有实现。

## 安装与兼容性

- 最低 Android 15（API 35）；原生引擎包含 `arm64-v8a` 和 `x86_64`。
- 从 [GitHub Releases](https://github.com/PantingEYEs/IPA_Board/releases) 下载签名 APK，允许安装该来源的应用后安装。
- 在 Android 系统设置中启用 IPA Board，并将它选择为当前输入法。
- 首次混合输入需要后台部署离线词库；基础输入无需联网，Emoji 目录更新需要网络。
- Mozc 原生库为 4KB 对齐，尚未完成 16KB 页设备兼容验证。原生资源来源、许可证和限制见 [引擎接入说明](docs/engine-integration.md)。

## 键盘与布局

打开应用后，在主设置页进入 **Keyboard Page**，点击预览中的按键进行编辑。其他设置入口为 Shortcut、Emoji、顔文字和 Clipboard。

- Text 可设置 IPA、多字符文本、空格与换行；清空文本即取消映射。默认空白布局的未配置键只在编辑预览中显示 ∅。
- 按键可设置短按、长按文本或功能；支持多项长按循环选择、长按持续退格以及左右滑动动作。
- 输入方式可选 Direct text / IPA 或 Mixed input。直接文本不交给混合输入引擎；长按文本会先原样结束待确认编码。
- 支持设置键盘颜色、高度、行高与按键宽度比例，以及键盘页排序。
- New Layout 创建并启用空白布局；Rename Keyboard Page 重命名当前页和对应文件；Delete Active Layout File 删除当前文件并回到默认布局，默认布局不可删除。
- Clear Keys 清空文本与功能映射，保留布局结构和外观；未切页且未进行其他编辑时，可用 Undo Clear 恢复。
- Export / Import 通过系统文件选择器保存或导入 JSON。导入保留来源文件名；重名时追加数字后缀，不覆盖已有布局。
- 尚未实现增删行列的布局编辑器。

## 混合输入与候选

从键盘页总览选择“多语言 QWERTY”，可输入中文全拼、英文和日文罗马字。中文简繁体、英文和日文候选混排；当前采用逐词混输与启发式排序，尚无整串跨语言自动分词或持久化个性学习。

点击候选上屏，点击右侧箭头展开候选面板。“原样上屏”保留输入字母。空格选择首选词，英文追加空格；Enter 原样结束组合，再次按 Enter 执行编辑器动作。IPA、标点与粘贴会先结束待确认编码。切换键盘页保留组合文本。

工具栏和候选栏独立于布局 JSON，不在布局预览中显示。候选和工具面板覆盖按键区，窗口高度保持不变；Return 或系统返回键恢复键盘。

## 功能键与快捷键

| 功能 | 用途 |
| --- | --- |
| Backspace / Continuous Delete | 删除文本；持续退格用于长按。 |
| Arrow Left / Right / Up / Down、Home / End | 移动光标，Shift 可扩展选区。 |
| Shift / Uppercase、Ctrl | 大写、导航选择与 Ctrl 快捷键。 |
| Enter / Tab | 编辑器动作、换行或焦点切换，行为取决于接收应用。 |
| Select All / Copy / Cut / Paste | 选择与剪贴板操作。 |
| Candidates / Clipboard / Keyboard Pages | 打开候选、剪贴板或键盘页面板。 |
| Previous / Next Keyboard Page | 切换前一页或后一页。 |
| Emoji / 顔文字 | 打开表情或颜文字面板。 |
| Calculator | 切换行内计算模式。 |

Shortcut 设置页可配置 Shift/Ctrl 映射。Ctrl+A/C/X/V 请求全选、复制、剪切和粘贴；其他单个 ASCII 字符可发送 Ctrl 组合键事件。快捷键支持取决于接收应用。

## Emoji 与颜文字

Emoji 面板支持分类筛选和连续点击输入。内置目录元数据标记为 Emoji 18.0，共 3,972 项 Emoji 和组件；Emoji 管理页提供常用表情及一键目录更新。更新从 Unicode 官方地址下载，不会更新系统字体；字体不支持的表情显示名称，仍可输入完整字符序列。见 [目录实现说明](docs/emoji-catalog.md)。

顔文字管理页支持添加、编辑、搜索、标签、批量管理及 JSON 导入/导出；输入法面板支持筛选和使用频率排序。

## 剪贴板与验证码

输入法会在系统允许读取剪贴板时，将文本保存到应用本地历史。未固定项最多保留 20 条，固定项单独保留；面板支持粘贴、固定与删除。Clipboard 设置可控制快捷粘贴的开关、保留时间和使用次数。

敏感标记不代表不保存。当前系统备份规则也未排除剪贴板历史，请及时删除不希望保留的内容。

短信验证码自动复制默认关闭。Broadcast 模式需要短信接收权限，使用本地规则提取并复制验证码。AutoFill 模式目前仅启动 Google Play services SMS Retriever，尚未实现检索结果接收处理，不应视为可用的自动复制功能。短信权限不是基础输入的必需权限。

## 行内计算

工具栏 ∑ 或 Calculator 功能键可切换计算模式。在混合输入中输入四则运算表达式时，结果以 ∑ 候选显示，点击即可上屏。

支持小数、负数和 `+`、`-`、`*`/`×`、`/`/`÷`。结果最多显示四位小数，舍入结果追加省略号；不支持括号或科学计算。

## 配置格式

布局导出使用 `version: 4`，包含 `layout` 和 `appearance`。按键字段包括 `text`、`action`、`textBehavior`、`longPressText`、`longPressAction`、`longPressItems`，以及左右滑动文本和动作；比例字段为行的 `heightWeight` 与键的 `widthWeight`。外观包括 `backgroundColor`、`symbolColor` 与 `heightDp`。布局导出不包含剪贴板历史、颜文字库或应用的全部设置。

动作序列化值包括 `text`、`backspace`、`repeat_backspace`、`left`、`right`、`up`、`down`、`shift`、`ctrl`、`enter`、`tab`、`home`、`end`、`emoji`、`kaomoji`、`calculator`、`candidates`、`clipboard`、`pages`、`prev_page`、`next_page`、`select_all`、`copy`、`cut`、`paste`。

旧版配置及只有 `name` 和 `rows` 的布局仍可导入，缺少新增字段时使用兼容默认值；未知动作会被拒绝。配置最大 1 MB，支持 1–20 行、每行 1–40 个键，短按与长按文本最多 1000 个 UTF-16 代码单元；比例必须为有限正数。

## 构建与验证

使用 Android Studio 打开项目，配置 Android SDK，并使用 Gradle Wrapper 构建。

```sh
python3 tools/verify_engine_assets.py
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest
# 连接 API 35+ 的测试设备或模拟器后：
./gradlew connectedDebugAndroidTest
```

发布前还需手工检查：IPA 与混合输入、候选展开和返回、布局导入/导出、长按与左右滑动、切页、快捷键、Emoji/颜文字、剪贴板与 ∑ 计算候选。分别确认旧配置兼容和更新后数据保留。

## 发布流程

从 `main` 创建版本开发分支（当前为 `0.0.2-dev`）。功能分支使用 `--no-ff` 合入版本开发分支；在开发分支完成正式版本号、README、更新日志及验证后，再使用 `--no-ff` 合入 `main`，保留两次集成边界。从最终提交构建并验证签名 release APK，创建对应版本标签并发布 GitHub Release。已合并的功能分支和旧开发分支在发布后清理。

开发阶段使用 `0.0.2-dev`；准备正式发布时，在构建前将 `versionName` 改为 `0.0.2`。每次正式发布递增 `versionCode`；若连续分发开发安装包并要求覆盖升级，也应分配递增的 `versionCode`。发布包与版本标签对应同一源码提交，正式标签保持不变。

当前 Gradle 配置没有 release 签名配置，`assembleRelease` 的产物不能直接当作已签名发布包；可在 Android Studio 中使用 Generate Signed App Bundle / APK 完成签名。签名密钥和密码不提交到仓库。对外分发前需落实组合原生依赖的源码与许可材料，见 [引擎接入说明](docs/engine-integration.md)。
