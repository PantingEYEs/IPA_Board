# 原生引擎接入与资源来源

当前实现是离线、逐词混输版本。IME 工具栏和候选栏是独立原生 View，不参与 KeyboardLayout JSON、应用布局预览、颜色/尺寸配置。键盘高度设置只改变下面的按键区。

## 固定资源

所有文件已随源码保存，正常 Gradle 构建不下载引擎。运行 `python3 tools/verify_engine_assets.py` 检查资源 SHA-256；`tools/engine-assets.lock.json` 记录发布包地址、原始包哈希及最终资源哈希。更换引擎或词库时必须重新执行真实候选测试，并更新资源目录版本，避免读取旧部署结果。

| 组件 | 精确来源 | 本项目接入方式 |
| --- | --- | --- |
| Mozc JNI + mozc.data | [Libre Japanese Input v0.1.2](https://github.com/elizagamedev/android-libre-japanese-input/tree/v0.1.2)，其 Mozc revision 为 `ddd9730b068387631e3b4d212314ef0ed93befe0` | 从原始 APK 取出 arm64-v8a/x86_64 的 libmozc.so 和 mozc.data，修改上游 Java JNI 加载器以移除前端依赖；JNI 方法签名保持原样 |
| Rime + OpenCC | [Trime v3.3.12](https://github.com/osfans/trime/tree/v3.3.12)，librime submodule `33e78140250125871856cdc5b42ddc6a5fcd3cd4` | 取对应 ABI 的 librime_jni.so，经 JNA 调用其中公开的 Rime C API 和 OpenCC C API，不启动 Trime Java 前端 |
| 中文资源 | 上述 Trime APK 的 assets/shared | 保留拼音、词典、OpenCC 数据；本项目修改 t2s.json 使用文本词典，并增加禁用学习的 schema patch |
| 英文词库 | [easy_en `54a4a07289412efc54134092c0d945f895a71ed3`](https://github.com/BlindingDark/rime-easy-en/tree/54a4a07289412efc54134092c0d945f895a71ed3) | 使用 easy_en.dict.yaml，本项目提供不依赖 Lua 的最小 schema |
| JNA | [5.17.0](https://github.com/java-native-access/jna/tree/5.17.0) | 原始 Android AAR；ABI 过滤只保留 arm64-v8a/x86_64 |

Rime function table顺序来自 librime 1.14.0 的 rime_api.h。适配器限定 64 位 ABI，并在访问函数前检查表大小。Mozc 的小型 wire reader 对应上述 revision 的 commands.proto / candidates.proto，支持该协议使用的 group、嵌套消息、未知字段跳过及输入长度限制。

Rime 的原生候选可能只覆盖原串前缀。本版本在禁用用户学习的 session 中重放、选择并检查实际 commit 和剩余编码，只把完整消费原串的结果交给 UI；这比直接展示全部返回值更慢，但不会把未消费编码丢掉。

## 生命周期与隐私

`:rime` 和 `:mozc` 为两个独立、非导出的进程。每个进程串行执行原生调用，查询按 revision 合并；主进程丢弃过期结果，点击候选也校验视图快照 revision。原生崩溃时剩余引擎和原样上屏仍可使用。

用户数据与部署资源放在 noBackupFilesDir。首版不写个人输入学习记录；Rime 禁用 user_dict，Mozc 使用 incognito 配置。上述限制仅针对输入引擎。当前应用具有联网权限，用于手动更新 Emoji 目录；输入法会在系统允许读取时保存本地剪贴板历史（包括敏感标记内容），未固定项最多 20 条。应用启用了系统备份，现有规则尚未排除剪贴板历史。短信自动复制可选，Broadcast 模式需短信接收权限。

## 构建、检查与当前限制

```sh
python3 tools/verify_engine_assets.py
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest
# Gradle 的设备测试依赖可用时：
./gradlew connectedDebugAndroidTest
# 也可直接安装两个 APK 后执行：
adb shell am instrument -w com.example.ipa_board.test/androidx.test.runner.AndroidJUnitRunner
```

- 首次 Rime 部署需编译词典，界面保持可用并显示加载状态；后续复用部署结果。
- 候选当前为有界列表：Rime 每通道检查前 40 个原生候选，Mozc 最多 60 个。展开显示这一批结果，尚无滚动到末尾继续向引擎分页。
- 排序采用来源内排名、原文匹配等启发式特征；不是经过跨语言语料校准的概率。尚无持久化个性学习，也没有无分隔整串跨语言分词。
- Rime 的两个二进制为 16KB ELF 对齐；当前 Mozc 上游发布库为 **4KB 对齐**。此次真机验证不等于 16KB 页设备兼容验证；面向此类设备发布前，应按上游 source revision 重编译 Mozc 并验证。不要通过修改 ELF 头伪造兼容性。
- 当前使用 Trime 发布的组合 JNI 库，其中包含 GPL 代码，不能仅将其视为 BSD 的 librime。许可证文本和原始声明在 app/src/main/assets/licenses。对外分发需按实际组合依赖提供相应源码/许可材料；若要求宽松许可发行，需独立构建纯 librime/OpenCC 并替换该二进制。本次未发布软件，也未给项目自有代码指定新许可证。

上游原生构建说明：[Trime 源码](https://github.com/osfans/trime/tree/v3.3.12)、[Libre Japanese Input 构建说明](https://github.com/elizagamedev/android-libre-japanese-input/tree/v0.1.2#building-the-mozc-jni-library-and-dataset)。

## 本次验证记录（2026-09-28）

- `testDebugUnitTest`：16 项通过；`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过（lint 存在非阻断警告）。
- 已连接 ARM64 设备通过 ADB instrumentation 运行完整套件：47 项通过。
- 实际 IME 窗口验证：同一键盘页连续输入 `你好hello汉语漢語日本語`；切页保留 `nihao` 编码；候选展开屏蔽底层键盘；返回先关面板再隐藏键盘；剪贴板面板正常打开/关闭。测试使用空白测试编辑器，并在 finally 恢复原输入法与布局。
- 仅测试自身视图的折叠/展开 PNG 已检查，顶部与按键区保持独立、窗口高度一致。
- 原生资源 SHA-256 检查通过。未验证 x86_64 运行、16KB 页设备、跨应用大规模兼容性、概率校准或性能 P95 指标。
