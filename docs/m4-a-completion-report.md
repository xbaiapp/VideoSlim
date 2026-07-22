# VideoSlim M4-A 候选完成报告

> **状态（2026-07-22）：** `CANDIDATE READY — DEVICE ACCEPTANCE PENDING`
> **候选源代码：** `d41e21c2ed063c087b384a5b63e8e4ab0a9985a7`
> **候选 tree：** `24befa9adb1fec15b3a8bb910e3556a931763c4b`
> **版本：** `1.5.0+20`
> **范围：** 仅 M4-A/F5 画面裁剪；M4-B/F8 时间裁剪仍未授权、未实现

## 1. 已交付范围

### Dart 与规划

- 显示方向像素 `CropRect`、控件/像素坐标换算、边界、八向缩放、整体移动、比例锁、64×64 最小值和偶数化；
- 普通预设先按裁后尺寸再缩放；
- “保持画质（仅裁剪）”只在 crop 存在时可用，使用源码率、裁剪面积、1.2 补偿和现有 codec clamp/fallback；
- crop 进入 `CompressionPlan`、`ProcessRequest`、channel map、snapshot 与 retry round-trip。

### Android 原生管线

- `getPreviewFrame({uri,timeMs})` 返回最长边不超过 1280 的显示方向 JPEG；
- 显示方向像素矩形严格映射到 Media3 NDC，包含 y 轴方向、越界 fail-closed 和偶数输出；
- 单次 Media3 Transformer 的效果顺序固定为 `Crop → Presentation`；
- 无中间视频、无第二次有损编码；
- 非空 trim 仍被拒绝，未实现 M4-B/F8；
- 新增 `INVALID_CROP`，publication/recovery/ProcessingService/finishOnce/通知/音频提取链路保持原结构。

### Flutter S3/S4

- 首页“裁剪画面”与 S3“添加/编辑裁剪”双入口汇合到同一 S4；
- 自由、16:9、9:16、1:1、4:3，八手柄、整体移动、实时像素尺寸；
- 预览帧滑杆使用 180ms 节流和 epoch 防旧响应覆盖；
- 首页入口保存后默认保持画质；S3 编辑保持原档位；取消回滚；移除 crop 时保持画质自动回到均衡；
- `INVALID_CROP` 原样重复提交被禁用，用户可编辑或移除后恢复。
- 真机反馈小修：缩放/移动从按下位置累计整段手势位移，避免慢速微增量被逐帧偶数像素舍入吞掉；八个缩放手柄在取整后固定相反源像素边。

## 2. 范围与架构不变量

- Media3 保持 `1.10.1`；
- minSdk/targetSdk/compileSdk 保持 `26 / 36 / 36`；
- Release 仅 `arm64-v8a`；
- 保持硬件 VBR，不引入 CBR 或严格体积拒绝；
- 单前台服务、单任务、`finishOnce` 唯一终止门不变；
- manifest、构建 SDK/ABI 配置相对 M3 发布基线无源文件漂移；
- 没有 hardening、refactor、migration 或 M4-B/F8 改动。

## 3. 自动化证据

候选源代码冻结前后的验证：

- Dart format：PASS，0 changed；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`215/215` PASS；
- Android JVM tests：`317/317` PASS，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Android debug/release assemble：PASS；
- `git diff --check`：PASS；
- tracked source 与 APK 静态凭据模式扫描：0 hits；
- 构建机 `adb devices -l`：没有连接设备，因此没有执行或宣称任何真机 PASS。

## 4. 复审

### 普通 diff review

普通复审曾阻止候选：锁定比例时，边中点缩到最小值可能被独立的 64px 下限改成方形。修订后，边手柄同时约束选定比例、两轴最小值和可用边界；新增 landscape 16:9 与 portrait 9:16 的最小值回归测试。修订后 focused tests、全量 analyze/test 均通过。

### exact-SHA 双路只读复审

- Route A：NO VERDICT（Hermes reviewer 已核验 exact SHA 并完成大部分只读检查，但未返回最终摘要；按 `AGENTS.md` 不计 PASS）
- Route B：PASS（Codex exact-SHA 只读复审；0 blocker）
- 候选阻塞项：已返回结论的 Route B 为 0；Route A 无最终 verdict，不能据此声明双路 PASS。
- 非阻塞已知债：Route B 仅发现 `docs/current-project-status.md` 与 `AI_REVIEW_START_HERE.md` 的候选状态陈旧；本次文档收口同步修正，不涉及生产代码。

上述 exact-SHA 结果只锚定已被替代的初始冻结 SHA `6de3f7f...`。项目所有者随后在真机复现手势问题并明确授权一次局部修复；旧 exact 结论不冒充当前 SHA 的 PASS。

### 真机反馈局部修复

- RED：慢速亚像素更新不能累计；自由模式拖上边时底边因偶数取整偏移；
- GREEN：`cec0dfb` 从手势起点累计总位移，并按八个手柄方向恢复固定对边；
- focused review：PASS，0 blocker；
- 修复后 Flutter analyze、215 项完整 Flutter tests 与 `git diff --check` 均通过；没有 Android 原生、转码、publication/recovery 或文件生命周期改动。

## 5. 候选 APK

- 文件：`VideoSlim-1.5.0+20-d41e21c-arm64-v8a-release.apk`
- 路径：`/root/artifacts/videoslim/m4-a/VideoSlim-1.5.0+20-d41e21c-arm64-v8a-release.apk`
- Package：`com.videoslim.videoslim`
- versionName/versionCode：`1.5.0 / 20`
- minSdk/targetSdk/compileSdk：`26 / 36 / 36`
- ABI：仅 `arm64-v8a`
- 大小：`18,362,275` bytes
- SHA-256：`680f6a9a5f5fca58b9e69a7d833ea9ffbd74b02b989ac1d44f2d2877d052b784`
- ZIP alignment：PASS
- 签名：Android Debug certificate，APK Signature Scheme v2；不是商店生产签名
- 权限：没有 `INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO` 或 `MANAGE_EXTERNAL_STORAGE`
- Rotation 样本包：`VideoSlim-M4A-rotation-samples.zip`，SHA-256 `1aa49f08b0f29824941bfe71a1d67a74779eed35eb73d37f84d900a38e2fd908`；0°/90°/180°/270° display matrix 已由 ffprobe 与 autorotate SSIM 验证，但尚未在 VideoSlim 真机执行

## 6. 真机状态与停止条件

`docs/m4-device-acceptance.md` 的十项矩阵全部保持 `PENDING`。尤其尚未证明：

- 0°/90°/180°/270° 预览、框选和真实输出对齐；
- 个别 SoC 上 `Crop + Presentation` 的真实编码行为；
- 保持画质的主观质量与面积/体积关系；
- 裁剪任务的后台、锁屏、取消、恢复和文件安全；
- S3 预估与实际输出误差 <25%。

若旋转样本出现无法由显示坐标和 ≤2px 取整解释的错位，必须停止验收并报告，不得改为中间文件后二次转码。任何源文件/旧输出丢失、误删或修改都是 blocker。

## 7. 未包含与下一步

未包含时间裁剪、旋转/翻转 UI、批量、历史、目标大小、hardening、refactor 或 migration。下一步仅交付本 APK 供项目所有者按真机清单测试；在真机结论前不启动 M4-B 或其他生产改动。
