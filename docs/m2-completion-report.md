# VideoSlim M2 自动化完成与真机交接报告

> 历史结论（已撤销当前验收身份）：M2 代码实现和 VPS 自动化质量门禁已完成，`1.2.0+3` Release APK 已构建并完成静态核验；但该 APK 随后在 Pixel 10 Pro / GrapheneOS / Android 17 真机出现阻塞性 Google Codec2 Released-state 失败，不能继续作为当前验收包。修复计划见 `plans/2026-07-19-m2-pixel-grapheneos-compatibility-repair.md`。真机验收通过前不进入 M3。

## 1. 交付身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.2.0+3`
- minSdk / targetSdk / compileSdk：`26 / 36 / 36`
- ABI：仅 `arm64-v8a`
- APK：`/root/artifacts/videoslim/m2/VideoSlim-M2-arm64-v1.2.0.apk`
- 大小：`17,952,527` bytes
- SHA-256：`7767ec5613a4185f61fa7f10ba67b1a641092332100b785474a0b59e2ac6893a`
- 签名：Android debug certificate，APK Signature Scheme v2；仅用于当前私有真机验收，不是生产签名。

## 2. M2 已实现范围

### 压缩配置

- 画质优先、均衡、极限压缩三个预设；
- 原始、1080p、720p、480p 长边配置，不放大较小来源；
- HEVC/H.265 与 H.264；HEVC 硬编不可用时按产品规则降级 H.264 并提高目标码率；
- 目标平均视频码率 VBR；
- 原音轨复制、AAC 192/128/96/64 kbps、移除音频；
- HDR 输入在 API 29+ 请求 Media3 HDR→SDR，API 26–28 明确失败；
- 输出大小预估、收益有限警告、6 小时/50 GB 已验证范围警告。

### 任务运行

- `ProcessingService` 持有实际 Media3 Transformer 任务；
- API 35+ 以 `mediaProcessing` 前台服务类型运行，API 29–34 使用 `dataSync`，API 26–28 使用兼容前台启动；
- Android 13+ 未授予通知权限时不启动任务；
- 仅活动任务持有带 6 小时 5 分钟保险超时的 Partial WakeLock；
- 持续通知与 App 内同步展示单调百分比、已用时间和 ETA；
- 通知和 App 内取消使用同一个 taskId；发布复制按 1 MiB 块检查取消和线程中断；
- Android 15+ `Service.onTimeout(startId, fgsType)` 立即请求取消、记录发布丢弃边界并使服务进入失败终态，不等待大型公共文件复制自然完成。

### 恢复和发布

- 进程内单活动任务和终态快照支持 Activity/Flutter 重建后重连；
- 进程死亡不承诺断点续传；下次合法启动按 recovery journal 对账清理；
- journal 记录私有随机临时文件、精确 App 创建的 MediaStore URI/legacy 路径、实际输出文件名及发布阶段；
- `ALLOCATED` 阶段在 MediaStore 插入返回 URI 后、首次目标字段回读前同步落盘，避免回读和回滚同时失败时遗失 `IS_PENDING` 行；
- 恢复删除同时核对 URI、相对目录、待发布状态和 `OWNER_PACKAGE_NAME`，不能证明仍由本 App 所有时拒绝删除；
- `DISCARDING` 阶段区分“成功发布应保留”和“取消/失败后应删除”的完整输出；
- 删除返回 0 时重新查询精确 URI；只有确认目标不存在后才清 journal，无法确认时保留后续对账依据；
- 空间预检通过 `StorageManager.getUuidForPath()` 区分存储卷：同卷按临时文件和公共副本重叠峰值检查，异卷分别检查，卷身份不可用时保守按同卷处理；
- 不可解码的 journal 只清除私有 journal 值，不触碰无可验证所有权的公共媒体；
- MediaStore 实际分配文件名实时传回 Flutter；结果页显示 `系统相册 > Movies > VideoSlim > 实际文件名`；
- 输出 metadata 回读失败时仍保留实际位置、打开和分享入口，不会重复压缩；
- 相册来源删除原视频有 App 内二次确认和系统授权，SAF 来源不提供该入口。

## 3. 自动化质量门禁

在精确 M2 HEAD 上实际通过：

- Dart format：35 个文件，0 个需修改；
- Flutter analyze：0 issues；
- Flutter tests：99/99；
- Android JVM tests：69/69，0 failures、0 errors、0 skipped；
- Android Debug/Release Kotlin 编译；
- Android `lintDebug` 与 `lintRelease`；
- `git diff --check`；
- Flutter arm64 Release APK 构建；
- `apksigner verify`；
- `aapt` 包名、版本、SDK、权限、Application、Service 和 ABI 检查；
- APK 构建产物与交付副本逐字节 `cmp` 一致。

Release manifest 静态核验结果：

- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`MANAGE_EXTERNAL_STORAGE`；API 26–28 因旧版写权限存在系统隐含的 `READ_EXTERNAL_STORAGE maxSdkVersion=28`；
- `WRITE_EXTERNAL_STORAGE` 仅 `maxSdkVersion=28`；
- 包含通知、基础 FGS、dataSync、mediaProcessing 和 WakeLock 权限；
- `ProcessingService` 为 `exported=false`、`stopWithTask=false`，类型位为 `dataSync|mediaProcessing`；
- Application 为 `VideoSlimApplication`；
- APK 原生库只有 `lib/arm64-v8a/libapp.so` 和 `lib/arm64-v8a/libflutter.so`。

## 4. 必须真机验证的剩余风险

VPS 无 Android 设备或模拟器，以下不能由编译、JVM 测试或静态 APK 检查替代：

1. Android 8–16 的通知授权、前台服务启动限制及 OEM 后台策略；
2. Home、切换 App、最近任务划走、锁屏和整段熄屏期间的持续转码；
3. WakeLock、通知取消、App 内取消及 Android 15+ 系统 timeout 的真实时序；
4. 具体 SoC 的 HEVC/H.264 硬件编码器、Media3 VBR 行为和 HDR→SDR 色彩；
5. API 26–28 legacy MediaStore 与 API 29+ `IS_PENDING` 发布/恢复清理；
6. 接近 6 小时、接近 50 GB，以及超过 4 GB 输出的 muxer、文件系统、相册和播放器兼容性；
7. 转码中/发布中强杀和连续 10 次异常中断后的幂等清理与无误删；
8. 实际输出码率、分辨率、音轨、时长误差和音画同步。

执行标准和记录模板见 `docs/m2-device-acceptance.md`。这些测试未通过前，6 小时/50 GB 仍是待验证支持目标，而不是已经正式保证的能力。

## 4.1 Pixel / GrapheneOS 阻塞事故记录

同一段约 98 分钟的 SDR H.264/AAC 视频在 Pixel 10 Pro、GrapheneOS、Android 17 上得到以下证据：

- `c2.google.hevc.encoder` 在 M1 21%、M2 38% 和 M2 96% 进入 Released state；
- `c2.google.avc.decoder` 在 M2 64% 进入 Released state；
- M1 另一次相同参数完整成功，说明故障具有运行时/时序性质；
- M2 的 decoder 失败被错误映射为 `SOURCE_CORRUPTED`，encoder 4002 被错误映射为 `UNKNOWN`；
- 当前 capability 只证明存在某个硬件 encoder，没有约束 Media3 的实际 decoder/encoder 选择；
- SAF 日志只证明尝试持久化读取授权，尚未证明失败瞬间 URI/Provider 仍可重新打开。

因此 `1.2.0+3` 的自动化结果继续作为历史构建证据保留，但该 APK 的真机验收状态为 **FAIL / 已撤销**。修复必须同时覆盖 SAF 证据、硬件视频 Codec 选择、稳定错误分类、ETA 和普通用户文案。

## 5. M2 主提交链

- `ba62540` 前台处理服务；
- `407eafc` Flutter 重连活动任务；
- `1388c11` ETA；
- `903b802` 输出及来源媒体操作；
- `a912274` M2 Dart 参数契约；
- `271fc27` M2 原生转码参数；
- `d918aa2` 异常中断对账；
- `da8e9d2` recovery 生命周期边界；
- `e032e14` M2 Flutter 完整工作流；
- `a5fba2e` 发布、取消、timeout 和 recovery 加固；
- `f974ce7` MediaStore 分配恢复、删除复查和共享存储容量加固；
- `8a4346a` scoped 所有权证明和按存储卷建模的容量预检。
