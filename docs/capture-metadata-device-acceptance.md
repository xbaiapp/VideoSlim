# VideoSlim 拍摄时间、位置与输出命名真机验收

> **日期：** 2026-07-23
> **候选版本：** `1.6.0+21`
> **候选源代码：** `497c5d2c028213835825f1aea1df5d356450d7f2`
> **候选APK：** `VideoSlim-1.6.0+21-497c5d2-arm64-v8a-release.apk`
> **APK SHA-256：** `f1c035effffafafb319cedec4d1de8fe4f41c84c0009afc6f9ddb66f0e94b6b3`
> **状态：** `BLOCKED — DO NOT START; EXACT-SHA ROUTE A FAIL`
> **范围：** 仅原拍摄时间/GPS保留、MediaStore `DATE_TAKEN`、输出命名及现有视频链路回归

## 1. 验收边界

自动化测试、静态 APK 检查和桌面端容器工具不能替代真实 Android 设备上的 Media3 mux、MediaStore 和 SAF 行为。没有未加工原始素材或没有实际执行的行必须保持 `PENDING`，不得推断为 PASS。

**当前停止条件：** `497c5d2...`在无可靠来源时间时没有覆盖Media3 `System.currentTimeMillis()`缺省时间，也没有核验时间字段必须缺失。当前APK已隔离；新修订通过完整门禁和exact-SHA复审前，不得执行本清单。

验收期间不得：

- 修改或覆盖原视频；
- 在报告、截图、F19 日志或聊天中公开精确 GPS；
- 用处理时间、文件 mtime、设备当前位置或文件名代替原拍摄时间；
- 把音频 M4A 当作应继承视频时间/GPS的输出；
- 为了通过测试增加第二次有损编码、无损 remux、自定义 MP4 writer 或 Media3 升级。

若真实输出证明单次 `InAppMp4Muxer` 无法可靠写入时间/GPS，立即停止；该结果触发实施计划中的规模升级条件。

## 2. 候选身份

安装前记录：

- [ ] APK 文件名与交付记录一致
- [ ] APK SHA-256 与交付记录一致
- [ ] package：`com.videoslim.videoslim`
- [ ] versionName/versionCode：`1.6.0 / 21`
- [ ] 目标设备可正常覆盖安装或干净安装

设备记录：

| 项 | 实测值 |
|---|---|
| 设备型号 | PENDING |
| Android / ROM | PENDING |
| App 首次安装或覆盖安装 | PENDING |
| 系统相册应用 | PENDING |
| SAF DocumentProvider | PENDING |

## 3. 原始素材矩阵

每个来源必须是设备或相机产生的未加工原文件。不要先经聊天软件、云盘“优化”、桌面编辑器或转封装工具处理。

| 编号 | 原始素材 | 来源字段 | 默认 MediaStore | 自定义 SAF | 状态 |
|---|---|---|---|---|---|
| S1 | iPhone SDR MOV | 时间 + GPS | PENDING | PENDING | PENDING |
| S2 | iPhone SDR MOV | 仅时间 | PENDING | PENDING | PENDING |
| S3 | iPhone HDR MOV（若实际使用） | 按实际文件 | PENDING | PENDING | PENDING |
| S4 | Pixel 普通 MP4 | 时间 + GPS | PENDING | PENDING | PENDING |
| S5 | Pixel 普通 MP4 | 仅时间 | PENDING | PENDING | PENDING |
| S6 | Pixel 10-bit HDR/AV1（若实际使用） | 按实际文件 | PENDING | PENDING | PENDING |
| S7 | 明确不含时间/GPS的 MP4 | 两者均无 | PENDING | PENDING | PENDING |

素材不存在时写 `N/A — sample unavailable`，不能写 PASS。

## 4. 每个素材的检查步骤

### 4.1 输入基线

在受控电脑上只读检查原文件：

```bash
exiftool -G1 -a -s INPUT
ffprobe -v error -show_format -show_streams INPUT
```

记录字段是否存在、时间是否可解释及 GPS 是否存在。共享报告只记录 `present/missing/match/mismatch`，精确坐标保留在本地私有验收资料中。

### 4.2 App 转码

1. 通过 Photo Picker 或 SAF 选择原始素材。
2. 使用“均衡”完成一次普通视频压缩；有裁剪样本时另做一次已验收的画面裁剪流程。
3. 确认处理期间仍只有一个前台任务，取消/通知/后台行为没有变化。
4. 若出现“无法确认原拍摄时间或位置已保留”，确认没有公开输出，不要反复自动重试。
5. 分别验证默认 `Movies/VideoSlim` 和持久化 SAF 自定义文件夹。

### 4.3 输出内部字段

把输出复制到受控电脑后执行：

```bash
exiftool -G1 -a -s OUTPUT.mp4
ffprobe -v error -show_format -show_streams OUTPUT.mp4
```

逐项判断：

- [ ] 来源有受支持拍摄时间时，输出时间存在并按 MP4 秒精度匹配
- [ ] 来源无可靠拍摄时间时，输出未用处理时间或文件 mtime伪造
- [ ] 来源有受支持 GPS 时，输出位置存在并在每轴 `0.0001°` 容差内匹配
- [ ] 来源无可靠 GPS 时，输出未加入设备当前位置
- [ ] 输出没有承诺复制设备型号、软件版本、完整 XMP/mdta、Apple 私有 atom 或 Pixel `mett`
- [ ] 视频时长、方向、画面、音轨和播放正常

### 4.4 MediaStore 与 SAF

默认 MediaStore：

- [ ] 系统媒体行的 `DATE_TAKEN` 来自已验证的原拍摄时间
- [ ] 相册按原拍摄时间显示/排序，或记录具体图库应用不采用该列的行为
- [ ] 不出现写入中或失败的半成品

自定义 SAF：

- [ ] 文件内部时间/GPS与默认输出一致
- [ ] 文件长度与发布结果有效、可完整回读和播放
- [ ] 不把 SAF 文件是否立即进入图库当作失败
- [ ] 不要求 SAF provider 提供 MediaStore 索引日期

## 5. 输出命名

| 场景 | 期望 | 状态 |
|---|---|---|
| HEVC 最终计划 | `_slim_h265_target<kbps>k_<yyyyMMdd_HHmmssSSS>_<4hex>.mp4` | PENDING |
| HEVC 不可用后 H.264 fallback | 名称使用 `h264`，不保留原始 `h265` 选择 | PENDING |
| AAC 音轨直提 | `_audio_copy_<yyyyMMdd_HHmmssSSS>_<4hex>.m4a` | PENDING |
| AAC 重编码 | `_audio_aac_target<kbps>k_<yyyyMMdd_HHmmssSSS>_<4hex>.m4a` | PENDING |
| copy不兼容后AAC重试 | 名称从 `_audio_copy_` 更新为 `_audio_aac_target128k_`，不沿用旧模式名 | PENDING |
| Unicode/长名称 | 危险字符已清理，完整 UTF-8 名称不超过 240 bytes | PENDING |
| 同毫秒或已有同名 | provider 最终分配不覆盖已有文件 | PENDING |

文件名中的时间只表示处理时间，不应与原拍摄时间比较；`target` 也不表示硬件 VBR 的实测平均码率。

## 6. 稳定链路回归

- [ ] 未裁剪普通压缩：播放、方向、codec、音轨正常
- [ ] 已验收裁剪框和慢速拖动行为未变化
- [ ] `Crop → Presentation` 输出方向与框选一致
- [ ] HEVC/H.264 fallback 正常
- [ ] 原音轨 copy、AAC re-encode、remove 正常
- [ ] HDR 输入沿既有 tone-map/兼容策略运行
- [ ] 后台、锁屏、通知和取消正常
- [ ] 强杀/恢复后没有半发布输出或误删旧文件
- [ ] 源文件和旧输出未被修改、覆盖或删除

任何用户文件丢失/覆盖、不可解释裁剪错位、方向回归或单次 mux 无法保留字段，均阻止接受。

## 7. 接受记录

| 决策 | 结果 |
|---|---|
| 自动化与静态 APK 检查 | 由候选完成报告记录 |
| 真实来源 metadata | PENDING |
| MediaStore `DATE_TAKEN` | PENDING |
| SAF 内部 metadata | PENDING |
| 输出命名 | PENDING |
| 稳定链路回归 | PENDING |
| 项目所有者最终决定 | PENDING |

项目所有者明确回复接受前，状态保持 `DEVICE ACCEPTANCE PENDING`。
