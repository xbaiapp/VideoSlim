# VideoSlim 拍摄时间、位置与输出命名候选完成报告

> **日期：** 2026-07-23
> **版本：** `1.6.1+22`
> **候选源代码：** `b0267a0b959ccb46785daa1c91d0be96b5a0ef98`
> **候选 tree：** `aa218a23dac1bbf0f69eb5dc2ff6e633eedd1ceb`
> **状态：** `CANDIDATE READY — DEVICE AND REAL-SOURCE ACCEPTANCE PENDING`

## 1. 交付范围

本候选在既有 `1.5.0+20` 裁剪候选之上增加：

1. 读取并规范化来源中可可靠解释的拍摄/容器创建时间与 ISO 6709 GPS；
2. 通过 Media3 `1.10.1` 的 `InAppMp4Muxer.MetadataProvider`，在现有单次视频转码中只写入时间和位置；
3. 来源时间缺失时用1904/zero sentinel覆盖Media3缺省处理时间，但不把sentinel视为来源时间或送入 `DATE_TAKEN`；
4. Transformer 完成后、公开 URI 分配前回读最终临时 MP4，对时间/GPS同时核验必有与必无；
5. 应有字段缺失/不匹配或应无字段意外出现时返回 `CAPTURE_METADATA_FAILED`，不发布不完整结果；
6. 默认 MediaStore 视频发布从已验证的来源时间设置 `DATE_TAKEN`；
7. 视频和音频输出名包含最终计划、目标码率、毫秒处理时间和四位随机十六进制 token；
8. 对设备发现的超长日志复制问题做独立收窄修复：剪贴板只接收最近128 KiB完整日志行，完整日志继续用文件分享，避免Android Binder `TransactionTooLargeException`。

明确没有增加：隐私模式、完整 metadata 复制、设备定位、反向地理编码、MOV 输出、第二次 remux/有损编码、Media3 升级、音频继承视频时间/GPS、M4-B 时间裁剪或并发/recovery重构。

## 2. 实现边界

### Android

- `CaptureMetadata.kt`：安全模型、带时区日期解析、ISO 6709解析、muxer白名单 provider、无时间sentinel和双向输出核验器；
- `VideoMetadataReader.kt`：容器字段优先、MediaStore正数 `DATE_TAKEN` fallback，不把值送入 Flutter wire；
- `TranscodeEngine.kt`：显式 `InAppMp4Muxer.Factory(policy)`、临时 MP4核验先于 publication；
- `MediaStoreSaver.kt`：只对视频 MediaStore insert写可信 `DATE_TAKEN`，SAF保持字节发布边界；
- 新错误码和通知不包含精确时间或坐标。

### Flutter

- `OutputFileNameBuilder` 统一清洗路径/非法字符、按 UTF-8 240 bytes限制主干并生成随机 token；
- 视频使用能力 fallback 后的 `CompressionPlan.videoCodec/videoBitrate`；
- 音频区分 AAC copy 与 AAC re-encode；copy兼容重试切换为AAC时重新生成真实名称，且 `target` 只表示目标码率。
- `LogClipboardPayload` 对超长日志生成UTF-8安全的最近完整行尾部；小日志逐字不变，复制失败不再把原始平台堆栈展示给用户。

既有 `ProcessingService`、单 service/task、`finishOnce`、publication/recovery、硬件 VBR、裁剪编辑器和 `Crop → Presentation` 顺序未改变。

## 3. 自动化证据

候选 SHA `b0267a0...`（metadata核心祖先 `a92d1cd...`）：

- Dart format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`224/224`；
- Android JVM tests：`341/341`，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Android debug/release assemble：PASS；
- ARM64 Flutter release build：PASS；
- `git diff --check`：PASS；
- Media3保持 `1.10.1`；Manifest权限相对 `1.5.0+20` 无变化；
- GitHub CI run `29974304294` 的主Flutter/Android job：PASS；
- API 35 x86_64 instrumentation：未执行，既有runner在安装固定SDK时因 `sdkmanager: command not found` 退出127，发生在App/instrumentation构建前。

首次组合release门禁没有保留可引用的完整错误文本；清理纯构建产物并拆分复核后，全部门禁实际通过。随后发现直接调用Gradle组装可能沿用旧Flutter版本缓存；该中间APK已作废，不属于交付物。最终APK由 `flutter build apk --release --target-platform android-arm64` 从干净候选生成并单独核验为 `1.6.1+22`。

## 4. Exact-SHA 复审

旧冻结点 `47c5448...` 的两路reviewer均超时且没有verdict，因此不计PASS；该冻结点随后又因AAC兼容重试名称仍显示copy而失效。问题通过失败测试复现并在唯一修订中修复。

被阻止候选的最终复审目标为：

```text
497c5d2c028213835825f1aea1df5d356450d7f2
```

- Route A：**FAIL — BLOCKER**
- Route B：**PASS — 0 blocker/important/nit**

Route A通过本地Media3 `1.10.1`字节码确认：`Mp4Muxer.MetadataCollector`在未收到显式时间时使用 `System.currentTimeMillis()` 初始化时间戳。当前policy在来源时间缺失时不写 `Mp4TimestampData`，而verifier又不检查“必须缺失”；因此无时间或仅GPS来源可能发布带处理时间的MP4，违反“不伪造”契约。该问题阻止交付和真机验收。

项目所有者随后明确授权一次只处理该blocker的额外修订。新exact-SHA：

```text
a92d1cd4f5bf6b4b7dd0a7aaded199c6e0b230e8
```

- 修订：无可靠来源时间时写 `Mp4TimestampData(0, 0)` 覆盖Media3当前时间；resolved metadata和 `DATE_TAKEN` 仍保持空。
- 修订：verifier不再对空期望提前返回，并拒绝任何意外时间或位置。
- focused corrective review：**PASS — no BLOCKER/IMPORTANT finding**。

项目所有者随后在Pixel设备完成一条视频任务；任务本身、时间存在/位置缺失核验和MediaStore发布均成功，但整份约1 MiB日志复制触发Android Binder超限。该UI问题不影响已发布视频。经项目所有者批准，当前 `b0267a0...` 只修改Flutter日志复制边界、测试和版本：

- 最近日志尾部上限：128 KiB UTF-8，并从完整物理日志行开始；
- 完整日志：继续通过既有FileProvider文件分享；
- 用户提供的 `1,048,167` bytes日志实跑得到 `130,885` bytes复制载荷，保留最新事件；
- focused exact-SHA clipboard review：**PASS — no BLOCKER/IMPORTANT finding**。

## 5. APK 身份

- 文件：`VideoSlim-1.6.1+22-b0267a0-arm64-v8a-release.apk`
- 路径：`/root/artifacts/videoslim/capture-metadata/VideoSlim-1.6.1+22-b0267a0-arm64-v8a-release.apk`
- 大小：`18,378,659` bytes
- SHA-256：`21ac3df44e8afa116cc9bb7c5f8ca7db94bacc45830f2dd373e4b9d4b0570409`
- package：`com.videoslim.videoslim`
- versionName/versionCode：`1.6.1 / 22`
- minSdk/targetSdk/compileSdk：`26 / 36 / 36`
- ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate，APK Signature Scheme v2
- zipalign：PASS
- 权限相对旧候选：无变化；没有 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE`
- 常见凭据模式扫描：PASS

同目录包含 `SHA256SUMS` 和 `verification.txt`；哈希核验通过。被阻止的 `497c5d2`、已作废的 `47c5448` 和被日志复制问题取代的 `1.6.0+21 / a92d1cd` 分别保留在明确命名的隔离子目录，不得作为当前包分发。

## 6. 尚未开始的真实验收

一条Pixel设备任务已证明App内“时间存在、位置缺失”核验和默认MediaStore发布成功，但不能证明原文件谱系、外部atom或图库索引。以下项目在实际执行并记录证据前均不能写为PASS：

- 真实 MOV/MP4 输入与输出的时间/GPS atom对照；
- Media3单次 mux 在实际设备上的写入行为；
- MediaStore `DATE_TAKEN` 和具体图库排序；
- SAF内部 metadata与DocumentProvider行为；
- HDR/AV1、方向、裁剪、codec、音频、后台、锁屏、取消和恢复回归。

完整清单见 `docs/capture-metadata-device-acceptance.md`，所有未执行行保持 `PENDING`。

若真实设备发现单次 Media3 mux不能可靠保留字段，必须停止并上调修改规模；不得静默增加第二次 remux、自定义 MP4 writer或 Media3升级。

## 7. 回滚

旧 `1.5.0+20` 候选、源码 SHA和APK哈希保持不变。新候选真机失败时回退旧包，不删除或修改源视频，不覆盖旧 artifact。
