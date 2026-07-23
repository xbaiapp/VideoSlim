# VideoSlim C2/F21 exact-SHA复审处置

> **日期：** 2026-07-23
> **复审轮次：** 每任务唯一一次双路复审
> **被复审SHA：** `85e249726e36325e9532e641f296854b8c8d1eb2`
> **被复审tree：** `42a03c5a7c83686b9c7924d763e41efdfa598dee`
> **纠正候选SHA：** `11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`
> **纠正候选tree：** `bcc2cbed2fe44027ea38c4d6973f126a7661faaf`
> **最终评级：** `CORRECTED PRIVATE CANDIDATE — NO INDEPENDENT EXACT-SHA REVIEW PASS`

## 1. 复审结果

首个冻结SHA同时发起两个互不依赖、只读的exact-SHA reviewer：

| 路线 | 目标 | 结果 |
|---|---|---|
| specification/compliance | contract、范围、API语义、测试和文档一致性 | `NO VERDICT — 600s TIMEOUT` |
| code-quality/runtime-safety | API 26–36、channel/dispatcher、UI异步与媒体/文件安全边界 | `NO VERDICT — 600s TIMEOUT` |

两个reviewer都检查了exact Git对象、核心实现、测试和相邻媒体不变量，也运行了Flutter检查；但都在Android Gradle命令完成前达到600秒上限，没有输出规定格式的`VERDICT`和`VERIFIED_SHA`最终块。进程退出、进行中的分析或“未发现问题”的中间推理都不能替代裁决，因此两路均不得记PASS，也不得推导为BLOCKERS。

原始只读transcript保存在：

- `/root/artifacts/videoslim/c2-encoder-capabilities/85e249726e36325e9532e641f296854b8c8d1eb2/spec-review-raw.log`
- `/root/artifacts/videoslim/c2-encoder-capabilities/85e249726e36325e9532e641f296854b8c8d1eb2/quality-review-raw.log`

## 2. 控制器门禁发现

同一SHA的controller exact gate随后确定失败：

```text
:app:lintDebug — FAIL
5 errors: NewApi
MediaCodecInfo.getCanonicalName / isAlias /
isHardwareAccelerated / isSoftwareOnly / isVendor
```

原实现使用由`apiLevel >= 29`计算出的普通Boolean局部变量保护五个API 29 getter。运行时意图正确，但Android lint不能把普通Boolean证明为SDK guard，release门禁因此无法通过。首个SHA `85e2497...`被明确否决，未构建为可交付候选。

## 3. 唯一纠正修订

纠正SHA `11f169ca9f30b2f05eeeec777dbaaaf71a01f7ff`只修改`EncoderCapabilityReader.kt`：

1. 使用lint可识别的直接`Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`分支；
2. 把API 29 getter集中在`@RequiresApi(Build.VERSION_CODES.Q)` helper；
3. API 26–28继续不调用这些getter并返回null分类属性；
4. 不改变wire、UI、dispatcher或任何媒体处理路径。

纠正后证据：

- focused `EncoderCapabilityReaderTest`与`MediaIoRoutingPolicyTest`：PASS；
- focused `lintDebug`：PASS；
- exact Flutter format/analyze/tests：PASS，`257/257`；
- exact Android JVM：PASS，`350/350`；
- exact `lintDebug`、`lintRelease`、`assembleDebug`：PASS；
- ARM64 APK独立静态核验：PASS。

## 4. 评级边界

项目治理限制每任务最多一次实现、一次修订和一轮复审。因此没有为纠正SHA发起第二轮review。必须保持以下区分：

- 首个SHA：有双路复审尝试，但两路均无最终裁决，且controller门禁失败；**不得使用**。
- 纠正SHA：自动化、lint、build和APK证据完整；但**没有独立exact-SHA review PASS**。
- 后续docs-only commit：只记录来源、hash和处置；不是APK构建源码，也没有把旧review转移到新SHA。

依据私有项目的低风险候选政策，纠正SHA可晋级为`CANDIDATE READY — DEVICE CAPABILITY REPORT PENDING`，但不得描述为“reviewed PASS”、production release或真机能力已验证。若未来政策要求独立exact-SHA PASS，必须由项目所有者另行授权新的复审轮次。
