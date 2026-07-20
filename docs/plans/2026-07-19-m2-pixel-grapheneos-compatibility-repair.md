# M2 Pixel / GrapheneOS 兼容性修复实施计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 修复 Pixel 10 Pro、GrapheneOS、Android 17 上长视频压缩过程中 Google Codec2 软件解码器/编码器被提前释放的问题，同时补强 SAF 权限证据、错误分类、ETA 和用户文案。

**Architecture:** 保持 Jetpack Media3 Transformer 1.10.1、前台服务、私有临时文件和 MediaStore 发布架构不变。原生层在开始前验证源 URI 可读性并显式限制视频解码/编码候选为支持当前任务的硬件 Codec；运行失败时重新探测源 URI，把权限/Provider、解码、编码和存储故障分开。Flutter 层使用只在进度推进时采样的低精度 ETA，并仅展示用户可理解的阶段和恢复建议。

**Tech Stack:** Flutter/Dart、Kotlin、Android SAF/ContentResolver、MediaCodecList、Media3 Transformer 1.10.1、JUnit 4、Flutter test。

**实施状态（2026-07-20）：** Task 1–8 的源码、自动化门禁、独立复审和静态核验已完成。真机发现 `1.2.3+6` 的严格硬件标志与旧名称启发式组合将 HEVC/H.264 候选全部过滤；该问题已在 `1.2.5+8` 修复。当前源码提交为 `ce679b21fba70fb54678175ef1efa231d68b7e41`，APK SHA-256 为 `13b23dc929d75aef8c74c575111c2178d9115f69c4e6fb0088e9cc4290c0446a`。Pixel 10 Pro / GrapheneOS / Android 17 真机短片和约 98 分钟目标视频仍待复测，完成前不得标记 M2 最终 PASS。

---

## 事故基线与约束

真机：Pixel 10 Pro，GrapheneOS，Android 17。输入为约 98 分钟、8.89 GB、720×1280、30 fps、SDR H.264/AAC 视频。

已确认失败：

- M1：`c2.google.hevc.encoder` 在 21% 进入 Released state；同参数另一次成功。
- M2 均衡：`c2.google.avc.decoder` 在 64% 进入 Released state，被错误显示为源文件损坏。
- M2 均衡：`c2.google.hevc.encoder` 在 38% 进入 Released state，Media3 4002。
- M2 画质优先：`c2.google.hevc.encoder` 在 96% 进入 Released state，Media3 4002。

硬约束：

- 不引入 FFmpeg/GPL，不上传视频，不增加 `INTERNET`、`READ_MEDIA_VIDEO` 或 `MANAGE_EXTERNAL_STORAGE`。
- 保持 `content://`、系统选择器、私有临时输出、`Movies/VideoSlim` 发布和现有恢复清理边界。
- Media3 1.10.1 是当前最新稳定版；1.11.0 仍为 beta，不直接升级正式路径。
- 不静默用软件 Codec 或改变自定义设置；预设发生 HEVC→H.264 兼容降级时必须明确告知。
- VPS 自动化不能代替 Pixel/GrapheneOS 的最终运行验证。

## 验收标准

1. SAF 选择结果明确记录持久读取授权是否成功；任务开始前必须能重新打开源 URI。
2. 失败时重新打开 URI：权限丢失、文件不可用、Provider I/O 与 Codec 失败映射为不同稳定错误。
3. F19 记录设备/系统、候选和实际视频 decoder/encoder、硬件/软件/vendor 属性、完整 ExportException 诊断。
4. 如果存在支持的硬件视频 Codec，实际任务不得选择 `c2.google.*`、`c2.android.*` 或 `OMX.google.*` 软件视频 Codec。
5. 没有可靠硬件 AVC decoder 或目标 encoder 时在开始阶段快速失败/明确降级，不允许长时间软件转码后才失败。
6. Media3 3001/3002 不再笼统显示“文件损坏”；4002 不再显示 `UNKNOWN` 或面向用户暴露 Media3。
7. ETA 只在进度推进时采样；阶梯进度、停滞、突增、速度变化和发布阶段均有测试。
8. 页面和通知不出现 Media3、Codec、encoder、前台服务、WakeLock、Android 时限等内部术语。
9. Flutter format/analyze/full tests、Android JVM tests、Debug/Release Kotlin compile、lint、manifest/ABI/signature/APK 校验全部通过。
10. 生成版本号递增的 arm64 Release APK；旧 APK 明确撤销，不覆盖为同一可辨识版本。

---

### Task 1: 固化修复计划和失败验收矩阵

**Objective:** 把已确认真机证据、修复边界和新的 Pixel/GrapheneOS 验收行写入 canonical docs。

**Files:**
- Create: `docs/plans/2026-07-19-m2-pixel-grapheneos-compatibility-repair.md`
- Modify: `docs/m2-device-acceptance.md`
- Modify: `docs/m2-completion-report.md`

**Steps:**
1. 保存本计划。
2. 把旧 M2 APK 标记为“自动化通过但 Pixel/GrapheneOS Codec 验收失败”。
3. 增加 SAF grant、失败时 URI 重开、实际硬件 decoder/encoder、前后台/锁屏完整长片测试。
4. 运行 Markdown 路径和 `git diff --check`。
5. 提交：`docs: plan Pixel GrapheneOS compatibility repair`。

### Task 2: 建立可测试的源 URI 访问诊断

**Objective:** 在进入 Media3 前及失败时提供可验证的 SAF/Provider 证据。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/SourceAccessProbe.kt`
- Create: `android/app/src/test/kotlin/com/videoslim/videoslim/SourceAccessProbePolicyTest.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/VideoPickerChannel.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`

**Steps:**
1. 先写纯 Kotlin policy 测试：可读、权限丢失、文件不存在、Provider I/O、不可 seek、未知长度。
2. 实现 `SourceAccessProbeResult` 和 `SourceAccessProbe`：检查 persisted read grant、`openFileDescriptor("r")`、`statSize`、seek 能力和异常类别。
3. VideoPicker 仅在成功持久化且 resolver 清单确认后记录成功；Photo Picker 当前授权单独记录。
4. prepare 阶段探测失败时不启动 Transformer。
5. ExportException 后在 I/O 线程重探测，再回主线程完成稳定错误映射。
6. 运行定向 JVM 测试和 Kotlin 编译。
7. 提交：`fix: verify SAF source access lifecycle`。

### Task 3: 拆分稳定错误分类和用户提示

**Objective:** 停止误报源文件损坏并提供可行动的普通用户提示。

**Files:**
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessModels.kt`
- Modify: `android/app/src/test/kotlin/com/videoslim/videoslim/EngineErrorMapperTest.kt`
- Modify: `lib/screens/home_screen.dart`
- Modify: `test/widget_test.dart`

**Steps:**
1. 先写失败测试覆盖：3001、3002、3003、4001、4002、4003、SAF permission/provider。
2. 增加稳定错误：`SOURCE_PERMISSION_LOST`、`SOURCE_UNAVAILABLE`、`SOURCE_PROVIDER_FAILED`、`VIDEO_DECODING_FAILED`、`VIDEO_FORMAT_UNSUPPORTED`、`VIDEO_ENCODING_FAILED`。
3. 仅 metadata/extractor 明确无有效视频轨道或 malformed 时使用 `SOURCE_CORRUPTED`。
4. 普通页面只展示结果和建议；稳定 code 保留在 F19/技术详情，不把 `[CODE]` 前缀暴露给普通错误卡。
5. 运行 JVM 与 widget 定向测试。
6. 提交：`fix: classify media failures without blaming source`。

### Task 4: 实现硬件视频 Codec 策略

**Objective:** 让 capability、预检和实际 Media3 选择保持一致。

**Files:**
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/HardwareCodecPolicy.kt`
- Create: `android/app/src/test/kotlin/com/videoslim/videoslim/HardwareCodecPolicyTest.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`
- Modify: `android/app/build.gradle.kts`（仅在直接引用 ExoPlayer selector API 时声明同版本依赖）

**Steps:**
1. 先写纯 policy 测试：硬件/vendor 排序、软件名称排除、无硬件候选、音频 selector 不受影响。
2. Encoder 使用 `DefaultEncoderFactory.Builder.setVideoEncoderSelector(...)`，候选只包含硬件视频 encoder。
3. Decoder 使用 `DefaultDecoderFactory.Builder.setMediaCodecSelector(...)`，视频 MIME 只返回硬件 decoder；音频保持默认 selector。
4. 通过 `DefaultAssetLoaderFactory` 注入 decoder factory；在支持范围内启用硬件候选间 fallback，不回退到软件视频 Codec。
5. 使用 decoder listener 和 encoder factory wrapper 记录实际 codec 名称。
6. 预检目标尺寸、帧率、码率、CBR/Surface 支持；不支持时快速失败。
7. 运行 JVM、Debug/Release Kotlin 编译。
8. 提交：`fix: require compatible hardware video codecs`。

### Task 5: 增强 F19 设备和 ExportException 诊断

**Objective:** 让下一次真机失败能区分 OS、权限、Provider、解码器和编码器。

**Files:**
- Create/Modify: 原生诊断 helper（按实现最小落点）
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`
- Modify: 对应 JVM tests

**Steps:**
1. 记录 model/device、SDK/release/security patch，不记录凭据或用户文件内容。
2. 记录候选名称、hardware/software/vendor/alias 和被排除原因。
3. 记录实际 decoder/encoder、请求格式、失败 phase/percent/elapsed。
4. 对 `ExportException` 记录 errorCode/name、codecInfo 和 cause chain；若存在 `MediaCodec.CodecException`，记录 diagnosticInfo、recoverable/transient。
5. 记录失败时 SourceAccessProbe 结果。
6. 运行定向测试和编译。
7. 提交：`feat: add actionable media runtime diagnostics`。

### Task 6: 修复 ETA 和阶段展示

**Objective:** 基于真实整数进度提供低精度、可隐藏的可靠 ETA。

**Files:**
- Modify: `lib/logic/eta_estimator.dart`
- Modify: `test/logic/eta_estimator_test.dart`
- Modify: `lib/screens/home_screen.dart`
- Modify: `test/widget_test.dart`

**Steps:**
1. 先写失败测试：8.5 秒/1% 阶梯、12 秒/1% 阶梯、同百分比每秒刷新、30 秒停滞、停滞后突增、速度变化、100% 完成。
2. 只有百分比增加时更新有效样本；不回归、不用 timer tick 制造速率样本。
3. 至少 30 秒、3 个推进点和 3% 后才显示 ETA；累计平均为主，最近窗口温和修正。
4. 停滞后隐藏 ETA并显示“正在重新估算”；发布阶段不显示转码 ETA。
5. ETA 显示为整分钟或范围，不显示秒级假精度。
6. 运行 Dart 定向测试。
7. 提交：`fix: make long-video ETA progress-aware`。

### Task 7: 普通用户页面和通知文案

**Objective:** 仅用用户能理解的语言描述准备、压缩、保存、取消和失败。

**Files:**
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingNotification.kt`
- Modify: `android/app/src/test/kotlin/com/videoslim/videoslim/ProcessingNotificationTextTest.kt`
- Modify: `lib/screens/home_screen.dart`
- Modify: `test/widget_test.dart`

**Steps:**
1. 先更新测试期望。
2. 映射：准备视频、压缩视频、保存到相册、正在取消、完成、失败。
3. 删除普通 UI 中 SAF、Photo Picker、前台服务、Partial WakeLock、Android 时限、Media3/Codec 等词。
4. 通知失败正文使用短提示，详细原因留在 App/F19。
5. 运行 JVM 与 widget 测试。
6. 提交：`fix: use plain language for processing states`。

### Task 8: 全量验证、独立复审和 APK 交付

**Objective:** 生成可安装且证据完整的新真机验收 APK。

**Files:**
- Modify: `pubspec.yaml`（版本递增）
- Modify: `docs/m2-completion-report.md`
- Modify: `docs/m2-device-acceptance.md`
- Create/Update: `/root/artifacts/videoslim/m2/` 下新版本 APK、SHA-256 和 verification JSON

**Steps:**
1. Dart format、Flutter analyze、全量 Flutter tests。
2. Android `testDebugUnitTest`、`lintDebug`、`lintRelease`、Debug/Release Kotlin compile。
3. `git diff --check`，检查 merged release manifest 无新增广泛读取/联网权限。
4. 独立 spec 与 code-quality 复审；修复阻塞项并重跑定向门禁。
5. 递增版本，构建 arm64 Release APK。
6. 校验 package/version/SDK/ABI、zipalign、v2 signature、权限、SHA-256 和构建产物一致性。
7. 更新交接文档，撤销旧 APK 的“当前验收包”身份。
8. 提交最终代码和文档。
9. 交付 APK；真机只需确认实际 codec 不是软件路径并完成一条短片和一条完整长片。

---

## 最终真机停止条件

- 如果 F19 显示实际视频 decoder/encoder 均为硬件且完整长片成功：关闭本事故，继续剩余 M2 验收矩阵。
- 如果硬件路径仍出现 Released state：保持当前稳定版，隔离测试 Media3 1.11 beta，不直接替换生产依赖。
- 如果失败时 URI 重开为权限/Provider 失败：优先修 SAF/GrapheneOS 兼容，不继续调整 Codec。
- 如果设备没有支持当前输入的硬件 decoder：不允许软件长任务；明确告知该视频无法在本机可靠处理。
