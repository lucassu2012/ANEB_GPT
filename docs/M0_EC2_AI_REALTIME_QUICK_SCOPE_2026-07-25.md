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
| Profile | `ai_realtime_voice_quick@1.1.0` | ［KNOWN｜HIGH］当前没有 `execution_requirements` |
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

| 门 | 当前差距 | 落地与验证 |
|---|---|---|
| EC2-01 Profile/P3 | 实时 Quick 无执行要求，generator/catalog 只强制 Token | TDD 更新 generator、schema 语义测试、Profile 1.1.1、manifest、catalog 1.6.0 与协议合同 |
| EC2-02 P2 能力 | server loader 硬编码唯一 Token 身份和三原语 | 抽取 fail-closed Profile 注册表；增加 realtime 原语；保留 Token 回归和未知 Profile 拒绝 |
| EC2-03 P2 审计 | `/realtime-sim` 被降为 `/api/v1/other` | 增加精确路由、受限 `realtime_run` scope、会话摘要与并发/污染/日志隐私测试 |
| EC2-04 P1 能力门 | Realtime 直接开 WebSocket | 抽取通用 Gate 核心；Quick 在首个 WS 前授权；Standard/Recovery 未迁移时保持既有兼容 |
| EC2-05 P1 持久化 | 没有实时合同拒绝专用生命周期 | TDD 固定机器 reason、零会话/帧、null score/grade、单次 Room 终态与日志顺序 |
| EC2-06 采集/复核 | collector、DB/audit/bundle/release verifier 均为 Token 专用 | 参数化共享安全骨架，增加实时协议/DB/摘要 verifier；READY 仍为唯一发布点 |
| EC2-07 离线门 | 无第二 Profile 跨语言漂移测试 | Kotlin/Go/Python 正反例、全仓质量门、凭据扫描、clean commit |
| EC2-08 云端门 | 无同提交 0.5.13/0.8.1 provenance | GitHub CI 全绿后独立复核 APK、server candidate、manifest/checksums/attestation |
| EC2-09 真机正负 | 当前证据不是 M0-EC2 合同证据 | 受保护部署 0.8.1 后，P40 正向与 `receipt_missing` 负向各生成独立 READY |
| EC2-10 收尾 | 尚无最终里程碑记录 | 精确清理 P40/E-01，独立复核两个 READY，回填账本与计划对齐 |

## 8. 硬约束与停止条件

- ［KNOWN｜HIGH］离线门未全绿前不操作 P40、E-01 或阿里云。
- ［KNOWN｜HIGH］E-01 切换必须使用远端 `flock`、冻结六项主机指纹、staged receipt、原子回滚
  和验后复核；0.8.0 必须保留可回滚二进制。
- ［KNOWN｜HIGH］任一 Profile/hash/能力/协议摘要冲突都在首个业务 WebSocket 前拒绝。
- ［KNOWN｜HIGH］任一正向 exact signature、负向 zero-business、READY 四方绑定或结束清理失败，
  本轮标记失败并保留诊断证据，不发布 READY、不继续下一门。
- ［KNOWN｜HIGH］A6 D-110 盲审撤回与 M0-EC2 相互隔离；新 v3 neutral package 未明确交接前，
  不打开旧 material PNG/template-v2。
