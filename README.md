# VideoSlim（视频瘦身）

VideoSlim 是一款仅在 Android 本机处理媒体的私有工具。它不需要网络权限，不上传源文件，当前版本提供视频压缩和音频提取。

> **当前状态（2026-07-24）：** 最新私有ARM64候选为`1.9.2+27 / 7948f9f...`。AAC无损copy已在Pixel 10 Pro/Android 17上正确接受真实`23,812 µs`源小抖动并单次发布成功，主观播放确认仍PENDING；完整AAC frame gap继续被拒绝。一次性自动software decoder fallback的自然failure contract也已在真机观察到，所有者处置PENDING。M4-B/F8 `1.8.0+24 / 9351e75...`仍是已接受私有基线；C1b/C3及M4-C未授权。完整进度见`docs/current-project-status.md`。

## 当前功能

### 视频压缩

- HEVC / H.264 硬件编码；
- 目标平均码率使用硬件 VBR；
- 可复制、重编码或移除视频中的音轨；
- 默认保存到 `Movies/VideoSlim`，也可选择持久化授权的 SAF 文件夹。
- M4-A支持自由、16:9、9:16、1:1、4:3画面裁剪，裁剪、缩放与压缩保持一次有损转码；
- 来源中存在且可可靠解析的拍摄时间/GPS会在同一次MP4 mux中保留，并在公开发布前回读核验；缺失字段不伪造；
- 默认MediaStore视频从已验证的来源时间设置 `DATE_TAKEN`；SAF只承诺文件内部metadata；
- 视频输出名包含最终codec和目标码率，例如 `_slim_h265_target2500k_...`。
- 当保守输出上界预计节省低于15%时，S3提示结果可能收益有限甚至变大；提示不改编码目标、不阻止继续或发布。无crop可暂不处理，有crop可改选“保持画质（仅裁剪）”。
- M4-B支持选择一个连续保留区间；起止时间与画面crop、缩放、codec、码率和音频模式进入同一次Media3导出，估算和存储预检按保留时长计算。
- 每个新视频任务仍先使用硬件decoder；仅首次硬件attempt的结构化`VIDEO_DECODING_FAILED`会在同一用户任务和同一前台服务内，无确认地从头使用软件decoder重试一次。软件attempt不再循环，不建立永久素材/codec黑名单；进度和通知明确回到准备阶段0%。

不得把 CBR 或“实际码率必须精确命中目标值”的发布拦截重新引入当前实现。

### 音频提取

- 无损直提：第一条音轨必须为 AAC，使用 `MediaExtractor + MediaMuxer` 复制压缩 sample，不创建音频 Decoder / Encoder；
- AAC 转换：Media3 audio-only 强制实际重编码，支持 `192 / 128 / 96 / 64 kbps`；
- 支持单声道和双声道；超过双声道时明确拒绝，不隐式下混；
- 输出统一为 `.m4a` / `audio/mp4`；
- 发布前逐 sample 有界物理读回 payload，并以 AAC frame/编码延迟/时间取整边界核对声明时长和源音轨覆盖时长；无损copy只有在sample count、payload bytes/digest、indexed size、PTS与覆盖完整性都通过后，output cadence才同时受`source max + 2,000 µs`与`普通严格上限 + 1,000 µs`约束，AAC重编码仍使用严格规则；
- 默认保存到 `Music/VideoSlim`，也可选择持久化授权的 SAF 文件夹；
- 任务复用同一个前台服务、通知、取消、恢复和调试日志入口，并通过 `taskKind` 与视频任务隔离。
- 音频不继承视频拍摄时间/GPS；文件名区分 `_audio_copy_...` 与 `_audio_aac_target128k_...`，copy兼容重试切换为AAC时也会重新生成真实名称。
- F19调试日志复制对超长内容仅保留最近128 KiB完整行；完整日志仍可通过“分享日志”作为文件发送。

### 本机编码器能力诊断（C2/F21候选）

- 从F19调试日志进入只读“编码器能力”页；
- 查询AVC、HEVC、AV1、VP9编码器的平台软硬件属性、CQ/VBR/CBR、QP bounds、码率与复杂度范围；
- 支持刷新和复制完整清单，单个codec查询失败不会隐藏其他结果；
- 只读查询不会创建媒体任务、配置codec或改变现有压缩设置。
- Pixel 10 Pro / API 37报告包含9个entry、四种目标MIME和0 query errors：硬件AVC/HEVC声明支持QP bounds；三个硬件encoder均不支持CQ；硬件AV1存在但不支持QP bounds。以上只是系统声明，不代表高级编码档已实现或实测成功。

当前版本不包含 MP3、多音轨选择、多段时间线/区间乱序/拼接、混音、降噪、采样率选择、C1b建议目标、高级编码档、隐私模式或完整metadata复制。C2能力页只提供诊断证据，不等于已经实现QP/CQ/AV1编码档。

## 隐私与存储

- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE` 权限；
- 输入通过系统 Picker / SAF 授权；
- 公共输出先写私有临时文件，验证后再发布；
- 取消、失败和进程恢复只清理有确切所有权证据的本任务文件。

## 开发门禁

```bash
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test

cd android
./gradlew :app:testDebugUnitTest \
  :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease \
  --console=plain
```

C2范围与真机清单分别见`docs/plans/2026-07-23-c2-encoder-capabilities.md`和`docs/c2-encoder-capabilities-device-acceptance.md`。M3 cadence修正见`docs/m3-lossless-copy-cadence-correction-completion-report.md`及`docs/m3-device-acceptance.md`。一次性自动软件解码重试的冻结行为、review处置和真机矩阵见`docs/automatic-software-decoder-retry-completion-report.md`、`docs/automatic-software-decoder-retry-exact-review-disposition.md`与`docs/automatic-software-decoder-retry-device-acceptance.md`。C1a矩阵见 `docs/c1a-low-savings-device-acceptance.md`，真实来源/metadata矩阵见 `docs/capture-metadata-device-acceptance.md`，M4-A画面裁剪矩阵见 `docs/m4-device-acceptance.md`。M4-B完成报告、复审处置与真机矩阵分别见`docs/m4-b-completion-report.md`、`docs/m4-b-exact-sha-review-disposition.md`和`docs/m4-b-device-acceptance.md`。M3测试素材可用 `tool/generate_m3_fixtures.sh` 生成；二进制fixture不提交到Git。
