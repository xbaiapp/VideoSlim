# 一次性自动软件解码重试：真机验收清单与实测处置

> 日期：2026-07-24
> 功能实现：VideoSlim `1.9.1+26 / 8f4e970c8c724a5019bcc0f56cbbddbb47d2fb33`
> 当前可执行候选：`1.9.2+27 / 7948f9fdbfc839b26d6055ca83ddf8449df42de5`（之后只改 AAC copy；视频 retry 关键路径无差异）
> 设备：Pixel 10 Pro / Android 17 / API 37
> 状态：`NATURAL DEVICE FALLBACK CONTRACT OBSERVED — OWNER DISPOSITION PENDING`
> 分发边界：Android Debug certificate，仅私有内部测试

## 1. 证据解释规则

日志必须先按**外层用户任务**分组，再分析其中的硬件和软件 **attempt**。不能把软件 attempt 当成用户第二次提交，也不能把稍后的独立手动 software 任务当成自动 fallback 的第二轮。

日志中的“软件模式”只控制视频 Decoder；实际 Encoder 仍可为硬件实现。因此 software attempt 中出现 `c2.google.hevc.encoder` 不代表 fallback 没有生效。

## 2. 安装边界

- 保留原 C2 候选和证据，不覆盖归档。
- 使用同一签名“更新安装”；若签名不匹配则停止，不卸载后继续。
- 当前 `1.9.2+27` 与既有私有候选的证书 SHA-256 连续：`77edcb53fa49f75964c16cbb3e83b72188728abf3b0bcd19cf088e14c2ba218a`。

## 3. A：正常硬件成功路径

同类参数的多次独立视频任务提供以下日志证据：

- [x] 每个新用户任务仍先选择硬件 Decoder；
- [x] 成功任务没有 `automaticSoftwareDecoderRetry=true`；
- [x] 实际 Decoder/Encoder 保持硬件路径，VBR、音频、crop/trim、metadata 和目的地契约未变；
- [x] 每个成功任务只完成一次 publication；
- [x] 成功后 recovery record 清除，ProcessingService 正常销毁；
- [ ] 日志不能证明最终文件已由项目所有者实际播放或主观检查音画同步。

**A 的 App 日志路径：PASS；人类播放项：PENDING。**

## 4. B：真实硬件 Decoder failure 的自动 fallback

一次自然设备故障完整进入了自动 fallback：

1. 外层用户任务的首次硬件 attempt 使用 `c2.google.avc.decoder`，在约 `79%` 返回结构化 `VIDEO_DECODING_FAILED`；
2. 同一个外层 task 立即记录 `starting one automatic software decoder retry`；
3. 没有第二次 Flutter `process()`，没有创建第二个外层用户任务；
4. 当前 task 发送 `preparing / 0% / videoDecoderMode=software / automaticSoftwareDecoderRetry=true`；
5. flow 层记录无确认切换兼容方式的提示；
6. software attempt 的实际 Decoder 为 `c2.android.avc.decoder`；
7. 输入、输出名、目的地、HEVC、目标 VBR、crop、trim 与 audio copy request 保持一致，只改变 Decoder mode；
8. software attempt 的 Encoder 仍为 `c2.google.hevc.encoder`，符合“只切 Decoder”的契约；
9. 首次硬件 attempt 清除 recovery 后才进入 software attempt；同一 ProcessingService 没有在 attempt 边界销毁重建；
10. 两个 attempt 都没有发布残缺公共输出。

对应清单：

- [x] 不出现确认弹窗或手动兼容重试按钮；
- [x] 不返回首页创建第二个用户任务；
- [x] flow/UI event 表明已自动切换软件 Decoder；
- [x] Flutter progress 回到 preparing / 0%，没有保留硬件 attempt 的 `79%`；
- [x] 先记录硬件结构化解码失败，再记录同 task 的 software attempt；
- [x] 实际 Decoder 为软件实现，请求其他字段保持一致；
- [x] software attempt 只发生一次；
- [x] 最终最多发布一个文件；本次实际为 0 个，不存在首 attempt 残缺发布；
- [x] 两个 attempt 的 recovery 均清理，service 在外层 terminal 后销毁；
- [ ] 没有独立前台通知截图，日志只证明 progress event 已重置。

**B 的核心 contract：PASS。**

## 5. C：software attempt 失败和取消

上述自然 fallback 的 software Decoder 已正常启动并推进，但随后硬件 HEVC Encoder 返回结构化 `VIDEO_ENCODING_FAILED`：

- [x] 外层任务以普通 `VIDEO_ENCODING_FAILED` 结束；
- [x] 没有把 Encoder 错误误判为新的 Decoder failure；
- [x] 没有第二轮自动 fallback；
- [x] 没有发布输出；
- [x] recovery 清除，ProcessingService 正常销毁；
- [ ] software attempt 运行中主动取消尚无独立设备证据；
- [ ] cancelling 文案优先级尚无独立设备证据；
- [ ] 后台/锁屏/Activity 重建保持同一 software attempt 尚无独立设备证据。

稍后出现的 `automaticSoftwareDecoderRetry=false` software 任务是新的手动用户任务，不属于自动 fallback 循环。

## 6. D：非资格错误

- [x] software attempt 的 `VIDEO_ENCODING_FAILED` 没有触发新的 Decoder retry；
- [x] 错误保持 Encoder 分类；
- [x] publication/recovery/terminal 行为保持原契约；
- [ ] 音频、存储、SAF、权限、forced finish 各分类尚无专门设备矩阵，但自动化已覆盖资格判定。

## 7. 当前验收判定

### 已证明

- 新用户任务默认硬件；
- 只响应第一次硬件视频 attempt 的结构化 `VIDEO_DECODING_FAILED`；
- 同 task、同 ProcessingService、无确认、从 0% 开始一次 software Decoder attempt；
- 精确复用 request，仅改变 Decoder mode；
- software attempt 的非 Decoder 失败不会循环；
- 首 attempt 不发布残缺文件，最终最多一个公共输出；
- recovery 和 service terminal 边界正常。

### 仍未证明

- 自动 fallback 后最终成功发布并由人类播放检查的完整 happy path；
- 前台通知截图；
- software attempt 主动取消；
- fallback 期间后台、锁屏和 Activity 重建。

### 状态

真实 non-deterministic failure path 已自然复现，不能再写成“device path PENDING”。最精确状态是：

`NATURAL DEVICE FALLBACK CONTRACT OBSERVED — OWNER DISPOSITION PENDING`

不能把该次最终 Encoder failure 写成 software Decoder 失败，也不能仅凭 App 的 Released-state 异常宣称系统已证明发生某个 native MTE/SEGV 崩溃。项目所有者明确接受前，不把本功能写成 `ACCEPTED — private scope`；未执行矩阵继续保持 PENDING。
