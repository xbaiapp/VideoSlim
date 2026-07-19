# VideoSlim M2 自动化完成与真机交接报告

> **状态（2026-07-19）：** Pixel / GrapheneOS 专项修复的源码、自动化门禁、独立复审、ARM64 APK 构建和静态核验已完成。`1.2.3+6` 是当前真机验收包；Pixel 10 Pro / GrapheneOS / Android 17 真机短片和约 98 分钟目标视频尚未执行，因此 M2 仍为**设备验收待定**，不得宣称最终 PASS 或进入 M3。

## 1. 当前交付身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.2.3+6`
- minSdk / targetSdk / compileSdk：`26 / 36 / 36`
- ABI：仅 `arm64-v8a`
- APK：`/root/artifacts/videoslim/m2/VideoSlim-M2-arm64-v1.2.3.apk`
- 大小：`17,968,911` bytes
- SHA-256：`2da1a990cb29a7c00628e6e7a4fb667aa1e470dfe6ce93250966f8363cf0862a`
- 构建源码：`b0d1c29f55482d4c7583437f36c965c3355e0f63`
- 签名：Android debug certificate，APK Signature Scheme v2；与旧私有验收包证书一致，仅用于私有真机验收，不是生产签名。

旧 `1.2.0+3` 已因 Google Codec2 Released-state 故障撤销；中间候选 `1.2.1+4`、`1.2.2+5` 未交付，不能替代当前包。

## 2. 本次阻塞修复

### 硬件视频 Codec 路径

- 输入 AVC Decoder 与输出 HEVC/H.264 Encoder 同时做硬件约束；
- 预检使用当前任务的 MIME、尺寸、帧率、码率、Surface、VBR，并在输入 metadata 可获得时纳入 profile/level；
- capability、预检和 Media3 selector 共用同一候选策略；selector 只返回已批准候选；
- 默认排除 `c2.google.*`、`c2.android.*`、`OMX.google.*` 等软件视频实现；音频仍委托默认路径；
- 无可靠硬件 AVC Decoder 时快速阻止任务；无 HEVC 硬件 Encoder 时，预设和自定义设置均需用户明确确认后才切换硬件 H.264。

### SAF、Provider 与错误分类

- 系统选择器记录原始 read/persistable flags、持久化尝试和 resolver 实际授权事实；Photo Picker transient 边界独立记录；
- metadata 前执行 open/stat/read/seek 探测；Media3 失败后重开 URI 并与起始基线比较；
- 区分授权丢失、源文件不可用、Provider I/O、Decoder 运行失败、Encoder 运行失败和格式不支持；
- unknown size 本身不视为 Provider 故障；被包装的 `SecurityException`、`FileNotFoundException` 和 `IOException` 沿 cause chain 分类；
- Media3 300x/400x 不再笼统误报“文件损坏”，普通页面不显示错误码或内部术语。

### 阶段、ETA、取消与诊断

- 原生阶段 `preparing / encoding / publishing / cancelling / finished` 贯通 registry、service、channel、Flutter 恢复、页面和通知；0% 阶段切换也会发送；
- 恢复缓冲事件按百分比与阶段单调合并，旧事件不能把 `publishing/cancelling` 回退成 `encoding`；
- 用户确认取消后页面立即显示取消状态，但不覆盖原生 canonical phase；取消失败会保留等待期间收到的合法阶段；
- 发布期间重复取消幂等，publication boundary 持续到主线程成功、取消或失败终态处理完成后才释放；
- ETA 仅按真实百分比推进采样，前期隐藏、停滞后重新估算，仅在 encoding 阶段显示分钟范围；
- F19 记录设备/系统/App/Media3、候选与实际 Codec、具体格式、ExportException、diagnosticInfo、cause chain、最后阶段/进度和 URI probe；
- 页面和通知使用普通用户文案，不暴露 Media3、Codec、encoder/decoder、WakeLock、前台服务或原始异常。

## 3. 最终自动化与独立复审

在精确源码提交 `b0d1c29` 上实际通过：

- Dart format：35 个文件，无需修改；
- Flutter analyze：0 issues；
- Flutter tests：`106/106`；
- Android JVM：17 suites、`83/83`，failures/errors/skipped 均为 0；
- Android Debug/Release Kotlin 编译；
- Android `lintDebug`、`lintRelease`；
- `git diff --check`；
- 源码/测试凭据模式扫描：0 命中；
- 三方向独立审查覆盖 Codec、SAF/错误分类、phase/ETA/UI；其阻塞 finding 已修复；
- 对最终两个修复提交分别进行了精确复审，最终提交复审结果为 `PASS`。

## 4. APK 静态核验

- package/version：`com.videoslim.videoslim` / `1.2.3+6`；
- SDK：`26 / 36 / 36`；
- ABI：仅 `arm64-v8a`；
- `zipalign -c -P 16 4`：通过；
- APK Signature Scheme v2：通过，1 signer；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`MANAGE_EXTERNAL_STORAGE`；
- `WRITE_EXTERNAL_STORAGE` 仅 `maxSdkVersion=28`，系统隐含 legacy `READ_EXTERNAL_STORAGE maxSdkVersion=28`；
- 包含通知、FGS、dataSync、mediaProcessing 和 WakeLock 所需权限；
- `ProcessingService`：`exported=false`、`stopWithTask=false`、`dataSync|mediaProcessing`；
- APK 凭据模式扫描：0 命中；
- 构建产物与交付副本 `cmp` 一致。

完整机器可读证据：`/root/artifacts/videoslim/m2/verification.json`。

## 5. 尚未由服务器证明的事项

当前 VPS 没有连接的 Android 设备。以下必须在 Pixel 10 Pro / GrapheneOS / Android 17 上验证：

1. 目标输入存在支持 AVC High Profile Level 3.1、720×1280、30 fps 的真实硬件 Decoder；
2. HEVC/H.264 目标格式存在真实硬件 Encoder，Media3 实际选择与 allowlist 一致；
3. 实际 Codec 名称不为 `c2.google.*`、`c2.android.*`、`OMX.google.*`；
4. Photo Picker、SAF DocumentsProvider、后台、锁屏、通知取消和 App 内取消真实时序；
5. 发布阶段重复取消不会留下 `IS_PENDING` 行或公共半成品；
6. 约 98 分钟目标视频完整成功且无 Released-state；
7. 输出时长、音画同步、码率、分辨率、音轨和相册播放正常。

执行顺序和记录模板见 `docs/m2-device-acceptance.md`。真机通过前不进入 M3。

## 6. 本次主要提交

- `e78eb58`：Pixel / GrapheneOS 专项修复计划；
- `da42117`：硬件 Codec、SAF/错误、阶段、ETA、文案与诊断主体修复；
- `8907350`：发布取消幂等和阶段恢复修复；
- `b0d1c29`：publication boundary 与取消阶段 race 最终修复。
