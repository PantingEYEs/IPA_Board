# 0.0.1 Git 拓扑修复方案

状态：已执行。核对日期：2026-10-01（Asia/Shanghai）。

## 实际执行结果

用户批准重建分支并移动正式标签后，已完成下述方案的严格标签对齐变体：

- 功能集成合并提交：`6ee3734`（双父节点）。
- `0.0.1-dev`：`ad1913f`（重新应用发布准备提交）。
- `main`：`be0119a`（双父节点发布合并）。
- `v0.0.1` 重新指向 `be0119a`；本地与远程引用已核对一致。
- 两个分支及标签使用精确 lease 原子推送成功；最终源码树与原 `c3e1760` 完全一致。
- Release ID、正文、附件 ID/名称/大小/哈希均保持不变。
- main 与 0.0.1-dev 的本地分支级 mergeoptions 已设置为 --no-ff。
- 未提交文件保留；临时修复分支已清理。
- 备份位于 `.artifacts/topology-repair-20261001/`，包含 bundle、完整 .git 归档、原始引用/reflog、未提交文件、APK、设备测试报告与 Release 元数据。已从 bundle 恢复独立裸仓库，确认原分支和标签可恢复。

以下内容保留执行前方案与取舍记录。

## 原因与修复目标

此前两次合并使用了 `--ff-only`，未产生合并提交。目标是显式保留“功能分支 → 0.0.1-dev → main”的两次集成边界，并保持已发布源码内容不变。

## 本地与远程现状

远程仓库：https://github.com/PantingEYEs/IPA_Board

已通过 `git ls-remote --symref` 直接核对远程引用，默认分支为 main：

| 引用 | 本地与远程结果 |
| --- | --- |
| main | c3e17601ece2683af866ccaffc6885ab2ae115ed |
| 0.0.1-dev | c3e17601ece2683af866ccaffc6885ab2ae115ed |
| codex/edit-key-mappings | 51bea5195a6bdea412f5dfe475627fc1b80d3c6b |
| v0.0.1 附注标签对象 | 125920f7c77121c50cb3c9d6fb6efcfb711a34e7 |
| v0.0.1 指向的提交 | c3e17601ece2683af866ccaffc6885ab2ae115ed |

reflog 确认原 main 为 `124d2c0`，原 0.0.1-dev 为 `1852435`。这两个提交是重建合并边界的起点。保留既有初始化到开发分支的历史，不虚构更早的分叉。

GitHub API 已确认 [v0.0.1 Release](https://github.com/PantingEYEs/IPA_Board/releases/tag/v0.0.1) 为正式公开发布，非草稿、非预发布：

- 发布时间：2026-10-01 19:48:32（Asia/Shanghai）。
- Release ID：400915556；API 返回 immutable=false，这仅表示平台未锁定，不代表移动发布标签没有影响。
- 附件：IPA_Board_v0.0.1.apk，45,224,825 字节。
- 附件 SHA-256：6c31619ff24ba597a5ac5ce0e2c60763ac348a2fa803b11e649d2e31ebb1a12a。
- 查询时已有 1 次下载；不能假设历史无人使用。
- API 的 target_commitish 为 main，但确定实际源码版本的是标签解析结果。

本地还有以下未跟踪文件，必须原样保留，不混入修复提交：

- RELEASE_NOTES_0.0.1.md
- docs/0.0.2-development-plan.draft.md
- app/release/（含 APK 与输出元数据）

## 建议：重建两个分支，保留已发布标签

重建步骤：

1. 从旧开发分支提交 1852435 创建修复分支。
2. 使用 `--no-ff` 合并功能分支 51bea51，得到合并提交 M1。
3. cherry-pick 原发布准备提交 c3e1760，得到内容相同、父提交不同的 P2。
4. 从旧 main 提交 124d2c0 创建修复分支。
5. 使用 `--no-ff` 合并 P2，得到正式集成提交 M2。
6. 验证 M1、P2、M2 的源码树，两个合并提交的父节点，以及全部原功能提交是否保留。
7. 审阅后将 0.0.1-dev 指向 P2、main 指向 M2，以带精确 lease 的原子推送同步远程。

目标结构（示意）：

```text
124d2c0 ------------------------------ M2   main
    \                                 /
     ... 1852435 -------- M1 --- P2 --/       0.0.1-dev
              \          /
               ... 51bea51                  codex/edit-key-mappings
                         \
                          c3e1760            v0.0.1（原发布记录）
```

M1 有两个父提交：1852435 与 51bea51。M2 有两个父提交：124d2c0 与 P2。P2/M2 的 tree 必须与 c3e1760 完全一致。

此方案会改写远程 main 与 0.0.1-dev，保留功能分支全部原始提交。原发布准备提交 c3e1760 仍由 v0.0.1 保存，因此原 APK、标签和发布记录保持对应。代价是 v0.0.1 不在重建 main 的直接祖先路径上，而在旁支；它与重建发布集成提交的源码树一致。接受这个结构是保护已经公开的发布身份所作的明确取舍。

## 分阶段执行命令

以下命令用于后续执行，并非已执行记录。备份目录名和临时分支名需检查是否已存在；不得覆盖已有备份。执行前重新读取远程引用；若它们已变化，停止并重新评估，不能放宽 lease。

### 1. 备份与前置检查

备份所有引用和未跟踪文件到仓库外的持久目录。建议创建 Git bundle，另行复制 APK、Release notes、开发计划及设备测试报告。Git bundle 不包含未跟踪文件和构建报告。

```sh
git bundle create /absolute/backup/IPA_Board-before-topology-repair.bundle --all
git bundle verify /absolute/backup/IPA_Board-before-topology-repair.bundle

git branch backup/main-before-topology-repair c3e1760
git branch backup/dev-before-topology-repair c3e1760
```

确认没有新的已跟踪文件修改，备份完整，远程引用仍与上表一致。检查分支保护、rulesets 和历史改写权限；若服务器阻止强推，不自动关闭保护。

### 2. 仅在临时分支重建与验证

```sh
git switch -c codex/repair-0.0.1-dev 1852435
git merge --no-ff 51bea51 -m "merge: integrate key mappings into 0.0.1-dev"
git cherry-pick c3e1760

git switch -c codex/repair-0.0.1-main 124d2c0
git merge --no-ff codex/repair-0.0.1-dev -m "merge: release 0.0.1 into main"

git diff --exit-code c3e1760 codex/repair-0.0.1-dev
git diff --exit-code c3e1760 codex/repair-0.0.1-main
git diff --exit-code 51bea51 codex/repair-0.0.1-dev^ 
git show -s --format='%H %P %T' codex/repair-0.0.1-dev^
git show -s --format='%H %P %T' codex/repair-0.0.1-main
git merge-base --is-ancestor 51bea51 codex/repair-0.0.1-main
git log --graph --oneline --decorate --all
```

出现冲突时停止，不采用自动 ours/theirs。两份最终 diff 必须为空；M1/M2 必须各有两个预期父节点。核对 tree 哈希一致可证明此次只改变历史结构，不改变源码。此次修复不意味着此前设备测试失败已解决，也不需要为了拓扑变化声称重新验证 APK。

建议在此处展示实际提交图和新 SHA，审阅后再替换正式分支与推送。

### 3. 替换本地分支并同步远程

仅在重建验证通过且历史改写获批准后执行：

```sh
git branch -f 0.0.1-dev codex/repair-0.0.1-dev
git branch -f main codex/repair-0.0.1-main
git switch main

git push --atomic \
  --force-with-lease=refs/heads/main:c3e17601ece2683af866ccaffc6885ab2ae115ed \
  --force-with-lease=refs/heads/0.0.1-dev:c3e17601ece2683af866ccaffc6885ab2ae115ed \
  origin main:refs/heads/main 0.0.1-dev:refs/heads/0.0.1-dev
```

不使用裸 `--force`。原子推送保证两个远程分支一起成功或一起失败；若服务器不支持原子推送，先停止并调整方案，不擅自拆分。推送后再次 `ls-remote` 核对两个分支的新 SHA，确认 v0.0.1 标签对象及指向提交均未变化。

其他已有克隆需先保护未提交内容和自己的提交，再同步重建后的分支；不能直接要求所有人 reset --hard。确认修复前保留备份与临时分支。

## 可选方案与代价

### 严格让 v0.0.1 指向 M2

若要求发布标签也必须标记新的 main 合并节点，则需重建 v0.0.1 附注标签并强制更新远程标签。现有使用者已取得的标签、GitHub 自动生成的源码归档以及提交身份都会变化；源码树相同也不能消除这种身份变化。需要额外核对 GitHub Release 与标签更新后的关联，并明确说明此次仅修复历史、APK 附件未替换。此操作须单独确认，不作为建议方案默认动作。

### 完全不改写远程历史

可保留现有 main/tag，建立重建的开发分支后，再向现有 main 添加一个新的非快进合并提交。这能留下补记的集成节点，避免重写 main；但表示的是“发布后补记集成”，无法还原原本期望的两阶段发布历史。若不接受强推，可采用此折中方案，或仅从 0.0.2 起严格使用 --no-ff。

## 回滚

在远程推送前：切回 main 并将 main、0.0.1-dev 恢复至备份引用。未跟踪文件保持原样，不执行 reset --hard 或 clean。

在远程推送后：先确认无人追加新提交；以修复后两个分支的精确 SHA 作为 lease，原子推送备份引用恢复远程。任何新增提交都必须先保存并重新评估，不能覆盖。建议方案未改动 v0.0.1，回滚时无需修改标签或 Release。备份 bundle 提供独立恢复来源。

## 后续规则

功能分支合入版本开发分支、版本开发分支合入 main，统一明确使用 `git merge --no-ff`。必要时为这两个目标分支设置分支级 mergeoptions，配置后仍检查每次实际合并结果；若将来使用 GitHub PR，还需选择保留合并提交的合并方式。

从修复后的 main 创建 0.0.2-dev，把待提交开发计划带入新分支；测试维护、版本信息与功能工作独立提交。正式发布标签固定，不再因拓扑外观移动。
