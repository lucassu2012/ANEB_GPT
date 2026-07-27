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

### S2-V2 中间里程碑：显式高层 verifier adapter

- ［KNOWN｜HIGH］`scripts/quick_collection_verifier_adapter.py` 以不可变合同承载 manifest、candidate provenance、phone pair、device identity、remote snapshot、lock、serverinfo、mode inventory、evidence-root 与 COMPLETE 的共享验证算法；模块自身零 Token/Realtime/Network collector/verifier import。
- ［KNOWN｜HIGH］Network verifier 已不再导入 `verify_realtime_quick_collection` 或 `collect_realtime_quick_evidence`；Network 自身只提供 schema、候选身份、手机/设备合同、远端 marker、serverinfo 与 evidence-root 回调，Network cross-evidence 业务重算没有进入共享模块。
- ［KNOWN｜HIGH］S2-V2 RED 是 adapter 模块不存在的唯一 ImportError；GREEN 后 adapter 合同 3/3、Network 6/6、primitives/adapter/Realtime/Network 同进程 29/29、Realtime/Network release 12/12 PASS，终态残留进程均为 0。
- ［KNOWN｜HIGH］Network 仍导入历史命名的 `verify_realtime_evidence_security`，Realtime 自身也尚未改由 Adapter 驱动；所以 S2-V2 只关闭 Network→Realtime collector/verifier 的反向依赖，不等于 verifier 全部收敛。
- ［INFERRED｜HIGH］下一切片固定为：先把 evidence security 改为中立模块并保留兼容入口，再让 Realtime 使用同一 Adapter、删除重复高层算法；之后才设计 Token compatibility adapter。

### S2-V3 中间里程碑：中立证据安全与双族 adapter 收敛

- ［KNOWN｜HIGH］证据根安全实现已迁至不带业务族名称的 `scripts/quick_evidence_security.py`；Realtime collector、Realtime/Network publisher、collection verifier 与 release consumer 均直接导入该中立模块。历史 `verify_realtime_evidence_security` 仅保留同一模块身份的兼容入口，避免旧调用方与 monkeypatch 形成两份状态。
- ［KNOWN｜HIGH］Realtime collection verifier 已改由与 Network 相同的 `QuickCollectionVerifierAdapter` 驱动，删除 manifest、candidate、phone、device identity、remote、lock、serverinfo、mode inventory、evidence-root 与 COMPLETE 的重复实现；Realtime 只保留自身 plan/status/run、serverinfo 业务约束与 cross-evidence 重算。
- ［KNOWN｜HIGH］TDD 证据为：neutral security RED 仅因模块不存在；compatibility 3/3、legacy+neutral security 9/9、adapter 结构 4/4、共享 core+adapter+Realtime/Network collection 30/30 PASS；最终 security/collector/双族 collection/release 生产链组合 97/97 PASS。所有执行窗口结束后匹配测试残留进程均为 0。
- ［KNOWN｜HIGH］本里程碑没有修改冻结证据、Profile、KPI、评分、门限或真机/服务器状态，也没有把 Realtime/Network 的业务判定器并入共享模块。
- ［INFERRED｜HIGH］下一切片是 Token compatibility fixture 与 Token Adapter；在 Token 迁移和三族完整门禁/CI 通过前，仍不得宣称 S2 三族 verifier 全部收敛。

### S2-V4a 中间里程碑：Token mode 方言兼容切口

- ［KNOWN｜HIGH］Token 的 READY/verification 使用 `execution_mode={positive,negative_receipt_missing}`，Realtime/Network 使用 `mode={positive,negative}`；把前者强行改名会破坏已冻结证据，把后者放宽为任意字符串会削弱既有门禁。
- ［KNOWN｜HIGH］`QuickReadyContract` 现显式冻结 `mode_field` 与不可变 `mode_values`。默认值保持原 Realtime/Network 合同；Token 兼容夹具可选择 `execution_mode` 与精确两值。mode key 与 READY 身份字段碰撞、值不在白名单时均 fail closed，发布失败会回滚新建 report/READY。
- ［KNOWN｜HIGH］TDD 证据：RED 唯一错误为构造器尚不接受 Token mode 合同；GREEN/安全用例 6/6 PASS。首次双族回归在加载期发现公开 `READY_KEYS` 兼容常量被改名，未执行测试逻辑；恢复默认兼容面后 core + Realtime/Network release 18/18 PASS，前后测试残留为 0。
- ［KNOWN｜HIGH］本切口只证明共享 READY 事务能无损表达 Token 的 mode 方言；Token publisher/consumer 仍未接入共享事务，不能标记 Token Adapter 或 S2 完成。
- ［INFERRED｜HIGH］下一步以现有 Token release 故障注入套件作等价性夹具，抽取可配置的 READY/digest/COMPLETE 机械层；必须保留 Token 的精确 reason code、外部工具闭包和正负业务重算，不得用无操作 adapter 冒充迁移。

### S2-V4b 中间里程碑：Token READY marker consumer 接入

- ［KNOWN｜HIGH］Token release consumer 已声明不可变 `TOKEN_READY_CONTRACT`；历史 `execution_mode` 两值域和 READY 精确 key 集由共享合同派生，不再在 Token 模块复制 marker key/schema/run/mode/binding/timestamp 算法。
- ［KNOWN｜HIGH］共享 `ready_marker_failure` 只分类 `keys/contract/identity/binding/timestamp`，不选择业务族 reason code。Token 将分类机械映射回既有 `release_ready_*_invalid`，Realtime/Network 仍维持原有 contract/timestamp 失败面。
- ［KNOWN｜HIGH］Token 自身仍额外验证 collection stamp 的真实日历合法性；manifest、verification report、COMPLETE、文件摘要、外部工具闭包、正负业务语义和 publisher 均未迁入共享层。
- ［KNOWN｜HIGH］TDD 证据：结构 RED 唯一为 `TOKEN_READY_CONTRACT` 缺失；GREEN 1/1。共享 core + Token/Realtime/Network release 回归 46/46（另有 4 项平台相关 skip），删除两条已无引用的 Token 重复正则后同一套件再次 46/46；每次测试前后匹配残留进程均为 0。
- ［INFERRED｜HIGH］下一切片只抽取 digest/COMPLETE 纯机械分类，并继续用 Token 故障注入套件锁定历史失败码；在 publisher 与完整 consumer 等价迁移、三族完整门禁和 CI 全绿前，S2 仍不得标记完成。

### S2-V4c 中间里程碑：三族 COMPLETE 确定性编码

- ［KNOWN｜HIGH］Realtime/Network 与 Token 的 COMPLETE 都绑定 collection、run 和 manifest SHA；唯一格式差异是 Token 还冻结 `manifest=evidence-manifest.final.json`。中立 `build_complete_marker` 以可选 `manifest_leaf` 明确表达该差异，不用业务族分支猜格式。
- ［KNOWN｜HIGH］既有 Realtime/Network `verify_complete` 已改为调用该编码器；Token release consumer 也改用相同编码器后做字节全等，仍保留本族 `release_complete_mismatch`。读取上限、路径安全、manifest/report 解析和所有业务判定均未移动。
- ［KNOWN｜HIGH］TDD 证据：RED 唯一为公共编码器不存在；GREEN 1/1。adapter + Token release + Realtime/Network collection 兼容回归 56/56（另有 4 项平台相关 skip），测试前后匹配残留进程为 0。
- ［KNOWN｜HIGH］本里程碑只收敛 COMPLETE marker 的确定性字节构造；Token digest、manifest、verification report、identity closure、外部工具闭包、publisher 和业务重算仍由 Token 自身负责。
- ［INFERRED｜HIGH］下一切片是摘要绑定的业务族中立分类与 Token 历史 reason-code 映射；在故障注入等价性、完整三族门禁和 clean CI 通过前，S2 仍不得标记完成。
