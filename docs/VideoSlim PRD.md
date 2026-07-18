# 视频瘦身（VideoSlim）产品需求文档（PRD）

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.2（发布前复查修正：§4 表格、F5/§5.4 坐标职责、F6 服务类型、§5.5 权限清单、R4 HDR 分阶段） |
| 日期 | 2026-07-18 |
| 状态 | 规划完成，待开发 |
| 目标读者 | AI 编程助手 + 项目所有者 |
| 产品名 | 视频瘦身（VideoSlim，工作代号，可随时更换） |

---

## 0. 如何使用本文档（给项目所有者）

本文档为"零编程经验 + AI 辅助开发"模式设计。使用规则：

1. **按里程碑推进**：不要把整个文档一次性丢给 AI 让它"把 App 写完"。每次只做一个里程碑（见第 8 章），完成并在真机上通过验收标准后，再进入下一个。
2. **每次开发会话的标准开场**：把「第 5 章技术架构」+「当前里程碑章节」+「涉及功能的详细规格章节」一起提供给 AI，并说明"我正在做里程碑 MX，请只完成本里程碑的任务"。
3. **遇到报错**：把报错信息**原文完整粘贴**给 AI，并说明当时在做哪一步、哪个里程碑。不要自己总结报错。
4. **AI 给的方案与本文档冲突时**：以本文档的架构决策为准（特别是 5.1 节的技术选型），除非 AI 明确指出文档中某个 API 已过时并给出了官方文档依据。
5. 文档中标注 ⚠️ 的地方是已知技术风险点，开发到相关功能时要提醒 AI 特别处理。

---

## 1. 项目概述

### 1.1 背景

手机录制的讲课视频、屏幕录制视频体积巨大。原因：手机为保证实时录制，使用高码率 H.264 硬件编码（1080p 通常 12–20 Mbps）。而讲课、录屏这类画面变化少、复杂度低的内容，实际只需 2–4 Mbps 的 HEVC（H.265）编码即可达到几乎无损的观感，存在 **4–8 倍的压缩空间**。

### 1.2 产品目标

一款**全本地处理**的 Android 视频工具 App，实现：

1. 在尽量不损失画质和音质的前提下，大幅压缩视频体积；
2. 从视频中无损或有损提取音频；
3. 裁剪视频画面（去掉录屏中多余的画面区域）。

### 1.3 目标用户与定位

- 第一阶段：项目所有者自用；
- 第二阶段（可选）：打磨后上架应用商店。
- 因此从第一天起就要求：**授权干净**（不引入 GPL 强制开源的组件作为核心依赖）、架构可扩展到 iOS。

### 1.4 平台与版本

| 项 | 决策 |
|---|---|
| 首发平台 | Android |
| minSdkVersion | 26（Android 8.0，保证 HEVC 硬件编码器普及率与 API 稳定性） |
| targetSdkVersion | 跟随当年最新稳定版 |
| iOS | 架构预留（UI 与业务逻辑跨平台复用，仅需补一个原生引擎实现），本期不开发 |
| 开发环境 | 远程 Ubuntu VPS 上的 Hermes Agent，纯命令行开发（无 Android Studio 图形界面、无模拟器、无 USB 调试）；所有测试在真机进行，工作流见 §5.8 |

### 1.5 核心设计原则

1. **全本地处理**：视频永不上传，App 不需要联网权限（除崩溃统计等可选项，第一版一律不加）。
2. **硬件加速优先**：使用系统硬件编解码器，1 小时 1080p 视频的压缩耗时目标为视频时长的 1/6～1/3（中端机型）。
3. **能不重编码就不重编码**：无损操作（如音频直提、去音轨）必须用"转封装"方式实现，零质量损失、秒级完成。
4. **一次管线原则**：画面裁剪、缩放、压缩合并为**一次**转码完成，绝不让用户对同一视频做两次有损编码。
5. **失败可解释**：所有失败给出用户能看懂的原因（空间不足 / 编码器不支持 / 源文件损坏等）。

---

## 2. 功能需求总览

| 编号 | 功能 | 优先级 | 里程碑 |
|---|---|---|---|
| F1 | 视频导入 | P0 | M1 |
| F2 | 视频信息查看 | P0 | M1 |
| F3 | 视频压缩（预设 + 自定义） | P0 | M1 基础 / M2 完整 |
| F4 | 音频提取（无损 / 有损） | P0 | M3 |
| F5 | 画面裁剪（crop） | P0 | M4 |
| F6 | 任务处理体验（后台、进度、通知、取消） | P0 | M2 |
| F7 | 输出与结果（保存、对比、分享） | P0 | M1 基础 / M2 完整 |
| F19 | 应用内调试日志页（远程开发生命线） | P0 | M1 |
| F8 | 时间裁剪（trim 掐头去尾） | P1 | M4 |
| F9 | 历史记录与节省统计 | P1 | M5 |
| F10 | 批量队列处理 | P1 | M5 |
| F11 | 压缩到目标大小 | P1 | M5 |
| F12 | 帧率调整 ⚠️ | P1 | M5 |
| F13 | 去除音轨（静音视频，无损） | P1 | M5 |
| F14 | 旋转 / 翻转 | P1 | M5 |
| F15 | MP3 导出 ⚠️（授权） | P1 | M5 可选 |
| F16 | 视频合并、变速、GIF 导出 | P2 | 远期 |
| F17 | HDR 视频处理 ⚠️ | P2 | 远期 |
| F18 | iOS 版本 | P2 | M6 |

---

## 3. P0 功能详细规格

### F1 视频导入

**描述**：用户从相册或文件系统选择一个视频进入处理流程。

**实现要求**：
- 首选 **Android Photo Picker**（`ActivityResultContracts.PickVisualMedia`，限定视频类型）。这是系统级选择器，**不需要任何存储权限**。
- 提供第二入口"从文件选择"，使用 SAF（`ACTION_OPEN_DOCUMENT`），用于选取不在相册中的视频文件。
- 选取后获得 content URI，全流程使用 URI 而非文件路径（Scoped Storage 规范）。

**验收标准**：
- 在 Android 8～最新版本上均可正常选取；
- 选取后 1 秒内进入视频信息页；
- 取消选择不产生任何异常。

### F2 视频信息查看

**描述**：展示所选视频的完整技术信息，帮助用户理解"为什么大"以及压缩收益。

**展示字段**：文件名、文件大小、时长、容器格式、视频编码（H.264/HEVC/…）、分辨率、旋转角度、帧率、视频码率、音频编码、声道数、采样率、音频码率、是否 HDR。

**实现要求**：
- 用 `MediaMetadataRetriever` + `MediaExtractor` 读取；两者结合可拿到全部字段。
- ⚠️ **旋转元数据陷阱**：手机竖拍视频通常存储为 1920×1080 + rotation=90。所有 UI 展示与后续裁剪计算必须使用"显示方向"的宽高（即已应用旋转后的宽高），但引擎层要清楚原始存储方向。
- ⚠️ **HDR 检测**：读取色彩传递特性（transfer function），若为 HLG/PQ 则判定为 HDR，在信息页标注"HDR 视频"。（HDR 的处理策略见 5.7-R4。）

**验收标准**：字段与桌面端 MediaInfo 工具读数一致；竖拍视频显示为竖屏分辨率（如 1080×1920）。

### F3 视频压缩

**描述**：核心功能。将视频重新编码为更小体积，画质观感尽量无损。

**3.1 预设档位**（默认界面只显示三个预设 + 一个"自定义"入口）：

| 预设 | 分辨率 | 视频编码 | 视频码率（1080p 基准） | 音频 |
|---|---|---|---|---|
| 画质优先 | 保持原始 | HEVC | 4 Mbps | 原样复制（不重编码） |
| 均衡（默认） | 保持原始 | HEVC | 2.5 Mbps | 原样复制 |
| 极限压缩 | 长边缩至 1280（720p） | HEVC | 1.2 Mbps | AAC 96 kbps 重编码 |

码率按分辨率换算：以 1080p（约 207 万像素）为基准，目标码率 = 基准码率 × (目标像素数 / 207 万)，下限 0.8 Mbps。

**3.2 自定义参数**：
- 分辨率：保持原始 / 1080p / 720p / 480p（按长边缩放，保持宽高比，输出宽高取偶数）；
- 视频编码：HEVC（默认）/ H.264；
- 视频码率：滑杆 0.5–12 Mbps，步进 0.1；
- 音频：原样复制（默认）/ AAC 192k / 128k / 96k / 64k / 移除音轨。

**3.3 智能行为**：
- **无压缩空间提示**：若目标视频码率 ≥ 原视频码率 × 0.9，弹提示"该视频码率已较低，压缩收益有限"，允许用户坚持继续；
- **预估输出大小**：`预估大小(字节) ≈ (视频码率 + 音频码率)(bps) × 时长(秒) ÷ 8`，参数变化时实时刷新显示；
- **HEVC 编码器降级**：初始化时探测设备是否有 HEVC 硬件编码器；没有则自动降级 H.264 并把目标码率 × 1.5，同时提示用户；
- 处理前检查剩余存储空间 ≥ 预估输出大小 × 1.5，不足则报错并说明。

**验收标准**：
- 一段典型 1080p/15Mbps 手机录制讲课视频，"均衡"预设下体积降至原来的 15%–25%；
- 手机屏幕上原视频与压缩后视频肉眼对比无明显画质劣化（文字边缘清晰）；
- 输出视频时长与原视频误差 < 0.5 秒，音画同步；
- 输出可被系统相册、微信正常播放。

### F4 音频提取

**模式 A：无损直提（默认）**
- 原理：视频中的音频本身就是压缩流（几乎总是 AAC），直接抽出音频轨封装为 `.m4a`，**零转码、零质量损失、秒级完成**。
- 实现：`MediaExtractor` 选中音频轨 → `MediaMuxer`（MPEG_4 容器）写出。**不经过任何编解码器。**
- 若音频轨编码不是 AAC（极少数，如 AMR），提示用户改用模式 B。

**模式 B：有损转码**
- 用户选择目标码率：AAC 192 / 128 / 96 / 64 kbps；
- 实现：`MediaCodec` 解码音频为 PCM → `MediaCodec` AAC 编码器（设置 `KEY_BIT_RATE`）→ `MediaMuxer` 写出 `.m4a`。
- MP3 输出见 F15（系统无 MP3 编码器，属 P1）。

**验收标准**：
- 模式 A：1 小时视频提取耗时 < 10 秒；输出音频时长与原视频一致；频谱与原音轨一致（可抽查）；
- 模式 B：输出大小符合码率预期；无爆音、无变速。

### F5 画面裁剪（crop）

**描述**：框选保留区域，去掉录屏中多余画面。与压缩合并为一次转码。

**交互流程**：
1. 进入裁剪编辑器，显示视频中间时间点的一帧作为预览底图（用 `MediaMetadataRetriever.getFrameAtTime`）；
2. 预览图上叠加可拖拽、可缩放的裁剪框；四角与四边中点可拖动；
3. 比例锁定选项：自由（默认）/ 16:9 / 9:16 / 1:1 / 4:3；
4. 实时显示裁剪框对应的像素尺寸（如 1080×1420）；
5. 提供时间轴滑杆可切换预览帧（防止所选帧恰好是黑屏）；
6. 确认后进入压缩配置页（裁剪参数随任务一起提交，一次转码完成）。

**实现要求**：
- ⚠️ **三层坐标换算**，每层写成独立纯函数：
  1. UI 层：裁剪框在预览控件中的坐标（逻辑像素）；
  2. → 视频显示坐标：按预览缩放比换算为"显示方向"下的视频像素坐标（已考虑旋转）。**第 1–2 层在 Dart 层实现并配单元测试**（远程无真机调试，单测是唯一低成本验证手段）；
  3. → 引擎坐标：显示像素矩形 → Media3 `Crop` 所需 NDC 坐标（-1～1，注意 y 轴方向与存储旋转）。**第 3 层在 Android 引擎（Kotlin）内实现**，与 §5.4 契约一致——平台通道只传显示方向的像素矩形。
- 裁剪输出宽高强制取偶数（硬件编码器要求）；
- 裁剪后分辨率若再叠加"分辨率缩放"参数，先裁剪后缩放。

**验收标准**：竖屏视频、横屏视频、带旋转元数据的视频三种情况下，输出画面与框选区域一致（误差 ≤ 2 像素）；锁定比例时框选比例严格保持。

### F6 任务处理体验

- 任务开始后启动**前台服务**：Android 15+（API 35）声明 `mediaProcessing` 类型，更低版本用 `dataSync`（两者单次运行均有 6 小时系统上限，满足本场景），常驻通知显示进度百分比；
- 持有部分 WakeLock，保证熄屏后任务继续；
- App 内进度页：进度条 + 百分比 + 已用时间 + 预计剩余时间 + 取消按钮；
- 进度来源：引擎回调（Media3 Transformer 的进度 API），经 EventChannel 推送至 Flutter；
- **取消**：立即停止转码、删除临时文件与半成品输出；
- **失败处理**：错误分类映射为用户可读文案（存储不足 / 编码器初始化失败 / 源文件损坏 / 未知错误+原始错误码），失败后清理临时文件；
- 同一时间只允许一个转码任务（P0 阶段），新任务需等待或取消当前任务。

**验收标准**：熄屏 10 分钟任务不中断；进度单调递增不回跳；取消后 storage 中无残留临时文件；杀掉 App 后服务内任务的行为可预期（本期允许任务随进程终止，但重启 App 不出现僵尸任务状态）。

### F7 输出与结果

- 视频输出到相册 `Movies/VideoSlim/`，音频输出到 `Music/VideoSlim/`，通过 `MediaStore` 插入（使用 `IS_PENDING` 标志防止写入中被扫描）；
- 文件命名：`原文件名_slim_yyyyMMdd_HHmmss.mp4 / .m4a`；
- 结果页展示：原大小 → 新大小、节省百分比（大字号突出）、点击可播放预览、分享按钮（系统分享）、"删除原视频"按钮（需二次确认，且仅对相册来源提供）。

**验收标准**：输出后立即在系统相册可见；分享到微信可正常发送播放。

### F19 应用内调试日志页

**背景**：开发环境为远程 VPS，无 adb 可用。App 必须自己暴露错误信息，否则远程 AI 无法定位问题——这是本项目远程调试的唯一通道。

**要求**：
- 捕获三类信息进入日志（内存列表 + 追加写入应用私有目录文件）：
  1. Flutter 层未捕获异常（`FlutterError.onError` + `runZonedGuarded`）；
  2. 平台通道每次调用的方法名、参数摘要、响应与错误（含引擎 errorCode/errorMessage 原文与原生堆栈摘要）；
  3. 关键流程埋点：任务开始/结束、完整参数 JSON、耗时。
- 入口：设置页"调试日志"（M1 阶段可临时放首页角落）；
- 每条含时间戳；提供**一键复制全部**与**分享**按钮（分享出的纯文本直接发给 Hermes）；
- 日志轮转：最多保留最近 2000 条或 1 MB。

**验收标准**：人为制造一次失败（如选损坏文件压缩），日志页可见完整错误链路；一键复制后粘贴到聊天软件内容完整可读。

---

## 4. P1 / P2 功能简要规格

| 功能 | 规格要点 |
|---|---|
| F8 时间裁剪 | 双滑块选择起止时间；实现用 Media3 `ClippingConfiguration`；与压缩同一次管线。 |
| F9 历史记录 | 本地数据库（sqflite 或 drift）记录每次任务：时间、类型、参数、前后大小、输出 URI；首页展示累计节省空间。 |
| F10 批量队列 | 多选视频 → 统一参数 → 顺序执行队列；前台服务通知显示"第 x/n 个"。 |
| F11 目标大小压缩 | 用户输入目标大小（如 200MB），反推视频码率 = (目标大小×8 ÷ 时长) − 音频码率；低于 0.5 Mbps 时提示不可行并建议降分辨率。 |
| F12 帧率调整 ⚠️ | 目标 30/24/15fps。**开发前先验证** Media3 Transformer 当前版本对降帧率的支持；若不支持，本功能降级为"不提供"或由可选 FFmpeg 引擎承担（见 5.7-R6），不得为此改变主架构。 |
| F13 去除音轨 | Media3 `removeAudio`；无画面编辑时应走转封装路径（无损、秒级）。 |
| F14 旋转/翻转 | Media3 `ScaleAndRotateTransformation`；纯旋转 90°倍数优先尝试仅改旋转元数据（转封装，无损）。 |
| F15 MP3 导出 ⚠️ | 系统无 MP3 编码器。方案：引入社区维护的 `ffmpeg_kit_flutter_new` 作为**可选附加引擎**仅用于音频格式转换。自用无碍；**上架前必须重新评估**（该包内含 GPL 组件，见附录 C）。 |
| F16 合并/变速/GIF | 远期，架构上由引擎接口扩展方法支持，本期不设计细节。 |
| F17 HDR 处理 | 远期。本期策略见 5.7-R4（检测 + 提示 + 色调映射到 SDR）。 |
| F18 iOS | UI/业务层复用，新增 AVFoundation 引擎实现（AVAssetExportSession / AVAssetWriter），接口契约见 5.4。 |

---

## 5. 技术架构

### 5.1 技术选型与决策记录

| 层 | 选型 | 理由与备选 |
|---|---|---|
| 跨平台框架 | **Flutter（Dart）** | AI 训练语料最丰富的跨平台框架，适合 AI 辅助的零基础开发者；UI 与业务逻辑 iOS/Android 复用。备选 React Native 亦可行但视频生态更弱。 |
| Android 视频引擎 | **Jetpack Media3 Transformer**（+ media3-effect） | Google 官方、Apache 2.0 授权（无开源义务）、基于 MediaCodec 硬件加速、原生支持转码/裁剪/剪辑/效果、自带机型兼容 workaround。 |
| 无损音频直提 | **MediaExtractor + MediaMuxer** | 系统 API，纯流复制，零依赖。 |
| 有损音频转码 | **MediaCodec**（AAC 编码器） | 系统 API，标准解码→编码管线。 |
| ❌ 不采用为主引擎 | FFmpegKit / 其 fork | FFmpegKit 官方已退役（2025-04 二进制下架）；社区 fork `ffmpeg_kit_flutter_new` 虽在维护，但内含 x264/x265 等 GPL 库（上架有开源义务风险），且软件编码在手机上处理 1 小时视频需数小时，不符合核心场景。**仅**作为 F15 MP3 等边缘需求的可选附加引擎。 |
| 数据库（P1） | sqflite | 简单成熟。 |
| 状态管理 | Riverpod 或 Provider | 由 AI 按其最熟悉的选择，一经选定不再更换。 |

### 5.2 分层架构

```
┌────────────────────────────────────────────────┐
│  UI 层（Flutter Widgets）                        │
│  首页 / 信息页 / 压缩配置 / 裁剪编辑器 / 进度 / 结果 / 历史  │
├────────────────────────────────────────────────┤
│  业务层（Dart）                                   │
│  任务模型 · 参数预设 · 大小预估 · 坐标换算 · 状态管理 · 队列  │
├────────────────────────────────────────────────┤
│  引擎抽象层（Dart 接口 VideoEngine）                │
│  getVideoInfo / process / extractAudio / cancel │
│  progressStream                                  │
├──────────────────────┬─────────────────────────┤
│ Android 引擎实现（Kotlin）│  iOS 引擎实现（远期，Swift）  │
│ · Media3 Transformer   │  · AVFoundation           │
│ · MediaExtractor/Muxer │                           │
│ · MediaCodec（音频）     │                           │
│ · 前台 Service + 通知    │                           │
├──────────────────────┴─────────────────────────┤
│  存储层：MediaStore（输出）· 应用私有目录（临时文件）      │
│  · sqflite（历史，P1）                              │
└────────────────────────────────────────────────┘
```

### 5.3 引擎抽象接口（Dart，权威定义）

```dart
abstract class VideoEngine {
  /// 读取视频完整信息
  Future<VideoInfo> getVideoInfo(String uri);

  /// 统一处理入口：压缩/裁剪/时间剪辑在一次调用（一次转码）内完成。
  /// 返回 taskId，进度经 progressStream 推送。
  Future<String> process(ProcessRequest request);

  /// 音频提取（lossless=true 走流复制；false 走重编码）
  Future<String> extractAudio(AudioExtractRequest request);

  /// 取消当前任务并清理临时文件
  Future<void> cancel(String taskId);

  /// 进度流：ProgressEvent(taskId, percent 0-100, state)
  Stream<ProgressEvent> get progressStream;

  /// 设备能力探测（是否有 HEVC 硬件编码器等）
  Future<DeviceCapabilities> getCapabilities();
}
```

### 5.4 平台通道契约（Flutter ↔ Android）

- `MethodChannel`：`videoslim/engine`
- `EventChannel`：`videoslim/progress`

**方法与 JSON 参数约定**（引擎实现必须严格遵守，iOS 未来同样实现此契约）：

| 方法 | 入参（JSON） | 出参 |
|---|---|---|
| `getVideoInfo` | `{ "uri": String }` | VideoInfo JSON（字段见 §6） |
| `getCapabilities` | `{}` | `{ "hevcEncoder": bool, "h264Encoder": bool }` |
| `process` | `{ "uri", "outputFileName", "video": { "codec": "hevc"\|"h264", "bitrate": int(bps), "longEdge": int?(null=保持), "crop": {"left","top","width","height"}?(显示方向像素), "trimStartMs": int?, "trimEndMs": int? }, "audio": { "mode": "copy"\|"reencode"\|"remove", "bitrate": int? } }` | `{ "taskId": String }` |
| `extractAudio` | `{ "uri", "outputFileName", "lossless": bool, "bitrate": int? }` | `{ "taskId": String }` |
| `cancel` | `{ "taskId": String }` | `{}` |

**进度事件**：`{ "taskId", "percent": double, "state": "running"|"success"|"failed"|"cancelled", "outputUri": String?, "errorCode": String?, "errorMessage": String? }`

**错误码约定**：`INSUFFICIENT_STORAGE` / `ENCODER_UNAVAILABLE` / `SOURCE_CORRUPTED` / `CANCELLED` / `UNKNOWN`。

### 5.5 Android 端实现要点

- 依赖（版本号以开发时最新稳定版为准，三者必须同版本）：
  `androidx.media3:media3-transformer`、`media3-effect`、`media3-common`
- **压缩**：`Transformer.Builder` + `setVideoMimeType`（H.265/H.264）；码率经 `DefaultEncoderFactory` + `VideoEncoderSettings.Builder().setBitrate()` 设置；分辨率缩放用 `Presentation` 效果；裁剪用 `Crop` 效果（NDC 坐标）；时间剪辑用 `MediaItem.ClippingConfiguration`。
- **音频 copy 模式**：不设置音频 MIME、不加音频处理器，让 Transformer 对音轨走转封装路径。
- **无损音频直提**：独立实现类，MediaExtractor → MediaMuxer，不经过 Transformer。
- **前台服务**：任务在 `ForegroundService` 中执行；建通知渠道；进度节流为每秒 ≤ 2 次更新。
- **权限清单**：`FOREGROUND_SERVICE` + 对应类型权限（`FOREGROUND_SERVICE_MEDIA_PROCESSING` / `FOREGROUND_SERVICE_DATA_SYNC`，Android 14+ 缺失会直接崩溃）、`POST_NOTIFICATIONS`（Android 13+ 需运行时申请，否则进度通知不显示）、`WAKE_LOCK`。Photo Picker 选取与 MediaStore 写入自有输出文件均**无需**存储权限。
- **输出流程**：先写入应用私有缓存目录的临时文件 → 成功后经 MediaStore 拷入公共目录 → 删除临时文件。失败/取消只需删临时文件，公共目录零污染。
- **进度**：使用 Transformer 的进度查询 API 轮询（如 `getProgress`），经 EventChannel 推送。

### 5.6 项目目录结构（建议）

```
videoslim/
├─ lib/
│  ├─ main.dart
│  ├─ models/          # VideoInfo, ProcessRequest, TaskState ...
│  ├─ engine/          # VideoEngine 接口 + MethodChannel 实现
│  ├─ logic/           # 预设、大小预估、裁剪坐标换算（纯函数，可单测）
│  ├─ screens/         # 各页面
│  └─ widgets/         # 裁剪框、进度组件等
├─ android/app/src/main/kotlin/.../
│  ├─ MainActivity.kt
│  ├─ EngineChannel.kt        # 通道分发
│  ├─ TranscodeEngine.kt      # Media3 Transformer 封装
│  ├─ AudioExtractor.kt       # Extractor+Muxer / MediaCodec
│  ├─ ProcessingService.kt    # 前台服务
│  └─ MediaStoreSaver.kt
└─ test/               # logic/ 下纯函数的单元测试（坐标换算必测）
```

### 5.7 关键技术风险与对策（开发时提醒 AI）

| 编号 | 风险 | 对策 |
|---|---|---|
| R1 | **旋转元数据**：竖拍视频存储方向与显示方向不一致，裁剪坐标算错 | 信息读取即归一化出"显示宽高 + 旋转角"两组数据；坐标换算写纯函数并单测横/竖/旋转三用例 |
| R2 | 机型差异：不同芯片 MediaCodec 行为不同 | 依赖 Media3 内置 workaround；预设码率不设过低；至少 2–3 台不同芯片真机测试 |
| R3 | 长任务被系统杀死 | 前台服务 + WakeLock + 省电白名单引导（可选） |
| R4 | **HDR 输入**：新手机常录 HLG HDR，直接转码可能颜色异常 | 分阶段：M1 仅检测，遇 HDR 提示"暂不支持 HDR 视频"并终止任务；M2 起启用 Transformer 的 HDR→SDR 色调映射设置并提示"已转换为 SDR"，设备不支持映射则明确报错 |
| R5 | 大文件（>4GB）与临时空间 | 输出用 MediaMuxer/Media3 Muxer 的 MP4 正常支持大文件；处理前强制空间检查（F3.3） |
| R6 | 个别需求超出 Media3 能力（MP3、帧率等） | 一律通过"可选附加引擎"扩展，不动主架构；附加引擎与主引擎实现同一 Dart 接口 |
| R7 | FFmpeg 生态波动 | 主引擎不依赖 FFmpeg，天然免疫；附加引擎若失效仅影响 F15/F12 |

### 5.8 开发环境与远程调试工作流（硬约束）

开发在**远程 Ubuntu VPS 上的 Hermes Agent** 中进行，纯命令行。

**约束**：无模拟器（VPS 无 KVM/无显示）、无 USB/无线调试、无 `adb logcat`、无真机热重载。所有功能验证只能在项目所有者的手机上进行。

**每次迭代的开发循环**：
1. Hermes 在 VPS 上写代码 → `flutter build apk --release --split-per-abi`；
2. 交付 `app-arm64-v8a-release.apk` 到手机：**首选**通过 Hermes 接入的聊天渠道（如 Telegram）直接发送文件；备选在 VPS 上临时 `python3 -m http.server` 供手机浏览器下载（下载完立即关闭端口）；
3. 用户安装（首次需允许"安装未知应用"），按当前里程碑验收标准测试；
4. 用户把截图 + F19 日志页复制的文本 + 现象描述反馈给 Hermes；
5. Hermes 修复，回到第 1 步。

**配套要求**：
- F19 应用内日志页为 P0，与首个功能同批交付（M1）；
- 统一构建 arm64-v8a 单 ABI 包（体积小，覆盖近年绝大多数手机）；
- Flutter 默认模板的 release 构建使用 debug 签名，可直接安装；自用阶段不配置正式签名（M6 上架时再做）；
- VPS 要求：剩余磁盘 ≥ 20 GB（SDK + Gradle 缓存），内存建议 ≥ 8 GB，不足必须配 swap，否则 Gradle 构建会被 OOM 杀掉。

---

## 6. 数据模型（Dart）

```dart
class VideoInfo {
  String uri; String fileName; int fileSizeBytes; int durationMs;
  String container; String videoCodec;
  int width; int height;        // 显示方向宽高（已应用旋转）
  int rotationDegrees;          // 原始旋转元数据
  double frameRate; int videoBitrate;
  String? audioCodec; int? audioChannels; int? audioSampleRate; int? audioBitrate;
  bool isHdr;
}

class ProcessRequest {
  String uri; String outputFileName;
  String videoCodec;            // "hevc" | "h264"
  int videoBitrate;             // bps
  int? longEdge;                // null = 保持原分辨率
  CropRect? crop;               // 显示方向像素坐标
  int? trimStartMs; int? trimEndMs;
  String audioMode;             // "copy" | "reencode" | "remove"
  int? audioBitrate;
}

class CropRect { int left; int top; int width; int height; }

enum TaskState { idle, running, success, failed, cancelled }

class TaskInfo {
  String taskId; TaskState state; double percent;
  String? outputUri; String? errorCode; String? errorMessage;
  DateTime startedAt;
}

// P1
class HistoryRecord {
  int id; DateTime time; String type;   // compress | extract | ...
  int inputSize; int outputSize; String outputUri; String paramsJson;
}
```

---

## 7. UI 页面规格

设计基调：Material 3，简洁工具风，单手可完成主流程。深色模式 P2。

| 页面 | 关键元素 |
|---|---|
| S1 首页 | 三张大功能卡片：压缩视频 / 提取音频 / 裁剪画面；底部最近一次结果摘要（P1 换成历史入口 + 累计节省空间） |
| S2 视频信息页 | 视频封面帧 + F2 全部字段列表 + 底部主按钮进入所选功能配置 |
| S3 压缩配置页 | 预设三选一（分段控件）+ "自定义"展开高级参数 + 顶部实时预估大小（原大小 → 预估大小，绿色箭头）+ 开始按钮 |
| S4 裁剪编辑器 | 预览帧 + 裁剪框 + 比例选择条 + 帧位置滑杆 + 像素尺寸标签 + 下一步（进入 S3） |
| S5 进度页 | 圆形进度 + 百分比 + 预计剩余 + 取消；提示"可退出界面，处理会在后台继续" |
| S6 结果页 | 节省百分比大字 + 前后大小 + 播放预览 + 分享 + 删除原视频（二次确认） |
| S7 历史页（P1） | 任务列表 + 累计节省统计 |
| S8 设置/关于 | 版本、开源许可证列表（合规要求）、默认预设选择 |

---

## 8. 里程碑计划

> 每个里程碑 = 一个可在真机验证的交付物。验收未通过不进入下一个。

### M0 环境搭建（远程 VPS 无头模式，预计 1–3 天）
- 执行者：Hermes（详见独立文档《M0 任务书》，可整体发给它执行）。任务：环境自检（磁盘/内存/swap）→ 安装 OpenJDK 17、Android SDK 命令行工具、Flutter SDK → 接受全部许可证 → 创建 `videoslim` 项目 → 构建出 `app-arm64-v8a-release.apk` → 交付到用户手机。
- `flutter doctor` 判定标准：Flutter 与 Android toolchain 两项必须通过；Android Studio / Chrome / 已连接设备三项缺失属预期，忽略。
- 用户侧任务：手机允许"安装未知应用"，安装并打开 Flutter 默认计数器 App。
- 验收：真机上默认 App 运行正常，点按钮计数增加。

### M1 端到端最小原型（全项目最关键）
- 任务：F1 导入 → F2 信息页 → 写死"均衡"预设参数调用 F3 压缩 → 简单进度对话框 → F7 存入相册；**同批实现 F19 应用内日志页**（远程调试的生命线，必须与功能一起交付）。首次搭建平台通道 + Media3 Transformer + MediaStore 全链路。
- 验收：任选一个手机录制视频，一键压缩成功，相册可见且可播放，体积明显变小；日志页可见本次任务的关键日志且可一键复制。
- 说明：本里程碑不做前台服务、不做取消、不做参数界面，一切从简，目标只有"跑通"。预期会经历多轮"APK → 真机 → 反馈"迭代，属正常节奏。

### M2 压缩功能完整化
- 任务：F3 全部规格（预设/自定义/预估/智能提示/降级）+ F6 全部（前台服务、通知、取消、失败分类、空间检查）+ F7 完整结果页。
- 验收：F3、F6、F7 验收标准全部通过；熄屏 10 分钟长视频任务完成。

### M3 音频提取
- 任务：F4 模式 A + 模式 B，入口接入首页与信息页。
- 验收：F4 验收标准通过。

### M4 裁剪编辑器 + 时间裁剪
- 任务：F5 全部（含坐标换算纯函数与单元测试）+ F8；裁剪参数并入压缩管线一次转码。
- 验收：F5 验收标准三类视频全过；trim 起止误差 < 0.5 秒。

### M5 打磨（自用版完成）
- 任务：F9 历史、F10 批量、F11 目标大小、F13 去音轨、F14 旋转；F12/F15 按 5.7-R6 评估后决定做或砍；整体 UI 打磨与空态/异常态完善。
- 验收：连续正常使用两周无崩溃、无数据异常。

### M6 远期（上架/iOS，另行规划）
- iOS 引擎（AVFoundation 实现 5.4 契约）；应用签名、隐私政策、商店素材；附录 C 授权合规复查。

---

## 9. 风险清单（汇总）

| 风险 | 等级 | 对策索引 |
|---|---|---|
| 新手环境搭建受阻 | 高 | M0 单独成里程碑，卡住即向 AI 粘贴原始报错 |
| 远程开发无 adb，问题定位困难 | 高 | F19 应用内日志页 + §5.8 工作流；日志文本/截图反馈给 Hermes |
| 旋转元数据导致裁剪错位 | 高 | 5.7-R1 |
| HDR 输入颜色异常 | 中 | 5.7-R4 |
| 机型编码器差异 | 中 | 5.7-R2 |
| 长任务被杀 | 中 | 5.7-R3 |
| FFmpeg 生态/授权 | 低（因主架构不依赖） | 5.7-R7、附录 C |

---

## 附录 A：参数速查

- 码率-大小换算：大小(MB) ≈ 码率(Mbps) × 时长(秒) ÷ 8。
  例："均衡"档 2.5 Mbps 的 1 小时视频 ≈ 2.5 × 3600 ÷ 8 ≈ 1125 MB ≈ 1.1 GB（另加音频，copy 模式下与原视频音频同大小）。原视频若为 15 Mbps（约 6.6 GB），压缩后约为原来的 17%。
- 常见长边分辨率：1920（1080p）/ 1280（720p）/ 854（480p，取偶数则 854×480）。

## 附录 B：术语表（给项目所有者）

| 术语 | 解释 |
|---|---|
| 码率（bitrate） | 每秒视频/音频占用的数据量，直接决定体积与画质。 |
| 转码（transcode） | 解码后重新编码，有损（本 App 的压缩）。 |
| 转封装（transmux/remux） | 只换容器不重编码，无损且极快（本 App 的音频直提、去音轨）。 |
| HEVC / H.265 | 新一代视频编码，同画质下体积约为 H.264 的 50–60%。 |
| HDR | 高动态范围视频，处理时需特殊颜色转换。 |
| SAF | Android 存储访问框架，选取任意位置文件的系统机制。 |
| NDC | 归一化设备坐标（-1～1），Media3 裁剪效果使用的坐标系。 |

## 附录 C：授权合规备忘

| 组件 | 授权 | 结论 |
|---|---|---|
| Flutter / Media3 / AndroidX | BSD / Apache 2.0 | 商用与上架无义务风险 ✅ |
| ffmpeg_kit_flutter_new（仅当启用 F15） | 含 x264/x265 等 GPL 组件 | 自用 ✅；**上架前必须处理**：或移除该依赖、或改用无 GPL 组件的构建、或整 App 以 GPL 开源（苹果商店对 GPL 兼容性差，慎选） |
| 设置页义务 | — | 上架版需展示开源许可证列表（用 Flutter 许可证页组件自动生成） |

---

*文档结束。开发从 M0 开始，逐里程碑推进。*
