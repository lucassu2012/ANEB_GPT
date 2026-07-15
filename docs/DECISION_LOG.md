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
