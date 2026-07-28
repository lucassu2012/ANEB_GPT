# ANEB 里程碑账本

> 更新日期：2026-07-28（Asia/Shanghai）
>
> 用途：把“3 个子项目 + 1 个横切机制”的目标、实现步骤、验收证据和当前状态固化在仓库中。
> 会话摘要只能帮助定位；代码、CI、真机现场和本账本引用的原始证据才是完成判断依据。

## 1. 最终目标与架构

| 轨道 | 最终应实现的内容 | 当前边界 |
|---|---|---|
| P1 手机端 | ANEB App 以真实网络流量模拟 Token 多模态、AI 实时交互和网络综合业务；动态展示关键指标，冻结全量指标、评分、结论与可导出证据 | ［KNOWN｜HIGH］三类引擎、Compose 动态界面、Room v19、strict-v2 结果与导出主链已实现；三族 Quick 正负 READY 已闭环；S3/M1 已完成三族各 5 次、无线权限 granted 的 strict cohort，S3/M2 的 10-run qualification 尚未执行 |
| P2 测试服务器 | 提供可审计的 echo、token-sim、download、实时交互、综合网络与受控故障原语；任何共享主机变更都可预检、互斥、回滚和验后复核 | ［KNOWN｜HIGH］E-01 运行 `aneb-server/0.8.2`，binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`，PID=`1295423`，InvocationID=`d975f7c374aa4ef3a490210d0a495e53`；受锁部署后六项共享指纹不变，owned/stage/watchdog 残留为 0，flock 已释放 |
| P3 标准/业务模型 | 独立模拟 Kimi、DeepSeek、千问等 AI 业务行为，不调用真实 API；以版本化 Profile、模型摘要、校准状态和确定性轨迹供 P1/P2 执行 | ［KNOWN｜HIGH］本地候选为 behavior model 0.3.2、catalog 1.9.0、16 个 Profile、7 个运行包、3 个 execution evidence contracts 与 D-110 唯一重复性资格政策；没有真实授权数据时不得冒充 calibrated/validated |
| Profile 横切机制 | 每类测试冻结业务类型、全量业务/网络指标、质量目标、动态关键指标、评分算法、结论与网络建议；P1/P2/P3 必须使用同一精确合同 | ［KNOWN｜HIGH］Token Quick 1.2.1、AI 实时 Quick 1.1.1 与 Network Quick 1.2.0 均已完成跨端正负 READY；三族的业务判定器继续独立，不能把 Quick 结案扩大为所有 Profile、正式基线或外场验收完成 |

## 2. 总体阶段

| 阶段 | 必须实现的内容 | 完成证据 | 当前判断 |
|---|---|---|---|
| M0 契约冻结 | 冻结 Profile/KPI/AQS/结果 Schema、客户端执行要求、服务器能力回执、业务模型输入输出与来源合同 | catalog/schema 自动校验；同一 Profile SHA；正负合同测试；clean commit CI | ［KNOWN｜HIGH］三类 Quick 窄切片已完成正负 READY；其他 Standard/Recovery Profile 的通用跨端执行合同仍未收敛，因此 M0 整体不是全量完成 |
| M1 核心闭环 | 三类 App 引擎、单节点服务、动态测试、Room/导出、无线证据与重复性门禁可用 | Android/Go 门禁；P40 单节点真机结果；可复算导出 | ［KNOWN｜HIGH］三族 Quick 单节点跨端闭环已完成；首轮三族各 5 次工程采样证明业务编排可连续完成，但 strict 无线 cohort 被权限拒绝阻断。正式重复性矩阵、无线证据完整性和非开发者连续运行门仍未完成 |
| M2 外场 MVP | 6–8 点位、忙闲时段、双运营商，生成可比较的体验地图/报告；当前按 PO 决策使用单节点参考端 | 冻结采样计划、有效样本矩阵、独立复算报告 | ［KNOWN｜HIGH］未启动；点位、双运营商、忙闲采样与活动资源仍为外部依赖，三级节点不是当前验收前置 |
| M3 真实交互 | AI 实时语音/视频仿真、真实业务画像校准、打断/恢复和业务适配器 | 授权观测、留出集、真机边缘条件、适配器合同 | ［KNOWN｜HIGH］WebSocket 仿真轨已完成，真实画像与 RTP/WebRTC/逐帧适配未完成 |
| M4 产品化 | 专业 UI、安装升级、普通用户测试/结果/分享、隐私安全、正式签名与发布运维 | 非 ADB 用户整链、Release 签名、公开发布包、维护手册 | ［KNOWN｜HIGH］Debug 产品化大部完成；正式签名和公开发布未完成 |

### 2.1 面向最终产品的逐步落地顺序

| 顺序 | P1 手机端 | P2 服务器 | P3 标准/业务模型 | Profile 横切 | 完成门 |
|---|---|---|---|---|---|
| S0 契约单元 | 每个测试族先实现业务前能力门、单次终态、Room/strict-v2 与动态指标数据源 | 注册精确 Profile/原语、运行配置和 request-entry 审计 | 发布版本化 Profile/runtime/model，保留来源与校准状态 | 冻结业务类型、全量指标、目标、主动态指标、评分、结论和网络建议 | 正向 exact signature + 负向固定机器原因和零业务产物；本地/CI 全绿 |
| S1 跨端 Quick | 安装精确 CI APK，在 P40 跑一次正向与一次受控负向 | 受锁部署精确 server binary，冻结同-run 窗口与共享指纹 | 提供同 SHA 的运行包，不把 hypothesis 冒充 calibrated | 独立 DB/audit/bundle/release consumer 重算，最后原子提交 READY | 同一 run/APK/server/Profile；正负 READY；PhoneGuard 与远端验后清理 |
| S2 三族 M0 收敛 | Token、Realtime、Network 三族均达到 S1；抽取共用采集生命周期，避免三套语义漂移 | 三族能力与旧客户端兼容回归 | catalog/schema/manifest 统一治理 | ［KNOWN｜HIGH］D-109/S2-V5a 已完成 WorkflowTrace、Token shadow Adapter、33-file tooling closure、Token 两套全模块、三族 11 模块交叉回归、本地全仓质量门与 clean CI；未改写冻结的 EC1 真机证据 | ［KNOWN｜HIGH］EC1、EC2、EC3 与 S2-V5a 均结案；commit `95aaaf0` 的 CI `30313367261` 七个 job 全绿，S2 于 2026-07-28 关闭 |
| S3 M1 可重复核心 | 非开发者可连续执行三类测试；无线证据、1Hz 动态刷新、历史/报告可复算 | 单节点稳定运行、合成弱网和恢复可重复 | hypothesis 参数与确定性轨迹版本化 | ［KNOWN｜HIGH］strict-v2 cohort/导出合同已实现；首轮 P40 三族各 5 次业务采样完成，Token D-58 子门 PASS，但 15/15 无线权限拒绝；Realtime/Network 保持 policy pending，不提升置信度 | 权限机器回执先行的三族重采；1Hz 无线完整性；关键指标 CV/完整性门；导出后独立复算一致 |
| S4 M2 外场 MVP | 点位、运营商、忙闲、网络制式矩阵采集与地图/报告 | 当前单节点参考端保持稳定；若 PO 恢复多级节点再扩展 | 冻结外场采样计划和对照元数据 | 报告只陈述采样覆盖内结论，不把单点扩写为城市基线 | 6–8 点位×忙闲×运营商有效样本矩阵与可审计热力卡 |
| S5 M3 真实画像/适配 | 独立 Experience Lab/adapter 驱动首批业务 App；核心 ANEB 仍只跑自建仿真 | 独立网关承载弱网，不污染 E-01 | 授权观测→训练/留出→画像参数；签名审核闭环 | Profile 标注 source_portrait、校准状态和适用边界 | 豆包/DeepSeek 首批适配；画像留出误差门；失败不进入正式基线 |
| S6 M4 发布产品 | 正式签名、安装升级、无 ADB 整链、隐私与友好错误、专业 UI/图标/分享 | 发布运维、监控、回滚、容量和安全手册 | 稳定 spec 发布节奏与兼容矩阵 | Release 包绑定版本、合同和报告格式 | 普通用户独立完成测试/查看/导出；公开 APK 与维护/恢复文档齐全 |

［KNOWN｜HIGH］S2 已按 D-109 于 2026-07-28 关闭，当前进入 **S3 M1 可重复核心**。S3 已从纯离线合同进入首轮真机工程采样：三族各 5 次业务完成，但 strict 无线证据失败。不得把 Realtime/Network 诊断 CV 扩写为已批准门限，也不得把 15 次业务终态扩写为合格 cohort 或正式基线完成。

## 3. 已结案切片：M0-EC1 Token Quick

| 门 | 应实现的内容 | 权威证据 | 状态 |
|---|---|---|---|
| EC1-01 合同与范围 | 固定 App 0.5.12 / server 0.8.0 / model 0.3.1 / Quick 1.2.1；精确 20 个 echo、3 个 token task、1 个 download；不调用真实 AI API | `spec/`、`profiles/`、`docs/M0_TOKEN_QUICK_EXECUTION_CONTRACT_VALIDATION_2026-07-18.md` | ［KNOWN｜HIGH］完成 |
| EC1-02 E-01 服务端 | 能力回执、同-run start/end barrier、连续 request-entry、固定 CA TCP/UDP 与 download 原语；受锁部署和验后基线一致 | E-01 live `serverinfo`、部署 receipt、binary SHA、共享主机指纹 | ［KNOWN｜HIGH］完成；该切片验收身份为 0.8.0，当前服务已按后续切片升级至 0.8.2；历史证据不得改写或为追求 rc=0 重部署 |
| EC1-03 Android fail-closed | 回执缺失/冲突必须在业务请求前拒绝；机器原因落 Room；score/grade=`null`；任务/KPI 产物为零；只插入一次终态 | Android 单测、Room/结果验证器、D-89 durability 回归 | ［KNOWN｜HIGH］本地实现完成 |
| EC1-04 正负采集 | 正向冻结 20/3/1 与全量 Room/服务端证据；负向用一次性 loopback 代理仅删除能力回执且不修改 E-01 | collector、negative proxy、raw/client/time/device/bundle verifier | ［KNOWN｜HIGH］本地实现完成 |
| EC1-05 发布事务 | `.complete`、`COMPLETE`、report 都不是终态；只有最后原子提交并四方语义绑定的 UUIDv7 sibling `READY.json` 才可发布 | D-87；release verifier；ACL/reparse/paired-sidecar 故障注入 | ［KNOWN｜HIGH］本地实现完成 |
| EC1-06 CI 来源 | clean commit 构建 Debug APK；attestation 精确签 APK、manifest、安装说明三项；最终清单精确四项；独立 reverify | D-88；GitHub run；provenance bundle；机器 manifest/checksums | ［KNOWN｜HIGH］完成；正向 source `10927c1` / run `30124854408`，负向 source `67eb66d` / run `30162011890`，各自 provenance 独立复核通过 |
| EC1-07 正向 P40 | 安装同提交 CI APK；在实时干净现场执行 Quick；客户端、E-01 和 READY 证据必须同 run、同 APK、同 server binary | 正向 `<collection>.READY.json` 及独立 verifier PASS | ［KNOWN｜HIGH］完成；run `019f95f9-a317-7766-9725-243b9660b9f1`，READY SHA `d67efb7f…790b`，20/3/1，99.2/A 但 `LOW/INCONCLUSIVE`、coverage 0.15 |
| EC1-08 负向 P40 | 在不修改 E-01 的前提下制造 `receipt_missing`；服务端业务入口为零、客户端零任务/KPI、机器原因稳定；精确清除 reverse/proxy | 负向 `<collection>.READY.json` 及独立 verifier PASS | ［KNOWN｜HIGH］完成；run `019f99c7-5b40-75ba-ad58-b5b522e9abf9`，READY SHA `b801131d…07af`，control 1 / business 0，任务/KPI/评分产物为零 |
| EC1-09 结束清理 | 停止本轮 ANEB/代理/VPN/抓包，清除 reverse/临时设置，回到 Huawei Launcher 并即时复核；服务器无临时残留 | 结束现场只读清单；READY 的 final-clean 证据 | ［KNOWN｜HIGH］完成；最终 PhoneGuard receipt `f1614e31…bcef`，Launcher、冲突 PID/service/accessibility/VPN/tun=0，reverse 已清，E-01 marker 不存在且锁已释放 |
| EC1-10 切片结案 | 回填 commit/run/APK SHA、正负 run/READY、结论与剩余风险；只在全部门通过后标记 M0-EC1 完成 | 本账本、计划对齐文档、验证记录、CI 与 READY | ［KNOWN｜HIGH］完成；完整证据与限制见 `M0_TOKEN_QUICK_READY_VALIDATION_2026-07-25.md`，完成仅适用于 Token Quick 窄切片 |

### 3.1 已结案切片：M0-EC2 AI 实时 Quick

| 门 | 应实现的内容 | 当前状态 |
|---|---|---|
| EC2-01 范围冻结 | 固定 Profile、候选版本、实时协议签名、正负证据、非目标和停止条件 | ［KNOWN｜HIGH］完成；见 `M0_EC2_AI_REALTIME_QUICK_SCOPE_2026-07-25.md` |
| EC2-02 P2 能力 | Profile 白名单注册表、实时原语与 Token 向后兼容 | ［KNOWN｜HIGH］完成；E-01 `aneb-server/0.8.1` 已受保护部署并验收 |
| EC2-03 P2 审计 | `realtime_run`、实时路由、有界会话摘要、连续审计与隐私门 | ［KNOWN｜HIGH］本地完成 |
| EC2-04 P1 能力门 | 首个 WebSocket 前验证本地/节点/Profile/原语合同 | ［KNOWN｜HIGH］本地完成 |
| EC2-05 P1 持久化 | `receipt_missing`、零业务产物、null 评分与单次 Room 终态 | ［KNOWN｜HIGH］本地完成 |
| EC2-06 采集/复核 | 实时 collector、正负 DB/audit/protocol/cross verifier、私有根与 D-87 READY | ［KNOWN｜HIGH］完成；真实正负 collection 均发布 READY，独立 consumer 均 PASS |
| EC2-07 离线门 | Kotlin/Go/Python 正反例、全仓质量门、凭据扫描与 clean commit | ［KNOWN｜HIGH］完成；最终候选 `fe60c1c` 的完整 quality gate 通过，CI 7/7 全绿 |
| EC2-08 云端门 | 同提交 0.5.13/0.8.1 provenance 与全绿 CI | ［KNOWN｜HIGH］完成；source `fe60c1c`、CI `30215857444`、APK `3855b972…4664`、server binary `43e7dc16…5197` 精确绑定 |
| EC2-09～10 跨端结案 | 受保护部署、P40 正负 READY、精确清理与独立复核 | ［KNOWN｜HIGH］完成；正向 run `019fa00a-3e17-7c9d-959b-50aab47c1b91`、负向 run `019fa00d-17f3-71d3-b2d9-af2e9271c96d`；完整证据见 `M0_EC2_REALTIME_QUICK_READY_VALIDATION_2026-07-27.md` |

### 3.2 已结案切片：M0-EC3 网络综合 Quick

| 门 | 应实现的内容 | 当前状态 |
|---|---|---|
| EC3-01 Profile/runtime | 冻结 Network Quick 1.2.0、四原语、运行顺序、manifest 与 catalog 1.8.0 | ［KNOWN｜HIGH］完成；Profile SHA=`15ae5187…82cc`，runtime SHA=`89812670…2603` |
| EC3-02～03 P2 | 精确能力白名单、upload、ANEB2 同-run UDP、同端口运行配置和连续 request-entry 审计 | ［KNOWN｜HIGH］完成；E-01 已受保护部署 `aneb-server/0.8.2`，source `33434dc`，binary `62ff966b…c96e` |
| EC3-04～06 P1 | 首业务包前能力门、授权传输、固定 `receipt_missing`、零业务产物、null score/grade 和单次 Room 终态 | ［KNOWN｜HIGH］离线实现完成；Android/Go/Python 回归和 full quality gate 通过 |
| EC3-07 同-run 证据 | 独立 Room 与 server audit 判定，再把 App 终态、mode、run、Profile 交叉绑定 | ［KNOWN｜HIGH］完成；Network 独立 consumer 重算客户端、服务端与 cross-bound 三报告，并在最终正负 bundle 中验证同 run/mode/Profile、唯一 D82 marker、空 logcat stderr 和采集新鲜度 |
| EC3-08 CI/provenance | clean commit、7-job CI、精确多 subject attestation 和独立复核 | ［KNOWN｜HIGH］完成；最终真机候选 source `ea9de17c2acea763513b144b4fb9942a3d54c5c6`，CI `30266912724` 7/7，artifact `8653462642`，APK `e1af670c…db0e`，signer `0936cdcf…f1f3`，独立 provenance PASS |
| EC3-09 P40 正负 | 正向四原语 exact signature；负向 loopback `receipt_missing` 且客户端/服务端业务产物均为零 | ［KNOWN｜HIGH］完成；负向 run `019fa3a7-b34a-7a0c-b45f-3e6e3d7b0d8c` / READY `7fa7fb24…0878`，权威正向 run `019fa3d7-8ab2-76eb-90bd-182a482b3c7f` / READY `e153ee46…c3837`；独立 consumer 均 PASS。首份正向本地 bundle 因事后 SQLite checkpoint 污染而废弃 |
| EC3-10 收尾结案 | 恢复进入前 App/Room、停止本轮服务/reverse/VPN、回 Launcher；E-01 lock/marker/指纹复核；回填 READY | ［KNOWN｜HIGH］完成；权威 bundle phone postflight 文件 SHA `2efbbd94…7009`、独立 stable state `27782451…c21198`，Launcher、相关 PID/service/accessibility/VPN/tun=0，reverse empty，stayon=7；远端锁释放，共享身份/指纹稳定；完整证据见 `M0_EC3_NETWORK_QUICK_READY_VALIDATION_2026-07-27.md` |

## 4. 已冻结的关键节点

1. ［KNOWN｜HIGH］D-80：`SHARED_TEST_STATUS.md`、claim、lease、待交接、异常锁定和二次 Verifier 已退役；不得再次用它们阻塞或授权 P40。
2. ［KNOWN｜HIGH］P40 准入只看实时现场：在线、Huawei Launcher 前台、无冲突 App/服务、无 VPN/tun/抓包；来源不明的会话不得擅自停止。
3. ［KNOWN｜HIGH］D-85～D-87：现场证据必须来自 clean commit、私有证据根、独立重算和最后 READY 逻辑提交点。
4. ［KNOWN｜HIGH］D-88：CI provenance 的签名 subject 是 APK、manifest、安装说明三项全集，不是仅 APK。
5. ［KNOWN｜HIGH］D-89：合同拒绝结果记录实际测量端点；终态日志失败不得触发第二次 Room 插入。
6. ［KNOWN｜HIGH］E-01 当前 `aneb-server/0.8.2`、binary `62ff966b…c96e`、PID `1295423`、InvocationID `d975f7c374aa4ef3a490210d0a495e53`；M0-EC3 受锁部署前后六项共享主机指纹一致，owned/stage/watchdog 残留为 0，flock 已释放。`0.8.1` 仅保留为 M0-EC2 历史身份。
7. ［KNOWN｜HIGH］2026-07-23 本地整仓门禁通过；随后正向 CI `30124854408` 与负向最终 CI `30162011890` 通过并生成独立验证的云端工件。负向最终定向门禁为 proxy 25/25、collector 89/89、raw 41/41、bundle 118/118、release 27/27。
8. ［KNOWN｜HIGH］M0-EC2 本地候选冻结为 App 0.5.13 / server 0.8.1 / model 0.3.2 /
   catalog 1.6.0 / AI 实时 Quick 1.1.1；Profile 规范化 SHA-256=
   `701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7`，runtime SHA-256=
   `f2472d2faa7a3ab51582e1496a6925d106806fdd9747e097e20e38e921d9dc07`。
9. ［KNOWN｜HIGH］M0-EC2 collector/release 新增路径绑定的私有根安全回执；采集、READY publisher
   与最终 consumer 都执行只读 ACL 检查，owner 或可写主体漂移即 fail closed，不自动修改 ACL。
10. ［KNOWN｜HIGH］M0-EC2 最终 source `fe60c1c` / CI `30215857444` / APK `3855b972…4664`
    的正向与负向 READY 均已由独立 release verifier 通过；完整事实和限制见
    `M0_EC2_REALTIME_QUICK_READY_VALIDATION_2026-07-27.md`。
11. ［KNOWN｜HIGH］D-90～D-95：Network Quick 使用 ANEB2 绑定 run UUID；UDP 在回显前进入同一 request-entry FIFO；Room、服务端审计与 App 终态必须同 run/mode/Profile 交叉绑定；三类 Quick 只共享机械生命周期顺序，audit scope、busy-sentinel schema、启动码、远端 marker 与证据 schema 不得隐式继承。最终正负 READY 已实证该边界。
12. ［KNOWN｜HIGH］M0-EC3 最终 CI 候选为 source `ea9de17c2acea763513b144b4fb9942a3d54c5c6` / run `30266912724` / artifact `8653462642` / APK `e1af670c…db0e` / signer `0936cdcf…f1f3`；同候选正负 READY、独立 verifier 与最终清理均通过。
13. ［KNOWN｜HIGH］D-98～D-101：Realtime/Network 的 READY 事务、collection verifier 原语、高层 Adapter 与证据根安全已迁入业务族中立模块；两族业务 plan/status/run 与 cross-evidence 判定仍隔离。Token 尚未接入 Adapter，S2 不得标记完成。
14. ［KNOWN｜HIGH］D-102 / S2-V4a：共享 READY 合同已能精确表达 Token 的 `execution_mode` 方言，同时保留 Realtime/Network 默认 `mode` 兼容面；这只是 Token Adapter 的安全切口，不是 Token publisher/consumer 迁移完成。
15. ［KNOWN｜HIGH］D-103 / S2-V4b：Token release consumer 的 READY marker key/identity/binding/timestamp 机械校验已接入 family-neutral 合同；四模块回归与 refactor 后复跑均为 46/46（4 项平台跳过）。Token manifest/report/COMPLETE、publisher、外部工具闭包与业务重算仍保持本族实现，S2 尚未完成。
16. ［KNOWN｜HIGH］D-104 / S2-V4c：三族 COMPLETE marker 已由同一确定性编码器表达，Token 通过显式 manifest leaf 保留历史格式和失败码；adapter + Token release + Realtime/Network collection 回归 56/56（4 项平台跳过）。摘要、manifest/report、publisher 与业务重算仍未迁移。
17. ［KNOWN｜HIGH］D-105 / S2-V4d：中立事务已支持既有 canonical report 原子发布 READY；失败只回滚本轮 READY，不改写既有 report，且原 Realtime/Network publisher 继续复用同一内部事务。Token PowerShell 尚未接线，不能标记 publisher 迁移完成。
18. ［KNOWN｜HIGH］D-106 / S2-V4e：Token Python publisher Adapter 已用完整 ReleaseFixture 证明“既有 report → READY → 真实 Token release consumer”闭环；共享默认族仍要求 sorted-key canonical report，Token 冻结的历史字段顺序由不可变合同单独声明。共享 core + Token release 38/38（4 项平台跳过），但 PowerShell/CLI/工具闭包尚未迁移，S2 仍未完成。
19. ［KNOWN｜HIGH］D-107 / S2-V4f：Token publisher CLI 已冻结 `bundle report` 两参数与 canonical stdout/stderr/exit-code 合同；Realtime/Network/Token direct CLI + Token Adapter E2E 3/3。PowerShell collector 与 tooling provenance 尚未接线，S2 仍未完成。
20. ［KNOWN｜HIGH］D-108 / S2-V4g：Token PowerShell 已删除重复 READY digest/JSON/Move 事务，改为有界调用已冻结 CLI；CLI 失败保留既有 report，CLI 成功后若输出或后置 provenance 失败则回滚本轮 READY/partial。collector、独立 bundle verifier 与 fixture 精确共享 31-file tooling closure。PowerShell AST 无错误，collector 92/92（1 skip）、bundle verifier 119/119（2 skip）、三族共享/collection/release 91/91（4 skip）通过；全仓质量门 exit0：827 主 Python（16 skip）、44 附加 Python、Android、Go、release/spec/secret 全 PASS；commit `c3f1d11` 的 clean CI `30303472975` 七个 job 全部通过。D-108 已完成，但 Token 尚未接入共享 live lifecycle，S2 仍不能结案。
21. ［KNOWN｜HIGH］D-109 / S2-V5a：下一切片使用 family-neutral WorkflowTrace 统一阶段顺序、多失败保留与发布资格。Realtime/Network callback executor 与 Token PowerShell executor 是两个真实 Adapter；Token 先采用 shadow gate，不重排现有 try/finally、cleanup 重试、业务语义或 READY 字节。新旧发布资格不一致必须 fail closed；完成门见 `S2_TOKEN_LIFECYCLE_ADAPTER_CONTRACT_2026-07-28.md`。
22. ［KNOWN｜HIGH］D-109 / S2-V5a 已结案：新增 canonical WorkflowTrace evaluator/CLI，Python callback executor 与 Token PowerShell 薄壳均使用同一决策；Token bundle 独立 verifier 从 trace 重算 decision 并要求原字节一致，tooling closure 从 31 扩为 33。collector 95/95（1 skip）、bundle verifier 123/123（2 skip）、三族 11 模块 150/150（4 skip）通过；完整质量门 exit0：846 主 Python（16 skip）、44 附加 Python、Android、Go、release/spec/secret 全 PASS，所有 fresh pre/post 残留为 0。commit `95aaaf0` 的 clean CI `30313367261` 七个 job 全部通过；结案审计确认 HEAD/远端一致、工作树干净、base diff-check 通过、D-108 的 31-file 与旧计数只作为历史证据保留。S2 于 2026-07-28 关闭。
23. ［KNOWN｜HIGH］S3/M1 首个纯离线切片新增 `aneb-repeatability-cohort-v1`：只接受完整 strict-v2、completed+valid、observed device/network、Wi-Fi/蜂窝且 VPN=false，并冻结 producer/Profile/claim/device/endpoint/network/algorithm 同质身份。Token `TOK-B04` 唯一委托 D-58；Realtime 三指标与 Network 三指标仅输出 `policy_pending/diagnostic_only`，正式基线资格固定 false、单 run 置信度不变。TDD 7/7、CLI 5/5、与既有 D-58/result-v2 四模块交叉回归 24/24 PASS；完整质量门 exit0：858 主 Python（16 skip）、44 附加 Python、Android、Go、release/spec/secret 全 PASS，post-scan 残留为 0；生产提交 `3a35236` 的 clean CI `30320671090` 七个 job 全绿。尚无本切片新 P40 样本，详见 `S3_REPEATABILITY_COHORT_CONTRACT_2026-07-28.md`。
24. ［KNOWN｜HIGH］S3/M1 第二个纯离线切片为 cohort 增加每 run 无线序列结构审计：要求 collected、内联样本数一致、elapsedRealtime 严格递增，并输出跨度、min/median/P95/max gap、观测频率、stale 与切卡计数；正式 cadence 判据仍为 null/diagnostic-only。新增 `aneb-repeatability-export-v1` 从 Room v19 原字节导出 strict-v2 JSONL，同时逐条绑定 `radio_sample`，不重建结果或评分。聚焦 29/29、完整质量门主 Python 863/863（16 skip）+44 附加及 Android/Go/release/spec/secret 全 PASS；尚未产生新 P40 三族 cohort，不能写成无线正式门或真机复算已完成。
25. ［KNOWN｜HIGH］S3/M1 首轮真机工程采样使用 source `5b968bfffdb81451c80b2fd86064c06b35da925b`、CI `30323979935`、APK SHA-256=`09db3b4a3f137cd98c2346c55f5dadf3f9e797367327e0c5e7b986de525ce8b4`，完成 Token/Realtime/Network 各 5 次、共 15 次业务运行。Token D-58 子门以 TTFT CV 中位数 2.61% PASS；Realtime/Network 只生成诊断分布。15/15 `radio=permission_denied/0 samples` 使严格导出拒绝，正式 cohort 未形成。临时 SQLite 诊断另触发原 DB/WAL/SHM checkpoint 转换，已降级为 engineering-only；runner 新增权限 create-once 回执，exporter 新增失败路径源三件套逐字节保护。聚焦交叉回归 39/39 PASS；冻结改动后的完整质量门 exit0：主 Python 873/873（skip 16）、附加 44/44、Android/Go/release/spec/secret 全 PASS。生产修复提交 `4184065` 的 clean CI `30348907807` 七个 job 全绿，详见 `S3_M1_REPEATABILITY_ENGINEERING_VALIDATION_2026-07-28.md`。
26. ［KNOWN｜HIGH］S3/M1 无线重采使用 source `8a834d49dd61a1f544dc5ea10991e50929f85a3e`、CI `30349786024`、APK SHA-256=`6b45ddd94ea0621fba09dfbb2eb31596a9c6ed4bb1212c7011018ec75a307ba3`；三项 runtime permission 机器回执全 granted，Token/Realtime/Network 15/15 completed。严格导出从冻结 Room 副本形成三组各 5 条 strict-v2 envelope，原 DB/WAL/SHM 在分析前后逐字一致；三族约 1 Hz 无线序列共 876 个样本，stale/订阅切换均为 0。Token D-58 以任务 TTFT CV 中位数 1.71% PASS；Realtime/Network 继续 `policy_pending/diagnostic_only`，无线 cadence 与正式基线资格仍未批准。runner finally 与独立 PhoneGuard、E-01 六指纹及 flock release 均闭合；冻结工作树完整质量门 exit0，主 Python 877/877（skip 16）、附加 44/44 及 Android/Go/release/spec/secret 全 PASS。详见 `S3_M1_REPEATABILITY_RADIO_VALIDATION_2026-07-28.md`。

## 5. 本轮完成顺序与下一阶段

1. ［KNOWN｜HIGH］GitHub CLI 已用浏览器设备流认证；已泄露的历史 PAT 未写入仓库或脚本。
2. ［KNOWN｜HIGH］正向 source `10927c1` / CI `30124854408` 与负向 source `67eb66d` / CI `30162011890` 的 provenance 候选已独立验证。
3. ［KNOWN｜HIGH］P40 正向与负向短会话、独立 READY 复核、PhoneGuard 清理和 E-01 锁/marker 复核均已完成。
4. ［KNOWN｜HIGH］M0-EC1 Token Quick 窄切片现已结案；不重跑已闭环证据，也不为追求新的部署返回码重复修改 E-01。
5. ［KNOWN｜HIGH］M0-EC2 AI 实时 Quick 的 EC2-01～10 已结案；正向 `100/A` 仍受
   `INCONCLUSIVE/LOW`、coverage 0.1 限制，不得冒充正式体验基线。
6. ［KNOWN｜HIGH］M0-EC3 Network Quick 已结案；权威正向 `79/B` 仍为 `INCONCLUSIVE/LOW`、coverage 0.5，不得冒充正式网络质量基线。
7. ［KNOWN｜HIGH］S2 已关闭；S3/M1 的“无线权限机器回执 → 三族重采 → 冻结副本严格导出 → 独立复算”已完成。Token D-58 子门 PASS；Realtime/Network 正式阈值与无线 cadence/gap/stale 质量门仍需 Product Owner 单独批准，因此 M1 仍为部分完成且所有正式基线资格为 false。
8. ［INFERRED｜HIGH］Realtime/Network 的正式重复性判据不能直接照搬 Token D-58：比例指标接近 0/1 时 CV 解释不稳定，P05/P95 指标还受样本量影响；应先用真实重复样本审计分布，再由 Product Owner 批准族专属门限。
9. ［KNOWN｜HIGH］A6 审核交接当前关闭；不得打开、复制或上传 reviewer HTML、secret、seed、
   private ZIP、sealed binding 或旧 material/template，也不得运行 materialization/qualification。

## 6. 更新规则

- ［KNOWN｜HIGH］每完成一个门，必须同时记录实际产物身份和验证输出；“代码已写”“测试未报错”或“界面看起来正常”不能单独证明完成。
- ［KNOWN｜HIGH］如果真实状态推翻本账本，先更正事实和证据边界，再更新状态；不得为保持文档一致而隐去失败。
- ［KNOWN｜HIGH］新阶段开始前先复核上一阶段硬门；未通过的外部依赖必须保留 `BLOCKED_EXTERNAL`，不能折算为 PASS。
- ［KNOWN｜HIGH］权重、门限、Profile 或结果语义变化必须进入 `docs/DECISION_LOG.md`；测试修复但不改变合同的机械变化不单独升级 Profile。
