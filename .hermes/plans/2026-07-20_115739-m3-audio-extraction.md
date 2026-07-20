# VideoSlim M3 音频提取 Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 在不回归 M2 视频压缩、安全发布和恢复契约的前提下，为 VideoSlim 增加 `.m4a` 音频提取：默认 AAC 无损直提，以及 AAC 192/128/96/64 kbps 有损转码。

**Architecture:** 保留一个进程内、单任务的前台处理服务，但把任务契约扩展为 `videoCompression | audioExtraction`。模式 A 使用 Android `MediaExtractor + MediaMuxer` 直接复制 AAC 压缩样本；模式 B 使用 Media3 Transformer 1.10.1 的 audio-only 管线（底层 MediaCodec）解码并重编码 AAC。MediaStore/SAF 发布、长度回读、durable recovery journal、取消和通知继续共用一套基础设施，并以强类型 `OutputMediaSpec` 区分视频与音频。

**Tech Stack:** Flutter/Dart、Kotlin/JVM 17、Android API 26–36、MediaExtractor、MediaMuxer、MediaCodec（经 Media3 Transformer 1.10.1）、MediaStore、SAF、JUnit 4、Flutter widget tests。

---

## 0. 外部复审采纳矩阵（2026-07-20）

| 复审意见 | 决定 | 处理 |
|---|---|---|
| Media3 在 AAC→AAC 时可能直接 transmux，忽略目标码率 | **采纳并加强** | 已用本地 Media3 1.10.1 bytecode 确认：`TransformerUtil.shouldTranscodeAudio()` 在同 MIME、无 audio effects 时依赖 `EncoderFactory.audioNeedsEncoding()`；`DefaultEncoderFactory` 继承默认 `false`。Task 8 不再等待真机才修，而是从第一版就用 wrapper 强制返回 `true`，四档真机大小单调仍是验收门禁。 |
| copy 未知码率时用整个视频大小会过度拒绝 | **采纳并修订** | 不再使用源视频大小。已知 bitrate 用 `bitrate × duration × 120%`；未知用保守 `512 kbps × duration × 120%`，再加容器开销和既有 storage headroom。512 kbps 比建议的 320 kbps 更保守，但仍与 6 GB 视频体积解耦。 |
| 成功页 metadata 来源矛盾 | **采纳** | 固定为发布成功后调用 Task 6 的 `getAudioInfo(outputUri)`；读取失败不得回滚已发布成功，只降级显示实际文件名和保存位置。 |
| 三路 exact-SHA 复审可减为一路 | **不采纳** | 本里程碑同时改动 sample/PTS、M2 高风险 publication/recovery、Flutter retry/state 三个独立故障域。保留三路；每路收窄范围，避免一次泛化审查超时。 |
| 预制非 AAC / 无音轨样本 | **采纳** | Task 12 增加 `tool/generate_m3_fixtures.sh`，使用 VPS 已安装的 FFmpeg 6.1.1 生成 AAC stereo/mono、Opus WebM、无音轨 MP4；脚本入库，二进制只放验收 artifacts。 |
| 回写 PRD §5.3/§5.4 并恢复时间戳命名 | **采纳** | Task 1 明确同步接口注释、wire contract、进度事件和 F7 命名；默认音频名改为 `<safe stem>_slim_yyyyMMdd_HHmmss.m4a`，provider collision suffix 仍作为第二层保护。 |

---

## 1. 产品范围与已定决策

### 1.1 M3 范围

- 模式 A：源音轨为 AAC（`audio/mp4a-latm`）时直接重封装为 `.m4a`，不解码、不重编码。
- 模式 B：重编码为 AAC-LC，目标码率固定提供 `192000 / 128000 / 96000 / 64000` bps。
- 首页提供“提取音频”入口；已导入视频的信息区也提供同一入口。
- 默认保存到 `系统音频 > Music > VideoSlim`；如果用户已选择自定义 SAF 文件夹，则沿用该文件夹。
- 成功页支持打开、分享和再次提取，并显示实际文件名、位置、大小、时长、采样率、声道和音频码率。
- 复用 M2 的前台服务、通知、取消、进度、任务恢复、发布补偿和 F19 日志。

### 1.2 明确不做

- 不输出 MP3；MP3 仍属于 F15。
- 不做音轨选择、多语言音轨 UI；M3 使用源文件顺序中的第一条音频轨。
- 不做音量、降噪、声道混音、采样率选择、标签/封面编辑。
- 不做 trim；M4 再统一处理时间裁剪。
- 不静默从模式 A 降级到模式 B。源音轨不是 AAC 时，明确提示并让用户选择模式 B。
- 不根据实际输出码率 fail closed。码率仅作为编码目标和估算依据；只对空文件、无音轨、非 AAC 输出、严重截断等结构性错误拒绝发布。

### 1.3 边界规则

- 模式 B 首版保证单声道和立体声。超过 2 声道时返回可理解的 `AUDIO_CHANNEL_LAYOUT_UNSUPPORTED`，不做隐式下混。
- 模式 A 保留样本相对时间戳，并将第一条有效音频样本归零；F19 记录原始 first/last PTS。
- 发布前必须确认临时 `.m4a`：非空、只有可识别 AAC 音轨、时长有效；输出时长与源音轨 span 差异超过 1 秒则返回 `AUDIO_OUTPUT_INVALID`。
- 用户取消、进程死亡、短写或保存失败时，不发布公共半成品，不误删源视频或非本任务文件。
- 继续保持离线：不得新增 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE`。

---

## 2. Wire contract

### 2.1 Flutter → native `extractAudio`

```dart
{
  'uri': 'content://...',
  'outputFileName': '原文件名_slim_20260720_115739.m4a',
  'destination': {
    'treeUri': null, // 或持久化 SAF tree URI
    'label': '系统音频 > Music > VideoSlim',
  },
  'audio': {
    'mode': 'copy', // copy | aac
    'bitrate': null, // aac 时严格为 192000/128000/96000/64000
  },
}
```

### 2.2 Native → Flutter task event/snapshot

在现有 map 上新增：

```dart
{
  'taskKind': 'audio_extraction', // video_compression | audio_extraction
  'retryRequest': { ... },
}
```

约束：

- `taskKind=video_compression` 时，`retryRequest` 解析为 `ProcessRequest`，保留现有 decoder/encoder 字段。
- `taskKind=audio_extraction` 时，`retryRequest` 解析为 `AudioExtractRequest`；Flutter 不显示视频 Decoder/Encoder 信息。
- 为兼容升级前的 snapshot，Dart 在缺失 `taskKind` 时只可回退为 `video_compression`；native 新事件始终显式发送。

### 2.3 稳定错误码

新增：

- `AUDIO_TRACK_MISSING`：视频没有可提取的音轨。
- `AUDIO_COPY_UNSUPPORTED`：源音轨不是 AAC，需改用 AAC 转码。
- `AUDIO_CHANNEL_LAYOUT_UNSUPPORTED`：模式 B 遇到超过 2 声道。
- `AUDIO_DECODING_FAILED`：源音频 Decoder 失败。
- `AUDIO_ENCODING_FAILED`：AAC Encoder 失败或不可用。
- `AUDIO_OUTPUT_INVALID`：临时输出为空、没有 AAC 音轨或明显截断。

继续复用：`INSUFFICIENT_STORAGE`、source/provider 权限错误、`OUTPUT_PERMISSION_LOST`、`CANCELLED`、`UNKNOWN`。

---

## 3. 实施任务

### Task 1: 正式收口 M2，并冻结 M3 基线

**Objective:** 将“私有范围 M2 通过、扩展设备矩阵 deferred”写入规范，避免 M3 实现被旧阻塞文案反向否定。

**Files:**
- Modify: `docs/m2-completion-report.md`
- Modify: `docs/m2-device-acceptance.md`
- Modify: `docs/VideoSlim PRD.md`
- Create: `docs/m3-device-acceptance.md`

**Steps:**

1. 把 M2 状态改为 `ACCEPTED — private scope`，记录 VBR 为产品决定。
2. 将同源严格 A/B/C、software-only 真机、6 小时/50 GB、多 Provider/多 SoC 转到 non-blocking hardening backlog。
3. 在 PRD 的 M3 条目明确本计划 1.1–1.3 的范围、默认路径和不做项。
4. 同步修改 PRD §5.3 的 `extractAudio` 注释、§5.4 的 `extractAudio` request/进度 snapshot 契约、§6 数据模型，以及 F7 文件名示例；删除 `lossless: bool` 旧事实源，改为 `mode: copy|aac + destination`。
5. 固定默认命名为 `原文件名_slim_yyyyMMdd_HHmmss.m4a`；MediaStore/SAF 的 collision suffix 只处理同秒或既有重名。
6. 创建 M3 真机表：短片 copy/AAC、1 小时 copy、后台/锁屏、取消、默认/SAF 保存、无音轨、非 AAC、mono/stereo。
7. 运行：
   ```bash
   git diff --check
   ```
   Expected: exit 0。
8. Commit：
   ```bash
   git add docs/
   git commit -m "docs: close M2 private acceptance and define M3"
   ```

---

### Task 2: 建立 Dart 音频请求、任务类型和估算模型

**Objective:** 先用严格单测定义 M3 的 Flutter 业务契约，不连接 native。

**Files:**
- Create: `lib/models/task_kind.dart`
- Create: `lib/models/audio_extract_request.dart`
- Create: `lib/models/audio_extract_settings.dart`
- Create: `lib/logic/audio_extract_planner.dart`
- Modify: `lib/models/process_request.dart`（移除当前占位 `AudioExtractRequest`）
- Test: `test/models/audio_extract_request_test.dart`
- Test: `test/models/audio_extract_settings_test.dart`
- Test: `test/logic/audio_extract_planner_test.dart`

**Key API:**

```dart
enum TaskKind { videoCompression, audioExtraction }
enum AudioExtractMode { copy, aac }

final class AudioExtractRequest {
  const AudioExtractRequest({
    required this.uri,
    required this.outputFileName,
    required this.outputLocationLabel,
    this.outputTreeUri,
    required this.mode,
    this.bitrate,
  });

  Map<String, Object?> toChannelMap();
  factory AudioExtractRequest.fromChannelMap(Map<Object?, Object?> map);
}
```

`AudioExtractPlan` 至少包含：`available`、`reason`、`estimatedMinBytes`、`estimatedMaxBytes`、`requestedName`。copy bitrate 已知时，上界使用 `bitrate × duration × 120%`；未知时使用 `512000 bps × duration × 120%`，再加容器开销。UI 必须标为保守估算，不得把 fallback 伪装成源音轨的实际码率。

**TDD:**

1. 写失败测试：严格 keys、`.m4a` 安全文件名、copy bitrate 必须 null、aac bitrate allowlist、destination round-trip。
2. 写失败测试：AAC 源默认 copy；非 AAC 禁用 copy；无音轨不可开始；AAC 模式四个码率的估算单调且溢出饱和；copy 未知 bitrate 使用 512 kbps fallback 而不是源视频大小。
3. Run：
   ```bash
   flutter test test/models/audio_extract_request_test.dart test/models/audio_extract_settings_test.dart test/logic/audio_extract_planner_test.dart
   ```
   Expected before implementation: FAIL；完成后 PASS。
4. Commit：
   ```bash
   git add lib/models lib/logic test/models test/logic
   git commit -m "feat: define M3 audio extraction contracts"
   ```

---

### Task 3: 建立 Kotlin 音频请求与纯计划逻辑

**Objective:** 在启动服务前严格拒绝畸形请求，并为 storage/preflight 提供纯函数。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioExtractModels.kt`
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioExtractPlan.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessModels.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/AudioExtractModelsTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/AudioExtractPlanTest.kt`

**Key types:**

```kotlin
internal enum class TaskKind(val wireName: String) {
    VIDEO_COMPRESSION("video_compression"),
    AUDIO_EXTRACTION("audio_extraction"),
}

internal enum class AudioExtractMode(val wireName: String) { COPY("copy"), AAC("aac") }

internal data class AudioExtractRequest(
    val sourceUri: String,
    val outputFileName: String,
    val outputTreeUri: String?,
    val outputLocationLabel: String,
    val mode: AudioExtractMode,
    val bitrate: Int?,
)
```

`AudioExtractPlan` 使用 source audio duration/bitrate；空间门禁必须覆盖私有 temp 与公共输出重叠。copy 缺 bitrate 时使用 `CONSERVATIVE_UNKNOWN_COPY_BITRATE_BPS = 512_000`，乘 120% 后再加容器开销和既有 headroom；禁止退化为整个视频大小，也禁止把估算上界变成实际码率失败条件。

**TDD/verify:**

```bash
cd android
./gradlew :app:testDebugUnitTest \
  --tests com.videoslim.videoslim.AudioExtractModelsTest \
  --tests com.videoslim.videoslim.AudioExtractPlanTest \
  --console=plain
```

Expected: 新测试先 FAIL，最小实现后 PASS；现有 `ProcessModelsTest` 仍 PASS。

**Commit:** `feat: validate native audio extraction requests`

---

### Task 4: 将 registry/event/snapshot 扩展为多任务类型

**Objective:** 让视频压缩与音频提取共享生命周期，但 UI 和重试不会混淆任务类型。

**Files:**
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingRegistry.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingRuntime.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/EngineChannel.kt`
- Modify: `lib/models/progress_event.dart`
- Modify: `lib/models/task_snapshot.dart`
- Modify: `lib/engine/video_engine.dart`
- Modify: `lib/engine/method_channel_video_engine.dart`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/ProcessingRegistryTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/SerializableArgumentsTest.kt`
- Test: `test/models/progress_event_test.dart`
- Test: `test/models/task_snapshot_test.dart`
- Test: `test/engine/method_channel_video_engine_test.dart`

**Steps:**

1. 先加失败测试：reservation 保存 `taskKind`；audio retry map 原样 round-trip；video 旧 map 缺 taskKind 时 Dart 只回退为 video。
2. `TaskRuntimeSnapshot` 增加 required native `taskKind`；`toProgressMap/toSnapshotMap` 显式输出。
3. `ProcessingRuntime.launch` 接收 task kind、通用 source/name/location/retry map，并写 `EXTRA_TASK_KIND`。
4. `EngineChannel.extractAudio` 替换占位错误：严格 parse、检查 output destination/通知/legacy 权限，然后 reserve + start foreground service。
5. Dart `TaskSnapshot` 使用 sealed retry request 或两个 nullable typed fields，禁止按错误类型解析另一种 request。
6. Run targeted Flutter/JVM tests，Expected PASS。
7. Commit：`refactor: generalize task snapshots for audio extraction`。

**Safety:** 在本任务结束时 `process()` 的 wire map 必须逐字保持兼容；普通视频 retry 和 software compatibility retry 测试不得改成宽松断言。

---

### Task 5: 将发布和 recovery journal 泛化到 `.mp4` 与 `.m4a`

**Objective:** 音频复用经过 M2 加固的发布安全，而不是复制一套较弱实现。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/OutputMediaSpec.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/MediaStoreSaver.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TaskRecoveryStore.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/OrphanCleanup.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/MediaPublicationCopyTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/TaskRecoveryCodecTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/OrphanCleanupPolicyTest.kt`

**Key type:**

```kotlin
internal enum class OutputMediaSpec(
    val wireName: String,
    val extension: String,
    val mimeType: String,
    val relativePath: String,
) {
    VIDEO_MP4("video_mp4", ".mp4", "video/mp4", "Movies/VideoSlim/"),
    AUDIO_M4A("audio_m4a", ".m4a", "audio/mp4", "Music/VideoSlim/"),
}
```

**Steps:**

1. 写失败测试覆盖 audio scoped URI、SAF URI、legacy `Music/VideoSlim` 路径和错误扩展名的 `SKIP_UNSAFE`。
2. 把 `publishVideo` 底层抽成 `publishMedia(temp, name, outputTreeUri, spec, cancel)`；保留 `publishVideo` wrapper，确保 M2 调用点不变。
3. Android Q+：video 使用 `MediaStore.Video`；audio 使用 `MediaStore.Audio`。共享 `MediaStore.MediaColumns` 查询字段。
4. SAF `createDocument` 使用 spec MIME；仍要求回读完整长度。
5. Android 8–9 legacy 根据 spec 选择 `DIRECTORY_MOVIES` 或 `DIRECTORY_MUSIC` 和对应 collection。
6. recovery codec 写 V2 `mediaKind`；decode 必须继续接受 V1 并映射为 `VIDEO_MP4`，不能把升级前未完成的视频记录直接丢掉。
7. temp name allowlist 接受 UUID `.mp4|.m4a`；output name、MediaStore URI、relative path、legacy path均按 `mediaKind` 精确验证。
8. orphan cleanup 对 `content://media/external/video/media/N` 与 `.../audio/media/N` 分别验证；不允许 kind 与 URI 混配。
9. Run：
   ```bash
   cd android
   ./gradlew :app:testDebugUnitTest \
     --tests com.videoslim.videoslim.MediaPublicationCopyTest \
     --tests com.videoslim.videoslim.TaskRecoveryCodecTest \
     --tests com.videoslim.videoslim.OrphanCleanupPolicyTest \
     --console=plain
   ```
10. Commit：`refactor: publish video and audio with one safe journal`。

---

### Task 6: 实现音轨 metadata 与输出结构校验

**Objective:** 为两种模式提供同一事实源，并在公开发布前拒绝空、无 AAC 音轨或截断的临时文件。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioMetadataReader.kt`
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioOutputVerifier.kt`
- Create: `lib/models/audio_info.dart`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/EngineChannel.kt`（`getAudioInfo`）
- Modify: `lib/engine/video_engine.dart`
- Modify: `lib/engine/method_channel_video_engine.dart`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/AudioOutputPolicyTest.kt`
- Test: `test/models/audio_info_test.dart`
- Test: `test/engine/method_channel_video_engine_test.dart`

**Data:** track index、MIME、durationUs、first/last sample time、bitrate、sampleRate、channelCount、maxInputSize。

**Policy:**

```kotlin
verify(
    fileBytes > 0,
    outputMime == "audio/mp4a-latm",
    outputAudioTrackCount == 1,
    outputVideoTrackCount == 0,
    outputDurationUs > 0,
    abs(outputDurationUs - sourceAudioSpanUs) <= 1_000_000,
)
```

测试纯 policy；真实 `MediaExtractor` 适配器通过短片真机测试验证。

**Commit:** `feat: inspect and verify extracted audio`

---

### Task 7: 实现模式 A AAC 无损直提

**Objective:** 用 bounded worker 在不创建 Codec 的情况下复制 AAC 样本并产生可取消进度。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioTrackCopier.kt`
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioExtractEngine.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/AudioTrackCopierPolicyTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/PublicationBoundaryTest.kt`

**Implementation rules:**

1. `MediaExtractor` 从 `content://` descriptor + offset/length 打开；只选择第一条音频轨。
2. MIME 非 `audio/mp4a-latm` 时返回 `AUDIO_COPY_UNSUPPORTED`。
3. buffer 使用 `KEY_MAX_INPUT_SIZE`，缺失时保守 fallback；设置硬上限，超出返回 unsupported，避免恶意 metadata 申请巨量内存。
4. `MediaMuxer(MUXER_OUTPUT_MPEG_4)` 写 `.m4a`；sample PTS 减 first valid PTS；保持单调，忽略零字节 EOS 样本。
5. 每个 sample 检查 cancel/interrupted；progress = sampleTime/sourceSpan，限制 0–99。
6. `stop/release/close` 全部在 finally；只删除本任务 temp。
7. copy 完成后调用 Task 6 verifier，再进入 Task 5 publication。
8. 任何 publish/cancel race 继续使用 `PublicationBoundary`，只能出现一个终态。

**TDD:** 对 sample sequence 抽象使用 fake reader/writer，覆盖 PTS 归零、单调、flag/size、取消和 writer failure；Android 类只做适配。

**Verify:** targeted JVM tests + `:app:assembleDebug`。

**Commit:** `feat: extract AAC audio without transcoding`

---

### Task 8: 实现模式 B AAC 转码

**Objective:** 复用 Media3/MediaCodec 完成 audio-only AAC-LC 转码，避免自写不可靠的 PCM pump。

**Files:**
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/AudioExtractEngine.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/HardwareCodecPolicy.kt`（让现有 `LoggingEncoderFactory` 可显式 force audio encoding，不改变视频 allowlist）
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`（现有 M2 `audioMode=reencode` 同样显式 force；视频 VBR 策略不变）
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/AudioExtractPlanTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/EngineErrorMapperTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/AudioEncodingPolicyTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/HardwareCodecPolicyTest.kt`

**已确认的 Media3 1.10.1 风险：**

- 本地 AAR bytecode 显示，同 MIME AAC→AAC、无 audio effects 时，`TransformerUtil.shouldTranscodeAudio()` 最终返回 false，除非 `Codec.EncoderFactory.audioNeedsEncoding()` 返回 true。
- `DefaultEncoderFactory` 没有 override 该方法，继承的默认值是 false；仅调用 `setRequestedAudioEncoderSettings()` 不足以保证创建 encoder。
- 因此不能把四档真机测试当成唯一兜底，更不能先发布一个可能静默透传的实现。

**Transformer configuration:**

```kotlin
val delegate = DefaultEncoderFactory.Builder(context)
    .setRequestedAudioEncoderSettings(
        AudioEncoderSettings.Builder().setBitrate(request.bitrate!!).build()
    )
    .setEnableFallback(true)
    .build()

val encoderFactory = LoggingEncoderFactory(
    delegate = delegate,
    logger = logger,
    forceAudioEncoding = true,
)

val transformer = Transformer.Builder(context)
    .setEncoderFactory(encoderFactory)
    .setAudioMimeType(MimeTypes.AUDIO_AAC)
    .build()

val item = EditedMediaItem.Builder(MediaItem.fromUri(request.sourceUri))
    .setRemoveVideo(true)
    .build()
```

**Rules:**

- preflight channelCount 1–2；否则 `AUDIO_CHANNEL_LAYOUT_UNSUPPORTED`。
- `LoggingEncoderFactory.audioNeedsEncoding()` 必须返回 `forceAudioEncoding || delegate.audioNeedsEncoding()`；M3 AAC 模式传 true。优先使用此官方决策 hook，不添加可能改变 PCM 的 no-op processor。
- 增加纯单测证明 force flag 会委托 codec creation 且 `audioNeedsEncoding()==true`；同时给现有 M2 `audioMode=reencode` 补回归测试，并让该路径也显式 force，避免同一潜在透传缺陷继续存在。
- 不选择或限制视频 decoder/encoder；audio-only 任务不创建视频 Codec。
- `LoggingEncoderFactory` 记录实际 audio encoder；DefaultDecoderFactory listener 记录实际 audio decoder。
- Media3 decoder errors 映射 `AUDIO_DECODING_FAILED`，encoder errors映射 `AUDIO_ENCODING_FAILED`。
- 完成后仍调用 Task 6 verifier；不以文件大小偏差拒绝。

**Verify:** targeted tests + assemble；真机必须对同一个 AAC/stereo 输入依次跑 192/128/96/64 kbps，确认 F19 每次都记录实际 audio decoder 和 encoder，且输出 bytes 随目标码率严格单调递减。四档大小相同或没有 encoder 日志均为 BLOCKER。

**Commit:** `feat: transcode extracted audio to AAC`

---

### Task 9: 将 AudioExtractEngine 接入前台服务、取消与通知

**Objective:** 让 M3 获得与 M2 相同的后台和恢复行为，并保证任务类型不会串线。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/MediaTaskController.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingService.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingRuntime.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingNotification.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/ProcessingNotificationTextTest.kt`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/ProcessingRegistryTest.kt`

**Steps:**

1. 用小接口统一 active task 的 `cancel/dispose`，不要把 audio 分支塞进 `TranscodeEngine`。
2. Service 根据已验证 `EXTRA_TASK_KIND` 解析唯一 request，并只启动对应 engine。
3. Audio phases 使用现有 wire：`preparing → encoding → publishing → finished`；UI 文案将 encoding 显示为“正在提取”或“正在转换音频”。
4. 通知按 task kind 输出：
   - `正在提取音频` / `正在转换音频`
   - `音频已提取并保存`
   - `音频提取已取消`
   - 失败文案不得显示视频编码方式。
5. FGS timeout、onDestroy、cancel pending intent、WakeLock 只能终结当前 taskId 一次。
6. 写失败通知测试后实现；保留全部现有视频通知断言。
7. Commit：`feat: run audio extraction in the foreground service`。

---

### Task 10: 构建 Flutter M3 入口与设置 UI

**Objective:** 提供最小、清晰、不会破坏 M2 首页状态机的音频提取流程。

**Files:**
- Create: `lib/widgets/audio_extract_card.dart`
- Create: `lib/widgets/audio_extract_sheet.dart`
- Modify: `lib/widgets/video_info_card.dart`
- Modify: `lib/state/home_flow_state.dart`
- Modify: `lib/screens/home_screen.dart`
- Test: `test/widget_test.dart`
- Test: `test/models/audio_extract_settings_test.dart`

**UX:**

- 首页空态增加“提取音频”；点击后仍使用当前视频 picker，metadata 成功后打开 sheet。
- `VideoInfoCard` 在有音轨时显示“提取音频”；无音轨时禁用并显示“这个视频没有可提取的音轨”。
- sheet 默认选“无损直提”；非 AAC 源禁用该项并预选 AAC 128 kbps。
- 模式 A 显示“速度最快，不改变音质”；模式 B 显示目标码率和保守大小区间。
- 开始前显示输出名和位置。默认文件名：`<safe stem>_slim_yyyyMMdd_HHmmss.m4a`。
- 音频任务运行时隐藏视频压缩设置，复用进度/取消；页面恢复必须依据 snapshot task kind。
- 错误页：`AUDIO_COPY_UNSUPPORTED` 提供“改用 AAC 转码”；其他可重试错误保留同一 audio request。
- 成功页显示“音频已保存”，发布成功后固定调用 Task 6 的 `getAudioInfo(outputUri)` 获取大小、时长、采样率、声道和码率；不得调用 `getVideoInfo`。如果 metadata readback 失败，任务仍保持 success，页面保留实际文件名、保存位置、打开和分享入口，并仅隐藏不可确认字段。

**TDD widget cases:**

1. 首页和信息区两个入口指向同一设置 sheet。
2. 无音轨禁用。
3. AAC 默认 copy；非 AAC 自动选 AAC 128 且 copy 不可点。
4. request map 包含 destination 和 `.m4a`。
5. 运行中取消、恢复、成功、失败文案均按 audio kind。
6. audio retry 不变成 video process；video compatibility retry 不出现在 audio error。

**Verify:**

```bash
flutter test test/widget_test.dart test/models/audio_extract_settings_test.dart
flutter analyze
```

**Commit:** `feat: add M3 audio extraction workflow`

---

### Task 11: 泛化打开/分享与结果 metadata

**Objective:** 系统播放器和分享 sheet 按真实 MIME 工作，音频结果可核验。

**Files:**
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/MediaActionsChannel.kt`
- Modify: `lib/engine/media_actions.dart`（文档/参数类型，wire 方法名可不变）
- Modify: `lib/screens/home_screen.dart`
- Test: `android/app/src/test/kotlin/com/videoslim/videoslim/MediaActionPolicyTest.kt`
- Test: `test/engine/media_actions_test.dart`
- Test: `test/widget_test.dart`

**Rules:**

- native 使用 `ContentResolver.getType(uri)`，只接受 `video/mp4` 或 `audio/mp4`；ACTION_VIEW/ACTION_SEND 使用真实 MIME。
- chooser 文案按媒体类型：“分享压缩后的视频”或“分享提取的音频”。
- 不扩大删除源文件权限；`deleteSource` 仍只服务用户选中的原视频。
- 打开/分享失败必须给普通用户文案，技术细节仅 F19。

**Commit:** `fix: open and share audio outputs with their MIME type`

---

### Task 12: 完整门禁、独立复审与私有 APK

**Objective:** 用不可变源码 SHA 证明 M3 软件门禁，并生成 Pixel 私有验收包。

**Steps:**

1. 创建 `tool/generate_m3_fixtures.sh`，用 FFmpeg lavfi 生成并用 `ffprobe` 验证以下短样本；脚本入库，二进制写到 `/root/artifacts/videoslim/m3/fixtures/`，不得提交二进制：
   - `m3-aac-stereo.mp4`
   - `m3-aac-mono.mp4`
   - `m3-opus.webm`
   - `m3-no-audio.mp4`

   脚本主体固定为以下可复现命令（FFmpeg 只用于 VPS 测试数据生成，不进入 App 或 APK）：

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   out="${1:-/root/artifacts/videoslim/m3/fixtures}"
   duration=12
   mkdir -p "$out"

   ffmpeg -y -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
     -f lavfi -i "aevalsrc=0.15*sin(2*PI*440*t)|0.15*sin(2*PI*660*t):s=48000:d=$duration" \
     -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -b:a 128k -shortest \
     "$out/m3-aac-stereo.mp4"

   ffmpeg -y -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
     -f lavfi -i "sine=frequency=880:sample_rate=44100:duration=$duration" \
     -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -b:a 96k -shortest \
     "$out/m3-aac-mono.mp4"

   ffmpeg -y -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
     -f lavfi -i "sine=frequency=550:sample_rate=48000:duration=$duration" \
     -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -c:a libopus -b:a 96k -shortest \
     "$out/m3-opus.webm"

   ffmpeg -y -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
     -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
     "$out/m3-no-audio.mp4"

   for file in "$out"/m3-*; do
     ffprobe -v error -show_entries stream=index,codec_type,codec_name,channels \
       -of compact=p=0:nk=0 "$file"
   done
   ```
2. 版本从 `1.3.5+14` 递增为 `1.4.0+15`。
3. Run full Flutter gate：
   ```bash
   dart format --output=none --set-exit-if-changed lib test
   flutter analyze
   flutter test
   ```
4. Run full Android gate：
   ```bash
   cd android
   ./gradlew :app:testDebugUnitTest \
     :app:lintDebug :app:lintRelease \
     :app:assembleDebug :app:assembleRelease \
     --console=plain
   ```
5. 解析 XML，要求 0 failures/errors/skipped；运行 `git diff --check` 和凭据/危险权限扫描。
6. 创建精确源码提交；提交后不得再改源码再复用旧门禁。
7. 对 exact SHA 发起三路窄范围 blocker review（本计划保留三路，因为三个故障域相互独立且 publication/recovery 具有误删风险）：
   - MediaExtractor/Muxer sample/PTS/cancel 正确性；
   - audio MediaStore/SAF/recovery/orphan 安全；
   - Flutter taskKind/retry/恢复/文案产品契约。
8. 所有 blocker 修复后重新执行 3–7。
9. 构建：
   ```bash
   flutter build apk --release --target-platform android-arm64
   ```
10. 核验 package、`1.4.0+15`、SDK 26/36/36、仅 arm64、zipalign、v2 signature、forbidden permissions、SHA-256 和 bytes。
11. 保存：
    ```text
    /root/artifacts/videoslim/m3/VideoSlim-M3-arm64-v1.4.0.apk
    /root/artifacts/videoslim/m3/verification.json
    ```
12. 更新 `docs/m3-device-acceptance.md` 和新建 `docs/m3-completion-report.md`；文档提交不得改变 APK 源码身份。

---

## 4. 真机验收顺序

1. 30–120 秒 AAC/stereo 视频：copy 3 次；打开、分享、时长和音质检查。
2. 同一视频：AAC 192/128/96/64，各检查输出可播放、无爆音/变速、大小随码率单调。
3. mono AAC；确认声道不被错误变为 stereo。
4. 使用 `m3-opus.webm`：copy 被阻止，显式选择模式 B 后成功。
5. 使用 `m3-no-audio.mp4`：入口/engine 均明确阻止。
6. 后台、锁屏各一次；通知进度单调，返回 App 恢复同一 taskId。
7. 10–30% 与 publishing 阶段取消；无 temp、pending row 或 SAF 半成品。
8. 默认 `Music/VideoSlim` 和自定义 SAF 各覆盖 copy/AAC。
9. 一小时 AAC 视频 copy：目标 `<10 s`；若存储/Provider 性能导致超时，记录真实耗时，不伪造 PASS。
10. F19 检查：task kind、模式、源/输出 MIME、采样率、声道、请求码率、实际 codec（模式 B）、first/last PTS、输出 bytes/duration、发布路径和清理终态。

**M3 PASS 条件：** 自动化和三路 exact-SHA 复审通过；Pixel 上模式 A/B、后台/锁屏、取消、默认/SAF、错误边界通过；1 小时 copy 达到 PRD 性能目标。多音轨选择、>2 声道混音和 MP3 不属于 M3 PASS 条件。

---

## 5. 主要风险与控制

- **回归 M2 安全发布：** 发布泛化必须保留 video wrapper 和全部旧测试；先测后改。
- **recovery V1 升级：** V2 decoder 必须读取 V1 为 video；不能用“版本不支持”丢弃现存有效证据。
- **Audio MediaStore 所有权误判：** record 必须携带 media kind，URI/path/extension/relative path 四者严格一致才可删除。
- **MediaMuxer 时间戳：** 把 sample pump 抽成纯接口，单测首 PTS 归零和 monotonic；真机比对时长。
- **Media3 audio-only 意外创建视频 Codec：** `setRemoveVideo(true)`，F19 和真机日志证明只创建 audio decoder/encoder。
- **Media3 AAC→AAC 静默透传：** 已确认仅设置相同 audio MIME 和 bitrate settings 不会自动 force transcode；通过 `EncoderFactory.audioNeedsEncoding()==true` 强制路径，并以四档同源大小单调 + 实际 encoder 日志双重验收。
- **状态机串线：** taskKind required；audio retry 与 video retry 分开解析，拒绝错误 map。
- **大文件内存：** 每次只处理一个 compressed sample/codec buffer；禁止把整条音轨读入内存。
- **码率与可听性：** 不做 CBR 或输出体积硬拦截；用户选择目标 AAC bitrate，真机以听感、时长和合理大小验收。

## 6. 实施停止条件

出现以下任一情况立即停止并请求产品/技术决策，而不是静默扩 scope：

- Media3 1.10.1 audio-only 无法稳定输出 `.m4a`；
- Android 设备常见视频包含 >2 声道且必须在 M3 支持；
- 默认保存位置需继续放在 Movies 而不是 Music；
- 一小时 AAC copy 在 Pixel 上无法接近 10 秒且瓶颈来自必须的 temp→public 双写；
- 泛化 recovery 会削弱 M2 误删保护或无法兼容 V1 journal。
