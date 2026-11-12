# Rime／OpenCC 官方源码接入

本分支以 [IPA_Engine `chinese` 固定提交 `073aabe`](https://github.com/PantingEYEs/IPA_Engine/tree/073aabe899fdcbdd195e2e9199fb25c43a574f47) 的 AAR 和兼容资源替换旧 Trime 组合库。正常应用构建使用已锁定产物，不下载源码或在手机上编译。

## 固定来源与版本

| 层级 | 固定值 |
| --- | --- |
| librime | 1.17.0，官方 `33e78140250125871856cdc5b42ddc6a5fcd3cd4` |
| OpenCC 运行库 | 1.4.2，官方 `025f371dc76b598d77384fbdab90c937471844d8` |
| OpenCC 兼容数据 | 官方 1.2.0 的 ST/TS 字符与词组数据；转换词汇不随运行库升级 |
| 独立构建提交 | `073aabe899fdcbdd195e2e9199fb25c43a574f47` |
| 原生 build ID | `08a2c62169dfdbea884ac4a3210ead026d1552a43149e5d196f8584e36ab7665` |
| 接口／候选包 | JNI ABI 1／候选包 v3 |
| Android 产物 | `arm64-v8a`、`x86_64`；NDK r29；16KB LOAD／RELRO；二进制链接基线 API 26 |

上游源码锁、资源锁和产物摘要随应用保存于 `assets/licenses/rime-upstream/`；应用构建检查入口为 `tools/verify_engine_assets.py`，交付文件以 `tools/engine-assets.lock.json` 为准。最低适配 API 仍按应用政策决定，二进制 API 26 链接基线不是整机兼容承诺。

## 单一原生接口与数据生命周期

AAR 只提供 `org.ipaboard.rime.RimeNative` 和 `libipa_rime.so`。librime、OpenCC、同一份 Marisa、LevelDB、yaml-cpp 与静态 C++ 运行库在同一链接单元内；应用不再加载 JNA、`librime_jni.so` 或 `libipa_rime_batch.so`，也不需要另装独立 OpenCC 共享库。

六个 JNI 操作负责接口版本、构建身份、打开、查询、转换与关闭。Java/Kotlin 只传受限 UTF-8 字节与不透明句柄；C++ 对象、Rime 函数表及内存所有权不跨边界。运行时身份必须与 `RimeBundle` 的固定版本和 build ID 一致，否则按中文引擎不可用回退，不能拿不匹配的运行库尝试解释内部对象。

原生每次只输入一次并只读枚举候选，返回有界候选包和真实消耗范围，不通过逐候选选词／重放估算范围。应用继续执行拼音／注音编码、精确边界映射、部分候选拼装、上下文续写及异步过期结果拒绝。损坏包、无法证明的覆盖范围和失败会话保留原文回退；新接口不是新的候选排序算法。

兼容资源直接来自固定官方仓库，不再从 Trime APK 提取。朙月、地球拼音、注音及 essay 资源保留原有数据；方案 overlay 继续禁用用户学习与补全。两份 OpenCC 文本配置显式使用 `match_policy: short_circuit`，等价于旧隐式默认。资源以新的内容身份部署，旧部署标记不能让新版本继续使用旧文件。既有方案、声调键、页绑定和用户界面设置不因引擎替换而初始化。

## Engine Management

中文转换显示已打包的 librime、OpenCC、独立构建提交和 build ID；简繁转换条目另标注 OpenCC 数据版本。中文续写显示固定 essay 来源，避免把语料库误称为另一个原生引擎版本。

展开类目查询官方 librime/OpenCC release，以及 IPA_Engine 的 chinese 源码分支提交。最新来源信息与当前版本并列；源码分支可能只更新文档，查询结果不表示可安装更新或二进制兼容。网络或元数据错误只显示 unavailable，不覆盖已安装信息，不影响离线输入。

保留现有朙月／地球拼音选择和可选声调替代键。此次不增加无明确需求的配置开关；用户词典、区域转换方案及任意外部资源导入仍需各自的持久化、许可与失败处理设计。

## 许可与历史边界

原始通知按上游归档结构保存在 `assets/licenses/rime-upstream/Rime/` 与 `OpenCC/`，AAR 中也包含对应通知。原生构建使用实际组成部分的 BSD、MIT、Apache-2.0、Boost 等许可；Marisa 选择 BSD 选项，原始双许可文字完整保留。

中文方案、词典及 essay 的 LGPL v3、随附 GPL v3 文本和适用 CC-BY-SA／CC-CEDICT 归属继续保留。旧 Trime/JNA 专项通知不再代表当前运行库；历史说明与旧外置桥接文档在 [归档](archive/rime-trime-integration.md)。新的独立构建脚本及 JNI 适配代码保留 PantingEYEs 版权，尚未指定项目许可。此接入不宣称 IPA Board 整体协议已经迁移，#54 仍需核验其他代码和依赖。

## 验证范围

来源分支的原生、资源与临时应用验证见 [其记录](https://github.com/PantingEYEs/IPA_Engine/tree/073aabe899fdcbdd195e2e9199fb25c43a574f47/Rime/validation/README.md)；这不能替代当前应用分支的专项。当前接入的实际主机、Engine Management、原生和多语言混排结果以 [测试记录](testing.md) 为准。设备操作必须走备份／恢复事务。仅按实际执行的 ABI/API/16KB 环境报告通过，不外推 ARM64 真机、4KB 设备、release/AAB 或完整编辑器矩阵。
