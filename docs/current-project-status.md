# VideoSlim 当前项目状态

> **更新时间：** 2026-07-22
> **当前阶段：** M3 `ACCEPTED — private scope`
> **当前可安装版本：** `1.4.3+18`
> **当前发布代码基线：** `19abfb7da2e8fa028e7200000f0dc2a114bc840e`

## 1. 里程碑进度

| 里程碑 | 状态 | 结果 |
|---|---|---|
| M0 环境搭建 | COMPLETE | 无头 Flutter/Android 工具链与 ARM64 APK 真机安装通过 |
| M1 最小原型 | ACCEPTED | 导入、信息读取、视频压缩、MediaStore 输出和 F19 日志主流程通过 |
| M2 压缩完整化 | `ACCEPTED — private scope` | 预设/自定义、VBR、前台服务、通知、取消、恢复、SAF、兼容 Decoder 重试可用 |
| M3 音频提取 | `ACCEPTED — private scope` | 项目所有者于 2026-07-22 明确报告当前 M3 候选测试成功 |
| M4 裁剪编辑器 + trim | NOT STARTED | PRD 已定义，代码只预留 contract/UI seam，尚未启用 |
| M5 自用版打磨 | NOT STARTED | 历史、批量、目标大小等未实现 |
| M6 上架/iOS | NOT STARTED | 生产签名、商店和 iOS 引擎未实现 |

`ACCEPTED — private scope` 表示项目所有者接受当前自用设备/素材范围，不表示生产发布、多设备兼容保证或完整扩展矩阵全部通过。

## 2. 当前发布身份

- 应用：VideoSlim / 视频瘦身
- 包名：`com.videoslim.videoslim`
- 版本：`1.4.3+18`
- Android：minSdk 26、targetSdk 36、compileSdk 36
- Media3：`1.10.1`
- Release ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate、APK Signature Scheme v2；可覆盖此前私有测试版，不是商店生产签名
- APK 文件：`VideoSlim-1.4.3+18-19abfb7-arm64-v8a-release.apk`
- APK 大小：`18,231,123` bytes
- APK SHA-256：`12523bac8b91994e23f965c98ce9f26c4e0ff3a8aac18fbfefb4cb01f34fbcf7`
- 发布代码 commit：`19abfb7da2e8fa028e7200000f0dc2a114bc840e`
- 发布代码 tree：`8014c3af95b152db653a6903775576b539fbbb3b`

## 3. 已实现功能

### 视频

- Android Photo Picker 与 SAF `content://` 导入；
- 完整视频/音频 metadata 读取；
- HEVC / H.264 硬件编码；
- 画质优先、均衡、极限压缩和自定义设置；
- 目标平均码率使用硬件 VBR；
- 原音轨复制、AAC 重编码或移除；
- 硬件输入 Decoder 失败后由用户明确触发 software-only 兼容重试；
- 输出大小区间、空间检查、真实文件名/保存位置、打开和分享。

### 音频（M3）

- 第一条 AAC 音轨无损直提：`MediaExtractor + MediaMuxer`，不创建音频 Decoder/Encoder；
- AAC-LC 强制重编码：192/128/96/64 kbps；
- 单声道/双声道；超过 2 声道明确拒绝；
- 无音轨、copy 不支持、解码、编码和输出无效均有稳定错误；
- 默认发布到 `Music/VideoSlim`，支持持久化 SAF 自定义文件夹；
- 发布前逐 sample 物理读取并核对 sample count、PTS、payload bytes、digest 和覆盖时长；
- 成功后的 `getAudioInfo` 复用刚完成校验的 one-shot metadata，避免立即重复扫描同一输出；cache miss、URI 不匹配和进程重启仍走物理读取。

### 共同任务运行时

- 一个前台服务、同一时间一个媒体任务；
- `taskKind=video_compression|audio_extraction` 严格隔离；
- 通知、进度、取消、snapshot 重连和明确终态；
- 私有临时输出 → 完整校验 → MediaStore/SAF 发布；
- recovery journal、启动 reconciliation 和有所有权证据的精确清理；
- F19 本地调试日志复制/分享；
- 无 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE` 权限。

## 4. 当前验证证据

在发布代码 `19abfb7...` 上：

- Dart format：PASS（49 files，0 changed）；
- Flutter analyze：PASS；
- Flutter tests：`191/191`；
- Android JVM tests：`307/307`；
- Android debug/release lint：PASS；
- Debug/Release assemble：PASS；
- ARM64 Release APK build：PASS；
- ZIP、zipalign、v2 签名、package/version/SDK/ABI、权限差异和静态 secret scan：PASS；
- 两路 exact-SHA 只读复审：PASS / PASS，0 actionable blocker。

GitHub Actions run `29883255089`：

- 主任务 `Flutter and Android checks` 全部通过；
- API 35 x86_64 instrumentation job 因 runner 中 `sdkmanager: command not found`（exit 127）在安装 SDK 时失败；该 job 尚未构建应用/instrumentation APK，也没有执行 emulator 测试。因此远端 CI 总状态是 red，但没有观察到应用测试失败。

## 5. M3 真机接受结论

项目所有者于 2026-07-22 明确报告：“M3 测试成功”。据此，M3 状态更新为 `ACCEPTED — private scope`，允许关闭当前 M3 开发候选。

本次聊天没有附带完整设备型号、逐项矩阵、F19 原文或精确耗时，因此：

- 不反向把 `docs/m3-device-acceptance.md` 所有空白项目预填为 PASS；
- 不宣称原 PRD 的“一小时 copy <10 秒”性能目标已证明；
- 不扩大到 API 26–28、多 Provider、多 SoC、极端长媒体或商店生产范围。

## 6. 已知限制与冻结债务

1. Task 3 Slice B 未合入：冻结于 `981d36662d37d59c36f221ffea72ec9ff32a7134`，不得把它描述为当前产品能力。
2. Recovery journal 的完整 engine-serial I/O 迁移未进入当前候选；极端慢存储/Provider 下可能有短暂无响应，目前没有真机文件安全 blocker 证据。
3. 发布前完整音频物理校验故意保留。它优先保证完整性，不以速度换取错误成功；最新优化只移除了成功后第二次 `getAudioInfo` 扫描。
4. API 35 x86_64 instrumentation CI 仍需修正 `sdkmanager` PATH/setup 后补跑；这不是当前真机接受的自动替代品。
5. Debug certificate 仅适合私有安装，不是 Play 商店签名。
6. M4 crop/trim、M5 历史/批量/目标大小和 M6 iOS/上架均未开始。

## 7. 下一阶段边界

PRD 下一里程碑是 M4：画面裁剪和时间裁剪。但 M3 接受不会自动授权 M4 开工；应由项目所有者明确批准范围后再实施。

推荐 M4 保持现有架构：裁剪参数与压缩参数一起进入 `VideoEngine.process()`，用同一个 Media3 Transformer composition 执行“先裁剪、后缩放/压缩”，不得先生成中间裁剪文件再二次有损编码。

## 8. 阅读顺序

1. `AI_REVIEW_START_HERE.md`
2. `docs/current-project-status.md`
3. `README.md`
4. `docs/VideoSlim PRD.md`
5. `docs/m3-completion-report.md`
6. `docs/known-debt.md`
7. `AGENTS.md`
