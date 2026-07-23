# VideoSlim 当前项目状态

> **更新时间：** 2026-07-23
> **当前阶段：** 拍摄时间/GPS保留与输出命名 `CANDIDATE READY — DEVICE ACCEPTANCE PENDING`
> **当前候选版本：** `1.6.1+22`
> **当前候选源代码：** `b0267a0b959ccb46785daa1c91d0be96b5a0ef98`
> **当前已接受发布基线：** M3 `19abfb7da2e8fa028e7200000f0dc2a114bc840e`（`1.4.3+18`）

## 1. 里程碑进度

| 里程碑 | 状态 | 结果 |
|---|---|---|
| M0 环境搭建 | COMPLETE | 无头 Flutter/Android 工具链与 ARM64 APK 真机安装通过 |
| M1 最小原型 | ACCEPTED | 导入、信息读取、视频压缩、MediaStore 输出和 F19 日志主流程通过 |
| M2 压缩完整化 | `ACCEPTED — private scope` | 预设/自定义、VBR、前台服务、通知、取消、恢复、SAF、兼容 Decoder 重试可用 |
| M3 音频提取 | `ACCEPTED — private scope` | 项目所有者于 2026-07-22 明确报告当前 M3 候选测试成功 |
| M4-A 画面裁剪 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 自动化、构建和静态 APK 检查通过；真机十项矩阵尚未执行 |
| F7 拍摄时间/GPS与输出命名增强 | `CANDIDATE READY — DEVICE ACCEPTANCE PENDING` | 缺省处理时间blocker已修订；一条Pixel设备“仅时间”任务成功，完整来源/图库/SAF矩阵仍待执行 |
| C轨 D1/F20–F22 | `PLANNED — NOT AUTHORIZED` | D1先读既有F19；C1a提示、C1b建议目标、C2能力诊断、C3条件增强均尚未实施 |
| M4-B 时间裁剪 | `PLANNED — NOT AUTHORIZED` | F8 trim已有书面范围，但不在M4-A授权范围 |
| M4-C 多段时间编辑 | `PLANNED — NOT AUTHORIZED` | F23同源多段依赖M4-B真机接受；跨文件拼接归F16 |
| M5 自用版打磨 | NOT STARTED | 历史、批量、目标大小等未实现 |
| M6 上架/iOS | NOT STARTED | 生产签名、商店和 iOS 引擎未实现 |

`ACCEPTED — private scope` 只表示项目所有者接受已测试的自用设备/素材范围，不表示生产发布或多设备保证。`CANDIDATE READY` 也不等于真机通过。

## 2. 当前候选身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.6.1+22`
- 候选源代码：`b0267a0b959ccb46785daa1c91d0be96b5a0ef98`
- 候选 tree：`aa218a23dac1bbf0f69eb5dc2ff6e633eedd1ceb`
- Android：minSdk 26、targetSdk 36、compileSdk 36
- Media3：`1.10.1`
- Release ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate、APK Signature Scheme v2；不是商店生产签名
- APK：`VideoSlim-1.6.1+22-b0267a0-arm64-v8a-release.apk`（仅供私有真机验收）
- APK 大小：`18,378,659` bytes
- APK SHA-256：`21ac3df44e8afa116cc9bb7c5f8ca7db94bacc45830f2dd373e4b9d4b0570409`

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

## 4. 当前候选自动化与证据

候选源代码 `b0267a0...`（metadata核心仍来自已复审的 `a92d1cd...`）：

- Dart format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`224/224`；
- Android JVM tests：`341/341`，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Debug/Release assemble：PASS；
- ARM64 Flutter Release APK：PASS；
- ZIP、zipalign、v2 签名、package/version/SDK/ABI、权限和静态凭据扫描：PASS；
- 旧 `47c5448...` 双路复审均超时且失效，不计PASS；`497c5d2...` Route A：FAIL、Route B：PASS；metadata缺省时间修订 `a92d1cd...` focused review：PASS。设备发现的超长日志复制问题已在 `b0267a0...` 收窄修复，focused exact-SHA review：PASS，无BLOCKER/IMPORTANT finding。

构建机没有连接 Android 设备。项目所有者提供的Pixel 10 Pro / Android 17最后一次任务日志证明：来源解析为“时间存在、位置缺失”，最终MP4同样通过该状态核验并成功发布；但来源原文件谱系、外部atom对照、`DATE_TAKEN`查询、图库排序和SAF仍未验证，因此不能把该单次成功扩写为完整设备验收。

## 5. 真机验收状态

`docs/capture-metadata-device-acceptance.md` 现可执行；独立的M4-A裁剪矩阵也仍保持 `PENDING`，包括：

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
5. 单次 `InAppMp4Muxer` 已有一条Pixel设备“时间存在、位置缺失”的App内核验成功证据，但尚无未加工来源谱系、外部atom与图库索引对照；失败时必须停止并重新评级，不得自动增加remux。
6. C轨、M4-B/F8、M4-C/F23、M5 历史/批量/目标大小和 M6 iOS/上架均未开始；书面规划不得误写为已实现。

## 7. 已批准的规划基线

- `docs/VideoSlim-AI-Handoff-2026-07-23.md` 已整合项目所有者批准的C轨和时间编辑规划；
- `docs/VideoSlim PRD.md` v1.12 将C1a/C1b、C2、C3分别映射为F20、F21、F22，将同源多段编辑映射为F23；
- 规划入库不等于全部条目同时开工。每个代码项仍需明确选择范围，并遵守一次实现、一次修订、一轮复审和真机证据优先规则。

## 8. 下一步与禁止范围

下一步是安装 `b0267a0...` APK，先复测超长日志复制，再执行真实iPhone/Pixel来源、MediaStore图库和SAF矩阵。不得再自动追加生产修订。

- 若出现无法由显示坐标与 ≤2px 取整解释的错位，停止验收并报告；不得改用中间视频后二次有损转码。
- 若发生源文件/旧输出丢失、覆盖、误删或修改，立即阻止候选。
- 当前候选真机验收仍是第一优先。若项目所有者明确选择一个新代码项，应只启动该最小范围；不得并行启动M4-B、C2/C3、hardening、refactor、migration或其他生产改动。

## 9. 阅读顺序

1. `docs/VideoSlim-AI-Handoff-2026-07-23.md`
2. `AI_REVIEW_START_HERE.md`
3. `docs/current-project-status.md`
4. `docs/capture-metadata-completion-report.md`
5. `docs/capture-metadata-device-acceptance.md`
6. `docs/m4-a-completion-report.md`
7. `docs/m4-device-acceptance.md`
8. `docs/VideoSlim PRD.md`
9. `docs/known-debt.md`
10. `AGENTS.md`
