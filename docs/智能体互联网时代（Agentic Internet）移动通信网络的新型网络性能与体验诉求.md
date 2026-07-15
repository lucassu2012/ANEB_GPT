# 智能体互联网时代（Agentic Internet）移动通信网络的新型网络性能与体验诉求

## TL;DR

- **核心结论（反直觉判断成立）**：编码类/多轮对话类移动端智能体（Claude Code、Kimi K2.7 Code/Kimi Claw、Cursor 等）对移动网络的本质诉求，**不是更大峰值带宽，而是"有界时延 + 会话持续性 + 小包可靠性 + 上下行对称化"**。实测数据支撑这一判断：单 token 数据包仅 50–80 字节（Bard）到 842 字节/3 tokens（ChatGPT）；芝加哥大学 Eloquent 研究（SIGCOMM NAIC'24，arXiv:2401.12961）实测"文本数据远小于音频与视频，网络最低带宽需求低得多……即使网络不稳定平均带宽通常仍保持在 100Kbps 以上"（对比音频 150Kbps、视频 >1Mbps）。瓶颈在于流式 token 对抖动、丢包、连接中断的高敏感度，而非吞吐量。
- **流量画像本质区别**：智能体流量是"高频、小包、长会话、强突发、上下行严重不对称"的自回归流。清华大学邓欣豪等的 AGENTPRINT 研究（arXiv:2510.07176）证明 Agent 交互在加密流量中留下可识别的"突发-空闲"指纹（闭集 50 个受监控 GPT 下，行为识别宏 F1 0.924、身份识别宏 F1 0.866）；WiLLM 5G 实测显示 LLM token 流呈现"极端突发性（extreme burstiness）"，与周期性资源调度严重错配。这与视频流的"大带宽、可预测、可缓冲"画像完全相反——视频靠 buffer 抵抗抖动，而智能体交互式 token 流无法缓冲。
- **网络反哺智能体是关键新方向**：CAMARA/GSMA Open Gateway 的 Quality on Demand（QoD）、边缘发现等能力，叠加算力网络、确定性网络、MEC 下沉，构成"Network-for-Agent"能力体系。3GPP（R20 SA1 已研究 agent 服务、SA2 6G 已立 AI/agent 工作任务）、ETSI ENI（GR ENI 055/056）、IETF（自 IETF#123 起研究 AI 协议）均已启动标准化，但现网架构在有界时延、会话保持、突发调度上仍存在明显 gap。

## Key Findings

1. **智能体流量的定义性特征是"processuality（过程性）"与"multimodality（多模态性）"**，而非数据量。清华大学与蚂蚁集团 2025 年 10 月论文《Exposing LLM User Privacy via Traffic Fingerprint Analysis》（邓欣豪等，arXiv:2510.07176）指出，Agent 交互由"多步工作流编排 + 外部工具调用"构成，在加密流量中留下 packet size 与 timing 的稳定指纹；其 AGENTPRINT 系统在闭集（50 个受监控 GPT）下达到行为识别宏 F1 0.924、身份识别宏 F1 0.866。

2. **编码智能体的 token 消耗与请求节奏远超传统 chat**：Claude Code 单次"编辑文件"命令一旦装配完整上下文窗口，可消耗 5 万–15 万 tokens；一个复杂任务可链式触发 8–15 次内部模型调用、数十次工具调用；会话历史随轮次累积，第 15 条命令可能携带 20 万+ 输入 tokens。这形成了典型的"上行大 prompt 突发 + 下行流式 token 涓流"的强不对称模式。

3. **首 token 时延（TTFT）与 token 间时延（ITL）取代吞吐时延成为体验核心指标**：交互式应用中"用户感受到的等待"就是 TTFT；流式输出下，200ms 以上的 token 间间隔即被定义为"卡顿（stall）"。实测表明网络不稳定时（丢包率 10.5%–15.8%），GPT-3.5 的 95 分位 token 渲染卡顿从 377ms 恶化到 3483ms——瓶颈完全来自网络而非模型。

4. **小包丢失在 Agent 场景被放大**：HALO 研究（arXiv:2601.11676，8 节点树莓派 dllama+TinyLlama 张量并行）实测："对 TCP，丢包率（PLR）每增加 1%，TPOT 可增加约 617.2ms；当 PLR=1% 时，其开销占无丢包 TPOT 的近 70%"。这是因为自回归生成要求"每个 token 尽快送达"，TCP 的队头阻塞使单包丢失阻塞整个 token 流。

5. **长会话在移动场景面临连接持续性危机**：Agent 会话是 long-lived sessions，但移动网络的切换（handover）、IP 变化、NAT 超时（NAT 映射静默失效导致连接挂起而无 RST）、无线信道波动都会中断连接。QUIC 的连接迁移（connection migration）与 0-RTT 恢复是关键缓解手段，但需要后端实现 SSE 断点续传。

6. **信令与 RRC 状态迁移是被忽视的成本**：Agent 的"思考-输出-工具调用"间歇性 pattern 触发频繁的 idle↔connected 态迁移，每次迁移带来信令开销与时延；维持 connected 态又快速耗电。据 ResearchGate 移动性管理综述引述的性能分析，5G 的 RRC_INACTIVE 态相较 LTE Idle 态在信令开销、时延、功耗三方面分别具有约 71%、88%、79% 的优势，但仍未针对 Agent 的突发-长尾模式优化。

7. **标准化已全面启动但存在 gap**：3GPP R20 SA1 已研究 agent 服务、SA2 6G SID 已纳入 AI/agent 工作任务；ETSI ENI 发布 GR ENI 051/055/056 定义 CN-Agent 与多智能体框架；IETF 出现 draft-hw-ai-agent-6g、draft-rosenberg-ai-protocols 等草案；MCP/A2A 在 2025 年 12 月归入 Linux 基金会 Agentic AI Foundation。但现有草案多为定性需求语言，缺乏针对 Agent 流量的量化 KPI。

## Details

### 第一部分：智能体流量画像（Traffic Profiling）

**交互模式：Agent Loop 的请求-响应节奏。** 以 Claude Code 为例，其 agent loop 生命周期为：SystemMessage(init) → Claude 评估 prompt → 产出含 tool-use 的 AssistantMessage → SDK 执行工具 → 工具结果回灌 → 循环直至产出无工具调用的最终回答。每一"轮（turn）"是一次往返，一个复杂任务（如"重构 auth 模块并更新测试"）可链式跨越数十次工具调用。只读工具（Read/Glob/Grep）可在单轮内并行执行，SDK 合并结果后再进入下一推理步——这意味着**单个 Agent 会话会触发多个并发连接与 API 调用**。

**上下文压缩与会话恢复。** 当上下文接近窗口上限（多数模型 200K tokens），SDK 自动 compaction（摘要化旧历史）。会话通过 session_id 可 resume/fork，恢复时"之前读过的文件、执行的分析、采取的动作"全部重建——这对网络意味着**会话状态的持续性与可恢复性诉求极高**。

**上下行严重不对称。** 这是 Agent 流量区别于视频最本质的画像特征之一。香港科技大学等的 WiLLM 5G 实测框架（arXiv:2506.19030，基于 OpenAirInterface，1,649,996 条记录）量化了双向负载不对称：
- 上行密集场景（image-to-text）：LLM 计算推理主导端到端时延，占 74%–87%，网络传输贡献极小；
- 下行密集场景（text-to-image）：网络传输成为主要瓶颈，占 81%–86% 的总时延。

对编码类文本 Agent 而言，典型模式是**上行一次性大 prompt（代码库、上下文、工具 schema）突发上传，下行流式 token 涓流下发**。这与视频的"下行大带宽为主、上行极小"截然不同，也与网页浏览、IM 不同。

**包大小分布：小包为主。** 芝加哥大学 Eloquent 研究（SIGCOMM NAIC'24，arXiv:2401.12961）实测：单 token 数据包对 Bard 仅 50–80 字节，ChatGPT 为 842 字节承载 3 个 token。"在 token streaming 中，最小传输单元是单个 token，远小于单包最大尺寸。"文本流的最低带宽需求 >100 Kbps 即可（对比音频 150 Kbps、视频 >1 Mbps）。但**多模态输入（截图转代码、上传文档/图像）会引入大 payload 突发**——Kimi K2.5/K2.6 主打"视觉 agentic"，支持截图转代码，这类上行会周期性出现大包。

**时间分布：强突发性 + 长尾会话 + 思考-输出间歇。** WiLLM 明确指出："与可预测的 DNN 流量相反，LLM 的 token 流展现出前所未有的突发性与状态依赖性"；"token 生成呈现极端突发性，因为模型在快速 token 传输与注意力计算的计算暂停之间交替"；"这种随机生成过程创造的传输突发与周期性资源调度严重错配。"从工程侧看，API 网关厂商 Zuplo 观察到："一个自主 Agent 完成单个任务可能在数秒内链式发起 10–50 次 API 调用……然后空闲数分钟"，其流量形状"高频、突发、自动化，看起来极像 DDoS 或爬虫"。

**流量画像对比总表：**

| 维度 | 视频/短视频 | 网页浏览 | IM | 云游戏 | **智能体（编码/多轮）** |
|---|---|---|---|---|---|
| 带宽需求 | 高（>1Mbps） | 中，突发 | 低 | 高且稳定 | **低（>100Kbps 足够）** |
| 时延敏感度 | 低（可缓冲） | 中 | 中 | 极高 | **极高（TTFT/ITL）** |
| 抖动敏感度 | 低（buffer 吸收） | 低 | 中 | 高 | **极高（无法缓冲）** |
| 包大小 | 大包为主 | 混合 | 小包 | 中小包 | **小包为主 + 周期大包** |
| 会话时长 | 中 | 短 | 长（但空闲） | 中 | **长（活跃、有状态）** |
| 上下行 | 下行为主 | 下行为主 | 对称 | 下行为主 | **上行突发 + 下行涓流，强不对称** |
| 连接持续性诉求 | 中 | 低 | 中 | 高 | **极高（状态恢复）** |

### 第二部分：对网络性能的新型诉求（逐维度分析）

**1. 时延（Latency）：确定性/有界时延 >> 平均时延。** 必须区分四层：吞吐时延、首 token 时延（TTFT）、token 间时延（ITL/TPOT）、往返时延（RTT）。为什么 Agent 场景下不同？流式输出把体验拆成两段：TTFT 决定"何时开始看到输出"，ITL 决定"输出流是否顺滑"。用户对"平均快、偶尔巨卡"的容忍度极低——一次 3.5 秒的 token 卡顿足以摧毁交互体验。因此**有界时延（bounded latency）的价值远高于更低的平均时延**。IETF 个人草案 draft-hw-ai-agent-6g（华为/中国电信/中国联通）§4.4 明确要求"QoS（如时延与时效性）应针对整个突发数据集保障，而非单个数据包"。

**2. 抖动（Jitter）：流式输出的致命弱点。** Eloquent 将 token 间间隔 >200ms 定义为 stall（借鉴视频流理论）。但与视频不同，交互式 token 流**无法用 buffer 吸收抖动**——因为 token 是自回归实时生成的，"每个 token 必须尽快送达用户"。实测在移动 LTE 步行场景（MacBook M2 网络共享 iPhone 11、Mint LTE，丢包 10.5%–15.8%）下，GPT-3.5 的 95 分位卡顿从 377ms 恶化到 3483ms，GPT-4 从 1501.4ms 到 2924.4ms——即使平均带宽仍保持在 100Kbps 以上。这直接证明：**Agent 体验瓶颈是抖动与丢包，不是带宽。**

**3. 连接持续性与会话保持（Session Continuity）。** 这是移动 Agent 最尖锐的新诉求。IETF draft-hw-ai-agent-6g §3.3 "Agent Service Continuity" 原文要求："在 UE-agent 的移动性场景或网络地址变化时，agent 服务（如消息或任务处理结果）的连续性应得到保障……应减少链路（重）建立的额外信令开销与连接中断的可能性"，并特别提出"任务执行中途承载 Agent 的设备关机，Agent 需在关机前迁移到另一宿主设备，如何处理与通知"这一未解问题。现网痛点包括：移动切换导致 IP 变化、NAT 映射超时后连接静默挂起（无 TCP RST，应用层需等待超时）、无线信道波动。缓解手段：QUIC 的连接迁移使"手机从 Wi-Fi 切到蜂窝时同一连接得以存续"，0-RTT 恢复消除重连延迟；SSE 规范支持断点续传但需后端将部分输出存于中间存储（如 Redis）而非单实例内存，以便重连客户端落到任意后端仍能续传。

**4. 可靠性与丢包（Reliability / Packet Loss）：小包丢失的放大效应。** HALO 实测（arXiv:2601.11676，8 树莓派张量并行 dllama+TinyLlama）："对 TCP，丢包率每增 1%，TPOT 增加约 617.2ms；1% 丢包时开销占无丢包 TPOT 近 70%"。根因是 TCP 队头阻塞：一个承载 token 的小包丢失会阻塞后续所有 token，即使它们已到达。金融行情流、遥测流有相同问题——业界方案是转向 QUIC（每流独立可靠性，一条流丢包不阻塞其他流）或带应用层序号的 UDP。

**5. 上下行对称性诉求（Uplink/Downlink Symmetry）。** 传统移动网络为"下行为主"优化（视频、网页），上行资源配置保守。但 Agent 的大 prompt 上传（代码库、多模态输入、工具结果回灌）使上行成为常态突发。当前 5G 上行受限（某 WISV 实测：20 米距离上行均值 172 Mbps、2 米 498 Mbps，随信道质量剧烈波动）。**Agent 时代要求重新平衡上下行资源分配，尤其是上行的突发调度能力。**

**6. 突发流量处理与拥塞控制（Burst / Congestion Control）。** IETF draft-hw-ai-agent-6g §3.2.3 "Burst Agent traffic" 原文要求："agent 服务流可以是突发流量：当为突发而非周期流量时，应实时调整网络资源以保障动态 QoS"，并区分"SSE/流式模式的资源应始终保持激活，而 Push Notification 模式的资源可灵活（去）激活"。TCP 的 CUBIC 拥塞控制是基于丢包的、慢启动的，对突发小包不友好；QUIC 将拥塞控制移入用户态、支持更细粒度调整、避免剧烈退窗。但需注意 QUIC 对乱序包敏感（会误判为丢包），在高乱序移动链路上未必总优于 TCP。

**7. 并发连接与信令开销。** 一个 Agent 会话可触发多个并发工具调用、多个 API 调用（读文件、跑命令、web fetch 并行）。MCP 偏好 JSON-RPC over SSE 传输，需要"持久且可验证的端点使 agent 不会中途'失去双手'"。机器速度的 Agent 会以网络层速度连发请求，失败即重试——这对连接建立开销、信令面（尤其无线接入信令）形成新压力。

**8. 能耗与移动性（RRC 状态迁移）。** Agent 的间歇性突发（思考时静默、输出时爆发、工具调用时再爆发）对终端 RRC 状态机是最坏情况之一：频繁 idle↔connected 迁移带来信令负载与时延，而为避免迁移延迟长期驻留 connected 态则快速耗电（需持续监测 PDCCH、周期发送 CQI）。5G 的 RRC_INACTIVE 态保存 UE 上下文，恢复时所需核心网信令更少；据移动性管理综述引述的性能分析，其相较 LTE Idle 态在信令开销、时延、功耗上分别有约 71%、88%、79% 的优势。但现有 RRC 计时器与 DRX 参数并未针对 Agent 的"长尾会话 + 突发"模式联合优化。

### 第三部分：网络反哺智能体（Network-for-Agent）

**CAMARA / GSMA Open Gateway 网络能力开放。** 据 GSMA 网络主管 Henry Calvert 于 MWC26 Barcelona 官方博文《From Ambition to Execution》，Open Gateway 已有"86 个运营商集团，代表 300+ 网络与全球 80% 移动连接"对齐，"20 个不同 CAMARA API 在全球 65 个市场（从加拿大到智利）实现 300+ 次商用部署"。对 Agent 最相关的能力：
- **Quality on Demand（QoD）**：允许应用按需请求稳定时延或保证吞吐的会话级 QoS。这正是 Agent"确定性时延保障"诉求的直接对接点——Agent 可在关键任务段申请 QOS_L（低时延）profile。QoD API 支持 4G/5G，通过 POST /sessions 携带 IP 与 QoS profile 发起。
- **Simple Edge Discovery / Edge Cloud**：帮助 Agent 发现最近的边缘节点，支撑边缘 Agent 部署。
- **SIM Swap / Number Verification**：可服务于 Agent 身份认证与防欺诈。GSMA 已展望"agentic AI 把静态 API 变成动态自优化积木"，例如"QoD 驱动的工作流根据网络状况自调整，仅在需要时请求增强容量"。

**算网融合 / 算力网络（Computing Power Network）。** 中国移动《算力感知网络技术白皮书》（2019）及后续研究提出端-边-云三级算力结构下的算网一体调度。核心价值是为端-边-云协同的 Agent 提供算力与网络的联合优化：计算卸载（computation offloading）将本地任务迁移到更合适的算力节点。对移动 Agent，这意味着可根据网络状况与算力分布动态决定推理在端/边/云何处执行。

**确定性网络（Deterministic Networking）：适用性与局限。** 5G URLLC（3GPP R16/R17 定义）目标是 1ms 单向 RAN 时延、99.999% 可靠性，技术手段包括 Massive MIMO 空间分集、特权流优先接入、资源预留、边缘计算。网络切片可为不同服务隔离资源。**但对 Agent 的适用性有限**：URLLC 为"小数据、超低时延、确定性周期流量"设计（工业控制、AGV），而 Agent 流量是"突发、非周期、长会话、上下行不对称"的——URLLC 的保守传输模式（牺牲频谱效率换可靠性）与 Agent 的突发大 prompt 不匹配。真正需要的是"有界时延 + 突发容忍"的新型服务类别，介于 eMBB 与 URLLC 之间。

**边缘计算（MEC）：LLM 推理下沉。** 将 LLM 推理/Agent 执行下沉到边缘（基站、本地边缘服务器）可缩短网络路径、降低端到端时延、减少骨干回传。相较云端推理，边缘服务减少对广域网依赖；相较纯端侧，边缘提供更大算力/内存 headroom，支撑更长上下文与更高并发。研究显示端-边协同（端侧小模型生成推测 token、边缘大模型并行验证）等方案可降低时延。但单个 MEC 服务器算力有限，无法承载完整大模型，需模型并行切分跨多 MEC，引入节点间通信开销。

**网络可编程性 / 意图驱动网络（Intent-based Networking）。** 这是最具前瞻性的方向：Agent 主动向网络请求特定 QoS。IETF draft-tong-network-agent-use-cases-in-6g 描述了 6G 网络 AI Agent 三层架构（中央智能层含 Task Orchestration Agent 与 Capability Exposure Agent、服务 Agent 层含 QoS Assurance Agent 与 Connectivity Agent）。设想 Agent 通过 A2A 协议（基于 JSON-RPC 2.0 的 message/send、task/get 等方法）向网络申请一段确定性低时延连接，网络进行意图识别、任务分解、资源配置。ETSI GR ENI 051 也研究了 LLM 赋能的 Agent 作为人类运营商定义高层意图的接口。

**端侧-网络协同的 Agent 身份、安全与信任。** A2A v1.0.0（2026 年 1 月）引入签名 Agent Card（密码学验证）、编纂发现与信任机制；MCP 的安全架构（OAuth 2.1 + PKCE、动态客户端注册、Resource Indicators）成为 A2A 的显式模型。ETSI GR ENI 055 要求"移动网络提供机制唯一标识代表用户行事的 AI Agent"。运营商的 SIM/网络身份可成为 Agent 身份的信任锚点。

### 第四部分：标准化与产业进展

**3GPP。** R18 首次研究 AI/ML for NR air interface（CSI 反馈、波束管理、定位）；R19 引入 one-sided 模型的信令与 LCM、AI/ML 移动性优化（RRM 测量预测、切换失败预测）；R20 推进两侧模型 CSI 压缩、AI/ML 辅助移动性规范。**关键**：据 3GPP Highlights（2026 年 6 月）第 12 期，R20 SA1 已研究 agent 服务，SA2 6G SID 已纳入 AI 与 agent 工作任务（work task #3），SA WG2 主导 AI 相关 6G 架构研究，stage 2 研究自 2025 年 6 月启动，对应规范化 stage 1 工作已于 2026 年 3 月在 R21 启动。3GPP 区分"AI for Networks"与"Network for AI"两范式。

**ITU / 6G 愿景。** 6G 被定位为 AI-native network，ITU 将 5G 三场景（eMBB/URLLC/mMTC）扩展为 6G 六类场景。6G 标准化预计 2025-2027 研究期、R21 产出首套 6G 规范、2030 商用。

**IETF。** 自 IETF#123（2025 年 7 月）起研究 AI 协议，#124/#125 持续讨论。相关草案：draft-hw-ai-agent-6g（华为/中国电信/中国联通，分析移动网络特性下的 agent 协议需求）、draft-rosenberg-ai-protocols（AI Agent 协议框架与用例）、draft-tong-network-agent-use-cases-in-6g、draft-yu-ai-agent-use-cases-in-6g。MCP/A2A/ACP 于 2025 年 12 月归入 Linux 基金会 Agentic AI Foundation。Compute First Networking（CFN）自 2019 年在 IETF RTGWG 提出。

**ETSI。** ENI（Experiential Networked Intelligence）ISG 发布 GR ENI 051（AI Agent 赋能下一代网络切片，2025-02）、GR ENI 055（AI Agent 核心网用例与需求，2025-10）、GR ENI 056（网络多智能体框架）。定义了 CN-Agent 概念及其功能模块与接口。

**TM Forum / GSMA。** TM Forum 提供 Open Gateway Operate API 支撑变现与运营，并与 GSMA 联合认证。GSMA Open Gateway 于 MWC23 发布，首批 8 个 API。

**Gap Analysis：现网架构短板。**
- **缺乏针对 Agent 的 QoS 类别**：现有 5QI 与切片模板为 eMBB/URLLC/mMTC 设计，无"突发容忍 + 有界时延 + 长会话"的 Agent 专用类别。
- **会话连续性机制不足**：移动切换、IP 变化、NAT 超时对 long-lived Agent 会话的中断缺乏系统性解决；QUIC 连接迁移与 SSE 续传属应用层补救，非网络原生能力。
- **突发调度与周期调度错配**：WiLLM 明确指出 token 流突发"与周期性资源调度严重错配"，现网调度器未针对随机突发优化。
- **上行资源配置保守**：为下行优化的历史架构不适配 Agent 的上行大 prompt 突发。
- **标准量化 KPI 缺失**：现有 agent 相关草案多为定性需求语言（IETF draft 无任何量化数字），缺乏 TTFT/ITL/会话中断率等面向 Agent 的可测 KPI。
- **信令开销**：Agent 的高频小包与间歇突发加剧 RRC 状态迁移信令负载，现有 RRC/DRX 参数未联合优化。

### 第五部分：Agent-QoE KPI 体系（指标定义、建议门限与测量方法）

本部分将前文分析收敛为一套可操作的 KPI 体系，作为测量工具（见《ANEB Probe 开发设计文档》）的需求输入。体系版本号 **agent-qoe-kpi v0.2.2**（v0.1 为 2026-07-11 定稿基线；v0.2 于 2026-07-12 按《测量红队清单》32 项经对抗验证的测量失真风险修订统计口径与有效性规则；v0.2.1 于 2026-07-13 为**修注版**——回写阶段 0–3 首轮实测锚点与实测注记；v0.2.2 于 2026-07-13 亦为**修注版**——新增候选指标 **REACH（连接建立可达性/握手成功率，5.5，未定版、不进 AQS）**、回写首份真实蜂窝锚点（电信 5G SA）与运营商 SNI-keyed TLS RST 实测注记（红队 R-33 / 决策 D-22），既有 T/U/C/N/R 指标定义、门限与统计口径均无变更），门限值标注为"实验性"——待蜂窝真机实测数据回流后修订。

**声明边界（claim scope）**：本体系所有指标的测量对象是**"终端至指定仿真节点的应用层端到端路径"**（claim scope = `application_end_to_end_to_probe_node`，写入结果 schema 并 const 锁定）。禁止表述为：无线层 RTT、RAN 时延、IP 层丢包率、MOS、SLA 认证或运营商全网评级。"网络分量"类派生量（如 TTFT 上下行拆分）是含时钟同步不确定度的估计值，只允许以区间形式出现且不进 AQS（见 5.3.2）。

#### 5.1 指标体系总览

指标分四组：**T 组（交互时延/流式体验）**、**U 组（上行突发）**、**C 组（连续性/可靠性，阶段二）**、**N 组（网络基线）**；另有 **R 组（无线层协变量）** 不设门限、仅作归因维度。

| ID | 指标 | 定义 | 统计口径 |
|---|---|---|---|
| N1 | RTT | 应用层往返时延（HTTP echo，同连接复用） | P50，每场景开始前采样 ≥20 次 |
| N2 | 抖动 | RTT 的 P95 − P50 | 同上 |
| T1 | TTFT（网络分量） | 请求最后一字节发出 → 首个 SSE token 事件首字节到达，减去服务端已知注入时延 | 每次流式阶段 1 个样本，取场景内中位数 |
| T2 | ITL P95 | 相邻 token 事件到达间隔的 95 分位（剔除服务端节奏误差见 5.3.4；合帧组内只保留组首间隔、0 值样本不入分位数，另以"含 coalesced"口径并列报告） | 逐 token 计算（按 seq join，见 5.3.8） |
| T3 | stall 率 | ITL >200ms 的间隔占比（Eloquent 定义）；输出含/不含 resume_latency（T5）两口径 | 逐 token 计算 |
| T4 | 严重卡顿率 | ITL >1s 的间隔占比 | 逐 token 计算 |
| T5 | 恢复时延 resume_latency | pause（思考停顿/簇间静默）后首 token 的到达间隔（含 C-DRX/RRC 唤醒税——运营商省电参数影响 Agent 体验的独立证据），按前置静默时长分桶，从 T2/T3 样本集剔除 | 分布 + P95；阶段一引入，暂不设门限、不进 AQS |
| U1 | 上行突发吞吐 | 大 prompt 上传（默认 1MB）的有效 goodput；计时终点=收到服务端 2xx 响应头（服务端读完 body，防把写入本地协议栈测成假吞吐），报告"含/剔除慢启动爬坡"两口径 | 每场景 ≥3 次上传取中位数 |
| U2 | 工具循环时延 | 一轮"上行 8KB → 服务端处理（固定 200ms）→ 下行 2KB"的端到端耗时减去 200ms | S2 场景每轮 1 样本，取 P95 |
| C1 | 会话中断率 | 流式阶段异常断开次数 / 流式阶段总数 | 跨多次测试聚合 |
| C2 | 切换恢复时间 | 触发 WiFi↔蜂窝切换 → token 流恢复到达 | 每次切换实验 1 样本 |
| C3 | NAT 静默挂起 | 空闲 N 分钟后连接是否可用及恢复耗时（N ∈ {1,3,5,10}） | 阶梯探测 |
| R1 | 无线层快照 | RSRP/RSRQ/SINR（LTE）或 SS-RSRP/SS-RSRQ/SS-SINR（NR）、PCI、频段、制式（4G/5G NSA/SA）、小区变更事件 | 1Hz 随测打点 |

> **C2 两种恢复语义（D-23，真机取证细化）。** C2「切换恢复时间」按恢复承载网络分两类，入库列 `c2CrossNetworkRecoveries` 与日志 `CONTINUITY_RECOVERY semantic=` 标注：
> - **same_network 重连恢复**：原绑定网仍在（或 AUTO 未绑定，切换由系统透明迁移），重连复用同一承载——模拟器 508ms 基线（transport=auto，evidence/phase2/continuity_e2e_20260713.log）属此；因是"平滑网络替换、原句柄可回绑"，未触及真机移动性的严苛面。
> - **cross_network 迁移恢复**：真机移动性**硬切换**（蜂窝→WiFi）拆除原绑定网句柄，重连须迁到**当前系统新默认网**后首 token 到达——即 IETF draft-hw-ai-agent-6g §3.3「Agent Service Continuity」直指的真实场景、Agent 长会话的核心诉求（QUIC 连接迁移的应用层对应）。真机首测（evidence/phase3/realdevice_continuity_kimi_20260713.log §3）实证：**固定回绑原句柄在硬切换下 EPERM 全败**（原蜂窝网 net110 被拆除，回绑 110 → Operation not permitted → 5 次退避全败 recovery_failed），故 cross_network 恢复须回绑"当前可用新默认网"而非已失效的原句柄。
>
> 恢复计时口径两类统一为"中断检出 →（迁网后）首 token 到达"，含退避与换网耗时（D-20/D-23）。§5.2 的 C2 门限对两种语义同表适用，但**只有 cross_network 样本反映真实移动性**，门限定版以真机 cross_network 数据为准（待 E-02 回流）；模拟器 same_network 数据仅验工具灵敏度与量表方向性，不用于 C2 门限定版。

#### 5.2 四级门限（agent-qoe-kpi v0.1，实验性）

适用场景：**国内云 VM 仿真服务器、蜂窝网络接入**。真实 API（跨境）参考值另列。

| ID | 优 | 良 | 可 | 差 | 依据 |
|---|---|---|---|---|---|
| N1 RTT P50 | <30ms | 30–60ms | 60–100ms | >100ms | 国内骨干网基线 + 交互响应感知研究 |
| N2 抖动 | <10ms | 10–30ms | 30–80ms | >80ms | token 流无缓冲、抖动直接透传为 ITL 波动 |
| T1 TTFT | <200ms | 200–500ms | 500–1000ms | >1000ms | Nielsen 100ms/1s 感知阈值；真实 API 端到端参考：<500 / <1000 / <2000 / >2000ms |
| T2 ITL P95 | <100ms | 100–200ms | 200–400ms | >400ms | 200ms=stall 线；优级须 P95 显著低于 stall 线 |
| T3 stall 率 | <0.5% | 0.5–2% | 2–5% | >5% | Eloquent stall 定义 + 本文阶段一 5% 触发线 |
| T4 严重卡顿率 | 0 | <0.2% | 0.2–1% | >1% | 单次 >1s 卡顿即显著损伤体验（实测 95 分位卡顿 3483ms 为反例） |
| U1 上行突发吞吐 | >20Mbps | 5–20Mbps | 1–5Mbps | <1Mbps | 1MB prompt 上传耗时 0.4s/1.6s/8s 边界 |
| U2 工具循环时延 P95 | <150ms | 150–300ms | 300–600ms | >600ms | 一次任务链 10–50 次调用，600ms×N 即分钟级拖尾 |
| C1 会话中断率 | <0.5% | 0.5–2% | 2–5% | >5% | 本文阶段一触发线 |
| C2 切换恢复时间 | <1s | 1–3s | 3–10s | >10s 或失败 | QUIC 迁移目标 ≈0；TCP 重建+TLS+SSE 续传的现实阶梯；same/cross 两种恢复语义见 §5.1 注（D-23），门限定版以真机 cross_network 为准 |
| C3 NAT 挂起 | ≥10min 存活 | ≥5min | ≥3min | <3min 或静默挂起 | 运营商 CGNAT 常见超时 1–5min |

R 组无线层参考区间（工程惯用值，仅作归因参考，非本体系门限）：LTE RSRP 优 >-85dBm / 良 -85~-95 / 可 -95~-105 / 差 <-105；SINR 优 >20dB / 良 10–20 / 可 0–10 / 差 <0；NR SS-RSRP 对应放宽约 5dB。

##### 首轮实测锚点（2026-07 模拟器 + 固网基线，供门限迭代参考）

以下为"门限随数据回流修订"制度的第一轮数据锚点，取自阶段 0–3 验收证据（evidence/phase0、phase3）。**环境与 claim scope 声明**：除末行外，客户端为 Android 模拟器（x86_64 AVD）或本机 PC 探针，承载为本机环回/固网，claim scope 一律为 `application_end_to_end_to_probe_node`；**均非蜂窝无线数据，不用于门限定版**——仅验证工具灵敏度与量表方向性。末行为**首份真实蜂窝无线层锚点**（ELS-AN00 电信 5G SA，evidence/phase3/realdevice_first_campaign_20260713.log），但 E-01 蜂窝 KPI 因运营商 SNI-keyed TLS RST（R-33）未获、仍待 bare-IP 通道重采（D-22）补齐，故亦不用于门限定版；蜂窝真机门限锚点待 E-02 双通道回流。

| 环境剖面 | N1 RTT P50 | T1 TTFT | T2 ITL | T3 stall | U1 (1MB) | U2 P95 | AQS (aqs-v0.1) |
|---|---|---|---|---|---|---|---|
| 本机环回（模拟器→宿主容器，无损伤对照组） | 4.1–5.0ms | 3.3–18.2ms | P50 10.5–25.5ms | 0 | 23.4–23.8Mbps | 12.5–21.1ms | 96.3 / 97.1（优） |
| 固网→深圳 E-01 直连（PC 探针） | 28.1ms（抖动 17.3ms） | ≈31.5ms | 逐读间隔 P50 25.1 / P95 29.2ms | 0 | – | – | –（探针非完整 run） |
| netem 100ms + 1% loss（灵敏度验收） | 104.5–105.1ms | 103.4–127.2ms | P50 10.7–35.0ms | ≤0.001 | 5.2–11.9Mbps | 207–418ms | 86.0 / 86.1 |
| netem 跨境剖面 150ms±10ms + 0.5% loss（E-04 本地替代） | 153.0–159.4ms | 148.4–170.6ms | P50 43.7–52.5ms | 0.005–0.010 | 8.2–8.5Mbps | 281–308ms | 80.0 / 80.9（良） |
| 真实蜂窝（电信 5G SA n78；ELS-AN00；RSRP -96.7dBm / SINR 8.3dB / RSRQ ~-12dB / PCI 317）† | —‡ | 2.4–2.6s* | ITL 190–230ms* | —‡ | —‡ | —‡ | —‡（SNI-RST 阻断 E-01） |

† 首份真实蜂窝无线层锚点（NR-SA 三元组核实：dataNetworkType=NR + override=none + nrState=nsa_unknown，genuine 5G SA 非 NSA；单稳定小区、无 CELL/RAT_CHANGE）。‡ E-01 蜂窝 N1/T2/T3/U1/U2/AQS 因运营商 **SNI-keyed TLS RST**（对 sslip.io/nip.io 主机名握手中途双向 RST，bare-IP 同路径 TLS 可完成）**未获**，待 bare-IP + IP-SAN 自签通道重采补齐（红队 R-33、决策 D-22）。* T1/T2 为 **Kimi 真实域名 api.moonshot.cn**（NR-SA 下 SNI 放行、TLS 洁净完成）已获值——claim scope 为至 Moonshot 生产端点、**不同于 E-01 探针节点**，仅作真实蜂窝下 LLM 交互时延的方向性锚，不与其余行同口径比较。

锚点解读（详见 evidence/phase3/netem_experiments_20260713.md 与 realdevice_first_campaign_20260713.log）：①已知损伤下 KPI 方向与量级全部符合预期（N1 精确复现注入值、KPI 分级掉档 优→差 6/6 场景），且弱网样本零误判 invalid（R-05/R-06 幸存核对通过）；②±10ms 抖动使 ITL/stall 显著移动而平坦时延几乎不动 ITL——印证"抖动比时延更伤流式体验"的量表设计；③注入丢包在 TCP 层被重传修复、T4 恒 0，属正确测量而非灵敏度不足；④**真实蜂窝行**证明无线层采集通路已打通（NR-SA 信号/小区/制式三元组核实无误），但也实证了连接层中间盒（SNI-RST）是蜂窝主通道的硬前置门——首份真实蜂窝 KPI 待双通道（D-22）落地后回填。

#### 5.3 测量方法与方案

**5.3.1 计时基准。** 客户端一律使用单调时钟（Android `SystemClock.elapsedRealtimeNanos`），禁用墙钟；计时点在网络库回调线程（OkHttp EventListener / SSE 读线程）直接记录，避免主线程卡顿污染。

**5.3.2 时钟同步与归因。** 每场景**首尾各执行一次** 4 时间戳握手（t0 客户端发出 / t1 服务端收到 / t2 服务端发出 / t3 客户端收到，Cristian 算法取最小 RTT 样本），场景内按首尾两次 offset 线性插值做 skew 校正（90 秒场景内晶振/虚拟化漂移可积累 2–9ms），漂移率随结果存档，>100ppm 打 `offset_suspect` 标。采样细则：前 2–3 个请求丢弃预热、样本间 100–300ms 随机间隔去相关（防 20 样本落入同一拥塞周期整体污染 min-RTT）、原始序列全量入库；offset 质量指标（min−P10 差、变异系数）超阈值即 `offset_low_confidence`，fail-closed 抑制单向拆分输出。**归因边界**：蜂窝上/下行路径结构性不对称（上行经历 SR/BSR/授权等待），对称假设下估出的 offset 不能为"上下行不对称"主张背书（循环论证）——所有依赖 offset 的拆分量只允许以区间 `{low, high}` 输出（按最坏不对称 0–100% 分配 RTT），区间宽度超过所在门限档位宽度 50% 即判 `indeterminate` 并在 UI 显示"不可判定"；拆分量为实验性派生量、不进 AQS、对外报告禁止单独引用点值。T1 总量、T2/T3/T4（间隔差）、U1/U2、N1/N2 均不依赖 offset，可出单点值。TTFT 拆分采用三段法：先用 prelude 帧剥离服务端 dwell（见设计文档 §6）。

**5.3.3 反缓冲与批化检测。** SSE 流必须逐 token flush 且全链路无缓冲：服务端每 event 显式 Flush、直连裸端口、不走 CDN。批化检测在**服务端节奏剥离后的残差域**运行（"负残差簇 + 正残差尖峰"的锯齿才是缓冲证据，profile 内生的突发节奏被剥离后不会误触发），输出**连续 `buffering_score`**（残差自相关/锯齿占比）随结果存档，而非简单二值判无效——防两类误判：弱覆盖下空口 TTI/C-DRX 天然微批被当成中间盒缓冲、导致弱网样本被系统性丢弃（幸存者偏差，恰好丢掉最有取证价值的样本）；以及亚阈值轻度缓冲带病通过。区分手段：批间隔周期性谱分析（DRX/调度周期呈 8/10/20/40ms 离散特征 vs 中间盒缓冲的连续/RTT 相关特征）+ 与 R1 无线快照联动（信号差+批化→空口聚合；信号优+批化→中间盒）+ 与客户端 `app_jank` 时间轴比对区分设备侧冻结（`device_side_batching`）与链路缓冲（`link_batching`）。判无效阈值待阶段一用签名样本（无中间盒 WiFi 直连 / 已知代理路径 / nginx 默认缓冲反代）标定后确定，标定数据存 evidence/。**实测注记（2026-07-13）**：三签名标定与 netem 实验（evidence/phase1/c08_calibration_20260713.md、evidence/phase3/netem_experiments_20260713.md）显示——纯 `proxy_buffering` 在快速客户端下并不攒批，**gzip 过滤器才是现网 SSE 攒批主因**；且纯时延+丢包路径上"重传批化"与中间盒缓冲签名同形（middlebox_suspect 误报，affects_validity=false 无实害），区分两者需引入重传共变量（TCP_INFO retrans，P3-C05 迭代方向）。

**5.3.4 服务端节奏误差剥离。** 每个 SSE event 内嵌序号 `seq` 与两个服务端时间戳——**期望发出时刻**（profile 时刻表）与**实际 flush 时刻**（一律以进程启动锚点的单调差序列化，防墙钟 NTP 步进伪造卡顿）。客户端 ITL = 到达间隔；**网络贡献 = 到达间隔 − 发出间隔的逐 seq 对齐差**（强制按 seq join，见 5.3.8），服务端调度误差（GC、定时器漂移）由此剥离。服务端 pacing 误差**拆两类分别记录**：timer 迟到（调度问题→判服务端失真）与 flush 阻塞时长（TCP 回压→**网络证据，绝不判失真**、随流返回参与归因）——不区分两者会把弱网最恶劣的 stall 样本误判为服务端失真而系统性剔除，形成方向性乐观偏差。"网络贡献为负"的样本不做 clamp，原值入库并统计负值占比作为发出时刻可信度指标。

**5.3.5 无线层打点。** 以 1Hz 采样 TelephonyManager 信号与小区信息，与 KPI 事件按时间戳对齐；记录测试期间的小区变更（PCI 变化）与制式变更事件，用于把 stall/中断事件归因到切换。需要 `ACCESS_FINE_LOCATION` + `READ_PHONE_STATE` 权限；NSA/SA 判别用 `TelephonyDisplayInfo` override 类型交叉验证。

**5.3.6 测试流程与有效性。** 两种模式：**快测模式**——S1/S2/S3 各跑 1 遍（约 4 分钟），输出 AQS；**取证模式**——各场景 3 遍取中位数（约 12 分钟），**三遍间场景执行顺序按拉丁方轮转**并记录实际顺序（防固定顺序的无线状态冷热系统性污染对照），输出含置信区间的报告。测试期间强制前台 Service + 屏幕常亮 + 非省电模式；有效性守卫为**测中持续监控**（Doze/省电变化、路径变更、热状态迁移均入事件时间轴），输出三态 `valid / valid_low_confidence / invalid`，触发条件与 fail-closed 语义见设计文档 §4。元数据记录：设备型号、系统版本、运营商、APN、厂商 5G 省电类设置（人工 checklist）、APK 版本与签名指纹、服务端环境快照（sysctl/拥塞算法/时钟状态）。

**5.3.7 对照组设计。** S1（对话流）即"普通 chat"对照组；S2/S3 相对 S1 的 KPI 差异，就是"Agent 流量画像对网络的额外压力"的直接证据。弱网剖面（服务端 tc netem 注入固定时延/丢包）用于验证工具灵敏度与文献结论（如 1% 丢包对 TPOT 的放大效应）。

**5.3.8 样本配对与失败语义。** KPI 计算强制以 event 内嵌 `seq` 为 join 键（禁止数组位置配对——丢/错切一个 event 会使整段对齐静默错位而数值看似合理）；seq 缺号/重号即计 gap，gap>0 打降级标、gap 超 token 总数 1% 判该场景 invalid。失败/超时样本的时延值一律记 `null`（绝不记 0，也不记超时上限值）；流式异常中断时，中断点后的"最后间隔"不计入 ITL/stall/严重卡顿统计，改计会话中断事件（C1 证据）；`successful` = 2xx + 无传输错误 + 计时值非空。invalid 场景抑制 KPI/AQS 聚合输出，但原始事件全量保留并记失效原因码。

#### 5.4 AQS 综合评分（aqs v0.1）

每个 KPI 按四级门限锚点做分段线性映射：优/良边界=85 分、良/可=70 分、可/差=55 分，级内线性插值，上限 100、下限 0。加权聚合：

| 组 | KPI 与权重（MVP） |
|---|---|
| 流式体验 55% | T1 20% + T3 20% + T2 15%（T4 作一票否决项：>1% 时 AQS 封顶 54） |
| 上行突发 25% | U1 15% + U2 10% |
| 网络基线 20% | N1 10% + N2 10% |

阶段二引入 C 组后，上述权重 ×0.8，C 组占 20%（C1 10% + C2 10%）。AQS 分级：≥85 优 / 70–85 良 / 55–70 可 / <55 差。权重与锚点写入版本化 profile，随实测数据迭代，报告须标注 AQS 版本号。

**AQS 输入映射口径（as-built，与实现 `AqsInputMapper` 一致）**：run 级 AQS 的各 KPI 输入按场景来源固定映射——**N1/N2 ← S1** 首次 clock_sync 样本（尾部 clock_sync 仅用于 skew 插值，不进 N 组统计）；**T 组（T1–T5）← S2** 主场景（S1/S3 的 T 组仅逐场景展示，不进 AQS）；**U1 ← S3** 1MB 上传，进 AQS 用**含慢启动**主口径（剔慢启动口径并列展示；S2 的 512KB 上传不进 U1）；**U2 ← S2** tool_loop P95。取证模式多遍时逐 KPI 取**有效遍（值非 null）的中位数**；贡献场景 invalid/缺失 → 对应 KPI 记 KPI_MISSING（绝不以 0 顶替），run 级绝不因单场景失效合成 invalid。映射合同随日志 `AQS_INPUT_MAP` 行落盘可审计。

**AQS 表述边界**：AQS 是**实验性应用层综合体验分**（版本号随附），测量对象为"终端至指定仿真节点的应用层路径"；禁止表述为 MOS、无线层评级、运营商全网评级或 SLA 结论。invalid 场景不产生 AQS；`valid_low_confidence` 场景的 AQS 必须带低置信标注展示；T2 进入评分的口径为"合帧组内只保留组首间隔"（见 5.1）。

#### 5.5 候选指标（agent-qoe-kpi v0.2.2 新增，未定版、不进 AQS）

前述 T/U/C/N 指标默认连接已建立；但真实蜂窝首测（2026-07-13）实证：**连接建立本身**可能被运营商中间盒选择性阻断，成为 Agent 长会话的硬门槛。此前 PC 侧亦实证按 TLS 栈指纹的选择性 RST（P3-C11）。据此新增一项候选指标，先入册观测、门限与是否并入 AQS 待数据回流后议定。

**REACH — 连接建立可达性 / 握手成功率（candidate）**

- **定义**：按 **{SNI 主机名, bare-IP, 协议栈}** 分组的 TLS 握手成功率 = 握手完成（进入应用数据阶段）次数 / 尝试次数。"握手失败"细分终止方式：中途双向 RST（中间盒注入嫌疑）/ 超时 / 证书校验失败（与连接层干预区分，如 bare-IP 缺 IP-SAN 属证书问题非 RST）。
- **理据**：真实网络的连接层干扰对 Agent 长会话建立是硬门槛——握手失败即该分组下 T/U/N 全组无数据、AQS 不可计算。将其显式量化，是把"测量障碍"转为"可量化测量维度"。
- **测量方法**：同设备同 SIM 分钟级 A/B 对同一目的地分别用 {带 SNI 主机名 vs bare-IP} × {OkHttp(TLS1.3) vs Cronet(QUIC)} 发起握手，记录每次终止方式与 claim scope；bare-IP 通道另需 IP-SAN 自签证书以隔离"证书失败"与"连接被杀"。
- **首轮实测锚点（电信 5G SA n78，ELS-AN00，evidence/phase3/realdevice_first_campaign_20260713.log）**：`sslip.io 主机名` = **RST**（握手中途双向复位）；`bare-IP 120.79.148.0:8443` = **OK**（TLS 完成，仅证书不匹配 IP）；`真实域名 api.moonshot.cn` = **OK**（SNI 放行、洁净完成）。即干预面为 wildcard-DNS 类主机名 SNI，非全量 SNI、非端口、非目的 IP。
- **claim scope 与边界**：REACH 是**应用层连接建立可达性**（`application_reachability_to_probe_node`），禁止表述为运营商全网封锁率或审查结论；本项目为自有服务器 + 自有设备 + 自有 SIM 的授权性能测量，SNI-RST 作为量化维度纳入、bare-IP 通道用于采真实 KPI，目的非规避审查。
- **与 P3-C11 的关系**：P3-C11 是按 **TLS 栈指纹**（大 ClientHello）的选择性 RST，REACH 的 `协议栈`维度承接之；R-33（本次）是按 **SNI 内容**的选择性 RST，REACH 的 `SNI 主机名 vs bare-IP`维度承接之——二者共同构成"握手成功率"候选指标的两条正交实测证据。详见《测量红队清单》R-33、《DECISION_LOG》D-22。

## Recommendations

**阶段一（0–6 个月）：建立测量基线与画像。**
- 在真实移动网络（4G/5G）上采集主流移动 Agent 应用（Claude Code、Kimi Code/Claw、Cursor 移动端）的流量 trace，测量 TTFT、ITL、token 间间隔分布、上下行字节比、包大小分布、会话时长与中断率。
- 关键基准：借鉴 Eloquent，token 间间隔 >200ms 判定为 stall；同时统计会话中断率、切换导致的重连次数。**触发调整的阈值**：若 95 分位 ITL 在移动场景下 >1s，或切换导致会话中断率 >5%，则连接持续性问题优先级最高。

**阶段二（6–18 个月）：验证网络能力对接。**
- 试点 CAMARA QoD API 为 Agent 关键任务段申请低时延 profile，量化 QoD 对 95 分位 ITL 与卡顿率的改善。
- 试点 QUIC 连接迁移 + SSE 断点续传，验证移动切换场景下会话保持效果。**基准**：QUIC 迁移应使切换导致的连接中断趋近于零。
- 在 MEC 部署轻量推理，测量边缘 vs 云端的 TTFT 改善，确定下沉的收益/成本平衡点。

**阶段三（18+ 个月）：推动标准化与架构演进。**
- 向 3GPP SA1/SA2、IETF、ETSI ENI 输入 Agent 流量量化 KPI 与"突发容忍 + 有界时延"新型 QoS 类别提案。
- 探索意图驱动接口：Agent 通过 A2A/API 向网络申请确定性连接，网络进行意图识别与资源编排。
- 推动上下行资源再平衡与 Agent 感知的 RRC/DRX 参数优化。

**决策原则**：优先投资"有界时延 + 会话持续性 + 小包可靠性"，而非盲目扩容带宽。若测量显示某场景带宽利用率长期 <20% 但卡顿率高，则明确证伪"带宽诉求论"，应将资源转向抖动控制与连接保持。

## Caveats

- **反直觉判断的边界**：多模态 Agent（截图转代码、视频输入，如 Kimi K2.5/K2.6 视觉 agentic）会引入周期性大 payload 上行突发，此时带宽诉求局部回升。"低带宽"结论主要适用于文本编码类 Agent 的稳态 token 流，不适用于多模态输入峰值。
- **量化数据的时效性**：Eloquent 的包大小数据（Bard 50–80B、ChatGPT 842B/3tokens）为 2024 年初测量，模型版本已迭代，2026 年端点行为可能不同。~10 tokens/s 的 GPT-4 速率也已被更快模型超越（如部分模型 per-token 0.010s）。
- **IETF 草案的地位**：draft-hw-ai-agent-6g、draft-rosenberg-ai-protocols 等均为个人草案（非 IETF 工作组文档、非 RFC），按 IETF 规则只能作为"work in progress"引用，draft-hw-ai-agent-6g 已于 2026 年 1 月 21 日到期。其内容为定性需求，无量化数字。
- **测量场景差异**：WiLLM 的上下行不对称数据来自 image-to-text / text-to-image 场景；"LLMs on Edge"论文测量的是分布式多设备边缘推理的节点间流量，非 client↔cloud 的 Agent 交互流量，其吞吐数字（2.7–28.46 tokens/s）反映的是树莓派/Jetson 集群性能，不代表商用云 Agent。HALO 的 617.2ms/1% 丢包数据来自树莓派 TinyLlama 张量并行测试，绝对值随模型与硬件而变，但趋势具普适性。
- **URLLC/切片的适用性存疑**：将 URLLC 直接套用于 Agent 是常见误区。URLLC 为周期性小数据设计，与 Agent 突发模式不匹配；本报告判断"需要新型 QoS 类别"为推论而非既有标准结论。
- **部分行业来源（API 网关厂商博客、Cisco 博客等）带有营销倾向**，其"机器速度""agentic era 需要新网络"等表述已剔除营销成分、仅保留可验证的技术观察。