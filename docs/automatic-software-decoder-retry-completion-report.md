# 一次性自动软件解码重试：实现与候选报告

> 日期：2026-07-24
> 状态：私有内部候选；静态门禁通过后等待真机验收
> 版本：`1.9.1+26`
> 代码候选：`8f4e970c8c724a5019bcc0f56cbbddbb47d2fb33`
> Tree：`54059b52b5cb9a65419ab65e8815f4527946c3d3`

## 1. 产品结论

同一原视频、同一裁剪区间和同一压缩参数后来再次使用硬件解码成功，因此现有证据不支持永久素材黑名单，也不足以认定为稳定的 1.9.0 回归。最符合证据的解释是一次非确定性的 codec 生命周期故障。产品策略改为：每个视频用户任务仍默认硬件解码；只有第一次硬件视频 attempt 返回结构化 `VIDEO_DECODING_FAILED` 时，才在原任务内自动、无确认地从头使用软件解码器重试一次。

## 2. 冻结行为契约

- 仅视频任务、首次硬件 attempt、结构化 decoder failure、未取消、未 forced finish、未用过自动 retry 时有资格。
- encoder、audio、storage、publication、一般异常、取消和服务强制终止均不触发。
- 软件 attempt 失败后按普通 terminal failure 结束，禁止循环。
- 用户 task ID、`ProcessingService` launch、输入 URI、裁剪、码率、codec、尺寸、帧率、音频策略、目标目录、输出名和元数据策略保持不变；仅 decoder mode 从 hardware 改为 software。
- 不建立设备、codec 或素材永久软件黑名单；下一次新用户任务仍先硬件。
- 不改变硬件 HEVC VBR、planner、编码器选择、SAF 发布、音频处理或 C2 只读诊断。

## 3. 生命周期与所有权

- `TranscodeEngine` 在发出失败 callback 前清空 active Transformer、删除旧临时输出并清除旧 recovery record；回调随后才可能启动软件 attempt。
- 新 attempt 使用新的 engine route；`ActiveTaskContext` 只允许一次 route transfer。
- publication identity 保持不变，但 publication owner 显式从旧 route 转给新 route；旧 route 的迟到 completion/publish 不能获胜。
- registry 原子写入软件 request、`automaticSoftwareDecoderRetry=true`、`preparing/0%` 和新的 attempt 起始时间。任何 context/publication/registry 转移失败均 fail-closed。
- 同一 `ProcessingService` 内部启动软件 engine；Flutter 不第二次调用 `process()`，也不创建第二个用户任务。
- 运行中恢复以显式 automatic flag 表示当前软件 attempt；UI 和前台通知显示“硬件读取失败，已自动改用兼容方式从头重试…”。
- reconnect 比较器把 hardware→software 自动 attempt boundary 视为新事件，即使进度故意从旧 attempt 的较高值回到 0%。

## 4. 回归覆盖

- 自动 retry 严格资格门及所有排除类别。
- `ProcessRequest` 精确复用且仅 decoder mode 改变。
- task route 只转移一次；旧 route 被拒绝。
- publication owner 转移，旧 route 不得发布。
- registry request/flag/preparing/0% 原子重置。
- Flutter 同 task attempt 重置、无确认按钮、无第二次提交、Activity 恢复。
- 延迟硬件 snapshot 与提前到达的软件 retry event 的竞态。
- 前台通知自动 retry 文案及 cancelling 优先级。
- progress/snapshot schema round-trip。

## 5. 自动化门禁

- Dart format：`63 files / 0 changed`。
- Flutter analyze：`0 issues`。
- Flutter tests：`259/259 PASS`。
- Android JVM：`359/359 PASS`，58 个 test class，0 failures/errors/skips。
- Android `lintDebug` / `lintRelease`：PASS。
- Android `assembleDebug` / `assembleRelease`：PASS。
- Flutter ARM64 release build：PASS。
- 门禁前后工作树均 clean。

## 6. APK 静态核验

- 文件：`VideoSlim-1.9.1+26-8f4e970-arm64-v8a-release.apk`
- 大小：`18,509,915 bytes`
- SHA-256：`8765c79b1afe9cfe5b451359766646b4be089d12820e3478a05ba636f24272f3`
- package/version：`com.videoslim.videoslim` / `1.9.1 (26)`
- SDK：min 26 / target 36
- ABI：仅 `arm64-v8a`
- ZIP 完整性：PASS
- 16 KiB zipalign：PASS
- APK Signature Scheme v2：PASS
- 签名证书 SHA-256：`77edcb53fa49f75964c16cbb3e83b72188728abf3b0bcd19cf088e14c2ba218a`，与原 1.9.0 私有候选一致
- 权限集合与原候选一致；无 `MANAGE_EXTERNAL_STORAGE`，历史写权限继续受 `maxSdkVersion=28` 限制
- APK 凭据 marker 扫描：0 findings
- 对应源码归档：`VideoSlim-1.9.1+26-8f4e970-source.tar.gz`，SHA-256 `c572d339e7bb0314e779c3f4ce4ed8f7dc283929b1fd8cb62c35ce140ead9b44`

该 APK 使用 Android Debug certificate，只能称为“私有内部候选”，不得描述为商店发布包或正式签名发布包。

## 7. 审查处置

唯一一轮独立 exact-state 审查针对 `11799b06…` 运行 600 秒后超时，完成 29 次只读调用但没有形成 verdict，按规则记录为 **NO VERDICT**，不是 PASS。控制器随后发现并修复两个 attempt-boundary 问题：延迟硬件 snapshot 可能压过软件 retry 的 0% 重置，以及 registry 转移失败后仍继续 best-effort。修订提交为本报告的 `8f4e970…`。遵守每任务一轮 review/一次 revision 的预算，没有启动无限复审循环；完整处置见`docs/automatic-software-decoder-retry-exact-review-disposition.md`。

## 8. 仍需真机证明

自动化测试证明状态机和所有权约束，但不能强制 Pixel 10 Pro/API 37 复现非确定性的硬件 decoder failure，也不能代替真实 Media3/Codec2、后台锁屏、SAF 发布和长视频行为。新候选的完成定义仍包含独立真机验收清单。

## 9. 与原 C2 候选的关系

原 `1.9.0+25 / 11f169ca…`、tree `bcc2cbed…`、APK SHA-256 `fe9e0e…` 和 Pixel 10 Pro C2 报告继续作为冻结历史证据，不被本候选覆盖。本实现是新的 `1.9.1+26` 私有内部候选。
