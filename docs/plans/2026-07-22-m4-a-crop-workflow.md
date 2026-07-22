# VideoSlim M4-A 画面裁剪实施计划

> **日期：** 2026-07-22
> **授权：** 项目所有者已批准按 `docs/VideoSlim PRD.md` v1.9 的 F5 与 M4-A 执行
> **基线：** `19abfb7`（`1.4.3+18`，M3 `ACCEPTED — private scope`）
> **状态：** IMPLEMENTATION AUTHORIZED

## 目标

在不改变现有单任务、单前台服务、`finishOnce`、发布/恢复边界和硬件 VBR 策略的前提下，实现可选的画面裁剪工作流：用户可从压缩配置页进入裁剪，也可从首页“裁剪画面”入口先裁剪；裁剪、缩放和编码在同一次 Media3 Transformer 任务中按 `Crop → Presentation` 完成。

## 范围锁定

本计划只实施：

- PRD F5 画面裁剪；
- M4-A 双入口、S3/S4 工作流；
- “保持画质（仅裁剪）”档；
- `getPreviewFrame` 只读平台方法；
- `INVALID_CROP`、裁剪几何、恢复 round-trip、F19 裁剪诊断；
- M4-A 自动化验证、候选 APK 和待项目所有者执行的真机验收矩阵。

明确不实施：

- M4-B / F8 时间裁剪；
- 旋转或翻转 UI；
- 中间裁剪视频或两次转码；
- hardening、refactor、migration；
- 新并发模型、第二套服务或任务生命周期；
- M5/M6 功能。

## 架构

- **Flutter 状态：** 在现有 `HomeFlowState` typed workflow 中增加 `editingCrop` 交互阶段和可空 `CropRect`；不引入新状态框架。
- **Dart 几何：** 使用显示方向像素作为唯一业务坐标。纯逻辑负责预览控件与视频像素换算、移动/缩放、比例锁、边界和 64×64 最小尺寸、偶数化预检。
- **输出规划：** `CompressionPlanner` 先把源尺寸替换为裁剪后尺寸，再执行现有像素码率换算和 long-edge 缩放；“保持画质”按 PRD §3.1 公式计算。
- **Wire：** `process` 顶层结构不变，开始填充现有 `video.crop`；新增 `getPreviewFrame({uri,timeMs}) -> Uint8List`。档位名称不进入 wire。
- **Android：** 严格解析显示方向 `CropRect`；`CropGeometryMapper` 映射到 Media3 `Crop(left,right,bottom,top)` NDC 参数并计算偶数输出尺寸；`TranscodePlan` 以裁后尺寸规划 Presentation 和编码器能力。
- **预览帧：** `MediaMetadataRetriever` 在媒体 I/O dispatcher 上读取最近同步帧，按显示方向返回 JPEG，长边不超过 1280；不写任务状态。
- **Transformer：** 视频效果列表严格为裁剪在前、缩放在后；无裁剪时保持 1.4.3 路径。
- **诊断：** Dart F19 记录所选档位和显示像素矩形；原生 F19 记录显示矩形、NDC、旋转元数据与最终输出尺寸。

## 验收标准

1. 首页裁剪入口启用；完成 S4 后返回同一 S3，并默认选择“保持画质”。
2. 压缩配置页可新增、编辑、移除裁剪；移除时若正在使用“保持画质”，回退“均衡”。
3. S4 默认自由比例，支持自由、16:9、9:16、1:1、4:3；具有八个缩放手柄、整体移动、帧滑杆、实时像素标签。
4. 裁剪框永不越界，显示方向最小 64×64，提交给 wire 的宽高为偶数。
5. 预设码率、输出分辨率和大小预估均按裁剪后再缩放的最终像素计算。
6. “保持画质”只在有裁剪时显示；HEVC 不可用时沿用现有 H.264 fallback 且码率 ×1.5。
7. Android 对越界、零/负面积、偶数化后过小的裁剪 fail closed 为 `INVALID_CROP`，不得静默夹取。
8. Media3 effects 顺序为 `Crop → Presentation`；无 crop 的 plan/effects 路径保持原行为。
9. snapshot/retryRequest 原样保留 crop；trim 仍为 null 且非 null 继续拒绝。
10. 自动化门禁通过并生成可安装的 arm64 Release 候选；旋转/画面对齐、codec、后台、锁屏、取消和恢复只在真实设备完成后才可标记 PASS。

---

## Task 1：Dart 裁剪契约、几何和规划器

**Files**

- Modify: `lib/models/process_request.dart`
- Modify: `lib/models/compression_settings.dart`
- Create: `lib/logic/crop_geometry.dart`
- Modify: `lib/logic/compression_planner.dart`
- Create: `test/logic/crop_geometry_test.dart`
- Modify: `test/logic/compression_planner_test.dart`
- Modify: `test/models/process_request_test.dart`

**Steps**

1. 先新增失败测试，覆盖 crop map round-trip、坐标换算、八向缩放、比例锁、越界夹取、最小尺寸和偶数化。
2. 新增 `preserveQuality` 档；只允许 planner 在 crop 非空时生成。
3. planner 以 crop 尺寸作为缩放与像素换算输入，实现源码率公式、clamp 和源码率缺失 fallback。
4. `CompressionPlan.toProcessRequest()` 自动携带 crop。
5. 运行定向 Dart 测试与 formatter。

## Task 2：Android 契约、预览与原生裁剪管线

**Files**

- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessModels.kt`
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/CropGeometryMapper.kt`
- Create: `android/app/src/main/kotlin/com/videoslim/videoslim/PreviewFrameReader.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/EngineChannel.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodePlan.kt`
- Modify: `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`
- Modify/Create: 对应 `android/app/src/test/...` JVM tests

**Steps**

1. 先新增失败测试：严格 crop parse、`INVALID_CROP`、四种旋转元数据、NDC y 轴、偶数化、裁后 long-edge 和效果顺序策略。
2. `ProcessRequest` 接受可空 crop、继续拒绝非空 trim，并由 `toChannelMap()` 保证恢复 round-trip。
3. mapper 校验显示方向边界并生成 Media3 constructor 顺序 `(left,right,bottom,top)`。
4. `getPreviewFrame` 严格接收 `{uri,timeMs}`，在 media I/O dispatcher 上运行，返回显示方向 JPEG；失败归一化为可读引擎错误。
5. plan 先裁后缩；Transformer 按顺序装配 `Crop` 和 `Presentation`。
6. F19 写入 crop 矩形、NDC、rotation 和最终宽高；不改变 publication/recovery/service。
7. 运行定向 JVM tests、Kotlin 编译和 lint。

## Task 3：S3/S4 双入口工作流

**Files**

- Modify: `lib/engine/video_engine.dart`
- Modify: `lib/engine/method_channel_video_engine.dart`
- Modify: `lib/state/home_flow_state.dart`
- Modify: `lib/screens/home_screen.dart`
- Modify: `lib/widgets/m2_compression_card.dart`
- Create: `lib/widgets/crop_editor.dart`
- Modify/Create: 对应 engine/state/widget tests

**Steps**

1. 先新增失败测试：`getPreviewFrame` 契约、typed `editingCrop` 互斥、双入口、徽章、编辑/移除、保持画质显隐和回退。
2. 为 `VideoEngine` 增加 typed `Future<Uint8List> getPreviewFrame(...)`。
3. 裁剪入口导入成功后进入 S4；确认后回 S3 并默认保持画质。S3 内新增裁剪保持当前档位。
4. 实现预览帧加载与节流、防止旧请求覆盖新帧、自由/比例锁、八手柄和实时像素尺寸。
5. 任务开始前在 F19 记录档位与裁剪摘要；`ProcessRequest` 仍只发送具体参数。
6. 对 `INVALID_CROP` 展示“裁剪区域无效，请重新框选”。
7. 运行定向 widget/state/engine tests。

## Task 4：范围文档、全量验证与候选冻结

**Files**

- Modify: `AGENTS.md`（只同步 M4-A 已授权 / M4-B 未授权）
- Modify: `docs/current-project-status.md`
- Create: `docs/m4-device-acceptance.md`
- Create: `docs/m4-a-completion-report.md`
- Modify: `pubspec.yaml`（候选冻结时递增为 `1.5.0+19`）
- Create/Update: `/root/artifacts/videoslim/m4-a/` 验收 APK 与校验清单

**Steps**

1. 运行 `dart format`、`flutter analyze`、全量 Flutter tests。
2. 运行 Android JVM tests、debug/release lint、debug/release assemble。
3. 运行 `git diff --check`，核对 manifest 权限、ABI、Media3、min/target/compile SDK 均未漂移。
4. 执行一次普通 diff review；按项目预算最多进行一次修订。
5. 完全冻结并提交候选；随后只对该 exact SHA 执行一次双路只读复审。
6. 若 exact-SHA 发现非真机/非文件安全问题，记录到 `known-debt`，不得无限修订；真机/文件安全 blocker 按一次修订预算处理。
7. 构建并验证 `1.5.0+19` arm64 Release APK：package/version/SDK/ABI、zipalign、v2 签名、权限、SHA-256。
8. 生成 `docs/m4-device-acceptance.md`，所有真机行保持 `PENDING`，交付项目所有者测试；不把自动化测试描述为真机通过。

## 真机停止条件

- 0/90/180/270° 任一样本出现无法由显示坐标映射解释的错位：停止验收并报告，不得改成中间文件双转码。
- 发生文件丢失、误删或发布边界回归：阻止候选并使用唯一一次修订预算。
- 仅自动化、静态检查或审查意见且无真机/文件安全影响：记录债务，不阻止候选进入真机验收。
