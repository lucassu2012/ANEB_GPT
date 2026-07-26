# M0-EC2 AI 实时 Quick 跨端执行合同范围

> 冻结日期：2026-07-25（Asia/Shanghai）
>
> 目标 Profile：`ai_realtime_voice_quick`
>
> 前置基线：M0-EC1 Token Quick 正负 READY 已完成；E-01 当前保持
> `aneb-server/0.8.0`，本阶段离线实现与门禁完成前不得部署。

## 1. 先给反方观点

- ［KNOWN｜HIGH］把 Token Quick 的三个 HTTP 原语机械复制到 AI 实时不成立。AI 实时的业务主体是
  一个有状态 WebSocket 会话，只有握手次数而没有帧级业务签名，无法证明 App 真正执行了 3 轮、
  400 个上行帧、676 个有效下行帧和一次打断。
- ［KNOWN｜HIGH］现有实时引擎会直接打开 `/api/v1/realtime-sim`，没有在业务流量前验证节点能力；
  现有服务器又把该路由审计为 `/api/v1/other`。因此当前真机成功结果不能冒充 M0-EC2 执行合同证据。
- ［KNOWN｜HIGH］本阶段只覆盖 AI 实时 **Quick**。Standard、Recovery、RTP/WebRTC、真实厂商语音
  画像、外场无线质量和正式 Release 都不是本切片目标。
- ［INFERRED｜HIGH］最小但可扩展的方案是抽取通用执行门、服务器白名单注册表和参数化审计骨架，
  再给实时流增加有界协议摘要；复制 `TokenExecutionContractGate` 会制造第三个 Profile 时必须再次重写的债务。

## 2. 当前事实与可计算业务签名

| 项目 | 当前值 | 证据边界 |
|---|---:|---|
| Profile | `ai_realtime_voice_quick@1.1.1` | ［KNOWN｜HIGH］本地候选已加入冻结的 `execution_requirements`；规范化 Profile SHA-256=`701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7` |
| runtime | `aneb-realtime-runtime-plan-v1` | ［KNOWN｜HIGH］当前 manifest runtime SHA-256=`f2472d2faa7a3ab51582e1496a6925d106806fdd9747e097e20e38e921d9dc07` |
| session | 1 | ［COMPUTED｜HIGH］来自 create-once `runtime_plan.json` |
| turn | 3 | ［COMPUTED｜HIGH］2 个完整轮次 + 1 个打断轮次 |
| frame cadence | 20 ms | ［KNOWN｜HIGH］固定业务帧节奏 |
| uplink | 400 帧 / 256000 字节 | ［COMPUTED｜HIGH］只计算应用负载，不含 WebSocket/TCP/IP 开销 |
| planned downlink | 800 帧 / 768000 字节 | ［COMPUTED｜HIGH］未考虑打断前的计划值 |
| effective downlink | 676 帧 / 648960 字节 | ［COMPUTED｜HIGH］第三轮在第 101 帧打断 |
| barge-in | 1 次，stop ≤300 ms | ［KNOWN｜HIGH］来自冻结计划 |

## 3. 候选版本边界

| 单元 | M0-EC2 候选 | 变更原因 |
|---|---:|---|
| P1 Android | `0.5.13-codex` / code 45 | ［INFERRED｜HIGH］新增实时业务前能力门、审计头和拒绝持久化 |
| P2 server | `aneb-server/0.8.1` | ［INFERRED｜HIGH］向后兼容增加实时原语、Profile 能力与审计，不删除 0.8.0 接口 |
| P3 behavior model | `0.3.2` | ［INFERRED｜HIGH］生成器为实时 Quick 发布执行要求，业务画像仍为 hypothesis 0.2.0 |
| Profile catalog | `1.6.0` | ［INFERRED｜HIGH］新增第二个 execution evidence contract |
| AI realtime Quick | `1.1.1` | ［INFERRED｜HIGH］运行计划和评分不变，只增加强制执行要求与新的规范化 Profile 摘要 |

## 4. 冻结执行要求

［FRAME｜HIGH］`ai_realtime_voice_quick@1.1.1` 增加以下受限声明：

```json
{
  "contract_id": "aneb-execution-requirements",
  "contract_version": "1.0.0",
  "client_engine": {
    "contract_id": "aneb-realtime-simulation-engine",
    "min_version": "1.0.0",
    "max_version_exclusive": "2.0.0"
  },
  "server_capability_receipt": {
    "contract_id": "aneb-server-capability-receipt",
    "min_version": "1.0.0",
    "max_version_exclusive": "2.0.0"
  },
  "required_primitives": [
    {
      "primitive_id": "realtime_sim",
      "wire_contract_id": "aneb-realtime-session-v1"
    }
  ]
}
```

- ［KNOWN｜HIGH］该声明不允许任意 URL、脚本、额外 query 或服务端故障开关。
- ［KNOWN｜HIGH］服务器只认可源码注册表中精确冻结的 Profile 身份、版本、mode、target、claim、
  client engine 和原语集合；不能因为 JSON 自洽就自动信任新 Profile。
- ［KNOWN｜HIGH］旧 11 个未迁移 Published Profile 继续兼容；Token Quick 1.2.1 的现有回执和证据
  语义不得变化。

## 5. 正向业务与审计合同

［FRAME｜HIGH］新增
`spec/execution-contracts/ai_realtime_voice_quick-1.1.1.protocol.json`，同时冻结：

1. 业务前能力门必须验证本地 APK 内 manifest、规范化 Profile SHA、客户端引擎区间、
   服务器回执版本、`realtime_sim/aneb-realtime-session-v1` 原语和服务器
   `validated_profiles` 中的精确 Profile 身份。
2. request-entry 必须是 `GET /api/v1/realtime-sim = 1`；请求带同 run 的规范 UUID 和
   `realtime_run` 审计作用域。
3. 服务端只输出隐私有界的会话摘要：同进程 instance、run UUID、session/turn 数、上下行帧数、
   打断次数与 `protocol_ok`；不记录音频负载、用户文本、地址或任意 header。
4. 客户端冻结结果必须为 1 session / 3 turns / 400 uplink / 676 effective downlink /
   1 barge-in，且与 Profile/runtime SHA、服务端摘要、request-entry 窗口和同一 App provenance 交叉绑定。
5. start/end barrier、能力回执和业务握手必须在同一服务器进程、连续审计窗口且无 drop；
   窗口内出现未归因业务仍 fail closed。

## 6. 负向合同

- ［KNOWN｜HIGH］复用一次性 loopback 代理，只从真实上游 `/serverinfo` 删除
  `execution_capabilities`；不修改 E-01、不打开 WebSocket。
- ［FRAME｜HIGH］期望机器原因固定为 `receipt_missing`，`GET /api/v1/realtime-sim = 0`，
  客户端 session/turn/帧产物为零，score/grade 为 null，终态只持久化一次。
- ［KNOWN｜HIGH］实时结果信封可保留 required metric 的显式 `missing` 状态；“零业务产物”指
  零 session、零 turn、零 observed frame 和零可计算业务指标，不要求删除 Schema 定义的缺失占位。
- ［KNOWN｜HIGH］结束时必须精确清除本轮 reverse/代理，停止本轮 App/服务，恢复 `stayon`，
  返回 Huawei Launcher 并按实时现场清单复核；服务器 marker/lock/临时文件必须为零。

## 7. 差距矩阵与落地顺序

| 门 | 应实现的内容 | 当前状态 | 下一验收 |
|---|---|---|---|
| EC2-01 Profile/P3 | generator、schema、Profile 1.1.1、manifest、catalog 1.6.0 与协议合同 | ［KNOWN｜HIGH］本地完成；behavior model 0.3.2、Profile SHA 与 runtime SHA 已进入 catalog/manifest 漂移测试 | clean commit + CI 重算 |
| EC2-02 P2 能力 | fail-closed Profile 注册表、`realtime_sim` 原语并保留 Token 兼容 | ［KNOWN｜HIGH］本地完成；服务器候选为 `aneb-server/0.8.1`，未知 Profile/原语/版本拒绝测试已覆盖 | 同提交 Linux candidate + E-01 受保护切换 |
| EC2-03 P2 审计 | 精确实时路由、`realtime_run` scope、有界会话摘要及隐私/污染门 | ［KNOWN｜HIGH］本地完成；并发、缺号、未归因流量、摘要不一致与日志隐私均 fail closed | 真机同-run 原始 journal 重算 |
| EC2-04 P1 能力门 | Quick 在首个 WebSocket 前验证本地/节点/Profile/原语合同 | ［KNOWN｜HIGH］本地完成；能力缺失/冲突在业务前拒绝，Standard/Recovery 保持兼容 | CI APK + P40 正负请求计数 |
| EC2-05 P1 持久化 | 固定机器 reason、零业务产物、null score/grade、单次 Room 终态 | ［KNOWN｜HIGH］本地完成；Room v19、结果信封、日志顺序和二次插入防护均有正反例 | 真机冻结 DB/WAL/SHM 独立复算 |
| EC2-06 采集/复核 | 实时 collector、DB/audit/协议摘要/cross-bundle verifier 与 D-87 READY | ［KNOWN｜HIGH］完成；正/负模式库存分离、私有根 ACL、manifest、独立 report 与 READY 最终消费者已闭合；两次真实 collection 均发布 READY | 已结案；两个 READY 均由独立 consumer 重算通过 |
| EC2-07 离线门 | Kotlin/Go/Python 正反例、全仓质量门、凭据扫描与 clean commit | ［KNOWN｜HIGH］完成；最终 source `fe60c1c` 的完整 quality gate 为主 Python 747 项通过（16 项设计跳过）、behavior model 43/43、Android/Lint/供应链/server+gateway Go 全绿；随后 GitHub CI 7/7 全绿 | 已结案；不以更早候选的局部门替代最终候选证据 |
| EC2-08 云端门 | 同提交 0.5.13/0.8.1 provenance | ［KNOWN｜HIGH］完成；source `fe60c1c`、CI `30215857444` 7/7 全绿，APK `3855b972…4664` 与 E-01 binary `43e7dc16…5197` 独立复核通过 | 已结案；精确身份见 `M0_EC2_REALTIME_QUICK_READY_VALIDATION_2026-07-27.md` |
| EC2-09 真机正负 | P40 正向 exact signature 与负向 `receipt_missing` zero-business | ［KNOWN｜HIGH］完成；正向 run `019fa00a-3e17-7c9d-959b-50aab47c1b91`，负向 run `019fa00d-17f3-71d3-b2d9-af2e9271c96d`，两个 READY 均由独立 release verifier 通过 | 已结案；Quick 正向仍为 `INCONCLUSIVE/LOW`，不得扩写为正式基线 |
| EC2-10 收尾 | 精确清理 P40/E-01、独立复核两个 READY 并回填账本 | ［KNOWN｜HIGH］完成；最终 PhoneGuard `16ac15ca…95d5`、reverse empty、E-01 lock released 且共享主机指纹与进入前一致 | 已结案；完整证据见 `M0_EC2_REALTIME_QUICK_READY_VALIDATION_2026-07-27.md` |

### 7.1 本地候选已形成的执行链

1. ［KNOWN｜HIGH］`scripts/collect_realtime_quick_evidence.py` 已把 clean commit、CI 候选、
   P40 实时现场、E-01 `flock`/指纹、三次 serverinfo、Room 冻结、journal 与正/负合同收进
   同一个受限工作流；导入模块本身不访问手机、服务器或网络。
2. ［KNOWN｜HIGH］`scripts/verify_realtime_quick_collection.py` 会重算 manifest、候选来源、
   设备身份、前后 PhoneGuard、远端稳定指纹、锁 nonce、serverinfo 时序和跨端语义；正向包拒绝
   任意负向代理残留，负向包必须包含完整代理、交付与四阶段 reverse 证据。
3. ［KNOWN｜HIGH］证据根目录使用只读 ACL 探针：owner 必须为当前用户，可写主体只允许当前用户、
   SYSTEM 与 Administrators；采集时写入路径绑定的安全回执，READY publisher 与最终 consumer
   都会重新读取实时 ACL，漂移即拒绝。
4. ［KNOWN｜HIGH］`publish_realtime_quick_ready.py` 先发布独立 verification report，再以
   no-replace 方式最后提交 `<collection>.READY.json`；任何后验失败会移除本轮 `COMPLETE` 并降级，
   未经 READY 的 `.complete` 不能被消费者当作成功。
5. ［KNOWN｜HIGH］2026-07-26 第一轮全仓门禁的 719 项 Python 回归出现 1 次
   `test_optional_certificate_is_published_from_its_single_validated_snapshot` 瞬时失败；
   同一用例随后 20/20、完整模块 21/21 通过，无法复现，故未修改生产构建逻辑。最终冻结输入的
   全仓门禁 719/719 通过（16 项按设计跳过），同时 behavior model 43/43、Android 与 Go 全绿。
6. ［KNOWN｜HIGH］本地实现和上述验证全程零 ADB、零 P40、零 E-01；E-01 仍为
   `aneb-server/0.8.0`，0.8.1 不能写成已部署，任何 READY 也不能写成已生成。

## 8. 硬约束与停止条件

- ［KNOWN｜HIGH］离线门未全绿前不操作 P40、E-01 或阿里云。
- ［KNOWN｜HIGH］E-01 切换必须使用远端 `flock`、冻结六项主机指纹、staged receipt、原子回滚
  和验后复核；0.8.0 必须保留可回滚二进制。
- ［KNOWN｜HIGH］任一 Profile/hash/能力/协议摘要冲突都在首个业务 WebSocket 前拒绝。
- ［KNOWN｜HIGH］任一正向 exact signature、负向 zero-business、READY 四方绑定或结束清理失败，
  本轮标记失败并保留诊断证据，不发布 READY、不继续下一门。
- ［KNOWN｜HIGH］A6 D-110 盲审撤回与 M0-EC2 相互隔离；新 v3 neutral package 未明确交接前，
  不打开旧 material PNG/template-v2。
