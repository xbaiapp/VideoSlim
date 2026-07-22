# VideoSlim 已知技术债与冻结边界

> **决策日期：** 2026-07-22
> **当前路线：** M3 已接受为 `ACCEPTED — private scope`；当前发布代码为 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`，Task 3 Slice B 继续冻结且未集成。
> **处理原则：** 本清单中的问题不自动触发新计划；只有真机可复现或会丢失/误删用户文件的问题才是 blocker，其他事项由项目所有者裁决。

## 1. Slice B 冻结状态

Task 3 Slice B 未合入当前候选。

- 分支：`hardening/task3-engine-io`
- worktree：`/root/hermes-project/videoslim-task3-engine-io`
- 冻结 HEAD：`981d36662d37d59c36f221ffea72ec9ff32a7134`
- 相对主整改分支：3 个提交
- 状态：保留、clean、不集成、不删除、不推送为当前候选

冻结提交：

1. `c48954a3716b459807341ccb9bc0970691553d35` — durable engine lifecycle I/O；
2. `c3add3ace5c66b3f07b3d2104350abd24e0fdbff` — bounded cleanup 与 legacy rollback；
3. `981d36662d37d59c36f221ffea72ec9ff32a7134` — durable lifecycle race tests。

停止后产生的未提交 diff 已完整保存后还原：

- patch：`/root/artifacts/videoslim/slice-b-freeze/981d366-post-stop-dirty.patch`
- SHA-256：`2e45a4aae925a5c2ea491c8632973d5a10a816f700e076f707c422c265e69963`
- 内容：2 个生产文件、3 个测试文件；346 insertions、32 deletions
- 验证：在 clean `981d366` 上 `git apply --check` 通过

该 patch 仅为证据，不代表已完成、已测试或已复审的修复。

## 2. 未解决项

### 2.1 Exact terminal winner 与 watchdog arm 顺序

在冻结的 Slice B 中，正常 engine terminal 可能在 exact winner 安装前 arm deadline。如果 scheduler 同步触发或拒绝调度，通用 fallback 可能先获胜，使真实 success cleanup 和带输出 URI 的 registry publication 不执行。

用户可感知的潜在表现：处理已经成功，但 App 状态可能仍显示处理中，或结果信息没有及时出现。

当前处置：Slice B 不合入，因此该新增竞态不会进入当前候选；作为未来 debt 保留。

### 2.2 Cleanup completion 的 main dispatch rejection

冻结的 Slice B 可能在 cleanup 已完成后，把 terminal completion 投递到 main 时遇到 rejection。当前实现可能过早取消 deadline 并把“已 post”误当成“已交付”，使 task 停在 `FINISHING`。

用户可感知的潜在表现：极端系统退出/Looper 状态下，任务界面或通知不能正常收尾。

当前处置：Slice B 不合入；若未来重新引入，必须使用 delivery-acknowledged dispatch 和 off-main-safe fallback。

### 2.3 三条生产 start 路径的行为测试不足

现有 Slice B 测试对 video transform、audio copy、audio AAC 的 durable-start coordinator 有覆盖，但仍部分依赖 fake harness 与 source substring，不能充分证明每条真实 engine wiring 的 admission、取消和 executor rejection 行为。

用户可感知风险：当前没有确认的真机故障；这是测试证据强度不足。

当前处置：记为 debt，不阻止当前候选进入真机验收。

## 3. 当前候选的明确限制

### Recovery journal 尚未完整迁出主线程

主整改分支已合入 Task 3 Slice A：

- Application 异步 one-shot reconciliation；
- Service 先进入 foreground，再等待 reconciliation；
- bounded watchdog、typed revalidation、API 26-safe future facade；
- timeout 后 detach heavy callback，避免持续保留 Service/engine 图。

但 Slice B 未合入，因此 `TaskRecoveryStore.begin/updateStage/markDiscarding/clear` 的完整 engine-serial I/O 迁移不属于当前候选。换言之：**recovery journal 尚未完整迁出主线程。**

可能的用户影响：任务开始或结束时，极端慢存储/Provider 条件下仍可能出现短暂无响应。当前没有真机复现证据，也没有确认的用户文件丢失或误删证据，因此按项目规则作为已知限制进入真机验收。

## 4. 当前 private-scope blocker 判定

以下任一情况出现时，立即停止该用例、保留 F19 日志与素材信息，并判定为 blocker：

- 真机上可以稳定复现 App 卡死、任务永不结束、错误成功、错误取消或无法恢复；
- 源文件丢失、被覆盖或被修改；
- VideoSlim 之外的文件被删除；
- 已成功输出被错误删除；
- 失败/取消产生无法清理且用户可见的错误文件。

只在静态复审中出现、无法在真机复现且不涉及用户文件安全的问题，继续保留在本清单，由项目所有者裁决。

## 5. 接受后的禁止状态声明

M3 已由项目所有者接受为 private scope，但仍不得声称：

- `docs/m3-device-acceptance.md` 中所有空白矩阵项均已通过；
- 原 PRD 的“一小时 copy <10 秒”已被证明；
- recovery journal 已完整迁出主线程；
- Slice B 已完成或已集成；
- API 26–28、多 Provider、多 SoC、Pixel/GrapheneOS/OEM 扩展矩阵均已通过；
- 当前 APK 是 production-signed 商店包。
