# VideoSlim M2 私有范围完成报告

> **状态（2026-07-20）：** `ACCEPTED — private scope`。项目所有者已接受 Pixel 当前私有使用场景；`1.3.5+14` 的软件输入解码兼容路径、准确错误语义、保存位置/体积区间、SAF 发布恢复和实际编码模式已完成本地自动化与 APK 静态核验。该决定允许进入 M3，但不等同于生产发布或扩大设备支持范围。
>
> **后续策略（2026-07-24）：** 本报告继续作为`1.3.5+14`历史证据，不改写其接受范围；最新`1.9.1+26`已把hardware `VIDEO_DECODING_FAILED`后的用户确认流程替换为同一task/同一service内最多一次的无确认自动software retry。新策略、候选与真机边界见两个`automatic-software-decoder-retry-*`文档。

## 1. 当前交付身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.3.5+14`
- minSdk / targetSdk / compileSdk：`26 / 36 / 36`
- Media3：`1.10.1`
- ABI：仅 `arm64-v8a`
- APK：`/root/artifacts/videoslim/m2/VideoSlim-M2-arm64-v1.3.5.apk`
- 大小：`17,968,911` bytes
- SHA-256：`cda938d86a96f10f5e7b0d1d1d9e3a170ce88c361eac9be01f6094379265b480`
- 构建源码：`9d7c48523056b417d3a837e63fef4b21e8748e27`
- 签名：Android debug certificate、APK Signature Scheme v2；仅用于当前私有验收，不是生产签名。

`1.3.0+9` 因 legacy 路径清理与 quarantine 目录持久化缺口而撤销；`1.3.3+12` 因首次创建 quarantine 目录时还需同步其父目录项而撤销；`1.3.4+13` 因解码失败通知缺少“原视频没有被修改”而撤销；`1.3.1+10`、`1.3.2+11` 是未正式交付的文案过渡包；旧 `1.2.x` 也不再用于事故关闭。

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

视频压缩继续采用设备硬件 Encoder 的目标平均码率 **VBR**。不得把 CBR、实际输出码率必须命中目标值，或目标码率偏差硬拦截重新引入 M2/M3；目标码率用于编码配置、体积估算与收益提示，不作为成功发布的硬失败条件。

## 5. 本地自动化证据

在源码提交 `9d7c485` 上实际通过：

- Dart format：通过；
- Flutter analyze：`0 issues`；
- Flutter tests：`114/114`；
- Android JVM：17 suites、`98/98`，failures/errors/skipped 均为 0；
- Android `lintDebug`、`lintRelease`；
- Debug/Release assemble；
- ARM64-only Release APK 构建；
- `git diff --check`；
- 源码/测试凭据模式扫描：0 命中。

精确源码 `9d7c48523056b417d3a837e63fef4b21e8748e27` 的三方向只读阻塞级复审均为 PASS：publication/recovery 安全、产品状态契约、用户错误面与通知 finding closure。

## 6. APK 静态核验

- package/version：`com.videoslim.videoslim` / `1.3.5+14`；
- SDK：`26 / 36 / 36`；
- ABI：仅 `arm64-v8a`；native libs 仅 `libapp.so`、`libflutter.so`；
- `zipalign -c -P 16 4`：通过；
- APK Signature Scheme v2：通过，1 signer；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`MANAGE_EXTERNAL_STORAGE`；
- `WRITE_EXTERNAL_STORAGE`/系统合并的 legacy `READ_EXTERNAL_STORAGE` 仅 Android 8–9（`maxSdkVersion=28`）；
- `ProcessingService`：`exported=false`、`stopWithTask=false`、`dataSync|mediaProcessing`；
- 构建产物与交付副本 `cmp` 一致。

机器可读证据：`/root/artifacts/videoslim/m2/verification.json`。

## 7. 接受范围与 non-blocking hardening

M2 已按项目所有者的 Pixel 当前私有使用场景接受。服务器没有连接 Pixel 10 Pro / Android 17，因而未由服务器补做或伪造下列结果；这些项目保留在 `docs/m2-device-acceptance.md`，现统一归入 **non-blocking hardening backlog**，不再阻止 M3：

1. 同一事故输入、同一参数下的严格 A/B/C（前台、后台/锁屏、后台并发播放）对照；
2. hardware 失败后显式 software-only 输入 Decoder 的完整真机对照及 F19 证明；
3. Photo Picker、Media DocumentsProvider、ExternalStorage DocumentsProvider 等多 Provider 异常矩阵；
4. 多 SoC、多 Android/Provider 实现的兼容性重复验证；
5. 接近 6 小时、接近 50 GB、输出超过 4 GB 的边界与持续后台验证；
6. 撤权、短写、删除失败、取消、发布 race 与进程死亡的扩展破坏性测试。

上述未执行项不得标为 PASS，也不得据此宣称生产范围已经验证。若未来扩大分发或支持承诺，应先按矩阵补证；当前 `m2codec` 状态固定为 `ACCEPTED — private scope`，M3 可继续实施。

## 8. 主要提交

- `765d6f0`：保存位置、体积区间、实际编码模式、软件输入解码兼容路径及基础测试；
- `6ec611f`：完整重试请求恢复、SAF grant/发布长度/恢复 quarantine、通知循环建议和 legacy 清理加固。
- `b4e7857`：legacy row 删除未确认时禁止删除路径；quarantine rename 后 fsync 父目录再清 active journal；版本递增至 `1.3.1+10`。
- `7e3254a`：将 H.264 输出格式降级文案与软件输入解码“兼容模式”术语彻底分离；版本递增至 `1.3.2+11`。
- `522bebd`：同步 native 错误与通知文案，只把“兼容模式”用于软件输入解码重试；版本递增至 `1.3.3+12`。
- `b973ae7`：首次创建 quarantine 目录时同时 fsync quarantine 与其父目录，再清 active journal；版本递增至 `1.3.4+13`。
- `9d7c485`：hardware/software 解码失败通知均明确原视频未修改；版本递增至 `1.3.5+14`。
