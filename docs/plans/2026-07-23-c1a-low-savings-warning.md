# C1a 低收益/可能变大提示实施计划

> **For Hermes:** 按 RED→GREEN 完成一个最小纯 Dart 候选；冻结后只做一轮 exact-revision 复审。

**Goal:** 用保守输出上界与已知源文件大小判断“预计节省低于 15%”，替换现有按视频码率 90% 判断的单一提示；不改变编码参数、不阻止发布。

**Architecture:** 保留 `CompressionPlan.hasLowSavings` 作为现有 UI 接缝，只替换其纯 Dart 计算语义。S3 继续使用一个确认弹窗：无 crop 时提供“暂不处理 / 仍按原目标压缩”；有 crop 时额外提供“保持画质（仅裁剪）”，选择后只切换档位并返回 S3，不启动任务。其他 HDR、codec fallback、超范围警告继续合并进同一弹窗。

**Tech Stack:** Flutter 3.44.6、Dart 3.12.2、Provider、Flutter widget tests。

**Scope lock:**

- 允许修改：`lib/logic/compression_planner.dart`、`lib/screens/home_screen.dart`、`lib/widgets/m2_compression_card.dart`、对应 Dart/widget 测试、`pubspec.yaml`候选版本和状态/交接文档。
- 不修改：Kotlin、Media3、码率/codec/音频请求、publication、recovery、前台服务、`finishOnce`、文件名或F19 wire。
- `fileSizeBytes <= 0` 时不计算低收益、不弹该提示。
- 不新增第二个低收益弹窗，不静默改写目标，不增加体积发布门禁。

---

### Task 1: 用保守体积上界替换低收益判定

**Files:**
- Modify: `test/logic/compression_planner_test.dart`
- Modify: `lib/logic/compression_planner.dart`

**Steps:**
1. 把旧“目标码率达到源码率 90%”测试替换为：`estimatedOutputMaxBytes` 恰好相当于源大小 85% 时不提示；再小 1 byte 时提示；源大小为 0 时不提示；不同 `videoBitrate` 不改变结果。
2. 运行 `flutter test test/logic/compression_planner_test.dart`，确认旧实现 RED。
3. 最小实现：`source.fileSizeBytes > 0 && estimatedOutputMaxBytes * 100 > source.fileSizeBytes * 85`；使用现有 BigInt 安全乘积 helper 或等价安全函数。
4. 更新 `hasLowSavings` 注释并运行同一测试，确认 GREEN。

### Task 2: 更新 S3 提示和条件动作

**Files:**
- Modify: `test/widget_test.dart`
- Modify: `lib/screens/home_screen.dart`
- Modify: `lib/widgets/m2_compression_card.dart`

**Steps:**
1. 将通用 widget fixture 的源大小调整为与其声明码率/时长一致的非低收益素材，并更新对应结果页断言。
2. 新增无 crop 低收益测试：S3 显示“可能变大”提示；开始时只显示“暂不处理 / 仍按原目标压缩”；前者不创建请求，后者保留原计划并创建请求。
3. 新增有 crop 低收益测试：确认弹窗显示“保持画质（仅裁剪）/ 仍按原目标压缩 / 取消”；选择保持画质后不创建请求，S3 的 preserve-quality chip 被选中。
4. 运行上述 widget 测试，确认 RED。
5. 将 `_confirmPlan` 的布尔结果改为局部枚举结果；低收益时按 crop 条件构建动作，其他警告保持原行为。选择 preserve-quality 时调用现有 `HomeFlowState.selectCompressionPreset` 后返回，不启动 native 任务。
6. 更新 S3 notice 文案为“保守估算下收益有限，甚至可能变大”。
7. 运行 widget 测试，确认 GREEN。

### Task 3: 冻结候选并验证

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/current-project-status.md`
- Modify: `docs/VideoSlim-AI-Handoff-2026-07-23.md`

**Steps:**
1. 记录项目所有者已明确选择 C1a；其余 C1b/C2/C3/M4-B/M4-C 保持未授权。
2. 运行：
   - `dart format --output=none --set-exit-if-changed lib test`
   - `flutter analyze`
   - `flutter test`
3. 检查 `git diff --check`、范围和不变量；提交候选。
4. 对冻结 revision 做一轮并行的规格符合性 + 代码质量复审。任何修改都会使旧 verdict 失效；最多进行一次局部修订并重跑相关门禁。
5. 新用户可见行为使用 `1.7.0+23`，从冻结源码构建arm64-v8a私有验收APK；本机没有Android设备，不宣称真机通过。
