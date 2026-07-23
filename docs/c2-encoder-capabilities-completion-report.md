# VideoSlim C2/F21 编码器能力诊断完成报告

> **日期：** 2026-07-23
> **状态：** `CANDIDATE READY — DEVICE CAPABILITY REPORT PENDING`
> **版本：** `1.9.0+25`
> **候选源码 SHA：** `11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`
> **源码 tree：** `bcc2cbed2fe44027ea38c4d6973f126a7661faaf`
> **分支：** `m4a/crop`
> **设备证据：** `PENDING — NO DEVICE CAPABILITY REPORT`

## 1. 交付范围

本候选只在现有F19调试日志区增加本机编码器能力诊断：

- 固定查询`video/avc`、`video/hevc`、`video/av01`和`video/x-vnd.on2.vp9`；
- 展示codec/canonical名称、alias、平台hardware/software/vendor属性、CQ/VBR/CBR、QP-bounds支持、bitrate range、complexity range和单项错误；
- API 26–28的平台分类字段保持不可用/null；API 31以前的QP-bounds保持不可用/null；
- 单个codec/mime的`getCapabilitiesForType`失败只标记该entry；顶层目录失败显示可重试错误；
- Dart对完整channel payload执行严格schema、字段、enum、整数、安全范围、range和重复entry检查；
- 页面支持加载、明确空态、错误/重试、刷新generation隔离、单项失败展示和确定性完整报告复制；
- Android查询复用既有进程级有界本地I/O dispatcher。

C2只枚举`MediaCodecList`并读取`MediaCodecInfo`/capabilities。它不创建、配置或启动codec，不读取媒体文件，不创建任务，不触碰Transformer、前台服务、publication、recovery、硬件VBR或codec fallback，也不增加权限。系统声明不能证明实际编码成功。

## 2. 唯一复审轮次与纠正

首个冻结源码`85e249726e36325e9532e641f296854b8c8d1eb2`发起一次双路exact-SHA复审。两个只读reviewer均在600秒上限内完成大量源码/测试检查但在Android命令结束前超时，均没有形成规定格式的最终裁决，因此都记为`NO VERDICT — TIMEOUT`，不能记PASS。

控制器随后在同一SHA的exact Android门禁中发现`lintDebug`的5个`NewApi`错误：API 29的`canonicalName/isAlias/isHardwareAccelerated/isSoftwareOnly/isVendor`调用虽然经普通Boolean分支保护，但lint不能证明该分支等价于SDK guard。首个SHA因此被否决。

唯一纠正修订`11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`：

- 使用直接`Build.VERSION.SDK_INT >= Q`分支；
- 把五个API 29 getter封装到`@RequiresApi(Q)` helper；
- API 26–28继续返回null/unavailable，而不是名称启发式；
- focused reader/routing测试与`lintDebug`随后PASS。

按每任务“一次实现、一次修订、一轮复审”的预算没有发起第二轮。旧SHA的超时记录不适用于纠正SHA；因此本候选明确是**纠正后自动化与APK证据通过、但没有独立exact-SHA review PASS**的私有设备候选。完整处置见`docs/c2-exact-sha-review-disposition.md`。

## 3. 纠正SHA自动化

所有最终门禁均在干净工作树和exact SHA `11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`上执行，门禁前后SHA不变：

| 门禁 | 结果 |
|---|---|
| Dart format | PASS，63 files、0 changed |
| Flutter analyze | PASS，0 issues |
| Flutter tests | `257/257 PASS` |
| Android JVM tests | `350/350 PASS`（57个XML，0 failure/error/skipped） |
| Android lintDebug | PASS，0 errors（24个既有/非阻断warning） |
| Android lintRelease | PASS，0 errors（24个既有/非阻断warning） |
| Android assembleDebug | PASS |
| Flutter ARM64 release build | PASS |
| exact-SHA前后身份/工作树 | SHA不变、工作树clean |

编译仍显示Flutter/AGP/Media3既有弃用告警；C2没有把范围扩大到Gradle/Kotlin迁移或媒体管线重构。

## 4. ARM64私有候选

- **文件：** `VideoSlim-1.9.0+25-11f169c-arm64-v8a-release.apk`
- **绝对路径：** `/root/artifacts/videoslim/c2-encoder-capabilities/11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff/VideoSlim-1.9.0+25-11f169c-arm64-v8a-release.apk`
- **大小：** `18,509,915` bytes
- **SHA-256：** `fe9e0e70b90dd5ae3bd6aace327b3a636b365a112689cd7a1f994927ccb6b2ed`
- **package：** `com.videoslim.videoslim`
- **versionName/versionCode：** `1.9.0 / 25`
- **minSdk/targetSdk：** `26 / 36`
- **ABI：** 仅`arm64-v8a`
- **签名：** APK Signature Scheme v2；单一Android Debug certificate
- **证书SHA-256：** `77edcb53fa49f75964c16cbb3e83b72188728abf3b0bcd19cf088e14c2ba218a`

独立静态核验：

| 检查 | 结果 |
|---|---|
| raw build output与durable copy哈希一致 | PASS |
| ZIP完整性 | PASS（93 entries） |
| 16 KiB兼容zipalign | PASS |
| v2签名验证 | PASS |
| 与实际上一M4-B APK证书连续性 | PASS |
| package/version/SDK/仅ARM64 ABI | PASS |
| 权限集合与上一内部APK一致 | PASS |
| `INTERNET`、现代媒体读取、all-files权限 | ABSENT |
| 私钥、credential URL、Bearer、签名密码及指定敏感字面量扫描 | 0 findings |
| 构建/核验后source SHA与clean工作树 | PASS |

该APK使用Android Debug certificate，只能称为**私有ARM64内部候选**，不是production-signed或商店发布包。

## 5. 来源与证据

- **源码归档：** `VideoSlim-1.9.0+25-11f169c-source.tar.gz`
- **源码归档大小：** `497,410` bytes
- **源码归档SHA-256：** `52f1a4b3b5ef03a5117e98ee553996a21f98da821e2c68e1caac5b983c4c7219`
- **最终证据目录：** `/root/artifacts/videoslim/c2-encoder-capabilities/11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff/`
- **被否决首个SHA证据：** `/root/artifacts/videoslim/c2-encoder-capabilities/85e249726e36325e9532e641f296854b8c8d1eb2/`

证据包含exact Flutter/Android门禁、纠正focused gate、release build、lint报告、APK/manifest/badging/permissions、zipalign、签名与上一APK对照、秘密扫描、source archive和哈希清单；不包含用户媒体、运行时数据库、精确位置或凭据。

## 6. 设备边界与后续

构建机没有Android设备。`docs/c2-encoder-capabilities-device-acceptance.md`中的所有入口、刷新、复制、四种MIME能力、只读行为和回归项继续保持`PENDING`，直到项目所有者安装本APK并回传实际能力清单。自动化与静态检查不能证明设备encoder目录、厂商capabilities或真实编码行为。

C2完成不自动授权C1b建议目标、C3 QP/CQ/AV1编码档或M4-C多段编辑；下一代码项仍需项目所有者独立决策。
