# VideoSlim 拍摄时间、位置与输出命名候选完成报告

> **日期：** 2026-07-23
> **版本：** `1.6.0+21`
> **候选源代码：** `497c5d2c028213835825f1aea1df5d356450d7f2`
> **候选 tree：** `4a51769e6764c296dbcc0ccf6e188de888d9de9c`
> **状态：** `BLOCKED — EXACT-SHA ROUTE A FAIL; DO NOT DISTRIBUTE`

## 1. 交付范围

本候选在既有 `1.5.0+20` 裁剪候选之上增加：

1. 读取并规范化来源中可可靠解释的拍摄/容器创建时间与 ISO 6709 GPS；
2. 通过 Media3 `1.10.1` 的 `InAppMp4Muxer.MetadataProvider`，在现有单次视频转码中只写入时间和位置；
3. Transformer 完成后、公开 URI 分配前回读最终临时 MP4，按秒精度和 `0.0001°` 容差核验；
4. 已承诺字段缺失/不匹配时返回 `CAPTURE_METADATA_FAILED`，不发布不完整结果；
5. 默认 MediaStore 视频发布从已验证的来源时间设置 `DATE_TAKEN`；
6. 视频和音频输出名包含最终计划、目标码率、毫秒处理时间和四位随机十六进制 token。

明确没有增加：隐私模式、完整 metadata 复制、设备定位、反向地理编码、MOV 输出、第二次 remux/有损编码、Media3 升级、音频继承视频时间/GPS、M4-B 时间裁剪或并发/recovery重构。

## 2. 实现边界

### Android

- `CaptureMetadata.kt`：安全模型、带时区日期解析、ISO 6709解析、muxer白名单 provider和输出核验器；
- `VideoMetadataReader.kt`：容器字段优先、MediaStore正数 `DATE_TAKEN` fallback，不把值送入 Flutter wire；
- `TranscodeEngine.kt`：显式 `InAppMp4Muxer.Factory(policy)`、临时 MP4核验先于 publication；
- `MediaStoreSaver.kt`：只对视频 MediaStore insert写可信 `DATE_TAKEN`，SAF保持字节发布边界；
- 新错误码和通知不包含精确时间或坐标。

### Flutter

- `OutputFileNameBuilder` 统一清洗路径/非法字符、按 UTF-8 240 bytes限制主干并生成随机 token；
- 视频使用能力 fallback 后的 `CompressionPlan.videoCodec/videoBitrate`；
- 音频区分 AAC copy 与 AAC re-encode；copy兼容重试切换为AAC时重新生成真实名称，且 `target` 只表示目标码率。

既有 `ProcessingService`、单 service/task、`finishOnce`、publication/recovery、硬件 VBR、裁剪编辑器和 `Crop → Presentation` 顺序未改变。

## 3. 自动化证据

候选 SHA `497c5d2...`：

- Dart format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`219/219`；
- Android JVM tests：`337/337`，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Android debug/release assemble：PASS；
- ARM64 Flutter release build：PASS；
- `git diff --check`：PASS；
- Media3保持 `1.10.1`；Manifest权限相对 `1.5.0+20` 无变化。

首次组合release门禁没有保留可引用的完整错误文本；清理纯构建产物并拆分复核后，全部门禁实际通过。随后发现直接调用Gradle组装可能沿用旧Flutter版本缓存；该中间APK已作废，不属于交付物。最终APK由 `flutter build apk --release --target-platform android-arm64` 从干净候选生成并单独核验为 `1.6.0+21`。

## 4. Exact-SHA 复审

旧冻结点 `47c5448...` 的两路reviewer均超时且没有verdict，因此不计PASS；该冻结点随后又因AAC兼容重试名称仍显示copy而失效。问题通过失败测试复现并在唯一修订中修复。

最终复审目标固定为：

```text
497c5d2c028213835825f1aea1df5d356450d7f2
```

- Route A：**FAIL — BLOCKER**
- Route B：**PASS — 0 blocker/important/nit**

Route A通过本地Media3 `1.10.1`字节码确认：`Mp4Muxer.MetadataCollector`在未收到显式时间时使用 `System.currentTimeMillis()` 初始化时间戳。当前policy在来源时间缺失时不写 `Mp4TimestampData`，而verifier又不检查“必须缺失”；因此无时间或仅GPS来源可能发布带处理时间的MP4，违反“不伪造”契约。该问题阻止交付和真机验收。

## 5. APK 身份

- 文件：`VideoSlim-1.6.0+21-497c5d2-arm64-v8a-release.apk`
- 隔离路径：`/root/artifacts/videoslim/capture-metadata/blocked-497c5d2/VideoSlim-1.6.0+21-497c5d2-arm64-v8a-release.apk`
- 大小：`18,378,659` bytes
- SHA-256：`f1c035effffafafb319cedec4d1de8fe4f41c84c0009afc6f9ddb66f0e94b6b3`
- package：`com.videoslim.videoslim`
- versionName/versionCode：`1.6.0 / 21`
- minSdk/targetSdk/compileSdk：`26 / 36 / 36`
- ABI：仅 `arm64-v8a`
- 签名：Android Debug certificate，APK Signature Scheme v2
- zipalign：PASS
- 权限相对旧候选：无变化；没有 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE`
- 常见凭据模式扫描：PASS

同目录包含 `BLOCKED.txt`、`SHA256SUMS` 和 `verification.txt`。哈希核验曾通过，但该事实不改变候选已被隔离、不得分发。

## 6. 被阻止的真实验收

Route A blocker解除、重新完成门禁并形成新候选前，不得开始设备验收。以下均不能写为PASS：

- 真实 MOV/MP4 输入与输出的时间/GPS atom对照；
- Media3单次 mux 在实际设备上的写入行为；
- MediaStore `DATE_TAKEN` 和具体图库排序；
- SAF内部 metadata与DocumentProvider行为；
- HDR/AV1、方向、裁剪、codec、音频、后台、锁屏、取消和恢复回归。

完整清单见 `docs/capture-metadata-device-acceptance.md`，所有未执行行保持 `PENDING`。

若真实设备发现单次 Media3 mux不能可靠保留字段，必须停止并上调修改规模；不得静默增加第二次 remux、自定义 MP4 writer或 Media3升级。

## 7. 回滚

旧 `1.5.0+20` 候选、源码 SHA和APK哈希保持不变。新候选真机失败时回退旧包，不删除或修改源视频，不覆盖旧 artifact。
