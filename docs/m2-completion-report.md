# VideoSlim M2 自动化完成与真机交接报告

> **状态（2026-07-20）：** `1.3.1+10` 的软件输入解码兼容路径、准确错误语义、保存位置/体积区间、SAF 发布恢复和实际编码模式已完成本地自动化与 APK 静态核验。Pixel 10 Pro / Android 17 A/B/C 和软件兼容模式尚未真机执行，因此当前仅为**私有验收候选**，不得称为生产发布完成或进入 M3。

## 1. 当前交付身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.3.1+10`
- minSdk / targetSdk / compileSdk：`26 / 36 / 36`
- Media3：`1.10.1`
- ABI：仅 `arm64-v8a`
- APK：`/root/artifacts/videoslim/m2/VideoSlim-M2-arm64-v1.3.1.apk`
- 大小：`17,968,911` bytes
- SHA-256：`ad58955e2e0a8e4fae43634a4b10095c535fd452712c0012358da768a93b048a`
- 构建源码：`b4e785769838cba03163edea957cc0fe53025639`
- 签名：Android debug certificate、APK Signature Scheme v2；仅用于当前私有验收，不是生产签名。

`1.3.0+9`（SHA-256 `71645ffd…`）因 late review 发现 legacy 路径清理与 quarantine 目录持久化缺口而撤销；旧 `1.2.x` 也不再用于事故关闭。

## 2. 故障结论与处理策略

最后一次事故日志确认：

```text
Media3 3002 / ERROR_CODE_DECODING_FAILED
IllegalStateException: Invalid to call at Released state; only valid in executing state
progress: 89%
input: AVC, about 85 minutes, 7,722,771,154 bytes
```

失败后同一源 URI 仍可打开、读取和 seek，大小未改变，且任务未进入发布阶段。因此本次归类为**输入视频解码器运行失败**，不是源文件损坏、Photo Picker/SAF 授权丢失或保存失败。

A/B/C 旧复测只有“后台转换并同时播放另一视频”的 C 失败，说明并发媒体 Codec 条件与故障强关联；日志没有 `ERROR_RECLAIMED`，所以不能声称具体系统 reclaim 机制已被直接证明。

已采用：

1. 普通提示为“手机的视频解码器未能完成此次处理，原视频没有被修改”；
2. 默认 `video.decoderMode=hardware`；
3. 用户明确确认后，从头使用 `video.decoderMode=software` 重试；
4. 软件模式只改变输入视频 Decoder，音频路径和输出 HEVC/H.264 Encoder 策略不变；
5. 不静默重跑长视频，不从 89% 中间 MP4 续写；
6. 转换页提示避免同机并发播放其他视频；
7. Media3 升级仍作为独立真机对照变量，不以版本号代替验证。

## 3. 兼容重试与恢复契约

- native parser 只接受 `hardware|software`；
- software selector 必须依赖 Android 平台 `isSoftwareOnly` 能力标志，不以 Codec 名称猜测；
- 完整 `ProcessRequest` 进入 native snapshot；Activity 恢复后重试仍使用原 URI、输出文件名、保存位置、编码格式、码率、尺寸、裁剪、时间范围和音频设置；
- 兼容重试只修改 decoder mode；普通重试保持上一次实际请求模式；
- 只有 hardware 模式的 `VIDEO_DECODING_FAILED` 显示兼容入口；software 失败和通知都不会循环建议兼容模式；
- 无合格 software-only Decoder 时使用 `COMPATIBILITY_DECODER_UNAVAILABLE`；
- 实际视频编码模式以 Media3 成功创建的输出 Encoder 为事实源；诊断回调失败不能破坏转码；
- snapshot 和同百分比 progress event 合并时，已知实际编码模式可以补充 `unknown`，但不能回退。

## 4. 输出位置、体积和发布安全

- 默认输出：MediaStore `Movies/VideoSlim`；
- 自定义输出：`ACTION_OPEN_DOCUMENT_TREE` + 持久化读写授权；
- 文件夹选择、任务启动和发布前均验证持久化读写权限、根文档可查询及 `FLAG_DIR_SUPPORTS_CREATE`；
- Android 8–9 自定义 SAF 不申请无关的 legacy 广域写权限；默认 Movies 路由才使用 `maxSdkVersion=28` 权限；
- 自定义位置保留真实 collision-adjusted 文件名，页面、通知和恢复状态显示实际位置；
- VBR 体积显示为保守区间，空间门禁使用上界；custom SAF 不被无关的系统 Movies 剩余空间阻止；
- create/copy/close 后，在 `PUBLISHED` 前验证目标长度；SAF provider 执行目标 URI 回读，零字节、短文件、过长文件或无法确认时进入失败清理；
- 取消、ENOSPC、权限撤销和异常关闭均走精确 URI 删除及二次确认；
- `ALLOCATED` 且未确认实际显示名的记录不是删除权限；目标仍存在时 fail closed；
- 无法证明所有权或 provider 不可查询时，恢复证据进入私有 durable quarantine，释放 active journal 槽但不猜测删除；
- `PUBLISHED` 记录直接保留成功输出并清 journal，不依赖已经撤销的 SAF grant；
- legacy MediaStore `delete=0` 且行仍存在/身份变化时，不再删除可能已被复用的文件路径。

## 5. 本地自动化证据

在源码提交 `b4e7857` 上实际通过：

- Dart format：通过；
- Flutter analyze：`0 issues`；
- Flutter tests：`114/114`；
- Android JVM：17 suites、`97/97`，failures/errors/skipped 均为 0；
- Android `lintDebug`、`lintRelease`；
- Debug/Release assemble；
- ARM64-only Release APK 构建；
- `git diff --check`；
- 源码/测试凭据模式扫描：0 命中。

最终精确提交三方向只读复审正在运行；其结果必须在本报告提交前关闭或明确记录。

## 6. APK 静态核验

- package/version：`com.videoslim.videoslim` / `1.3.1+10`；
- SDK：`26 / 36 / 36`；
- ABI：仅 `arm64-v8a`；native libs 仅 `libapp.so`、`libflutter.so`；
- `zipalign -c -P 16 4`：通过；
- APK Signature Scheme v2：通过，1 signer；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`MANAGE_EXTERNAL_STORAGE`；
- `WRITE_EXTERNAL_STORAGE`/系统合并的 legacy `READ_EXTERNAL_STORAGE` 仅 Android 8–9（`maxSdkVersion=28`）；
- `ProcessingService`：`exported=false`、`stopWithTask=false`、`dataSync|mediaProcessing`；
- 构建产物与交付副本 `cmp` 一致。

机器可读证据：`/root/artifacts/videoslim/m2/verification.json`。

## 7. 真机未完成边界

当前服务器没有连接 Pixel 10 Pro / Android 17。以下仍是事故关闭阻塞门禁：

1. A：VideoSlim 前台、不播放其他视频；
2. B：VideoSlim 后台或锁屏、不播放其他视频；
3. C：VideoSlim 后台转换，同时在另一 App 播放视频；
4. hardware 解码失败后，用户显式选择 software 兼容模式并从头完整结束；
5. F19 证明 software 模式输入 Decoder 为平台 software-only，而输出 Encoder 仍按原硬件策略选择；
6. 输出可播放，时长、音画同步、分辨率、码率、音轨、大小和保存位置正确；
7. SAF provider 的撤权、短写、删除失败和进程死亡场景无误删、无永久任务死锁；
8. 取消/失败后没有私有临时文件、`IS_PENDING` 行或可证明属于本任务的公共半成品。

执行顺序见 `docs/m2-device-acceptance.md`。真机通过前，`m2codec` 只能标记为“代码与本地门禁完成，设备验收待定”。

## 8. 主要提交

- `765d6f0`：保存位置、体积区间、实际编码模式、软件输入解码兼容路径及基础测试；
- `6ec611f`：完整重试请求恢复、SAF grant/发布长度/恢复 quarantine、通知循环建议和 legacy 清理加固。
- `b4e7857`：legacy row 删除未确认时禁止删除路径；quarantine rename 后 fsync 父目录再清 active journal；版本递增至 `1.3.1+10`。
