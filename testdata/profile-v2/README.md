# Profile v2 shared contract corpus

此目录只保存供 Schema、Kotlin 和 Go 回归测试共同消费的合同样本，不是可发布
Profile 目录，也不会被 Android 正式运行时自动执行。

## Golden

- 文件：`golden/token_multimodal_standard.seed-20260716.json`
- 来源模型：`tools/aneb-ai-behavior-model/models/token_multimodal_hypothesis_v0.1.json`
- seed：`20260716`
- PRNG：`pcg32-v1`
- measurement 数量：`26`
- canonical Profile SHA-256：
  `sha256:de034840850362fb80b829090c2a7a2dc3b8e3c509622b2edd620b1b2528a7af`
- behavior model SHA-256：
  `sha256:ee638c9c755d49d8af51074ea57333ca51223559af99b9ca600146af834b503c`

该文件必须由 `build_artifacts(model, seed).profile` 确定性生成，禁止手工调整
字段或门限。Python 测试会重新生成并做语义等价校验。

## 边界

- Schema 负责最低 wire 结构；
- Kotlin 负责语义与引擎能力 fail-closed；
- Go 负责启动期 envelope 校验和权威 wire 无损下发。

模型状态仍为 `hypothesis`，不能声称代表任何厂商的真实表现。当前模型只生成
10 个逻辑任务，尚未达到正式 standard 要求的至少 20 个任务；本 golden 仅冻结
当前合同形状，不能作为发布 Profile、评分基线或模型验证证据。
