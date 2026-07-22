# VideoSlim — AI Review Start Here

> **用途：** 给新的 AI/开发者一个可验证的当前项目入口。先读本文，再决定是否需要展开 PRD 和源码。
> **生成日期：** 2026-07-22
> **当前发布版本：** `1.4.3+18`
> **发布代码 SHA：** `19abfb7da2e8fa028e7200000f0dc2a114bc840e`
> **阶段：** M3 `ACCEPTED — private scope`；M4 未开始
> **安全：** 凭据、用户媒体、运行时数据库和私有日志不属于本交接包；任何秘密值只能写为 `[REDACTED]`。

## 1. 先读什么

1. `AI_REVIEW_START_HERE.md`（本文）
2. `docs/current-project-status.md`（当前进度与证据）
3. `README.md`（当前用户能力）
4. `docs/VideoSlim PRD.md`（产品和权威 contract）
5. `docs/m3-completion-report.md`（M3 实现、修复、验证、接受范围）
6. `docs/known-debt.md`（冻结 Slice B 与已知限制）
7. `AGENTS.md`（项目治理、复审预算、真机优先规则）
8. 之后再读下方“关键源码”。

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
| M4 | NOT STARTED | crop + trim，仅 contract/UI seam 已预留 |
| M5/M6 | NOT STARTED | 打磨、批量、目标大小、iOS/上架 |

当前 APK：

```text
VideoSlim-1.4.3+18-19abfb7-arm64-v8a-release.apk
SHA-256 12523bac8b91994e23f965c98ce9f26c4e0ff3a8aac18fbfefb4cb01f34fbcf7
package com.videoslim.videoslim
version 1.4.3+18
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
→ CompressionPlanner
→ ProcessRequest
→ capability/exact-format/storage preflight
→ Media3 Transformer
→ private MP4 verification
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

- `lib/screens/home_screen.dart` — 主工作流与界面；当前 crop card 禁用
- `lib/state/home_flow_state.dart` — typed UI/workflow state
- `lib/engine/video_engine.dart` — 跨平台引擎接口
- `lib/engine/method_channel_video_engine.dart` — 平台通道 adapter
- `lib/models/process_request.dart` — 已有 `CropRect`、crop/trim wire contract
- `lib/models/audio_extract_request.dart` — M3 音频请求
- `lib/logic/compression_planner.dart` — 输出尺寸、码率、估算和 capability fallback
- `lib/logic/audio_extract_planner.dart` — M3 模式、文件名和估算

### Android/Kotlin

- `EngineChannel.kt` — MethodChannel/EventChannel、I/O routing、snapshot/readback
- `ProcessingRuntime.kt` / `ProcessingRegistry.kt` — 进程级任务所有权和状态
- `ProcessingService.kt` — 前台服务、通知、终态和 recovery observer
- `TranscodeEngine.kt` — Media3 视频转码、codec preflight、effects、发布
- `AudioExtractionEngine.kt` — AAC copy / AAC encode
- `AudioSampleCopyLoop.kt` / `AudioSampleDigest.kt` — sample copy 和完整性证据
- `AudioMetadataReader.kt` — 音频 metadata/物理扫描 fallback
- `VerifiedAudioInfoCache.kt` — 发布成功后的一次性 verified metadata handoff
- `MediaStoreSaver.kt` — 默认 MediaStore 与 SAF document-tree 发布
- `TaskRecoveryStore.kt` / `RecoveryIoCoordinator.kt` / `VideoSlimApplication.kt` — durable journal、进程级 reconciliation gate 与启动对账

## 6. M3 验证状态

发布代码 `19abfb7...`：

- Flutter tests：191/191；
- Android JVM tests：307/307；
- analyze、format、debug/release lint、Debug/Release assemble、ARM64 APK：PASS；
- APK ZIP、zipalign、v2 signature、package/version/SDK/ABI、permission diff、secret scan：PASS；
- exact-SHA 双复审：PASS / PASS，0 blocker；
- 项目所有者：M3 测试成功，接受为 private scope。

远端 CI 不是全绿：主 Flutter/Android job 全部通过，但 API 35 x86_64 instrumentation job 因 runner 中 `sdkmanager: command not found` 在该 job 的应用/instrumentation 构建前失败，emulator instrumentation 未执行。

## 7. 不要误读的事实

- “M3 接受”不等于每一个空白测试矩阵行都通过；当前聊天未附逐项设备/F19 证据。
- 原 PRD 的“一小时 AAC copy <10 秒”没有被证明。安全实现包含三次物理 sample 证据路径，长媒体可能明显更慢。
- `1.4.3+18` 只消除成功后 `getAudioInfo` 的重复扫描，不删除发布前完整校验。
- Task 3 Slice B 未集成；其 worktree/patch 是冻结研究，不是产品代码。
- Release 使用 Debug certificate，不是生产签名。
- Crop/trim 未实现。Dart contract 已预留不代表 native 支持。

## 8. M4 裁剪扩展边界

若下一任务是画面裁剪，不应重写架构：

```text
裁剪 UI → display-oriented CropRect
→ CompressionPlanner 以裁剪后尺寸规划
→ ProcessRequest.crop
→ Kotlin 严格解析/旋转坐标换算
→ Media3 Effects: crop → Presentation/scale
→ 现有 Transformer/verify/publication/recovery
```

已存在：

- `CropRect` 和平台 wire map；
- 首页 disabled crop card；
- `ProcessRequest` 的 crop/trim keys；
- `TranscodeEngine` 的 Media3 `Effects`/`Presentation` seam。

尚需实现：裁剪编辑器、预览坐标纯函数、Kotlin display→NDC 转换、边界/偶数尺寸校验、planner 的裁剪后 geometry、effect order、snapshot/recovery 和横屏/竖屏/rotation 真机矩阵。

不要生成“裁剪中间视频”再二次压缩；PRD 明确要求裁剪、缩放、压缩一次转码完成。

## 9. 已知债务和下一 AI 的工作规则

- 先读 `docs/known-debt.md`；不要恢复或合并冻结的 Slice B。
- M3 已接受，但任何 M4/新 hardening/refactor/migration 仍需项目所有者明确批准。
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
