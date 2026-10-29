# IPA 引擎与词典独立更新

客户端入口为 Engine Management → Manage IPA engine and dictionary。检查更新只读取版本；更新引擎只替换 Java DEX 与 JNI 库；更新词典只下载开发侧预先编译的 `.ipad`，保持当前引擎。客户端没有源词库编译功能，也没有输入学习上传。

内置包来自 IPA_Engine 的 `v0.0.1-dev` ARM64 AAR，源发布提交为 `53eed9518f932934a28aceb4e03a453d545f80b8`。内置词典是 `IPADWG01` v2 的压缩图与词条二进制，包含 1,024,289 个 IPA 编码，支持五个地区；只提供词条转换，不提供下一词预测。数据来源声明保存在打包的 lexicon.manifest.json 与 licenses/IPA-Engine-THIRD_PARTY_NOTICES.json。这些是内置基线，不表示上游最新版；活动版本以客户端资源页为准。

## Release 获取与更新渠道

客户端从 `PantingEYEs/IPA_Engine` 的 GitHub Releases API 分页读取已发布版本，不再从固定分支的 `board-update.json` 发现更新。IPA Engine 管理页新增 **Allow Pre-release updates**，默认关闭；关闭时排除所有 Pre-release，开启时正式版与预发布版共同参与语义版本比较，始终排除 Draft。若渠道没有可用发布，明确显示无可用版本；网络／限流／清单错误不会被当作“已是最新版”。

开关独立持久化，不改变引擎启用状态或已安装资源。关闭开关不会回退已安装的预发布引擎；更新动作也不安装相同或更旧的组件版本。引擎与词典版本分别比较，词典版本未变化时不会重新下载。当前正式发布为引擎 `v0.0.4`，其词典仍为 `v0.0.1-dev-lexicon`。开关切换后清空旧的远程版本显示，需要再次 Check updates。

更新排序使用 SemVer（允许 `v` 前缀），不依赖列表首项、发布日期或字符串字典顺序。发布列表支持分页；超过客户端查询上限会报告错误，不能把不完整列表当成最终结果。无法识别的组件版本不尝试盲目替换已安装资源。

## 开发侧提供的 Release 清单与附件

每个发布的顶层 `client.json` 使用 `manifestType=ipa-engine.release`、`schemaVersion=1`，记录 `releaseVersion`、`tag`、`prerelease`、独立 `engineVersion`／`dictionaryVersion`，以及 `abi`、`minSdk`、`entryPoint`、`dictionaryFormat`、`dictionaryIndexBits` 和 `dictionaryKeys`。发布 tag／状态必须与 GitHub API 记录一致。

`assets.dex`、`assets.native`、`assets.dictionary` 每项提供精确的 `name`、`url`、`bytes` 和 `sha256`。客户端核对这些描述与同一 Release 的实际附件，再下载对应的独立文件；当前不依赖 AAR、历史完整客户端 ZIP 或 `board-update.json`。开发侧提供的引擎专用 ZIP 可供其他安装方式使用，本客户端直接获取裸 DEX／SO，以继续保持引擎、词典独立更新。

- 下载入口必须属于同仓库、同 tag 的 Release Assets，不拼接分支路径、不接受清单中的任意外部 URL。允许 GitHub 附件服务的 HTTPS 重定向，但限定其官方主机与重定向次数，不携带认证令牌。
- DEX、SO、词典的上限分别为 4 MB、8 MB、40 MB；要求 SHA-256、实际大小和清单一致。当前支持 ARM64、GraphEngine 既有入口、IPADWG01 v2／24 位词典。最低引擎 API 声明允许 26–36，IPA Board 自身最低适配 API 仍为 35。
- 引擎代码和配套 SO 必须同批发布；单独更新词典时仍使用已安装引擎做隔离验证。版本标签与组件内容应保持不可覆盖，修订使用新版本。
- 来源与许可声明应随资源提供。`assets.dictionaryManifest` 和 Release 其他说明保留开发侧数据声明；本次没有扩充客户端词典编译或模型训练功能。

内置旧包仍沿用本地 `client.json` 的历史字段作为首次离线部署格式；已有安装无需迁移或清数据。`tools/package_ipa_client.py` 是历史 AAR 提取工具，不再代表远程发现合同。旧分支和历史 Release 的 `board-update.json` 可保留服务旧客户端，新客户端不读取它。

## 切换与失败行为

资源存储在应用私有 no_backup 目录。更新文件先校验长度与 SHA-256，随后在非导出的 `:ipa_check` 进程中，用待切换资源打开引擎、配置并验证查询接口。下载失败、校验失败、ABI 不支持、API 不兼容、native 崩溃或验证超时均保留当前活动资源。

只有验证通过才原子写入活动资源指针，通知输入法清空旧候选并重新查询；旧 generation 的晚到回复不会进入候选栏。词典成功替换后删除旧词典；已打开的 mmap 可以结束当前查询。引擎代码目录不可覆盖，避免已加载 JNI 引用被改变。关闭管理页不会中断正在进行的发布与清理。

关闭 IPA 开关或进程加载失败时，其他候选引擎及原文上屏继续工作。此实验集成目前只验证 ARM64；x86_64 不运行该上游包。


## 应用内词汇反查 IPA

Engine Management → Manage IPA engine and dictionary → **IPA Engine** 页面最下方新增 **Word to IPA**。输入完整词条后点击 **Lookup**，结果按 `en-US`、`en-GB`、`zh-CN`、`zh-TW`、`ja-JP` 分组，保留同一词的多条读音；标为 **Derived** 的记录来自词典中的派生标志。结果文本可选中复制。页面按键盘占用区域调整滚动视口，键盘弹出时仍可上下滚动查看搜索框和结果，收起键盘后恢复可用区域。

反查独立于输入法的 IPA 开关，不会开启引擎或访问网络。使用当前活动 `.ipad`，只做 NFC 与完整词条精确匹配，不自动转换大小写或繁简、不分句、不猜测读音。例如 `学校` 与 `學校` 是不同查询；词典中的开发期读音不代表已经语言学校验。

后台只读格式适配器支持 `IPADWG01` v2（24 位）和 v3（32 位），不加载引擎 DEX／JNI，不需要开发侧 SQLite、源词库或客户端编译词典。当前安全限制为文件小于 40 MB、输入最多 2000 个 UTF-16 单元、IPA 路径最多 64 个码点；单次显示上限 500 条，多出的结果会明确标注，不宣称完整。无词条、未安装资源、未知格式与读取失败分别显示不同状态。

不建立持久反查缓存：每次查询读取活动词典，成功更新后新查询使用新 generation；旧查询的晚到结果不会覆盖新状态。更新失败仍沿用原资源。编辑查询、资源变更或退出页面会取消旧任务；现有不可覆盖资源目录及 mmap 机制允许已打开的旧文件安全结束，但旧 generation 的结果不会展示。

格式参考为 [IPA Engine 的压缩词典读取定义](https://github.com/PantingEYEs/IPA_Engine/blob/HEAD/source/cli_tests/scripts/compiled_lexicon.py)，客户端实现位于 `ipa/IpaDictionaryLookup.kt`，页面异步与生命周期处理位于 `ipa/IpaLookupPanel.kt`。旧引擎没有反查 API 不影响这个工具；未来不兼容的词典格式需要扩展客户端读取器，不能被误报成“无词条”。
