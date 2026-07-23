# VideoSlim 项目规划与当前进度交接（给其他 AI）

> **用途：** 让新的 AI 在不依赖聊天记录的情况下，理解产品边界、已完成能力、当前候选、未完成验收和后续路线。
>
> **生成日期：** 2026-07-23
> **仓库：** `https://github.com/xbaiapp/VideoSlim`
> **规范分支：** `m4a/crop`
> **当前可执行候选源码：** `b0267a0b959ccb46785daa1c91d0be96b5a0ef98`
> **当前候选版本：** `1.6.1+22`
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

**当前没有正在实施的新功能。** 下一步不是继续改代码，而是安装 `1.6.1+22` 后完成真机验收：

1. 复测约1 MiB日志复制不再触发Android Binder异常；
2. 补齐M4-A裁剪真机矩阵；
3. 补齐真实iPhone/Pixel来源的时间/GPS、MediaStore `DATE_TAKEN`、图库排序和SAF矩阵；
4. 由项目所有者决定接受、拒绝或授权一个明确的小修。

M4-B时间裁剪、M5打磨、hardening、refactor、migration均**没有自动开工权限**。

---

## 2. 已验证基线与候选身份

### 2.1 接受状态与候选状态不是同一概念

| 对象 | 状态 | 含义 |
|---|---|---|
| M3音频提取 `1.4.3+18` | `ACCEPTED — private scope` | 项目所有者已在自用范围接受；不代表多设备或生产发布 |
| M4-A画面裁剪 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 实现、自动化和APK静态检查完成；完整真机矩阵未完成 |
| 拍摄时间/GPS与输出命名 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 一条Pixel设备任务成功；真实来源/图库/SAF矩阵未完成 |
| 超长日志复制修复 | `CANDIDATE READY — DEVICE RETEST PENDING` | 自动化、实际日志smoke和exact-SHA复审通过；尚待手机复测 |
| M4-B时间裁剪 | `NOT STARTED — NOT AUTHORIZED` | 不得规划为已开工或顺带实现 |
| M5/M6 | `NOT STARTED` | 仅为路线图，不是当前任务 |

### 2.2 当前私有验收APK

```text
文件：VideoSlim-1.6.1+22-b0267a0-arm64-v8a-release.apk
package：com.videoslim.videoslim
versionName/versionCode：1.6.1 / 22
源码：b0267a0b959ccb46785daa1c91d0be96b5a0ef98
源码 tree：aa218a23dac1bbf0f69eb5dc2ff6e633eedd1ceb
ABI：arm64-v8a only
minSdk/targetSdk/compileSdk：26 / 36 / 36
大小：18,378,659 bytes
SHA-256：21ac3df44e8afa116cc9bb7c5f8ca7db94bacc45830f2dd373e4b9d4b0570409
签名：Android Debug certificate，APK Signature Scheme v2
```

该APK只用于项目所有者私有真机验收，不是production-signed商店包。旧 `1.6.0+21` 因日志复制缺陷已被隔离，不应再作为当前包分发。

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
- `trimStartMs/trimEndMs` 已预留但非空值仍被拒绝；M4-B没有实现。
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

---

## 6. 当前任务进度与下一步

### 6.1 已完成并关闭

| 任务 | 结果 |
|---|---|
| 复现日志Binder超限 | RED测试已证明旧实现会把整份日志交给剪贴板 |
| 实现最近128 KiB复制 | 已完成；小日志不变、超长尾部、UTF-8/行边界、准确提示均有测试 |
| 构建 `1.6.1+22` | 已完成并静态核验 |
| exact-SHA focused review | PASS，无BLOCKER/IMPORTANT finding |
| 文档和远端同步 | `m4a/crop`已同步；当前工作树在生成本文前为clean |

### 6.2 当前唯一优先队列：真机验收

#### P0：安装并复测当前APK

1. 使用相同签名覆盖安装 `1.6.1+22`，不要先卸载，以保留应用数据和旧F19日志；
2. 打开F19调试日志并执行复制；
3. 预期不再出现Binder异常，提示只复制最近部分；
4. 粘贴确认包含最新任务尾部；
5. 使用“分享日志”确认导出的仍是完整文件。

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

## 7. 后续路线图（尚未授权实施）

### M4-B：时间裁剪 F8

- 状态：`NOT STARTED — NOT AUTHORIZED`；
- 规划方向：双滑块起止时间 + Media3 `ClippingConfiguration`；
- 必须与压缩/画面裁剪走同一次管线；
- 只有项目所有者另行明确批准后才能制定实施计划或编码。

### M5：自用版打磨

PRD中的候选范围：

- F9历史记录与累计节省统计；
- F10批量顺序队列；
- F11目标大小压缩（这是独立于VBR自定义码率的产品能力）；
- F13无画面编辑时的无损去音轨/转封装路径（当前压缩任务中的 `audioMode=remove` 已存在，但仍会随视频压缩重编码）；
- F14旋转/翻转；
- F12帧率、F15 MP3先做Media3能力和授权评估，再决定实现或删除；
- UI空态/异常态和连续两周自用稳定性。

M5不是当前任务，不得因“顺手”而提前加数据库、队列或新生命周期框架。

### M6：上架/iOS

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

体积事实：输入 `279,277,518` bytes，输出 `754,489,009` bytes；请求500 kbps，实测视频约 `2,307,117` bps。输出约为输入的2.70倍，因此该素材没有达到“瘦身”效果，但按项目所有者已确认的硬件VBR政策，这不是改CBR或增加严格体积拒绝的授权。应记录并由用户评估画质/用途，不得私自改变编码策略。

这条日志只能证明App内“时间存在、位置缺失”核验与发布成功；不能证明原文件确为Pixel相机未加工文件，也不能证明外部atom、MediaStore `DATE_TAKEN`、图库排序或SAF行为。

---

## 9. 自动化、复审和CI证据

当前候选 `b0267a0...`：

```text
Dart format                         PASS
Flutter analyze                     PASS，0 issues
Flutter tests                       224/224 PASS
Android JVM tests                   341/341 PASS
Android debug/release lint          PASS
Android debug/release assemble      PASS
ARM64 Flutter release build         PASS
APK ZIP/zipalign/v2 signature       PASS
package/version/ABI/permission scan PASS
common credential-pattern scan      PASS
metadata corrective review          PASS
clipboard exact-SHA focused review PASS，无BLOCKER/IMPORTANT
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
9. 硬件VBR可能明显偏离目标码率，甚至产出比源文件更大的结果；项目所有者接受保留VBR，不接受以牺牲观看质量换取CBR或严格体积命中。

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
5. `docs/VideoSlim PRD.md` — 产品和架构权威contract
6. `docs/capture-metadata-completion-report.md`
7. `docs/capture-metadata-device-acceptance.md`
8. `docs/m4-a-completion-report.md`
9. `docs/m4-device-acceptance.md`
10. `docs/m3-completion-report.md`
11. `docs/known-debt.md`
12. `AGENTS.md` — 审批、复审预算和blocker规则

旧完成报告中的历史SHA只证明对应历史候选；当前APK和生产行为以 `b0267a0...` 为准。文档提交可能晚于该源码提交，但不得把docs-only HEAD当作APK构建源码。

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

1. 是否已经覆盖安装并复测 `1.6.1+22` 的超长日志复制；
2. 这次要继续的是M4-A裁剪验收、metadata/图库/SAF验收，还是只需要分析现象；
3. 用户是否拥有对应的iPhone/Pixel未加工原始素材；
4. 是否要把当前候选接受为private scope；
5. 若要进入M4-B或M5，先让项目所有者明确批准范围与优先级。

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

**当前最安全的继续方式：安装现有APK并完成清单；没有新的可复现缺陷前，不再改生产代码。**
