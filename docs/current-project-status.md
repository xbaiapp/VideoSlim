# VideoSlim 当前项目状态

> **更新时间：** 2026-07-23
> **当前阶段：** C2/F21编码器能力诊断 `DEVICE REPORT CAPTURED — OWNER DISPOSITION PENDING`
> **上一C1a候选：** `1.7.0+23 / 7c49e57e3b6eafeeb765f2600c17b0242bea1160`（设备测试由所有者跳过）
> **当前已接受私有基线：** M4-B `1.8.0+24 / 9351e75bcc43c71a6e7caf03093fe27b0072b061`（所有者报告测试成功；详细矩阵未提供）
> **上一已接受基线：** M3 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`（`1.4.3+18`）

## 1. 里程碑进度

| 里程碑 | 状态 | 结果 |
|---|---|---|
| M0 环境搭建 | COMPLETE | 无头 Flutter/Android 工具链与 ARM64 APK 真机安装通过 |
| M1 最小原型 | ACCEPTED | 导入、信息读取、视频压缩、MediaStore 输出和 F19 日志主流程通过 |
| M2 压缩完整化 | `ACCEPTED — private scope` | 预设/自定义、VBR、前台服务、通知、取消、恢复、SAF、兼容 Decoder 重试可用 |
| M3 音频提取 | `ACCEPTED — private scope` | 项目所有者于 2026-07-22 明确报告当前 M3 候选测试成功 |
| M4-A 画面裁剪 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 自动化、构建和静态 APK 检查通过；真机十项矩阵尚未执行 |
| F7 拍摄时间/GPS与输出命名增强 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 缺省处理时间blocker已修订；一条Pixel设备“仅时间”任务成功，完整来源/图库/SAF矩阵仍待执行 |
| C轨 D1/F20–F22 | `C2/F21 DEVICE REPORT CAPTURED — OWNER DISPOSITION PENDING` | Pixel 10 Pro / API 37回传9个entry、四种MIME、0 query errors；硬件AVC/HEVC支持QP bounds、三个硬件encoder均不支持CQ、硬件AV1存在但不支持QP bounds；纠正SHA无独立review PASS，C1b/C3仍未授权 |
| M4-B 时间裁剪 | `ACCEPTED — private scope` | `1.8.0+24`由所有者报告测试成功；详细设备/素材/PTS/日志和逐项矩阵未提供，未填行继续PENDING |
| M4-C 多段时间编辑 | `PLANNED — NOT AUTHORIZED` | M4-B依赖已满足，但F23仍需独立授权；跨文件拼接归F16 |
| M5 自用版打磨 | NOT STARTED | 历史、批量、目标大小等未实现 |
| M6 上架/iOS | NOT STARTED | 生产签名、商店和 iOS 引擎未实现 |

`ACCEPTED — private scope` 只表示项目所有者接受已测试的自用设备/素材范围，不表示生产发布或多设备保证。`CANDIDATE READY` 也不等于真机通过。

## 2. 当前候选身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.9.0+25`
- 候选源代码：`11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`
- 候选 tree：`bcc2cbed2fe44027ea38c4d6973f126a7661faaf`
- Android：minSdk 26、targetSdk 36、compileSdk 36
- Media3：`1.10.1`
- Release ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate、APK Signature Scheme v2；不是商店生产签名
- APK：`VideoSlim-1.9.0+25-11f169c-arm64-v8a-release.apk`（仅供私有真机能力验收）
- APK 大小：`18,509,915` bytes
- APK SHA-256：`fe9e0e70b90dd5ae3bd6aace327b3a636b365a112689cd7a1f994927ccb6b2ed`

C2现在是最新可执行候选；M4-B `1.8.0+24 / 9351e75...`仍是项目所有者已接受的私有基线。C2已收到API 37完整能力清单，但项目所有者尚未明确接受或拒绝；系统声明不能升级为production签名、实际编码成功或QP/CQ/AV1实现授权。

M3已接受APK、旧`1.5.0+20`M4-A候选、`1.6.1+22 / b0267a0...`日志修复候选及各自证据继续保留，不被新候选覆盖或改写。

## 3. 当前已实现功能

### 视频压缩、M4-A画面裁剪与M4-B时间裁剪

- Android Photo Picker 与 SAF `content://` 导入；
- HEVC / H.264 硬件 VBR；画质优先、均衡、极限压缩、自定义；
- 首页裁剪入口与 S3 添加/编辑/移除裁剪；
- S4 自由、16:9、9:16、1:1、4:3，八手柄、整体移动、实时像素尺寸和预览帧滑杆；
- 缩放/移动使用单次手势累计位移；自由缩放在偶数像素取整后仍固定相反边；
- “保持画质（仅裁剪）”档；
- 显示方向像素 crop → Media3 NDC，单次 `Crop → Presentation`；
- crop 进入 request、snapshot 和 retry；`INVALID_CROP` 可编辑/移除恢复；
- 单个连续时间区间使用半开范围`[startMs,endMs)`，最短保留1秒；与crop、缩放、codec、码率和音频模式组合在同一次Media3导出；
- trim进入request、snapshot、recovery和retry；非法端点fail closed为`INVALID_TRIM`，估算、C1a提示和Android存储预检按保留时长计算；
- 原音轨复制、AAC 重编码或移除；
- 输出大小预估、空间检查、真实文件名/位置、打开和分享。
- C1a：仅在源大小已知且保守输出上界意味着预计节省低于15%时提示“收益有限、甚至可能变大”；无crop可暂不处理，有crop始终可选保持画质；提示不改目标、不阻止继续或发布。

### 拍摄时间、位置与输出命名

- 只读取来源中存在且可可靠解释的原拍摄/容器时间和GPS；无时间时用不被视为来源时间的1904/zero sentinel覆盖Media3处理时间默认值，不进入 `DATE_TAKEN`；
- 通过Media3 `1.10.1` `InAppMp4Muxer`在现有单次转码中写入时间/GPS，不做第二次remux；
- 临时MP4在公开URI分配前回读核验，时间/GPS应有不匹配或应无却出现时都不发布；
- 默认MediaStore视频写入可信 `DATE_TAKEN`；SAF只承诺文件内部metadata，音频不继承；
- 视频名包含最终codec和目标码率；音频区分copy/AAC，copy兼容重试切换为AAC时也重新生成真实名称，并包含毫秒时间和防重名token。

### 音频（M3）

- 第一条 AAC 音轨无损直提；
- AAC-LC 192/128/96/64 kbps 强制重编码；
- 单声道/双声道；超过 2 声道明确拒绝；
- 默认 `Music/VideoSlim` 和持久化 SAF 自定义目录；
- 发布前 sample count、PTS、payload bytes、digest 和覆盖时长校验。

### 共同任务运行时

- 单前台服务、同一时间一个媒体任务、`finishOnce` 唯一终止门；
- 通知、进度、取消、snapshot 重连和明确终态；
- 私有临时输出 → 完整校验 → MediaStore/SAF 发布；
- recovery journal、启动 reconciliation 和有所有权证据的精确清理；
- F19 本地调试日志；复制操作对超长日志只取最近128 KiB完整行，完整文件继续走系统“分享日志”，避免Android Binder超限；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE` 权限。

### C2/F21只读编码器能力诊断（候选）

- F19调试日志页新增“编码器能力”入口，独立页面自动查询、刷新并复制确定性能力清单；
- 固定查询`video/avc`、`video/hevc`、`video/av01`和`video/x-vnd.on2.vp9`；
- 显示codec/canonical名称、alias、平台hardware/software/vendor属性、CQ/VBR/CBR、API 31+ QP bounds、bitrate range和complexity range；
- API 26–28不把名称启发式伪装成平台分类；单个codec/mime查询失败只标记该entry；
- Android查询复用既有进程级有界本地I/O dispatcher；只用`MediaCodecList`/`getCapabilitiesForType`，不创建、配置或启动codec，也不触碰Transformer、任务、publication或recovery；
- typed Dart parser拒绝未知字段、非整数数字和不一致的API语义；刷新以generation隔离旧响应，复制/错误均有安全中文反馈。

## 4. 当前候选自动化与证据

C2先以测试锁定wire、只读边界、空态/错误/刷新/复制和单项失败隔离，再实现最小GREEN。首个冻结SHA `85e249726e36325e9532e641f296854b8c8d1eb2`的一次双路复审均在600秒超时且无最终裁决，按`NO VERDICT`处理；controller exact gate随后发现5个API 29 `NewApi` lint错误，该SHA被否决。唯一纠正SHA `11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`改用直接SDK guard与`@RequiresApi(Q)` helper，最终exact证据为：Dart format `63 files / 0 changed`、Flutter analyze `0 issues`、Flutter tests `257/257`、Android JVM tests `350/350`（57个XML，0 failures/errors/skipped）、debug/release lint和debug assemble PASS、ARM64 release build及APK静态核验PASS。按预算未复审纠正SHA，不得宣称独立review PASS。项目所有者随后回传Pixel 10 Pro / Android 17 API 37能力报告：9 entries、7个唯一canonical实现、2个alias、四种固定MIME和0 query errors。完整证据见`docs/c2-encoder-capabilities-completion-report.md`与`docs/c2-exact-sha-review-disposition.md`，真机清单与未完成交互项见`docs/c2-encoder-capabilities-device-acceptance.md`。

M4-B首个冻结SHA `9c9ca887ec4a8a26a6eb892077f88d333eb2a0da`通过当时的自动门禁，但其唯一双路复审为一路PASS、一路BLOCKERS。质量审查发现终态`INVALID_TRIM`快照在来源metadata缩短时会先抛错并进入所有权不确定锁定，阻断用户重新编辑/移除；该旧SHA已否决且不得用于候选。

唯一纠正SHA `9351e75bcc43c71a6e7caf03093fe27b0072b061`保留快照端点、显式标记`trimNeedsRepair`，并以“不夹取、不自动保存、用户重新选择或移除”的编辑器状态恢复。其exact-SHA证据为：

- Dart format：PASS，59 files、0 changed；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`244/244`；
- Android JVM tests：`346/346`，56个XML、0 failures/errors/skipped；
- Android debug/release lint与debug assemble：PASS；
- Flutter ARM64 Release APK：PASS；
- APK ZIP、16 KiB zipalign、v2签名、证书连续性、package/version/SDK/仅ARM64 ABI、权限和静态凭据扫描：PASS；
- APK：`18,509,915` bytes，SHA-256 `ac85d84e0a69185b3e73180737918fb98326f3899db9264991c0a9c681351567`。

按每任务一轮复审预算没有发起第二轮；旧SHA的混合裁决不等于纠正SHA的独立PASS。完整处置与候选证据见`docs/m4-b-exact-sha-review-disposition.md`和`docs/m4-b-completion-report.md`。这些自动化与静态结果本身不是M4-B真机PASS；后续接受来自项目所有者的独立测试成功报告。

构建机没有连接 Android 设备。项目所有者提供的Pixel 10 Pro / Android 17最后一次任务日志证明：来源解析为“时间存在、位置缺失”，最终MP4同样通过该状态核验并成功发布；但来源原文件谱系、外部atom对照、`DATE_TAKEN`查询、图库排序和SAF仍未验证，因此不能把该单次成功扩写为完整设备验收。

## 5. 真机验收状态

C2已在Pixel 10 Pro / Android 17 API 37上返回完整确定性清单，复制回传PASS；刷新、返回后设置不变、查询无任务/通知/文件副作用、重启重查和并发普通压缩仍未获得独立设备证据，项目所有者接受决定也仍为`PENDING`。项目所有者已报告M4-B测试成功并接受private scope；由于没有提供完整设备/素材/PTS/截图矩阵，`docs/m4-b-device-acceptance.md`未报告的行继续保持`PENDING`。`docs/c1a-low-savings-device-acceptance.md`已由所有者明确跳过且所有行仍为`PENDING`；`docs/capture-metadata-device-acceptance.md`和独立M4-A裁剪矩阵也保持`PENDING`，包括：

1. 0°/90°/180°/270° 框选与输出对齐；
2. 五种比例与自由拖拽；
3. 裁剪后 720p 缩放顺序；
4. 保持画质的主观质量与体积关系；
5. 未裁剪普通压缩回归；
6. 取消、后台、锁屏和进程恢复；
7. 预估大小误差与 F19 参数。
8. iPhone/Pixel原始文件的输入/输出时间和GPS对照；
9. MediaStore `DATE_TAKEN`、图库排序和SAF内部metadata；
10. 新视频/音频文件名及provider碰撞处理。

M4-B只在所有者实际测试的私有范围内记为`ACCEPTED`；未报告矩阵不得补写PASS。M4-A和拍摄时间/GPS增强在完成对应证据与明确接受前仍不得写为`ACCEPTED`。

## 6. 已知限制与冻结债务

1. Task 3 Slice B 继续冻结且未集成；recovery journal 尚未完整迁出主线程。
2. API 35 x86_64 instrumentation CI 的历史 `sdkmanager` PATH 问题仍未补跑；这不是当前真机验收替代品。
3. Release 使用 Android Debug certificate，只适合私有安装。
4. M4-A 默认假设 Media3 effects 作用于按旋转元数据转正后的画面；必须以四种 rotation 真机样本确认。
5. 单次 `InAppMp4Muxer` 已有一条Pixel设备“时间存在、位置缺失”的App内核验成功证据，但尚无未加工来源谱系、外部atom与图库索引对照；失败时必须停止并重新评级，不得自动增加remux。
6. C2/F21已形成纠正私有候选并收到一份API 37真机能力报告，但没有独立exact-SHA review PASS、项目所有者接受决定或完整交互/副作用矩阵；C1b/C3、M4-C/F23、M5历史/批量/目标大小和M6 iOS/上架均未开始。

## 7. 已批准的规划基线

- `docs/VideoSlim-AI-Handoff-2026-07-23.md` 已整合项目所有者批准的C轨和时间编辑规划；
- `docs/VideoSlim PRD.md` v1.20 将C1a/C1b、C2、C3分别映射为F20、F21、F22，将同源多段编辑映射为F23，并锁定C2实现contract、真机报告与候选边界；
- C1a实现已完成、真机矩阵由所有者跳过但不记PASS；D1结论见`docs/d1-bitrate-diagnosis-2026-07-23.md`；M4-B已由所有者报告测试成功并接受private scope；C2/F21已收到API 37能力报告，接受决定仍PENDING。C1b/C3/M4-C仍不得开工；
- 每个代码项遵守一次实现、一次修订、一轮复审和真机证据优先规则。

## 8. 下一步与禁止范围

下一步是项目所有者根据已回传报告明确接受、拒绝，或先补测刷新与“查询无任务/通知/文件/设置副作用”。报告已把C3决策空间缩小为：硬件AVC/HEVC声明支持QP bounds；硬件CQ不可用；硬件AV1存在但不支持QP bounds。任何C3方向仍需新的明确授权与真实编码证据。

- 若出现无法由显示坐标与 ≤2px 取整解释的错位，停止验收并报告；不得改用中间视频后二次有损转码。
- 若发生源文件/旧输出丢失、覆盖、误删或修改，立即阻止候选。
- C2代码与内部候选已完成；在新的明确授权前不得启动C1b、C3、M4-C、hardening、refactor、migration或其他生产改动。新候选不能把任何未执行真机矩阵改写为PASS。

## 9. 阅读顺序

1. `docs/VideoSlim-AI-Handoff-2026-07-23.md`
2. `AI_REVIEW_START_HERE.md`
3. `docs/current-project-status.md`
4. `docs/c2-encoder-capabilities-completion-report.md`
5. `docs/c2-exact-sha-review-disposition.md`
6. `docs/c2-encoder-capabilities-device-acceptance.md`
7. `docs/plans/2026-07-23-c2-encoder-capabilities.md`
8. `docs/m4-b-completion-report.md`
9. `docs/m4-b-exact-sha-review-disposition.md`
10. `docs/m4-b-device-acceptance.md`
11. `docs/c1a-low-savings-completion-report.md`
12. `docs/d1-bitrate-diagnosis-2026-07-23.md`
13. `docs/c1a-low-savings-device-acceptance.md`
14. `docs/capture-metadata-completion-report.md`
15. `docs/capture-metadata-device-acceptance.md`
16. `docs/m4-a-completion-report.md`
17. `docs/m4-device-acceptance.md`
18. `docs/VideoSlim PRD.md`
19. `docs/known-debt.md`
20. `AGENTS.md`
