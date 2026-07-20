# VideoSlim（视频瘦身）

VideoSlim 是一款仅在 Android 本机处理媒体的私有工具。它不需要网络权限，不上传源文件，当前版本提供视频压缩和音频提取。

## M3 功能

### 视频压缩

- HEVC / H.264 硬件编码；
- 目标平均码率使用硬件 VBR；
- 可复制、重编码或移除视频中的音轨；
- 默认保存到 `Movies/VideoSlim`，也可选择持久化授权的 SAF 文件夹。

不得把 CBR 或“实际码率必须精确命中目标值”的发布拦截重新引入当前实现。

### 音频提取

- 无损直提：第一条音轨必须为 AAC，使用 `MediaExtractor + MediaMuxer` 复制压缩 sample，不创建音频 Decoder / Encoder；
- AAC 转换：Media3 audio-only 强制实际重编码，支持 `192 / 128 / 96 / 64 kbps`；
- 支持单声道和双声道；超过双声道时明确拒绝，不隐式下混；
- 输出统一为 `.m4a` / `audio/mp4`；
- 发布前逐 sample 有界物理读回 payload，并以 AAC frame/编码延迟/时间取整边界核对声明时长和源音轨覆盖时长；
- 默认保存到 `Music/VideoSlim`，也可选择持久化授权的 SAF 文件夹；
- 任务复用同一个前台服务、通知、取消、恢复和调试日志入口，并通过 `taskKind` 与视频任务隔离。

M3 不包含 MP3、多音轨选择、trim、混音、降噪、采样率选择或标签编辑。

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

M3 真机矩阵见 `docs/m3-device-acceptance.md`。测试素材可用 `tool/generate_m3_fixtures.sh` 生成；二进制 fixture 不提交到 Git。
