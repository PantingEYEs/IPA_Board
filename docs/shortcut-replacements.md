# Shortcut 替代文字

在 Shortcut Management 底部的 **Replacement Text** 区域使用 **Add replacement**，填写完整的 **Source candidate** 与 **Replacement text**。每行一条规则；同一源文字可以添加多行，允许目标完全相同。Edit 修改，Delete 删除，Up／Down 调整顺序；保存立即生效，取消编辑不写入。每个字段最多 1000 个 UTF-16 单元，保留空白、换行和 Unicode 文本，不做去重、繁简转换或大小写转换。

例如添加 `鞥 → 嗯`。当输入 `ㄥ` 时，只要原候选中存在完整的 `鞥`，就在全部原候选之后追加 `嗯`。`鞥啊` 不触发此规则，原 `鞥` 不会消失，显示替代项不会自动上屏。候选栏只显示前一部分候选时，可打开候选页查看追加项。

## 匹配与选择

- 仅检查追加前的完整候选快照，不以追加项触发其他规则。每条规则在一次刷新中最多追加一次，与源候选出现次数无关。
- 按规则表顺序追加；原候选与已追加内容即使已有同样文字，也继续保留重复项。每条配置使用独立 ID，使同文字项目可以分别显示和选择。
- 引擎分批回复、E5 排序、计算器或规则设置变化时，先剔除旧追加项，再从当前原候选重建；不累积旧项，也不把它们重新交给引擎评分。
- 同一源文字有多个选择范围时，优先继承完整输入候选；没有完整输入候选时，继承当前列表中第一个匹配项。继承原始消耗范围，不从替代文字长度推算。选择部分候选的替代文字后，剩余编码仍待输入。
- 联想候选继承原有插入行为；计算器候选的替代文字仍写回预输入以继续计算，不改为直接上屏。普通候选依旧通过原有选择路径上屏。

## 存储与 #33 导出衔接

规则只保存于应用私有设置 `shortcut_replacement_text_v1`，与 Shift／Ctrl 映射及多功能栏绑定分开。缺少配置表示没有规则；无法识别的配置不自动覆盖，候选功能回退为不追加，设置页面报告读取失败。

```json
{
  "schemaVersion": 1,
  "category": "replacementText",
  "rules": [
    {"id": "unique-entry-id", "source": "鞥", "replacement": "嗯"}
  ]
}
```

`rules` 数组保存完整顺序、重复源文字及重复目标，每行具有不同 ID。`ReplacementSettings.encode/decode` 提供这一类别的无损读写接口，已覆盖 Unicode、换行与重复条目的往返测试。

#33 的用户导出功能尚未实现，本功能没有顺带增加导出或导入入口。实现 #33 时必须将本类别纳入其版本化导出，保持数组顺序、重复项和完整文本，不用 Map 按源文字合并；旧导出中缺少该类别时应保持“类别缺失”的识别语义，不伪装成新版本文件。其他 Shortcut 类别不受本存储影响。

## 代码与测试

- `shortcuts/ReplacementRules.kt`：不依赖 Android 的追加规则和选择范围优先级。
- `shortcuts/ReplacementSettings.kt`、`ReplacementRuleEditor.kt`：版本化存储与现有设置页编辑。
- `IpaBoardService.refreshCandidates()`：在混排／语义结果与计算器覆盖完成后追加。
- 设备专项入口：`python3 tools/regression.py run --suite replacement-text --serial DEVICE_SERIAL --no-build`，先完成主机检查并遵守 [测试保护流程](testing.md)。
