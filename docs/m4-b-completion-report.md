# VideoSlim M4-B/F8完成报告

> **日期：** 2026-07-23
> **状态：** `CANDIDATE READY — DEVICE ACCEPTANCE PENDING`
> **版本：** `1.8.0+24`
> **纠正候选源码SHA：** `9351e75bcc43c71a6e7caf03093fe27b0072b061`
> **源码tree：** `c9807183c59bbce7fdf47ac79fac2fde24c179ed`
> **分支：** `m4a/crop`
> **设备证据：** `NOT RUN — NO DEVICE EVIDENCE`

## 1. 交付范围

本候选只增加一条来源视频的一个连续半开区间`[trimStartMs, trimEndMs)`：

- 两端同时为空表示不裁剪；否则必须成对存在并为整数毫秒；
- 严格要求`start >= 0`、`end > start`、`end-start >= 1000`、`end <= source.durationMs`；
- 非法端点fail closed为稳定错误码`INVALID_TRIM`，不补端点、不交换、不夹取、不自动扩展；
- 估算、低收益提示和native storage preflight按保留后的有效时长计算；
- request、snapshot、普通retry、软件decoder retry和native recovery精确保留端点；
- Media3在输入`MediaItem`应用`ClippingConfiguration`且不主动吸附关键帧；
- 时间trim可与crop、Presentation缩放、codec、VBR码率和现有音频模式在同一个Transformer任务中组合；
- 保持单任务、单前台服务、`finishOnce`、主线程亲和、recovery同步提交、metadata白名单与发布安全不变量。

不包含多段、拼接、跨来源时间线、M4-C、hardening、refactor或migration。

## 2. 唯一复审与纠正修订

首个冻结SHA `9c9ca887ec4a8a26a6eb892077f88d333eb2a0da`完成一次双路exact-SHA复审：

- 两个初始广泛审查均在600秒上限超时且没有裁决，按`NO VERDICT`处理；
- 同一未变SHA的限时替代审查得到一路`PASS`、一路`BLOCKERS`；
- 接受的阻断是：终态`INVALID_TRIM`快照若遇到更短的恢复metadata，会在消费终态前重新校验失败，外层转入所有权不确定锁定，用户无法重新编辑或移除。

唯一纠正修订：

- 把native快照端点视为权威恢复数据并原样保留；
- 用`trimNeedsRepair`显式表示“结构合法、但超出当前来源时长”的恢复状态；
- 新保存仍严格fail closed；
- 编辑器不夹取和不自动保存旧端点，而显示明确提示并回到全时长中性范围，要求用户重新选择或返回移除；
- reset、metadata-late、transaction rollback和启动恢复均新增回归覆盖。

按每任务“一次实现、一次修订、一次复审轮次”的预算，没有发起第二轮。旧SHA的混合裁决**不等于**纠正SHA `9351e75...`的独立PASS；纠正SHA依靠完整exact-SHA自动化和独立APK核验晋级私有内部候选。完整复审处置见`docs/m4-b-exact-sha-review-disposition.md`。

## 3. 纠正SHA自动化

所有命令均在干净工作树和exact SHA `9351e75bcc43c71a6e7caf03093fe27b0072b061`上执行：

| 门禁 | 结果 |
|---|---|
| Dart format | PASS，59 files，0 changed |
| Flutter analyze | PASS，0 issues |
| Flutter tests | `244/244 PASS` |
| Android JVM tests | `346/346 PASS`（56个XML，0 failure/error/skipped） |
| Android lintDebug | PASS |
| Android lintRelease | PASS |
| Android assembleDebug | PASS |
| exact-SHA前后身份/工作树 | SHA不变，工作树clean |

编译只出现既有Flutter/AGP/Media3弃用告警；本里程碑没有为此扩大到迁移或重构。

## 4. ARM64内部候选

- **文件：** `VideoSlim-1.8.0+24-9351e75-arm64-v8a-release.apk`
- **绝对路径：** `/root/artifacts/videoslim/m4b-single-trim/9351e75bcc43c71a6e7caf03093fe27b0072b061/VideoSlim-1.8.0+24-9351e75-arm64-v8a-release.apk`
- **大小：** `18,509,915` bytes
- **SHA-256：** `ac85d84e0a69185b3e73180737918fb98326f3899db9264991c0a9c681351567`
- **package：** `com.videoslim.videoslim`
- **versionName/versionCode：** `1.8.0 / 24`
- **minSdk/targetSdk：** `26 / 36`
- **ABI：** 仅`arm64-v8a`
- **签名：** APK Signature Scheme v2；单一Android Debug certificate
- **证书SHA-256：** `77edcb53fa49f75964c16cbb3e83b72188728abf3b0bcd19cf088e14c2ba218a`

静态核验：

| 检查 | 结果 |
|---|---|
| ZIP完整性 | PASS（93 entries） |
| 16 KiB兼容zipalign | PASS |
| v2签名验证 | PASS |
| 与`1.7.0+23`上一内部APK证书连续性 | PASS |
| package/version/SDK/ABI | PASS |
| 权限集合与上一内部APK一致 | PASS |
| `android.permission.INTERNET` | ABSENT |
| 私钥、credential URL、Bearer、签名密码及指定token字面量扫描 | 0 findings |
| source SHA与构建后clean工作树 | PASS |

首个验证器调用误用了非权威的旧证书摘要，验证器按设计拒绝。随后直接对上一内部APK与新APK分别执行`apksigner --print-certs`，确认两者实际摘要都为`77edcb...`；未修改APK，并用该直接来源值重新执行完整验证后PASS。初始失败JSON被保留在证据目录，不作隐藏或改写。

该APK使用Debug certificate，只能称为**私有ARM64内部候选**，不能称为production-signed或商店发布包。

## 5. 来源与证据

- **源码归档：** `VideoSlim-1.8.0+24-9351e75-source.tar.gz`
- **源码归档大小：** `473,193` bytes
- **源码归档SHA-256：** `92ff58cc8cf0c6216165a446093d7291474feb71bf25e2420754978d7a2fa6b6`
- **证据目录：** `/root/artifacts/videoslim/m4b-single-trim/9351e75bcc43c71a6e7caf03093fe27b0072b061/`
- **证据清单：** `evidence-manifest.sha256`，生成后已通过`sha256sum -c`
- **旧SHA复审证据：** `/root/artifacts/videoslim/m4b-single-trim/9c9ca887ec4a8a26a6eb892077f88d333eb2a0da/`

证据包含exact gates、构建日志、APK/manifest/badging/permissions、zipalign、签名、上一APK对照、秘密扫描、source archive与哈希清单；不包含用户媒体、精确GPS、运行时数据库或凭据。

## 6. 设备边界与下一步

`docs/m4-b-device-acceptance.md`全部设备行仍为`PENDING`，总状态为`NOT RUN — NO DEVICE EVIDENCE`。本报告没有把自动化或静态APK检查写成真机PASS。

下一步仅是使用本SHA和本APK执行M4-B真机矩阵，重点确认实际起止段、A/V同步、trim与crop/缩放/codec/音频组合、后台/取消/恢复/retry、metadata/发布安全以及源文件不变。出现错误片段、明显不同步、源文件或旧输出丢失/覆盖/修改时必须停止。
