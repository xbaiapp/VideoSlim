# VideoSlim M3 AAC 无损 copy cadence 修正完成报告

> 日期：2026-07-24
> 状态：`APP-LEVEL DEVICE LOG PASS — SUBJECTIVE PLAYBACK PENDING`
> 可执行源码：`7948f9fdbfc839b26d6055ca83ddf8449df42de5`
> 源码 tree：`a668c12b9f9dca7d6ea5024c55343850d83a661b`
> 版本：`1.9.2+27`
> 范围：仅 AAC 无损 copy 的源 cadence 小抖动继承；不改变 AAC 重编码、视频处理、自动 decoder fallback、publication 或 recovery 所有权

## 1. 真实问题

Pixel 10 Pro / Android 17 的真实 AAC-LC 48 kHz 素材在无损 copy 后具有以下一致证据：

- source、copy loop 与 output 均为 `805` 个 sample；
- AAC payload 均为 `206,177` bytes；
- 首 PTS 均为 `0`；
- 末 PTS 均为 `17,158,041 µs`；
- payload digest 与 indexed physical sample-size 聚合检查通过；
- 最大相邻 sample delta 为 `23,812 µs`；
- 声明音轨时长为 `17,179 ms`。

48 kHz AAC-LC 的名义 frame cadence 为 `ceil(1024 × 1,000,000 / 48,000) = 21,334 µs`。原严格规则另给 `2,000 µs` 时间戳取整容差，因此旧上限为 `23,334 µs`。真实设备只超出 `478 µs`，但旧 verifier 仍返回 `AUDIO_OUTPUT_INVALID`，阻止本来已经完整复制的 M4A 发布。

这不是自动 software decoder fallback 引入的回归：该音频失败任务没有创建 Decoder 或 Encoder，且 fallback 候选到修正候选之间未修改视频 retry 关键路径。

## 2. 最终规则

最终实现保持普通 verifier 和 AAC 重编码的严格 cadence 规则不变，只为已经通过无损聚合完整性检查的 copy 路径增加窄继承窗口：

1. source、copy loop 与 output 的 sample count、payload bytes、payload digest、indexed physical sample-size 证据必须先一致；
2. PTS 必须单调，首尾 PTS、覆盖时长与截断检查保持原规则；
3. output 最大 delta 不得超过 `source maxSampleDeltaUs + FRAME_TIMESTAMP_ROUNDING_US (2,000 µs)`，以容纳 mux 时间戳取整；
4. output 还不得超过普通严格上限加独立绝对常量 `MAX_INHERITED_SOURCE_JITTER_US = 1,000 µs`；最终取两者较小值；
5. AAC 重编码不传入 source cadence 继承上下文。

因此 48 kHz AAC-LC 的 lossless-copy 绝对上限为 `24,334 µs`：

- 真实 `23,812 µs`：通过，距绝对上限 `522 µs`；
- 约一整个 AAC frame 的 `42,668 µs` gap：无论是 output 新引入，还是 source/output 共同存在，都必须失败。

`FRAME_TIMESTAMP_ROUNDING_US = 2,000 µs` 仍只代表普通 frame 时间戳取整容差，不等于可以无限继承 source gap。

## 3. 测试先行与唯一审查修订

### 3.1 真实设备误杀

先加入 `23,812 µs`、`805 samples` 的真实设备回归 fixture；旧实现聚焦测试为 RED。加入仅限 lossless copy 的 source-relative 规则后转为 GREEN。

### 3.2 冻结 SHA 审查

初版冻结 SHA `fe0e04d7d49b161ef7370ffa169d86756c2cf178` 接受一次双路只读审查：

- integration/regression 路：PASS；
- correctness/safety 路：BLOCKER——仅要求 output 不大于 source 不够安全；若 source 与 output 都含 `42,668 µs` gap，初版可能错误放行。

该初版 APK 已撤销并隔离，不得交付。

### 3.3 唯一修订

先加入“source 与 output 共同含完整 frame gap 仍必须失败”的测试，并在初版实现上得到 RED；随后增加 `1,000 µs` 绝对继承增量上限，在修订 SHA `7948f9fdbfc839b26d6055ca83ddf8449df42de5` 上转为 GREEN。

遵守每任务一次审查、一次修订预算，没有把初版混合裁决改写为最终 SHA 的第二轮独立 review PASS；最终保证来自 blocker 的精确处置、对应 RED→GREEN 测试和完整门禁重跑。

## 4. 最终自动门禁

`7948f9f...` 的最终证据：

- 聚焦 `AudioOutputVerifierTest`：`22/22 PASS`；
- Flutter format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`259/259 PASS`；
- Android JVM：`365/365 PASS`，58 个 XML suite，0 failures/errors/skipped；
- Android `lintDebug` / `lintRelease`：PASS；
- Android `assembleDebug` / `assembleRelease`：PASS；
- Flutter ARM64 release build：PASS；
- package/version/ABI、ZIP、16 KiB zipalign、APK Signature Scheme v2、签名连续性、权限及敏感字节扫描：PASS。

GitHub Actions 在同一 executable SHA 上的主 `Flutter and Android checks` job 全部通过。API 35 x86_64 instrumentation job 当时因 runner PATH 中缺少 `sdkmanager` 在应用与测试 APK 构建前失败；这是 CI 启动环境问题，不是测试失败。workflow-only修正`68aaa77...`随后在远端run `30107559081`通过全部主门禁和API 35 x86_64 instrumentation `3/3`；它不得被描述为新的可执行APK源码，详见`docs/ci-api35-instrumentation-recovery-2026-07-24.md`。

## 5. ARM64 覆盖安装候选

```text
文件：VideoSlim-1.9.2+27-7948f9f-arm64-v8a-release.apk
package：com.videoslim.videoslim
versionName/versionCode：1.9.2 / 27
ABI：arm64-v8a only
大小：18,509,915 bytes
SHA-256：7a180bc7ce8f89439c7e31f1fcb4c019d1be45d4d356c436698aad43bbcb6f18
签名证书 SHA-256：77edcb53fa49f75964c16cbb3e83b72188728abf3b0bcd19cf088e14c2ba218a
```

与旧私有候选签名连续，可覆盖安装；不得先卸载，以保留应用数据和 F19 日志。若设备报告签名不一致，应停止而不是卸载后继续。

## 6. 修正后设备证据

项目所有者回传的最近任务片段记录了同类 48 kHz AAC-LC 素材的无损 copy：

- `decoder=none`、`encoder=none`；
- source、copy loop 与 output 均为 `805 samples / 206,177 payload bytes`；
- 首末 PTS 仍为 `0 / 17,158,041 µs`；
- output verifier 接受 `maxSampleDeltaUs=23,812`；
- 只分配并完成一次 SAF publication；
- 最终 M4A 为 `213,382` bytes、`17,179 ms`、AAC mono 48 kHz / 96 kbps；
- 终态 `100% success`，无 `AUDIO_OUTPUT_INVALID` 或其他 error；
- recovery record 清除，service 正常销毁；成功路径只会在私有临时文件已经删除或确认不存在后清除 recovery。

该日志片段本身不打印 APK version/hash，因此 APK 身份依赖覆盖安装交付上下文；不能单凭片段反向证明二进制 SHA。日志也不能证明人耳听感或外部播放器实际解码。

## 7. 当前处置

- **App 端功能与安全边界：PASS。** 原真实 `23,812 µs` 误杀已在设备上通过，同时完整 AAC frame gap 的拒绝由自动化锁定。
- **唯一发布、终态、recovery/temp 清理：PASS。**
- **主观播放、声音与外部播放器兼容：PENDING。** 只有项目所有者明确确认实际播放正常后，才可把此次修正候选记为完整 `ACCEPTED — private scope`。
- 本证据不扩大到其他采样率、其他 SoC、多设备或商店生产发布保证。

## 8. 不变项

- 自动 software decoder retry 仍只处理首次硬件视频 attempt 的结构化 `VIDEO_DECODING_FAILED`，同 task、同 service、最多一次；
- software 模式只控制视频 Decoder，Encoder 仍可能为硬件；
- 视频 VBR、裁剪、时间裁剪、metadata、publication/recovery 所有权均未修改；
- C1b、C3/F22、M4-C/F23、hardening、refactor 和 migration 均未因本修正获得授权。
