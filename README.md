# IPA Board

Android 音标键盘原型，使用 Kotlin 和原生 View，最低 API 35。

## 使用

1. 安装应用，在 Android 系统设置中启用 IPA Board 并选择为输入法。
2. 打开 IPA Board 应用，点击页面中部偏上的键盘预览中的任意按键。
3. 在编辑对话框的 Key type 中选择 Text 或所需功能键。Text 支持多字符、组合音标、空格和换行；清空后保存即取消映射。功能键自动显示对应标签。保存后立即同步到输入法。
4. 在其他应用的文本框中点击对应按键，即可输入文本或执行功能。
5. 点击 Export，在系统文件选择器中保存 JSON 配置。点击 Import 可恢复配置，导入成功后自动启用。

默认键位尚未分配字符，显示 ∅。可粘贴字符来配置，也可从系统输入法切换入口切换到其他键盘输入。应用内编辑对话框点击保存后生效，取消不改变映射。

## 功能键

现有键位可设置为以下功能，无需重新创建布局：

| Key type | 功能 |
| --- | --- |
| Backspace | 删除选中文本或光标前的字符。 |
| Arrow Left / Right / Up / Down | 移动光标，启用 Shift 后扩展选区。 |
| Shift / Uppercase | 开关大写和 Shift 导航。启用后文本键显示并输入大写；再次点击或结束当前输入时关闭。 |
| Ctrl | 为下一个非修饰键启用 Ctrl，随后自动关闭；再次点击可取消。 |
| Enter | 执行文本框的输入动作；多行文本框中换行。 |
| Tab | 发送 Tab，焦点切换行为由接收应用决定。 |
| Home / End | 移动至行首或行尾，启用 Shift 后扩展选区。 |

启用的 Shift 和 Ctrl 会在键盘中高亮。Ctrl+A、Ctrl+C、Ctrl+X、Ctrl+V 分别请求全选、复制、剪切、粘贴；其他支持的单个 ASCII 字符通过 Ctrl 组合按键事件发送。快捷键及光标移动的具体支持由接收应用决定，Ctrl 不用于多字符文本或 IPA 组合音标。

## 配置

导出配置含 `version: 2`、`layout` 和 `appearance`。布局包含名称、各行的 `heightWeight`、各键的 `widthWeight`、`text` 与 `action`；外观包含 `backgroundColor`、`symbolColor` 与 `heightDp`。不包含设备或账户数据。

`action` 可取 `text`、`backspace`、`left`、`right`、`up`、`down`、`shift`、`ctrl`、`enter`、`tab`、`home` 或 `end`。例如 `{"widthWeight": 1, "text": "", "action": "backspace"}` 表示退格键。导出和重新导入会保留功能键配置。

旧版 `version: 1` 配置以及只有 `name` 和 `rows` 的布局文件仍可导入：缺少 `action` 的键按文本键处理，缺少 `text` 的键视为未分配。未知的 `action` 会被拒绝。导入不会覆盖已有布局，会创建新文件。配置最大 1 MB，支持 1–20 行、每行 1–40 个键、每键最多 1000 个 UTF-16 代码单元；比例必须为有限正数。

目前通过修改现有键位添加功能键，尚未实现增删行列的布局编辑器。

## 验证

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest
# 连接 API 35+ 设备或模拟器后运行配置序列化及兼容测试：
./gradlew connectedDebugAndroidTest
```

手工验证：编辑任意键为 `t͡ʃ` → 切换到文本框输入 → 导出配置 → 修改该键 → 导入原配置 → 确认映射、颜色、高度恢复；同时检查取消编辑、空映射及旋转屏幕后导出。将其他键设为 Backspace、方向键、Shift、Ctrl 和字符 `a`/`c`/`v`，验证删除、移动与选择、大写、Ctrl+A/C/V，并导出后重新导入确认功能键保留。
