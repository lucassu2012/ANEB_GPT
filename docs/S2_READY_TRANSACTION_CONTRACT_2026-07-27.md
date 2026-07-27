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
