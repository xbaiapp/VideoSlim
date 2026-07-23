# VideoSlim — AI Review Start Here

> **用途：** 给新的 AI/开发者一个可验证的当前项目入口。先读本文，再决定是否需要展开 PRD 和源码。
> **生成日期：** 2026-07-23
> **当前候选版本：** `1.7.0+23`
> **当前候选代码 SHA：** `7c49e57e3b6eafeeb765f2600c17b0242bea1160`
> **M4-B目标版本：** `1.8.0+24`（首个冻结SHA的唯一双路复审已完成并发现一项阻断；唯一纠正修订正在执行exact-SHA门禁）
> **阶段：** C1a已实现但项目所有者跳过真机验收（未记PASS）；D1已完成并确认Pixel HEVC运行期明显过冲；M4-B/F8正在验证`INVALID_TRIM`恢复纠正；M3 `1.4.3+18`仍是当前已接受发布基线
> **安全：** 凭据、用户媒体、运行时数据库和私有日志不属于本交接包；任何秘密值只能写为 `[REDACTED]`。

## 1. 先读什么

1. `docs/VideoSlim-AI-Handoff-2026-07-23.md`（可独立转交的新AI摘要、当前任务和路线图）
2. `AI_REVIEW_START_HERE.md`（本文）
3. `docs/current-project-status.md`（当前进度与证据）
4. `README.md`（当前用户能力）
5. `docs/m4-b-exact-sha-review-disposition.md`（M4-B唯一双路复审、阻断与纠正边界）
6. `docs/m4-b-device-acceptance.md`（M4-B真机矩阵；当前全部PENDING）
7. `docs/d1-bitrate-diagnosis-2026-07-23.md`（当前已完成的码率诊断）
8. `docs/c1a-low-savings-completion-report.md` / `docs/c1a-low-savings-device-acceptance.md`（已实现功能与跳过的PENDING矩阵）
9. `docs/VideoSlim PRD.md`（产品和权威 contract）
10. `docs/capture-metadata-completion-report.md` / `docs/capture-metadata-device-acceptance.md`（仍保留的metadata证据与矩阵）
11. `docs/m4-a-completion-report.md` / `docs/m4-device-acceptance.md`（仍保留的画面裁剪候选与PENDING矩阵）
12. `docs/known-debt.md`（冻结 Slice B 与已知限制）
13. `AGENTS.md`（项目治理、复审预算、真机优先规则）
14. 之后再读下方“关键源码”。

## 2. 产品边界

VideoSlim 是 Android 本地媒体工具：

- 视频和音频不上传；
- App 没有 `INTERNET`、广域媒体读取或 all-files 权限；
- 输入只通过 Photo Picker / SAF 的 `content://` URI；
- 输出先写私有临时文件，验证后发布到 MediaStore 或用户选择的 SAF 文件夹；
- 当前仅供项目所有者私用，使用 Android Debug certificate；
- release 仅包含 `arm64-v8a`；
- 当前不承诺商店发布、多设备覆盖或 iOS。

必须保持的产品决策：

1. 视频压缩继续使用硬件 VBR；不得未经批准改为 CBR 或严格目标大小失败策略。
2. 一个前台服务、同一时间一个任务、`finishOnce` 单终态所有权。
3. Media3 create/start/cancel/dispose 保持 main-affine。
4. `SharedPreferences.commit()` 的 recovery durability 不得改为 `apply()`。
5. 不删除/修改源文件；失败和取消只清理由不可变证据证明属于本任务的对象。
6. 自动化、模拟器和静态复审不能代替真机验收。

## 3. 当前进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 | COMPLETE | 工具链和默认 APK 真机通过 |
| M1 | ACCEPTED | 导入→metadata→压缩→相册→F19 主流程通过 |
| M2 | `ACCEPTED — private scope` | 完整压缩、前台任务、取消、恢复、SAF 和兼容 Decoder 重试 |
| M3 | `ACCEPTED — private scope` | AAC 无损直提和 AAC 强制重编码；所有者于 2026-07-22 报告测试成功 |
| M4-A | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | F5 画面裁剪已实现；自动化与候选构建通过，真机矩阵未执行 |
| F7 metadata/name增强 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 无来源时间改用unknown sentinel并增加必无核验；focused review通过 |
| C轨 D1/F20–F22 | `C1a IMPLEMENTED — DEVICE TEST WAIVED；D1 COMPLETE` | D1有效配置500 kbps，Pixel HEVC运行期明显过冲；C1b/C2/C3未授权 |
| M4-B | `CORRECTIVE REVISION — EXACT-SHA GATES PENDING` | 首个冻结SHA的一次双路复审为一路PASS、一路BLOCKERS；唯一纠正修订正在验证，APK和真机证据尚未完成 |
| M4-C | `PLANNED — NOT AUTHORIZED` | F23同源多段依赖M4-B真机接受 |
| M5/M6 | NOT STARTED | 打磨、批量、目标大小、iOS/上架 |

当前私有真机验收APK：

```text
VideoSlim-1.7.0+23-7c49e57-arm64-v8a-release.apk
SHA-256 72dcce8374c3bb771cdfa1b8fddd6d2dfec8baba19f8e3b917015c221e92367f
package com.videoslim.videoslim
version 1.7.0+23
```

## 4. 技术架构

```text
Flutter UI / HomeFlowState
        │
        ▼
Dart models + planners + VideoEngine interface
        │ MethodChannel / EventChannel
        ▼
EngineChannel + ProcessingRuntime/Registry
        │
        ├── TranscodeEngine
        │     Media3 Transformer + hardware codec selection
        │
        ├── AudioExtractionEngine
        │     AAC copy: MediaExtractor + MediaMuxer
        │     AAC encode: Media3 audio-only
        │
        ├── ProcessingService
        │     foreground notification / wake-lock / finishOnce
        │
        └── publication + recovery
              private temp → verify → MediaStore/SAF
              TaskRecoveryStore + startup reconciliation
```

### 视频流程

```text
Picker URI
→ VideoMetadataReader
→ reliable source capture time/GPS policy
→ CompressionPlanner
→ ProcessRequest
→ capability/exact-format/storage preflight
→ Media3 Transformer + InAppMp4Muxer capture metadata provider
→ private MP4 media + promised capture metadata verification
→ MediaStore/SAF publication
→ registry/snapshot success
→ Flutter result/open/share
```

### M3 AAC copy

```text
Picker URI
→ physical source audio scan
→ sample-by-sample MediaExtractor/MediaMuxer copy
→ physical output scan
→ count/PTS/bytes/digest/duration comparison
→ MediaStore/SAF publication
→ one-shot verified metadata handoff
→ Flutter result
```

### M3 AAC encode

```text
Picker URI
→ metadata/profile/channel validation
→ Media3 audio-only forced decode/encode
→ physical output verification
→ publication
→ result
```

## 5. 核心源码

### Flutter

- `lib/screens/home_screen.dart` — 主工作流及S3到画面/时间S4编辑器接线
- `lib/widgets/crop_editor.dart` / `lib/logic/crop_geometry.dart` — 画面裁剪编辑器与显示像素几何；单次手势累计位移并固定缩放对边
- `lib/widgets/trim_editor.dart` — 连续单段起止编辑、1秒下限、180ms预览节流与旧响应隔离
- `lib/state/home_flow_state.dart` — typed UI/workflow state
- `lib/engine/video_engine.dart` — 跨平台引擎接口
- `lib/engine/method_channel_video_engine.dart` — 平台通道 adapter
- `lib/models/process_request.dart` — `CropRect`、`VideoTrim`与严格request/snapshot/retry contract
- `lib/models/audio_extract_request.dart` — M3 音频请求
- `lib/logic/compression_planner.dart` — 输出尺寸、码率、trim有效时长估算、C1a判断和capability fallback
- `lib/logic/audio_extract_planner.dart` / `lib/logic/output_file_name_builder.dart` — M3模式、最终计划命名和估算
- `lib/logic/log_clipboard_payload.dart` / `lib/screens/debug_log_screen.dart` — Android Binder安全的最近128 KiB日志复制与完整文件分享边界

### Android/Kotlin

- `EngineChannel.kt` — MethodChannel/EventChannel、I/O routing、snapshot/readback
- `ProcessingRuntime.kt` / `ProcessingRegistry.kt` — 进程级任务所有权和状态
- `ProcessingService.kt` — 前台服务、通知、终态和 recovery observer
- `TranscodeEngine.kt` / `TrimmedMediaItem.kt` — Media3输入clipping、视频转码、codec preflight、effects与发布
- `ProcessModels.kt` / `TranscodePlan.kt` — 严格trim解析、`INVALID_TRIM`、来源时长边界和存储预检
- `CaptureMetadata.kt` / `VideoMetadataReader.kt` — 来源时间/GPS解析、muxer白名单、发布前核验与安全传播
- `CropGeometryMapper.kt` / `PreviewFrameReader.kt` — 显示像素到 NDC 与只读预览帧
- `AudioExtractionEngine.kt` — AAC copy / AAC encode
- `AudioSampleCopyLoop.kt` — sample copy、版本化SHA-256 digest和完整性证据
- `AudioMetadataReader.kt` — 音频 metadata/物理扫描 fallback
- `VerifiedAudioInfoCache.kt` — 发布成功后的一次性 verified metadata handoff
- `MediaStoreSaver.kt` — 默认 MediaStore 与 SAF document-tree 发布
- `TaskRecoveryStore.kt` / `RecoveryIoCoordinator.kt` / `VideoSlimApplication.kt` — durable journal、进程级 reconciliation gate 与启动对账

## 6. 验证状态

发布代码 `19abfb7...`：

- Flutter tests：191/191；
- Android JVM tests：307/307；
- analyze、format、debug/release lint、Debug/Release assemble、ARM64 APK：PASS；
- APK ZIP、zipalign、v2 signature、package/version/SDK/ABI、permission diff、secret scan：PASS；
- exact-SHA 双复审：PASS / PASS，0 blocker；
- 项目所有者：M3 测试成功，接受为 private scope。

M4-A 修复候选代码 `d41e21c...`：

- Flutter analyze：PASS；Flutter tests：215/215；
- Android JVM tests：317/317；debug/release lint 与 assemble：PASS；
- ARM64 APK 的 zipalign、v2 signature、package/version/SDK/ABI、权限与凭据模式扫描：PASS；
- 初始冻结 SHA `6de3f7f...` 的 exact-SHA Route B：PASS、0 blocker；Route A 未返回最终 verdict，按规则不计 PASS；源码随后因真机手势反馈变更，旧 exact 结论不冒充当前 SHA 的 PASS；
- 当前手势修复 diff 的唯一 focused review：PASS；慢速亚像素累计与自由模式固定对边 RED→GREEN 回归测试通过；
- 构建机无连接设备，`docs/m4-device-acceptance.md` 全部保持 PENDING。

当前 metadata/name/clipboard候选代码 `b0267a0...`（metadata核心祖先 `a92d1cd...`）：

- Flutter analyze：PASS；Flutter tests：224/224；
- Android JVM tests：341/341；debug/release lint 与 assemble：PASS；
- ARM64 APK package/version/SDK/ABI、zipalign、v2签名、权限diff与常见凭据模式扫描：PASS；
- 旧 `47c5448...` 双路复审均超时且失效，不计PASS；`497c5d2...` Route A：FAIL、Route B：PASS；缺省时间修订 `a92d1cd...` 的focused review：PASS；超长日志复制 `b0267a0...` 的focused exact-SHA review：PASS，无BLOCKER/IMPORTANT finding；
- 一条Pixel设备任务完成时间存在/位置缺失核验和MediaStore发布；来源谱系、外部atom、`DATE_TAKEN`/图库排序、SAF和其他字段矩阵仍保持PENDING。

当前C1a候选代码`7c49e57...`：

- Dart format、Flutter analyze：PASS；Flutter tests：`227/227`；
- Android JVM tests：`341/341`；debug/release lint与debug assemble：PASS；
- ARM64 APK的ZIP、zipalign、v2签名、证书连续性、package/version/SDK/ABI、权限与凭据模式扫描：PASS；
- 首版`d3af1c3...`一轮并行复审两路均FAIL；三项IMPORTANT已在唯一修订`7c49e57...`中处置。按预算未二次复审，不得表述为最终SHA review PASS；
- 构建机无连接设备；项目所有者跳过`docs/c1a-low-savings-device-acceptance.md`，全部行仍保持PENDING而非PASS。

D1已从此前提供的最新相关F19任务完成零代码诊断：Media3有效encoder `Format`仍记录500 kbps，输出metadata的`2,307,117 bps`与`文件字节×8÷时长`完全一致，实际是容器平均码率fallback。结论是该Pixel/HEVC组合运行期明显过冲，而非Media3 fallback配置期夹高；完整脱敏证据见`docs/d1-bitrate-diagnosis-2026-07-23.md`。

远端 CI 不是全绿：主 Flutter/Android job 全部通过，但 API 35 x86_64 instrumentation job 因 runner 中 `sdkmanager: command not found` 在该 job 的应用/instrumentation 构建前失败，emulator instrumentation 未执行。

## 7. 不要误读的事实

- “M3 接受”不等于每一个空白测试矩阵行都通过；当前聊天未附逐项设备/F19 证据。
- 原 PRD 的“一小时 AAC copy <10 秒”没有被证明。安全实现包含三次物理 sample 证据路径，长媒体可能明显更慢。
- `1.4.3+18` 只消除成功后 `getAudioInfo` 的重复扫描，不删除发布前完整校验。
- Task 3 Slice B 未集成；其 worktree/patch 是冻结研究，不是产品代码。
- Release 使用 Debug certificate，不是生产签名。
- M4-A crop与C1a提示已实现但尚未真机接受；M4-B连续单段trim已实现但尚未冻结候选或真机接受；C1b/C2/C3与M4-C多段仍未实现、未授权。
- `a92d1cd...` 已用1904/zero sentinel覆盖Media3处理时间默认值，并对时间/GPS执行必有与必无核验；`b0267a0...` 只增加日志复制边界。单次Pixel成功不能替代真机矩阵，不得提前写为ACCEPTED。

## 8. M4-A 实现边界与剩余验收

M4-A 已按下列一次转码链路实现：

```text
裁剪 UI → display-oriented CropRect
→ CompressionPlanner 以裁剪后尺寸规划
→ ProcessRequest.crop
→ Kotlin 严格解析与 display-pixel → NDC 映射
→ Media3 Effects: crop → Presentation/scale
→ 现有 Transformer/verify/publication/recovery
```

已完成裁剪编辑器、预览帧、比例锁、边界/偶数尺寸、裁后 planner、`Crop → Presentation`、snapshot/retry round-trip 和 `INVALID_CROP` 恢复。尚未完成的是 0°/90°/180°/270°、codec、后台、锁屏、取消、恢复和文件安全的真实设备矩阵。

不要生成“裁剪中间视频”再二次压缩；PRD 明确要求裁剪、缩放、压缩一次转码完成。

## 9. 已知债务和下一 AI 的工作规则

- 先读 `docs/known-debt.md`；不要恢复或合并冻结的 Slice B。
- 当前只实施已获准的M4-B/F8连续单段时间裁剪；C1b/C2/C3、M4-C/F23或任何hardening/refactor/migration不得并行开工。
- 当前候选只保留可靠来源时间/GPS；不要增加隐私模式、完整metadata复制、设备定位、音频继承、第二次remux或自定义MP4 writer。真实单次mux失败时停止并报告规模升级。
- “分析”意味着只读，不得自动编辑代码。
- 每任务默认最多一次实现、一次修订、一轮 exact-SHA 复审。
- 只有真机可复现问题或用户文件丢失/误删风险是当前 private-scope blocker。
- 不得用静态 reviewer PASS 覆盖真实构建/真机失败。

## 10. 希望下一个 AI 输出什么

除非项目所有者明确要求编码，先给出：

1. 对当前代码与 PRD 的事实核对；
2. 用户可感知的方案和不做的后果；
3. 最小改动边界与明确不改的稳定组件；
4. 分层数据/接口变更；
5. 测试和真机验收矩阵；
6. 已知风险、开放产品决定和停止条件。
