# IPA Board

Android 音标键盘原型，使用 Kotlin 和原生 View，最低 API 35。

## 使用

1. 安装应用，在 Android 系统设置中启用 IPA Board 并选择为输入法。
2. 打开 IPA Board 应用，点击顶部预览中的任意按键。
3. 输入该键要输出的文本并保存。支持多字符、组合音标、空格和换行；清空后保存即取消映射。修改立即保存并同步到输入法。
4. 在其他应用的文本框中点击对应按键，即可输入映射文本。
5. 点击 Export，在系统文件选择器中保存 JSON 配置。点击 Import 可恢复配置，导入成功后自动启用。

默认键位尚未分配字符，显示 ∅。可粘贴字符来配置，也可从系统输入法切换入口切换到其他键盘输入。应用内编辑对话框点击保存后生效，取消不改变映射。

## 配置

导出配置含 `version: 1`、`layout` 和 `appearance`。布局包含名称、各行的 `heightWeight`、各键的 `widthWeight` 与 `text`；外观包含 `backgroundColor`、`symbolColor` 与 `heightDp`。不包含设备或账户数据。

旧版只有 `name` 和 `rows` 的布局文件仍可导入，缺少 `text` 的键视为未分配。导入不会覆盖已有布局，会创建新文件。配置最大 1 MB，支持 1–20 行、每行 1–40 个键、每键最多 1000 个 UTF-16 代码单元；比例必须为有限正数。

当前按键仅支持提交文本，尚未实现退格、Shift 等功能键或增删行列的布局编辑器。

## 验证

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest
# 连接 API 35+ 设备或模拟器后运行配置序列化及兼容测试：
./gradlew connectedDebugAndroidTest
```

手工验证：编辑任意键为 `t͡ʃ` → 切换到文本框输入 → 导出配置 → 修改该键 → 导入原配置 → 确认映射、颜色、高度恢复；同时检查取消编辑、空映射及旋转屏幕后导出。
