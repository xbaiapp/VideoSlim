# C2/F21 本机编码器能力诊断实施计划

> **日期：** 2026-07-23
> **状态：** `COMPLETE — DEVICE REPORT CAPTURED, OWNER DISPOSITION PENDING`
> **基线：** `de1b33f8a64fc56d8690397ba85b54669d60f469`（M4-B private-scope接受记录）
> **候选版本目标：** `1.9.0+25`
> **唯一范围：** F19调试区新增只读“编码器能力”页，查询并展示Android系统声明的目标视频编码器能力；不configure codec、不创建媒体任务、不改变任何转码行为

## 1. 用户价值

当前Pixel/HEVC样本已经证明：Media3有效请求仍是500 kbps，但硬件编码器运行期输出明显过冲。C2把本机能力从猜测变成可复制的真实系统声明，作为后续C3“QP / CQ / AV1 / 都不做”决策输入。

不做C2的后果：C3只能根据codec名称或经验猜测，容易在设备不支持QP/CQ/AV1硬编时选错方向。C2本身不承诺系统声明一定等于实际编码行为，最终仍以真机编码证据为准。

## 2. 锁定的wire contract

Channel继续使用`videoslim/engine`，新增方法：

```text
getEncoderCapabilities(arguments = {})
```

响应顶层固定为：

```text
{
  sdkInt: int,
  queriedMimeTypes: [string],
  encoders: [EncoderCapabilityEntry]
}
```

查询mime固定且顺序稳定：

1. `video/avc`
2. `video/hevc`
3. `video/av01`
4. `video/x-vnd.on2.vp9`

每个`EncoderCapabilityEntry`固定字段：

- `name: string`
- `canonicalName: string?`
- `mimeType: string`
- `isAlias: bool?`
- `isHardwareAccelerated: bool?`
- `isSoftwareOnly: bool?`
- `isVendor: bool?`
- `classificationSource: platform | unavailable_pre29`
- `supportsCq: bool?`
- `supportsVbr: bool?`
- `supportsCbr: bool?`
- `supportsQpBounds: bool?`
- `bitrateRange: {lower:int, upper:int}?`
- `complexityRange: {lower:int, upper:int}?`
- `errorCode: string?`

语义：

- API 29+使用平台`isHardwareAccelerated/isSoftwareOnly/isVendor/isAlias/canonicalName`；API 26–28不把名称启发式伪装成平台事实，对应字段为null，`classificationSource=unavailable_pre29`。
- `supportsQpBounds`只在API 31+查询；更早系统返回null，而不是false。
- 单个codec/mime查询失败时只把该entry标为`CAPABILITY_QUERY_FAILED`，其余entries继续返回；顶层codec目录获取失败才让method返回错误。
- 数字经Dart按有限、整数、安全范围严格解析；未知字段/enum或结构错误归一为typed `UNKNOWN`平台数据错误，不泄漏TypeError。

## 3. 必须实现

1. Kotlin新增可注入、可纯JVM测试的能力读取器：
   - 只枚举`MediaCodecList.ALL_CODECS`；
   - 只保留encoder及四种目标mime；
   - 读取CQ/VBR/CBR、QP bounds、bitrate/complexity range和平台分类属性；
   - 名称、mime确定性排序；
   - 单项失败隔离。
2. `EngineChannel`新增严格空map方法，不改`getCapabilities`或任何process/recovery路径。
3. Dart新增不可变typed report/entry/range模型和严格解析、确定性可复制文本。
4. `VideoEngine`与`MethodChannelVideoEngine`新增`getEncoderCapabilities()`；调用日志仍为best-effort，不影响成功/错误语义。
5. F19调试日志页新增“编码器能力”入口；独立只读页支持：
   - 自动加载与刷新；
   - 系统API、查询mime和entry列表；
   - 显示硬件/软件/未知、码率模式、QP、bitrate/complexity range、单项失败；
   - 复制确定性能力清单；
   - 明示“只查询系统声明，不会开始压缩或改变设置”。
6. 版本递增为`1.9.0+25`。
7. 新建真机验收清单；能力输出在所有者实际运行前保持PENDING。

## 4. 明确不做

- 不调用`MediaCodec.create*`、`configure()`、`start()`或创建Surface；
- 不启动`ProcessingService`、Transformer、通知、WakeLock、publication或recovery；
- 不改变`getCapabilities`的HEVC/H.264粗粒度结果；
- 不改变硬件VBR、码率、codec fallback、encoder/decoder selector、crop/trim/audio/metadata；
- 不自动实施C1b、C3a/QP、C3b/CQ、C3c/AV1或M4-C；
- 不增加网络、媒体读取或广域存储权限；
- 不引入hardening、refactor、migration或新生命周期框架。

## 5. RED→GREEN任务

### Task 1：Dart模型与channel RED

- `test/models/encoder_capabilities_test.dart`
- `test/engine/method_channel_video_engine_test.dart`
- `test/models/video_engine_test.dart`

先证明：完整/nullable/failure entry解析、整数约束、未知enum/字段拒绝、确定性文本、严格`{}`参数和方法名。

状态：`RED CONFIRMED → GREEN`。

### Task 2：Kotlin读取器RED

- `android/app/src/test/kotlin/com/videoslim/videoslim/EncoderCapabilityReaderTest.kt`

先证明：只保留target encoder mime、稳定排序、完整字段、API<29/31 null语义、单项失败隔离，以及生产文件没有codec create/configure/start调用。

状态：`RED CONFIRMED → GREEN`。

### Task 3：F19页面RED

- `test/screens/encoder_capabilities_screen_test.dart`
- `test/screens/debug_log_screen_test.dart`
- `test/widget_test.dart`

先证明：入口、只读说明、加载/空/错误/刷新、能力显示、复制清单和旧异步响应不覆盖新结果。

状态：`RED CONFIRMED → GREEN`。

### Task 4：最小GREEN

只修改上述模型、adapter、`EngineChannel`、新reader、新screen及必要调用点；不触碰TranscodeEngine/ProcessingService/registry/publication/recovery。

状态：`GREEN`。Android查询复用既有进程级有界本地I/O dispatcher；媒体生产路径未修改。

## 6. 自动化与复审门禁

1. focused Dart/Kotlin测试先各自确认RED，再GREEN；
2. `dart format --output=none --set-exit-if-changed lib test`；
3. `flutter analyze`；
4. `flutter test`；
5. Android完整JVM测试；
6. `lintDebug`、`lintRelease`、`assembleDebug`；
7. `git diff --check`、权限/秘密模式/生产路径scope检查；
8. 冻结exact source SHA并执行唯一一次双路复审；最多一个纠正修订，不无限复审；
9. 构建并独立核验`1.9.0+25` ARM64 Debug-certificate内部APK；
10. 文档、提交、推送、远端SHA一致性。

完成结果：纠正候选源码为`11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`。Flutter `257/257`、Android JVM `350/350`、debug/release lint、debug assemble、ARM64 release build和APK静态核验均PASS。唯一双路复审绑定首个SHA `85e2497...`且两路均在600秒后无裁决；controller门禁随后发现并纠正API 29 lint blocker。按预算未复审纠正SHA，因此不得宣称独立exact-SHA review PASS。完整证据见`docs/c2-encoder-capabilities-completion-report.md`与`docs/c2-exact-sha-review-disposition.md`。

真机结果：Pixel 10 Pro / Android 17 API 37回传9个entry、四种固定MIME和0 query errors。硬件AVC/HEVC声明QP bounds，硬件AV1存在但不声明QP bounds，三个硬件encoder均不声明CQ。完整报告和未完成交互项见`docs/c2-encoder-capabilities-device-acceptance.md`；这些系统声明不自动授权C3。

## 7. 真机边界与停止条件

真机必须证明页面能打开、刷新、复制，且查询期间没有媒体任务、前台通知或设置变化；能力清单必须能回写设备验收文档。系统声明不证明实际运行效果。

出现以下任一情况立即停止候选：

1. 打开能力页启动/改变媒体任务；
2. 查询导致App崩溃、卡死或现有压缩设置变化；
3. 单个厂商codec异常让整页永久不可用且无法重试；
4. 新版影响已有压缩/trim/crop/audio任务或文件安全；
5. 引入新权限或修改现有转码路径。
