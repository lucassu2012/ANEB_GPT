# ANEB 里程碑账本

> 更新日期：2026-07-26（Asia/Shanghai）
>
> 用途：把“3 个子项目 + 1 个横切机制”的目标、实现步骤、验收证据和当前状态固化在仓库中。
> 会话摘要只能帮助定位；代码、CI、真机现场和本账本引用的原始证据才是完成判断依据。

## 1. 最终目标与架构

| 轨道 | 最终应实现的内容 | 当前边界 |
|---|---|---|
| P1 手机端 | ANEB App 以真实网络流量模拟 Token 多模态、AI 实时交互和网络综合业务；动态展示关键指标，冻结全量指标、评分、结论与可导出证据 | ［KNOWN｜HIGH］三类引擎、Compose 动态界面、Room v19、strict-v2 结果与导出主链已实现；M0-EC1 Token Quick 正负 READY 已完成，M0-EC2 AI 实时 Quick 的 0.5.13 客户端合同已本地实现但尚无 CI/P40 READY |
| P2 测试服务器 | 提供可审计的 echo、token-sim、download、实时交互、综合网络与受控故障原语；任何共享主机变更都可预检、互斥、回滚和验后复核 | ［KNOWN｜HIGH］E-01 仍运行 `aneb-server/0.8.0`，binary SHA-256=`fad6fdd53ebb73c63b2bf3b9f03106f1348626853cb344d72c3f6d08511fdce7`；0.8.1 实时合同候选仅在本地，离线门和 CI 全绿前禁止部署 |
| P3 标准/业务模型 | 独立模拟 Kimi、DeepSeek、千问等 AI 业务行为，不调用真实 API；以版本化 Profile、模型摘要、校准状态和确定性轨迹供 P1/P2 执行 | ［KNOWN｜HIGH］本地候选已升至 behavior model 0.3.2、catalog 1.6.0、16 个 Profile 与 6 个运行包；没有真实授权数据时不得冒充 calibrated/validated |
| Profile 横切机制 | 每类测试冻结业务类型、全量业务/网络指标、质量目标、动态关键指标、评分算法、结论与网络建议；P1/P2/P3 必须使用同一精确合同 | ［KNOWN｜HIGH］Token Quick 1.2.1 已完成跨端 READY；AI 实时 Quick 1.1.1 已形成本地第二条执行合同，但 CI/真机尚未验收；不能扩大表述为所有 Profile 已统一 |

## 2. 总体阶段

| 阶段 | 必须实现的内容 | 完成证据 | 当前判断 |
|---|---|---|---|
| M0 契约冻结 | 冻结 Profile/KPI/AQS/结果 Schema、客户端执行要求、服务器能力回执、业务模型输入输出与来源合同 | catalog/schema 自动校验；同一 Profile SHA；正负合同测试；clean commit CI | ［KNOWN｜HIGH］部分完成；M0-EC1 Token Quick 已完成，M0-EC2 AI 实时 Quick 已完成 EC2-01～06 本地实现并进入最终离线门，其他 Profile 的通用跨端执行合同仍未收敛 |
| M1 核心闭环 | 三类 App 引擎、单节点服务、动态测试、Room/导出、无线证据与重复性门禁可用 | Android/Go 门禁；P40 单节点真机结果；可复算导出 | ［KNOWN｜HIGH］原计划单节点窄切片已通过，但三级节点内容未完成 |
| M2 外场 MVP | 6–8 点位、忙闲时段、双运营商、三级节点，生成可比较的体验地图/报告 | 冻结采样计划、有效样本矩阵、独立复算报告 | ［KNOWN｜HIGH］未启动；三级节点与活动资源仍为外部依赖 |
| M3 真实交互 | AI 实时语音/视频仿真、真实业务画像校准、打断/恢复和业务适配器 | 授权观测、留出集、真机边缘条件、适配器合同 | ［KNOWN｜HIGH］WebSocket 仿真轨已完成，真实画像与 RTP/WebRTC/逐帧适配未完成 |
| M4 产品化 | 专业 UI、安装升级、普通用户测试/结果/分享、隐私安全、正式签名与发布运维 | 非 ADB 用户整链、Release 签名、公开发布包、维护手册 | ［KNOWN｜HIGH］Debug 产品化大部完成；正式签名和公开发布未完成 |

## 3. 当前执行切片：M0-EC1 Token Quick

| 门 | 应实现的内容 | 权威证据 | 状态 |
|---|---|---|---|
| EC1-01 合同与范围 | 固定 App 0.5.12 / server 0.8.0 / model 0.3.1 / Quick 1.2.1；精确 20 个 echo、3 个 token task、1 个 download；不调用真实 AI API | `spec/`、`profiles/`、`docs/M0_TOKEN_QUICK_EXECUTION_CONTRACT_VALIDATION_2026-07-18.md` | ［KNOWN｜HIGH］完成 |
| EC1-02 E-01 服务端 | 能力回执、同-run start/end barrier、连续 request-entry、固定 CA TCP/UDP 与 download 原语；受锁部署和验后基线一致 | E-01 live `serverinfo`、部署 receipt、binary SHA、共享主机指纹 | ［KNOWN｜HIGH］完成；当前 0.8.0 active，禁止为追求 rc=0 重部署 |
| EC1-03 Android fail-closed | 回执缺失/冲突必须在业务请求前拒绝；机器原因落 Room；score/grade=`null`；任务/KPI 产物为零；只插入一次终态 | Android 单测、Room/结果验证器、D-89 durability 回归 | ［KNOWN｜HIGH］本地实现完成 |
| EC1-04 正负采集 | 正向冻结 20/3/1 与全量 Room/服务端证据；负向用一次性 loopback 代理仅删除能力回执且不修改 E-01 | collector、negative proxy、raw/client/time/device/bundle verifier | ［KNOWN｜HIGH］本地实现完成 |
| EC1-05 发布事务 | `.complete`、`COMPLETE`、report 都不是终态；只有最后原子提交并四方语义绑定的 UUIDv7 sibling `READY.json` 才可发布 | D-87；release verifier；ACL/reparse/paired-sidecar 故障注入 | ［KNOWN｜HIGH］本地实现完成 |
| EC1-06 CI 来源 | clean commit 构建 Debug APK；attestation 精确签 APK、manifest、安装说明三项；最终清单精确四项；独立 reverify | D-88；GitHub run；provenance bundle；机器 manifest/checksums | ［KNOWN｜HIGH］完成；正向 source `10927c1` / run `30124854408`，负向 source `67eb66d` / run `30162011890`，各自 provenance 独立复核通过 |
| EC1-07 正向 P40 | 安装同提交 CI APK；在实时干净现场执行 Quick；客户端、E-01 和 READY 证据必须同 run、同 APK、同 server binary | 正向 `<collection>.READY.json` 及独立 verifier PASS | ［KNOWN｜HIGH］完成；run `019f95f9-a317-7766-9725-243b9660b9f1`，READY SHA `d67efb7f…790b`，20/3/1，99.2/A 但 `LOW/INCONCLUSIVE`、coverage 0.15 |
| EC1-08 负向 P40 | 在不修改 E-01 的前提下制造 `receipt_missing`；服务端业务入口为零、客户端零任务/KPI、机器原因稳定；精确清除 reverse/proxy | 负向 `<collection>.READY.json` 及独立 verifier PASS | ［KNOWN｜HIGH］完成；run `019f99c7-5b40-75ba-ad58-b5b522e9abf9`，READY SHA `b801131d…07af`，control 1 / business 0，任务/KPI/评分产物为零 |
| EC1-09 结束清理 | 停止本轮 ANEB/代理/VPN/抓包，清除 reverse/临时设置，回到 Huawei Launcher 并即时复核；服务器无临时残留 | 结束现场只读清单；READY 的 final-clean 证据 | ［KNOWN｜HIGH］完成；最终 PhoneGuard receipt `f1614e31…bcef`，Launcher、冲突 PID/service/accessibility/VPN/tun=0，reverse 已清，E-01 marker 不存在且锁已释放 |
| EC1-10 切片结案 | 回填 commit/run/APK SHA、正负 run/READY、结论与剩余风险；只在全部门通过后标记 M0-EC1 完成 | 本账本、计划对齐文档、验证记录、CI 与 READY | ［KNOWN｜HIGH］完成；完整证据与限制见 `M0_TOKEN_QUICK_READY_VALIDATION_2026-07-25.md`，完成仅适用于 Token Quick 窄切片 |

### 3.1 下一执行切片：M0-EC2 AI 实时 Quick

| 门 | 应实现的内容 | 当前状态 |
|---|---|---|
| EC2-01 范围冻结 | 固定 Profile、候选版本、实时协议签名、正负证据、非目标和停止条件 | ［KNOWN｜HIGH］完成；见 `M0_EC2_AI_REALTIME_QUICK_SCOPE_2026-07-25.md` |
| EC2-02 P2 能力 | Profile 白名单注册表、实时原语与 Token 向后兼容 | ［KNOWN｜HIGH］本地完成；0.8.1 尚未部署 |
| EC2-03 P2 审计 | `realtime_run`、实时路由、有界会话摘要、连续审计与隐私门 | ［KNOWN｜HIGH］本地完成 |
| EC2-04 P1 能力门 | 首个 WebSocket 前验证本地/节点/Profile/原语合同 | ［KNOWN｜HIGH］本地完成 |
| EC2-05 P1 持久化 | `receipt_missing`、零业务产物、null 评分与单次 Room 终态 | ［KNOWN｜HIGH］本地完成 |
| EC2-06 采集/复核 | 实时 collector、正负 DB/audit/protocol/cross verifier、私有根与 D-87 READY | ［KNOWN｜HIGH］本地完成；定向 100 项通过（1 项按设计跳过），没有真实 READY |
| EC2-07 离线门 | Kotlin/Go/Python 正反例、全仓质量门、凭据扫描与 clean commit | ［KNOWN｜HIGH］本地门禁完成；最终全仓 719/719（16 项设计跳过）、behavior model 43/43、Android/Go/发布边界与 651 tracked + 13 staged 密钥扫描全绿；本文件所在提交为 clean candidate，CI 尚未执行 |
| EC2-08 云端门 | 同提交 0.5.13/0.8.1 provenance 与全绿 CI | ［KNOWN｜HIGH］待执行 |
| EC2-09～10 跨端结案 | 受保护部署、P40 正负 READY、精确清理与独立复核 | ［KNOWN｜HIGH］待执行；离线门通过前禁止触碰 P40/E-01 |

## 4. 已冻结的关键节点

1. ［KNOWN｜HIGH］D-80：`SHARED_TEST_STATUS.md`、claim、lease、待交接、异常锁定和二次 Verifier 已退役；不得再次用它们阻塞或授权 P40。
2. ［KNOWN｜HIGH］P40 准入只看实时现场：在线、Huawei Launcher 前台、无冲突 App/服务、无 VPN/tun/抓包；来源不明的会话不得擅自停止。
3. ［KNOWN｜HIGH］D-85～D-87：现场证据必须来自 clean commit、私有证据根、独立重算和最后 READY 逻辑提交点。
4. ［KNOWN｜HIGH］D-88：CI provenance 的签名 subject 是 APK、manifest、安装说明三项全集，不是仅 APK。
5. ［KNOWN｜HIGH］D-89：合同拒绝结果记录实际测量端点；终态日志失败不得触发第二次 Room 插入。
6. ［KNOWN｜HIGH］E-01 当前 0.8.0 服务端身份已闭合；本轮客户端验收不包含服务器重新部署。
7. ［KNOWN｜HIGH］2026-07-23 本地整仓门禁通过；随后正向 CI `30124854408` 与负向最终 CI `30162011890` 通过并生成独立验证的云端工件。负向最终定向门禁为 proxy 25/25、collector 89/89、raw 41/41、bundle 118/118、release 27/27。
8. ［KNOWN｜HIGH］M0-EC2 本地候选冻结为 App 0.5.13 / server 0.8.1 / model 0.3.2 /
   catalog 1.6.0 / AI 实时 Quick 1.1.1；Profile 规范化 SHA-256=
   `701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7`，runtime SHA-256=
   `f2472d2faa7a3ab51582e1496a6925d106806fdd9747e097e20e38e921d9dc07`。
9. ［KNOWN｜HIGH］M0-EC2 collector/release 新增路径绑定的私有根安全回执；采集、READY publisher
   与最终 consumer 都执行只读 ACL 检查，owner 或可写主体漂移即 fail closed，不自动修改 ACL。

## 5. 本轮完成顺序与下一阶段

1. ［KNOWN｜HIGH］GitHub CLI 已用浏览器设备流认证；已泄露的历史 PAT 未写入仓库或脚本。
2. ［KNOWN｜HIGH］正向 source `10927c1` / CI `30124854408` 与负向 source `67eb66d` / CI `30162011890` 的 provenance 候选已独立验证。
3. ［KNOWN｜HIGH］P40 正向与负向短会话、独立 READY 复核、PhoneGuard 清理和 E-01 锁/marker 复核均已完成。
4. ［KNOWN｜HIGH］M0-EC1 Token Quick 窄切片现已结案；不重跑已闭环证据，也不为追求新的部署返回码重复修改 E-01。
5. ［KNOWN｜HIGH］当前从 M0-EC2 EC2-07 的全绿本地候选进入 clean commit/push，再执行 EC2-08
   GitHub provenance；CI 未全绿前不操作 P40/E-01。
6. ［KNOWN｜HIGH］A6 审核交接当前关闭；不得打开、复制或上传 reviewer HTML、secret、seed、
   private ZIP、sealed binding 或旧 material/template，也不得运行 materialization/qualification。

## 6. 更新规则

- ［KNOWN｜HIGH］每完成一个门，必须同时记录实际产物身份和验证输出；“代码已写”“测试未报错”或“界面看起来正常”不能单独证明完成。
- ［KNOWN｜HIGH］如果真实状态推翻本账本，先更正事实和证据边界，再更新状态；不得为保持文档一致而隐去失败。
- ［KNOWN｜HIGH］新阶段开始前先复核上一阶段硬门；未通过的外部依赖必须保留 `BLOCKED_EXTERNAL`，不能折算为 PASS。
- ［KNOWN｜HIGH］权重、门限、Profile 或结果语义变化必须进入 `docs/DECISION_LOG.md`；测试修复但不改变合同的机械变化不单独升级 Profile。
