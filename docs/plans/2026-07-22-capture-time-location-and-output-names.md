# VideoSlim 拍摄时间、位置与输出文件名实施计划

> **日期：** 2026-07-22
> **授权：** 项目所有者已批准“先写规划，再开始实施”
> **基线：** `c8c9e070ead6`（`m4a/crop`，已交付裁剪修复候选 `1.5.0+20`）
> **状态：** IMPLEMENTED — AUTOMATED GATES PASS; DEVICE ACCEPTANCE PENDING
> **规模评级：** 中等偏小；若需要第二次 remux、专用 MOV/MP4 writer 或 Media3 升级，立即停止并重新评级

## 1. 目标

在不改变现有单前台服务、单任务、`finishOnce`、publication/recovery、统一 MP4 输出和硬件 VBR 策略的前提下：

1. 压缩视频时，若来源中存在且可可靠解析，则在最终 MP4 中保留原始拍摄/容器创建时间。
2. 压缩视频时，若来源中存在且可可靠解析，则在最终 MP4 中保留原始 GPS 位置。
3. 默认发布到 MediaStore 时，同步设置视频行的 `DATE_TAKEN`，使系统相册更可能按原始时间排序。
4. 增强压缩视频和音频提取文件名，使名称反映最终计划并具备防冲突后缀。
5. 在公开输出分配前验证临时 MP4 中承诺保留的字段；验证失败时不发布半成品。

## 2. 产品语义

### 2.1 固定行为

- 不提供隐私模式。
- 不提供“保留/清除”选择控件。
- 不新增 `MetadataMode`、Flutter 设置项或 MethodChannel 模式字段。
- 来源时间存在且能可靠解析时保留；来源位置存在且能可靠解析时保留。
- 来源字段缺失时保持缺失，不使用当前时间、输出时间、文件 mtime、文件名、目录或设备当前位置补齐。
- 来源字段存在但当前解析链无法可靠解释时，不伪造、不阻断视频编码；仅不承诺该字段已保留。
- 已解析或由 Media3 可靠提取出的字段若在最终临时 MP4 中缺失或不匹配，则阻止 publication，并返回明确的元数据保留失败。
- 不请求当前定位权限，不读取设备当前位置，不反向地理编码。
- 日志只能记录 `captureTimePresent=true/false`、`locationPresent=true/false` 等布尔状态，不得记录精确时间值或经纬度。

### 2.2 “拍摄时间”的定义

本功能中的拍摄时间是来源媒体内部或系统媒体索引中可解释为来源创建/拍摄时刻的时间。优先级为：

1. `MediaMetadataRetriever.METADATA_KEY_DATE` 中带明确 UTC/offset 的日期；
2. 来源 MediaStore 行的正数 `DATE_TAKEN`；
3. Media3 从输入 MOV/MP4 解析出的有效 `Mp4TimestampData.creationTimestampSeconds`。

约束：

- 只接受带明确时区/UTC 的字符串；不把无时区本地时间按设备时区猜测。
- 规范化为 Unix epoch milliseconds；写入 MP4 时使用 `Mp4TimestampData.unixTimeToMp4TimeSeconds()`。
- MP4 v0 时间为秒级，输出验证按秒比较，不要求恢复来源毫秒尾数。
- 零值、1904 epoch sentinel、越过 Media3 1.10.1 可写范围的值不视为可靠拍摄时间。
- 该语义不等同于输出文件系统 mtime，也不等同于压缩开始/完成时间。

### 2.3 “位置”的定义

- 来源读取使用 `MediaMetadataRetriever.METADATA_KEY_LOCATION` 的 ISO 6709 字符串，或 Media3 解析出的 `Mp4LocationData`。
- 纬度必须位于 `[-90, 90]`，经度必须位于 `[-180, 180]`，且两者均为有限数值。
- 支持 ISO 6709 的可选海拔段，但本次只映射纬度和经度；不伪造海拔。
- 写入使用 `Mp4LocationData(latitude, longitude)`。
- 输出验证使用 `0.0001°` 容差，避免 Float 和文本小数位差异造成误报。

## 3. 范围锁定

### 3.1 本次实施

- 压缩视频来源时间/GPS读取和纯解析器。
- Media3 1.10.1 muxer metadata provider。
- 最终临时 MP4 的时间/GPS验证。
- MediaStore 视频 `DATE_TAKEN` 写入。
- 视频和音频输出文件名增强。
- 纯 JVM/Dart 自动化测试、完整现有门禁、新候选 APK。
- iPhone/Pixel 原始样本验收清单；没有真实素材证据的行保持 `PENDING`。

### 3.2 明确不实施

- 隐私模式、元数据模式 UI、模式协议或模式持久化。
- 完整复制 QuickTime、XMP、mdta、Apple 私有 atom、Pixel `mett`、设备型号、软件版本、标题、caption 或辅助数据轨道。
- 自动保存到来源目录。
- MOV 输出；所有压缩视频仍统一输出 MP4。
- 音频 M4A 继承视频拍摄时间或 GPS。
- 第二次有损编码、第二次容器 remux、FFmpeg、专用 MOV/MP4 writer。
- Media3 升级；继续固定 `1.10.1`。
- M4-B/F8 时间裁剪。
- 裁剪编辑器、裁剪手势、`Crop → Presentation` 顺序的任何修改。
- hardening、refactor、migration 或并发模型调整。

## 4. 已验证的 Media3 API 边界

对项目实际缓存的 Media3 `1.10.1` AAR/JAR 进行 `javap` 核查后确认：

- 当前 `DefaultMuxer.Factory` 内部已经委托 `InAppMp4Muxer.Factory`，并非切换到新的 muxer 家族。
- `InAppMp4Muxer.Factory` 提供：
  - 无参构造；
  - `Factory(InAppMp4Muxer.MetadataProvider)`；
  - `getSupportedSampleMimeTypes(trackType)`。
- `MetadataProvider.updateMetadataEntries(Set<Metadata.Entry>)` 接收可修改 Set，可删除来源条目并新增受支持条目。
- `MuxerUtil.isMetadataSupported()` 明确支持 `Mp4OrientationData`、`Mp4LocationData`、有效 `Mp4TimestampData`、部分 mdta 和 XMP。
- `Mp4TimestampData.unixTimeToMp4TimeSeconds(long)` 的输入是 Unix milliseconds。
- `Transformer.Builder.setMuxerFactory(...)` 可在现有单次 Transformer 输出中接入该 provider。

因此计划采用“显式使用同一 `InAppMp4Muxer` + provider”，不增加额外媒体处理阶段。音频 copy 预检也改用同一 factory 类型，避免预检与实际输出能力漂移。

## 5. 设计

### 5.1 内部模型

新增仅限 Android 原生层的模型：

```text
SourceCaptureMetadata
  captureTimeEpochMs: Long?
  location: CaptureLocation?

CaptureLocation
  latitude: Double
  longitude: Double
```

要求：

- 模型不可进入 Flutter wire、任务通知或用户可见任务快照。
- `toString()` 只显示字段是否存在，不显示值。
- 每次任务在来源 metadata 读取阶段建立初始值；muxer provider 可从 Media3 输入条目补全缺失字段。
- retry/recovery 仍从同一持久 URI 重读来源，不修改现有 snapshot schema。来源授权丢失时沿用现有 source-access 失败路径。

### 5.2 来源读取

扩展 `VideoMetadataReader`：

1. 读取 retriever date/location 原始字符串。
2. 由纯 `CaptureMetadataParser` 做日期与 ISO 6709 解析。
3. 单独、best-effort 查询 `MediaStore.Video.VideoColumns.DATE_TAKEN`；第三方 DocumentsProvider 不支持该列时返回 null，不影响媒体读取。
4. 将安全模型挂在原生 `VideoMetadata` 上，但不加入 `toChannelMap()`。

解析失败不得在日志打印原字符串，因为位置字符串可能包含精确坐标。

### 5.3 Muxer metadata 策略

新增 `CaptureMetadataPolicy : InAppMp4Muxer.MetadataProvider`：

1. 接收来源读取阶段得到的初始时间和位置。
2. provider 调用时，从输入 metadata Set 中选择有效的 `Mp4TimestampData` / `Mp4LocationData` 作为缺失字段 fallback。
3. 清空可传播的来源 metadata Set，避免无意承诺保留任意 mdta/XMP/厂商字段。
4. 只添加规范化后的 `Mp4TimestampData` 和 `Mp4LocationData`。
5. 保存实际选定的不可变 `resolvedMetadata`，供 Transformer 完成后的验证和 MediaStore publication 使用。

输出方向不从来源 metadata 盲目复制。`InAppMp4Muxer` 仍由最终视频 track `Format.rotationDegrees` 写入输出方向；当前裁剪和显示方向逻辑保持原样。

### 5.4 输出验证

Transformer 成功回调后、分配任何公开 URI 前，在现有 media I/O executor 上：

1. 用 `MediaMetadataRetriever` 读取临时 MP4。
2. 用同一纯解析器得到实际时间和位置。
3. 对 `resolvedMetadata` 中存在的字段逐项验证：
   - 时间按 epoch seconds 相等；
   - 位置按每轴 `0.0001°` 容差。
4. 若期望字段缺失或不匹配，抛出 `CAPTURE_METADATA_FAILED`，删除临时文件并清理 recovery；不得创建 MediaStore/SAF 可见输出。
5. 若来源没有可靠字段，则不要求输出出现该字段。

此验证发生在现有 publication boundary 内，不新增服务、任务或第二阶段公共 API。

### 5.5 MediaStore 与 SAF

- 默认 MediaStore 路径：`publishVideo()` 接收可空 `dateTakenEpochMs`；视频且值有效时，在 scoped/legacy insert `ContentValues` 中写入 `MediaStore.Video.VideoColumns.DATE_TAKEN`。
- 自定义 SAF Tree：只能保证 MP4 文件内部 metadata；不承诺 DocumentProvider 或系统相册立即创建/更新 MediaStore 行。
- 音频 publication 不设置 `DATE_TAKEN`。
- `IS_PENDING`、长度验证、observer、删除、取消和 recovery 顺序保持不变。

### 5.6 文件名

新增共享纯 Dart builder，统一清理、UTF-8 240-byte 上限、时间戳和 4 位随机十六进制 token。token 可注入以便测试。

视频：

```text
<stem>_slim_<finalCodec>_target<kbps>k_<yyyyMMdd_HHmmssSSS>_<token>.mp4
```

示例：

```text
旅行_slim_h265_target2500k_20260722_103015123_a7f3.mp4
```

规则：

- `finalCodec` 使用 planner 已完成能力 fallback 后的实际计划：`h265` 或 `h264`。
- 码率写 `target`，不暗示硬件 VBR 实测平均码率。

音频 copy：

```text
<stem>_audio_copy_<yyyyMMdd_HHmmssSSS>_<token>.m4a
```

音频 AAC 重编码：

```text
<stem>_audio_aac_target<kbps>k_<yyyyMMdd_HHmmssSSS>_<token>.m4a
```

随机 token 只降低冲突概率；MediaStore/DocumentProvider/legacy publication 仍负责最终名称分配，绝不覆盖已有文件。

## 6. 错误与日志策略

新增稳定错误码：

```text
CAPTURE_METADATA_FAILED
```

用户文案：

```text
无法确认原拍摄时间或位置已保留，未保存不完整结果。
```

- 该错误发生于临时 MP4 验证失败；原视频不修改，公共输出不创建。
- 可允许用户按同一请求重试，但不自动无限重试。
- 日志记录失败字段类型和 present/match 布尔值，不记录时间值、坐标值或原始 metadata 字符串。

## 7. 实施任务

### Task 1：纯解析器、模型与 muxer 策略

**Files**

- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/CaptureMetadata.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/VideoMetadataReader.kt`
- Create: `android/app/src/test/kotlin/com/videoslim/videoslim/CaptureMetadataParserTest.kt`
- Create: `android/app/src/test/kotlin/com/videoslim/videoslim/CaptureMetadataPolicyTest.kt`
- Modify: `android/app/src/test/kotlin/com/videoslim/videoslim/VideoMetadataTest.kt`

**Steps**

1. 先写失败测试：带 Z/offset 日期、紧凑日期、无时区拒绝、非法日期、ISO 6709 正负坐标、海拔、范围、NaN/无穷、缺失字段。
2. 写失败测试：初始值优先、Media3 fallback、无效 MP4 epoch 拒绝、Set 最终只含时间/位置、`toString()` 不泄露值。
3. 实现最小纯模型和解析器。
4. 扩展 reader，并保证 Dart channel map 不变化。
5. 运行定向 JVM tests。

### Task 2：单次 muxer 注入、输出验证与错误路径

**Files**

- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessModels.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessingNotification.kt`
- Create: `android/app/src/test/kotlin/com/videoslim/videoslim/CaptureMetadataVerificationTest.kt`
- Modify/Create: 原生 source-policy tests
- Modify: `lib/screens/home_screen.dart`（仅错误码映射）
- Modify/Create: 对应 Dart错误文案测试

**Steps**

1. 先写失败测试：秒级时间比较、位置容差、期望字段缺失/不匹配、无期望字段通过。
2. ActiveTask 持有 policy；Transformer 显式使用 `InAppMp4Muxer.Factory(policy)`。
3. audio copy 预检使用 `InAppMp4Muxer.Factory()`。
4. Transformer 完成后先验证临时 MP4，再进入现有 publication。
5. 添加 `CAPTURE_METADATA_FAILED` 映射和不含敏感值的诊断。
6. 运行定向 JVM/Flutter tests和 Kotlin 编译。

### Task 3：MediaStore DATE_TAKEN

**Files**

- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/MediaStoreSaver.kt`
- Modify/Create: publication policy/source guard tests

**Steps**

1. 先写失败测试，约束 video-only、scoped/legacy 写入、audio/SAF 不承诺索引时间。
2. `publishVideo()` 增加可空时间参数并传入 `publishMedia()`。
3. 只在视频 MediaStore insert 写 `DATE_TAKEN`；其余 publication/recovery 顺序不变。
4. TranscodeEngine publication 使用 provider 的 resolved time。
5. 运行定向 publication tests。

### Task 4：输出文件名增强

**Files**

- Create: `lib/logic/output_file_name_builder.dart`
- Modify: `lib/screens/home_screen.dart`
- Modify: `lib/logic/audio_extract_planner.dart`
- Create: `test/logic/output_file_name_builder_test.dart`
- Modify: `test/logic/audio_extract_planner_test.dart`
- Modify: `test/widget_test.dart`

**Steps**

1. 先写失败测试：最终 codec fallback、目标码率、copy/AAC、毫秒、token、非法字符、路径、Unicode UTF-8 长度。
2. 视频使用已解析的 `CompressionPlan.videoCodec/videoBitrate`。
3. 音频 planner 使用最终 `AudioExtractSettings`。
4. 随机 token 通过 injectable callback 保持测试确定性。
5. 运行 formatter 和定向 Dart tests。

### Task 5：全量验证、候选和真实素材验收

**Files**

- Modify: `docs/VideoSlim PRD.md`
- Modify: `docs/current-project-status.md`
- Create: `docs/capture-metadata-device-acceptance.md`
- Modify: `pubspec.yaml`（冻结候选时计划递增为 `1.6.0+21`）
- Create: `/root/artifacts/videoslim/capture-metadata/` APK与验证清单

**Steps**

1. 运行 `dart format`、`flutter analyze`、全量 Flutter tests。
2. 运行 Android JVM tests、debug/release lint、debug/release assemble。
3. 运行 `git diff --check`，核查 Media3 仍为 1.10.1、SDK/ABI/权限未漂移。
4. 只进行一次普通 diff review；按项目规则最多进行一次修订。
5. 冻结候选并提交；再对 exact SHA 进行一次双路只读复审，不把 timeout/无 verdict 算 PASS。
6. 构建 arm64 Release APK，核验 package/version/SDK/ABI、zipalign、v2 签名、权限和 SHA-256。
7. 使用原始未加工样本执行输入/输出对照；没有样本或没有真机证据的行保持 `PENDING`。

## 8. 自动化测试矩阵

### 8.1 时间

- `20260722T103015.123Z`
- `20260722T103015Z`
- `2026-07-22T10:30:15.123+08:00`
- 不带时区字符串拒绝
- 非法月/日/秒拒绝
- MediaStore 正数 fallback
- MP4 1904/零 sentinel 拒绝
- Unix ↔ MP4 秒转换
- 输出秒级相等、毫秒尾数允许丢失

### 8.2 位置

- 北/东、北/西、南/东、南/西
- 赤道和本初子午线
- 边界 `±90`、`±180`
- 可选海拔和结尾 `/`
- 越界、缺少第二符号、NaN/Infinity拒绝
- Float 写入后的 `0.0001°` 比较容差

### 8.3 Metadata provider

- reader 初始值覆盖来源重复条目
- reader 缺失时采用有效 Media3条目
- 最终 Set 只含规范化时间和位置
- 两项均缺失时不新增
- 不保留 mdta/XMP/厂商字段
- 日志/toString 不包含精确值

### 8.4 Publication

- 有期望字段且验证成功才调用 `publishVideo`
- 有期望字段且验证失败时零公开输出
- scoped/legacy video 写 `DATE_TAKEN`
- SAF只复制嵌入 metadata
- audio 不写 video `DATE_TAKEN`
- 取消、清理、observer、recovery顺序不回归

### 8.5 文件名

- HEVC/H.264 fallback 名称正确
- 目标码率标记为 `target`
- audio copy/AAC名称正确
- 毫秒和4位token
- 无覆盖、provider碰撞仍由publication处理
- Unicode和非法字符，完整名称不超过240 UTF-8 bytes

### 8.6 回归

- 裁剪效果仍为 `Crop → Presentation`
- 0/90/180/270°方向不二次旋转
- HDR/SDR、HEVC/H.264、AAC copy/re-encode/remove
- MediaStore/SAF、取消、恢复、后台服务
- 已验收裁剪交互文件不修改

## 9. 真实样本验收矩阵

至少需要以下未加工原始素材；素材不存在则该行不适用，不能伪造 PASS：

1. iPhone 普通 SDR MOV：有时间、有位置。
2. iPhone 普通 SDR MOV：有时间、无位置。
3. iPhone HDR MOV（用户实际使用时）。
4. Pixel 10 普通 MP4：有时间、有位置。
5. Pixel 10 普通 MP4：有时间、无位置。
6. Pixel 10 10-bit HDR/AV1（用户实际使用时）。

每个样本记录：

- 输入 `exiftool -G1 -a -s` 和 `ffprobe -show_format -show_streams` 基线；
- 输出同样检查；
- Android retriever结果只记录字段存在/匹配，不记录公开文档中的精确坐标；
- 默认 MediaStore 相册排序；
- 自定义 SAF 输出的内部 metadata；
- 播放、方向、裁剪、音频和HDR结果。

## 10. 修改规模与停止条件

当前保持“中等偏小”评级，因为：

- 不增加 UI、wire模式、snapshot schema或第二服务；
- 当前默认 muxer 与计划显式 muxer本质上都是 Media3 1.10.1 `InAppMp4Muxer`；
- 核心变化集中在来源解析、provider、验证、MediaStore字段和文件名。

遇到以下任一情况立即停止实施并报告，不擅自扩大范围：

1. `InAppMp4Muxer.MetadataProvider` 无法在真实 Transformer 输出中新增时间或位置。
2. 必须进行第二次 remux、修改MP4 atom、引入FFmpeg或专用容器writer。
3. 必须升级 Media3。
4. 元数据路径导致方向、HDR、音频copy或硬件编码回归。
5. 必须修改现有service/task/finishOnce/publication/recovery架构。

上述情况会把任务升级为中等偏大或大型，必须重新获得项目所有者批准。

## 11. 回滚边界

- 规划文档独立提交，先于任何生产代码。
- 原生 metadata链、MediaStore日期和Dart文件名分层提交，便于单独回滚。
- 旧 `1.5.0+20` APK、SHA和M4-A验收记录保持不变，不覆盖、不重新标记。
- 新候选使用独立 artifact目录和版本；真机验收失败时回退到旧已验证候选。
- 不以删除或修改源视频作为任何回滚手段。

## 12. 实施结果（2026-07-23）

- Android 原生层已实现来源时间/GPS解析、Media3 `InAppMp4Muxer.MetadataProvider` 白名单注入、最终临时 MP4 发布前核验、`CAPTURE_METADATA_FAILED` 和视频 MediaStore `DATE_TAKEN`。
- Dart 层已实现共享输出名 builder；视频名称使用最终 codec/目标码率，音频区分 copy 与 AAC 目标码率，并包含毫秒处理时间和四位随机十六进制 token。
- 没有新增 UI 模式、wire schema、第二服务、第二次转码/remux、Media3 升级或裁剪交互改动。
- `flutter analyze`：0 issues；Flutter tests：`219/219`；Android JVM tests：`337/337`，0 failures/errors/skipped。
- Android debug/release lint 与 debug/release assemble：PASS；候选版本递增为 `1.6.0+21`。
- 构建机没有连接 Android 设备，也没有项目所有者的未加工 iPhone/Pixel样本；最终 MP4 atom、MediaStore相册排序和 SAF 行为仍须按 `docs/capture-metadata-device-acceptance.md` 真机验证，所有相关行保持 `PENDING`。
- exact-SHA 复审、最终 APK 身份和哈希由候选完成报告记录，不在规划文档中预填。
