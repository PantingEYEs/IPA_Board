# LatinIME 源码构建接入（#52）

本次在 #52 分支接入 `IPA_Engine/english` 固定构建
[`ad79fe388ced`](https://github.com/PantingEYEs/IPA_Engine/tree/ad79fe388ced2d9944d81a159f72b5ef6a972fce)，
底层为 HeliBoard 4.1 的 `9f5bb635c2e8609dcd95dc7506c0c58fba82a52c`。
AAR 与现成词典继续随 APK 发布；不要求手机编译、下载后使用或导入完整 HeliBoard。

## 构建与来源

- `arm64-v8a`、`x86_64` 均由固定源码与 NDK r29 自主构建，采用静态 C++ 运行库；源码锁、构建参数、链接输入、产物哈希及原始声明位于 [tools/latinime](../tools/latinime/README.md)。
- 构建身份为 `6ddfef54ed2c518f5435994eccb14cb2b19897da8ee826c179c8b124aac03258`。
- 独立构建中的 82 份编译单元和 139 份可用头文件有原始 Apache-2.0 声明，逐文件证据见 [源码清单](../tools/latinime/source-inventory/native-files.json)。
- 新接口直接调用经过审阅的原生 API；旧 `com.android.inputmethod` Java 注册类及 `libjni_latinime.so` 已整体移除，没有通过删除旧文件的 GPL 标识改变来源。
- 新 JNI／构建代码保留开发侧版权及尚未选定项目协议的声明；本次不指定 IPA Board 新协议。

[旧 APK 提取方式](archive/latinime-heliboard-apk-integration.md)只保留历史事实，不再作为当前库的获取或注册方式。

## 词典独立保留

现有 `main_en-US.dict` 的 SHA-256 保持
`bd950ef4b57655120eee65cee62a5d216a63f721d9a8bb759ce2022437840443`，
大小 2,956,881 字节；词表版本 54 与二进制格式 202 是两个不同概念。
不重新编译、不替换成另一份字典。其 OpenBoard／HeliBoard 来源链、对应词表与许可证据在
[词典来源锁](../tools/latinime/dictionary-sources.lock.json)。

词典不能套用原生核心的 Apache-2.0 声明：证据记录其发布仓库的 GPL-3.0 及独立文件许可 `NOASSERTION`。
保留原始许可与对应词表材料，完整许可结论继续由 #54 核验；本次接入不宣称英文资源或整个项目已脱离 GPL。

新部署目录按词典身份隔离，旧部署目录与用户设置不清除。打开前校验缓存文件全长与 SHA-256；
损坏时从随包原始字节写临时文件，校验并设为只读后原子替换；失败由现有诊断阶段及引擎降级处理。
原生 owner 存活时不改写已映射词典。重复查询不写学习记录、私人词典或词典更新文件。

## 运行接口与候选行为

`org.ipaboard.latinime.LatinImeNative` 由匹配 AAR 提供，仅有六个原生入口：
`abiVersion`、`identity`、`open`、`query`、`probability`、`close`。
初始化先核对 ABI、结果包版本、完整上游修订、词典哈希及 build ID；错配不会仅因文件名相同而继续加载。

返回值是自有 `LATN v1` 字节包；应用严格检查完整长度、数量、条目边界、连续文本范围、UTF-8、
码点上限及尾随数据，拒绝损坏输出后沿用现有错误回退。详细格式见
[固定接口说明](../tools/latinime/bridge/README.md)。

保留大小写和撇号输入、分数降序及同分规则、补全／纠错／预测独立开关、中文上下文的常用词回退、
上下文亲和度、混输字面片段、候选版本隔离和原文输入。队列顺序不作为来源排名，
不增加手势输入、个人学习或默认设置变化。Engine Management 分开展示当前打包身份与最新来源元数据，
`english` 分支最新提交不表示已提供客户端可安装更新。

服务保留单个进程级 owner 以供重新绑定复用；临时／测试 owner 显式关闭，应用包装层重复关闭安全。
底层使用受锁保护的不复用 owner ID；关闭、过期句柄和并发查询不能直接访问已释放内存。

## 验证边界

常规主机检查包括 `python3 tools/verify_latinime.py`、资源校验、相关 JVM／工具测试、
debug／AndroidTest 编译及 lint。源码构建工程自身的既有验证与本应用接入回归分开记录，
不将其隔离测试副本当作当前工作树已经通过。

设备入口为 `python3 tools/regression.py run --suite latinime-upstream --serial DEVICE_SERIAL --no-build`。
只定位原生包装时使用 `latinime-runtime`；实际输入法与候选点击使用 `english-ime`。
所有安装／instrumentation 通过外部备份、恢复校验与删除备份，使用合成输入和专用模拟器。
静态双 ABI、APK ZIP 对齐、真实进程页大小与实际输入结果分别验收。
当前执行结果与未测范围见 [测试记录](testing.md)，不从一台设备外推所有 API、ABI、编辑器或 release／AAB。

本应用当前主机检查与 70 项设备专项通过；实际 IME 的两项用例在键盘显示前超时，补跑仍失败。维护者决定保留记录并另行调查，不把整套标为通过；具体报告及恢复结果见 [2026-11-16 测试记录](testing.md#2026-11-16-latinime-源码构建接入52)。

维护者于 2026-11-16 报告使用中未发现异常，接受上述自动化缺口并授权合并；该人工验收不改写自动化失败结果。

实现提交 `4ca12b7` 已经 [PR #61](https://github.com/PantingEYEs/IPA_Board/pull/61) 合入 `0.0.4-dev`，merge commit 为 `192f0bd`；#52 已关闭，开发分支已清理。词典许可及整体项目协议继续由 #54 核验。
