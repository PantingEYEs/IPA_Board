# 文档索引

当前开发版本为 `0.0.4-dev`，最新正式版为 `v0.0.3`。0.0.4 计划从 `fbbb642` 开始，整理日期为 2026-10-25；功能进度以开发计划及当前说明为准，未完成目标不作为现有能力承诺，历史验证结果仍按对应版本和实际范围解释。正式附件以 GitHub Releases 为准。

## 使用与接入

| 文档 | 内容 |
| --- | --- |
| [项目说明](../README.md) | 安装要求、当前功能、管理入口与构建方式 |
| [更新日志](../CHANGELOG.md) | 开发中及已发布版本的变更 |
| [引擎接入与来源](engine-integration.md) | 已接入引擎、固定资源、开关、生命周期与限制 |
| [IPA 必要接口](engine-specification.md) | 引擎接受的操作/信息、候选格式与安全边界 |
| [IPA 资源更新](ipa-updates.md) | 固定发布位置、清单格式、引擎/预编译词典独立更新 |
| [语义排序](context-ranking.md) | 默认关闭的 E5、上下文处理、重排规则与验证范围 |
| [错误诊断](diagnostics.md) | 日志开关、固定事件、文件边界与剔除入口 |
| [引擎仪表盘](engine-dashboard.md) | ◷ 绑定、只读状态及当前功能边界 |
| [Emoji 目录与浏览](emoji-catalog.md) | Unicode 目录、连续分区、分类导航与更新 |
| [浮窗多功能操作](popup-multifunctional-actions.md) | 按键双模式、图形编辑、撤回与运行时手势 |

## 开发与验证

- [0.0.4 开发计划](0.0.4-development-plan.md)：本版范围、分期目标与实施前待确认事项。
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
- [0.0.1 Git 拓扑修复记录](archive/git-topology-repair-plan.md)：保留历史引用，不作为当前操作指南。
