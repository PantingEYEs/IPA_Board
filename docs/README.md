# 文档索引

当前开发版本为 `0.0.4-dev`，最新正式版为 `v0.0.3`。0.0.4 计划从 `fbbb642` 开始，首次整理日期为 2026-10-25，源码构建与许可迁移计划更新于 2026-10-27；功能进度以开发计划及当前说明为准，未完成目标不作为现有能力承诺，历史验证结果仍按对应版本和实际范围解释。正式附件以 GitHub Releases 为准。

## 使用与接入

| 文档 | 内容 |
| --- | --- |
| [项目说明](../README.md) | 安装要求、当前功能、管理入口与构建方式 |
| [更新日志](../CHANGELOG.md) | 开发中及已发布版本的变更 |
| [引擎接入与来源](engine-integration.md) | 已接入引擎、固定资源、开关、生命周期与限制 |
| [Rime／OpenCC 官方源码接入](rime-upstream.md) | 单一 JNI、固定构建与兼容中文数据、版本展示及许可边界 |
| [Mozc 官方源码接入](mozc-upstream.md) | 固定构建、OSS 词典、迁移与 16KB 验证边界 |
| [ONNX Runtime Extensions 官方源码接入](onnx-extensions-upstream.md) | SentencePiece 专用构建、E5 兼容资源、许可与 16KB 验证边界 |
| [地球拼音](terra-pinyin.md) | 方案选择、声调键、预编辑切换及独立部署 |
| [全半角互换](character-width.md) | 单次／锁定模式、选区操作、候选与字符边界 |
| [App 内蓝牙键盘](bluetooth-remote-keyboard.md) | 系统输入法、两种发送模式、连接生命周期与标准按键范围 |
| [IPA 必要接口](engine-specification.md) | 引擎接受的操作/信息、候选格式与安全边界 |
| [IPA 资源更新](ipa-updates.md) | Release 清单、正式／预发布渠道与引擎／预编译词典独立更新 |
| [语义排序](context-ranking.md) | 默认关闭的 E5、上下文处理、重排规则与验证范围 |
| [错误诊断](diagnostics.md) | 日志开关、固定事件、文件边界与剔除入口 |
| [引擎仪表盘](engine-dashboard.md) | ◷ 绑定、只读状态及当前功能边界 |
| [Emoji 目录与浏览](emoji-catalog.md) | Unicode 目录、连续分区、分类导航与更新 |
| [Shortcut 替代文字](shortcut-replacements.md) | 候选完整匹配、追加规则、选择行为与导出衔接 |
| [Shortcut 配置导入与导出](shortcut-export.md) | 四类配置、版本化 JSON、导入确认与取消／失败行为 |
| [键盘页组文件夹](page-group-transfer.md) | 整组导入／导出、重名副本、元数据及默认初始化 |
| [浮窗多功能操作](popup-multifunctional-actions.md) | 按键双模式、图形编辑、撤回与运行时手势 |

## 开发与验证

- [0.0.4 开发计划](0.0.4-development-plan.md)：本版范围、官方源码构建与下载拆分、GPL-3.0 迁移目标及待确定的新协议。
- [自适应按键区域开发计划](adaptive-key-regions-development-plan.md)：个人词典之后的 Lv.0/Lv.1、广义 Voronoi 命中、页面学习、数学与开源参考；尚未排期。
- [测试流程、套件选择与验证记录](testing.md)
- [仓库测试规则](../AGENTS.md)
- [英文 PR 写作规范](pull-request-guidelines.md)：草稿及描述副本存于被 Git 忽略的 `docs/pull-requests/`，不强制提交。

功能状态以当前代码和专题说明为准；计划中的目标不等于已交付能力。测试结果保留日期、实际源码范围与运行环境，后续通过不改写旧失败记录。内置引擎版本不是上游最新版，运行时更新后的活动版本以客户端显示为准。

## 发布与历史归档

- [v0.0.3 发布说明](releases/RELEASE_NOTES_0.0.3.md)：本版交付范围、安装声明与已知限制。
- 正式发布说明：[v0.0.2](releases/RELEASE_NOTES_0.0.2.md)、[v0.0.1](releases/RELEASE_NOTES_0.0.1.md)。它们描述对应版本，不随开发版功能变化改写。
- [0.0.3 开发计划及交付范围](archive/0.0.3-development-plan.md)：保留已交付摘要、未完成目标及后续建议。
- [Popup 多功能操作原始需求](archive/issue-20-popup-multifunctional-actions.md)：已实现的 #20 需求草稿，现行行为见上方专题文档。
- [0.0.2 开发计划与历史测试审计](archive/0.0.2-development-plan.md)
- [早期多语言混输设计](archive/ime-multilingual-plan.md)：包含已变更或未实现的设想，不作为现行接口规范。
- [旧 Mozc APK 接入来源](archive/mozc-libre-integration.md)：#50 迁移前的 Libre Japanese Input 来源、哈希与部署行为，不再作为当前资源入口。
- [旧 Extensions Maven 接入来源](archive/onnx-extensions-maven-integration.md)：#51 迁移前的 0.13.0 原包、哈希及 4KB 对齐限制，不再作为当前分词运行库来源。
- [0.0.1 Git 拓扑修复记录](archive/git-topology-repair-plan.md)：保留历史引用，不作为当前操作指南。

- [旧 Trime／JNA 与外置 Rime 候选桥接](archive/rime-trime-integration.md)：固定基线历史说明，不作为当前接入和测试指令。
