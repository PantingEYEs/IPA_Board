# 引擎仪表盘（Issue #8）

在 Keyboard Management 中编辑任意按键，将功能设为 **Engine Dashboard**，默认键面符号为 **◷**；也可绑定到长按、滑动或 Shift/Ctrl 快捷映射。默认布局及工具栏不添加仪表盘入口。

按下该键后，键盘按键区替换为可竖向滚动的只读列表。列出 Engine Management 中所有已接入的功能，包括关闭项；共享 Rime、Mozc 或 LatinIME 的功能显示同一后端状态。顶部可打开 IPA Board 主设置，右侧 Return 或系统返回键回到键盘。单纯打开/关闭面板不提交或清空预输入，不初始化、重试或释放引擎。

每项区分配置开关与运行状态：

- **Enabled / Disabled**：该功能的用户配置，关闭不代表原生实例已经释放。
- **Not initialized / Initializing / Ready / Processing / Unavailable**：由持有真实实例的服务上报，独立于候选内容和 Debug diagnostics 开关。
- **Connecting**：正在连接服务，不能据此判定初始化完成。
- **Unknown**：当前没有可信连接，无法确认实例是否仍驻留；不沿用旧进程的“就绪”状态。
- **On demand; no persistent engine instance**：排序、分段、计算器、短信解析等内置按需函数没有独立常驻实例。这不是“正在执行”或性能检测结果。

状态订阅复用正常输入已经建立的连接，不为打开仪表盘新增绑定、请求候选或唤醒已关闭引擎。服务发送无输入内容的状态消息，包含实例代次、递增序号及固定状态值；旧连接、旧实例或乱序事件不能覆盖新状态。服务解绑后显示 Unknown，尤其不能将 Rime 等进程级缓存误报为已卸载。

打开主设置会离开当前编辑器，正常 Android 输入法生命周期可能随之暂停或释放服务；这与只在键盘内部切换仪表盘不同。

本期不含引擎控制、性能/资源统计或详细日志；不实现 Issue #4 的远程模式。后续功能可复用同一只读状态源。

验证入口：先执行主机检查，再通过 `python3 tools/regression.py run --suite dashboard --serial DEVICE_SERIAL --no-build` 执行有备份与回滚保护的界面/IPC/实际键盘用例。完整集成验证使用 `full`。不得直接安装或调用 instrumentation。

## 验证记录

2026-10-20，基于 `af4ff70` 的 #8 工作树，debug，专用 Android 15 / API 35 / ARM64 只读模拟器：

- 主机编译通过；114 个 JVM 测试、47 个测试工具用例、93 项引擎资源哈希及 E5 重组校验通过；lint 无错误，269 项警告。未验证其他 API/ABI 或签名 release。
- `dashboard` 的 5 个类共 22 个断言全部通过，包含真实 LatinIME 状态、独立 IPC、配置兼容、真实 IME 预输入保留及设置跳转。报告：`app/build/reports/regression/bdc5e102-978d-48a4-9ad1-321b62bc2b5d/`。
- 自动收尾未通过临时运行环境校验，因此上述报告的整体结果仍为失败。随后执行脚本给出的 `restore`，确认原软件与私有数据恢复且备份删除，`pending` 为空，再关闭只读模拟器丢弃临时环境。没有绕过校验或操作实体手机。

首次 #8 验收时，用户确认暂缓以下问题。后续已修复并通过完整回归，见 [2026-10-20 回归失败修复](testing.md#2026-10-20-回归失败修复)；原用例及历史失败报告保留：

| 待处理问题 | 涉及用例 |
| --- | --- |
| 布局操作已移入 Page Settings 分页，旧测试仍在默认 Group Settings 分页查找按钮 | LayoutManagementTest |
| 预览区不在 ScrollView 内，旧 scrollTo 定位失败 | KeyEditorTest、EmojiFeatureTest |
| Delete 同时匹配弹窗标题与按钮 | PageGroupUiTest |
| 生命周期 trace 的阶段配置未开启文件记录，与测试预期不符 | DiagnosticsContractTest |
| 真实 IME 中全半角、Emoji、触摸长按与页组切换存在点击/窗口等待失败，原因待进一步隔离 | ImeEndToEndTest、PageGroupImeTest |
| 回归工具临时运行环境恢复校验失败，需单独定位具体状态差异 | tools/regression.py |

此前完整回归执行 193 个断言，176 通过、17 失败，不能作为完整通过的依据；其中本次新增配置用例的源布局包装读取错误已修正，并在上述专项中通过。完整失败报告：`app/build/reports/regression/4534bd7f-ff13-47b4-9033-400adc40a1d2/`。该轮保留的软件备份也已通过显式恢复验证并删除。
