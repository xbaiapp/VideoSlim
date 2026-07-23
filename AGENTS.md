# AGENTS.md — VideoSlim 项目永久规则

本文件是 VideoSlim 的项目级工作规范。所有开发者和 AI 在修改本仓库前都必须遵守。

## 当前冻结状态

- M3 已由项目所有者于 2026-07-22 接受为 `ACCEPTED — private scope`；当前发布代码基线是 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`（`1.4.3+18`）。
- `hardening/task3-engine-io`（Slice B）保留但冻结：不集成、不删除、不作为当前候选的一部分。
- M4-A/F5 画面裁剪已由项目所有者于 2026-07-22 明确授权实施，当前进入候选与真机验收阶段；M4-B/F8 时间裁剪仍未开始、未授权。该授权不包含 hardening、refactor 或 migration。
- 项目所有者已于 2026-07-22 授权拍摄时间/GPS保留和输出命名增强；范围固定为“仅保留来源中存在且可靠的时间与位置”，不增加隐私模式、完整 metadata 复制、第二次 remux 或音频继承。真机结论记录在 `docs/capture-metadata-device-acceptance.md`。
- 项目所有者于2026-07-23在既定修订预算用尽后，额外授权一次仅用于修复Media3缺省处理时间和必无字段核验的外科手术式修订；该例外不授权其他生产改动，也不自动增加后续修订预算。
- 未提供逐项真机证据的矩阵行不得反向预填 PASS，private-scope 接受不得扩写为生产发布或多设备保证。
- 当前已知限制见 `docs/known-debt.md`。

## A. 开工审批

任何名称或实质包含 `hardening`、`refactor`、`migration`、加固、重构、迁移的计划，开工前必须先用大白话向项目所有者说明：

1. 它解决什么普通用户能感知到的问题；
2. 不做会出现什么实际后果；
3. 预计会改动哪些现有行为或模块。

只有项目所有者明确批准后才能执行。不得用内部架构术语代替用户可感知的解释，也不得把文档讨论视为实施授权。

## B. 复审预算和 blocker 定义

每个任务最多允许：

1. 1 次实现；
2. 1 次修订；
3. 1 轮复审。

只有以下问题可以阻止候选进入真机验收：

- 能在真机上复现的问题；
- 会丢失用户文件的问题；
- 会误删用户文件的问题。

其余问题一律记录到 `docs/known-debt.md`，由项目所有者决定是否、何时处理。达到预算后必须停止，不得自动追加修订或复审轮次。

## C. 真机验收优先

上一里程碑真机验收未通过前，不得开始任何 hardening、refactor 或 migration。自动化测试、静态检查、模拟器和代码复审不能代替真机验收，也不能用来提前宣称里程碑已通过。

## D. 保持简单并发模型

必须保持 M2 已验证的简单模型：

- 单前台服务；
- 单任务；
- `finishOnce` 作为唯一终止门。

未经项目所有者明确批准，不得引入新的生命周期框架、第二套任务所有权模型、公共两阶段 engine API，或扩大并发任务数量。

## E. exact-SHA 复审时机

- exact-SHA 双复审只在候选完全冻结后执行一次；
- 开发过程中只使用普通 diff review；
- timeout、无摘要或未返回 verdict 的 reviewer 不是 PASS；
- 候选冻结后的源代码若发生变化，旧 exact-SHA 结论失效，但不得自动开启无限复审循环，仍受 B 节预算约束。

## 现有产品不变量

除非项目所有者明确改变决定：

- 视频保持硬件 VBR；不得恢复 CBR 或严格输出大小拦截；
- release 只包含 `arm64-v8a`；`x86_64` 仅用于 debug/test/instrumentation；
- 保持 minSdk 26、targetSdk 36、compileSdk 36、Media3 1.10.1；
- `SharedPreferences.commit()` 的 recovery durability 语义必须保留；
- Media3 create/start/cancel/dispose 保持 main-affine；
- production signing 暂缓，debug certificate 不得描述为商店生产签名；
- 没有真实设备证据时，不得宣称安装、播放、codec、通知、后台、恢复或取消已通过。
