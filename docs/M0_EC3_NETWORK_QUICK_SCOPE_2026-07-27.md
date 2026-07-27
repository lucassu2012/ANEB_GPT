# M0-EC3 网络综合 Quick 跨端执行合同范围

> 冻结日期：2026-07-27（Asia/Shanghai）
> 目标 Profile：`network_comprehensive_quick`
> 前置基线：M0-EC1 Token Quick、M0-EC2 AI 实时 Quick 正负 READY 均已闭环；本阶段离线实现和门禁通过前不操作 P40 或 E-01。

## 1. 先给反方观点

- ［KNOWN｜HIGH］现有 Network Quick 会直接执行连通性、echo、下载、上传和 UDP 探测，没有在首个业务请求前核验节点能力；因此当前真机成功结果不能冒充 M0-EC3 执行合同证据。
- ［KNOWN｜HIGH］仅复用 Token Quick 的 `echo/download` 不足以证明节点具备上传接收和 UDP 序列回显能力；`upload` 与 `udp_echo` 必须是独立的冻结原语。
- ［KNOWN｜HIGH］Network Quick 是到 ANEB 探针节点的应用层综合测量，不会人为调节 RSRP/SINR，也不等价于运营商无线覆盖基线、第三方 AI 厂商体验或实验室弱网标定。
- ［INFERRED｜HIGH］最小可发布切片是：冻结 Profile/runtime、增加 P1 业务前能力门、增加 P2 精确能力注册和同-run 审计、复用 EC1/EC2 的 provenance/READY/清理框架；本阶段不扩展 Standard Profile。

## 2. 候选版本与业务边界

| 单元 | M0-EC3 候选 | 变更原因 |
|---|---:|---|
| P1 Android | `0.5.14-codex` / code 46 | 增加 Network Quick 业务前能力门、授权传输边界和拒绝持久化 |
| P2 server | `aneb-server/0.8.2` | 增加 Network Quick 白名单、`upload/udp_echo` 原语与运行配置校验 |
| Profile catalog | `1.8.0` | 增加第三个 execution evidence contract |
| Network Quick | `1.2.0` | 冻结 runtime plan、manifest 与 `execution_requirements` |
| P3 behavior model | 不适用 | 网络综合测试使用确定性探测计划，不声称模拟某个 AI 厂商行为 |

［FRAME｜HIGH］冻结执行计划保持现有 Quick 业务负载：3 次路径建立、10 个空闲 RTT、6 秒/32 MiB/4 并发下载、6 秒/8 MiB/2 并发上传、50 个 256-byte UDP 包（50/s）、5 个负载后 RTT。它是最大工作量和次序合同，不是网络质量目标本身。

## 3. 冻结执行要求

`network_comprehensive_quick@1.2.0` 必须声明：

```json
{
  "contract_id": "aneb-execution-requirements",
  "contract_version": "1.0.0",
  "client_engine": {
    "contract_id": "aneb-network-comprehensive-engine",
    "min_version": "1.0.0",
    "max_version_exclusive": "2.0.0"
  },
  "server_capability_receipt": {
    "contract_id": "aneb-server-capability-receipt",
    "min_version": "1.0.0",
    "max_version_exclusive": "2.0.0"
  },
  "required_primitives": [
    {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"},
    {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
    {"primitive_id": "udp_echo", "wire_contract_id": "aneb-udp-echo-v2"},
    {"primitive_id": "upload", "wire_contract_id": "aneb-upload-v1"}
  ]
}
```

- ［KNOWN｜HIGH］P1 必须先校验本地 Profile SHA、客户端引擎版本、服务器能力回执和服务器 `validated_profiles` 精确身份，再创建任何 Network 业务传输。
- ［KNOWN｜HIGH］P2 只接受源码注册表中冻结的 Profile 身份、模式、执行目标、claim scope、客户端引擎和完整原语集合；未知声明一律 fail closed。
- ［FRAME｜HIGH］`upload` 指 `POST /api/v1/upload` 的有限请求体接收合同；`udp_echo` 指携带当前 run UUID 的 UDP 序列包原样回显合同。二者不代表公网任意上传或通用 UDP 转发。
- ［KNOWN｜HIGH］服务器启动时若发布 Network Quick，必须证明上传 handler 已注册且 UDP echo 运行配置可用；不能只因代码中存在实现就发布能力。

## 4. 正向业务与同-run证据

［FRAME｜HIGH］正式正向 Quick 必须同时满足：

1. 能力门只产生 `/api/v1/serverinfo` control 请求，随后才允许进入 Network 业务阶段。
2. 客户端结果固定绑定同一 run UUID、Profile/runtime SHA、APK/服务器 provenance 与连续服务端审计窗口。
3. 服务端 HTTP 审计至少能分别归因 echo、download、upload；UDP 证据必须绑定同一 run 的冻结探测计划、发送/返回序列和端点，不能只记一个总丢包率。
4. 结果必须保留原始窗口样本和派生指标：下载/上传窗口速率、idle/loaded/post RTT、RTT 变化、低速窗口率、请求失败率、UDP 未返回/乱序率、连接阶段时延。
5. 任一 required metric 样本不足、协议摘要冲突、未归因业务流量、运行配置漂移或清理失败，score/grade 必须为 null 或本轮不得发布 READY。

## 5. 负向合同

- ［KNOWN｜HIGH］负向测试复用一次性 loopback 代理，只从真实上游 `/serverinfo` 删除 `execution_capabilities`；不修改 E-01，不发送 echo/download/upload/UDP 业务流量。
- ［FRAME｜HIGH］期望机器原因固定为 `receipt_missing`；客户端持久化一个 INVALID 终态，score/grade 为 null，所有任务、窗口、字节、RTT 与 UDP 业务产物为零。
- ［KNOWN｜HIGH］服务端审计窗口允许能力 control 请求，但 echo/download/upload 业务请求必须为零；UDP 发送计数必须为零。
- ［KNOWN｜HIGH］结束时必须精确清除本轮 reverse/代理/App/服务和临时设置，返回 Huawei Launcher；E-01 marker/lock/临时文件为零且共享主机指纹不变。

## 6. 差距矩阵与落地顺序

| 门 | 应实现内容 | 当前状态 | 验收 |
|---|---|---|---|
| EC3-01 Profile | Profile 1.2.0、runtime plan、manifest、catalog 1.8.0 | 离线实现完成，聚焦门禁通过 | 规范化摘要与 manifest 漂移测试 |
| EC3-02 P2 能力 | Network 白名单及四原语注册 | 离线实现完成，Go 全量通过 | 三个 migrated Quick 精确回执；未知/缺项拒绝 |
| EC3-03 P2 运行配置 | upload handler 与 UDP echo 运行配置可证明 | 离线实现完成，部署安全门通过 | 启动配置正反例与 handler/UDP 测试 |
| EC3-04 P1 能力门 | 首个 Network 业务包前完成能力核验 | 离线实现完成，聚焦门禁通过 | receipt 缺失/冲突时业务 transport 计数为零 |
| EC3-05 P1 授权边界 | 只有授权对象能发 echo/download/upload/UDP | 离线实现完成，Legacy/Quick 兼容回归通过 | 绕过/重用/异常路径 fail closed |
| EC3-06 持久化 | 固定 reason、零业务产物、null score/grade、单次终态 | 离线实现完成，聚焦门禁通过 | Room 正反例与 DB 冻结重算 |
| EC3-07 同-run 审计 | HTTP 分类、UDP 序列、原始窗口与 Profile/runtime 交叉绑定 | 离线实现完成：Network 已接入共用设备机械生命周期后端与独立 CLI，冻结独立 audit scope、busy-sentinel schema、启动操作码、Room 合同包名、远端 marker 与 Profile 三文件；独立 consumer 只接受唯一 D82 marker 后日志及空 logcat stderr，并重算客户端、服务端、交叉绑定三份报告。Room 包名去耦后分段完整门 796+44 及 Android/Go/发布门全部 PASS；直接 CLI 子进程回归与 Network/Realtime 14 模块交叉回归 159/159 PASS | 污染、缺号、跨类别身份、旧日志、stderr、入口不可执行或摘要不一致均拒绝 |
| EC3-08 离线门 | Kotlin/Go/Python、全仓门禁、secret scan、clean commit | ［KNOWN｜HIGH］最近独立验证候选为 source `1dd6bc9ed764c43870f64a2cb9945ff465bd81c3`、GitHub run `30239585679`（7/7）、artifact `8642938682`、APK `55df3be8…05ab`、signer `b217bfb2…f316`；Room 包名去耦提交仍需自己的 CI provenance | 本地门禁与 GitHub CI 全绿 |
| EC3-09 真机正负 | P40 正向 exact signature、负向 `receipt_missing` zero-business | 进行中；CI provenance 已复核，首次换装前因 ADB 断开 fail closed，手机未发生卸载/安装/启动 | 两个独立 READY consumer 重算通过 |
| EC3-10 收尾 | P40/E-01 精确清理与计划账本回填 | E-01 0.8.2 受保护部署与锁内验后完成；P40 收尾待 EC3-09 | Launcher/进程/服务/VPN/tun、远端锁及六指纹复核 |

## 7. 停止条件

- ［KNOWN｜HIGH］离线门未全绿前不得操作 P40、E-01 或阿里云。
- ［KNOWN｜HIGH］任一 Profile/runtime/manifest、能力回执、原语或运行配置冲突，必须在首个 Network 业务包前停止。
- ［KNOWN｜HIGH］正向 exact signature、负向 zero-business、READY 绑定或最终清理任一失败，本轮保留诊断证据但不发布 READY、不进入下一里程碑。
- ［KNOWN｜HIGH］Quick 正向即使获得高分，也只能形成当前路径的一次工程样本；未满足正式样本量与环境覆盖前不得扩写为体验基线。
