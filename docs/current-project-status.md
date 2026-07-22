# VideoSlim 当前项目状态

> **更新时间：** 2026-07-22
> **当前阶段：** M4-A `CANDIDATE READY — DEVICE ACCEPTANCE PENDING`
> **当前候选版本：** `1.5.0+19`
> **M4-A 候选源代码：** `6de3f7f971bbfbe4b1b2a8cf457f8e125b9228e1`
> **当前已接受发布基线：** M3 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`（`1.4.3+18`）

## 1. 里程碑进度

| 里程碑 | 状态 | 结果 |
|---|---|---|
| M0 环境搭建 | COMPLETE | 无头 Flutter/Android 工具链与 ARM64 APK 真机安装通过 |
| M1 最小原型 | ACCEPTED | 导入、信息读取、视频压缩、MediaStore 输出和 F19 日志主流程通过 |
| M2 压缩完整化 | `ACCEPTED — private scope` | 预设/自定义、VBR、前台服务、通知、取消、恢复、SAF、兼容 Decoder 重试可用 |
| M3 音频提取 | `ACCEPTED — private scope` | 项目所有者于 2026-07-22 明确报告当前 M3 候选测试成功 |
| M4-A 画面裁剪 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 自动化、构建和静态 APK 检查通过；真机十项矩阵尚未执行 |
| M4-B 时间裁剪 | `NOT STARTED — NOT AUTHORIZED` | F8 trim 不在 M4-A 授权范围 |
| M5 自用版打磨 | NOT STARTED | 历史、批量、目标大小等未实现 |
| M6 上架/iOS | NOT STARTED | 生产签名、商店和 iOS 引擎未实现 |

`ACCEPTED — private scope` 只表示项目所有者接受已测试的自用设备/素材范围，不表示生产发布或多设备保证。`CANDIDATE READY` 也不等于真机通过。

## 2. M4-A 候选身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.5.0+19`
- 候选源代码：`6de3f7f971bbfbe4b1b2a8cf457f8e125b9228e1`
- 候选 tree：`28bbb852bbaaa887f739ca09871102d78c9775fe`
- Android：minSdk 26、targetSdk 36、compileSdk 36
- Media3：`1.10.1`
- Release ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate、APK Signature Scheme v2；不是商店生产签名
- APK：`VideoSlim-1.5.0+19-6de3f7f-arm64-v8a-release.apk`
- APK 大小：`18,362,275` bytes
- APK SHA-256：`de8c6a4b4ce53f4ae7d117bd361b0f2830681d3b3d3798de76fa47a2e3520b4f`

M3 已接受 APK 和证据继续保留在 M3 文档中，不因 M4-A 候选出现而被改写。

## 3. 当前已实现功能

### 视频压缩与 M4-A 画面裁剪

- Android Photo Picker 与 SAF `content://` 导入；
- HEVC / H.264 硬件 VBR；画质优先、均衡、极限压缩、自定义；
- 首页裁剪入口与 S3 添加/编辑/移除裁剪；
- S4 自由、16:9、9:16、1:1、4:3，八手柄、整体移动、实时像素尺寸和预览帧滑杆；
- “保持画质（仅裁剪）”档；
- 显示方向像素 crop → Media3 NDC，单次 `Crop → Presentation`；
- crop 进入 request、snapshot 和 retry；`INVALID_CROP` 可编辑/移除恢复；
- 原音轨复制、AAC 重编码或移除；
- 输出大小预估、空间检查、真实文件名/位置、打开和分享。

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
- F19 本地调试日志；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE` 权限。

## 4. M4-A 自动化与候选证据

候选源代码 `6de3f7f...`：

- Dart format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`213/213`；
- Android JVM tests：`317/317`，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Debug/Release assemble：PASS；
- ARM64 Release APK：PASS；
- ZIP、zipalign、v2 签名、package/version/SDK/ABI、权限和静态凭据扫描：PASS；
- exact-SHA 双路只读复审：Route B PASS、0 blocker；Route A 未返回最终 verdict，按规则不计 PASS，不能写为 PASS / PASS。

构建机没有连接 Android 设备。上述证据不能替代真实裁剪输出、旋转方向、codec、通知、后台、锁屏、取消、恢复或文件安全验收。

## 5. 真机验收状态

`docs/m4-device-acceptance.md` 的十项矩阵全部是 `PENDING`，包括：

1. 0°/90°/180°/270° 框选与输出对齐；
2. 五种比例与自由拖拽；
3. 裁剪后 720p 缩放顺序；
4. 保持画质的主观质量与体积关系；
5. 未裁剪普通压缩回归；
6. 取消、后台、锁屏和进程恢复；
7. 预估大小误差与 F19 参数。

项目所有者完成清单并明确接受前，M4-A 不得写为 `ACCEPTED`。

## 6. 已知限制与冻结债务

1. Task 3 Slice B 继续冻结且未集成；recovery journal 尚未完整迁出主线程。
2. API 35 x86_64 instrumentation CI 的历史 `sdkmanager` PATH 问题仍未补跑；这不是当前真机验收替代品。
3. Release 使用 Android Debug certificate，只适合私有安装。
4. M4-A 默认假设 Media3 effects 作用于按旋转元数据转正后的画面；必须以四种 rotation 真机样本确认。
5. M4-B/F8 时间裁剪、M5 历史/批量/目标大小和 M6 iOS/上架均未开始。

## 7. 下一步与禁止范围

下一步只有：交付 M4-A APK，由项目所有者执行 `docs/m4-device-acceptance.md`。

- 若出现无法由显示坐标与 ≤2px 取整解释的错位，停止验收并报告；不得改用中间视频后二次有损转码。
- 若发生源文件/旧输出丢失、覆盖、误删或修改，立即阻止候选。
- 真机结论前不启动 M4-B/F8、hardening、refactor、migration 或其他生产改动。

## 8. 阅读顺序

1. `AI_REVIEW_START_HERE.md`
2. `docs/current-project-status.md`
3. `docs/m4-a-completion-report.md`
4. `docs/m4-device-acceptance.md`
5. `docs/VideoSlim PRD.md`
6. `docs/known-debt.md`
7. `AGENTS.md`
