# VideoSlim M3 真机验收矩阵

> **状态：** 待 M3 私有 APK 交付后执行。
> **证据规则：** 未实际执行的项目必须保持未勾选，**不得预填 PASS**。只有真实设备操作、F19 日志和输出回读一致时才能勾选。
> **凭据规则：** 日志、截图、URI 附加信息或报告中的任何凭据值始终替换为 `[REDACTED]`。

## 1. 测试前记录

- [ ] 手机型号、SoC、Android/GrapheneOS 构建号、安全补丁、电量/省电模式、可用/总存储已记录；
- [ ] APK 文件名、版本、bytes、SHA-256、签名类型和对应源码 commit SHA 已记录；
- [ ] F19 可查看、复制和分享，复制结果中的凭据均为 `[REDACTED]`；
- [ ] App 仍无 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO`、`MANAGE_EXTERNAL_STORAGE`；
- [ ] 默认音频位置显示为 `系统音频 > Music > VideoSlim`，自定义位置使用持久化 SAF tree 授权；
- [ ] 默认请求名符合 `<safe stem>_slim_yyyyMMdd_HHmmss.m4a`；只有同秒或既有重名时才由 provider 追加 collision suffix。

记录每个输入的文件名、bytes、时长、容器、第一音轨 MIME/codec/profile、采样率、声道、码率及音轨总数。若失败，附任务阶段、百分比、屏幕状态、剩余存储、稳定错误码和完整 F19；不得仅写“失败”。

## 2. 测试素材与共同检查

至少准备：

1. 30–120 秒 AAC-LC 或 HE-AAC stereo 视频，第一音轨 MIME 为 `audio/mp4a-latm`；
2. mono AAC 视频；
3. Opus WebM（非 AAC）；
4. 无音轨 MP4；
5. >2 声道视频；
6. 约 1 小时、第一音轨为 AAC 的视频。

每个成功输出共同检查：

- [ ] 扩展名 `.m4a`，resolver MIME 为 `audio/mp4`，系统播放器可播放；
- [ ] 实际文件名、用户可读保存位置、输出 URI、bytes 与回读 metadata 一致；
- [ ] 输出只有第一条音轨对应的 AAC 音轨，无视频轨，时长与源音轨 span 差异 ≤ 1 秒；
- [ ] 无爆音、无变速；普通页面/通知不显示 Codec 名称、原始异常或技术错误栈；
- [ ] 成功后可直接打开和分享；metadata 回读失败时不回滚已发布成功，仍保留实际文件名、位置、打开与分享入口；
- [ ] 任务终态后无残留 WakeLock、临时文件、`IS_PENDING` 行、自定义 SAF 半成品或重复通知。

## 3. AAC copy 短片连续三次

对**同一个** AAC stereo 短片、相同保存位置连续执行 3 次：

- [ ] 第 1 次 copy 成功；
- [ ] 第 2 次 copy 成功；
- [ ] 第 3 次 copy 成功；
- [ ] 三次均选择源文件顺序中的第一条音轨，且输出音频可播放、时长和声道与第一音轨一致；
- [ ] 三次 F19 均显示 `taskKind=audio_extraction`、`mode=copy`、源 MIME `audio/mp4a-latm`；
- [ ] 三次 F19 均证明走 `MediaExtractor + MediaMuxer` 纯 sample copy，**没有创建实际音频 Decoder 或 Encoder**；
- [ ] F19 记录 copy 的原始 first/last PTS、归零后时间范围、输出 bytes/duration 和发布终态；
- [ ] 同秒或已有同名时，provider 实际 collision suffix 正确回写页面、snapshot 与 F19，且未覆盖既有文件。

## 4. AAC 四码率同源强制重编码

使用第 3 节的**同一个源文件**，分别执行四次 AAC 模式。填写真实值，不用估算值代替：

| 请求码率 | 输出 bytes | 输出时长 | 实际 audio Decoder | 实际 AAC Encoder | 结果 |
|---:|---:|---:|---|---|---|
| 192000 bps |  |  |  |  | [ ] |
| 128000 bps |  |  |  |  | [ ] |
| 96000 bps |  |  |  |  | [ ] |
| 64000 bps |  |  |  |  | [ ] |

- [ ] 每一档 F19 均显示 `taskKind=audio_extraction`、`mode=aac`、精确请求码率、源/输出 MIME、采样率和声道；
- [ ] 每一档均记录**实际** audio Decoder 与 AAC Encoder，证明 Media3 audio-only 已强制重编码，没有因 AAC→AAC 静默 transmux；
- [ ] 四个输出都可播放、无爆音/变速、时长一致；
- [ ] 实际 bytes 严格单调：`192000 > 128000 > 96000 > 64000`；
- [ ] 不要求实际输出码率逐字节精确命中请求值，也不因合理偏差硬拦截发布。

任一档未创建实际 Decoder/Encoder，或 bytes 不单调，均不得将本节标为 PASS。

## 5. 音轨与格式边界

### 5.1 mono

- [ ] mono AAC copy 成功，输出仍为 mono，没有错误变为 stereo；
- [ ] mono AAC 128 kbps 强制重编码成功，F19 记录实际 Decoder/Encoder，输出仍为 mono。

### 5.2 Opus WebM（非 AAC）

- [ ] copy 在启动前或引擎预检时稳定拒绝，错误码为 `AUDIO_COPY_UNSUPPORTED`，没有空 `.m4a` 或公共半成品；
- [ ] UI 明确提示显式改用 AAC 模式，不静默切换；
- [ ] 用户显式选择 AAC 128 kbps 后，Media3 audio-only 解码 Opus 并编码 AAC-LC 成功，F19 记录实际 Decoder/Encoder。

### 5.3 无音轨

- [ ] 信息区入口禁用或给出“这个视频没有可提取的音轨”；
- [ ] 绕过 UI 直接调用引擎也稳定返回 `AUDIO_TRACK_MISSING`；
- [ ] 未启动幽灵任务，未创建临时文件、MediaStore pending 行或 SAF 半成品。

### 5.4 超过 2 声道

- [ ] copy 与 AAC 模式均稳定返回 `AUDIO_CHANNEL_LAYOUT_UNSUPPORTED`；
- [ ] 不隐式下混，不创建可被误报成功的输出。

## 6. 后台、锁屏与恢复

至少使用一个足以观察通知和恢复过程的 AAC 模式任务：

- [ ] 任务开始后出现正确的音频处理常驻通知，百分比单调且与 App 基本一致；
- [ ] 切到后台后继续处理并成功；
- [ ] 锁屏后继续处理并成功；
- [ ] 返回或重建 Activity 后恢复同一 `taskId`、`taskKind=audio_extraction`、mode、bitrate、阶段、百分比、输出名和位置，没有生成第二任务；
- [ ] 音频错误/重试不显示视频 software compatibility 入口，重试仍使用原 `AudioExtractRequest`；
- [ ] 完成后通知、WakeLock、snapshot 和页面均显示一致终态。

## 7. 转换与发布取消

### 7.1 转换阶段取消

- [ ] AAC 转换约 10%–30% 时从 App 取消；按钮进入“正在取消”并锁定，只出现一个 `cancelled` 终态；
- [ ] 再从通知执行一次独立取消用例，确认取消的是同一 `taskId`；
- [ ] F19 记录 `phase=encoding → cancelling → finished` 与 `CANCELLED`，没有临时文件或公共半成品。

### 7.2 发布阶段取消

- [ ] 在 `phase=publishing` 时触发取消，并覆盖一次快速重复点击；请求幂等，等待保存或补偿清理完成后再给终态；
- [ ] 默认 MediaStore 无 `IS_PENDING` 行/不完整音频，自定义 SAF 无零字节或短写半成品；
- [ ] 已成功输出、源视频和任何非 VideoSlim 文件均不受影响；
- [ ] 取消失败或 race 期间页面不回退阶段、不错误启用按钮、不伪报成功。

## 8. 默认位置与自定义 SAF

以下四个组合都必须实际执行：

- [ ] copy → 默认 `Music/VideoSlim`；
- [ ] AAC → 默认 `Music/VideoSlim`；
- [ ] copy → 自定义 SAF 文件夹；
- [ ] AAC → 自定义 SAF 文件夹。

共同检查：

- [ ] 开始前显示请求文件名和人类可读位置，成功后显示 provider 实际文件名和位置；
- [ ] 默认输出进入 MediaStore Audio/`Music/VideoSlim`，不是 `Movies/VideoSlim`；
- [ ] 自定义 SAF 使用已持久化的读写授权，close 后回读目标长度才可报告成功；
- [ ] 零字节、短写、无法确认长度或授权撤销时稳定失败并精确清理本任务 URI，不误删其他文件；
- [ ] provider collision suffix 只作第二层保护，时间戳默认命名仍存在。

## 9. 一小时 copy 性能

使用约 1 小时 AAC 视频，开始前记录时长、第一音轨 span、bytes、音频码率、输入 Provider、目标 Provider 和可用存储：

- [ ] copy 墙钟耗时 **< 10 秒**；
- [ ] 全程未创建音频 Decoder/Encoder，未把整条音轨载入内存；
- [ ] 输出可播放，时长与源第一音轨一致，bytes/频谱抽查与 sample copy 相符；
- [ ] F19 记录真实起止时间、耗时、first/last PTS、输出 bytes/duration 和发布路径。

若因设备、存储或 Provider 性能达到或超过 10 秒，记录真实耗时并保持本项未通过；不得伪造 PASS。

## 10. F19 必查字段

每个代表性 copy/AAC/失败/取消任务至少确认：

- [ ] `taskId`、`taskKind=audio_extraction`、mode、请求码率（copy 为 null）；
- [ ] 源 URI 摘要、第一音轨索引、源/输出 MIME、AAC profile、采样率、声道；
- [ ] copy 明确记录无 Decoder/Encoder；AAC 明确记录实际 audio Decoder 与实际 AAC Encoder；
- [ ] 原始 first/last PTS、输出 duration/bytes、进度阶段、耗时；
- [ ] 请求文件名、provider 实际文件名、默认/SAF 发布位置、长度回读与清理终态；
- [ ] 失败时稳定错误码与原始异常链可供诊断，但普通页面/通知仅显示用户文案；
- [ ] 所有凭据、授权令牌或敏感值均显示为 `[REDACTED]`。

## 11. M3 通过条件

只有以下全部取得真实证据后，M3 才可标记 PASS：

1. AAC 短片 copy 连续 3 次通过，且证明没有实际音频 Decoder/Encoder；
2. 同源 AAC 192/128/96/64 四档均强制重编码，实际 Decoder/Encoder 有日志，bytes 严格单调；
3. mono、Opus WebM 非 AAC、无音轨和 >2 声道边界符合稳定契约；
4. 后台、锁屏、转换取消、发布取消及任务恢复通过；
5. 默认 `Music/VideoSlim` 与自定义 SAF 的 copy/AAC 四组合通过；
6. 1 小时 copy 实测 <10 秒；
7. F19 字段、打开/分享、输出结构、时长和清理安全全部通过。

MP3、多音轨选择、trim、降噪和标签不属于 M3 范围，也不能用这些未实现项替代上述门禁。
