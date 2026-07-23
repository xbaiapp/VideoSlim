# VideoSlim 当前项目状态

> **更新时间：** 2026-07-23
> **当前阶段：** 拍摄时间/GPS保留与输出命名 `BLOCKED — EXACT-SHA ROUTE A FAIL`
> **当前候选版本：** `1.6.0+21`
> **当前候选源代码：** `497c5d2c028213835825f1aea1df5d356450d7f2`
> **当前已接受发布基线：** M3 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`（`1.4.3+18`）

## 1. 里程碑进度

| 里程碑 | 状态 | 结果 |
|---|---|---|
| M0 环境搭建 | COMPLETE | 无头 Flutter/Android 工具链与 ARM64 APK 真机安装通过 |
| M1 最小原型 | ACCEPTED | 导入、信息读取、视频压缩、MediaStore 输出和 F19 日志主流程通过 |
| M2 压缩完整化 | `ACCEPTED — private scope` | 预设/自定义、VBR、前台服务、通知、取消、恢复、SAF、兼容 Decoder 重试可用 |
| M3 音频提取 | `ACCEPTED — private scope` | 项目所有者于 2026-07-22 明确报告当前 M3 候选测试成功 |
| M4-A 画面裁剪 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 自动化、构建和静态 APK 检查通过；真机十项矩阵尚未执行 |
| F7 拍摄时间/GPS与输出命名增强 | `BLOCKED — EXACT-SHA REVIEW FAIL` | Media3缺省处理时间未被覆盖或做缺失核验；当前APK已隔离，不进入真机验收 |
| M4-B 时间裁剪 | `NOT STARTED — NOT AUTHORIZED` | F8 trim 不在 M4-A 授权范围 |
| M5 自用版打磨 | NOT STARTED | 历史、批量、目标大小等未实现 |
| M6 上架/iOS | NOT STARTED | 生产签名、商店和 iOS 引擎未实现 |

`ACCEPTED — private scope` 只表示项目所有者接受已测试的自用设备/素材范围，不表示生产发布或多设备保证。`CANDIDATE READY` 也不等于真机通过。

## 2. 当前候选身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.6.0+21`
- 候选源代码：`497c5d2c028213835825f1aea1df5d356450d7f2`
- 候选 tree：`4a51769e6764c296dbcc0ccf6e188de888d9de9c`
- Android：minSdk 26、targetSdk 36、compileSdk 36
- Media3：`1.10.1`
- Release ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate、APK Signature Scheme v2；不是商店生产签名
- APK：`blocked-497c5d2/VideoSlim-1.6.0+21-497c5d2-arm64-v8a-release.apk`（隔离，禁止分发）
- APK 大小：`18,378,659` bytes
- APK SHA-256：`f1c035effffafafb319cedec4d1de8fe4f41c84c0009afc6f9ddb66f0e94b6b3`

M3 已接受 APK、旧 `1.5.0+20` M4-A候选和各自证据继续保留，不被新候选覆盖或改写。

## 3. 当前已实现功能

### 视频压缩与 M4-A 画面裁剪

- Android Photo Picker 与 SAF `content://` 导入；
- HEVC / H.264 硬件 VBR；画质优先、均衡、极限压缩、自定义；
- 首页裁剪入口与 S3 添加/编辑/移除裁剪；
- S4 自由、16:9、9:16、1:1、4:3，八手柄、整体移动、实时像素尺寸和预览帧滑杆；
- 缩放/移动使用单次手势累计位移；自由缩放在偶数像素取整后仍固定相反边；
- “保持画质（仅裁剪）”档；
- 显示方向像素 crop → Media3 NDC，单次 `Crop → Presentation`；
- crop 进入 request、snapshot 和 retry；`INVALID_CROP` 可编辑/移除恢复；
- 原音轨复制、AAC 重编码或移除；
- 输出大小预估、空间检查、真实文件名/位置、打开和分享。

### 拍摄时间、位置与输出命名

- 只读取来源中存在且可可靠解释的原拍摄/容器时间和GPS；缺失或无效保持缺失，不使用处理时间、mtime或设备位置伪造；
- 通过Media3 `1.10.1` `InAppMp4Muxer`在现有单次转码中写入时间/GPS，不做第二次remux；
- 临时MP4在公开URI分配前回读核验，已承诺字段不匹配时不发布；
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
- F19 本地调试日志；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE` 权限。

## 4. 当前候选自动化与证据

候选源代码 `497c5d2...`：

- Dart format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`219/219`；
- Android JVM tests：`337/337`，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Debug/Release assemble：PASS；
- ARM64 Flutter Release APK：PASS；
- ZIP、zipalign、v2 签名、package/version/SDK/ABI、权限和静态凭据扫描：PASS；
- 旧 `47c5448...` 双路复审均超时且随后失效，不计PASS；最终 `497c5d2...` Route A：FAIL（处理时间伪造成来源时间的blocker），Route B：PASS。

构建机没有连接 Android 设备。上述证据不能替代真实时间/GPS atom、MediaStore图库排序、SAF、裁剪输出、旋转方向、codec、通知、后台、锁屏、取消、恢复或文件安全验收。

## 5. 真机验收状态

`docs/capture-metadata-device-acceptance.md` 已被Route A blocker阻止，不得执行；独立的M4-A裁剪矩阵仍保持 `PENDING`，包括：

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

项目所有者完成对应清单并明确接受前，M4-A和拍摄时间/GPS增强都不得写为 `ACCEPTED`。

## 6. 已知限制与冻结债务

1. Task 3 Slice B 继续冻结且未集成；recovery journal 尚未完整迁出主线程。
2. API 35 x86_64 instrumentation CI 的历史 `sdkmanager` PATH 问题仍未补跑；这不是当前真机验收替代品。
3. Release 使用 Android Debug certificate，只适合私有安装。
4. M4-A 默认假设 Media3 effects 作用于按旋转元数据转正后的画面；必须以四种 rotation 真机样本确认。
5. 单次 `InAppMp4Muxer` 时间/GPS写入尚无真实设备和未加工来源样本证据；失败时必须停止并重新评级，不得自动增加remux。
6. M4-B/F8 时间裁剪、M5 历史/批量/目标大小和 M6 iOS/上架均未开始。

## 7. 下一步与禁止范围

下一步必须由项目所有者决定是否额外授权一次外科手术式修订：在来源时间缺失时显式覆盖Media3当前时间默认值，并对时间/GPS的必有与必无同时做发布前核验。未获授权前不得修改生产代码、构建或交付 `1.6.0+21`。

- 若出现无法由显示坐标与 ≤2px 取整解释的错位，停止验收并报告；不得改用中间视频后二次有损转码。
- 若发生源文件/旧输出丢失、覆盖、误删或修改，立即阻止候选。
- 真机结论前不启动 M4-B/F8、hardening、refactor、migration 或其他生产改动。

## 8. 阅读顺序

1. `AI_REVIEW_START_HERE.md`
2. `docs/current-project-status.md`
3. `docs/capture-metadata-completion-report.md`
4. `docs/capture-metadata-device-acceptance.md`
5. `docs/m4-a-completion-report.md`
6. `docs/m4-device-acceptance.md`
7. `docs/VideoSlim PRD.md`
8. `docs/known-debt.md`
9. `AGENTS.md`
