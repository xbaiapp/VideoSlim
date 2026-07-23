# VideoSlim 项目规划与当前进度交接（给其他 AI）

> **用途：** 让新的 AI 在不依赖聊天记录的情况下，理解产品边界、已完成能力、当前候选、未完成验收和后续路线。
>
> **生成日期：** 2026-07-23
> **修订：** r8（2026-07-23）— M4-B/F8连续单段时间裁剪已完成RED→GREEN和完整自动门禁，`1.8.0+24`等待冻结后的唯一复审与候选构建。规划演进与实现记录见§16。
> **文档状态：** 项目所有者已批准把本规划整合进仓库；本文与同步后的 `docs/VideoSlim PRD.md`、`docs/current-project-status.md` 共同构成规划基线。标为 `PLANNED — NOT AUTHORIZED` 的条目仍需逐项明确开工范围，不因文档入库自动授权。
> **仓库：** `https://github.com/xbaiapp/VideoSlim`（2026-07-23 核实为公开可见；默认分支 `main` 的 README/进度描述仍停留在 M3，落后于规范分支）
> **规范分支：** `m4a/crop`
> **当前可执行候选源码：** `7c49e57e3b6eafeeb765f2600c17b0242bea1160`
> **当前候选版本：** `1.7.0+23`
> **M4-B目标版本：** `1.8.0+24`（完整自动门禁已通过；源码尚未冻结，当前不是候选）
> **项目阶段：** 私有自用；M3 已接受，M4-A 与拍摄时间/GPS增强处于真机验收阶段
> **安全：** 不要提交或公开用户媒体、精确GPS、凭据、运行时数据库或私有日志；用户私下提供F19日志用于排障时，只分析其指定的任务（未指定则只看最后一次任务），任何秘密值统一写为 `[REDACTED]`。

---

## 1. 一分钟结论

VideoSlim 是一个**完全在 Android 本机运行**的视频/音频处理工具。当前已经实现：

- 视频导入、信息读取、HEVC/H.264 硬件 VBR 压缩；
- 前台服务、进度、通知、取消、任务重连、发布与异常退出恢复；
- AAC 音轨无损直提和 AAC-LC 强制重编码；
- 画面裁剪编辑器及单次 `Crop → Presentation → encode` 管线；
- 只保留可靠来源拍摄时间/GPS，发布前同时核验“应有”和“应无”；
- 默认 MediaStore / 自定义 SAF 输出、真实文件名与位置、打开与分享；
- F19 本地调试日志，超长日志只复制最近128 KiB，完整日志走文件分享。

**C1a已实现，但项目所有者明确跳过其真机验收；未执行矩阵仍是PENDING，不能写PASS。** D1已完成；M4-B/F8连续单段时间裁剪已完成RED→GREEN和完整自动门禁，唯一复审和APK静态核验完成前不得称为候选。C1b/C2/C3/M4-C仍未授权。当前`1.7.0+23`候选的既有真机债务不会因继续开发而自动转为PASS：

1. 复测约1 MiB日志复制不再触发Android Binder异常；
2. 补齐M4-A裁剪真机矩阵；
3. 补齐真实iPhone/Pixel来源的时间/GPS、MediaStore `DATE_TAKEN`、图库排序和SAF矩阵；
4. 由项目所有者决定接受、拒绝或授权一个明确的小修。

除已明确授权的M4-B外，M4-C、M5打磨、hardening、refactor、migration均**没有自动开工权限**。

r2历史上只授权写入规划；r7中项目所有者已单独授权§7.2 M4-B单段时间裁剪实施。§7.3 M4-C及C1b/C2/C3仍为`PLANNED — NOT AUTHORIZED`。

---

## 2. 已验证基线与候选身份

### 2.1 接受状态与候选状态不是同一概念

| 对象 | 状态 | 含义 |
|---|---|---|
| M3音频提取 `1.4.3+18` | `ACCEPTED — private scope` | 项目所有者已在自用范围接受；不代表多设备或生产发布 |
| M4-A画面裁剪 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 实现、自动化和APK静态检查完成；完整真机矩阵未完成 |
| 拍摄时间/GPS与输出命名 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 一条Pixel设备任务成功；真实来源/图库/SAF矩阵未完成 |
| 超长日志复制修复 | `CANDIDATE READY — DEVICE RETEST PENDING` | 自动化、实际日志smoke和exact-SHA复审通过；尚待手机复测 |
| M4-B时间裁剪 | `AUTOMATED VERIFIED — REVIEW PENDING` | F8连续单段trim已完成RED→GREEN和完整门禁；多段/跨文件不在范围，候选与真机证据尚未完成 |
| M4-C多段时间编辑 | `PLANNED — NOT AUTHORIZED` | r2 新增规划（§7.3）；依赖 M4-B 验收通过 |
| 压缩策略修正 C 轨（D1/C1a/C1b/C2/C3） | `C1a IMPLEMENTED — TEST WAIVED；D1 COMPLETE` | C1a未真机测试、不记PASS；D1确认Pixel HEVC运行期明显过冲；C1b/C2/C3仍未授权 |
| M5/M6 | `NOT STARTED` | 仅为路线图，不是当前任务 |

### 2.2 当前私有验收APK

```text
文件：VideoSlim-1.7.0+23-7c49e57-arm64-v8a-release.apk
package：com.videoslim.videoslim
versionName/versionCode：1.7.0 / 23
源码：7c49e57e3b6eafeeb765f2600c17b0242bea1160
源码 tree：36c50b0d1a5a3929148d5aa6ea0aa4c31bb4c709
ABI：arm64-v8a only
minSdk/targetSdk/compileSdk：26 / 36 / 36
大小：18,378,659 bytes
SHA-256：72dcce8374c3bb771cdfa1b8fddd6d2dfec8baba19f8e3b917015c221e92367f
签名：Android Debug certificate，APK Signature Scheme v2
```

该APK只用于项目所有者私有真机验收，不是production-signed商店包。旧`1.6.0+21`因日志复制缺陷已被隔离；`1.6.1+22`保留为历史候选证据，不再作为当前包分发。

r4历史走读基线为`9796b0d...`，当时当前APK生产源码仍为`b0267a0...`。r6当前候选`7c49e57...`只在Dart planner/UI/test和版本身份上增加C1a；`android/`生产源码仍与`b0267a0...`一致。

---

## 3. 产品边界和不可擅自改变的决定

### 3.1 产品边界

- Android本地处理，视频和音频不上传；
- App没有 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE`；
- 输入来自Photo Picker或SAF `content://` URI；
- 输出先写应用私有临时文件，完整验证后才发布到MediaStore/SAF；
- 当前仅供项目所有者私用；不承诺多设备、商店发布或iOS；
- release只包含 `arm64-v8a`。

### 3.2 产品不变量

除非项目所有者明确改变决定，任何AI都必须保持：

1. **硬件VBR不变。** 不得改为CBR，也不得因实际码率/体积偏离目标而拒绝发布。
2. **单前台服务、同一时间一个任务、`finishOnce`唯一终止门。**
3. Media3 create/start/cancel/dispose保持main-affine。
4. recovery使用的 `SharedPreferences.commit()` durability语义不得改成 `apply()`。
5. 不修改或覆盖源文件；失败、取消、恢复只清理能证明属于当前任务的对象。
6. 裁剪、缩放、压缩必须一次转码完成，不创建中间视频后再二次有损编码。
7. 拍摄metadata只允许可靠时间和GPS；不复制完整XMP/mdta、设备型号、软件版本或厂商私有atom。
8. metadata字段缺失时保持缺失；不得用处理时间、文件mtime、当前位置或文件名伪造。
9. 不增加隐私模式、音频继承视频metadata、第二次remux、自定义MP4 writer或Media3升级，除非项目所有者重新批准范围。
10. 自动化、模拟器和静态review不能替代真机证据。

r2 适用性说明（r5 措辞更新）：不变量1约束的是"擅自替换现有硬件VBR行为"。§7.1 中的 C1a 只提示不改值、C1b 仅在用户显式采用建议值时调整下发给 VBR 的目标值、C3a/C3c 保持 VBR 码率模式，均与其不冲突；C3b（CQ 画质档）若被采纳，属于所有者明示批准的**新增档位**而非替换现有 VBR 档，实施前需把该修订写回本节。任何 C 轨条目都不引入"体积不达标即拒绝发布"的行为。

---

## 4. 当前实现架构

### 4.1 技术栈

| 层 | 当前实现 |
|---|---|
| UI/业务 | Flutter 3.44.6 / Dart 3.12.2，Provider，Material 3 |
| 视频 | Android Kotlin + Jetpack Media3 Transformer `1.10.1` + MediaCodec硬件编解码 |
| AAC无损直提 | `MediaExtractor + MediaMuxer` sample copy |
| AAC有损转换 | Media3 audio-only，强制Decoder → AAC Encoder |
| 输出 | 私有temp → 验证 → MediaStore或持久化SAF tree |
| 运行时 | 单 `ProcessingService`、`ProcessingRuntime/Registry`、WakeLock、通知、snapshot |
| 恢复 | `TaskRecoveryStore` + `RecoveryIoCoordinator` + 启动reconciliation |
| 平台边界 | Flutter `MethodChannel videoslim/engine` + `EventChannel videoslim/progress` |
| 测试 | Flutter/widget/pure Dart + Android JVM；API 35 instrumentation工作流存在但runner配置未修复 |

### 4.2 分层关系

```text
Flutter Screens / Widgets
        │
        ▼
HomeFlowState + Dart models/planners
        │
        ▼
VideoEngine interface / MethodChannel adapter
        │
        ▼
EngineChannel + ProcessingRuntime/Registry
        │
        ├── TranscodeEngine
        │     Media3 Transformer + hardware codec + effects
        ├── AudioExtractionEngine
        │     AAC sample copy / Media3 audio-only encode
        ├── ProcessingService
        │     foreground + notification + wake-lock + finishOnce
        └── publication/recovery
              private temp → verify → MediaStore/SAF
              journal → startup reconciliation → evidence-based cleanup
```

依赖方向必须保持：UI调用Dart业务/引擎接口；平台实现不把UI状态反向塞入原生引擎；publication/recovery是共享稳定接缝，视频和音频codec细节保持独立。

### 4.3 视频主流程

```text
Picker/SAF URI
→ VideoMetadataReader
→ 可靠来源时间/GPS policy
→ CompressionPlanner（按裁剪后的最终像素规划）
→ ProcessRequest
→ codec/capability/storage preflight
→ Media3 Transformer
   effects固定为 Crop → Presentation
   InAppMp4Muxer只写白名单时间/GPS
→ 私有临时MP4回读
→ 验证时间/GPS应有及应无
→ MediaStore/SAF发布
→ registry/snapshot终态
→ Flutter结果、打开和分享
```

### 4.4 音频主流程

AAC copy：

```text
第一音轨物理扫描
→ 验证AAC profile和≤2声道
→ MediaExtractor/MediaMuxer逐sample copy
→ 输出物理扫描
→ count/PTS/payload bytes/digest/覆盖时长对照
→ 发布
```

AAC encode：

```text
第一音轨metadata/profile/channel校验
→ Media3 audio-only强制decode/encode
→ AAC-LC 192/128/96/64 kbps
→ 输出物理校验
→ 发布
```

### 4.5 关键领域契约

- `taskKind` 只允许 `video_compression | audio_extraction`；新event/snapshot必须显式携带。
- `ProcessRequest.crop` 使用**显示方向像素** `{left,top,width,height}`。
- `trimStartMs/trimEndMs`承载同一连续半开区间`[startMs,endMs)`；两端必须成对为整数、至少保留1秒且不超过来源时长，非法值fail closed为`INVALID_TRIM`。
- progress状态：`running | success | failed | cancelled`；阶段：`preparing | encoding | publishing | cancelling | finished`。
- 公开URI分配、写入和journal阶段必须保持单调；所有权证据不足时宁可quarantine，也不能猜测删除。
- 视频输出名反映fallback后的最终codec；`target`只表示目标码率，不表示实际平均码率。
- 小日志完整复制；超长日志只复制最近128 KiB完整UTF-8行并提示截断；完整文件继续由FileProvider分享。

---

## 5. 已完成能力

### 5.1 M0/M1/M2

- 无头Linux Flutter/Android工具链和ARM64 APK构建；
- Photo Picker/SAF导入、视频信息读取；
- HEVC/H.264硬件VBR压缩、预设/自定义、分辨率规划和体积预估；
- 音轨copy/reencode/remove；
- 前台服务、通知、进度、取消、失败分类；
- 默认MediaStore和自定义SAF；
- 真实输出名/位置、打开和分享；
- snapshot重连、durable journal、启动对账和所有权安全清理；
- software decoder兼容重试；
- F19应用内日志。

M2已接受为private scope；极端长媒体、多Provider、多SoC、API 26–28和破坏性恢复扩展矩阵仍属于未完成hardening证据。

### 5.2 M3音频提取

- 第一AAC音轨无损直提；
- AAC-LC 192/128/96/64 kbps强制重编码；
- mono/stereo，>2声道稳定拒绝；
- Opus等非AAC源可显式转AAC，但不能伪装为copy；
- 默认 `Music/VideoSlim` 和自定义SAF；
- 发布前sample完整性证明；
- 已验证metadata的一次性handoff，避免成功后重复完整扫描。

项目所有者已明确接受M3为private scope。原PRD“一小时copy <10秒”没有被证明，不得补写为PASS。

### 5.3 M4-A画面裁剪

- 首页和S3双入口汇合到同一S4编辑器；
- 自由、16:9、9:16、1:1、4:3；
- 八手柄、整体移动、64×64下限、偶数像素；
- 预览帧滑杆、180ms节流、旧响应防覆盖；
- 慢速手势从按下点累计总位移；自由模式取整后固定相反边；
- 显示像素 → Media3 NDC mapper；
- 单次 `Crop → Presentation`；
- `INVALID_CROP` fail closed及编辑/移除恢复；
- crop在request/snapshot/retry中round-trip；
- “保持画质（仅裁剪）”档。

实现已完成，但0°/90°/180°/270°、后台/锁屏/取消/恢复和文件安全十项真机矩阵仍未完成。

### 5.4 拍摄时间/GPS与命名

- 只解析可靠来源时间和ISO 6709 GPS；
- 通过同一次Media3 mux写入；
- 无来源时间时写1904/zero sentinel覆盖Media3默认处理时间，但sentinel不进入业务metadata或 `DATE_TAKEN`；
- 发布前回读最终临时MP4，既检查应有字段，也拒绝意外出现的应无字段；
- 核验失败返回 `CAPTURE_METADATA_FAILED`，不发布；
- 默认MediaStore视频写入可信 `DATE_TAKEN`；SAF只承诺文件内部metadata；
- 文件名包含最终codec/模式、目标码率、毫秒处理时间和防碰撞token。

### 5.5 最近的超长日志修复

设备上复制约1 MiB完整日志时，Android剪贴板Binder抛出 `TransactionTooLargeException`。当前候选：

- 对超长日志只取最近128 KiB、UTF-8安全且从完整物理行开始；
- clipboard内容附截断说明；
- 完整日志仍通过“分享日志”作为文件导出；
- UI不再显示原始平台堆栈；
- 真实 `1,048,167` bytes日志smoke得到 `130,885` bytes载荷，并保留最新事件。

该修复没有修改转码、metadata、publication或recovery路径。

### 5.6 C1a低收益/可能变大提示

- 仅在源大小已知且保守输出上界意味着预计节省低于15%时提示；
- 源大小未知（wire为0）不误报；
- 无crop提供“暂不处理/仍按原目标压缩”；有crop始终提供“保持画质（仅裁剪）/仍按原目标压缩/取消”；
- 选择保持画质不创建native任务；继续时不改用户原目标；
- 不增加输出体积发布门禁，硬件VBR策略不变。

完整实现、复审处置、APK证据和真机矩阵见`docs/c1a-low-savings-completion-report.md`与`docs/c1a-low-savings-device-acceptance.md`。

---

## 6. 当前任务进度与下一步

### 6.1 已完成并关闭

| 任务 | 结果 |
|---|---|
| 复现日志Binder超限 | RED测试已证明旧实现会把整份日志交给剪贴板 |
| 实现最近128 KiB复制 | 已完成；小日志不变、超长尾部、UTF-8/行边界、准确提示均有测试 |
| 构建 `1.6.1+22` | 已完成并静态核验 |
| exact-SHA focused review | PASS，无BLOCKER/IMPORTANT finding |
| 规划文档初次入库 | `3ca2dc7...`已推送到`m4a/crop`；r6候选证据在后续docs-only提交同步 |
| C1a RED→GREEN | 15%边界、未知大小、无crop、crop切换及crop已选保持画质路径均覆盖 |
| C1a唯一复审与修订 | 首版`d3af1c3...`两路FAIL；三项IMPORTANT全部在`7c49e57...`唯一修订中处置，不把旧FAIL改写为最终PASS |
| C1a自动化 | format、analyze、Flutter `227/227`、Android JVM `341/341`、lint和debug assemble通过 |
| 构建`1.7.0+23` | ARM64 release、ZIP、zipalign、v2签名、证书连续性、身份/权限/秘密扫描通过 |

### 6.2 当前唯一优先队列：M4-B验证、复审与候选构建

项目所有者已跳过C1a真机矩阵；以下P0/P1清单继续作为未测试债务保留，不阻止已明确授权的M4-B开工，也不得改写为PASS。

#### P0：安装并复测当前APK

1. 使用相同签名覆盖安装`1.7.0+23`，不要先卸载，以保留应用数据和旧F19日志；
2. 先执行`docs/c1a-low-savings-device-acceptance.md`，确认提示条件、无crop两项、有crop三项和“保持画质不启动”；
3. 打开F19调试日志并执行复制；
4. 预期不再出现Binder异常，提示只复制最近部分；
5. 粘贴确认包含最新任务尾部；
6. 使用“分享日志”确认导出的仍是完整文件。

#### P1：拍摄时间/GPS真实来源矩阵

至少区分：

- iPhone SDR MOV：时间+GPS、仅时间；
- Pixel普通MP4：时间+GPS、仅时间；
- 明确无时间/GPS的MP4；
- HDR/AV1仅在有真实使用需求时执行。

每个来源都需要：

1. 原始未加工文件；
2. 输入 `exiftool`/`ffprobe`只读基线；
3. App默认MediaStore输出；
4. 自定义SAF输出；
5. 输出内部时间/GPS外部对照；
6. MediaStore `DATE_TAKEN`和具体图库显示/排序；
7. 源文件、旧输出和其他文件安全检查。

共享报告只写 `present/missing/match/mismatch`，不要公开精确GPS。

#### P1：M4-A十项矩阵

- rotation 0°、90°、180°、270°；
- 五种比例、八手柄、整体移动；
- 裁剪后720p缩放顺序；
- “保持画质”的主观质量和体积关系；
- 未裁剪普通压缩回归；
- 取消、后台、锁屏、进程恢复；
- S3预估与实际误差；
- F19裁剪参数；
- 默认MediaStore和SAF；
- 源文件/旧输出/无关文件安全。

#### P2：项目所有者决策

完成上述证据后，项目所有者明确选择：

- `ACCEPTED — private scope`；或
- `REJECTED — blocker found`；或
- 只授权一个可复现问题的局部修订。

未获得该决定前，不得把M4-A或metadata增强写为ACCEPTED。

### 6.3 当前停止条件

出现以下任一情况立即停止当前用例，保留素材、截图和完整F19日志：

- 源文件、旧输出或VideoSlim之外的文件被修改、覆盖或删除；
- 失败/取消留下无法清理的公开半成品；
- 任务卡死、永不结束、错误成功、错误取消或无法恢复；
- 裁剪出现无法由显示坐标和≤2px取整解释的错位、双重旋转或拉伸；
- 单次Media3 mux不能可靠保留承诺的时间/GPS，或输出出现不应存在的字段。

不得用第二次转码、静默remux或扩大重构来绕过停止条件。

---

## 7. 后续路线图（r2 修订；除特别标注外均未授权实施）

新功能编号说明（r4修正、仓库整合时落位）：PRD已占用F16（视频合并、变速、GIF）、F17（HDR）、F18（iOS）、F19（应用内日志），r2/r3曾试用的F16–F18编号存在冲突。C轨继续使用轨内编号 **D1 / C1a / C1b / C2 / C3**，PRD正式映射为F20（C1a/C1b）、F21（C2）、F22（C3）；M4-C1同源多段映射为F23。M4-C2跨文件拼接归PRD既有F16，不重复立项。

### 7.0 推荐排期（最终由项目所有者决定）

1. M4-B单段时间裁剪（当前，中）；
2. C2编码能力探测（小型诊断改动，不改转码行为）；
3. C1b建议目标为后置条件项（依赖显式码率来源契约、D1结论与系数校准，见§7.1），与C3决策窗口一并评估，不单列排期；
4. C3按C2证据四选一，含"都不做"（中）；
5. M4-C多段时间编辑（中大，依赖M4-B验收）；
6. M5原范围顺延，其中F13建议提级（见7.4 r2备注）。

每项独立授权、独立验收；同一时间不并行两条编码任务。r4 修正：D1 第一步（读既有日志）零代码、可立即执行，无需授权流程；C2 虽只查询能力、不改转码行为，但仍是完整的小型代码任务（候选、测试、复审、真机），按独立小候选走，不与 D1 捆绑。

### 7.1 C 轨：压缩策略修正（C1a `CANDIDATE READY — DEVICE ACCEPTANCE PENDING`；其余未授权）

**背景。** 硬件 VBR 对高码率源效果良好，但对低码率源可能反向膨胀。§8 记录的任务：源总码率约 850 kbps，请求 500 kbps，实测视频码率 `2,307,117` bps（约 4.6 倍偏差），输出为输入的 2.70 倍。所有者已确认保留 VBR、不改 CBR；但**诊断偏差原因**与**提前识别并提示低收益或可能变大的任务**不属于改变编码策略。

**原理备注（帮助后续 AI 理解需求本源）。** 所有者在 MediaCoder 观察到的"按画质百分比压缩"，本质是恒定质量码控（x264/x265 的 CRF / Rate Factor）：给定质量水平，码率随内容复杂度浮动。恒定质量同样不保证低码率源不膨胀——质量档高于源实际画质时，编码器会花码率复刻源里的压缩噪点。工程上稳定的形态是"质量目标 + 码率上限"。C 轨在现有硬件管线内用 C1（提示 + 用户显式采用的建议上限）+ C3（画质上限或更高效 codec）逼近该形态，而不引入软件编码器。

#### D1 码率下发诊断（先行）

r4 更新：所有者侧 AI 已完成仓库走读（HEAD `9796b0d`，`android/` 自 `b0267a0` 起无代码变化），取得代码证据：`VideoEncoderSettings.Builder().setBitrate(task.request.videoBitrate).setBitrateMode(BITRATE_MODE_VBR)` 确实存在；未启用 `experimentalSetEnableHighQualityTargeting`；`DefaultEncoderFactory` 配置了 `setEnableFallback(true)`；且已有 `LoggingEncoderFactory` 把 `codec.configurationFormat` 写入 F19（形如 `actual video encoder name=... format=...`）。据此，原嫌疑 1（请求值未接线）与嫌疑 2（HQ targeting 覆盖）**已排除**，后续 AI 无需重复 grep 走读。

- D1结果（r7）：Pixel 10 Pro最新相关任务选中`c2.google.hevc.encoder`，Media3有效encoder `Format`仍记录`500,000 bps`，排除fallback配置期把目标夹到约2 Mbps；归类为该设备/HEVC组合运行期明显过冲。现有`codec.configurationFormat`是`androidx.media3.common.Format`，不是原始`android.media.MediaFormat`；若以后必须证明传给`MediaCodec.configure()`的`KEY_BIT_RATE`，仍需另加底层证据。
- 零代码步骤已完成，无需日志修订。输出`videoBitrate=2,307,117`与`文件字节×8÷时长`的容器平均码率一致，因此不是独立视频轨码率证据；完整脱敏计算见`docs/d1-bitrate-diagnosis-2026-07-23.md`。
- 结果分支：
  - 记录码率 ≈ `500,000` → 值已正确配置，属编码器运行期下限/无视低目标 → 记入 §10；此时**降低请求值无济于事**，C1a（提示）与 C3 才是主对策，该分支下 C1b 不得提供更低建议值；
  - 记录码率明显更高（如 ~2.3 Mbps）或编码器名与预期不符 → fallback 在配置期按编码器声明能力夹高/更换了编码器 → 结合 C2 的 `bitrateRange` 查询闭环取证，再决定接受、换编码器或调整请求策略；该声明范围同时构成 C1b 建议值的下限约束；
  - 该行缺失/无码率字段 → 走步骤 2 的最小日志修订。
- 停止条件：诊断过程不顺手修改任何编码行为；F19 分析只看该次任务。

#### C1 源感知提示（C1a，已实现/测试跳过）与建议性目标（C1b，未授权）（r5 拆分）

r5 拆分理由：r4 曾拟用"`videoBitrate` 与文件大小 × 8 ÷ 时长之差 ≤ 3%"的一致性推断猜测码率来源，复核认定该启发式在低音轨码率素材上易误判（此类素材的视频轨码率天然接近容器总码率），且与"一次实现、一次修订"的预算不匹配，**予以废弃**。提示不需要知道源视频码率，先行；建议值等显式契约与 D1 证据。

**C1a 低收益/可能变大提示（先行，纯 Dart）**

- 定位：**只提示，不改任何值**。仅当 `source.fileSizeBytes > 0` 时，用现有保守上界估算 `estimatedOutputMaxBytes`（字段名以代码为准）与**源文件大小**比较；预计节省 < 15%（含预计变大）即提示"该视频已经很小，压缩收益有限，甚至可能变大"。Provider 无法提供大小时 wire 值为 `0`，此时不得计算节省率或误报"可能变大"；S3 只把收益估算标记为不可用，不触发低收益提示。
- 选项按是否存在 crop 条件显示（现有代码明确拒绝 `preserveQuality && crop == null`，不得展示会被业务模型拒绝的选项）：
  - 有 crop：保持画质（仅裁剪）/ 仍按原目标压缩 / 取消；
  - 无 crop：暂不处理 / 仍按原目标压缩。"暂不处理"的文案直说：任何当前可用压缩档都会重新编码，低码率源最不失真的做法是暂不处理；无损转封装属 M5 F13 范围。
- 文案不承诺输出体积。
- 实施时替换 S3 既有的单一低收益提示判定与确认流程，不叠加第二个弹窗；已有“允许用户仍然继续”的产品行为保持不变。
- 边界：`lib/logic/compression_planner.dart` + S3 文案与选项，纯 Dart；不改 Kotlin、发布/校验/恢复；不引入体积拒发布。
- 测试：planner 单测（高/低预计节省率 × 有无 crop × 节省率在 15% 阈值上下 × `fileSizeBytes=0`）；S3 回归；一条真机低码率源任务，记录提示行为与最终体积。
- 停止条件：提示误报高频 → 暂停提示并回报；不得转为静默改写目标或拦截发布。

**C1b 建议性目标上限（后置条件项）**

- 前置条件（三者齐备才实施）：
  1. 显式码率来源契约：原生→Dart 返回 `videoBitrateSource = track | container | unknown`，或分别返回 `videoTrackBitrate` 与 `containerBitrate`——一次小型跨层改动，结果确定、可测试，取代已废弃的推断法。`track` 直接使用视频轨码率；`container` 使用容器平均码率减去可靠音频码率（缺失时使用明确标记的保守估计，无音轨则不减），结果继续标为 estimated；`unknown` 不生成建议值；
  2. D1 结论明确（见下方闭环分支）；
  3. 每个准备启用的 source codec → 最终输出 codec 组合都至少有一条真机样本完成独立校准；一个组合的样本不得批准其他组合。
- 与 D1 结果闭环（防止建议值误导用户）：
  - D1 证明目标基本被硬件遵守 → 可显示"采用建议目标"；
  - D1 证明 fallback 配置期夹高 → 建议值不得低于该编码器声明的支持下限（数据来自 C2）；
  - D1 证明配置约 500 kbps 后仍运行期严重过冲 → **不提供更低建议值**，C1a 的选项即为全部选项。
- 建议值 = min(用户目标, 源视频轨码率或明确标记的估计值 × 系数 × (目标像素 / 源像素))。codec 方向使用 capability fallback 后的**最终输出 codec**。建议值必须同时落在产品允许范围（当前代码：自定义码率下限 500 kbps、UI 滑杆 0.5–12 Mbps、preset 像素缩放后 800 kbps 下限，均以代码为准）与已知编码器声明范围内，并按当前 UI 的 100 kbps 步进做确定性取整；计算值低于产品下限时显示"暂无可靠建议目标"，**不得夹到 500 kbps 后假装仍有理论依据**。
- 系数只对已校准组合生效：H.264→HEVC 0.65、HEVC→HEVC 0.85、H.264→H.264 0.80（常量集中定义）；HEVC→H.264、AV1/VP9 等来源、未知源 codec 一律只显示风险提示，不生成建议值。
- 采用方式：仅由用户显式点按"采用建议目标"，绝不静默改写；用户最终确认的值统一用于编码下发、输出文件名与 F19 记录，S3 同步显示。
- 停止条件：建议值明显异常或与真机结果系统性偏离 → 下线建议值，仅保留 C1a。

#### C2 编码能力探测（推荐项，小型诊断改动）

- 用户价值：把 C3 的选择从猜测变成本机（Pixel 10 Pro）实测证据，并留档设备差异。
- 形式：F19 调试区新增"编码器能力"只读页（或 debug 构建输出到日志）：遍历 `MediaCodecList`，对每个编码器记录 mime（`video/avc`、`video/hevc`、`video/av01`、`video/x-vnd.on2.vp9`）、`isBitrateModeSupported(CQ/VBR/CBR)`、`FEATURE_QpBounds`（API 31+）、`bitrateRange`、`complexityRange`、软/硬件属性。
- 边界（r4 措辞修正）：探测本身只查询 capabilities，不 configure、不建任务，**不改变任何转码行为**；但新增页面/平台通道/日志入口仍是完整的小型代码任务，需要新版本候选、测试、复审与真机运行验证，不应描述为"零成本/近零改动"。
- 产出：能力清单回写设备验收文档，作为 C3 的决策输入。
- 风险：个别厂商 capabilities 声明与实际行为不符——C3 验收仍以真机编码证据为准。

#### C3 增强档（条件项，按 C2 证据四选一——含"都不做"，至多实施一个）

- **C3a QP 钳制档**（若 `FEATURE_QpBounds` 支持，默认优先）：保持 VBR + `KEY_VIDEO_QP_MIN`（初值在 28–34 区间试验），必要时配 `KEY_VIDEO_QP_MAX`。效果 = 给"最好画质"设上限，直接抑制低码率源上的码率过冲，最接近恒定质量且不更换码率模式。呈现方式二选一（所有者定）：仅当 C1a 触发低收益风险提示且 D1/C2 证据支持时自动附加，或作为"体积优先"高级开关。r3 实现成本修正：`VideoEncoderSettings` 只暴露码率/码率模式/profile/level 等，**未暴露 QP 键**（以 1.10.1 实测为准），因此 C3a 与 C3b 同样需要自定义 EncoderFactory 直接构造 MediaFormat；三个档位的工程量差异主要在验收面而非代码量，C3a 的相对优势在于不更换码率模式、对不变量冲击最小。
- **C3b CQ 画质档**（若 `BITRATE_MODE_CQ` 在硬件编码器上支持）：`KEY_QUALITY` 档位，对应 MediaCoder 的"画质百分比"体验；需自定义 EncoderFactory（Media3 默认工厂只接受 VBR/CBR）；采纳即触发 §3.2 r2 说明中的不变量修订流程。
- **C3c AV1 存档档**（前提：C2 在目标设备上确认存在可用的 `video/av01` 硬件编码器）：同画质体积再降约两三成属**实验预期**而非产品承诺；定位"仅自存档"，UI 必须带兼容性警告（多数其他设备无 AV1 硬编、部分播放器不支持）。前置验证：InAppMp4Muxer 对 av01 的写入、时间/GPS 白名单核验、缩略图/图库/`DATE_TAKEN` 行为；任何一项不满足即搁置，不得为此更换 muxer（不变量 9）。r4 定位修正：其验收面（mux、相册/缩略图、播放与分享兼容）使它实质上是一个**独立格式功能**而非编码器参数调整，工程量按独立功能评估。
- 决策规则（r4）：C2 出证据后，所有者在四个选项中决策——C3a / C3b / C3c / **都不做（维持现有 VBR + C1 提示策略）**。"至多实施一个"仍成立，但不预设"必须选一个"；所选档位走完整验收矩阵（含"保持画质（仅裁剪）"档与普通 VBR 档的回归）。
- **明确不做（决策记录）：软件 x264/x265/SVT-AV1 CRF 编码。** 理由：手机端速度与发热、APK 体积、GPL 复查负担（M6 项）、ffmpeg-kit 生态 2025-01 退役后碎片化，以及最关键——绕开 Media3 单管线将迫使裁剪、metadata 白名单、发布校验全部重做。复议条件：C1+C3 落地后仍存在所有者确认的真实素材无法达标，且所有者接受"慢速模式"的全部代价。
- 期望管理：约 850 kbps 的源即便一切正确，同画质 HEVC 重编的合理收益也只有三四成（其中还含音轨占比）；对已经很小的源，最优解往往是不重编视频轨（M5 F13）或只处理音轨。**C 轨的验收口径（r4 修正）：低收益或可能变大的任务必须得到明确提示；不承诺消灭膨胀，体积结果也不作为发布拦截条件。**

### 7.2 M4-B：单段时间裁剪 F8（r2补全计划；r8 `AUTOMATED VERIFIED — REVIEW PENDING`）

项目所有者于2026-07-23明确跳过C1a真机测试并要求继续下一步，现已授权本节连续单段trim实施；该授权不包含M4-C多段或任何顺带重构。

- 用户价值：掐头去尾与裁剪、压缩一次转码完成。不做的后果：需要外部工具先剪切再导入，多一次有损编码。
- 范围：
  1. S4 编辑器新增起/止双滑块，复用现有预览帧读取（180ms 节流与旧响应防覆盖沿用）；
  2. 启用 `ProcessRequest.trimStartMs/trimEndMs`（wire contract 已预留；现行为非空即拒，实施时改为校验通过后放行）；
  3. Kotlin 侧 `MediaItem.ClippingConfiguration`，与 `Crop → Presentation` 同一次 Transformer 导出；走解码后重编路径，**验收目标**（r4：真机证据前不写为已保证事实）为视频起止误差 ≤ 1 帧 + 设备容差、不依赖关键帧对齐，以真机 ffprobe 与主观音画同步核对为准；
  4. 校验：`0 ≤ start < end ≤ 时长`、最短保留时长（初值 1s）、fail-closed `INVALID_TRIM`；
  5. S3 体积预估按保留时长折算；
  6. trim 参数在 request/snapshot/retry/recovery 中 round-trip，照抄 crop 先例；
  7. metadata 决定：拍摄时间/GPS 仍按源策略写入（时间裁剪不改变"这是何时拍摄"的语义），该决定纳入发布前应有/应无核验预期。
- 明确不做：多段、跨文件拼接、无损切割、时间轴缩略图带（双滑块 + 现有预览帧已满足自用）。
- 测试矩阵（r3 剪枝：trim 作用于时间轴，与 rotation 理论正交，不做四向全交叉）：0° 全量 {无裁剪, 裁剪} × {无 trim, 掐头, 去尾, 两端}；90° 抽查"裁剪 + 两端 trim"一组；180°/270° 仅在 M4-A 矩阵发现 rotation 相关缺陷时补测。**追加维度：trim × audioMode {copy, reencode, remove} 各一组**——音轨在截断边界的对齐（AAC frame 粒度与编码延迟）是时间裁剪最真实的风险点。边界用例：start=0、end=时长、极短段、非关键帧起点；输出时长与请求差 ≤ 1 帧 + 容差（音轨额外允许约一个 AAC frame 的封装差，ffprobe 对照）；音画同步主观核对；取消/后台/锁屏/进程恢复后 trim 不丢；metadata 核验不回归；文件安全十项沿用。
- 风险：截段后音频 priming/首帧对齐的设备差异；S3 预估误差变大。
- 停止条件：可感知音画漂移、时长偏差超一帧加容差、恢复后 trim 丢失，或触发 §6.3 通用停止条件。

### 7.3 M4-C：多段时间编辑（r2 新增，`PLANNED — NOT AUTHORIZED`，依赖 M4-B 验收）

- 用户价值：删除视频中间任意一段，或保留多段一次输出（例：保留 `[0,t1]` + `[t2,end]` 即删除中段）；仍是单次转码、无中间文件（不变量 6 成立）。
- 技术路线：Media3 `Composition` + `EditedMediaItemSequence`——同一源拆成 N 个 `EditedMediaItem`，各带自己的 `ClippingConfiguration`，共享同一套 `Crop → Presentation` effects，一次导出。
- 契约扩展：`ProcessRequest` 由单 trim 扩展为有序 `segments[{startMs,endMs}]`（长度 1 等价 M4-B；旧字段映射为单段保持兼容）；段间禁止重叠、必须递增——即**不支持段乱序重排**，重排属拼接类新需求，出现时另行评估；snapshot/recovery 携带完整段列表。
- 范围切分：M4-C1 仅同源多段；跨文件拼接列为 M4-C2 可选项（需追加音频参数一致性与 Presentation 统一输出尺寸验证），默认不做。
- "把长视频切成 N 份独立输出"**不在** M4-C：等价于对同源发起多次单段任务，由 M5 F10 批量队列自然覆盖，不为其改动单任务运行时。
- UI：分段列表（每段起止 + 删除/添加），不做时间轴轨道编辑。
- 测试矩阵：段边界 PTS 连续性（无重帧/丢帧）、边界音画同步、整体进度单调、S3 预估 = Σ段时长折算、取消/恢复 round-trip、metadata 核验、文件安全。
- 风险：段边界处解码器/编码器 flush 行为的设备差异；进度映射。
- 停止条件：段边界出现可感知卡顿、闪帧或时间轴错乱。

### 7.4 M5：自用版打磨

PRD中的候选范围：

- F9历史记录与累计节省统计；
- F10批量顺序队列；
- F11目标大小压缩（这是独立于VBR自定义码率的产品能力）；
- F13无画面编辑时的无损去音轨/转封装路径（当前压缩任务中的 `audioMode=remove` 已存在，但仍会随视频压缩重编码）；
- F14旋转/翻转；
- F12帧率、F15 MP3先做Media3能力和授权评估，再决定实现或删除；
- UI空态/异常态和连续两周自用稳定性。

M5不是当前任务，不得因“顺手”而提前加数据库、队列或新生命周期框架。

r2 备注：

- F13（无画面编辑时的无损去音轨/转封装）是低码率源场景的最优解之一——不重编视频轨即零画质损失。建议 C 轨落地后在 M5 内提升 F13 优先级，并把它作为 C1 提示里"保持画质"路线的最终形态；
- F11 目标大小压缩与 C 轨的"相对源质量模式"是两个不同能力：前者精确命中体积，后者降低盲目膨胀风险、但不保证输出一定小于源文件。需求沟通与实现时不得合并；
- "长视频切成多份"由 F10 批量队列 + M4-B 单段任务组合覆盖，不新设功能（见 §7.3）。

### 7.5 M6：上架/iOS

- AVFoundation实现同一Dart引擎契约；
- production signing、隐私政策、商店素材；
- 开源许可证和GPL风险复查；
- 当前debug certificate和Android-only证据不能用于声称已经具备上架条件。

---

## 8. 最近一次真机任务的事实

项目所有者提供的最后一次日志对应Pixel 10 Pro / Android 17上的一次任务：

- 输入：普通SDR MP4，AVC、1280×720、24fps；
- 来源解析：拍摄时间存在，位置不存在；
- 执行：硬件AVC解码、硬件HEVC编码，进度到100%；
- 发布前metadata核验：时间存在、位置不存在；
- MediaStore发布、recovery record清除和任务终态：成功；
- `errorCode/errorMessage`：均为空；
- 视频任务成功后，日志复制才触发独立的Binder超限。

体积事实：输入`279,277,518`bytes，输出`754,489,009`bytes；请求500 kbps，输出容器平均码率约`2,307,117`bps。该Dart字段来自容器fallback，不能严格称为视频轨码率；扣除已报告320 kbps音频后的近似视频+容器开销约1.987 Mbps。输出约为输入的2.70倍，因此该素材没有达到“瘦身”效果，但按项目所有者已确认的硬件VBR政策，这不是改CBR或增加严格体积拒绝的授权。

r7 D1已判定Media3有效配置仍为500 kbps，属于该Pixel HEVC组合运行期明显过冲；诊断是查明事实，不改变已确认的VBR政策，也不授权C1b给出更低建议值。

这条日志只能证明App内“时间存在、位置缺失”核验与发布成功；不能证明原文件确为Pixel相机未加工文件，也不能证明外部atom、MediaStore `DATE_TAKEN`、图库排序或SAF行为。

---

## 9. 自动化、复审和CI证据

当前C1a候选`7c49e57...`（Android生产源码仍与`b0267a0...`一致）：

```text
Dart format                         PASS
Flutter analyze                     PASS，0 issues
Flutter tests                       227/227 PASS
Android JVM tests                   341/341 PASS
Android debug/release lint          PASS
Android debug assemble              PASS
ARM64 Flutter release build         PASS
APK ZIP/zipalign/v2 signature       PASS
package/version/ABI/permission scan PASS
common credential-pattern scan      PASS
metadata corrective review          PASS（历史生产祖先）
clipboard exact-SHA focused review PASS（历史生产祖先）
C1a first review d3af1c3...         FAIL，三项IMPORTANT
C1a revision 7c49e57...             findings已处置；按预算未二次review
```

GitHub主Flutter/Android job通过。API 35 x86_64 instrumentation job在安装固定SDK时因runner的 `sdkmanager: command not found` 退出127，发生在应用和instrumentation APK构建前，所以instrumentation**没有执行**。整体workflow显示failure不能解读为应用测试失败，也不能伪装成instrumentation PASS。

---

## 10. 已知限制与冻结债务

1. `hardening/task3-engine-io`（Slice B）保留但冻结，不能合入当前候选。
2. recovery journal尚未完整迁出主线程；极慢存储/Provider条件可能造成开始或结束时短暂无响应，目前没有真机复现或文件安全证据。
3. API 35 x86_64 instrumentation runner配置未修复。
4. Release使用Android Debug certificate。
5. M4-A对Media3 effects处理旋转后的实际行为仍依赖四种rotation真机样本。
6. 未完成多SoC、多Provider、API 26–28、极端长媒体和大文件扩展矩阵。
7. 一小时AAC copy `<10秒` 未被证明；安全实现保留发布前完整物理校验。
8. 当前没有MP3、多音轨选择、时间trim、音量/降噪、混音、采样率选择、隐私模式、完整metadata复制、iOS或商店发布。
9. 硬件VBR可能明显偏离目标码率，甚至产出比源文件更大的结果；项目所有者接受保留VBR，不接受以牺牲观看质量换取CBR或严格体积命中。（r2/r5：偏差原因由 §7.1 D1 诊断；规划期提示见 C1a。）
10. 仓库已公开且默认分支 `main` 的 README/进度描述停留在 M3（2026-07-23 核实），落后于规范分支 `m4a/crop`；对外可见状态与实际候选不一致，记为文档同步债，由所有者决定是否及何时同步。

只有真机可复现问题，或源文件/旧输出/无关文件的丢失、覆盖、误删风险，才是当前private-scope blocker。其他静态问题记录为debt，由项目所有者决定是否处理。

---

## 11. 关键源码导航

### Flutter

- `lib/screens/home_screen.dart` — 主流程、视频/音频双入口、S3/S4接线
- `lib/widgets/crop_editor.dart` — 裁剪手势UI
- `lib/logic/crop_geometry.dart` — 显示像素几何、比例、边界和取整
- `lib/state/home_flow_state.dart` — typed workflow state
- `lib/engine/video_engine.dart` — 引擎抽象
- `lib/engine/method_channel_video_engine.dart` — 平台通道adapter
- `lib/models/process_request.dart` — 视频请求、crop/trim wire contract
- `lib/models/audio_extract_request.dart` — 音频请求
- `lib/logic/compression_planner.dart` — codec/码率/尺寸/估算
- `lib/logic/output_file_name_builder.dart` — 安全输出名
- `lib/logic/log_clipboard_payload.dart` — 128 KiB日志复制边界
- `lib/screens/debug_log_screen.dart` — F19读取、复制与分享UI

### Android/Kotlin

根目录：`android/app/src/main/kotlin/com/videoslim/videoslim/`

- `EngineChannel.kt` — Method/Event Channel边界
- `ProcessingRuntime.kt` / `ProcessingRegistry.kt` — 任务所有权与状态
- `ProcessingService.kt` — 前台服务、通知、wake-lock、终态
- `TranscodeEngine.kt` — Media3视频转码/effects/publication
- `CaptureMetadata.kt` / `VideoMetadataReader.kt` — 来源时间/GPS与输出核验
- `CropGeometryMapper.kt` / `PreviewFrameReader.kt` — NDC映射与预览帧
- `AudioExtractionEngine.kt` — AAC copy/AAC encode
- `AudioSampleCopyLoop.kt` — sample copy、版本化SHA-256 digest与完整性统计
- `MediaStoreSaver.kt` — MediaStore和SAF发布
- `TaskRecoveryStore.kt` / `RecoveryIoCoordinator.kt` / `VideoSlimApplication.kt` — journal和启动对账
- `AppLogStore.kt` / `LogChannel.kt` — 本地日志和平台边界

---

## 12. 规范文档阅读顺序

1. 本文：`docs/VideoSlim-AI-Handoff-2026-07-23.md`
2. `AI_REVIEW_START_HERE.md`
3. `docs/current-project-status.md`
4. `README.md`
5. `docs/d1-bitrate-diagnosis-2026-07-23.md`
6. `docs/c1a-low-savings-completion-report.md`
7. `docs/c1a-low-savings-device-acceptance.md`
8. `docs/VideoSlim PRD.md` — 产品和架构权威contract
9. `docs/capture-metadata-completion-report.md`
10. `docs/capture-metadata-device-acceptance.md`
11. `docs/m4-a-completion-report.md`
12. `docs/m4-device-acceptance.md`
13. `docs/m3-completion-report.md`
14. `docs/known-debt.md`
15. `AGENTS.md` — 审批、复审预算和blocker规则

旧完成报告中的历史SHA只证明对应历史候选；当前APK源码以`7c49e57...`为准，其中Android生产行为仍与`b0267a0...`一致。文档提交晚于候选源码提交，不得把docs-only HEAD当作APK构建源码。

---

## 13. 给下一个AI的工作规则

1. **先确认用户要求是分析、规划还是真正实施。** “分析”默认只读，不得改代码。
2. 不要重复已经完成的日志复制修复、metadata sentinel修复、M4-A手势修复或release门禁。
3. 真机验收未结束前，不启动M4-B、hardening、refactor或migration。
4. 对任何hardening/refactor/migration，先用普通用户能理解的语言说明：解决什么、若不做会怎样、会扰动什么；等待明确批准。
5. 每项任务默认最多：一次实现、一次修订、一轮复审。
6. 新行为先写失败测试并确认RED，再做最小GREEN；候选冻结后review必须锚定完整exact SHA。
7. timeout、无摘要或没有verdict的review不是PASS。
8. 任何真实构建/真机失败优先于静态review PASS。
9. 不泄露精确GPS、用户URI/文件路径、凭据或完整私有日志。
10. 不把PENDING矩阵预填为PASS，也不把private-scope接受扩写为生产/多设备保证。
11. 如果用户给出素材、日志、截图或直接路径，先读取原始来源，再参考聊天历史。
12. 如果需要提出下一阶段方案，先给出用户可感知价值、明确不做的后果、最小改动边界、测试矩阵、风险和停止条件；不要直接编码。

---

## 14. 推荐下一个AI先回复什么

在没有新的明确编码授权时，建议先向项目所有者确认以下事实，不要修改仓库：

1. 是否已经覆盖安装`1.7.0+23`并执行C1a提示矩阵及超长日志复制复测（P0）；
2. P1两个既有真机矩阵（M4-A十项、时间/GPS来源）的进展，以及是否把当前候选接受为private scope；
3. 是否提供或指定该次任务的 F19 日志用于 D1 只读诊断（读取既有记录无需授权）；仅当日志字段不足时，再另行询问是否授权日志小修；
4. C1a真机通过后，C2/C1b/C3决策与M4-B谁先排期（推荐顺序见§7.0）；
5. C3 为四选一（含"都不做"），必须等 C2 的真机证据，不得提前实现；软件 CRF 已记录为不做（§7.1），除非所有者按复议条件重开。

如果用户只说“继续”，默认从**当前候选的真机验收记录**继续，而不是重做代码或自动进入下一里程碑。

---

## 15. 常用验证命令

```bash
cd /root/hermes-project/videoslim

git status --short --branch
git rev-parse HEAD
git rev-parse origin/m4a/crop

dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test

cd android
./gradlew :app:testDebugUnitTest \
  :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease \
  --console=plain
```

这些命令只证明对应源码状态的自动化/构建结果，不证明真机安装、播放、codec、图库、SAF、通知、后台、取消或恢复。

---

**当前最安全的继续方式：只按已授权范围实施M4-B连续单段trim，保留全部未测试矩阵状态；不得并行扩张到M4-C或其他生产改动。**

---

## 16. 修订记录

- **r1（2026-07-23）**：初版交接。
- **r2（2026-07-23，按项目所有者指示由 AI 修订）**：
  1. 新增 §7.0 推荐排期；
  2. 新增 §7.1 C 轨（低码率源膨胀的对策）：D1 码率下发诊断、C1 源感知目标码率护栏、C2 编码能力探测、C3 三选一增强档（QP 钳制 / CQ 画质档 / AV1 存档档），并把"软件 x264/x265/SVT-AV1 CRF"记录为明确不做及其复议条件；
  3. §7.2 补全 M4-B 单段时间裁剪的完整计划（范围、契约、测试矩阵、风险、停止条件）；
  4. 新增 §7.3 M4-C 多段时间编辑（删中段/多段保留；跨文件拼接列为可选 M4-C2；"切成多份"划归 F10）；
  5. §7.4 M5 增加 r2 备注：F13 建议提级、F11 与 C 轨的能力区分、F10 覆盖切分场景；
  6. §8 与 §10 增加 D1 诊断备注；§10 新增第 10 条（main 分支文档滞后于规范分支的同步债）；
  7. §2.1 状态表补入 M4-C 与 C 轨，M4-B 更新为 `PLANNED — NOT AUTHORIZED`；
  8. §3.2 增加不变量 1 的适用性说明（C1/C3a/C3c 不冲突；C3b 采纳需走明示修订）；
  9. §14 更新为 r2 版确认清单；文档头部记录仓库公开状态。

  编号说明（r5订正、仓库整合时落位）：r2历史版本曾误用F16–F18；自r4起改用D1/C1a/C1b/C2/C3，正式PRD映射为F20/F21/F22，M4-C1映射为F23，跨文件拼接继续归F16。所有新增条目状态均为 `PLANNED — NOT AUTHORIZED`，实施需逐项授权；本次修订未改动任何代码。

- **r3（2026-07-23，实现与运行视角自复核修正）**：
  1. D1 嫌疑清单从 2 项扩为 4 项：新增 `experimentalSetEnableHighQualityTargeting` 覆盖请求码率、`setEnableFallback` 改写设置两条真实机制；grep 模式同步扩充；结果分支改按嫌疑编号表述；
  2. C1 改为以"文件大小 × 8 ÷ 时长"为基础的估算阶梯（不再假设 metadata 提供视频轨码率）；提示触发简化为单一条件"预计节省 < 15%（含变大）"，删去每档分辨率码率下限；新增"生效目标统一用于编码/文件名/F19"与"无裁剪需求时直说仅裁剪档仍会重编码、最不失真是暂不处理"两条；
  3. C3a 实现成本修正：`VideoEncoderSettings` 未暴露 QP 键，C3a 与 C3b 同样需要自定义 EncoderFactory；
  4. M4-B 测试矩阵剪枝（trim 与 rotation 正交，0° 全量 + 90° 抽查，180°/270° 条件补测），追加 trim × audioMode 维度与音轨边界容差；
  5. M4-C 明确不支持段乱序重排；
  6. §7.0 注明 D1 与 C2 可合并为一次诊断授权。

  r3 复核后维持不变的判断：主线顺序（先验收 → D1 → C1 → M4-B → C2 → C3 三选一 → M4-C）、C3 只做一个、软件 CRF 不做及其复议条件、M4-B/M4-C 的 ClippingConfiguration / EditedMediaItemSequence 技术路线、单次转码与全部产品不变量。

- **r4（2026-07-23，吸收所有者侧 AI（Codex）的仓库实证复核）**：
  1. 编号修正：PRD 已占用 F16（合并/变速/GIF）、F17（HDR）、F18（iOS），原 F16→C1、F17→C2、F18(a/b/c)→C3(a/b/c) 全文更名（§16 历史条目一并更名）；标注 M4-C2 与 PRD F16 的范围重叠需对齐归属；
  2. D1 收窄：代码证据（HEAD `9796b0d`，`android/` 自 `b0267a0` 无代码变化）确认 `setBitrate(...)+BITRATE_MODE_VBR` 已接线、未启用 high-quality targeting，原嫌疑 1/2 排除；`setEnableFallback(true)` 存在；既有 `LoggingEncoderFactory` 已把 configurationFormat 写入 F19——第一步改为直接读该行（零代码），并新增"fallback 配置期夹高/换编码器"独立结果分支；
  3. C1 重定位为"源感知警告 + 建议性目标上限"，不再表述为阻止/消灭膨胀；记录 `videoBitrate` 无来源标志的契约现实与 (a) 契约扩展 / (b) Dart 一致性推断两个实现选项（默认 b）；提示触发改用保守上界估算（`estimatedOutputMaxBytes`）；建议目标由用户显式采用，不静默改写；验收口径同步修正；
  4. C2 措辞修正：只查询、不改转码行为，但页面/通道是完整小型代码任务，非"零成本"；§7.0 取消 D1+C2 捆绑授权的说法；
  5. C3 决策由"三选一"改为"四选一（含都不做，维持 VBR + C1 提示）"；C3c 标注为实质独立格式功能；
  6. M4-B "帧级精度"由事实表述降级为验收目标；
  7. 头部新增"文档状态：仓库外草案"声明；§2.2 补充 r4 走读基线（HEAD `9796b0d`，工作区干净，APK 生产源码仍为 `b0267a0`）。

  r4 采纳说明：Codex 六条意见中五条全盘采纳；第 3 条（不要自动压低目标）以"建议值仅展示、由用户一键采用"的形式落实——保留上限的价值，同时消除静默改写风险。

- **r5（2026-07-23，按 Codex 二轮复核做小幅收敛，未扩写内容）**：
  1. 清理与 r4 结论冲突的旧措辞：§7.1 背景改为"提前识别并提示低收益或可能变大的任务"；§7.4 F11 对比改为"降低盲目膨胀风险，但不保证输出小于源文件"；§8 备注改为区分"fallback 配置期夹高"与"运行期过冲"；§14 改为"提供日志做只读诊断，字段不足才谈授权"；§16 r2 条目的编号说明订正；§1/§3.2/§10/原理备注的"解决/护栏/守住不膨胀"措辞同步收敛；
  2. C1 拆分为 **C1a（低收益提示，纯 Dart，先行）**与 **C1b（建议目标，后置条件项）**；**废弃 r4 的 3% 一致性推断法**——低音轨码率素材的视频轨码率天然接近容器总码率、易误判，且与"一次实现、一次修订"预算不匹配；C1b 改以显式码率来源契约（`videoBitrateSource` 或分离字段）为前提；
  3. C1b 与 D1 结果闭环：运行期过冲分支不提供更低建议值；fallback 分支建议值受编码器声明下限约束；仅在"目标基本被遵守"分支才显示"采用建议目标"；
  4. C1b 建议值增加合法范围约束（自定义 500 kbps 下限 / 滑杆 0.5–12 Mbps / preset 缩放后 800 kbps 下限，以代码为准）与 codec 组合白名单；未校准组合与低于产品下限的情形不生成建议值，显示"暂无可靠建议目标"；
  5. C1a 提示选项按 crop 有无条件显示（代码明确拒绝 `preserveQuality && crop == null`，不展示会被拒绝的选项）；
  6. C3c 删除对本机硬件的预先结论，改为以 C2 探测为准，"再降两三成"标为实验预期；
  7. 仓库整合前补充：C1a 对未知源大小（wire 值 `0`）不计算节省率；D1 区分 Media3 `configurationFormat` 与原始 `MediaFormat`；C1b 明确 `track/container/unknown` 分支、逐 codec 组合校准、最终 codec 与 100 kbps 步进；同步修正 C3a 与 §14 的旧引用。

- **r6（2026-07-23，C1a实现与候选证据）**：
  1. 项目所有者明确选择C1a作为唯一代码项；按保守输出上界与已知源大小实现预计节省低于15%的提示，废弃旧90%源码率条件；
  2. 无crop提供暂不处理/继续，有crop始终提供保持画质/继续/取消；保持画质不创建native任务；
  3. 首版`d3af1c3...`一次并行复审发现三项IMPORTANT；全部在唯一修订`7c49e57...`中处置并新增可达回归测试。按预算不二次复审，不把旧FAIL写成最终PASS；
  4. format、analyze、Flutter`227/227`、Android JVM`341/341`、lint、debug assemble与ARM64 release构建通过；
  5. `1.7.0+23`私有APK完成身份、ABI、ZIP、zipalign、v2签名、证书连续性、权限和秘密扫描；真机矩阵继续PENDING；
  6. C1b/C2/C3/M4-B/M4-C仍未授权。

- **r7（2026-07-23，验收跳过、D1结论与M4-B授权）**：
  1. 项目所有者明确跳过C1a真机验收并要求继续；矩阵保持PENDING，不写PASS；
  2. D1读取此前提供的最新相关F19任务，确认Media3有效HEVC配置仍为500 kbps，输出`videoBitrate`实际等于容器平均码率fallback；
  3. 该Pixel HEVC组合归类为运行期明显过冲，C1b不得提供更低建议目标；
  4. M4-B/F8连续单段时间裁剪获得明确开工授权，成为唯一代码项；M4-C、多段、跨文件、C1b/C2/C3仍未授权。

- **r8（2026-07-23，M4-B实现与冻结前门禁）**：
  1. Dart与Kotlin继续使用预留的`trimStartMs/trimEndMs`，严格解释为一个半开连续区间；成对整数、最短1秒、来源时长边界与`INVALID_TRIM`均有RED→GREEN覆盖；
  2. S3/S4支持保存、编辑、移除和恢复一个区间；不足1秒的来源禁用入口；预览180ms节流且用户再次拖动会立即使旧响应失效；
  3. planner、C1a保守上界和Android存储预检按保留时长计算；snapshot、普通retry与兼容Decoder retry精确保留端点；
  4. Media3在输入`MediaItem`应用`ClippingConfiguration`且不主动吸附关键帧，继续复用crop、Presentation、codec、音频、单服务、单任务和`finishOnce`路径；
  5. 冻结前门禁通过：Dart format、Flutter analyze 0 issues、Flutter`241/241`、Android JVM`346/346`、debug/release lint和debug assemble；这些证据不等于APK或真机PASS；
  6. 目标版本为`1.8.0+24`；只有冻结提交的唯一复审和APK静态核验完成后才可晋级内部候选，多段/M4-C与C1b/C2/C3仍未授权。
