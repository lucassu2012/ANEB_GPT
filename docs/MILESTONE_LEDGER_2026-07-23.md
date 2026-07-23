# ANEB 里程碑账本

> 更新日期：2026-07-23（Asia/Shanghai）
>
> 用途：把“3 个子项目 + 1 个横切机制”的目标、实现步骤、验收证据和当前状态固化在仓库中。
> 会话摘要只能帮助定位；代码、CI、真机现场和本账本引用的原始证据才是完成判断依据。

## 1. 最终目标与架构

| 轨道 | 最终应实现的内容 | 当前边界 |
|---|---|---|
| P1 手机端 | ANEB App 以真实网络流量模拟 Token 多模态、AI 实时交互和网络综合业务；动态展示关键指标，冻结全量指标、评分、结论与可导出证据 | ［KNOWN｜HIGH］三类引擎、Compose 动态界面、Room v19、strict-v2 结果与导出主链已实现；M0-EC1 的新 0.5.12 候选尚未完成同提交 CI 与 P40 正负向验收 |
| P2 测试服务器 | 提供可审计的 echo、token-sim、download、实时交互、综合网络与受控故障原语；任何共享主机变更都可预检、互斥、回滚和验后复核 | ［KNOWN｜HIGH］E-01 正运行 `aneb-server/0.8.0`，binary SHA-256=`fad6fdd53ebb73c63b2bf3b9f03106f1348626853cb344d72c3f6d08511fdce7`；本轮不需要重复部署 |
| P3 标准/业务模型 | 独立模拟 Kimi、DeepSeek、千问等 AI 业务行为，不调用真实 API；以版本化 Profile、模型摘要、校准状态和确定性轨迹供 P1/P2 执行 | ［KNOWN｜HIGH］behavior model 0.3.1、catalog 1.5.0、16 个 Profile 与 6 个运行包已进入 M0-EC1 候选；没有真实授权数据时不得冒充 calibrated/validated |
| Profile 横切机制 | 每类测试冻结业务类型、全量业务/网络指标、质量目标、动态关键指标、评分算法、结论与网络建议；P1/P2/P3 必须使用同一精确合同 | ［KNOWN｜HIGH］Token Quick 1.2.1 已形成首个跨端窄切片；不能把一个 Quick 切片扩大表述为所有 Profile 已统一 |

## 2. 总体阶段

| 阶段 | 必须实现的内容 | 完成证据 | 当前判断 |
|---|---|---|---|
| M0 契约冻结 | 冻结 Profile/KPI/AQS/结果 Schema、客户端执行要求、服务器能力回执、业务模型输入输出与来源合同 | catalog/schema 自动校验；同一 Profile SHA；正负合同测试；clean commit CI | ［KNOWN｜HIGH］部分完成；M0-EC1 本地实现和 E-01 子阶段通过，clean commit/CI/P40 READY 未闭环 |
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
| EC1-06 CI 来源 | clean commit 构建 Debug APK；attestation 精确签 APK、manifest、安装说明三项；最终清单精确四项；独立 reverify | D-88；GitHub run；provenance bundle；机器 manifest/checksums | ［KNOWN｜HIGH］本地流水线与 2026-07-23 门禁完成；clean commit 和新 CI 尚未产生 |
| EC1-07 正向 P40 | 安装同提交 CI APK；在实时干净现场执行 Quick；客户端、E-01 和 READY 证据必须同 run、同 APK、同 server binary | 正向 `<collection>.READY.json` 及独立 verifier PASS | ［KNOWN｜HIGH］待执行 |
| EC1-08 负向 P40 | 在不修改 E-01 的前提下制造 `receipt_missing`；服务端业务入口为零、客户端零任务/KPI、机器原因稳定；精确清除 reverse/proxy | 负向 `<collection>.READY.json` 及独立 verifier PASS | ［KNOWN｜HIGH］待执行 |
| EC1-09 结束清理 | 停止本轮 ANEB/代理/VPN/抓包，清除 reverse/临时设置，回到 Huawei Launcher 并即时复核；服务器无临时残留 | 结束现场只读清单；READY 的 final-clean 证据 | ［KNOWN｜HIGH］随 EC1-07/08 执行 |
| EC1-10 切片结案 | 回填 commit/run/APK SHA、正负 run/READY、结论与剩余风险；只在全部门通过后标记 M0-EC1 完成 | 本账本、计划对齐文档、验证记录、CI 与 READY | ［KNOWN｜HIGH］待执行 |

## 4. 已冻结的关键节点

1. ［KNOWN｜HIGH］D-80：`SHARED_TEST_STATUS.md`、claim、lease、待交接、异常锁定和二次 Verifier 已退役；不得再次用它们阻塞或授权 P40。
2. ［KNOWN｜HIGH］P40 准入只看实时现场：在线、Huawei Launcher 前台、无冲突 App/服务、无 VPN/tun/抓包；来源不明的会话不得擅自停止。
3. ［KNOWN｜HIGH］D-85～D-87：现场证据必须来自 clean commit、私有证据根、独立重算和最后 READY 逻辑提交点。
4. ［KNOWN｜HIGH］D-88：CI provenance 的签名 subject 是 APK、manifest、安装说明三项全集，不是仅 APK。
5. ［KNOWN｜HIGH］D-89：合同拒绝结果记录实际测量端点；终态日志失败不得触发第二次 Room 插入。
6. ［KNOWN｜HIGH］E-01 当前 0.8.0 服务端身份已闭合；本轮客户端验收不包含服务器重新部署。
7. ［KNOWN｜HIGH］2026-07-23 本地整仓门禁通过：Android 单测/Lint/构建/发布边界、601 项脚本测试（16 skip）、41 项行为模型测试、server、gateway 与凭据扫描均通过；本地 APK SHA-256=`357D14DDF3F5A0B525045EB282E9369F6688915164F0BAF7071AA38AB5DF094E`，但它不是可用于正式取证的云端工件。

## 5. 当前唯一执行顺序

1. ［KNOWN｜HIGH］安装并认证 GitHub CLI；不得使用已泄露的历史 PAT。
2. ［KNOWN｜HIGH］显式暂存本轮 38 个实现/测试/文档文件，重新扫描 staged 内容，生成 clean commit 并推送当前 `codex/m0-token-quick-contract` 分支。
3. ［KNOWN｜HIGH］等待所有 GitHub CI job 结束；任何失败先修复并重新跑本地相关门，不能下载失败 run 的 APK。
4. ［KNOWN｜HIGH］下载并独立验证同提交 provenance 候选；记录 commit、run、APK/manifest/checksums/bundle/gh 摘要。
5. ［KNOWN｜HIGH］按 P40 实时现场规则执行正向短会话、清理并复核。
6. ［KNOWN｜HIGH］重新确认现场后执行负向短会话、清理并复核。
7. ［KNOWN｜HIGH］独立验证两个 READY，回填 EC1-06～10；只有此时才能写“M0-EC1 跨端正负验收完成”。

## 6. 更新规则

- ［KNOWN｜HIGH］每完成一个门，必须同时记录实际产物身份和验证输出；“代码已写”“测试未报错”或“界面看起来正常”不能单独证明完成。
- ［KNOWN｜HIGH］如果真实状态推翻本账本，先更正事实和证据边界，再更新状态；不得为保持文档一致而隐去失败。
- ［KNOWN｜HIGH］新阶段开始前先复核上一阶段硬门；未通过的外部依赖必须保留 `BLOCKED_EXTERNAL`，不能折算为 PASS。
- ［KNOWN｜HIGH］权重、门限、Profile 或结果语义变化必须进入 `docs/DECISION_LOG.md`；测试修复但不改变合同的机械变化不单独升级 Profile。
