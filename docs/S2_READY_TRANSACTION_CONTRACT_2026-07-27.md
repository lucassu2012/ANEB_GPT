# S2 Quick READY 机械事务合同

> 日期：2026-07-27
>
> 范围：只收敛 Realtime / Network 已实证的 READY 发布与消费机械；不合并业务判定器，不改变任何冻结证据。

## 1. 反方边界

- ［KNOWN｜HIGH］三个 Quick 家族的“业务正确”不是同一件事。Token 的任务/KPI/receipt 语义、Realtime 的流式/回合语义、Network 的四原语/UDP 同-run 语义不得进入共用模块。
- ［KNOWN｜HIGH］本切片不迁移 Token publisher/consumer。Token 先作为未迁移兼容对照；只有共用机械在 Realtime/Network 上通过故障注入与真实冻结 READY 等价性后，才允许设计 Token Adapter。
- ［KNOWN｜HIGH］本切片不触碰 P40、E-01、Profile、Room schema、评分、门限或采集器；它不能自行生成新的业务证据。

## 2. 共用 Interface

`scripts/quick_ready_transaction.py` 暴露两个稳定对象：

1. `QuickReadyContract`：冻结 family 的 collection/READY 文件名正则、release/report/publication schema 及版本。
2. `QuickReadyAdapter`：family 只提供 `verify_private_root(bundle)` 与 `verify_collection(bundle)`；所有业务重算继续由 family verifier 完成。

共用模块只拥有：

- 私有根当前态校验的调用顺序；
- canonical JSON、重复 key/NaN/UTF-8/大小上限检查；
- 目录与 regular-file/reparse/path race 防护；
- verification report 的 exclusive write、fsync、no-replace publish；
- READY 对 report/manifest/run/mode 的摘要绑定；
- consumer 重新调用 family collection verifier；
- postcheck 失败或操作者中断时，只回滚本事务新建的 sibling 文件。

Family wrapper 继续拥有原 CLI、异常类型和输出 schema，因此外部调用者不需要知道深模块存在。

## 3. 不变量与故障门

| 不变量 | fail-closed 行为 |
|---|---|
| READY 不是最后一个原子 sibling | 不得宣称发布成功 |
| report/READY 任一 final 或 partial 已存在 | `release_path_collision`，不得覆盖 |
| report 摘要、manifest/run/mode 或重算结果漂移 | consumer 拒绝 |
| 私有根 ACL/reparse/current-state 漂移 | collection verifier 前拒绝 |
| postcheck 失败 | 删除本事务新建 report/READY/partial，保留 bundle |
| `KeyboardInterrupt` / `SystemExit` | 清理新产物后原样传播，不改写成业务 reason code |
| family 业务 verifier 失败 | 保留 family reason；不得由共用模块猜测业务语义 |

## 4. 已执行证据

- ［KNOWN｜HIGH］新模块单测 3/3、Realtime/Network 原 release 回归 12/12、四个 direct CLI wrapper 1/1 组合测试 PASS。
- ［KNOWN｜HIGH］当前 S2 consumer 对六份真实冻结 READY 只读重算均 PASS：Token 正/负（未迁移对照）、Realtime 正/负、Network 负向与恢复后的权威正向；输出 READY/report/manifest SHA 与既有记录精确一致。
- ［KNOWN｜HIGH］Network 首份正向本地 bundle 因普通 SQLite 读取触发 checkpoint 后，当前 consumer 以 `manifest_file_binding_mismatch` 正确拒绝；没有把失败归因成模块回归，也没有重建 WAL/SHM。恢复采集的新权威 READY 为 `e153ee46…c3837`。
- ［KNOWN｜HIGH］稳定 S2 工作树上的完整 `scripts/quality_gate.ps1` 自然退出 `0`：主 Python 804/804 PASS（16 项按合同跳过）、附加 Python 44/44 PASS，Android 构建/单测/lint/assemble、Go server/gateway、release boundary、secret scan、spec/schema 与候选 APK 打包全部 PASS；结束后无 quality gate、unittest、Gradle 或 Go 测试残留进程。

## 5. 下一切片

［INFERRED｜HIGH］下一步先把 Realtime/Network 仍然通过 import 继承的 phone/remote/provenance 机械采集收敛成显式 provider/adapter，再设计 Token Adapter。顺序固定为：

1. 中立 collector/verifier mechanics；
2. Realtime/Network 显式 Adapter 与故障注入；
3. Token compatibility fixture → Token Adapter；
4. 三族完整回归与 clean CI，之后才替换更多入口。

### S2-V1 中间里程碑：verifier primitives

- ［KNOWN｜HIGH］`scripts/quick_collection_verifier.py` 已成为不导入任何业务家族的底层 verifier 原语模块，拥有 regular-file/reparse/race 防护、canonical JSON、摘要、相对路径、UUID、HTTPS endpoint 与 Android component 规范化。
- ［KNOWN｜HIGH］Realtime 已从该模块取得上述原语；Network 的上述原语不再从 Realtime 继承。新模块 + Realtime + Network 三模块聚焦回归 26/26 PASS。
- ［KNOWN｜HIGH］release 与跨族补跑再验 16/16 PASS，合计聚焦/跨族 42/42 PASS。当前快照的完整质量门运行 807 项：790 PASS、16 SKIP、唯一 ERROR 是既有 Token evidence verifier 子进程超过固定 120 秒；该唯一测试随后单独复现 1/1 PASS（13.924 秒）。因此本轮完整门禁不得记为 PASS，也没有证据把超时归因于 S2-V1。
- ［KNOWN｜HIGH］Network 仍从 Realtime 取得 candidate、manifest、phone、remote、lock、serverinfo 与 COMPLETE 等高层机械函数；因此 S2-V1 只是可审计的依赖切口，不等于第 1 步“中立 collector/verifier mechanics”完成。
- ［INFERRED｜HIGH］下一提交应把这些高层函数改为由显式 `QuickCollectionVerifierAdapter` 配置合同驱动；只有 Network 文件不再 import Realtime verifier 之后，才关闭 verifier 半边的反向依赖。
