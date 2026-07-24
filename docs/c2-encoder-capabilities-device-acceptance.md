# VideoSlim C2/F21 编码器能力诊断真机验收清单

> **日期：** 2026-07-23
> **状态：** `DEVICE REPORT CAPTURED — OWNER DISPOSITION / REMAINING CHECKS PENDING`
> **目标版本：** `1.9.0+25`
> **候选源码：** `11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`（tree `bcc2cbed2fe44027ea38c4d6973f126a7661faaf`）
> **APK身份：** `VideoSlim-1.9.0+25-11f169c-arm64-v8a-release.apk`，SHA-256 `fe9e0e70b90dd5ae3bd6aace327b3a636b365a112689cd7a1f994927ccb6b2ed`
> **自动证据：** exact gates与APK静态核验PASS；纠正SHA无独立exact-SHA review PASS，见`docs/c2-exact-sha-review-disposition.md`
> **设备证据：** 项目所有者于2026-07-23回传API 37完整能力报告：9 entries、四种固定MIME、0 query errors
> **边界：** 本页验证只读能力查询与展示，不授权或验证C1b/C3编码策略。系统capabilities声明也不能替代实际编码测试。

## 1. 测试记录

- 手机型号：Pixel 10 Pro（来自同次回传前一份F19日志）
- Android版本：Android 17 / API 37
- SoC：未提供
- 系统安全补丁日期：2026-07-05（来自同次F19日志）
- APK文件名：`VideoSlim-1.9.0+25-11f169c-arm64-v8a-release.apk`（本次交付候选；未在设备端独立回读文件哈希）
- App日志版本：`1.9.0`
- 候选APK SHA-256：`fe9e0e70b90dd5ae3bd6aace327b3a636b365a112689cd7a1f994927ccb6b2ed`
- 测试日期：2026-07-23
- 原始能力报告：`/root/artifacts/videoslim/c2-encoder-capabilities/11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff/device-reports/2026-07-23-api37-encoder-capabilities.txt`
- 报告SHA-256：`1edea9a31f6646b342a831e1045749dbe178efa0572c9ff79750e865693fca5d`

设备型号、安全补丁和App版本来自紧邻能力报告之前回传的同一安装会话F19日志；能力报告自身只声明API 37。不得把未回传的SoC、设备端APK哈希或交互步骤补写为事实。

## 2. 入口与只读行为

| # | 用例 | 预期 | 状态 | 证据/备注 |
|---:|---|---|---|---|
| 1 | 首页打开F19调试日志，再点“编码器能力” | 进入独立只读页；说明不会开始压缩或改变设置 | REPORT CAPTURED | 所有者按交付指引返回该页面格式的完整报告；页面说明文案未单独截图 |
| 2 | 首次加载 | 显示Android API、固定四种查询MIME与能力entry；无媒体任务/前台通知 | PARTIAL | API、四种MIME、9 entries已证实；“无任务/通知”未由截图或操作描述独立证明 |
| 3 | 点击刷新 | 重新查询；页面保持可用；旧响应不能覆盖新结果 | PENDING | 未回传刷新操作证据；自动widget回归已覆盖旧响应隔离，但不替代设备操作 |
| 4 | 复制能力清单 | 剪贴板得到完整、可读、确定性文本；可粘贴回项目会话 | PASS | 完整报告已原样粘贴回项目会话，字段与排序可解析 |
| 5 | 返回日志页和首页 | 原日志、选中来源、crop、trim、preset、codec、码率、音频与输出位置不变 | PENDING | 未回传前后状态对照 |
| 6 | 查询期间启动普通压缩 | C2本身不创建任务；正常任务仍保持单任务/单服务行为 | PENDING | 前一份日志证明普通压缩可完成，但没有证明与能力查询并发 |

## 3. 能力清单核对

四种固定MIME：

- [x] `video/avc`
- [x] `video/hevc`
- [x] `video/av01`
- [x] `video/x-vnd.on2.vp9`

对返回内容的核对：

- [x] 9个entry的codec `name`与`mimeType`均非空；
- [x] API 37全部使用平台hardware/software/vendor/alias属性，`classification source=platform`；
- [x] CQ/VBR/CBR逐项显示支持或不支持；
- [x] API 37逐项显示QP-bounds支持状态；
- [x] bitrate range与complexity range均有lower/upper；
- [ ] 单个entry查询失败的设备侧隔离未触发：本报告9项全部`query error: none`；自动测试已覆盖但不冒充真机失败样本；
- [x] 软件、硬件和alias分类明确，没有把名称启发式写成平台事实；
- [ ] 刷新前后顺序一致性未由第二份设备报告证明。

## 4. 实际能力报告

```text
VideoSlim encoder capability report
Android API: 37
queried mime types: video/avc, video/hevc, video/av01, video/x-vnd.on2.vp9
encoder entries: 9
---
codec name: OMX.google.h264.encoder
canonical name: c2.android.avc.encoder
mime: video/avc
hardware / software / vendor / alias: false / true / false / true
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: true
bitrate range: 1..12000000 bps
complexity range: 0..0
query error: none
---
codec name: OMX.google.vp9.encoder
canonical name: c2.android.vp9.encoder
mime: video/x-vnd.on2.vp9
hardware / software / vendor / alias: false / true / false / true
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: true
bitrate range: 1..30000000 bps
complexity range: 0..0
query error: none
---
codec name: c2.android.av1.encoder
canonical name: c2.android.av1.encoder
mime: video/av01
hardware / software / vendor / alias: false / true / false / false
classification source: platform
CQ / VBR / CBR: true / true / true
QP bounds: true
bitrate range: 1..20000000 bps
complexity range: 0..5
query error: none
---
codec name: c2.android.avc.encoder
canonical name: c2.android.avc.encoder
mime: video/avc
hardware / software / vendor / alias: false / true / false / false
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: true
bitrate range: 1..12000000 bps
complexity range: 0..0
query error: none
---
codec name: c2.android.hevc.encoder
canonical name: c2.android.hevc.encoder
mime: video/hevc
hardware / software / vendor / alias: false / true / false / false
classification source: platform
CQ / VBR / CBR: true / true / true
QP bounds: true
bitrate range: 1..10000000 bps
complexity range: 0..10
query error: none
---
codec name: c2.android.vp9.encoder
canonical name: c2.android.vp9.encoder
mime: video/x-vnd.on2.vp9
hardware / software / vendor / alias: false / true / false / false
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: true
bitrate range: 1..30000000 bps
complexity range: 0..0
query error: none
---
codec name: c2.google.av1.encoder
canonical name: c2.google.av1.encoder
mime: video/av01
hardware / software / vendor / alias: true / false / true / false
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: false
bitrate range: 1..60000000 bps
complexity range: 0..0
query error: none
---
codec name: c2.google.avc.encoder
canonical name: c2.google.avc.encoder
mime: video/avc
hardware / software / vendor / alias: true / false / true / false
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: true
bitrate range: 1..240000000 bps
complexity range: 0..0
query error: none
---
codec name: c2.google.hevc.encoder
canonical name: c2.google.hevc.encoder
mime: video/hevc
hardware / software / vendor / alias: true / false / true / false
classification source: platform
CQ / VBR / CBR: false / true / true
QP bounds: true
bitrate range: 1..240000000 bps
complexity range: 0..0
query error: none
```

## 5. 设备报告解读

- 9个entry对应7个唯一canonical实现和2个旧OMX alias；alias不应重复计为独立encoder。
- 平台声明3个硬件/vendor encoder：`c2.google.avc.encoder`、`c2.google.hevc.encoder`、`c2.google.av1.encoder`。
- 平台声明4个非alias软件encoder：Android AVC、HEVC、AV1、VP9；本机没有硬件VP9 entry。
- 所有entry都声明VBR与CBR；**三个硬件encoder都不支持CQ**。只有软件HEVC和软件AV1声明CQ。
- 硬件AVC与硬件HEVC声明`QP bounds=true`；硬件AV1声明`QP bounds=false`。
- 硬件AVC/HEVC声明的bitrate range均为`1..240000000 bps`。该极宽声明不构成真实质量/码率保证，也说明此前约1.111 Mbps请求的运行期过冲不是由平台声明的最低码率夹高。
- 所有entry均`query error: none`；本次没有真实厂商异常可用于验证设备侧单项失败隔离。

对后续决策的约束：

1. **C3a QP钳制是当前硬件VBR路径唯一获得正向能力声明的高级方向**：硬件AVC/HEVC均报告支持；仍需单独授权、自定义EncoderFactory实现和真实编码校准。
2. **C3b硬件CQ在本机不可用**：不能把软件HEVC/AV1的CQ支持冒充硬件能力；既定“不引入软件编码器”决定不变。
3. **C3c硬件AV1的存在已被确认**，但它不支持CQ或QP bounds，且仍需独立验证MP4 mux、metadata、相册/缩略图、播放和分享兼容性；不能因“存在”直接启用。
4. **“都不做”仍是有效选项**。能力声明不证明QP能解决Pixel硬件HEVC的运行期码率过冲。

以上只缩小决策空间，不构成C1b、C3a、C3b、C3c或M4-C的实施授权。

## 6. 回归与安全

- APK静态核验已证明权限集合无`INTERNET`、现代媒体读取或`MANAGE_EXTERNAL_STORAGE`；这不是设备侧权限截图。
- 查询是否创建文件、通知、WakeLock或recovery journal：`PENDING`，未收到独立设备操作证据。
- 紧邻本报告前回传的F19日志证明：同一`1.9.0`安装会话中，硬件AVC解码失败后源仍可读、软件兼容解码重试成功，单段trim输出完成发布；这证明一条回归smoke，但不覆盖全部M4-B矩阵。
- 同一F19附件证明普通日志的最近128 KiB有界复制可用；“分享完整日志”未在该附件中证明。
- App关闭重开后重新查询、错误页隔离与查询期间并发压缩仍为`PENDING`。

## 7. 当前结论

- [x] `DEVICE REPORT CAPTURED — 9 entries / 4 target MIME / 0 query errors`
- [ ] `ACCEPTED — private scope`（需要项目所有者明确结论）
- [ ] `REJECTED — blocker found`

项目所有者结论：`PENDING — report supplied, acceptance decision not yet stated`

日期：2026-07-23
