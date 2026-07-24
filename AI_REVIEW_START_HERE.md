# VideoSlim — AI Review Start Here

> **用途：** 给新的 AI/开发者一个可验证的当前项目入口。先读本文，再决定是否需要展开 PRD 和源码。
> **生成日期：** 2026-07-24
> **当前候选版本：** `1.9.1+26`
> **当前候选代码 SHA：** `8f4e970c8c724a5019bcc0f56cbbddbb47d2fb33`
> **当前候选状态：** 一次性自动软件解码重试 `PRIVATE INTERNAL CANDIDATE — DEVICE ACCEPTANCE PENDING`；静态门禁和APK核验通过；唯一独立exact-state review超时无裁决，纠正SHA无独立review PASS
> **当前已接受基线：** M4-B/F8 `1.8.0+24 / 9351e75...`；项目所有者报告测试成功，详细设备矩阵未提供
> **阶段：** 自动fallback候选等待Pixel真机验收；原C2报告与候选证据保持冻结，C1b/C3/M4-C仍未授权
> **安全：** 凭据、用户媒体、运行时数据库和私有日志不属于本交接包；任何秘密值只能写为 `[REDACTED]`。

## 1. 先读什么

1. `docs/VideoSlim-AI-Handoff-2026-07-23.md`（可独立转交的新AI摘要、当前任务和路线图）
2. `AI_REVIEW_START_HERE.md`（本文）
3. `docs/current-project-status.md`（当前进度与证据）
4. `docs/automatic-software-decoder-retry-completion-report.md`（自动fallback契约、门禁、审查边界和APK身份）
5. `docs/automatic-software-decoder-retry-device-acceptance.md`（自动fallback真机矩阵）
6. `docs/c2-encoder-capabilities-completion-report.md`（冻结C2源码、门禁与APK身份）
7. `docs/c2-exact-sha-review-disposition.md`（C2复审边界）
8. `docs/c2-encoder-capabilities-device-acceptance.md`（C2实际API 37报告与剩余真机项）
9. `docs/plans/2026-07-23-c2-encoder-capabilities.md`（C2固定contract）
10. `README.md`（当前用户能力）
11. `docs/m4-b-completion-report.md`（M4-B纠正源码、门禁与APK身份）
12. `docs/m4-b-exact-sha-review-disposition.md`（M4-B复审边界）
13. `docs/m4-b-device-acceptance.md`（M4-B所有者接受记录）
14. `docs/d1-bitrate-diagnosis-2026-07-23.md`
15. `docs/c1a-low-savings-completion-report.md` / `docs/c1a-low-savings-device-acceptance.md`
16. `docs/VideoSlim PRD.md`（产品和权威contract）
17. `docs/capture-metadata-completion-report.md` / `docs/capture-metadata-device-acceptance.md`
18. `docs/m4-a-completion-report.md` / `docs/m4-device-acceptance.md`
19. `docs/known-debt.md`
20. `AGENTS.md`
21. 之后再读下方“关键源码”。

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
7. 新视频任务始终先硬件decoder；只有首次结构化`VIDEO_DECODING_FAILED`可在同task/同service内无确认地自动software retry一次，不循环、不永久降级。

## 3. 当前进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 | COMPLETE | 工具链和默认 APK 真机通过 |
| M1 | ACCEPTED | 导入→metadata→压缩→相册→F19 主流程通过 |
| M2 | `ACCEPTED — private scope` | 完整压缩、前台任务、取消、恢复、SAF 和兼容 Decoder 重试 |
| M3 | `ACCEPTED — private scope` | AAC 无损直提和 AAC 强制重编码；所有者于 2026-07-22 报告测试成功 |
| M4-A | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | F5 画面裁剪已实现；自动化与候选构建通过，真机矩阵未执行 |
| F7 metadata/name增强 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 无来源时间改用unknown sentinel并增加必无核验；focused review通过 |
| C轨 D1/F20–F22 | `C2/F21 DEVICE REPORT CAPTURED — OWNER DISPOSITION PENDING` | Pixel 10 Pro / API 37报告9 entries、四种MIME、0 query errors；硬件AVC/HEVC有QP bounds、硬件CQ均不可用、硬件AV1存在但无QP bounds；C1b/C3未授权 |
| M4-B | `ACCEPTED — private scope` | `1.8.0+24`由所有者报告测试成功；详细矩阵未提供，旧SHA混合裁决不等于纠正SHA独立PASS |
| 自动软件decoder fallback | `PRIVATE INTERNAL CANDIDATE — DEVICE ACCEPTANCE PENDING` | `1.9.1+26 / 8f4e970...`静态门禁和APK核验通过；独立review超时无裁决；真实failure路径待Pixel证据 |
| M4-C | `PLANNED — NOT AUTHORIZED` | M4-B依赖已满足，但F23仍需独立授权 |
| M5/M6 | NOT STARTED | 打磨、批量、目标大小、iOS/上架 |

当前自动fallback私有真机验收APK：

```text
VideoSlim-1.9.1+26-8f4e970-arm64-v8a-release.apk
SHA-256 8765c79b1afe9cfe5b451359766646b4be089d12820e3478a05ba636f24272f3
package com.videoslim.videoslim
version 1.9.1+26
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
        ├── EncoderCapabilityReader
        │     query-only MediaCodecList diagnostics; no media task
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
- `lib/models/encoder_capabilities.dart` / `lib/screens/encoder_capabilities_screen.dart` — C2严格能力report、确定性复制和只读页面
- `lib/models/process_request.dart` — `CropRect`、`VideoTrim`与严格request/snapshot/retry contract
- `lib/models/audio_extract_request.dart` — M3 音频请求
- `lib/logic/compression_planner.dart` — 输出尺寸、码率、trim有效时长估算、C1a判断和capability fallback
- `lib/logic/audio_extract_planner.dart` / `lib/logic/output_file_name_builder.dart` — M3模式、最终计划命名和估算
- `lib/logic/log_clipboard_payload.dart` / `lib/screens/debug_log_screen.dart` — Android Binder安全的最近128 KiB日志复制与完整文件分享边界

### Android/Kotlin

- `EngineChannel.kt` — MethodChannel/EventChannel、I/O routing、snapshot/readback
- `EncoderCapabilityReader.kt` — C2固定四种mime的只读MediaCodecList/CodecCapabilities查询与单项失败隔离
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

历史 metadata/name/clipboard候选代码 `b0267a0...`（metadata核心祖先 `a92d1cd...`）：

- Flutter analyze：PASS；Flutter tests：224/224；
- Android JVM tests：341/341；debug/release lint 与 assemble：PASS；
- ARM64 APK package/version/SDK/ABI、zipalign、v2签名、权限diff与常见凭据模式扫描：PASS；
- 旧 `47c5448...` 双路复审均超时且失效，不计PASS；`497c5d2...` Route A：FAIL、Route B：PASS；缺省时间修订 `a92d1cd...` 的focused review：PASS；超长日志复制 `b0267a0...` 的focused exact-SHA review：PASS，无BLOCKER/IMPORTANT finding；
- 一条Pixel设备任务完成时间存在/位置缺失核验和MediaStore发布；来源谱系、外部atom、`DATE_TAKEN`/图库排序、SAF和其他字段矩阵仍保持PENDING。

上一C1a候选代码`7c49e57...`：

- Dart format、Flutter analyze：PASS；Flutter tests：`227/227`；
- Android JVM tests：`341/341`；debug/release lint与debug assemble：PASS；
- ARM64 APK的ZIP、zipalign、v2签名、证书连续性、package/version/SDK/ABI、权限与凭据模式扫描：PASS；
- 首版`d3af1c3...`一轮并行复审两路均FAIL；三项IMPORTANT已在唯一修订`7c49e57...`中处置。按预算未二次复审，不得表述为最终SHA review PASS；
- 构建机无连接设备；项目所有者跳过`docs/c1a-low-savings-device-acceptance.md`，全部行仍保持PENDING而非PASS。

D1已从此前提供的最新相关F19任务完成零代码诊断：Media3有效encoder `Format`仍记录500 kbps，输出metadata的`2,307,117 bps`与`文件字节×8÷时长`完全一致，实际是容器平均码率fallback。结论是该Pixel/HEVC组合运行期明显过冲，而非Media3 fallback配置期夹高；完整脱敏证据见`docs/d1-bitrate-diagnosis-2026-07-23.md`。

当前M4-B纠正候选代码`9351e75...`：

- Dart format、Flutter analyze：PASS；Flutter tests：`244/244`；
- Android JVM tests：`346/346`；debug/release lint与debug assemble：PASS；
- ARM64 APK的ZIP、16 KiB zipalign、v2签名、证书连续性、package/version/SDK/ABI、权限与凭据模式扫描：PASS；
- APK SHA-256：`ac85d84e0a69185b3e73180737918fb98326f3899db9264991c0a9c681351567`；
- 首个冻结SHA的一轮双路复审为一路PASS、一路BLOCKERS；阻断已在唯一纠正修订中处置，按预算未二次复审，不得表述为纠正SHA独立review PASS；
- 项目所有者已报告M4-B测试成功并接受private scope；未提供的详细设备/素材/PTS/日志矩阵行仍保持PENDING。

C2纠正候选`1.9.0+25 / 11f169c...`：

- RED先证明缺失模型/reader/page，再完成最小GREEN；
- Dart format：63 files、0 changed；Flutter analyze：0 issues；Flutter tests：`257/257`；
- Android JVM tests：`350/350`，57个XML、0 failures/errors/skipped；
- `TranscodeEngine.kt`、`ProcessingService.kt`、publication、recovery和manifest均未修改；
- 首个SHA `85e2497...`唯一双路复审均超时无裁决，且controller exact gate发现API 29 lint blocker；唯一纠正SHA使用直接SDK guard和`@RequiresApi(Q)` helper；
- 纠正SHA的debug/release lint、debug assemble、ARM64 release build与APK ZIP/16 KiB zipalign/v2签名/证书连续性/package/version/SDK/ABI/权限/秘密扫描均PASS；
- APK SHA-256：`fe9e0e70b90dd5ae3bd6aace327b3a636b365a112689cd7a1f994927ccb6b2ed`；按预算未复审纠正SHA，不得写成独立review PASS；API 37真机能力清单已归档，接受决定和未报告交互项仍PENDING。

当前自动software decoder fallback候选`1.9.1+26 / 8f4e970...`：

- 默认硬件；仅首次硬件video attempt的结构化`VIDEO_DECODING_FAILED`在同task、同`ProcessingService`内无确认地software retry一次；精确request仅切decoder mode；
- route、publication owner、registry与recovery ownership转移；旧route迟到事件拒绝；UI/前台通知以显式flag回到`preparing/0%`；
- Dart format 63/0、Flutter analyze 0 issues、Flutter tests`259/259`、Android JVM`359/359`、debug/release lint与assemble、ARM64 release build和APK静态核验PASS；
- APK SHA-256：`8765c79b1afe9cfe5b451359766646b4be089d12820e3478a05ba636f24272f3`；
- 唯一独立exact-state review针对首版`11799b0...`超时且无verdict；控制器用唯一修订关闭延迟硬件snapshot attempt-boundary和registry fail-open，不得写成独立review PASS；
- 真机状态PENDING，证据与矩阵见两个`automatic-software-decoder-retry-*`文档。

远端 CI 不是全绿：主 Flutter/Android job 全部通过，但 API 35 x86_64 instrumentation job 因 runner 中 `sdkmanager: command not found` 在该 job 的应用/instrumentation 构建前失败，emulator instrumentation 未执行。

## 7. 不要误读的事实

- “M3 接受”不等于每一个空白测试矩阵行都通过；当前聊天未附逐项设备/F19 证据。
- 原 PRD 的“一小时 AAC copy <10 秒”没有被证明。安全实现包含三次物理 sample 证据路径，长媒体可能明显更慢。
- `1.4.3+18` 只消除成功后 `getAudioInfo` 的重复扫描，不删除发布前完整校验。
- Task 3 Slice B 未集成；其 worktree/patch 是冻结研究，不是产品代码。
- Release 使用 Debug certificate，不是生产签名。
- M4-A crop与C1a提示已实现但尚未真机接受；M4-B连续单段trim已由所有者报告测试成功并接受private scope；C2证据保持冻结。自动fallback候选没有独立review PASS或真实failure-path真机证据；C1b/C3与M4-C仍未实现、未授权。
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
- 自动fallback代码与私有候选已经完成，下一步只是真机验收；C2证据归档保持独立。在项目所有者新的明确授权前，C1b/C3、M4-C/F23或任何hardening/refactor/migration不得开工。
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
