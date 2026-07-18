# ANEB App 0.5.9 · 语义结论闭环验证

> 日期：2026-07-18（Asia/Shanghai）
> 范围：P1 Android、P3/Profile 横切合同、P2 合成弱网 Profile 回执版本
> 结论：本地合同与自动化门禁通过；P40 蜂窝 Quick 已在 0.5.9 紧邻候选上完成动态视觉、结果页、系统下载目录 JSONL 读回及 strict-v2 校验。当前精确 APK 随后收紧“失败门限 basis 仅列实际失败指标”，并确保 Token 在总分不可计算时仍先报告任务完成性；两个分支均由自动化回归覆盖，尚待共享手机释放后做同二进制安装确认。

## 1. 反方风险与本轮裁定

- ［KNOWN｜HIGH］0.5.8 虽能输出多条中文结论，但导出器按条目顺序猜严重级别；第二条以后无论是事实、证据边界还是失败门限都会被标成 `recommendation`。
- ［KNOWN｜HIGH］Profile 已声明 `behavior_feature_ids`，三类 Compose 结果页却各自维护硬编码行为文案，存在 Profile 与 UI 漂移。
- ［KNOWN｜HIGH］受控恢复场景包含计划中的故障刺激，不能把刺激阶段的失败轮次直接判作普通用户任务失败。
- ［INFERRED｜HIGH］若继续只增加字符串，高分结果仍无法稳定回答“任务是否完成、模拟什么业务、瓶颈在哪里、网络目标是什么”，不具备可审计的商业交付质量。

本轮保留 Token、AI 实时和网络综合三套独立评分，只统一结论项的最小公共结构：

```text
conclusion_id + severity + text + basis[] + category conclusion_policy_id
```

结果 Schema 已允许这些字段，因此继续使用 `aneb-result-v2`；类别结论语义实质变化，必须升级结论策略与 Profile 小版本，不能同名覆盖 v1/v2 历史。

## 2. 已实现合同

- 每条正式结论由类别评分器冻结，不由 UI 或 JSONL 导出器重算。
- `severity` 只取 `info / recommendation / warning / failure`，不再由数组位置推断。
- `basis` 指向实际 `metric:*`、`score:*`、`evidence:*` 或 Profile/策略依据。
- 三类可计算结果统一包含 verdict/置信度、任务完成性、Profile 业务行为、网络目标和主要瓶颈。
- 必需指标缺失时总分继续为 null，但已观察的任务失败、缺失指标和证据边界不会被吞掉。
- 无效 run 保留明确 failure 结论与 `invalid_reason` 依据，不生成伪分数。
- AI 实时默认网络事件仍只写“同窗共现、支持关联定位、不能单独证明因果”。
- Recovery 按“计划中断是否恢复”计算任务完成性；普通交互仍按轮次和意外会话中断计算。
- Compose 结果页直接展示同一冻结文本；行为模型身份单独展示，删除三处硬编码行为特征卡。

## 3. 版本演进

| 类别 | 新 Profile | 新结论策略 |
|---|---|---|
| Token Quick / Standard | 1.1.0 | `token-sim-conclusions-v2` |
| Token Stress | 1.1.0 | `token-stress-conclusions-v2` |
| AI 实时 Quick / Standard | 1.1.0 | `realtime-interaction-conclusions-v2` |
| AI 实时 Recovery | 1.3.0 | `realtime-recovery-conclusions-v3` |
| 网络 Quick / Standard / 合成容量时延 | 1.1.0 | `network-comprehensive-conclusions-v2` |
| 网络合成 Recovery | 1.1.0 | `network-recovery-conclusions-v2` |
| 专用网关 Loss | 1.1.0 | `network-gateway-conclusions-v2` |
| 专用网关 Recovery | 1.2.0 | `network-gateway-recovery-conclusions-v3` |

Profile catalog 从 1.2.0 升至 1.3.0。6 个 Token/AI 实时运行包已由 P3 发布工具重新生成规范化 Profile 哈希、运行计划绑定和 manifest；网络 Profile 为内嵌 phase 合同，不使用外部运行包。

## 4. 自动化证据

| 门禁 | 结果 |
|---|---|
| Android JVM | 89 suites / 545 tests / 0 failures / 0 errors / 0 skipped |
| Android Lint | 0 errors / 11 warnings |
| Release 边界 | PASS |
| Spec catalog | 8 schemas / 2 families / 16 profiles / 6 runtime bundles / 6 embedded-network profiles / 4 models，PASS |
| 结果 Schema | compatible v1 + strict v2 + shared core + cross-version/null/radio invariants，PASS |
| 测量分析 | 12 tests，PASS |
| 行为模型 | 31 tests，PASS |
| Go server | PASS；E-01 已部署合成弱网回执 Profile 1.1.0；本机与公网目录、恢复隔离、TCP/UDP 8443 smoke 通过 |
| Go gateway | PASS |

最终 Debug 候选为 `com.aneb.probe.codex` / `0.5.9-codex` / versionCode 41，APK 58,477,100 bytes，SHA-256 `4C7EA2FB0554E661EAE536100AD0BA273FC03B66EC1A15FE0EB24CBCC08EDAE9`。Debug 证书 SHA-256 为 `6644DDCF728B5BC9EFAA07361FC828B9F419D977681000F2E4136C24340B89D9`；Debug 产物不是签名 Release 证据。

［KNOWN｜HIGH］上述精确 APK 是蜂窝实测后重新构建的当前候选；实测后的业务逻辑变化仅限两个语义边缘分支：`realtime-failed-quality-gates` 的 `basis` 从全部必需指标收紧为实际未达指标；Token/Token Stress 在必需指标缺失时仍输出任务完成结论。定向测试与全量门禁均通过；由于 P40 已由 Experience Lab 占用，本文件不把紧邻候选的真机结果冒充为该精确 SHA 的安装证据。

新增/加强的回归覆盖：正常高置信结果、必需指标缺失、无效证据、UDP 不可用、默认网络 PATH_CHANGE 共现、Profile 未识别行为特征显式暴露、稳定 ID 唯一性、严重级别及 basis 原样进入结果信封。

## 5. P40 真机结果

- ［KNOWN｜HIGH］0.5.9 紧邻候选的 run `019f7377-9a61-7db5-a8c4-1ac57de1a486` 使用明确绑定的蜂窝承载，`ai_realtime_voice_quick@1.1.0` 完成 3/3 轮、0/1 意外中断，99.8/A；Quick 证据保持 `LOW/INCONCLUSIVE`，没有因高分越权升级置信度。
- ［KNOWN｜HIGH］动态页实测刷新应用 RTT、上下行净荷速率、轮次和阶段；缺失值显示“—”而非 0。结果页逐项显示任务完成性、Profile 业务行为、门限目标与主要瓶颈。
- ［KNOWN｜HIGH］最终结果含 12 条冻结语义结论；会话 RTT P95 76.3ms、到达变化 P95−P50 15.4ms、通话负载 RTT P95 120.8ms、2 秒音频准时帧率 99.7%、打断响应 P95 54.2ms。结论策略为 `realtime-interaction-conclusions-v2`。
- ［KNOWN｜HIGH］用户从真实“保存 JSONL”按钮写入系统下载目录后读回；文件 `evidence/device/aneb_059_final_cellular_result.jsonl` 为一条 `aneb-result-v2`，Schema/身份/摘要校验通过，文件 SHA-256 `FE964695E19997796F5FEB84E05F50FB69F61F2C6299FA0C577263E5198F7EA9`。
- ［KNOWN｜HIGH］测试中还修复两项真机暴露问题：旧默认 SNI 入口一次性迁移到 E-01 bare-IP 主通道；未收到 WebSocket ready 现在按连接/任务失败处理，不再误报 `node_contract_mismatch`。必需门限说明改为“不能判为 PASS，最终判定服从证据等级”，避免与 Quick 的 INCONCLUSIVE 矛盾。
- ［KNOWN｜HIGH］此前两次 Wi-Fi 失败发生在酒店门户尚未完成账号密码认证时，只能证明“未认证接入无法访问 E-01”；不能声称酒店 Wi-Fi 干扰 8443，也不能与蜂窝成功组成网络质量 A/B。
- ［KNOWN｜HIGH］该次测试结束后已 HOME + force-stop Codex 包；Claude/Codex 两包均无 PID，Codex 无 Service，前台为 Huawei Launcher，Wi-Fi 已开启。此后 P40 已交由 Experience Lab 做 WireGuard 准备，Codex 在收到明确释放通知前不再操作手机。

## 6. 仍保留的限制

- `aneb-result-v2` 没有单独的 `kind` 字段；当前通过稳定 `conclusion_id` 表达 verdict/completion/behavior/target/bottleneck 类别。只有外部消费者证明需要独立枚举字段时才发布 result v3。
- Profile 行为特征是业务需求描述，不是对 Kimi、DeepSeek、千问真实网络行为的已校准声明；4 个模型仍为 `hypothesis`。
- 单次 Quick/Stress/Recovery 继续保持低置信或方向性证据，不因结论文案更完整而提升统计置信度。
