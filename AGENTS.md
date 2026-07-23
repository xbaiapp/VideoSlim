# AGENTS.md — VideoSlim 项目永久规则

本文件是 VideoSlim 的项目级工作规范。所有开发者和 AI 在修改本仓库前都必须遵守。

## 当前冻结状态

- M3 已由项目所有者于 2026-07-22 接受为 `ACCEPTED — private scope`；当前发布代码基线是 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`（`1.4.3+18`）。
- `hardening/task3-engine-io`（Slice B）保留但冻结：不集成、不删除、不作为当前候选的一部分。
- M4-A/F5 画面裁剪已由项目所有者于 2026-07-22 明确授权实施，当前进入候选与真机验收阶段。M4-B/F8连续单段时间裁剪的纠正源码与`1.8.0+24` ARM64内部候选已完成自动化、APK核验，并由项目所有者于2026-07-23报告测试成功后接受为`ACCEPTED — private scope`；详细矩阵未提供的行仍为PENDING。两个授权都不包含hardening、refactor或migration。
- 项目所有者已于 2026-07-22 授权拍摄时间/GPS保留和输出命名增强；范围固定为“仅保留来源中存在且可靠的时间与位置”，不增加隐私模式、完整 metadata 复制、第二次 remux 或音频继承。真机结论记录在 `docs/capture-metadata-device-acceptance.md`。
- 项目所有者于2026-07-23在既定修订预算用尽后，额外授权一次仅用于修复Media3缺省处理时间和必无字段核验的外科手术式修订；metadata核心候选为 `a92d1cd4f5bf6b4b7dd0a7aaded199c6e0b230e8`，完整自动化门禁和focused review已通过。一条Pixel设备任务随后证明“时间存在、位置缺失”的App内核验与发布成功。
- 同日设备测试发现约1 MiB日志复制触发Android `TransactionTooLargeException`；项目所有者明确选择小修。上一候选 `b0267a0b959ccb46785daa1c91d0be96b5a0ef98` 仅将剪贴板载荷限制为最近128 KiB完整行、保留完整文件分享并递增到 `1.6.1+22`，不修改媒体生产路径，也不自动授权其他生产改动。
- 项目所有者于2026-07-23批准把C轨与M4-B/M4-C规划纳入仓库，并明确选择 **C1a低收益/可能变大提示** 作为首个代码项。C1a范围仅限Dart planner、S3单一提示/确认流程及测试：不改目标码率、不阻止发布、不改Kotlin/Media3/publication/recovery；允许把新的用户可见候选版本独立递增为`1.7.0+23`，仅用于APK身份与真机验收。该条是当时的授权边界；M4-B随后单独完成并接受，C2现在获得单独开工授权；C1b、C3、M4-C仍未授权。
- C1a上一候选源码为`7c49e57e3b6eafeeb765f2600c17b0242bea1160`（`1.7.0+23`）。首版`d3af1c3...`的一轮并行复审为FAIL；三项IMPORTANT均在唯一修订中处置，完整自动化与APK静态核验通过。按复审预算不再发起第二轮，不得把首版FAIL写成最终SHA的PASS；其设备测试后来由所有者跳过。
- 项目所有者随后明确跳过C1a真机验收并要求继续下一步；C1a状态记为`IMPLEMENTED — DEVICE TEST WAIVED/NOT RUN`，不得写成PASS。D1已用此前提供的最新相关F19任务完成零代码诊断：Media3有效配置仍为500 kbps，输出`videoBitrate`实际等于容器平均码率fallback；该Pixel HEVC组合属于运行期明显过冲，C1b不得建议更低目标。
- 同一指示构成M4-B/F8单段时间裁剪的明确开工授权。范围只允许连续单段起止时间、与crop/Presentation/压缩一次转码、现有音频模式与生命周期复用；不包含多段、乱序、跨文件、批量、hardening/refactor/migration或M4-C。M4-B首个冻结SHA `9c9ca887...`的一次双路复审为一路PASS、一路BLOCKERS；接受的`INVALID_TRIM`恢复锁定问题已在唯一纠正修订`9351e75bcc43c71a6e7caf03093fe27b0072b061`中修复。该纠正SHA通过Flutter `244/244`、Android JVM `346/346`、完整lint/build和独立APK核验；项目所有者随后报告测试成功，`1.8.0+24`现为`ACCEPTED — private scope`。按预算未追加第二轮复审，旧SHA裁决不得写成纠正SHA独立PASS；未提供的设备矩阵不得补写PASS。完整处置和身份见`docs/m4-b-exact-sha-review-disposition.md`、`docs/m4-b-completion-report.md`与`docs/m4-b-device-acceptance.md`。
- 项目所有者在报告M4-B测试成功后要求“继续进行下一步”，该指令按既定路线授权C2/F21编码器能力诊断作为唯一代码项。范围只允许F19只读能力页面/平台通道：查询`MediaCodecList`的目标视频encoder、码率模式、QP bounds、bitrate/complexity range及软硬件属性；不得configure codec、创建媒体任务、改变VBR/codec fallback/发布/生命周期，也不自动授权C1b、C3、M4-C或任何hardening/refactor/migration。
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
