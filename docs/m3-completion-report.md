# VideoSlim M3 私有范围完成报告

> **状态（2026-07-22）：** `ACCEPTED — private scope`
> **接受依据：** 项目所有者明确报告当前 M3 候选测试成功。
> **发布代码：** `19abfb7da2e8fa028e7200000f0dc2a114bc840e`
> **版本：** `1.4.3+18`

## 1. M3 交付范围

M3 在既有视频压缩运行时上增加音频提取，并复用同一个前台服务、任务所有权、通知、取消、snapshot、发布、恢复和 F19 日志体系。

### AAC 无损直提

- 使用源文件顺序中的第一条音轨；
- 只接受有明确 profile 证据的 AAC-LC、HE-AAC 或 HE-AAC-v2；
- 使用 `MediaExtractor + MediaMuxer` 逐 sample copy；
- 不创建音频 Decoder/Encoder，不重编码；
- 保留严格 PTS、sample count、payload bytes、digest 和覆盖时长完整性契约。

### AAC 强制重编码

- Media3 audio-only；
- 强制 Decoder → AAC Encoder，即使源轨已经是 AAC 也不得 transmux；
- 支持 192/128/96/64 kbps；
- 支持 mono/stereo，超过 2 声道明确拒绝；
- Opus 等可解码非 AAC 源只能由用户显式选择 AAC 模式转换。

### 输出与任务语义

- 默认 `.m4a` / `audio/mp4`，保存到 `Music/VideoSlim`；
- 支持持久化 SAF 自定义文件夹；
- provider 实际分配的 URI/文件名进入结果、snapshot 和日志；
- 与视频任务通过 `taskKind` 严格隔离；
- 失败、取消和 recovery 只清理有当前任务所有权证据的对象。

## 2. 真实问题闭环

### 2.1 某些 AAC 文件选轨后 `sampleTime < 0`

真机上两个可正常播放且 URI 可读/可 seek 的 AAC 输入，在 metadata/copy 开始时被旧实现误判为没有可读 sample。修复在选轨后仅当 `sampleTime < 0` 时执行一次：

```text
seekTo(0, SEEK_TO_CLOSEST_SYNC)
```

metadata 与 lossless copy 共用同一 helper；零 sample 输入使用稳定 `AUDIO_DECODING_FAILED`，不再误报为 provider 故障。

对应提交：`02ab6820da36a93e1845f67228acf6714b9ddaa2`。

### 2.2 成功后重复读取输出信息

旧版长音频成功后，Flutter 会立即调用 `getAudioInfo`，导致已完成完整校验的输出再次逐 sample 扫描。新实现：

- 完整发布前 verifier 不变；
- 只有发布完成且最终取消检查通过后才缓存已验证 metadata；
- 只对 registry 中同一成功音频任务、完全相同 public URI 命中；
- one-shot 消费并重绑定 provider 实际 URI/文件名；
- cache miss、进程重启、URI/任务不匹配继续物理读取；
- digest、sample count 和 payload totals 不进入 Flutter public map。

对应提交：`19abfb7da2e8fa028e7200000f0dc2a114bc840e`。

该优化不删除发布前完整扫描，因此不能把此前约 4 分 37 秒的安全校验描述为已优化掉；它只消除 intended cache-hit 路径上的成功后第二次扫描。

## 3. 发布制品

- APK：`VideoSlim-1.4.3+18-19abfb7-arm64-v8a-release.apk`
- Package：`com.videoslim.videoslim`
- versionName/versionCode：`1.4.3` / `18`
- minSdk/targetSdk/compileSdk：`26 / 36 / 36`
- Media3：`1.10.1`
- ABI：仅 `arm64-v8a`
- 大小：`18,231,123` bytes
- SHA-256：`12523bac8b91994e23f965c98ce9f26c4e0ff3a8aac18fbfefb4cb01f34fbcf7`
- 签名：Android Debug certificate、v2；可覆盖此前私有测试包，不是商店生产签名
- 发布代码 tree：`8014c3af95b152db653a6903775576b539fbbb3b`

双复审前的中间 APK `baaf5d…` 已撤销且未交付；只有 `12523b…` 是本候选有效制品。

## 4. 自动化与复审

发布代码 `19abfb7...` 的证据：

- Dart format：49 files、0 changed；
- Flutter analyze：0 issues；
- Flutter tests：191/191；
- Android JVM tests：307/307，0 failures/errors/skipped；
- debug/release lint：PASS；
- compileReleaseKotlin：PASS；
- assembleDebugAndroidTest：构建 PASS，但构建机无设备，未宣称执行；
- ARM64 release build：PASS；
- APK ZIP、zipalign、v2 签名、package/version/SDK/ABI、权限和静态凭据扫描：PASS；
- 两路 exact-SHA 只读复审：PASS / PASS，0 blocker。

GitHub Actions run `29883255089`：完整 Flutter/Android 主任务通过；API 35 x86_64 instrumentation job 因 runner 中 `sdkmanager` 不在 PATH，在安装 SDK 时 exit 127，该 job 未进入应用/instrumentation APK 构建或 emulator 测试。因此整体 CI 为 failure，不能报告为全绿。

## 5. 真机接受决定

项目所有者于 2026-07-22 明确报告：“M3 测试成功”。M3 据此关闭为 `ACCEPTED — private scope`。

该决定确认当前所有者自用范围可以继续，但当前聊天没有附带完整设备信息、逐项矩阵、F19 原文或精确耗时，所以本报告不会：

- 把未提供证据的矩阵行反向补成 PASS；
- 声称原 PRD 的“一小时 copy <10 秒”已经达到；
- 声称多设备、多 Provider、API 26–28、极端长媒体或商店生产范围已经验证。

## 6. 保留限制

- Task 3 Slice B 保持冻结且未集成；
- recovery journal 尚未完整迁出主线程；
- 发布前完整音频 payload/digest 校验保留，安全优先；
- API 35 instrumentation CI 尚未执行；
- 使用 Android Debug certificate；
- M3 不包含 MP3、多音轨选择、trim、音量/降噪、混音、采样率选择或标签编辑。

## 7. 下一步

PRD 下一里程碑是 M4（画面裁剪 + 时间裁剪），但尚未开始。若项目所有者批准，应复用现有 `VideoEngine.process()`、单前台服务、单任务、`finishOnce`、Media3 Transformer、发布和 recovery 管线；画面裁剪、缩放与压缩一次转码完成，不创建中间视频后再二次压缩。
