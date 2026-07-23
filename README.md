# VideoSlim（视频瘦身）

VideoSlim 是一款仅在 Android 本机处理媒体的私有工具。它不需要网络权限，不上传源文件，当前版本提供视频压缩和音频提取。

> **当前状态（2026-07-23）：** M3 `1.4.3+18`仍是已接受私有基线。`1.7.0+23 / 7c49e57...`已实现C1a提示，项目所有者跳过其真机验收（未记PASS）；D1确认Pixel HEVC有效配置500 kbps后仍运行期明显过冲。M4-B/F8连续单段时间裁剪现为唯一获准代码项；C1b/C2/C3及M4-C仍未授权。完整进度见`docs/current-project-status.md`。

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

不得把 CBR 或“实际码率必须精确命中目标值”的发布拦截重新引入当前实现。

### 音频提取

- 无损直提：第一条音轨必须为 AAC，使用 `MediaExtractor + MediaMuxer` 复制压缩 sample，不创建音频 Decoder / Encoder；
- AAC 转换：Media3 audio-only 强制实际重编码，支持 `192 / 128 / 96 / 64 kbps`；
- 支持单声道和双声道；超过双声道时明确拒绝，不隐式下混；
- 输出统一为 `.m4a` / `audio/mp4`；
- 发布前逐 sample 有界物理读回 payload，并以 AAC frame/编码延迟/时间取整边界核对声明时长和源音轨覆盖时长；
- 默认保存到 `Music/VideoSlim`，也可选择持久化授权的 SAF 文件夹；
- 任务复用同一个前台服务、通知、取消、恢复和调试日志入口，并通过 `taskKind` 与视频任务隔离。
- 音频不继承视频拍摄时间/GPS；文件名区分 `_audio_copy_...` 与 `_audio_aac_target128k_...`，copy兼容重试切换为AAC时也会重新生成真实名称。
- F19调试日志复制对超长内容仅保留最近128 KiB完整行；完整日志仍可通过“分享日志”作为文件发送。

当前版本不包含 MP3、多音轨选择、trim、混音、降噪、采样率选择、C1b建议目标、编码器能力页、高级编码档、隐私模式或完整metadata复制。

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

当前候选的C1a矩阵见 `docs/c1a-low-savings-device-acceptance.md`，真实来源/metadata矩阵见 `docs/capture-metadata-device-acceptance.md`，裁剪矩阵见 `docs/m4-device-acceptance.md`。M3测试素材可用 `tool/generate_m3_fixtures.sh` 生成；二进制fixture不提交到Git。
