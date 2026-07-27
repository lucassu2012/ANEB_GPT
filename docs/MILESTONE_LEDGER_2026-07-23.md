# ANEB 里程碑账本

> 更新日期：2026-07-27（Asia/Shanghai）
>
> 用途：把“3 个子项目 + 1 个横切机制”的目标、实现步骤、验收证据和当前状态固化在仓库中。
> 会话摘要只能帮助定位；代码、CI、真机现场和本账本引用的原始证据才是完成判断依据。

## 1. 最终目标与架构

| 轨道 | 最终应实现的内容 | 当前边界 |
|---|---|---|
| P1 手机端 | ANEB App 以真实网络流量模拟 Token 多模态、AI 实时交互和网络综合业务；动态展示关键指标，冻结全量指标、评分、结论与可导出证据 | ［KNOWN｜HIGH］三类引擎、Compose 动态界面、Room v19、strict-v2 结果与导出主链已实现；M0-EC1 Token Quick、M0-EC2 AI 实时 Quick 与 M0-EC3 Network Quick 均已完成正负 READY；三族 Quick 的跨端执行合同已闭环，但正式重复性矩阵尚未完成 |
| P2 测试服务器 | 提供可审计的 echo、token-sim、download、实时交互、综合网络与受控故障原语；任何共享主机变更都可预检、互斥、回滚和验后复核 | ［KNOWN｜HIGH］E-01 运行 `aneb-server/0.8.2`，binary SHA-256=`62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`，PID=`1295423`，InvocationID=`d975f7c374aa4ef3a490210d0a495e53`；受锁部署后六项共享指纹不变，owned/stage/watchdog 残留为 0，flock 已释放 |
| P3 标准/业务模型 | 独立模拟 Kimi、DeepSeek、千问等 AI 业务行为，不调用真实 API；以版本化 Profile、模型摘要、校准状态和确定性轨迹供 P1/P2 执行 | ［KNOWN｜HIGH］本地候选为 behavior model 0.3.2、catalog 1.8.0、16 个 Profile、7 个运行包与 3 个 execution evidence contracts；没有真实授权数据时不得冒充 calibrated/validated |
| Profile 横切机制 | 每类测试冻结业务类型、全量业务/网络指标、质量目标、动态关键指标、评分算法、结论与网络建议；P1/P2/P3 必须使用同一精确合同 | ［KNOWN｜HIGH］Token Quick 1.2.1、AI 实时 Quick 1.1.1 与 Network Quick 1.2.0 均已完成跨端正负 READY；三族的业务判定器继续独立，不能把 Quick 结案扩大为所有 Profile、正式基线或外场验收完成 |

## 2. 总体阶段

| 阶段 | 必须实现的内容 | 完成证据 | 当前判断 |
|---|---|---|---|
| M0 契约冻结 | 冻结 Profile/KPI/AQS/结果 Schema、客户端执行要求、服务器能力回执、业务模型输入输出与来源合同 | catalog/schema 自动校验；同一 Profile SHA；正负合同测试；clean commit CI | ［KNOWN｜HIGH］三类 Quick 窄切片已完成正负 READY；其他 Standard/Recovery Profile 的通用跨端执行合同仍未收敛，因此 M0 整体不是全量完成 |
| M1 核心闭环 | 三类 App 引擎、单节点服务、动态测试、Room/导出、无线证据与重复性门禁可用 | Android/Go 门禁；P40 单节点真机结果；可复算导出 | ［KNOWN｜HIGH］三族 Quick 单节点跨端闭环已完成，功能闭环大部完成；正式重复性矩阵、无线证据完整性和非开发者连续运行门仍未完成 |
| M2 外场 MVP | 6–8 点位、忙闲时段、双运营商，生成可比较的体验地图/报告；当前按 PO 决策使用单节点参考端 | 冻结采样计划、有效样本矩阵、独立复算报告 | ［KNOWN｜HIGH］未启动；点位、双运营商、忙闲采样与活动资源仍为外部依赖，三级节点不是当前验收前置 |
| M3 真实交互 | AI 实时语音/视频仿真、真实业务画像校准、打断/恢复和业务适配器 | 授权观测、留出集、真机边缘条件、适配器合同 | ［KNOWN｜HIGH］WebSocket 仿真轨已完成，真实画像与 RTP/WebRTC/逐帧适配未完成 |
| M4 产品化 | 专业 UI、安装升级、普通用户测试/结果/分享、隐私安全、正式签名与发布运维 | 非 ADB 用户整链、Release 签名、公开发布包、维护手册 | ［KNOWN｜HIGH］Debug 产品化大部完成；正式签名和公开发布未完成 |

### 2.1 面向最终产品的逐步落地顺序

| 顺序 | P1 手机端 | P2 服务器 | P3 标准/业务模型 | Profile 横切 | 完成门 |
|---|---|---|---|---|---|
| S0 契约单元 | 每个测试族先实现业务前能力门、单次终态、Room/strict-v2 与动态指标数据源 | 注册精确 Profile/原语、运行配置和 request-entry 审计 | 发布版本化 Profile/runtime/model，保留来源与校准状态 | 冻结业务类型、全量指标、目标、主动态指标、评分、结论和网络建议 | 正向 exact signature + 负向固定机器原因和零业务产物；本地/CI 全绿 |
| S1 跨端 Quick | 安装精确 CI APK，在 P40 跑一次正向与一次受控负向 | 受锁部署精确 server binary，冻结同-run 窗口与共享指纹 | 提供同 SHA 的运行包，不把 hypothesis 冒充 calibrated | 独立 DB/audit/bundle/release consumer 重算，最后原子提交 READY | 同一 run/APK/server/Profile；正负 READY；PhoneGuard 与远端验后清理 |
| S2 三族 M0 收敛 | Token、Realtime、Network 三族均达到 S1；抽取共用采集生命周期，避免三套语义漂移 | 三族能力与旧客户端兼容回归 | catalog/schema/manifest 统一治理 | 三族证据合同共用发布事务和 provenance 边界 | EC1、EC2、EC3 全部结案；通用 collector/READY contract 通过故障注入 |
| S3 M1 可重复核心 | 非开发者可连续执行三类测试；无线证据、1Hz 动态刷新、历史/报告可复算 | 单节点稳定运行、合成弱网和恢复可重复 | hypothesis 参数与确定性轨迹版本化 | 同条件重复性、样本有效性和置信度门 | P40 重复样本；关键指标 CV/完整性门；导出后独立复算一致 |
| S4 M2 外场 MVP | 点位、运营商、忙闲、网络制式矩阵采集与地图/报告 | 当前单节点参考端保持稳定；若 PO 恢复多级节点再扩展 | 冻结外场采样计划和对照元数据 | 报告只陈述采样覆盖内结论，不把单点扩写为城市基线 | 6–8 点位×忙闲×运营商有效样本矩阵与可审计热力卡 |
| S5 M3 真实画像/适配 | 独立 Experience Lab/adapter 驱动首批业务 App；核心 ANEB 仍只跑自建仿真 | 独立网关承载弱网，不污染 E-01 | 授权观测→训练/留出→画像参数；签名审核闭环 | Profile 标注 source_portrait、校准状态和适用边界 | 豆包/DeepSeek 首批适配；画像留出误差门；失败不进入正式基线 |
| S6 M4 发布产品 | 正式签名、安装升级、无 ADB 整链、隐私与友好错误、专业 UI/图标/分享 | 发布运维、监控、回滚、容量和安全手册 | 稳定 spec 发布节奏与兼容矩阵 | Release 包绑定版本、合同和报告格式 | 普通用户独立完成测试/查看/导出；公开 APK 与维护/恢复文档齐全 |

［KNOWN｜HIGH］S1 的第三族 M0-EC3 已完成，当前进入 **S2 三族 M0 收敛**。S2 只抽取已由 EC1/EC2/EC3 实证的机械生命周期、发布事务与 provenance 边界；不得合并或弱化三族各自的业务语义判定器。

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
| EC3-09 P40 正负 | 正向四原语 exact signature；负向 loopback `receipt_missing` 且客户端/服务端业务产物均为零 | ［KNOWN｜HIGH］完成；负向 run `019fa3a7-b34a-7a0c-b45f-3e6e3d7b0d8c` / READY `7fa7fb24…0878`，正向 run `019fa3aa-c0d5-7586-b736-ae2fe0a35c78` / READY `14ea8c7f…a83b`；独立 consumer 均 PASS |
| EC3-10 收尾结案 | 恢复进入前 App/Room、停止本轮服务/reverse/VPN、回 Launcher；E-01 lock/marker/指纹复核；回填 READY | ［KNOWN｜HIGH］完成；PhoneGuard receipt `a4cecc20…1789`，Launcher、相关 PID/service/accessibility/VPN/tun=0，reverse empty，stayon=7；远端两次锁释放，共享身份/指纹稳定；完整证据见 `M0_EC3_NETWORK_QUICK_READY_VALIDATION_2026-07-27.md` |

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

## 5. 本轮完成顺序与下一阶段

1. ［KNOWN｜HIGH］GitHub CLI 已用浏览器设备流认证；已泄露的历史 PAT 未写入仓库或脚本。
2. ［KNOWN｜HIGH］正向 source `10927c1` / CI `30124854408` 与负向 source `67eb66d` / CI `30162011890` 的 provenance 候选已独立验证。
3. ［KNOWN｜HIGH］P40 正向与负向短会话、独立 READY 复核、PhoneGuard 清理和 E-01 锁/marker 复核均已完成。
4. ［KNOWN｜HIGH］M0-EC1 Token Quick 窄切片现已结案；不重跑已闭环证据，也不为追求新的部署返回码重复修改 E-01。
5. ［KNOWN｜HIGH］M0-EC2 AI 实时 Quick 的 EC2-01～10 已结案；正向 `100/A` 仍受
   `INCONCLUSIVE/LOW`、coverage 0.1 限制，不得冒充正式体验基线。
6. ［KNOWN｜HIGH］M0-EC3 Network Quick 已结案；正向 `77/B` 仍为 `INCONCLUSIVE/LOW`、coverage 0.5，不得冒充正式网络质量基线。
7. ［KNOWN｜HIGH］当前最小执行切片进入 S2：把三族已经证明的 phone/remote/provenance/READY 机械生命周期抽成共用组件，但保留 Token/Realtime/Network 各自的业务语义判定器。
8. ［INFERRED｜HIGH］S2 应先做等价性/故障注入和三族回归，再替换现有入口；不重跑已冻结的 EC1/EC2/EC3 真机证据来证明纯机械重构。
9. ［KNOWN｜HIGH］A6 审核交接当前关闭；不得打开、复制或上传 reviewer HTML、secret、seed、
   private ZIP、sealed binding 或旧 material/template，也不得运行 materialization/qualification。

## 6. 更新规则

- ［KNOWN｜HIGH］每完成一个门，必须同时记录实际产物身份和验证输出；“代码已写”“测试未报错”或“界面看起来正常”不能单独证明完成。
- ［KNOWN｜HIGH］如果真实状态推翻本账本，先更正事实和证据边界，再更新状态；不得为保持文档一致而隐去失败。
- ［KNOWN｜HIGH］新阶段开始前先复核上一阶段硬门；未通过的外部依赖必须保留 `BLOCKED_EXTERNAL`，不能折算为 PASS。
- ［KNOWN｜HIGH］权重、门限、Profile 或结果语义变化必须进入 `docs/DECISION_LOG.md`；测试修复但不改变合同的机械变化不单独升级 Profile。
