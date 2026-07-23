# VideoSlim M4-B Exact-SHA复审处置

> **日期：** 2026-07-23
> **受审源码：** `9c9ca887ec4a8a26a6eb892077f88d333eb2a0da`
> **受审tree：** `84181f4e05173788232bae2186326e16ac75247e`
> **结论：** `BLOCKER ACCEPTED — CORRECTIVE REVISION IN VERIFICATION`
> **设备证据：** `NOT CLAIMED`

## 1. 复审执行边界

这是M4-B冻结后的唯一双路exact-SHA复审轮次。两名初始审查者均在完整SHA上只读检查，但都在600秒上限结束且没有返回裁决，因此必须按`NO VERDICT`处理，不能写成PASS。

在源码和tree均未变化的前提下，同一轮次启动了两个更窄、限时的替代裁决：

1. 产品规格/跨层契约：`PASS`，`VERIFIED_SHA`与上述完整SHA一致，`BLOCKERS: none`；
2. 实现质量/恢复可靠性：`BLOCKERS`，`VERIFIED_SHA`与上述完整SHA一致，发现一项可到达的恢复阻断。

原始超时记录、替代审查完整transcript、解析后的裁决与SHA-256清单位于仓库外的持久证据目录：

`/root/artifacts/videoslim/m4b-single-trim/9c9ca887ec4a8a26a6eb892077f88d333eb2a0da/`

## 2. 接受的阻断项

当原生终态快照返回`INVALID_TRIM`，且重新读取的来源metadata时长已短于快照中持久化的`trimEndMs`时，恢复流程先设置新metadata，再调用`restoreTrim`。旧实现会在终态事件被消费前重新验证端点并抛错，外层保护逻辑随后进入“原生所有权不确定”锁定状态。

结果是用户无法到达承诺的“重新编辑或移除时间裁剪”恢复入口；重启后还可能再次进入同一锁定状态。该问题违反M4-B已锁定的`INVALID_TRIM`可恢复契约，因此按release blocker接受。

## 3. 纠正范围

本轮只做一次最小纠正，不改变M4-B范围、Media3管线、生命周期或发布事务：

1. 原生快照中的结构合法端点继续作为恢复证据保留；恢复时不把来源边界变化升级为所有权不确定；
2. `HomeFlowState`显式记录`trimNeedsRepair`，新保存仍严格校验且fail closed；remove、新来源和有效保存都会清除此状态；事务回滚会精确保留该状态；
3. 如果恢复端点超出当前来源时长，`TrimEditor`不夹取、不自动保存，也不把旧端点静默改写为新端点；编辑器以全时长中性范围打开、禁用保存并显示“重新选择或返回后移除”提示，只有用户主动移动滑块后才能保存新的合法区间；
4. 普通有效trim、无trim、snapshot/retry、native clipping和其他既有流程不变。

## 4. RED→GREEN证据

纠正前新增三条回归均按预期失败：

- state恢复在来源边界检查处抛错；
- TrimEditor因无效initial trim抛错；
- startup snapshot进入`nativeOwnershipUncertain=true`。

纠正后：

- 三条定向回归：`3/3 PASS`；
- `dart format --output=none --set-exit-if-changed lib test`：PASS；
- `flutter analyze`：PASS；
- Flutter完整测试：`244/244 PASS`。

新的冻结源码SHA、完整Flutter/Android exact-SHA门禁、纠正自动化闭环和APK身份必须在后续证据中记录。按每任务一轮复审预算不发起第二轮，也不得把旧SHA的混合裁决写成纠正SHA的PASS。旧SHA `9c9ca887ec4a8a26a6eb892077f88d333eb2a0da`已被本阻断项否决，不得用于构建或交付内部候选。

## 5. 设备边界

本处置没有新增任何真机证据。M4-B真机矩阵仍为`NOT RUN — NO DEVICE EVIDENCE`；C1a仍为`NOT RUN — USER DECLINED`，两者都不得改写为PASS。
