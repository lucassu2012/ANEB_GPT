# DECISION_LOG — 决策日志与外部依赖

> 制度来源：参考项目"决策日志 + PO 决策分离"（本项目为研究者单人决策，简化为单表追加制）。决策追加不覆盖；推翻旧决策时新增条目并引用被推翻的 D-xx。

## 决策日志

| ID | 日期 | 决策 | 理由/来源 |
|---|---|---|---|
| D-01 | 2026-07-11 | 交付物 = 分析文档补 KPI 章节 + 独立工具设计文档 | grill-me 访谈；KPI 体系作为工具需求输入，形成"需求→实现"闭环 |
| D-02 | 2026-07-11 | 定位：研究/取证工具；数据质量 > 功能覆盖 > UI | 访谈 |
| D-03 | 2026-07-11 | 平台：Android 原生 Kotlin（minSdk 29），iOS 不做 | 只有 Android 能拿无线层信息与精细网络回调 |
| D-04 | 2026-07-11 | 测试对端：自建仿真服务器为主，真实 LLM API 探针为辅（阶段二） | 网络贡献可精确归因；真实 API 混杂模型侧波动 |
| D-05 | 2026-07-11 | 服务器：国内云 VM 单节点起步，后加海外对照 | 骨干段短、无线侧信号占比高 |
| D-06 | 2026-07-11 | MVP 指标 = T/U/R 组；C 组与 QUIC A/B 放阶段二 | 控制 MVP 周期；连续性实验设计复杂 |
| D-07 | 2026-07-11 | 3 个版本化预置场景（S1 对话/S2 编码 Agent/S3 多模态），发布即冻结、改动升版 | 跨时间/地点/设备横比 |
| D-08 | 2026-07-11 | 四级门限（优/良/可/差）+ AQS 0–100 综合分 | 归因与对外传播兼顾 |
| D-09 | 2026-07-11 | 数据：本地 Room 全量明细 + 服务端 JSONL 双写 | 多设备自动汇总且不引入新组件 |
| D-10 | 2026-07-11 | 技术栈：Kotlin+OkHttp / Go 服务端；阶段二 Cronet + quic-go | EventListener 计时精度；Go 的 pacing 精度与 H3 路径 |
| D-11 | 2026-07-11 | monorepo：docs/ app/ server/ profiles/（后加 evidence/） | profile 与门限是两端共识，单仓不分叉 |
| D-12 | 2026-07-12 | 吸收参考项目（ANEB Android Echo 切片）制度：四态证据制、claim scope 前置、fail-closed 三态 Gate、红队闭环、供应链钉死、失败样本 null 语义、成功主路径优先 | 参考文档 §4/§5 + 制度对齐分析 |
| D-13 | 2026-07-12 | v0.2 红队修订：32 项经对抗验证的测量失真风险缓解并入设计文档与 KPI 口径（agent-qoe-kpi v0.2、profiles v0.2.0、设计文档 v0.2） | 《测量红队清单》（4 视角发现→合并→逐条对抗验证） |
| D-14 | 2026-07-12 | 参考项目做法与本项目冲突处不照搬，逐条记录于下方"否决记录" | 制度对齐分析 conflicts 清单 |
| D-15 | 2026-07-12 | E-01 部署**不修改 chrony**（偏离设计文档 §6"禁 makestep"条款）：共用生产服务器不动全局时钟纪律；srv_ts 单调锚点（R-24）已免疫墙钟步进，chrony 现状 RMS offset 86µs 质量足够；chronyc tracking 快照将随运行元数据存档。P0-C15 步进实验改在本机 WSL2/一次性环境执行 | E-01 为共用服务器（另一项目 mongod/node 在跑）；evidence/phase0/server_provision_20260712.log |
| D-16 | 2026-07-12 | 本机/客户端测量**必须显式绕开系统代理并检测代理存在**：首次公网基线被本机代理（127.0.0.1:33210/7897）静默劫持，RTT p50 从 28.1ms 放大到 1519.7ms（54 倍）而流节奏无异常——单看 pacing 无法发现路径被劫持。PC 侧探针一律 UseProxy=false + 记录代理检测结果；Android NetGuard 的 VPN/代理硬拒测（R-03）优先级提升 | evidence/phase0/first_internet_baseline_20260712.log |
| D-17 | 2026-07-13 | 引入本项目**首个第三方 Go 依赖** `github.com/quic-go/quic-go` **v0.60.0**（钉死精确版本入 go.mod/go.sum，go 指令随之升 1.25.0，与部署工具链 go1.26 兼容）——专项用于阶段 2 HTTP/3：`-h3` 同端口 UDP 并行 http3.Server 复用同一路由树，**fail-closed**（无 -tls-cert/-tls-key 时 -h3 拒绝启动，h3 为 TLS-only）；TCP 侧加 Alt-Svc 广告。协商证据两侧留痕：所有响应带 `X-Aneb-Proto`（服务端视角 r.Proto + via=tcp/h3-server 处理栈标记），/serverinfo 增 `h3_enabled`——**QUIC 启用 ≠ 协商 h3**（红队项），A/B 分组以逐样本协商记录为准。"无外部依赖"原则（§6）就此收窄为"标准库 + quic-go 专项"，与 D-10 阶段二规划一致 | 设计文档 §6/§8 阶段 2；D-10；supply-chain：版本钉死 + go.sum 校验 |

| D-18 | 2026-07-13 | **P0-C14 验收判据修订**：原"U1 vs iperf3 偏差<20%"误把应用层 HTTP goodput 与裸 TCP 稳态直接对标——实测比值 0.66 稳定（1MiB POST 含请求头/逐块打戳/响应回程 vs C 裸 TCP 紧循环；亚毫秒 RTT 排除慢启动主因；iperf3 自身 run 间变异 ±19% 使 20% 门限先天偏紧）。修订为**比值带判据：U1 ∈ [0.5, 1.0] × iperf3 稳态中位**。原始 FAIL 与修订 PASS 并列入账（STATUS.json），判据变更透明可审计 | evidence/phase0/c14_u1_vs_iperf3_20260713.log 归因诊断 |
| D-19 | 2026-07-13 | **E-01 的 TLS 切换与 H3 部署合并到 Cronet A/B 批次执行**：服务端开 TLS 会使现役 http:// 客户端断链，须与客户端 https+自签信任锚+Cronet 改造一次协同切换；证书已预生成（/opt/aneb/tls，EC P-256，SAN=IP）。届时需用户在控制台放行 **UDP 8443**（E-01 依赖项追加） | H3 代码已合并（D-17）且 37 测试全绿，仅部署时点推迟 |
| D-20 | 2026-07-13 | **阶段 2 C 组连续性实验（continuity 模式）+ aqs v0.2 落地口径**：①C2 恢复计时起点＝客户端**检出**中断的时刻（IOException 浮出/读超时），非网络物理中断时刻——这是应用层端到端体验口径（claim scope 一致），模拟器实测蜂窝 data off 不 RST 存量 socket、检出耗时=readTimeout 30s，本身就是"静默挂起税"的直接证据；②重连=新请求同参数、指数退避 500ms×2^n、最多 5 次，全部失败→C2 该样本记 null（R-10，不记封顶值），run 状态 recovery_failed；③连续性 run 与场景 run 分流（独立引擎 ContinuityRunner/独立日志 KEY CONTINUITY_*/独立表 continuity_result），不复用场景状态机；④路径监控豁免：绑定模式用 PathMonitor(exemptPathChanges=true) 设计本尊，AUTO 模式用对偶 ExemptDefaultNetWatch——路径事件全量记 EnvEvent(exempt=true) 但绝不 invalidate（路径迁移是测量对象）；监控器自身故障不豁免，仍 fail-closed；⑤aqs v0.2＝v0.1 权重×0.8+C1 10%+C2 10%（C1 锚 0.5/2/5%，C2 锚 1/3/10s），仅显式传入 ContinuityKpi 才出 v0.2 分，无 C 数据回退 v0.1 语义不变；⑥C3 一律标 functional_only（模拟器 NAT/OkHttp 池 keepalive 5min 语义与运营商 CGNAT 不同，不构成 C3 测量结论） | KPI 文档 5.1/5.2/5.4；设计文档 §8 阶段 2；evidence/phase2/continuity_e2e_20260713.log |

| D-21 | 2026-07-13 | **Cronet QUIC 的公共信任链约束**：cronet-embedded 143 对 QUIC 强制 is_issued_by_known_root 校验（NetLog 逐帧证据：UDP 通、握手推进到证书阶段、客户端以 certificate_unknown 收连接退 h2），自签/私有 CA 即使装入 NSC 信任锚也无法让 h3 协商成功——TCP/TLS 不受此限（A 组 h2 正常）。**拒绝用 MockCertVerifier 关校验（造假红线）**。结论：①本地自签环境只能验证 A/B 机制与 fallback 语义（已 13 单测锚定+A 组端到端）；②E-01 公网 QUIC A/B 需要域名+公共 CA 证书（Let's Encrypt），新增外部依赖 E-06；③A/B 结论仅在 Cronet 栈内 TCP vs QUIC 对比得出，与 OkHttp 主测量数据不互比（栈间差异 KDoc 声明） | evidence/phase2/cronet_ab_e2e_20260713.log NetLog 诊断 |

| D-22 | 2026-07-13 | **SNI 双通道测量决策**（应对 R-33 实测中间盒行为）：真机首测实证中国电信 5G SA 对 `*.sslip.io`/`*.nip.io` 主机名注入 SNI-keyed TLS RST（bare-IP 同路径 TLS 可完成、真实域名 api.moonshot.cn 放行），经 sslip.io 主机名的 E-01 蜂窝取证/AB/连续性被整体阻断。决策：①**蜂窝主通道 = bare-IP + IP-SAN 自签证书**（OkHttp 主测量不需 known-root，绕过 SNI-RST 采集真实蜂窝 KPI）；②**保留带 SNI 通道做连接成功率对比**，落为新候选指标 REACH（按 {SNI 主机名, bare-IP, 协议栈} 分组的 TLS 握手成功率，KPI 文档 5.5）——SNI-RST 由此从"测量障碍"转为"可量化测量维度"；③**Cronet QUIC 蜂窝受 known-root（D-21）+ SNI 双重约束**，单独观测不并入 OkHttp 主线；④与 P3-C11（按 TLS 栈指纹的 RST）同族记录为中间盒 TLS 干预两种触发键。**研究正当性边界**：自有服务器 + 自有设备 + 自有 SIM 的授权网络性能测量，SNI-RST 作为量化维度纳入、bare-IP 通道采真实 KPI，非规避审查 | evidence/phase3/realdevice_first_campaign_20260713.log（step3 HEADLINE FINDING）；R-33；P3-C11（evidence/phase3/STATUS.json）；D-21 |
| D-23 | 2026-07-13 | **ContinuityRunner C2 跨网迁移恢复修复 + 两种 C2 语义**：真机（华为 P40 Pro 电信 5G SA）continuity 绑定蜂窝网 net110，流中 蜂窝→WiFi 硬切换时系统拆除原蜂窝网（bound_network_lost），原重连按设计固定回绑原句柄 110 → EPERM(Operation not permitted) → 5 次退避全败 → recovery_failed、无 recovery_ms；模拟器因是"平滑网络替换、原句柄可回绑"测得 508ms 掩盖了此缺陷。根因＝重连绑定的是发起时的固定 Network 句柄，真实移动性硬切换下该句柄失效。修复（engine/ContinuityRunner.kt + 新增 ContinuityRecovery.kt）：①重连时原句柄失效（bound_network_lost 路径事件或回绑 EPERM/ENETUNREACH 错误）即释放已死原绑定、改用 unbound client 落到当前系统新默认网（QUIC 连接迁移/重连的应用层对应，真实用户体验口径）；②C2 恢复计时口径覆盖"切到新网后首 token 到达"（interrupt→新网首 token，含退避与换网耗时，与 D-20 一致）；③区分两种 C2 语义并入库/日志：**same_network 重连恢复**（原绑定网仍在或 AUTO 透明迁移，模拟器 508ms 属此）vs **cross_network 迁移恢复**（真实移动性场景，Agent 长会话核心诉求）——CONTINUITY_RECOVERY 增 semantic=、CONTINUITY_C2 增 cross_network_samples=、新增 CONTINUITY_REBIND 日志 KEY、continuity_result 增 c2CrossNetworkRecoveries 列（DB v10→v11 additive）；④重连决策抽出纯 JVM ContinuityRecovery（副作用注入），补 9 项单测覆盖"原句柄失效→迁新网"与 same_network 决策不回归。约束守住：PathMonitor 豁免机制不动（exempt=true 未误判 INVALID）、既有测量语义/AUTO 508ms 路径不回归（决策级单测锚定 + 全量 278 单测通过）。模拟器验 same_network 不回归已过；**真机 cross_network 已验证（2026-07-13，同台 P40 Pro 电信 5G SA，run 019f5af6）**：硬切换拆除原绑定蜂窝 net119 → `CONTINUITY_REBIND ok=true` → 新蜂窝默认网 net120 上线 → `CONTINUITY_RECOVERY semantic=cross_network recovery_ms=7737ms` → `status=completed`（原缺陷同场景为 EPERM×5/recovery_failed），DB `c2CrossNetworkRecoveries=1`（v11 迁移端到端）；重连错误由 `EPERM Binding socket to network`（死句柄）变为 `ConnectException`（在新默认网尝试），精确证明"不再在死句柄上重试"。本环境无可用 WiFi AP，以 `svc data disable→enable` 令蜂窝新句柄作新默认网、等价复现 §3（net110 死、新蜂窝网上线），代码路径与 蜂窝→WiFi 完全一致；真正 蜂窝→WiFi 变体待有 WiFi AP 会话补测 | evidence/phase3/realdevice_continuity_crossnet_fix_verify_20260713.md（修复验证）；evidence/phase3/realdevice_continuity_kimi_20260713.log §3（EPERM 现象）；evidence/phase2/continuity_e2e_20260713.log（模拟器 508ms 基线，transport=auto）；D-20（C2 口径）；KPI 文档 §5.1 |

| D-24 | 2026-07-14 | **VpnService 流量观测模式（设计定案，本轮只出文档、不实施）**：为填补"手机端第三方**封闭** AI App（豆包/Kimi 客户端等）真实会话网络体验"这一测法空白——此类 App 无公开流式 API（ProviderPresets excludedNote 明确排除飞书 Aily/蚂蚁阿福），API 探针测的是"我用官方 API"而非"用户用官方 App"、干净路径仿真更测不到真实 App 加密流。这是三种 App 测法里最重最敏感的一种，故先设计清楚再决定建不建。决策：①**确立为独立互斥模式**：ANEB 作本地 VpnService 建 TUN 虚接口 → 读 IP 包 → 按用户显式选定 App 的 UID 过滤（PackageManager/ConnectivityManager 取 UID，per-UID 路由）→ 用户态 TCP/UDP 协议栈把流量重新转发出去（TUN 只给 IP 层，需自建流重组+转发或用成熟 tun2socks 思路）→ 逐包时序记录——**从加密流的时序推断，不解密内容**；这是**全项目工程量最大的一块**（TUN + 包解析 + 流重组 + 转发），量级远超现有 OkHttp/SSE 测量栈。②**能测 vs 不能测边界钉死、全部标"流级推断，近似"**：能——SYN→SYN-ACK 连接建立 RTT、TLS 握手成功/失败（ClientHello→是否被 RST，即 SNI-RST 类，与 R-33/D-22 同源、复用同一 ClientHello 观测面）、SNI 主机名（ClientHello 明文）、DNS 查询时延、上行请求突发字节量与时刻、**下行响应字节到达节奏**（请求突发→首个响应包≈TTFT 代理；下行包间隔停顿≈ITL/stall 代理）、字节总量；不能——解密内容、精确 token 边界（TLS record 合帧/分片使"逐 token"只能是**流级近似**、非应用层精确）、区分 token 与控制帧。③**新 claim_scope=`application_flow_observation_no_decrypt`**，与 `application_end_to_end_to_probe_node`（仿真节点）、`application_end_to_end_to_llm_api`（LLM API 探针）口径明确分开，结论一律标"流级推断"、**不进 AQS**。④**隐私与同意模型**：只观测本机 + 用户显式选定 App + 不解密 + 不留存 payload（只留元数据时序）+ 数据只存本机（呼应 §9.1 GPS 只存本机）；VpnService 系统强制显示持续通知与 VPN 图标（Android 不可关）；首次需明确的知情同意界面（说明抓什么/不抓什么/仅观测期）；随时可停。⑤**与 D-16（本机测量代理红线）/R-03（NetGuard VPN 硬拒测）的关系**：ANEB-as-VPN 是**独立模式**，与 ANEB 干净路径测量（仿真节点/API 探针）**互斥**——VPN 开启期间干净路径测量必须禁用（VPN 自身用户态转发会污染路径、批化，正是 R-03/D-16 判为"不可作运营商中间盒证据"的场景），反之干净路径测量期间 VPN 观测必须关；明确模式切换状态机。⑥**正当性边界**：自有设备 + 用户自选 App + 不解密 + 只存本机的**授权网络性能观测**；**非中间人、非抓他人流量、非规避**；明确不做——不解密 TLS、不注入证书、不 MITM。⑦**分期建议（本轮不实施）**：推荐先出本设计文档、暂不建；若将来建，MVP 只做**连接层确定可靠量**（SYN-RTT + TLS 握手成功率/SNI-RST + DNS 时延 + 字节量，这些无需解密即确定可靠、且与 REACH/R-33 口径自然衔接）；**下行节奏→TTFT/ITL 推断作二期**（流级近似度需先用 API 探针/仿真节点已知真值交叉验证后再启用）；给出可选最小 spike（若将来验证可行性该做什么）但本轮不做。**用户裁定（2026-07-14）：采纳推荐＝暂不建，VpnService 不实施；如将来重启，先做一期连接层确定量（SYN-RTT/TLS 握手/SNI-RST/DNS/字节量）再议二期下行节奏推断。item3 就此收口。** 设计详见设计文档 §11 | 设计文档 §11（新增，设计未实施）；ProviderPresets.kt excludedNote（封闭 App 测法空白 + "走 REACH 或 VPN 流量观测"指引）；R-33 / D-22（SNI-RST 同源、VPN 观测复用同一 ClientHello/握手观测面）；D-16 / R-03（VPN 用户态转发污染路径→互斥模式边界的直接依据）；§9.1（"只存本机"隐私锚）；§4 测量方法学（流级近似 vs 应用层精确的口径分界） |
| D-25 | 2026-07-14 | **SNI-RST 自动旁路：测量端点按 REACH 结果选路（修复真机蜂窝"未完成无结果"）**。真机 P40 电信 5G SA 实测暴露：app 默认端点是 E-01 sslip 主机名 `https://120-79-148-0.sslip.io:8443`，电信蜂窝路径 DPI 按 SNI 注入 TLS RST（R-33/D-22 同源）；run 前 REACH 探针虽已探明 `sni=rst / ip=ok`，但**测量仍死磕被 RST 的 SNI 路 → 每场景 Connection reset → 全 SCENARIO INVALID → AQS=null（用户所见"未完成无结果"）**。根因＝REACH 结果此前只用于记日志/落库，未回作用到测量选路。修复：新增纯函数 `ReachabilityProbe.preferredMeasureBase(base, reach)`——仅当"配置端点是 E-01 sslip 主机名 + SNI 被 RST + bare-IP 可达"时返回 bare-IP 等价基址，其余情形（未探测/非 E-01/SNI 本就通/已在 bare-IP）一律不变；TestEngine run 前算 `measureBase` 用于 profiles 拉取/场景测量/结果上报/落库，切换时打 `REACH_SWITCH` 日志。**同一节点、同一物理路径，仅换 SNI/证书绕过 DPI 的 SNI-keyed RST，claim_scope（application_end_to_end_to_probe_node）不变、无测量偏差**；SNI 本就可达时保留观测真实 sslip 路径（不掩盖 SNI-RST 研究信号，REACH 字段照记 sni 状态）。debug 版已内置 aneb_ip_ca 信任锚故 bare-IP 的 IP-SAN 证书可信（release 版另需配锚）。真机实证：默认 sslip URL → 全 Connection reset/AQS=null；手动 bare-IP URL → 场景 VALID_LOW_CONFIDENCE 真实 KPI——本修复即把该手动旁路**自动化**。7 项选路单测锚定；346 单测全绿。 | ReachabilityProbe.preferredMeasureBase + ReachabilityBaseSelectTest；TestEngine（measureBase 选路 + REACH_SWITCH）；R-33/D-22（SNI-RST 同源、bare-IP+IP-SAN 旁路）；deriveE01Pair（sni/ip 基址对） |
| D-26 | 2026-07-15 | **R-15 的 `nr_state` 停止读取 Android 私有 API**：删除 `ServiceState#getNrState` 反射与 `toString()` 内部格式解析，字段名和日志键保持不变，改为仅用公开三元组保守推导。只有 `dataNetworkType=NR` 且注册小区为 NR 才记 `connected`；一致 LTE 且 display override 明确回报 `none` 才记 `none`；图标单独声称 5G、来源冲突、回调尚未报告或 API 不可用一律记 `nsa_unknown`。不改变 KPI/AQS；R 组继续只作归因协变量。 | Product Owner 2026-07-15 批准推荐方案；R-15；targetSdk 35 `BlockedPrivateApi`；`PublicNrEvidenceTest` |
| D-27 | 2026-07-15 | **V1 主测量改由 `dataSync` 前台 Service 持有**：`ProbeRunService` 拥有 `TestEngine`、run 协程和持续通知；Activity 只订阅 session/logs/telemetry StateFlow。切后台或 Activity 配置重建不再取消 run，通知可取消，回到 App 可恢复测试中/结果/失败状态。ADB autorun 仍走同一引擎与原日志 KEY；KPI、AQS、场景顺序、节点选路和数据 schema 不变。Continuity/AB 专项暂保留原自动化入口，后续 C 阶段单独迁移，禁止假称已具备同等后台保证。 | Product Owner 已批准按推荐方案推进；V1_SCOPE background/resume；Android FGS dataSync |
| D-28 | 2026-07-15 | **APK 视觉以 `E:\G Project\ANEB\ANEB_UI` 的 `2026.07.15-2` 交付为唯一权威来源**：22 个交付文件与工程内 `design_handoff_aneb_probe` SHA-256 一致；采用原生 Compose 复刻深海军蓝/青色视觉、五栏导航、270° 仪表、地图壳、探针/历史/设置/分享结构，并重做自适应矢量 App 图标。HTML 中的演示数字、虚构城市热区和虚构 API 健康状态不得进入 APK；真实数据缺失时显示 `—` 或明确空状态。视觉一致性不能凌驾于 D-02/D-12 的证据真实性。 | Product Owner 要求 APK 严格按 `ANEB_UI` 更新并同时重设计 App 图标；UI manifest 与真机对照；禁止假数据 |
| D-29 | 2026-07-15 | **Token 模式的唯一实时主指标改为“AI 流式到达速率”**：SSE 读线程只在完整 event 边界做常数时间到达戳记录，独立遥测协程计算最近 1 秒 event/s，UI 每 250ms 用弹簧动画驱动 270° 指针、数字与曲线。该值明确标为仿真流式事件代理量，不等于有效模型 token 或计费 usage；EOF 后的 KPI/AQS/解析/落库口径不变。缺样本时不以 0 驱动指针，改显“建立速率窗口”。 | Product Owner 要求测试指标实时波动且达到 SpeedTest 级动态效果；现状根因是 token 累计值只在场景结束后更新；R-10/R-16 |
| D-30 | 2026-07-15 | **ANEB 采用“独立模式引擎 + 共用 Profile 外壳”扩展测试能力**：首批 `network_basic` 与 `token_experience`。Profile 必须声明 mode、业务说明、phase、指标清单、实时主指标、窗口/刷新频率和 `conclusion_policy_id`；结论公式由版本化客户端实现，不允许节点下发任意脚本。基本模式测下载/上传/RTT/抖动/应用层请求失败率，不与 AQS 混分；Token 模式保留现有三场景与 AQS。两者 claim scope 均限定到指定端点的应用层路径。详见 `TEST_MODE_PROFILE_DESIGN_2026-07-15.md`。 | Product Owner 要求支持与 SpeedTest 同项目的基本网络测速、Token 体验模式，并能通过新增 Profile 持续扩展；避免巨型统一引擎污染口径 |
| D-31 | 2026-07-15 | **基本测速结论采用版本化产品策略 `network-basic-conclusions-v1`**：结果页把测量事实与产品判断分开，并为 4K 视频、视频会议、大文件上传逐项给出“适合/受限/证据不足”。V1 条件：4K＝下载≥25 Mbps 且应用层请求失败率≤2%；视频会议＝上下行≥5 Mbps、RTT≤100 ms、抖动≤30 ms、应用层请求失败率≤2%；大文件上传＝上行≥10 Mbps。上述是 ANEB 产品阈值，不宣称为运营商 SLA/行业标准；缺指标一律“证据不足”，不得按 0 判差；调整阈值必须升 policy 版本。 | Product Owner 要求每个测试场景给出可行动结论；R-10 null 语义；避免无版本的展示层暗改判断 |
| D-32 | 2026-07-16 | **Profile 目录先做合同审计，不擅自改变 AQS 编排**：设置页新增 Profile Registry，优先展示当前节点下发的 Profile，拉取或解析失败时明确标注 APK 内置兜底；逐项展示业务、版本、实时主指标/窗口/刷新频率、输出指标、结论策略、phase 与当前引擎兼容状态。新增 `ProfileCapability` 对未知 mode/phase 和缺失展示合同标“需要引擎适配”，禁止静默宣称可执行。Token 模式仍按 S1/S2/S3 固定输入映射生成 AQS，基本测速仍独立运行；单 Profile 专项运行会改变 §5.3.6 编排与 AQS 完整性，未经单独裁定不实施。 | Product Owner 要求 Profile 可持续扩展；D-30；AGENTS 编排变更红线；本轮推荐方案先完成可审计目录 |
| D-33 | 2026-07-16 | **C 阶段生产化收口，不改变测量算法**：① Continuity/Protocol A/B 的协程所有权从 Activity 迁到独立 `dataSync` 前台服务，Runner、参数、日志 KEY、KPI/AQS 均不变，解决配置重建/切后台取消；② release 版本升为 `0.2.0`/20，Room v12 schema 导出入库；③ E-01 bare-IP IP-SAN CA 公钥进入 main Network Security Config，全局仍禁明文，补齐 D-25 在 release 的自动旁路；debug 继续仅对白名单联调地址允许明文；④ release assemble/bundle/install 增签名 fail-closed 门禁，密钥由 Product Owner 持有且不入库；⑤ Keystore 不可用时 API key 从“私有明文降级”改为禁止保存，并清除旧 fallback，非敏感 provider/base/model 另存私有配置。 | Product Owner 要求按推荐方案完成 A→B→C 与公开发布准备；D-25/D-27；V1_SCOPE background/resume、privacy-safe evidence、release signing readiness |
| D-34 | 2026-07-16 | **构建工具升级但 targetSdk 暂保持 35**：AGP 8.5.2 官方只验证到 API 34，故升级到 AGP 8.13.0 + Gradle 8.13（wrapper SHA-256 钉死），该组合官方支持 API 36.1；debug/release 双变体编译通过。`compileSdk/targetSdk` 仍为 35：截至 2026-07-16 Google Play 官方最低要求是 35，直接切 36 会启用 Android 16 定向行为，必须先做 Android 16 设备/模拟器专项回归，不能只为清除 `OldTargetApi` 警告盲升。Kotlin 2.0.21 的升级提示亦留到独立依赖批次，避免与本次产品/数据库/后台变更耦合。 | Android 官方 Play target API 要求、Android 16 setup、AGP 8.13 compatibility；供应链精确版本纪律；双变体构建证据 |
| D-35 | 2026-07-16 | **Profile 目录的节点读取复用 D-25 双通道选路，但不改变测量编排**：P40 真机验收发现测速主引擎可按 `sni=rst / ip=ok` 自动旁路到 E-01 bare-IP，而 Profile Registry 仍直接访问默认 SNI 地址，导致目录误显示“APK 内置兜底”。目录读取现先执行同一 REACH 双探针，仅在 D-25 的严格条件成立时用 bare-IP 读取节点合同，并记录 `PROFILE_CATALOG_ROUTE`；非 E-01、SNI 正常、bare-IP 不可达或未探测时均不切。该路径只决定配置展示来源，不改变 Profile 内容、测试阶段、AQS、结论策略或结果上报。真机复验显示“节点已核验 / 4 个 Profile”。 | P40 Pro 真机截图 `evidence/ui/aneb_codex_c_profiles_server.png`；日志 `PROFILE_CATALOG_ROUTE route=bare_ip reason=sni_rst_ip_ok`；D-25/D-32 |
| D-36 | 2026-07-16 | **ANEB App 的正式测试只执行自建节点可控仿真，第三方 AI 真实 API 不进入 Profile、主测量或评分；AI 真实业务行为建模拆为独立工程并行推进。** App 负责按冻结 Profile 生成/接收确定性的上传、处理阶段、Token/音频事件并测量网络影响；独立 `aneb-ai-behavior-model` 负责从获准观测数据或显式假设拟合业务行为、生成版本化模型/金轨迹/候选 Profile。两者以模型 id、版本、哈希连接。Kimi/DeepSeek/千问仅可作业务原型标签；未经校准必须标 `hypothesis`，不得声称厂商真实性能。现有真实 API 探针不参与新 Profile 或评分，其正式产品入口去留另行裁定。 | Product Owner 明确“ANEB APP 是模拟 Kimi 等 AI 应用行为，不是真实 API；另做模型和算法模拟真实业务行为，与 App 分开并行”；`PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md` |
| D-37 | 2026-07-16 | **批准 Profile Contract v2 的 provisional v1 质量目标、95% 达标口径、三套独立评分权重与门控作为实验性实施基线；批准真实 API 探针退出正式产品入口。** ①Token、AI 实时交互、网络综合性能各自独立出分，禁止与既有 AQS 或彼此混分；目标来源固定标 `aneb_product_provisional_v1`，后续改数值必须升 target/score policy 版本并追加决策；②必需指标缺失不重分权、分数为 null，invalid 保留原始证据但抑制聚合；③100MiB 视频使用独立 stress Profile；④正式 App 入口只展示自建节点仿真，现有真实 API 探针代码保留为开发诊断能力但不在正式导航/设置中出现、不进入 Profile 或评分。 | Product Owner 回复“按推荐方案”；D-36；`PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md` |

### D-38 — 私有 debug 通义当前界面资格观察（2026-10-13）

这是 Product Owner 已批准自主推进与外部界面测试范围内的工程操作决定，不是新测量、评分、证据合同或公开 claim 决定。

- 新增独立显式 `UI_REFERENCE_QUALIFICATION` 模式和 `UI_REFERENCE_QUALIFICATION_RESULT.json`；既有 `COLLECTION`、`PREPARATION` 路径与旧几何门禁不放宽。
- 只观察 `HOME → DRAWER → NEW_EMPTY → EDITOR_EMPTY → OWN_DRAFT → OWN_CLEARED`：通过当轮、同一自有会话的公开节点定位进入新空会话，写入固定测试草稿、完整读回，再仅清除本轮草稿；`send_calls=0`，不发送消息、不启动录屏/VPN/网络整形，不清除来源不明的会话或草稿。
- 每阶段保留原始 Native 节点、原始 XML、单次 PNG 及前后所有者/几何观察和文件校验值。不同 API 的先后观察不声明为原子同帧；保留失败与 UNKNOWN，不以退出码或截图代替原件闭合。
- 即使整条操作路径与自有清场闭合，结果仍为候选资格观察、`reference_accepted=false`，指标为 null、正式结果数为 0；不能直接解锁普通采集、网络归因或复用为已验收界面参考。
- 实施按整链行为 RED→最小 GREEN；固定整项候选后只运行一次完整质量门/CI/APK，再按 AGENTS 的在线、Launcher、后台冲突/VPN 检查和自有清场规则执行新 P40 资格观察。已消耗的 r5/r6 机会不重用、不回填。
- 本决定不改变冻结 workload、RPI、evidence schema、测量时钟、M6 矩阵、上传关闭、生产签名及既有发布，也不授权 E-01/Aliyun 改动。

### G0 binding decision packet — DN-1A through DN-5A (2026-08-28)

| Binding ID | Approved outcome | Source |
|---|---|---|
| DN-1A | Android `elapsedRealtimeNanos()` is the sole measurement clock; sample at the fixed dispatch/decode boundaries, retain raw nanoseconds, and reject boot/process/clock-domain splicing. | Product Owner binding receipt, Issue #14 comment `5449399284` |
| DN-2A | For an even number of successful runs, median is the arithmetic mean of the two middle sorted values. | Product Owner binding receipt, Issue #14 comment `5449399284` |
| DN-3A | Hash identities are bare lowercase 64-hex SHA-256 values; condition identity binds the profile-manifest hash with condition id/version and schedule hash. `nominal_interval_ms` remains an independently checked consistency field. | Product Owner binding receipt, Issue #14 comment `5449399284` |
| DN-4A | Null output has deterministic `primary_null_reason` plus ordered, deduplicated `all_null_reasons`; raw-event-to-report recomputation is the verifier authority. | Product Owner binding receipt, Issue #14 comment `5449399284` |
| DN-5A | VERSION/package binds exactly the four existing machine contracts; no `evidence-schema.json` artifact is invented. | Product Owner binding receipt, Issue #14 comment `5449399284` |

### D-39 — 负载下响应性独立纵切（2026-10-23）

PO 批准先查重、复用既有引擎及导出，固定候选通过质量门后一次 P40/本地节点验收。
历史 ANEB 已有合并 loaded RTT，本次不重复建设综合引擎。当前产品只新增显式隔离模式，
留存 idle/download/upload 各阶段 raw 与客户端观测传输重叠，旁列 P50/P95/增量。
旧 Basic、Token、Prototype、postLoadPingMs、AQS/RPI、门限和冻结合同均不改变。
详细固定口径和验收见 [LOADED_RESPONSIVENESS_V1.md](LOADED_RESPONSIVENESS_V1.md)。
缺失/失败保持 null，上传写入字节不冒充远端接收；不作网络因果或运营商结论。
不恢复千问、不追加云端/生产签名/上传权限。

### D-41 — 手机端业务Profile与条件体验基线主线（2026-10-24）

PO将下一阶段主线改为手机端Profile驱动的真人行为模拟与业务网络体验基线；完整回答耗时不再是全部能力前置。
先一个App一个场景，复用现有Android执行/证据/Reader/导出，不重建通用框架。
新视频方法候选为抖音一次上划后的30秒观察；新版本身份、内容规则、可观测端点、条件与重复先冻结再执行。
参考与少量单因素网络条件分离，未经全路径施加/手机验证不能称受控网络；自然波动仅条件关联。
v1旧指标、旧Completion、评分、旧数据/耗尽attempt、冻结正式合同均不改；未完成v2留原工作区，不混入新候选。
不把计划批准当成无限发送、账号/协议同意或网络安全设置更改。实施与验收见[首个视频纵切](business-baseline/PLAN.md)。

### D-42 — 视频业务操作与同钟截图观察（2026-10-24）

现有Debug MediaRecorder只提供MP4与操作状态，无逐帧PTS桥，禁止把start调用时间当PTS=0。
本新业务Profile选择同手机elapsedRealtimeNanos的公开截图观察作为独立新来源，记录调用区间/原图/序号/缺口；不改变原V0/VF或Completion。
新指标只描述首次被采集器观察到可辨播放的等待，含采样与观察器影响，不是播放器首帧、物理上屏或真实卡顿。独立复核前所有体验值仍null。
另一条同帧编码器PTS桥路线暂不前置，避免为首个业务纵切重建录制器；原片仍保存并可辅助内容/运动复核。
本方法固定500ms计划采样、最多64张、PNG总256MiB、操作后30秒，超限停止保留部分，不延长或补采。

### D-43 — 同片可见切换等待与已知页面准入（2026-10-24）

修正D-42的依赖范围：缺elapsed↔PTS桥只阻止跨时钟输入→画面耗时，不阻止同一SHA原片内两个可见事件的真实PTS区间差。
新方法`visible_switch_wait`单独说明为“记录中可见切换等待”：feed切换开始A→新目标可辨内在动态B，保留边界帧/真实PTS/判据；不得混同物理输入、播放器内部首帧或网络耗时，不回填原raw或旧V0/VF/Completion。
独立复核先检查原片/时间轴、内容身份与内在运动；原件/采集链通过与指标可计算分开，缺源继续NA，原30秒操作窗不变。
已取零手势来源只证明当前推荐页/容器；首资格使用已知正向锚点、精确viewport与显式非穷尽blocker覆盖，不为凑表虚构异常selector。未知页面停止；正向匹配不证明无广告、账号正常或正在播放。
资格条件标`QUAL_REFERENCE`，不混入R/B六次。将来R/B保持相同网关拓扑，仅改变单一已校准约束，并检查蜂窝/多链路旁路。

### D-44 — 业务视频输入前拒绝的原件关联诊断（2026-10-24）

唯一参考机会`video-qual-20261024-5e7829c`已失败并消耗：最后公开UI缺推荐锚点，原件仍为OPERATION_UNKNOWN，不能回填或重开。
本次仅离线修正诊断：Native的既有选择器分类移入可回放纯函数，同包/祖先可见性过滤仍由Native执行，未知页面、viewport、App/网络和一次机会守卫不放宽。
Native在ACTION_DOWN前的专门拒绝可报告PRE_INPUT_REJECTED；必须保留确切UI引用和SHA，才声明此适配器零次注入。任意普通/输入后异常、原件不可关联仍为UNKNOWN，不等于设备全局无输入证明。
`business-video-raw-1`仅兼容增加可空preInputRejection诊断；旧原件不重写，原始时延null/stalls not_computed、30秒窗、360/210预算及冻结正式schema/指标/评分不变。
新的UI原件逐行记录实际classification_eligible和值，分类器使用同一组采集值；这些依然是先后公开观察，不宣称原子页面快照。旧ui2/ui3回放缺每节点包/祖先来源，只证明选择器与执行拒绝兼容，不重新证明Android可见性过滤。
此修正不开放实机重试、R/B条件对照或网络设置变更；稳定页面准入仍须单独解决，不能只把失败改成更好看的分类。

### D-45 — 显式版本化的公开定点UI读取候选（2026-10-24）

沿PO已授权的单App/单场景主线解决页面读取可靠性，不把D-44诊断通过当业务通过，也不重开5e旧机会。
新观察策略`targeted-public-selectors-1`必须同时由执行参数`ui_observation_method`与固定UI binding声明；缺省仍是旧`legacy-public-tree-1`策略，二者不一致即拒绝。方法声明只表示实际选择的观察策略，不证明来源或指标有效。
查询词“推荐”仅从已观察的严格description派生，用Android公开文本查询召回，再用原有逐字description/唯一性/同包/当前根与祖先可见性/精确geometry分类；不将query_text改为最终text条件，不采用更弱的FEED_CONTINUATION，不在新策略失败后回退全树或补查。
Native逐次记录实际查询、手机elapsed请求/返回或异常、返回数、节点值/根关联和最终拒绝；每次最多32个固定查询、最多2048个不同返回节点，本绑定实际仅2查询。原截图/30秒观察窗和360/210预算不变。查询时长不是App/网络耗时，返回数不是召回完整性证明。
后续查询/原件保留失败或CANCEL仍保存已有有界候选，来源分类保持UNKNOWN且传播原错误/取消，运行取消仍为CANCELLED；不因部分命中放行。输入前再核录制健康、准备截止与当前App/窗口，不能对变化的前台注入。
新raw/binding仅增加可空uiObservationMethod声明；旧数据、旧NA/V0/VF/Completion、指标/评分、冻结正式schema与原时钟不改。实机provider兼容性尚未证明；固定候选完整门/CI/APK后，须另定明确方法资格卡，不因本条自动授权重试或R/B对照。

### D-46 — 原始业务视频与独立复核副本分离交接（2026-10-25）

沿PO已批准的Profile纵切复用已有Reader/报告/证据，不把结果交接组件替代一个有效真实场景或受控网络基线。1d03公开定点方法已实机进入一次输入，但其唯一资格在同包新窗口/空查询后停止，账号相关模态另有媒体证据；两项既定V2数值仍NA，原始ownedCleanup=false与稍后Root全局清场分开保留。一次独立前置准备也未取得可共同确认的提示，未点击/滑动或新增研究样本，不重开旧机会。

仅新增私有导入sidecar `business-video-review-1`，复用ResearchAnalysisStore、原始业务pane与现有视频ZIP/Downloads流程；不改旧正式evidence schema、workload、RPI、评分、V0/VF/Completion或原始采集字段。
复核必须绑定原文SHA、完整原文和唯一run；类型由业务校验成功分支显式赋值，不由旧分析中的未知format字段推断。原始STOPPED/清理未确认不能被声明为QUALIFIED；导入声明本身依然不是Android媒体、时钟或真实性认证。
两项新方法区间或NA、原因/限制及来源文本单独保存/重开/展示，不计算端点或达标结论、不改rawnull。引用不读取/打开；ZIP只含原文/复核原字节、可读首页与校验清单，不附媒体、不上传或自动聚合。
此变更不开放业务采集/重试、账号登录或新协议处理、网络规则/驱动操作；产品Reader QA与研究资格分开授权/记录。使用说明见business-baseline/REVIEWED_RESULTS.md。

### D-47 — 单手机转发条件的有界Windows工程候选（2026-10-25）

沿PO已批准的手机业务Profile/单因素条件基线，补覆盖第三方App流量的控制点；不是新KPI、评分、旧测量协议或已施加网络条件的证明。
复用现有手机执行/采集/Reader；Linux节点netem和本机进程QoS不冒充手机转发控制。新独立host工具仅处理一个手机地址集合、一个实际确认的NETWORK_FORWARD接口和有限寿命；不配置热点、路由、防火墙、VPN或安全策略。转发内存暂存原包，不解析或落盘payload。
工程边界为最多16个同一手机的host地址、0<TTL≤120秒、0<下行IP字节速率≤10^9B/s；这不是B条件选择、业务体验门限或网络能力事实。参考/B仍须同拓扑、完整IPv4/IPv6/蜂窝旁路准入、真实施加/恢复和手机校准后另行冻结。
SNIFF资格必须复制而非阻断且永不Send副本。限速保留原始包与完整80字节地址，接收与节流分离、有界队列；溢出、时钟故障、截断、注入失败等中止条件，不隐藏丢包或扩大过滤范围。主机注入成功不是手机收到，driver丢弃计数与手机收包缺证仍null。
寿命在驱动Open前由独立本地监督器约束；正常/失败退出先停止新增接管，再限时处理已接管包并关闭自有句柄。确定尚未派发的发送可在清理阶段恢复，已派发而结果未知不重发。不能把未join/未排空洗成清理成功；不泛化停止/卸载未知driver或服务。
固定源码/二进制/依赖/配置、完整质量门及实际系统批准先于现场调用；本决定不自动放行driver/热点/手机操作或研究quota。旧5e/1d03/prep失败、NA、frozen workload/RPI/schema/时钟、上传关闭、生产签名和E01/Aliyun边界均不变。当前实现/准入状态见tools/phone-forward-condition/README.md。

普通命令只开放plan及非阻断inspect候选，不开放limit执行。inspect仅在已批准的升权console内启动自有匿名Job监督的worker；不自动升权、不代点系统确认。正常TTL与父取消/错误/未join分开，正常截止无包只报NO_MATCHING_PACKETS，未定发送不重发。独立监督的预算为请求TTL加固定5秒启动/清理余量、总上限120秒，故inspect请求TTL最多115秒；不是延长业务研究窗口。Job创建→挂起进程Assign之前若父退出，仍可能留未执行的挂起进程；Assign之后才有kill-on-close保证，父冻结/同步OS API卡住不承诺硬墙钟截止。完整原始失败和未知项必须保留。

### D-48 — 零手势前置来源不申请录屏、与测量准入分离（2026-10-25）

修正既有Native SOURCE_PREPARATION仅因UI绑定存在就触发Projection授权的边界；source-only始终不申请录屏，REFERENCE_QUALIFICATION仍在准备内取得原系统授权，原20秒ready/30秒观察/360准备/210清理预算不变。
沿PO已批准的单App场景纵切复用原prepare、PID/UID/start身份、自有Shell、公开查询/截图/cleanup；不是新采集框架、账户登录或业务手势。新增显式source_observation_method=friends-invite-public-source-1，只能用于SOURCE_PREPARATION/max_gestures=0且不能与feed UI绑定/方法混用。
本方法固定查询已见邀请原片中的“发现抖音朋友”“登录后”“拒绝”“继续”，仅作同App/同窗口可见公开节点召回；保留逐查询elapsed/返回数/异常/部分来源和一张原截图，不使用uiautomator idle，不fallback/重查，不操作这些按钮。它不进入feed分类、不产生业务样本/录制/指标；空返回不证明没有弹窗，命中不证明必须登录或账号健康。
原feed准入/targeted方法、严格选择器、旧资格/前置机会及失败/NA均不变，不借新名称恢复已停止的全树idle准备路线。固定候选完整门/CI/APK及新鲜AGENTS前置之后，才可另立该新来源路径的一次有限准备卡；本代码决定自身不释放实机或研究quota。同步公开provider调用仍不能承诺可中断硬截止，失败须保留并停止，不加预算。
准入卡反审核出85990cd在同步查询迟到返回后仍可继续查询/截图，故该卡从未放行。修正沿用同一preparationEnd：新source路径在查询前后和截图前后检查，迟到结果先保留再抛错，禁止下一项UI工作并进入原210秒清理。截止后保留返回节点的已有属性，但不新发祖先查询；可见性未评估写null而非false。source旋转在查询前取得，原截止写入独立诊断；REFERENCE旧测量时钟和数据不替换，不以加预算或重试换通过。

### D-49 — 复用单一监督器的显式Limit候选接线（2026-10-25）

沿PO已批准的单App业务Profile/单因素网络条件主线，补D-47刻意未开放的命令接线；
复用既有pacer、收包队列、原包恢复和匿名Job，不重建通用流量框架或再套监管层。
新增显式limit与_worker-limit候选；inspect命令只接收inspect请求，limit只接收limit，
private worker还须同一模式及原始请求SHA。两者沿用原TTL≤115秒、固定5秒余量和
总监督120秒上限，先获批准的升权console前置不变，不自动提权、不绕安全策略。
Run真实核心通过可替换的OS授权/时钟/native-link系统边界离线验证；不mock内部算法，
不加载有效驱动、不配置热点/共享、不开P40业务App，不增加研究或发送额度。
原始失败、create-once输出及未确认清理保留；结果仍NO_MATCHING_PACKETS/
NO_PACED_PACKETS/INJECTED_NOT_CALIBRATED，不升级手机收到、已校准或业务达标。
固定候选完整门/CI/二进制与配置、可恢复实际转发路径、系统批准及手机校准仍先于任何
现场Limit；旧工作负载/RPI/schema/评分/时钟、失败/NA、生产签名、上传关闭和云端边界不变。
本条只替代D-47中“命令尚未接入limit”的历史实现状态，不替代其安全/事实准入。

## 否决记录（评估后明确不采纳）

- **Cronet 逐请求 bindToNetwork**：OkHttp 主栈无此 API；改用 `requestNetwork` + `socketFactory`/`Dns` 双绑定，保留其 fail-closed 语义（绑定不可得即不出数）。
- **M2 式里程碑制与正式 PO 角色**：研究者本人即决策者，轻量四态证据 + 阶段验收足够；对方被外部依赖全线卡死（M2=NO）正是过度制度化+外部前置的后果。
- **功能范围冻结**：本项目冻结的是 profile 与 KPI/AQS 的版本语义，不是功能范围——流式/上行恰是主战场。
- **单指标名 const 锁死**：本体系多 KPI 且版本化演进；schema 锁 claim_scope 与版本字段，不锁指标名清单，否则与"门限随数据回流重标定"（D-08 配套）冲突。
- **最小权限清单照搬**：R 组无线层归因必须采集小区/信号，申请 `ACCESS_FINE_LOCATION`+`READ_PHONE_STATE`，差异与数据保护承诺显式声明（设计文档 §9.1）。
- **invalid 全抑制到数据层**：抑制只作用于 KPI/AQS 聚合层；原始事件全量保留（取证需要分析失效原因）。
- **设备矩阵/双节点实验室校准作为验收前置**：留待阶段三按需借用其判据框架（同条件 median CV≤8%、同 CDN 边缘不算独立节点），不前移为阶段 0–2 门槛。
- **对方 Echo RTT 数据直接当 N1 基线**：Cronet vs OkHttp 栈间系统差未标定前，两项目数据不可直接互比；互校须同设备同网络先标定栈差。

## 外部依赖清单

原则：每项外部依赖必须有本地替代方案，保证**每个阶段都有纯本地可完成的验收路径**；依赖缺位时对应检查记 `BLOCKED_EXTERNAL`，绝不折算成 PASS。

| ID | 依赖 | 最晚需要时点 | 本地替代方案 |
|---|---|---|---|
| E-01 | 国内云 VM（2C4G、公网 IP、8443/TCP，阶段二 +UDP） | 阶段 0 后半（真机联调） | 局域网 Linux 盒 / WSL2 + tc netem |
| E-02 | Android 真机 + 蜂窝 SIM（4G/5G） | 阶段 1 蜂窝验收 | 模拟器只能产出功能/fail-closed 兼容性证据，不构成测量证据（参考项目教训：模拟器无 NET_CAPABILITY_VALIDATED） |
| E-03 | 真实 LLM API key（Anthropic / Kimi） | 阶段 2 探针 | 跳过探针对照列，主线不受阻 |
| E-04 | 海外节点 | 阶段 3 | netem 模拟跨境 RTT/丢包剖面 |
| E-05 | CAMARA QoD 试点（运营商合作） | 阶段 3 | 无替代；记 BLOCKED_EXTERNAL |
| E-06 | 域名 + 公共 CA 证书（Let's Encrypt，绑定 E-01） | 阶段 2 云端 QUIC A/B（P2-C06） | 无替代（D-21：Cronet QUIC 强制公共已知根，自签不可行）；同批次需 UDP 8443 放行 |
