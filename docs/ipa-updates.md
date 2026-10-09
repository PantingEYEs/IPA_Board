# IPA 引擎与词典独立更新

客户端入口为 Engine Management → Manage IPA engine and dictionary。检查更新只读取版本；更新引擎只替换 Java DEX 与 JNI 库；更新词典只下载开发侧预先编译的 `.ipad`，保持当前引擎。客户端没有源词库编译功能，也没有输入学习上传。

当前包来自 IPA_Engine 的 `v0.0.1-dev` ARM64 AAR，源发布提交为 `53eed9518f932934a28aceb4e03a453d545f80b8`；客户端更新产物提交为 `af73155fbe7fd819037f2e63fe6d75cd24c2d4a4`，清单已发布于开发分支。词典是 `IPADWG01` v2 的压缩图与词条二进制，包含 1,024,289 个 IPA 编码，支持五个地区；只提供词条转换，不提供下一词预测。数据来源声明保存在打包的 lexicon.manifest.json 与 licenses/IPA-Engine-THIRD_PARTY_NOTICES.json。

## 开发侧提供的文件

客户端读取 `PantingEYEs/IPA_Engine` 的 `codex/ipa-engine-v0.0.1-dev` 分支。直接读取分支上的 `board-update.json`，随后从清单指定的 artifactCommit 下载文件，避免版本检查与下载时资源混用，也不依赖有公共限流的 GitHub REST API。

`board-update.json` 为 UTF-8 JSON，格式示例见 [内置 client.json](../app/src/main/assets/engines/ipa/bundled/client.json)。必需字段：

| 字段 | 内容 |
| --- | --- |
| artifactCommit | 产物已发布提交的 40 位小写 SHA；只在远程 board-update.json 必需 |
| schemaVersion / entryPoint | `1` / `com.ipaengine.graph.GraphEngine` |
| engineVersion / dictionaryVersion | 分别显示的版本字符串，长度 1–100；词典版本独立变更 |
| abi / minSdk | 当前为 `arm64-v8a` / `26` |
| dictionaryFormat / dictionaryKeys | 当前为 `2` / IPA 编码数，1–2,000,000 |
| dexPath / nativePath / dictionaryPath | 同仓库内相对路径；不允许外部 URL、绝对路径或 `..` |
| dexBytes / nativeBytes / dictionaryBytes | 实际文件长度，上限分别为 4 MB、8 MB、40 MB |
| dexSha256 / nativeSha256 / dictionarySha256 | 对应文件的 64 位小写 SHA-256 |

引擎文件为 `classes.dex` 与 `libipa_graph_android.so`，调用合同保持 GraphEngine 的构造、configure、acceptsInput、getSegmentPattern、queryDetailed、close 及现有公开字段。候选需符合应用已有文本合同，开发调试包返回畸形值时被丢弃。

**单独发布新词典**：在开发侧编译与验证 `.ipad`，放入新的不可覆盖目录；更新 dictionaryVersion、dictionaryPath、dictionaryBytes、dictionarySha256、dictionaryKeys。保持引擎字段原样。新词典必须兼容用户已安装的旧引擎，客户端会用该旧引擎实际打开验证，不要求用户先更新引擎。

可用主机工具 `tools/package_ipa_client.py` 从 AAR 准备 DEX、JNI、词典和元数据；`--dictionary` 接受已经编译的替换二进制。工具不编译词条源数据。产物目录不得覆盖旧版本；新词典的许可与来源声明由开发侧随产物保留。完整目录先提交并发布；随后将 client.json 的内容复制为仓库根目录的 board-update.json，增加 artifactCommit 指向刚才的产物提交，再发布更新清单。清单与产物分两次提交，避免在提交内声明自身 SHA 的循环依赖。

## 切换与失败行为

资源存储在应用私有 no_backup 目录。更新文件先校验长度与 SHA-256，随后在非导出的 `:ipa_check` 进程中，用待切换资源打开引擎、配置并验证查询接口。下载失败、校验失败、ABI 不支持、API 不兼容、native 崩溃或验证超时均保留当前活动资源。

只有验证通过才原子写入活动资源指针，通知输入法清空旧候选并重新查询；旧 generation 的晚到回复不会进入候选栏。词典成功替换后删除旧词典；已打开的 mmap 可以结束当前查询。引擎代码目录不可覆盖，避免已加载 JNI 引用被改变。关闭管理页不会中断正在进行的发布与清理。

关闭 IPA 开关或进程加载失败时，其他候选引擎及原文上屏继续工作。此实验集成目前只验证 ARM64；x86_64 不运行该上游包。
