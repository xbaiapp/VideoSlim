# VideoSlim API 35 x86_64 instrumentation CI 恢复报告

> 日期：2026-07-24
> 状态：`RESOLVED — REMOTE INSTRUMENTATION PASS`
> workflow-only 修正：`68aaa777ef39b1b71441dbc9da3d17ce78258301`
> 可执行 APK 源码仍为：`7948f9fdbfc839b26d6055ca83ddf8449df42de5`

## 1. 原失败不是应用测试失败

GitHub Actions run `30103485466` 绑定可执行 SHA `7948f9f...`：

- `Flutter and Android checks` job：全部步骤 PASS；
- `Android API 35 x86_64 instrumentation` job：在 `Install pinned Android build SDK` 退出 `127`；
- 精确错误：`sdkmanager: command not found`；
- 失败发生在应用 APK、instrumentation APK 和 emulator 测试之前。

因此该 run 的整体 failure 只证明 runner 没把 Android command-line tools 放入 PATH，不能解释为 Flutter、Android JVM、lint、assemble 或 instrumentation 测试失败，也不能伪装成 instrumentation 已执行。

## 2. 最小修正

`.github/workflows/ci.yml` 只在 instrumentation job 的 Java setup 后增加：

```yaml
- name: Set up Android SDK command-line tools
  uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407
  with:
    packages: ''
```

该 action SHA 对应 `android-actions/setup-android` v3，负责准备并把 `sdkmanager` 加入 PATH；具体 platform/build-tools 仍由下一步原命令固定安装。action 使用 immutable commit，不使用可移动 tag。

修正没有改 Flutter、Kotlin、Gradle build logic、manifest、版本或 APK 内容。`68aaa77...`不能取代`7948f9f...`作为`1.9.2+27`可执行候选身份。

## 3. 本地静态验证

- workflow YAML parse：PASS；
- action commit 可由 GitHub API读取：PASS；
- `git diff --check`：PASS；
- 修改范围：`.github/workflows/ci.yml` 6 insertions，0 production files。

## 4. 远端结果

GitHub Actions run：`30107559081`

- URL：`https://github.com/xbaiapp/VideoSlim/actions/runs/30107559081`
- head SHA：`68aaa777ef39b1b71441dbc9da3d17ce78258301`
- workflow conclusion：`success`

### Flutter and Android checks

全部步骤 PASS，包括：

- format；
- Flutter analyze/tests；
- Android JVM tests；
- debug/release lint；
- debug/release assemble。

### Android API 35 x86_64 instrumentation

以下步骤全部 PASS：

- Android command-line tools setup；
- pinned Android platform/build-tools安装；
- debug app与instrumentation APK组装；
- KVM设置；
- API 35 / Google APIs / x86_64 emulator启动；
- `connectedDebugAndroidTest`。

测试日志：

```text
Starting 3 tests on emulator-5554 - 15
Finished 3 tests on emulator-5554 - 15
BUILD SUCCESSFUL
```

最终：`3/3 instrumentation PASS`。

## 5. 非阻断警告

GitHub 在成功 run 中提示若干已固定 action仍声明Node.js 20并被runner强制使用Node.js 24运行。该警告没有造成失败，也不属于当前真机或用户文件安全blocker；后续若升级这些action，必须逐个固定新immutable SHA并独立验证，不能借此顺带修改production代码。

## 6. 处置

- 历史`sdkmanager` PATH债务：关闭；
- API 35 x86_64 instrumentation：`3/3 PASS`；
- 模拟器结果仍不能替代Pixel物理设备的Codec、播放、通知、后台、取消或恢复验收；
- 当前release artifact、SHA-256和签名身份保持不变。
