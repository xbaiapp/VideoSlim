# VideoSlim C2/F21 编码器能力诊断真机验收清单

> **日期：** 2026-07-23
> **状态：** `PENDING — NO DEVICE CAPABILITY REPORT`
> **目标版本：** `1.9.0+25`
> **候选源码：** 冻结后填写
> **APK身份：** 构建核验后填写
> **边界：** 本页验证只读能力查询与展示，不授权或验证C1b/C3编码策略。系统capabilities声明也不能替代实际编码测试。

## 1. 测试前记录

- 手机型号：____________________
- Android / GrapheneOS版本：____________________
- SoC：____________________
- 系统安全补丁日期：____________________
- APK文件名：____________________
- App显示版本：____________________
- APK SHA-256：____________________
- 测试日期：____________________

覆盖安装，不要先卸载，以保留设置、F19日志和旧输出。不得把codec名称、能力范围或系统版本猜测预填为结果。

## 2. 入口与只读行为

| # | 用例 | 预期 | 状态 | 证据/备注 |
|---:|---|---|---|---|
| 1 | 首页打开F19调试日志，再点“编码器能力” | 进入独立只读页；说明不会开始压缩或改变设置 | PENDING | |
| 2 | 首次加载 | 显示Android API、固定四种查询mime与能力entry；无媒体任务/前台通知 | PENDING | |
| 3 | 点击刷新 | 重新查询；页面保持可用；旧响应不能覆盖新结果 | PENDING | |
| 4 | 复制能力清单 | 剪贴板得到完整、可读、确定性文本；可粘贴回项目会话 | PENDING | |
| 5 | 返回日志页和首页 | 原日志、选中来源、crop、trim、preset、codec、码率、音频与输出位置不变 | PENDING | |
| 6 | 查询期间启动普通压缩 | C2本身不创建任务；正常任务仍保持单任务/单服务行为 | PENDING | |

## 3. 能力清单核对

四种固定mime：

- [ ] `video/avc`
- [ ] `video/hevc`
- [ ] `video/av01`
- [ ] `video/x-vnd.on2.vp9`

对每个返回entry核对：

- [ ] codec `name`与`mimeType`非空；
- [ ] API 29+显示平台hardware/software/vendor/alias属性；更早系统不把名称启发式伪装成平台事实；
- [ ] CQ/VBR/CBR逐项显示支持或不支持；
- [ ] API 31+显示QP bounds支持状态；更早系统显示“不适用/系统未提供”，不是错误地显示“不支持”；
- [ ] bitrate range与complexity range显示lower/upper；
- [ ] 单个entry查询失败时标记该entry，其余能力仍可查看；
- [ ] 软件、硬件和未知分类不被UI混为一类；
- [ ] 列表顺序刷新前后一致。

## 4. 需要回传的实际清单

把“复制能力清单”结果粘贴到项目会话。至少保留：

```text
Android API:
queried mime types:
codec name / canonical name:
mime:
hardware / software / vendor / alias:
CQ / VBR / CBR:
QP bounds:
bitrate range:
complexity range:
query error（如有）:
```

能力输出：

```text
PENDING — paste the device report here
```

## 5. 回归与安全

- [ ] App仍无`INTERNET`、现代媒体读取或`MANAGE_EXTERNAL_STORAGE`权限；
- [ ] 查询没有创建公开文件、私有临时媒体或recovery journal；
- [ ] 现有M4-B单段trim smoke仍可完成且源文件不变；
- [ ] F19普通日志读取、刷新、复制最近128 KiB和分享完整文件不回归；
- [ ] App关闭重开后能力页仍可重新查询，不依赖持久化伪缓存；
- [ ] 能力页错误只影响诊断页，不锁定首页或媒体任务。

状态：`PENDING`

## 6. 停止条件

出现任一情况立即停止并保留截图、操作步骤和脱敏F19日志：

1. 查询触发压缩任务、通知、WakeLock、文件创建或设置变化；
2. 页面崩溃、卡死、无法返回，或单个codec异常拖垮整页；
3. 现有压缩、trim、crop、audio或文件安全回归；
4. 能力文本缺关键字段、刷新不稳定或复制结果无法回传；
5. 页面把系统声明描述成已经实测有效的编码策略。

## 7. 最终结论

- [ ] `ACCEPTED — capability report captured`
- [ ] `REJECTED — blocker found`
- [x] `PENDING — not yet executed`

项目所有者结论：____________________________________________________

日期：____________________
