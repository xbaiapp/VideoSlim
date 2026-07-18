# VideoSlim · M0 任务书：远程 VPS 无头开发环境搭建

> **用法**：将本文档整体发给 Hermes Agent 执行。执行者为 Hermes，验收者为项目所有者（用户）。
> 项目全貌见《VideoSlim PRD》v1.1，本任务只做环境搭建 + Hello World，**不要提前实现任何业务功能**。

## 任务目标

在本台 Ubuntu VPS 上搭建 Flutter Android **无头（headless）**开发环境，创建项目 `videoslim`，构建出可安装的 APK 并交付到项目所有者的手机。完成标志：手机上运行 Flutter 默认计数器 App。

## 背景约束

- 本机无图形界面：**不安装** Android Studio，**不安装**模拟器，全程命令行；
- 构建目标仅 Android arm64；
- 环境变量写入 shell 配置文件（如 `~/.bashrc`）保证重启后仍有效。

---

## 步骤 0：环境自检（先做，不达标先解决）

1. `df -h ~` —— 可用磁盘 ≥ 20 GB（SDK + Gradle 缓存所需）；
2. `free -h` —— 内存 < 8 GB 时创建 8 GB swap 并写入 `/etc/fstab` 持久化（否则后续 Gradle 构建会被 OOM 杀掉）；
3. 确认可访问 `dl.google.com` 与 `storage.googleapis.com`（下载 SDK 必需）。

## 步骤 1：基础依赖

```bash
sudo apt update && sudo apt install -y openjdk-17-jdk git curl wget unzip zip xz-utils libglu1-mesa
```

验证：`java -version` 显示 17.x。

## 步骤 2：Android SDK 命令行工具

- 先从官方页面 `https://developer.android.com/studio#command-line-tools-only` 获取最新 `commandlinetools-linux-*.zip` 的下载链接（版本号随时间变化，以页面为准）。
- 参考命令：

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
wget <官方最新链接> -O tools.zip && unzip -q tools.zip && mv cmdline-tools latest && rm tools.zip
```

- 环境变量（写入 `~/.bashrc` 后 `source`）：

```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

- 安装组件并接受许可证（platform/build-tools 用当前最新稳定版，两者版本保持一致）：

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 步骤 3：Flutter SDK

```bash
cd ~ && git clone https://github.com/flutter/flutter.git -b stable --depth 1
```

- 将 `$HOME/flutter/bin` 加入 PATH（写入 `~/.bashrc`）；
- 然后：

```bash
flutter config --no-analytics
flutter doctor --android-licenses
flutter doctor -v
```

**判定标准（重要）**：`[✓] Flutter` 与 `[✓] Android toolchain` 两项**必须**通过。`Chrome`、`Android Studio`、`Connected device` 三项缺失/警告属**预期，忽略**。其余任何 ✗ 必须解决后再继续。

## 步骤 4：创建并构建项目

```bash
flutter create videoslim --org com.videoslim
cd videoslim
flutter build apk --release --split-per-abi
```

- 产物：`build/app/outputs/flutter-apk/app-arm64-v8a-release.apk`；
- 说明：Flutter 模板的 release 构建默认使用 debug 签名，可直接安装，本阶段**不要**配置正式签名；
- 若构建进程被杀（日志出现 `Killed` / OOM）：确认 swap 已启用，并在 `android/gradle.properties` 中设置 `org.gradle.jvmargs=-Xmx2g` 后重试。

## 步骤 5：交付 APK 到手机

- **首选**：通过当前聊天渠道把 `app-arm64-v8a-release.apk` 作为文件直接发给项目所有者；
- 备选：`cd build/app/outputs/flutter-apk && python3 -m http.server 8000`，告知用户用手机浏览器访问 `http://<VPS公网IP>:8000/app-arm64-v8a-release.apk` 下载；如有防火墙需临时放行 8000 端口；**用户下载完成后立即停止服务并关闭端口**。

## 步骤 6：用户侧操作（把这段发给项目所有者）

1. 手机上打开收到的 APK 安装；首次会提示允许该来源"安装未知应用"，允许后继续；
2. 打开 App，应看到 Flutter 默认计数器界面；点右下角按钮，数字加一。

---

## 最终验收清单（全部满足则 M0 完成）

- [ ] `flutter doctor`：Flutter 与 Android toolchain 通过
- [ ] `app-arm64-v8a-release.apk` 构建成功
- [ ] APK 已送达手机并安装成功
- [ ] 默认 App 在真机运行正常，计数按钮有效

完成后向项目所有者汇报结果，等待进入 M1（见 PRD 第 8 章，M1 需同批实现 F19 应用内日志页）。

## 常见故障速查

| 现象 | 处理 |
|---|---|
| sdkmanager 报 Java 版本错误 | 确认使用 JDK 17（`java -version`、`JAVA_HOME`） |
| 构建进程被 `Killed` | 内存不足 → 启用 swap + `org.gradle.jvmargs=-Xmx2g` |
| 许可证相关报错 | 重新执行步骤 2 与步骤 3 中的两条许可证接受命令 |
| 下载 SDK 超时/失败 | 检查 VPS 对 Google 域名的连通性 |
| 手机提示无法安装 | 核对文件大小确认下载完整；确认手机为 arm64 架构（近 8 年内机型均是） |
