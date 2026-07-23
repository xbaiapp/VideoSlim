# M4-B/F8 连续单段时间裁剪实施计划

> **日期：** 2026-07-23
> **状态：** `CORRECTIVE REVISION — EXACT-SHA GATES PENDING`
> **基线：** `65e4dee44fe1fbeec5135b199d326ca8673174eb`（`m4a/crop`）
> **候选版本目标：** `1.8.0+24`
> **唯一范围：** 一条来源视频的连续单段起止时间，与现有crop、Presentation、音频策略、压缩和metadata白名单在同一次Media3 Transformer导出中完成

## 1. 范围锁定

### 必须实现

1. S3增加“裁剪时长（可选）”入口和已选起止/保留时长摘要；
2. 新建S4时间裁剪编辑器：起止双滑块、起止/保留时长、预览帧；
3. 预览帧复用现有`VideoEngine.getPreviewFrame`，180 ms节流，旧响应不得覆盖新位置；
4. 业务校验：`0 <= start < end <= source.durationMs`且保留时长至少1000 ms；
5. Dart planner按保留时长计算名义值、上下界和C1a低收益判断；
6. `ProcessRequest.trimStartMs/trimEndMs`在channel、snapshot、recovery、显式retry中完整round-trip；
7. Kotlin严格解析成对trim字段，错误统一为`INVALID_TRIM`；读取源metadata后再次校验`end <= duration`；
8. Media3 1.10.1使用`MediaItem.ClippingConfiguration`，`startsAtKeyFrame=false`，再交给现有单个`EditedMediaItemSequence`/`Composition`；
9. crop effect仍先于Presentation，trim不增加第二次导出或中间视频；
10. metadata时间/GPS策略、发布、取消、recovery、`finishOnce`和硬件VBR保持不变。

### 明确不做

- 多段、删中段、片段乱序；
- 跨文件拼接、批量切成多份；
- 无损关键帧切割；
- 缩略图时间轴；
- 新生命周期框架、服务拆分、hardening/refactor/migration；
- C1b、C2、C3、M4-C；
- 因输出时长/体积偏差新增发布拒绝。

## 2. 已核验的实现接缝

- Dart `ProcessRequest` wire已预留`trimStartMs/trimEndMs`，snapshot/retry解析也已携带，但业务层尚未生成；
- Kotlin `ProcessRequest.parse`当前明确拒绝任何非空trim，`toChannelMap`固定写null；
- `TranscodeEngine`当前只创建一个`EditedMediaItem`、一个sequence、一个composition和一次`transformer.start`，应原位给`MediaItem`加clipping；
- `CompressionPlanner`当前所有体积估算都使用源时长，需要统一替换为有效保留时长；
- `HomeFlowState`已为crop提供互斥编辑、保存、移除和恢复先例；trim照抄，不改任务生命周期；
- `CropEditor`已有180 ms预览节流和request epoch防旧响应覆盖；`TrimEditor`复用同一模式，不为此重构crop代码；
- Media3 1.10.1实证API：`MediaItem.ClippingConfiguration.Builder().setStartPositionMs(long).setEndPositionMs(long).setStartsAtKeyFrame(false)`，由`MediaItem.Builder.setClippingConfiguration(...)`接入。

## 3. RED→GREEN任务

Tasks 1–7已完成；实现与测试文件均已进入待冻结工作树。

### Task 1：Dart trim契约与planner RED

**测试文件：**
- `test/models/process_request_test.dart`
- `test/logic/compression_planner_test.dart`

先增加失败测试：

1. 成对trim严格解析和channel round-trip；
2. 单边null、负数、`end <= start`、保留不足1000 ms拒绝；
3. 10秒源保留2–6秒时，估算按4秒而非10秒；
4. start=0、end=源时长合法；end超时长、极短段拒绝；
5. `toProcessRequest`准确携带起止值；
6. trim后的保守上界参与C1a低收益判断。

### Task 2：Home state与S4编辑器 RED

**测试文件：**
- `test/state/home_flow_state_test.dart`
- 新建`test/widgets/trim_editor_test.dart`
- `test/widget_test.dart`

先增加失败测试：

1. trim编辑是独占interaction phase；
2. 保存/移除/新来源清理trim，失败mutation可回滚；
3. S4显示起止双滑块、起点/终点和保留时长；
4. 滑块不能产生不足1秒的保留段；
5. 预览请求180 ms节流，旧响应不能覆盖新帧；
6. S3添加、编辑、移除trim；
7. 启动任务包含trim；trim改变S3估算；
8. snapshot恢复与software decoder retry保留完全相同的trim。

### Task 3：Kotlin parser、plan与MediaItem clipping RED

**测试文件：**
- `android/app/src/test/kotlin/com/videoslim/videoslim/ProcessModelsTest.kt`
- `android/app/src/test/kotlin/com/videoslim/videoslim/TranscodePlanTest.kt`
- 新建`android/app/src/test/kotlin/com/videoslim/videoslim/TrimmedMediaItemTest.kt`

先增加失败测试：

1. Int/Long成对trim解析、`toChannelMap`和再次parse相等；
2. 单边、非整数、负值、反向、少于1秒统一`INVALID_TRIM`；
3. metadata阶段拒绝`end > duration`并返回`INVALID_TRIM`；
4. storage estimate使用保留时长；
5. clipping start/end准确，`startsAtKeyFrame=false`；
6. 无trim时clipping保持UNSET；
7. crop + trim + Presentation的effect顺序仍只有`CROP, PRESENTATION`，不创建中间管线。

### Task 4：实现Dart契约、planner和状态

**修改文件：**
- `lib/models/process_request.dart`
- `lib/logic/compression_planner.dart`
- `lib/state/home_flow_state.dart`
- `lib/screens/home_screen.dart`

实现：

- 增加不可变`VideoTrim(startMs,endMs)`和值相等；
- `CompressionPlan`持有trim和effectiveDurationMs；
- 所有估算统一使用effectiveDurationMs；
- Home state新增`editingTrim`、`trim`、save/restore/remove/clear；
- 新来源、snapshot恢复和错误修复入口同步trim；
- `INVALID_TRIM`与`INVALID_CROP`同样阻止启动，直到编辑或移除。

### Task 5：实现S4编辑器和S3入口

**新增/修改文件：**
- 新建`lib/widgets/trim_editor.dart`
- `lib/widgets/m2_compression_card.dart`
- `lib/screens/home_screen.dart`

实现：

- `RangeSlider`选择一个连续区间；
- 拖动哪一端就预览哪一端，终点预览用`endMs-1`避免读取媒体末端之外；
- 180 ms debounce + epoch防陈旧响应；
- 新增时完整区间不能作为“有效trim”保存，至少移动一端；
- S3摘要显示起点、终点和保留时长；
- 低于1秒的源禁用trim入口并给出说明；
- 不改变preset，crop与trim可独立添加/移除。

### Task 6：实现Kotlin严格契约、估算与clipping

**新增/修改文件：**
- `android/app/src/main/kotlin/com/videoslim/videoslim/ProcessModels.kt`
- `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodePlan.kt`
- 新建`android/app/src/main/kotlin/com/videoslim/videoslim/TrimmedMediaItem.kt`
- `android/app/src/main/kotlin/com/videoslim/videoslim/TranscodeEngine.kt`

实现：

- 增加`EngineErrorCode.INVALID_TRIM`；
- parser只接受两端同时null或整数成对出现，保留至少1000 ms；
- plan根据metadata校验终点并计算有效时长；
- storage estimate按有效时长；
- 构建带clipping的`MediaItem`，再沿用原单composition路径；
- 不触碰线程、服务、publication、metadata verifier或recovery commit语义。

### Task 7：版本与候选身份

**修改文件：**
- `pubspec.yaml`

把版本独立递增到`1.8.0+24`。不修改application ID、签名策略或权限。

## 4. 自动验证门禁

当前状态：首个冻结SHA已完成第1–8项；唯一双路复审为一路PASS、一路BLOCKERS并触发唯一纠正修订。纠正工作树已通过Flutter `244/244`，仍需冻结纠正SHA并完成第9–11项；按预算不发起第二轮复审。

1. focused Dart/Kotlin测试先各自证明RED，再完成GREEN；
2. `dart format --output=none --set-exit-if-changed lib test`；
3. `flutter analyze`；
4. `flutter test`；
5. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug --console=plain`；
6. `git diff --check`；
7. 冻结唯一候选commit并记录tree；
8. 对该exact SHA发起一轮规范与质量并行复审；最多只做一次修订，不发起第二轮；
9. 重新运行受影响测试和完整门禁；
10. 构建ARM64 release APK，核验SHA-256、package/version、ABI、ZIP、zipalign、v2签名、证书连续性、权限和秘密模式；
11. 文档必须区分自动化PASS与真机`NOT RUN/PENDING`，不得把用户跳过C1a测试外推为M4-B设备PASS。

## 5. 真机验收矩阵（候选完成后保持PENDING）

- 0°：{无crop,crop} × {掐头,去尾,两端}；
- 90°：crop + 两端trim抽查；
- audioMode：copy/reencode/remove各一条；
- 边界：start=0、end=时长、1秒段、非关键帧起点；
- ffprobe：视频时长误差目标≤1帧+设备容差，音频额外允许约一个AAC frame封装差；
- 主观音画同步；
- 取消、后台、锁屏、进程恢复、retry；
- 时间/GPS应有/应无、MediaStore/SAF和文件安全沿用现有矩阵。

没有物理设备证据时，以上全部保留PENDING；若出现可感知音画漂移、超容差、trim恢复丢失、源/旧输出损坏或单管线不变量破坏，停止候选，不扩张修复范围。
