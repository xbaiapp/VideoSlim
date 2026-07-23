# C1a 低收益/可能变大提示完成报告

> **日期：** 2026-07-23
> **状态：** `CANDIDATE READY — DEVICE ACCEPTANCE PENDING`
> **版本：** `1.7.0+23`
> **候选源码：** `7c49e57e3b6eafeeb765f2600c17b0242bea1160`
> **候选 tree：** `36c50b0d1a5a3929148d5aa6ea0aa4c31bb4c709`

## 1. 本候选完成什么

C1a只改变压缩前提示，不改变编码请求或发布规则：

- 只有来源文件大小已知且大于0时，才比较保守输出上界与源文件大小；
- `estimatedOutputMaxBytes * 100 > source.fileSizeBytes * 85`时提示“预计节省低于15%，甚至可能变大”；恰好15%不提示；
- 来源大小未知（wire值0）不计算、不误报；
- 无crop：单一确认弹窗提供“暂不处理 / 仍按原目标压缩”；
- 有crop：始终提供“保持画质（仅裁剪）/ 仍按原目标压缩 / 取消”，包括已选择保持画质的可达路径；
- 选择保持画质只切换/保持preset并返回S3，不创建native任务；
- 选择继续时仍使用用户原计划；没有静默改写码率、codec、音频或文件名；
- 实际输出即使大于源文件也不会被拒绝发布。

## 2. 代码范围

修改：

- `lib/logic/compression_planner.dart`
- `lib/screens/home_screen.dart`
- `lib/widgets/m2_compression_card.dart`
- `test/logic/compression_planner_test.dart`
- `test/widget_test.dart`
- `pubspec.yaml`（仅候选身份 `1.7.0+23`）

没有修改`android/`生产源码、Media3、前台服务、publication、recovery、`finishOnce`或F19 wire。

## 3. RED→GREEN与自动化

- 旧90%源码视频码率条件已废弃；新测试覆盖15%整数边界、源大小0、与源码率无关；
- 无crop停止/继续：RED后GREEN；
- crop切换到保持画质且不启动：RED后GREEN；
- 既已选择保持画质仍显示三项：外部复审指出后新增RED测试，再修订为GREEN；
- Dart format：PASS；
- Flutter analyze：PASS，0 issues；
- Flutter tests：`227/227`；
- Android JVM tests：`341/341`，0 failures/errors/skipped；
- Android debug/release lint：PASS；
- Android debug assemble：PASS；
- Flutter ARM64 release build：PASS。

## 4. 唯一复审轮次与处置

一次并行复审针对首版冻结提交`d3af1c3a0086eed4e9a7d09d16a30f5ebaa094da`，两路都返回FAIL：

1. crop已经选择保持画质时，首版隐藏了同名动作；
2. 交接文档仍把整个C轨写为未授权；
3. `1.7.0+23`与AGENTS旧措辞及`1.6.1+22`可安装候选身份没有清楚分层。

上述三项都在唯一修订`7c49e57...`中处置：新增可达回归测试、crop存在时始终显示动作、同步AGENTS/交接/状态，并明确`1.7.0+23`是新候选身份、旧`1.6.1+22`在新APK核验前只是上一可安装候选。

项目规则每任务只允许一轮复审和一次修订，因此没有对`7c49e57...`再发起第二轮外部复审。不得把首版FAIL写成最终SHA的PASS；最终修订依靠新增RED→GREEN测试、完整自动化和APK静态核验进入真机验收。

## 5. APK身份与静态核验

```text
文件：VideoSlim-1.7.0+23-7c49e57-arm64-v8a-release.apk
路径：/root/artifacts/videoslim/c1a-low-savings/VideoSlim-1.7.0+23-7c49e57-arm64-v8a-release.apk
package：com.videoslim.videoslim
versionName/versionCode：1.7.0 / 23
ABI：arm64-v8a only
minSdk/targetSdk/compileSdk：26 / 36 / 36
大小：18,378,659 bytes
SHA-256：72dcce8374c3bb771cdfa1b8fddd6d2dfec8baba19f8e3b917015c221e92367f
签名：Android Debug certificate；APK Signature Scheme v2
证书SHA-256：77edcb53fa49f75964c16cbb3e83b72188728abf3b0bcd19cf088e14c2ba218a
```

核验结果：

- build output与durable copy字节哈希一致；
- ZIP完整性、zipalign、v2签名：PASS；
- package/version/SDK/ABI：PASS；
- 签名证书与上一`1.6.1+22`候选一致，可覆盖安装；
- 最终权限仍无`INTERNET`、`READ_MEDIA_VIDEO`、`READ_MEDIA_AUDIO`或`MANAGE_EXTERNAL_STORAGE`；
- 私钥标记、credential URL、Bearer、签名密码及指定敏感字面量扫描：0 findings。

这是使用Android Debug certificate的私有验收包，不是production-signed商店包。

## 6. 剩余边界

构建机没有连接Android设备。自动化和APK静态核验不能证明真实手机上的弹窗排版、文字理解、Picker/provider大小未知路径或完整压缩结果；必须执行`docs/c1a-low-savings-device-acceptance.md`。M4-A与时间/GPS既有矩阵继续PENDING，不因本候选自动通过。
