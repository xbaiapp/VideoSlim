# 一次性自动软件解码重试：Exact-State Review 处置

> 日期：2026-07-24
> 唯一独立review目标：`11799b06dec490d4cab4c8094b10377580c5971b`
> 唯一纠正SHA：`8f4e970c8c724a5019bcc0f56cbbddbb47d2fb33`
> 最终状态：独立review为 **NO VERDICT**；纠正候选自动化通过；2026-07-24自然真机fallback contract已观察，所有者处置与剩余矩阵PENDING

## 1. 独立review结果

唯一一轮只读exact-state review针对`11799b06…`启动，要求覆盖native terminal ordering、旧Transformer/recovery清理、route/publication ownership、registry、Flutter live/reconnect、取消与迟到事件。review在600秒截止，完成29次API调用，但没有返回summary或verdict。

因此：

- 不能把该轮记为PASS；
- 不能从未完成的工具轨迹推导“没有blocker”；
- 不把`11799b06…`的自动门禁证据冒充为审查裁决；
- 按每任务一轮review预算，不启动无限复审循环。

## 2. Controller发现与唯一修订

### 2.1 延迟snapshot可能压过自动attempt boundary

在Activity恢复竞态中，software retry的`preparing/0%` event可能先到，而旧hardware attempt的高百分比snapshot随后返回。原比较器按percent/phase单调性会把0% event当成旧事件，导致界面暂时保留旧hardware进度。

纠正：`home_screen.dart`明确把同task、显式automatic flag、hardware→software的event识别为新attempt boundary，即使percent和phase故意回退；新增widget测试让software 0% event先于hardware 89% snapshot到达，并断言最终显示0%和自动重试文案。

### 2.2 Registry转移失败不得best-effort继续

原实现若context和publication owner已经转移、但registry snapshot更新失败，会记录日志后继续software engine，可能造成native执行与Flutter/通知/recovery可见状态分裂。

纠正：registry transfer改为fail-closed。context、publication或registry任一转移失败都会进入现有terminal cleanup，而不是带着分裂所有权继续。

以上两项构成该任务唯一revision；没有顺带修改planner、encoder、VBR、音频、metadata、SAF、C2或服务生命周期框架。

## 3. 修订后证据

`8f4e970…`通过：

- Dart format：63 files、0 changed；
- Flutter analyze：0 issues；
- Flutter tests：259/259；
- Android JVM：359/359，58 classes、0 failures/errors/skips；
- debug/release lint与assemble；
- Flutter ARM64 release build；
- APK ZIP、16 KiB zipalign、v2签名、证书连续性、package/version/SDK/ABI、权限连续性与credential marker扫描。

修订SHA没有第二轮独立review，因此准确表述是：**controller findings已修复且自动门禁通过；独立exact-state review仍无PASS裁决。**

## 4. 候选处置

- `11799b06…`及其APK已标记superseded，不得安装或分发。
- 自动fallback实现候选是`1.9.1+26 / 8f4e970…`；当前可执行私有候选为`1.9.2+27 / 7948f9f…`，后续只修改AAC copy cadence，视频retry关键路径无差异。
- 真实Media3/Codec2 fallback contract已自然观察；最终成功发布/播放、通知、取消、后台/锁屏等剩余项仍以真机清单为准。
