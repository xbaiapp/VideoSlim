# 视频瘦身（VideoSlim）产品需求文档（PRD）

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.15（记录M4-B RED→GREEN与候选验证阶段） |
| 日期 | 2026-07-23 |
| 状态 | M3 `ACCEPTED — private scope`；`1.7.0+23 / 7c49e57...`已实现F20/C1a但所有者跳过真机验收（未记PASS）；D1确认Pixel HEVC有效配置500 kbps后运行期明显过冲；M4-B/F8连续单段时间裁剪已完成RED→GREEN和完整自动门禁，`1.8.0+24`等待唯一复审，C1b/F21/F22与M4-C仍未授权 |
| 目标读者 | AI 编程助手 + 项目所有者 |
| 产品名 | 视频瘦身（VideoSlim，工作代号，可随时更换） |

---

## 0. 如何使用本文档（给项目所有者）

本文档为"零编程经验 + AI 辅助开发"模式设计。使用规则：

1. **按里程碑推进**：不要把整个文档一次性丢给 AI 让它"把 App 写完"。每次只做一个里程碑（见第 8 章）；按明确范围获得接受决定后再进入下一个。已转入 non-blocking hardening 的扩展矩阵不反向阻止后续里程碑，但未经实测不得宣称 PASS 或扩大支持范围。
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
| F5 | 画面裁剪（crop） | P0 | M4-A |
| F6 | 任务处理体验（后台、进度、通知、取消） | P0 | M2 |
| F7 | 输出与结果（保存、对比、分享） | P0 | M1 基础 / M2 完整 |
| F19 | 应用内调试日志页（远程开发生命线） | P0 | M1 |
| F8 | 时间裁剪（trim 掐头去尾） | P1 | M4-B |
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
| F20 | 低收益/可能变大提示与条件式建议目标 | P1 | C轨 C1a/C1b |
| F21 | 本机编码器能力诊断 | P1 | C轨 C2 |
| F22 | 条件式高级编码档（QP/CQ/AV1/都不做） | P2 | C轨 C3 |
| F23 | 同源多段时间编辑 | P1 | M4-C |

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

**3.1 预设档位**（未裁剪时仍只显示三个常规预设 + 一个"自定义"入口；已设置裁剪时额外显示"保持画质（仅裁剪）"）：

| 预设 | 分辨率 | 视频编码 | 视频码率 | 音频 |
|---|---|---|---|---|
| 保持画质（仅裁剪） | 保持裁剪后尺寸，不叠加缩放（`longEdge = null`） | HEVC | 按下述保持画质公式 | 原样复制（不重编码） |
| 画质优先 | 保持最终裁剪后尺寸 | HEVC | 4 Mbps（1080p 基准） | 原样复制（不重编码） |
| 均衡（默认） | 保持最终裁剪后尺寸 | HEVC | 2.5 Mbps（1080p 基准） | 原样复制 |
| 极限压缩 | 裁剪后长边缩至 1280（720p） | HEVC | 1.2 Mbps（1080p 基准） | AAC 96 kbps 重编码 |

常规预设码率按**最终输出像素**（裁剪后再应用分辨率缩放）换算：以 1080p（约 207 万像素）为基准，目标码率 = 基准码率 × (最终输出像素数 / 207 万)，下限 0.8 Mbps；预估输出大小使用同一最终参数实时重算。

"保持画质（仅裁剪）"仅在已设置裁剪时显示，目标是肉眼接近原画质，不宣称无损。其 HEVC 目标码率 = `源视频码率 × (裁剪后像素 ÷ 源像素) × 1.2`，并 clamp 到 `[min(2 Mbps, 源码率), min(源码率, 20 Mbps)]`；源码率低于 2 Mbps 时该区间退化为源码率。源码率读取失败时，退化为按"画质优先"档最终像素换算结果 × 1.5。设备无 HEVC 硬件编码器时沿用 capability fallback：改用 H.264，并将上述目标码率 × 1.5。用户移除裁剪时若当前选中本档，自动回退到"均衡"。UI 文案："保持画质（仅裁剪）— 输出接近原画质，体积大致随裁掉的面积减少"。

**3.2 自定义参数**：
- 分辨率：保持原始 / 1080p / 720p / 480p（按长边缩放，保持宽高比，输出宽高取偶数）；
- 视频编码：HEVC（默认）/ H.264；
- 视频码率：滑杆 0.5–12 Mbps，步进 0.1；
- 音频：原样复制（默认）/ AAC 192k / 128k / 96k / 64k / 移除音轨。

**当前范围说明**：M2 的“自定义”是指用户可修改上述编码参数；视频压缩量继续由设备硬件 Encoder 的**目标平均码率（VBR）**控制。当前规格未包含“画质百分比/恒定质量（CQ/CRF）”“CBR/VBR 模式切换”或“按原文件百分比压缩”。不得新增 CBR，也不得因为实际输出码率没有精确命中目标值而硬拦截发布；目标码率只用于编码配置、体积估算与收益提示。“按目标文件大小压缩”已列为 F11，计划在 M5 实现。

**3.3 智能行为**：
- **F20/C1a低收益提示（候选已实现）**：仅当源文件大小已知且`estimatedOutputMaxBytes × 100 > source.fileSizeBytes × 85`时提示预计节省低于15%、甚至可能变大；`fileSizeBytes <= 0`不触发。复用一个确认弹窗、不静默改写目标、不增加体积发布门禁；有crop始终提供保持画质/继续/取消，无crop提供暂不处理/继续；
- **F20/C1b条件建议（未授权）**：只有D1、显式码率来源契约和逐codec真机校准满足后才允许显示建议值，且必须由用户显式采用；完整边界见`docs/VideoSlim-AI-Handoff-2026-07-23.md` §7.1；
- **预估输出大小**：`预估大小(字节) ≈ (视频码率 + 音频码率)(bps) × 时长(秒) ÷ 8`，参数变化时实时刷新显示；
- **HEVC 编码器降级**：初始化时探测设备是否有 HEVC 硬件编码器；没有则自动降级 H.264 并把目标码率 × 1.5，同时提示用户；
- 处理前检查剩余存储空间 ≥ 预估输出大小 × 1.5，不足则报错并说明。

**3.4 M2 接受范围与扩展 hardening**：
- M2 已按项目所有者的 Pixel 当前私有使用场景接受，状态为 `ACCEPTED — private scope`；这允许进入 M3，但不是生产发布或多设备支持声明；
- “接近 6 小时”“接近 50 GB”“输出超过 4 GB”、严格同源 A/B/C、software-only、多 Provider 与多 SoC 均转为 non-blocking hardening。未实际执行时不得标为 PASS；
- 超出当前已验证私有场景时，UI 应说明处于 best-effort 范围，展示预估输出大小和所需空间，不承诺完成；不同芯片、Android 版本和 Provider 补齐边界证据后才能扩大保证范围；
- 输入通过 `content://` 直接读取，不复制源文件；处理期间仍会同时存在私有临时输出与正在发布的公共输出，空间检查必须覆盖峰值重叠占用并保留安全余量。

**验收标准**：
- 一段典型 1080p/15Mbps 手机录制讲课视频，"均衡"预设下体积降至原来的 15%–25%；
- 手机屏幕上原视频与压缩后视频肉眼对比无明显画质劣化（文字边缘清晰）；
- 输出视频时长与原视频误差 < 0.5 秒，音画同步；
- 输出可被系统相册、微信正常播放。

### F4 音频提取

**共同输入与输出规则（M3）**：
- 始终使用源文件顺序中的**第一条音频轨**；M3 不提供多音轨或多语言音轨选择。
- 支持单声道与立体声；遇到超过 2 声道的第一音轨时稳定返回 `AUDIO_CHANNEL_LAYOUT_UNSUPPORTED`，不隐式下混。
- 视频没有音轨时，入口与引擎均稳定返回 `AUDIO_TRACK_MISSING`，不得启动空任务或产出空文件。
- 默认发布到 MediaStore `Music/VideoSlim/`；用户可通过 SAF 选择并持久化自定义文件夹。发布、取消与异常恢复沿用 M2 的所有权和清理边界；SAF 即使报告 `UNKNOWN_LENGTH` 或看似可信的长度元数据，也必须对已关闭输出执行有界全量回读并与源临时文件 bytes 严格相等，无法证明完整性时不得进入 `PUBLISHED`。
- 输出统一为 `.m4a`（`audio/mp4`）。M3 不做 MP3、多音轨选择、trim、音量/降噪、声道混音、采样率选择或标签/封面编辑。

**模式 A：AAC copy（默认）**
- 只接受有明确 profile 证据的 AAC-LC、HE-AAC 或 HE-AAC-v2，Android 轨道 MIME 必须为 `audio/mp4a-latm`；其他音频编码稳定返回 `AUDIO_COPY_UNSUPPORTED`，提示用户显式改用 AAC 模式，不得静默降级。
- 使用 `MediaExtractor` 选中第一音轨，逐 sample 写入 `MediaMuxer` 的 MPEG-4 容器形成 `.m4a`；这是纯 sample copy，**不创建音频 Decoder 或 Encoder**，零转码、零质量损失。
- 保留 sample 相对时间戳并把第一条有效音频 sample 归零；源 PTS 必须严格单调，重复或回退立即失败，禁止用 clamp/递增改写掩盖坏源。copy 当步逐 sample 使用有界 `readSampleData` 证明索引 payload 可完整物理读取：API 28+ 的物理读数必须与 `sampleSize` 严格相等；API 26–27 使用“上限 + 1 sentinel byte”，拒绝负数、零、短读、超索引、超上限及任何填满 sentinel 的歧义读数。单一 copy 不允许在 indexed 与 legacy 证据模式间切换。
- 发布前把源文件物理预扫描、实际 copy 结果与输出文件物理复扫绑定为同一 payload 契约。三者 sample count 与 payload-byte aggregate 必须严格相等；API 28+ 同时要求 indexed-size 证据一致，API 26–27 只在三次独立 sentinel-bounded 物理扫描完全一致时通过。该检查是 profile、严格 PTS、完整 timeline、frame-aware 末帧覆盖和 sparse-gap 检查之外的附加门禁，不得替代或放宽它们。

**模式 B：AAC 强制重编码**
- 固定提供 AAC-LC `192 / 128 / 96 / 64 kbps` 四档，对应请求值 `192000 / 128000 / 96000 / 64000` bps；默认 128 kbps。
- 使用 Media3 Transformer 的 **audio-only** 管线，移除视频并强制走音频 Decoder → AAC Encoder；即使源轨已经是 AAC，也不得 transmux 或忽略目标码率。
- 允许可被 Media3 音频 Decoder 读取的非 AAC 源（例如 Opus WebM）由用户显式选择本模式后转为 AAC-LC。F19 必须记录实际音频 Decoder、实际 AAC Encoder、请求码率、源/输出 MIME、采样率与声道。
- 目标码率是 Encoder 配置与估算依据，不要求实际输出码率逐字节精确命中，也不得把码率偏差作为发布硬拦截；结构性无效输出才返回 `AUDIO_OUTPUT_INVALID`。
- copy 与强制重编码输出都必须按真实 sample span 加末帧时长计算覆盖时长，并使用 AAC frame、编码延迟和毫秒取整相关容差与源音轨比较；不得使用固定 1 秒容差，尤其不得让亚秒源的一帧/少量帧截断结果通过。

**验收标准**：
- 30–120 秒 AAC 短片 copy 连续 3 次，输出均可播放、时长一致，且 F19 证明未创建音频 Decoder/Encoder；
- 同一短片分别强制重编码为 192/128/96/64 kbps，F19 证明实际 Decoder/Encoder 已创建，输出 bytes 按 `192 > 128 > 96 > 64` 单调，无爆音、无变速；
- mono 不被错误改成 stereo；Opus WebM 的 copy 被稳定阻止、显式 AAC 转码成功；无音轨与 >2 声道得到上述稳定错误；
- 后台、锁屏、转换阶段取消、发布阶段取消、默认 `Music/VideoSlim` 与自定义 SAF 均通过；
- 1 小时 AAC 视频 copy 耗时 < 10 秒，输出音频时长与源音轨一致；全部结果按 `docs/m3-device-acceptance.md` 留存真实设备与 F19 证据。

### F5 画面裁剪（crop）

**描述**：裁剪是压缩前的可选步骤。用户可先框选保留区域，再配置输出并开始；不想裁剪时直接走现有压缩流程。裁剪、缩放与压缩必须在同一次转码中完成，禁止自动生成裁剪中间视频再做第二次有损编码。

**双入口工作流**：
1. 入口 A（压缩视频）：S2 信息页 → S3 输出配置页 → 可选进入 S4 裁剪编辑器 → 确认后回到 S3 → 开始；
2. 入口 B（裁剪画面）：S2 信息页 → S4 裁剪编辑器 → S3 输出配置页，并默认选中"保持画质（仅裁剪）" → 开始；
3. 不裁剪：S2 信息页 → S3 输出配置页 → 开始，现有行为不变；
4. S3 是唯一输出配置页。S4 确认后必须回到 S3，不得直接开始任务；用户开始前始终能看到裁剪区域、输出档位和预估大小的完整摘要；
5. S3 顶部提供"裁剪画面（可选）"入口。设置后显示如 `已裁剪 1080×1420` 的徽章以及"编辑 / 移除"操作；移除裁剪时，若当前档位为"保持画质（仅裁剪）"，回退到"均衡"。

**S4 裁剪编辑器**：
- 通过 §5.4 的只读 `getPreviewFrame` 获取显示方向 JPEG 预览帧；默认取中间时间点，帧位置滑杆需节流并取最近关键帧，避免拖动时阻塞；
- 预览图上叠加可拖拽、可缩放裁剪框，四角与四边中点均可拖动；
- 比例锁选项为自由（默认）/ 16:9 / 9:16 / 1:1 / 4:3；自由模式允许任意位置、大小和宽高比，不得默认锁定比例；
- 实时显示显示方向像素尺寸；最小裁剪区域为 64×64 视频像素；本期不含旋转或翻转 UI（F14 另议）。

**实现要求**：
- Flutter 沿用现有 typed workflow / interaction 互斥模型，增加裁剪编辑 phase，不引入新状态框架；
- Dart 纯逻辑负责预览控件坐标 ↔ 显示方向像素矩形，包括缩放/平移、比例锁、边界夹取、最小尺寸和输出偶数化预检，全部配单元测试；
- `compression_planner` 接受 `CropRect`，所有预设码率与预估大小按裁剪后再缩放得到的最终输出像素实时计算，并实现 §3.1 的"保持画质（仅裁剪）"公式；
- `process.video.crop = {left, top, width, height}` 的 wire 形状保持不变，档位标识不进入 wire；snapshot / retryRequest 必须原样 round-trip crop keys，不升级 journal 版本；
- Android 纯函数 mapper 将显示方向像素矩形除以显示方向宽高，映射为 Media3 `Crop` NDC 参数（注意 y 轴翻转）并计算偶数化输出宽高。默认不做旋转补偿：Media3 effects 预期在按旋转元数据转正后的帧上运行；`rotationDegrees` 只用于校验与 F19 记录。必须先用 0/90/180/270° 样本做单测与真机验证，只有真实旋转样本错位时才在 mapper 内增加补偿并记录结论；
- `TranscodeEngine` effects 顺序固定为 `Crop → Presentation`，`longEdge` 作用于裁剪后宽高；无 crop 时与 `1.4.3+18` 路径一致；
- UI 层可以夹取裁剪框；native preflight 对越界、零/负面积、偶数化后小于编码器下限的 crop 必须 fail closed，返回 §5.4 的 `INVALID_CROP`，不得静默夹取；
- publication / recovery / registry / `ProcessingService` / `finishOnce` / 通知 / 音频提取 / decoderMode 兼容重试 / MediaStore 与 SAF 发布 / 空间预检均不改；保持单服务、单任务、`finishOnce` 唯一终止门、硬件 VBR、main-affine 与 `commit()` durability 语义。

**真机验收**：
1. 横屏视频裁中部区域，输出与框选一致，误差 ≤ 2px；
2. 竖拍视频（存储 1920×1080 + rotation 90）一致；
3. rotation 180 / 270 样本各一；
4. 16:9 / 9:16 / 1:1 / 自由比例各一次；
5. 裁剪 + 极限压缩（720p）组合证明先裁后缩、尺寸正确且无拉伸；
6. 入口 B + 保持画质：对比开头/中段/结尾文字边缘，肉眼无差，体积变化与裁除面积比例大致相符；
7. 未裁剪普通压缩与 `1.4.3+18` 一致；
8. 裁剪任务取消 / 后台 / 锁屏 / 进程恢复各一次；
9. S3 预估大小与实际输出误差 < 25%；
10. F19 记录显示像素矩形、NDC 参数、最终输出宽高和所选档位。

**停止条件**：若真机出现无法用坐标换算解释的画面错位，停止并报告项目所有者；不得改用两次转码中间文件绕过。复审预算遵守 `AGENTS.md` B 节：1 次实现 + 1 次修订 + 1 轮复审。

### F6 任务处理体验

- 任务开始后启动**前台服务**：Android 15+（API 35）声明 `mediaProcessing` 类型，更低版本用 `dataSync`，常驻通知显示进度百分比；长任务仍受系统 wall-time/OEM 策略约束，不能仅凭 API 理论上限宣称 6 小时保证；
- 持有部分 WakeLock，保证熄屏后任务继续；
- App 内进度页：进度条 + 百分比 + 已用时间 + 预计剩余时间 + 取消按钮；
- 进度来源：视频/AAC 转码使用 Media3 Transformer 进度，音频 copy 按已复制 sample 时间戳/源音轨 span 计算；统一经 EventChannel 推送至 Flutter，事件必须携带 `taskKind`；
- **取消**：立即停止当前处理、删除临时文件与半成品输出；
- **失败处理**：错误分类映射为用户可读文案（存储不足 / 编码器初始化失败 / 源文件损坏 / 未知错误+原始错误码），失败后清理临时文件；
- **异常退出恢复清理（M2 必做）**：每个任务在应用私有持久化存储中维护最小任务日志，至少记录 `taskId`、阶段、私有临时文件标识、已分配的 MediaStore URI（如有）和开始时间；任何 API 路径（含 Android 8～9 legacy）在公共 URI 分配后必须先同步持久化该 URI，再查询/记录丰富 target 字段、复制 bytes 或执行后续发布工作。URI-only callback 合法地把 `TRANSFORMING` 推进到 `ALLOCATED`；随后的完整 target callback 只能以同一 URI、media kind、实际文件名及 canonical legacy path 单调 enrich 为 `PUBLISHING`，冲突 URI/名称/路径必须失败。任务成功完成且临时文件已删除后再清除该记录；
- App/处理服务启动时先与实际活动任务对账。若任务日志存在但已无对应活动任务，可删除由私有目录边界证明所有权的临时文件；公共输出只有在**当前对象**所有权可被不可变证据证明时才允许删除。`ALLOCATED` 阶段、相同 SAF URI、相同 legacy MediaStore URI/名称/路径都不单独授予删除权。Android 8～9 无法区分原半成品与同 URI/路径被替换写入的新对象，因此所有未发布 legacy 记录一律进入 durable quarantine、释放 active journal 槽但不删除公共对象；`PUBLISHED` 只保留输出并清理过期日志；
- 启动对账后扫描**仅限 App 自有**的 `cache/transcode/`：删除未被当前活动任务引用的孤儿文件；清理为 best-effort，失败写入 F19 日志但不得阻塞启动或覆盖业务结果；严禁扫描或删除用户其他目录、无日志归属的公共媒体以及已经成功发布的输出；
- 同一时间只允许一个媒体处理任务（视频压缩或音频提取，P0 阶段），新任务需等待或取消当前任务。
- Flutter 的 snapshot/源 metadata 恢复、初始音频/视频目的地预检、音频普通/AAC 重试和视频普通/兼容重试都属于同一全局 interaction lock；每次 `await` 后必须重新证明 generation、EventChannel、源、目的地 revision 与发布状态所有权。恢复 snapshot 查询失败时，native 任务存在性未知，必须保守保持全局锁，直到原生明确返回 no-task、接受取消或应用重启重新对账；仅源 metadata 恢复失败不能丢弃已证明的 native task。EventChannel 在 native task 尚未提交时**关闭**仍使预检 generation 失效并释放锁；但 task 已保留后收到可恢复/格式错误必须 snapshot reconcile/rebind 或保持 uncertain lock，不得调用 `_failGeneration` 遗忘仍可能运行的 native task，迟到的 MethodChannel task ID/结果仍须按 generation 关联。并发 reconcile 以最新 query epoch 为准，匹配 task/kind 的进度是 native liveness 证据，可使迟到的 null/error snapshot 失效。
- task ID 或恢复 metadata 未确定期间的进度暂存必须按 generation/task/kind 有界 coalesce：每个保留 key 最多保存最新 running 与首个 terminal，terminal 后忽略 running 回退；全局 key 数有固定上限。恢复后按接收序回放，并以 snapshot 的 task/kind/phase/percent 判定新旧，禁止无界 `List` 随事件量增长。

**验收标准**：M2 已按 Pixel 当前私有使用场景接受；进度、正常失败/取消清理、任务恢复和发布所有权边界保持为产品契约。M3 当前服务器门禁只能证明源码、host JVM/Flutter 行为与 APK 可组装，不能替代 API 26–28/Pixel/GrapheneOS 的物理设备验收。接近 6 小时、接近 50 GB、持续后台至完成、转码/发布强杀、连续 10 次异常中断、多 Provider 与多 SoC 的组合矩阵保留为 non-blocking hardening：未实际执行不得标为 PASS，补证前不得据此扩大生产支持范围，但不阻止 M3 候选准备。

### F7 输出与结果

- 视频输出到相册 `Movies/VideoSlim/`，音频输出到系统音频 `Music/VideoSlim/`，通过 `MediaStore` 插入（使用 `IS_PENDING` 标志防止写入中被扫描）；视频和音频都允许用户显式改用持久化授权的自定义 SAF 文件夹；
- 默认文件命名使用经清洗、UTF-8 总长不超过 240 bytes 的 `<safe stem>`、毫秒级处理时间和四位随机十六进制 token：视频为 `<safe stem>_slim_<h265|h264>_target<kbps>k_<yyyyMMdd_HHmmssSSS>_<token>.mp4`；音频直提为 `<safe stem>_audio_copy_<yyyyMMdd_HHmmssSSS>_<token>.m4a`；AAC 重编码为 `<safe stem>_audio_aac_target<kbps>k_<yyyyMMdd_HHmmssSSS>_<token>.m4a`。视频 codec 必须反映能力 fallback 后的最终计划；`target` 只表示目标码率，不表示硬件 VBR 的实测平均码率；
- 随机 token 只是第一层冲突缓解。MediaStore/SAF provider 在已有同名文件时继续追加 collision suffix（例如 ` (1)`）；结果页、snapshot 和 F19 必须使用 provider 实际分配的文件名；
- 视频压缩只保留来源中存在且可可靠解析的拍摄/容器创建时间与 GPS 经纬度，不复制设备型号、软件版本、Apple 私有 atom、Pixel `mett`、完整 XMP/mdta 或其他厂商字段；来源字段缺失或无效时保持缺失，不得使用处理时间、文件 mtime、设备当前位置或目录信息伪造；
- 受支持的来源时间/GPS在同一次 Media3 转码的 MP4 mux 中写入。Transformer 完成后、分配公开 MediaStore/SAF 输出前必须回读临时 MP4：时间按容器秒精度、位置按 `0.0001°` 容差核验；已承诺字段缺失或不匹配时返回 `CAPTURE_METADATA_FAILED`，不发布不完整结果；
- 默认 MediaStore 视频发布时，仅从已验证的来源拍摄时间设置 `DATE_TAKEN`；音频不继承视频时间/GPS。自定义 SAF 只承诺 MP4 内部字段，不承诺图库索引日期；
- 成功后必须在结果页顶部以高显著性文案显示实际保存位置与文件名：视频为“系统相册 > Movies > VideoSlim > 文件名”，音频为“系统音频 > Music > VideoSlim > 文件名”，自定义 SAF 显示用户可理解的文件夹标签；不得只显示原始 `content://` URI 或模糊的“保存成功”。同时提供打开/播放主按钮；
- 结果页展示：原大小 → 新大小、节省百分比（大字号突出）、实际文件名与人类可读保存位置、点击可播放预览、分享按钮（系统分享）、"删除原视频"按钮（需二次确认，且仅对相册来源提供）。

**验收标准**：默认输出后立即在对应系统媒体集合可见；用户无需自行搜索即可在 10 秒内从成功页确认实际保存位置并打开输出；自定义 SAF 输出同样显示实际文件名与文件夹标签；分享时使用真实媒体 MIME 并可正常发送播放。

### F19 应用内调试日志页

**背景**：开发环境为远程 VPS，无 adb 可用。App 必须自己暴露错误信息，否则远程 AI 无法定位问题——这是本项目远程调试的唯一通道。

**要求**：
- 捕获三类信息进入日志（内存列表 + 追加写入应用私有目录文件）：
  1. Flutter 层未捕获异常（`FlutterError.onError` + `runZonedGuarded`）；
  2. 平台通道每次调用的方法名、参数摘要、响应与错误（含引擎 errorCode/errorMessage 原文与原生堆栈摘要）；
  3. 关键流程埋点：任务开始/结束、完整参数 JSON、耗时。
- 入口：设置页"调试日志"（M1 阶段可临时放首页角落）；
- 每条含时间戳；提供**复制日志**与**分享日志**按钮。小日志复制全部；超长日志只复制最近128 KiB、从完整UTF-8日志行开始并附截断提示，避免Android Binder超限；完整日志始终通过文件分享发送给 Hermes；
- 日志轮转：最多保留最近 2000 条或 1 MB。
- 日志、截图和验收文档不得保留任何真实凭据；凭据值始终替换为 `[REDACTED]`。

**验收标准**：人为制造一次失败（如选损坏文件压缩），日志页可见完整错误链路；小日志复制后内容完整可读；约1 MiB日志复制不得触发 `TransactionTooLargeException`，应保留最新完整日志行并明确提示截断；“分享日志”导出的文件仍包含完整日志。

---

## 4. P1 / P2 功能简要规格

| 功能 | 规格要点 |
|---|---|
| F8 时间裁剪 | M4-B已授权为当前唯一代码项；使用双滑块选择连续起止时间，Media3 `ClippingConfiguration`与crop/缩放/压缩走同一次管线。不得顺带实现多段、乱序或跨文件。 |
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
| F20 低收益提示/建议目标 | C1a 仅提示、不改值：用保守输出上界与已知源大小判断预计节省是否低于15%；C1b为后置条件项，要求显式码率来源、D1/C2证据、合法码率范围与逐codec真机校准。不得承诺或强制输出一定小于源文件。 |
| F21 编码器能力诊断 | F19调试区只读枚举硬件编码器的mime、VBR/CBR/CQ、QP bounds、bitrate/complexity range等；不创建任务、不改变转码行为，但仍按完整小型代码任务构建、测试、复审和真机验证。 |
| F22 条件式高级编码档 | 仅在F21本机证据后由所有者从QP钳制、CQ、AV1、都不做中选择，至多实施一个；CQ需明示修订VBR产品不变量，AV1按独立格式功能评估。软件x264/x265/SVT-AV1 CRF默认不做。 |
| F23 同源多段时间编辑 | 依赖M4-B真机通过；Media3 `Composition + EditedMediaItemSequence`一次导出有序、不重叠的同源片段，不支持段乱序。跨文件拼接归F16，不在F23默认范围。 |

---

## 5. 技术架构

### 5.1 技术选型与决策记录

| 层 | 选型 | 理由与备选 |
|---|---|---|
| 跨平台框架 | **Flutter（Dart）** | AI 训练语料最丰富的跨平台框架，适合 AI 辅助的零基础开发者；UI 与业务逻辑 iOS/Android 复用。备选 React Native 亦可行但视频生态更弱。 |
| Android 视频引擎 | **Jetpack Media3 Transformer**（+ media3-effect） | Google 官方、Apache 2.0 授权（无开源义务）、基于 MediaCodec 硬件加速、原生支持转码/裁剪/剪辑/效果、自带机型兼容 workaround。 |
| 无损音频直提 | **MediaExtractor + MediaMuxer** | 系统 API，纯流复制，零依赖。 |
| 有损音频转码 | **Media3 Transformer audio-only**（底层 MediaCodec） | 强制音频解码→AAC-LC 编码，固定 192/128/96/64 kbps；即使 AAC→AAC 也禁止静默 transmux。 |
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

  /// 统一视频处理入口：压缩/裁剪/时间剪辑在一次调用（一次转码）内完成。
  /// 返回 taskId，进度经 progressStream 推送。
  Future<String> process(ProcessRequest request);

  /// 提取第一条音轨：mode=copy 仅对 AAC-LC/HE-AAC 做 sample copy；
  /// mode=aac 用 Media3 audio-only 强制重编码为指定 AAC-LC 码率。
  Future<String> extractAudio(AudioExtractRequest request);

  /// 取消当前任务并清理临时文件/未完成发布条目
  Future<void> cancel(String taskId);

  /// 进度流：每个 ProgressEvent 必须携带 taskKind。
  Stream<ProgressEvent> get progressStream;

  /// 恢复当前或最近的可重连任务；snapshot 必须携带 taskKind。
  Future<TaskSnapshot?> getTaskSnapshot();

  /// 设备能力探测（是否有 HEVC 硬件编码器等）
  Future<DeviceCapabilities> getCapabilities();
}
```

### 5.4 平台通道契约（Flutter ↔ Android）

- `MethodChannel`：`videoslim/engine`
- `EventChannel`：`videoslim/progress`

**方法与 JSON 参数约定**（引擎实现必须严格遵守并拒绝额外/缺失 key，iOS 未来同样实现此契约）：

| 方法 | 入参（JSON） | 出参 |
|---|---|---|
| `getVideoInfo` | `{ "uri": String }` | VideoInfo JSON（字段见 §6） |
| `getPreviewFrame` | `{ "uri": String, "timeMs": int }` | 显示方向 JPEG 字节（Flutter `Uint8List`；长边 ≤ 1280；取最近关键帧） |
| `getCapabilities` | `{}` | `{ "hevcEncoder": bool, "h264Encoder": bool }` |
| `process` | `{ "uri", "outputFileName", "destination": { "treeUri": String?, "label": String }, "video": { "codec": "hevc"\|"h264", "decoderMode": "hardware"\|"software", "bitrate": int(bps), "longEdge": int?(null=保持), "crop": {"left","top","width","height"}?(显示方向像素), "trimStartMs": int?, "trimEndMs": int? }, "audio": { "mode": "copy"\|"reencode"\|"remove", "bitrate": int? } }` | `{ "taskId": String }` |
| `extractAudio` | `{ "uri": String, "outputFileName": String, "destination": { "treeUri": String?, "label": String }, "audio": { "mode": "copy"\|"aac", "bitrate": int? } }` | `{ "taskId": String }` |
| `cancel` | `{ "taskId": String }` | `{}` |
| `getTaskSnapshot` | `{}` | TaskSnapshot JSON 或 `null` |

`getPreviewFrame` 是裁剪编辑器专用的只读查询：不创建任务、不进入任务生命周期、不写 journal / snapshot / registry，也不改变 single-flight 状态。native 返回应用旋转后的显示方向 JPEG，并把长边压缩到 ≤ 1280；帧位置按最近关键帧读取，Flutter 滑杆调用必须节流。

`process` 的 wire 契约在 M4-A **零变更**：`video.crop` 继续使用 M1 已预留的显示方向像素矩形；预设档位在 Dart 侧折算为具体 codec / bitrate / longEdge，不把档位标识发送到 native。`retryRequest` 与 snapshot 必须原样 round-trip crop keys，补充断言即可，不升级 journal 版本。

`extractAudio` 的 request 是单一事实源，不存在旧的 `lossless` 布尔字段：
- `audio.mode="copy"` 时 `audio.bitrate` 必须为 `null`；只接受第一条音轨为 AAC-LC/HE-AAC（`audio/mp4a-latm`）。
- `audio.mode="aac"` 时 `audio.bitrate` 必须严格为 `192000|128000|96000|64000`；Media3 audio-only 必须强制实际重编码。
- 默认 destination 为 `{ "treeUri": null, "label": "系统音频 > Music > VideoSlim" }`；自定义保存使用已持久化授权的 SAF tree URI 与用户可读 label。
- `outputFileName` 必须是安全的 `.m4a` 名称；默认 `<safe stem>_slim_yyyyMMdd_HHmmss.m4a`，provider collision suffix 仅作为第二层保护。

**任务类型**：`taskKind` 只允许 `"video_compression"|"audio_extraction"`。native 新建的每个 progress event 与 snapshot 都必须显式携带；Dart 仅为升级前缺少该字段的旧 snapshot 回退到 `video_compression`，不得对新事件静默猜测。

**进度事件**：

```json
{
  "taskKind": "video_compression|audio_extraction",
  "taskId": "String",
  "percent": 0.0,
  "state": "running|success|failed|cancelled",
  "phase": "preparing|encoding|publishing|cancelling|finished",
  "outputUri": null,
  "outputFileName": null,
  "outputLocationLabel": "String",
  "errorCode": null,
  "errorMessage": null
}
```

视频任务可继续携带 `videoDecoderMode` 与 `actualVideoEncodingMode`；音频任务不得据此显示视频 Decoder/Encoder 状态。百分比、状态与阶段仍遵守 M2 的单调和终态规则。

**TaskSnapshot**：至少携带进度事件的全部公共字段，加上 `sourceUri`、`startedAtEpochMs` 与 `retryRequest`。`taskKind=video_compression` 时 `retryRequest` 严格解析为 `ProcessRequest`；`taskKind=audio_extraction` 时严格解析为 `AudioExtractRequest`，两者不得串线。

**M3 稳定错误码**：
- `AUDIO_TRACK_MISSING`：没有第一条可提取音轨；
- `AUDIO_COPY_UNSUPPORTED`：copy 的第一音轨没有可接受的 AAC-LC/HE-AAC/HE-AAC-v2 profile 证据；
- `AUDIO_CHANNEL_LAYOUT_UNSUPPORTED`：第一音轨超过 2 声道；
- `AUDIO_DECODING_FAILED` / `AUDIO_ENCODING_FAILED`：实际音频 Decoder/AAC Encoder 失败或不可用；
- `AUDIO_OUTPUT_INVALID`：临时输出为空、没有可识别 AAC 音轨或明显截断。

**M4-A 稳定错误码**：
- `INVALID_CROP`：裁剪区域越界、零/负面积或偶数化后小于编码器下限；用户文案固定为"裁剪区域无效，请重新框选"。native 必须 fail closed，不得静默夹取。

继续复用 `INSUFFICIENT_STORAGE`、source/provider 权限错误、`OUTPUT_PERMISSION_LOST`、`CANCELLED` 与 `UNKNOWN`；普通 UI 不泄露原始异常，技术细节只进入 F19，任何凭据一律写为 `[REDACTED]`。

**重试契约**：视频和音频重试必须在第一次 `await` 前预留新的 workflow generation，并置位全局 destination-validation/single-flight 锁；每次异步返回后同时复核 generation、源 URI/源对象、原 retry request、保存位置 revision、输出未发布及 EventChannel 未关闭。锁定期间重置、重新选源、切换保存文件夹和重复重试均不得启动第二条工作流。视频普通重试只对可恢复错误开放（存储不足、provider 瞬态失败、视频解码/编码失败、输出权限丢失、未知瞬态错误）；`CANCELLED`、源权限永久丢失、源损坏/不可用、格式不支持、Encoder 不可用和 compatibility decoder 不可用均不得显示或执行重试。hardware `VIDEO_DECODING_FAILED` 的兼容入口还必须满足同一组未发布/通道存活/源与保存位置前置条件。

### 5.5 Android 端实现要点

- 依赖（版本号以开发时最新稳定版为准，三者必须同版本）：
  `androidx.media3:media3-transformer`、`media3-effect`、`media3-common`
- **压缩**：`Transformer.Builder` + `setVideoMimeType`（H.265/H.264）；码率经 `DefaultEncoderFactory` + `VideoEncoderSettings.Builder().setBitrate()` 设置；分辨率缩放用 `Presentation` 效果；裁剪用 `Crop` 效果（NDC 坐标）；时间剪辑用 `MediaItem.ClippingConfiguration`。
- **视频压缩的音频 copy 模式**：不设置音频 MIME、不加音频处理器，让 Transformer 对视频内音轨走转封装路径。
- **M3 音频 copy**：独立实现类，`MediaExtractor` → `MediaMuxer` 逐 sample 复制第一条有明确 profile 证据的 AAC-LC/HE-AAC/HE-AAC-v2（`audio/mp4a-latm`）音轨，不经过 Transformer，也不创建 Decoder/Encoder。
- **M3 AAC 模式**：Media3 Transformer audio-only，`setRemoveVideo(true)`，并强制 `audioNeedsEncoding=true` 以保证 AAC→AAC 仍实际创建音频 Decoder/AAC Encoder；目标码率只允许 192000/128000/96000/64000 bps。
- **前台服务**：任务在 `ForegroundService` 中执行；建通知渠道；进度节流为每秒 ≤ 2 次更新。
- **权限清单**：`FOREGROUND_SERVICE` + 对应类型权限（`FOREGROUND_SERVICE_MEDIA_PROCESSING` / `FOREGROUND_SERVICE_DATA_SYNC`，Android 14+ 缺失会直接崩溃）、`POST_NOTIFICATIONS`（Android 13+ 需运行时申请，否则进度通知不显示）、`WAKE_LOCK`。Photo Picker 选取与 MediaStore 写入自有输出文件均**无需**存储权限。
- **输出流程**：先写入应用私有缓存目录的临时文件 → 将任务阶段、临时文件标识及已分配的 App 自有 MediaStore URI 持久化到最小任务日志 → 成功后经 MediaStore 拷入公共目录 → 删除临时文件并清除任务日志。失败/取消只允许当前持有精确创建句柄的发布流程立即回滚公共对象；异常退出后的启动对账遵守当前对象所有权证明，证据不足即 quarantine，绝不以 URI/名称/路径相等猜测删除。
- **进度**：视频/AAC 转码使用 Transformer 进度查询；音频 copy 按 sample PTS 计算。所有 EventChannel 事件与 task snapshot 显式输出 `taskKind`。

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
│  ├─ TaskRecoveryStore.kt    # 最小任务日志 + 启动对账
│  ├─ OrphanCleanup.kt        # 仅清理 App 自有临时文件/未完成输出
│  └─ MediaStoreSaver.kt
└─ test/               # logic/ 下纯函数的单元测试（坐标换算必测）
```

### 5.7 关键技术风险与对策（开发时提醒 AI）

| 编号 | 风险 | 对策 |
|---|---|---|
| R1 | **旋转元数据**：竖拍视频存储方向与显示方向不一致，裁剪坐标算错 | 信息读取归一化出"显示宽高 + 旋转角"；Dart 只生成显示方向像素矩形，Kotlin 纯函数将其映射为 Media3 Crop NDC（含 y 轴翻转）并偶数化输出。默认假设 effects 作用于已按元数据转正的帧，不做旋转补偿；以 0/90/180/270° 单测和真机样本验证，只有真实错位时才在 mapper 内补偿并记录结论。若出现无法用坐标换算解释的错位，停止 M4-A，不得改用两次转码绕过 |
| R2 | 机型差异：不同芯片 MediaCodec 行为不同 | 依赖 Media3 内置 workaround；预设码率不设过低；至少 2–3 台不同芯片真机测试 |
| R3 | 长任务被系统杀死 | 前台服务 + WakeLock + 省电白名单引导（可选） |
| R4 | **HDR 输入**：新手机常录 HLG HDR，直接转码可能颜色异常 | 分阶段：M1 仅检测，遇 HDR 提示"暂不支持 HDR 视频"并终止任务；M2 起启用 Transformer 的 HDR→SDR 色调映射设置并提示"已转换为 SDR"，设备不支持映射则明确报错 |
| R5 | 大文件（>4GB）与临时空间 | 输出用 MediaMuxer/Media3 Muxer 的 MP4 正常支持大文件；处理前强制空间检查（F3.3） |
| R6 | 个别需求超出 Media3 能力（MP3、帧率等） | 一律通过"可选附加引擎"扩展，不动主架构；附加引擎与主引擎实现同一 Dart 接口 |
| R7 | FFmpeg 生态波动 | 主引擎不依赖 FFmpeg，天然免疫；附加引擎若失效仅影响 F15/F12 |
| R8 | 转码或 MediaStore 发布期间进程被强杀/系统崩溃，留下孤儿缓存或半成品输出 | M2 持久化最小任务日志；App/服务启动时与活动任务对账，仅按 App 自有临时目录和已记录 URI 清理；F19 记录清理结果；用转码中/发布中强杀及连续 10 次异常中断做真机验收 |

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
  String outputLocationLabel; String? outputTreeUri;
  String videoCodec;            // "hevc" | "h264"
  String videoDecoderMode;      // "hardware" | "software"
  int videoBitrate;             // bps，硬件 Encoder 目标平均码率 VBR
  int? longEdge;                // null = 保持原分辨率
  CropRect? crop;               // 显示方向像素坐标
  int? trimStartMs; int? trimEndMs;
  String audioMode;             // "copy" | "reencode" | "remove"
  int? audioBitrate;
}

class CropRect { int left; int top; int width; int height; }

enum AudioExtractMode { copy, aac }

class AudioExtractRequest {
  String uri; String outputFileName;
  String outputLocationLabel;   // 默认“系统音频 > Music > VideoSlim”
  String? outputTreeUri;        // null=默认 MediaStore；否则为持久化 SAF tree
  AudioExtractMode mode;
  int? bitrate;                 // copy=null；aac=192000|128000|96000|64000

  // Wire 序列化必须生成 §5.4 的 destination 与 audio 两个对象。
}

enum TaskKind { videoCompression, audioExtraction }
enum TaskState { idle, running, success, failed, cancelled }
enum TaskPhase { preparing, encoding, publishing, cancelling, finished }

class ProgressEvent {
  TaskKind taskKind;             // 新事件必填
  String taskId; TaskState state; TaskPhase phase; double percent;
  String? outputUri; String? outputFileName; String outputLocationLabel;
  String? errorCode; String? errorMessage;
}

class TaskSnapshot {
  TaskKind taskKind;             // 新 snapshot 必填
  String taskId; TaskState state; TaskPhase phase; double percent;
  String sourceUri; String outputFileName; String outputLocationLabel;
  int startedAtEpochMs;
  ProcessRequest? videoRetryRequest;
  AudioExtractRequest? audioRetryRequest; // 与 taskKind 严格匹配，不能同时存在
  String? outputUri; String? errorCode; String? errorMessage;
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
| S1 首页 | 三张大功能卡片：压缩视频 / 提取音频 / 裁剪画面；M4-A 启用裁剪画面入口；底部最近一次结果摘要（P1 换成历史入口 + 累计节省空间） |
| S2 视频信息页 | 视频封面帧 + F2 全部字段列表 + 底部主按钮；压缩入口进入 S3，裁剪入口进入 S4 |
| S3 压缩配置页 | 唯一输出配置页；常规预设三选一 + "自定义"，已裁剪时额外显示"保持画质（仅裁剪）"；顶部提供"裁剪画面（可选）"入口，设置后显示尺寸徽章及编辑/移除；按最终输出像素实时显示原大小 → 预估大小；开始前汇总裁剪 + 档位 + 预估 |
| S3A 音频提取配置 | copy / AAC 192/128/96/64；AAC 源默认 copy，非 AAC 禁用 copy 并默认 AAC 128；显示默认/SAF 位置与时间戳文件名 |
| S4 裁剪编辑器 | 显示方向预览帧 + 可拖拽/缩放裁剪框 + 自由（默认）及 16:9/9:16/1:1/4:3 比例锁 + 节流帧位置滑杆 + 实时像素尺寸；确认后只返回 S3，不直接开始任务 |
| S5 进度页 | 圆形进度 + 百分比 + 预计剩余 + 取消；提示"可退出界面，处理会在后台继续" |
| S6 结果页 | 按 `taskKind` 显示“视频/音频已保存”、人类可读位置与 provider 实际文件名；打开/播放 + 分享；视频保留节省信息和受限的删除原视频入口，音频显示大小/时长/采样率/声道/码率 |
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
- **真机验收记录（2026-07-19）**：M1 主流程已通过；除“生成文件保存位置提示不够醒目，用户花费较长时间寻找输出”外未发现其他问题。该问题作为 F7/S6 的 M2 必修 UX 缺陷处理，不回改已封存的 M1 APK。

### M2 压缩功能完整化
- 任务：F3 全部规格（预设/自定义/预估/智能提示/降级）+ F6 全部（前台服务、通知、取消、失败分类、空间检查、最小任务日志、启动对账与孤儿文件/半成品输出清理）+ F7 完整结果页。
- 状态：`ACCEPTED — private scope`（2026-07-20）。项目所有者已接受 Pixel 当前私有使用场景，可进入 M3；不等同于生产发布或多设备保证。
- non-blocking hardening：严格同源 A/B/C、显式 software-only、Photo Picker/多 DocumentsProvider、多 SoC/Android、接近 6 小时/50 GB、>4 GB 输出、持续后台与扩展破坏性恢复矩阵。未执行项不得预填 PASS，补证前不得扩大支持范围。
- 视频编码决策：设备硬件 Encoder + 目标平均码率 VBR；不引入 CBR，也不以实际输出码率偏离目标值硬拦截成功发布。

### M3 音频提取
- 状态：`ACCEPTED — private scope`（2026-07-22）。项目所有者明确报告当前 M3 候选测试成功；接受范围和未认领矩阵见 `docs/m3-completion-report.md` 与 `docs/m3-device-acceptance.md`。
- 任务：实现 F4 AAC copy + AAC 强制重编码，入口接入首页与信息页；复用 M2 前台服务、通知、取消、snapshot、发布、恢复与 F19，并以 `taskKind=audio_extraction` 与视频任务隔离。
- copy：第一音轨必须为 AAC-LC/HE-AAC（`audio/mp4a-latm`），使用 `MediaExtractor + MediaMuxer` 纯 sample copy 到 `.m4a`，不得创建 Decoder/Encoder；非 AAC 稳定返回 `AUDIO_COPY_UNSUPPORTED`。
- AAC：Media3 audio-only 强制重编码为 AAC-LC 192/128/96/64 kbps；即使 AAC→AAC 也不得 transmux。支持 mono/stereo，>2 声道拒绝；无音轨返回 `AUDIO_TRACK_MISSING`。
- 输出：默认 `Music/VideoSlim`，可选持久化 SAF；默认名 `<safe stem>_slim_yyyyMMdd_HHmmss.m4a`，provider collision suffix 是第二层保护。
- 不做：MP3、多音轨选择、trim、音量/降噪、混音、采样率选择、标签/封面。
- 验收：按 `docs/m3-device-acceptance.md` 实测 copy 短片 3 次、同源 AAC 四码率 bytes 单调及实际 Decoder/Encoder、mono、Opus WebM、无音轨、后台/锁屏、转换/发布取消、默认/SAF、1 小时 copy <10 秒和 F19。未实际执行不得预填 PASS。

### M4-A 画面裁剪（F5）
- 状态：`CANDIDATE READY — DEVICE ACCEPTANCE PENDING`。项目所有者于2026-07-22批准实施；双入口、S4编辑器、单次 `Crop → Presentation` 管线及真机反馈的慢拖累计位移修复均已完成。自动化、构建和静态APK检查通过，但完整十项真机矩阵仍未执行；该授权始终不包含M4-B、hardening、refactor或migration。
- 用户流程：压缩入口可在 S3 选择性进入 S4；裁剪入口先进入 S4；两者都在 S3 汇合，开始前显示裁剪 + 输出档位 + 预估大小。不裁剪路径保持现状。
- 已实现：F5双入口、S4手势编辑器、`getPreviewFrame`、Dart坐标/规划纯逻辑与单测、Kotlin NDC mapper、`Crop → Presentation`单次转码、`INVALID_CROP`、snapshot/retry crop round-trip、F19裁剪证据及“保持画质（仅裁剪）”档。
- 范围锁：不实现 trim、旋转/翻转、多裁剪框或两次转码中间文件；不改 publication / recovery / registry / `ProcessingService` / `finishOnce` / 通知 / 音频提取 / decoderMode 重试 / MediaStore 与 SAF / 空间预检；不引入新状态或生命周期框架。
- 验收：严格执行 F5 的 10 项真机矩阵。旋转样本若出现无法解释的错位，停止并报告；不得用二次编码绕过。未获得逐项真机证据不得标为 PASS。
- 复审预算：按 `AGENTS.md` B 节，最多 1 次实现 + 1 次修订 + 1 轮复审。

### C 轨：低码率源膨胀应对（D1 / F20–F22）
- 状态：F20/C1a `IMPLEMENTED — DEVICE TEST WAIVED/NOT RUN`；D1 `COMPLETE — ONE DEVICE/CODEC PATH`；C1b/F21/F22仍未授权。当前候选保持既有硬件VBR行为，不按体积拒绝发布。
- D1：已读取既有F19 `actual video encoder ... configurationFormat`；Pixel 10 Pro的`c2.google.hevc.encoder`有效Media3 `Format`仍为500 kbps，排除fallback配置期夹高，归类为运行期明显过冲。输出`videoBitrate`等于容器平均码率fallback，不是独立视频轨证据；该组合下C1b不得建议更低目标。`configurationFormat`仍只是Media3 `Format`，不是原始Android `MediaFormat`。
- F20/C1a：以保守输出上界和已知源文件大小替换现有单一低收益提示判定；只提示、不改值，`fileSizeBytes <= 0`不触发；有crop时可选“保持画质（仅裁剪）”，无crop时可选“暂不处理”，始终允许用户继续。
- F20/C1b：后置条件项。只有显式码率来源契约、D1结论、F21声明范围和逐codec真机校准齐备时才显示建议值；建议值由用户显式采用，不支持的codec组合或低于产品下限时不生成。
- F21/C2：F19只读编码器能力页；查询mime、VBR/CBR/CQ、QP bounds、bitrate/complexity range及软硬件属性，不创建转码任务。
- F22/C3：F21真机证据后，所有者从QP钳制、CQ、AV1、都不做中选择，至多实施一个。软件CRF默认不做。
- 推荐顺序：M4-B（当前）→ C2 → C1b/C3决策 → M4-C；C1a与既有真机矩阵作为未测试债务保留。每项独立授权、独立候选、独立验收；详细停止条件见交接文档§7.1。

### M4-B 时间裁剪（F8）
- 状态：`CORRECTIVE REVISION — EXACT-SHA GATES PENDING`。F8首个冻结SHA `9c9ca887...`的一次双路复审为一路PASS、一路BLOCKERS；接受的`INVALID_TRIM`恢复锁定问题已在唯一纠正修订中修复并通过Flutter `244/244`，仍需冻结纠正SHA、完成完整门禁和APK静态核验。按每任务一轮复审预算不追加第二轮，不得把旧SHA的混合裁决写成纠正SHA的PASS；真机验收尚未执行。
- 范围：S4起止双滑块；启用已预留的`trimStartMs/trimEndMs`；Kotlin使用Media3 `ClippingConfiguration`，与`Crop → Presentation`同一次Transformer导出。
- 校验：`0 <= start < end <= duration`、最短保留1秒、无效值fail closed为`INVALID_TRIM`；trim必须在request/snapshot/retry/recovery中round-trip，S3估算按保留时长折算。
- metadata：继续按源策略保留可靠拍摄时间/GPS；时间裁剪不改变拍摄语义，仍执行发布前应有/应无核验。
- 验收目标：视频起止误差不超过1帧加设备容差，音频另允许约一个AAC frame封装差；以`docs/m4-b-device-acceptance.md`的真机ffprobe和主观音画同步为准，不在证据前宣称已保证帧精度。
- 不做：多段、跨文件拼接、无损切割或时间轴缩略图带。任何可感知音画漂移、trim恢复丢失或文件安全问题立即停止。

### M4-C 同源多段时间编辑（F23）
- 状态：`PLANNED — NOT AUTHORIZED`，依赖M4-B真机接受。
- 范围：`Composition + EditedMediaItemSequence`将同一来源拆为有序、不重叠的多个片段，共享`Crop → Presentation`效果并一次导出；`segments[{startMs,endMs}]`长度1与M4-B等价。
- 不支持段乱序重排。跨文件拼接属于F16，长视频切成多个独立输出由F10批量队列与M4-B组合覆盖。
- 验收：段边界PTS连续、无卡顿/闪帧、音画同步、进度单调、估算按各段时长求和、取消/恢复完整round-trip、metadata及文件安全不回归。

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

*文档结束。后续开发按当前候选真机验收与上述逐项授权顺序推进。*
