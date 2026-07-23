# D1 码率下发诊断报告

> **日期：** 2026-07-23
> **状态：** `COMPLETE — ONE DEVICE/CODEC PATH`
> **范围：** 只读分析项目所有者此前提供的最新相关F19任务；未修改代码，未复测设备

## 1. 诊断问题

目标是区分：

1. Media3 fallback在配置期把500 kbps目标夹高或更换为其他目标；
2. Media3有效encoder configuration仍为500 kbps，但硬件编码器运行期明显过冲。

现有F19已记录Media3 `codec.configurationFormat`，因此不需要新增底层日志。

## 2. 脱敏证据

同一条Pixel 10 Pro / Android 17任务：

- 请求：HEVC、硬件编码、VBR目标`500,000 bps`、1280×720、音频copy；
- 选择编码器：`c2.google.hevc.encoder`；
- Media3有效encoder configuration `Format`：mime为`video/hevc`，码率字段为`500,000 bps`，尺寸1280×720，约23.976 fps；
- 输出时长：`2,616,213 ms`；
- 输出文件：`754,489,009 bytes`；
- 输出metadata的`videoBitrate`：`2,307,117 bps`；
- 来源文件：`279,277,518 bytes`；
- 音频metadata：`320,000 bps`。

原始日志含用户URI、任务ID和输出URI，不提交到Git；本报告只保留诊断所需的脱敏字段。

## 3. 计算与契约修正

- 输出/来源体积比：`2.701574×`；
- 输出文件按`bytes × 8 ÷ duration`计算的容器平均码率：`2,307,117.988 bps`；
- 该值与Dart收到的`videoBitrate=2,307,117`只差约`0.988 bps`。

因此本任务的输出`videoBitrate`几乎确定来自容器平均码率fallback，而不是可独立证明的视频轨码率。不能继续把`2,307,117 bps`严格称为“实际视频轨码率”。扣除已报告的320 kbps音频后，视频及容器开销约`1.987 Mbps`，约为500 kbps目标的`3.97×`；这是估计，不是精确轨码率。

## 4. 结论

- D1分支：**有效Media3 configuration仍为500 kbps；没有证据表明fallback在配置期把目标夹高。**
- `c2.google.hevc.encoder`在该Pixel/素材/HEVC组合上出现显著运行期过冲或设备内部最低可实现码率；现有Media3 `Format`不能证明原始`MediaCodec.configure()`的`KEY_BIT_RATE`，但已足以排除“Media3有效配置已被夹到约2 Mbps”。
- 对该设备/codec组合，继续把建议目标降到500 kbps以下没有实证依据，C1b不得生成更低建议值。
- 保留硬件VBR、不改CBR、不因体积偏离拒绝发布；当前主对策仍是C1a提示。若以后要改变编码行为，必须先做C2能力探测，再由所有者在C3方案与“都不做”之间选择。
- 本结论只覆盖这一条Pixel 10 Pro + `c2.google.hevc.encoder` + HEVC任务，不外推到H.264、其他SoC或其他分辨率。

## 5. 后续状态

D1现有日志诊断完成，无需日志代码修订。C1b仍未授权且在该组合上明确禁用更低目标建议。下一代码里程碑按项目所有者“跳过C1a真机测试并继续下一步”的指示转入M4-B单段时间裁剪规划；C1a矩阵保持未执行，不记PASS。
