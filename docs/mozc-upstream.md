# Mozc 官方源码构建接入

Issue [#50](https://github.com/PantingEYEs/IPA_Board/issues/50) 将原来从 Libre Japanese Input APK 提取的运行库与词典替换为 Google Mozc 官方源码构建的一套产物。旧来源、哈希和当时的部署行为见[历史归档](archive/mozc-libre-integration.md)；它们不再是当前资源的来源或更新入口。

## 一套固定的引擎与词典

| 项目 | 当前来源／标识 |
| --- | --- |
| 官方源码 | `google/mozc`，提交 `a4541df29db443b49f81cf54665873b68fde9efa` |
| 独立构建仓库 | `PantingEYEs/IPA_Engine` 的 `japanese` 分支，提交 `76a612924530188bce6fa1c770b1e2e096e27ed8` |
| 词典 | 同次构建的 Mozc OSS dataset，运行时数据版本 `24.11.oss`；不是 Google 日语输入法专有词典 |
| 构建工具 | Linux x86_64、Bazel 9.0.2、Android NDK r29，链接基线 API 26 |
| ABI | `arm64-v8a`、`x86_64`，不包含 32 位库 |
| 原生补丁 | 保留上游入口，同时提供现有 `MozcJNI.initialize(): void` 兼容入口；固定 API 26 链接基线，不修改转换或预测算法 |

可复核的[构建输入](../tools/mozc/sources.lock.json)、[静态产物清单](../tools/mozc/artifacts.json)与[资源锁](../tools/engine-assets.lock.json)一起约束实际打包内容。数据与两个 ABI 的库必须作为一套更新，不能只替换某一文件。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `arm64-v8a/libmozc.so` | 5,478,432 | `d7cc97611b162e1577dc419811bc4a39b72a9af0be4f380b68597519e64d9b12` |
| `x86_64/libmozc.so` | 5,642,432 | `04831587ad807ba1e26144ae8fb34c67d40e6f9193294500f680a1be7616b3ac` |
| `mozc.data` | 18,955,930 | `26d8d5391e37dba926df23e6f01e90158dd755af111712ce84f065a700a852b5` |

完整构建脚本、工具及依赖锁定、补丁和产物位于[固定构建提交](https://github.com/PantingEYEs/IPA_Engine/tree/76a612924530188bce6fa1c770b1e2e096e27ed8)。其 `README.md`、`VALIDATION.md` 与 `licenses/evidence/` 分别记录重建步骤、独立运行验证与实际链接／词典输入。IPA Board 的普通 Gradle 构建使用已锁定产物，不重复下载源码或在手机编译。

## 主程序适配

词典使用内容哈希区分部署代际，避免原 `mozc-v0.1.2` 目录中已有的旧词典被新库复用。发布新词典前验证大小与 SHA-256，再将完整文件原子发布；初始化后核对运行时数据版本，不能仅凭 `onPostLoad` 返回成功就把上游最小回退引擎当成完整词典。更新不修改键盘页、偏好或其他用户配置。既有安装中的旧私有目录不再参与加载，但保留用于旧版回退，不自动删除其中可能存在的配置或未知文件；仓库中的旧资源及活动接入说明已替换或归档。

日文转换继续使用逐次查询的临时会话，开启 incognito 与 `NO_HISTORY`，不引入个人学习或持久转换会话。分段选词仍按原生首段读音映射到实际输入前缀，不按汉字数量或假定罗马字音节推算；含数字、标点等内容的混排继续保留未转换片段。现有修订号校验、全引擎关闭与原样输入回退保持独立。

词典续写的临时会话同时设置 `Request.zero_query_suggestion=true`（字段 1）和 `Request.mixed_conversion=true`（字段 2）。新版上游将零输入词典预测放在 mixed-conversion 分支中，沿用旧版只设置字段 1 的请求会缺少续写候选。此参数只用于预测请求，不把普通转换切换为另一套模式。参考固定源码的 [dictionary_predictor.cc](https://github.com/google/mozc/blob/a4541df29db443b49f81cf54665873b68fde9efa/src/prediction/dictionary_predictor.cc) 与[官方请求设置](https://github.com/google/mozc/blob/a4541df29db443b49f81cf54665873b68fde9efa/src/request/request_test_util.cc)。

Engine Management 显示官方源码修订和 OSS 数据版本，不再将 Libre Japanese Input 的应用版本当成当前引擎版本。内置来源标识与实际运行就绪状态是两回事；运行失败仍由现有诊断及引擎状态显示，不以管理页有版本号证明初始化成功。

日文转换和联想仍可独立开关。本次不增加不起作用的标点、空格宽度或假名输入设置：当前混排会把标点作为原文保留，全半角由输入层控制，假名输入则需要另行扩展输入与分段规则。将来有经过验证的替代词典或罗马字表时，再为完整的资源与输入合同提供选择入口；不将尚无可选资源的占位选项展示给用户。

## 16KB 与验证边界

固定构建清单记录两个 ABI 的全部 `PT_LOAD` 段按 16384 字节对齐，并检查偏移／地址同余、JNI 导出及动态依赖。仅依赖 Android 系统的 `liblog.so`、`libc.so`、`libm.so`、`libdl.so`；C++ 运行时静态链接，不另行引入 `libc++_shared.so`。链接基线 API 26 不等于 API 26 应用行为已验证。

独立构建仓库曾在关闭页大小兼容模式的 API 36／x86_64 模拟器上运行 6 项真实 16KB Mozc 专项，但当时使用隔离的 IPA Board 副本，并排除了旧 ONNX Runtime Extensions；该结果不代替本分支接入后的验证，也不证明完整应用的 16KB 兼容。当前 Extensions 0.13.0 仍有独立的 4KB 对齐问题，完整应用的发布限制必须继续保留。

本分支的实际主机检查、日文转换／续写／分段、多语言混排与设备恢复结果统一记录在[测试记录](testing.md)。不得把静态 ELF 检查、4KB 运行、16KB 运行或不同 ABI 的结果互相替代。自动设备检查必须使用受保护的回归入口，不能直接安装或运行 instrumentation。

## 许可与旧资料处理

当前 APK 保存[构建专用第三方声明](../app/src/main/assets/licenses/mozc/THIRD_PARTY_NOTICES.md)、完整 Mozc／词典／依赖许可证及[来源和哈希](../app/src/main/assets/licenses/mozc/licenses/sources.json)。这些文件是固定构建仓库的原样副本；其中对当时 IPA Board 尚未接入的说明属于构建时背景，不代表本分支状态。各组件保留原始许可，不据此改变项目整体协议。

旧 APK 下载记录及旧资源哈希移入历史归档；旧 `mozc.txt` 和 `mozc-credits.html` 从当前 assets 移除，分别由新构建随附的完整许可证、引擎／数据声明、依赖声明和官方参考 credits 取代。旧发布说明与有日期的历史测试记录继续保留当时事实，不改写成新引擎通过记录。新构建资料由 `japanese` 分支维护，未将缓存、构建日志或旧二进制重复带入 IPA Board。
