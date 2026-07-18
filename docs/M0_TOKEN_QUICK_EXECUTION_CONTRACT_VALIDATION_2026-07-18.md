# M0-EC1 Token Quick 跨端执行合同与验证记录

> ［KNOWN｜HIGH］反方结论：M0-EC1 目前仍不能标记为“0.8.0 已部署”或“跨端正向验收完成”。
> commit `708ced3` 的 GitHub CI 工件 0.5.11 已安装到 P40，并已在 E-01 0.7.0 上证明缺少能力回执时
> fail closed；首次 0.8.0 切换的候选回执和全量旧端点 smoke 通过，但旧防火墙 raw 指纹误报随后
> 触发自动回滚。E-01 当前继续运行 0.7.0；须完成独立交接、用新 lease 重试部署并取得 Quick
> 正向 run，才能改变这个结论。

## 1. 目标与非目标

- ［KNOWN｜HIGH］本切片只解决一个问题：P1 在执行 Token Quick 前，能否证明本地执行引擎、
  P2 节点能力和本次精确 Profile 是互相兼容的。
- ［KNOWN｜HIGH］目标 Profile 只有 `token_multimodal_quick@1.2.0`；其余 11 个 Published
  Profile 保持既有兼容行为。
- ［KNOWN｜HIGH］本切片不修改业务指标、网络指标、质量目标、门限、AQS 权重、T4 否决、
  结论算法、Room schema 或结果合同。
- ［KNOWN｜HIGH］Profile 只能引用预定义原语和线路合同，不能下发任意 URL、脚本或命令。

## 2. 候选版本边界

| 单元 | 候选版本 | 当前状态 |
|---|---:|---|
| P1 / ANEB Probe Android | 0.5.11-codex，code 43，Room v19 | ［KNOWN｜HIGH］commit `708ced3` 的 GitHub CI 工件已安装到 P40；APK SHA-256 为 `7586B874EE53DFDB75C0E56EE0B50F43CCA2D1480ECA01E39256CDCCC3AAA0CC`。97 suites / 577 JVM tests、assembleDebug 与 Lint 通过；已完成 0.7.0 缺回执负向真机验证，尚未完成 0.8.0 正向 run |
| P2 / aneb-server | 0.8.0 | ［KNOWN｜HIGH］离线测试、首次切换中的候选回执及全量旧端点 smoke 通过；随后因旧 raw 防火墙指纹误报而自动回滚，当前未部署到 E-01 |
| P3 / aneb-ai-behavior-model | 0.3.0 | ［KNOWN｜HIGH］离线测试通过 |
| catalog / Profile 治理 | 1.4.0 | ［KNOWN｜HIGH］离线 catalog 校验通过 |
| Token Quick Profile | 1.2.0 | ［KNOWN｜HIGH］manifest 已绑定精确 Profile 与 runtime plan |
| 执行要求合同 | `aneb-execution-requirements@1.0.0` | ［KNOWN｜HIGH］P1/P2/P3 共用 |
| 服务端能力回执 | `aneb-server-capability-receipt@1.0.0` | ［KNOWN｜HIGH］P1/P2 共用 |

## 3. 冻结合同

［KNOWN｜HIGH］Token Quick Profile 顶层执行要求固定为：

```json
{
  "execution_requirements": {
    "contract_id": "aneb-execution-requirements",
    "contract_version": "1.0.0",
    "client_engine": {
      "contract_id": "aneb-token-simulation-engine",
      "min_version": "1.0.0",
      "max_version_exclusive": "2.0.0"
    },
    "server_capability_receipt": {
      "contract_id": "aneb-server-capability-receipt",
      "min_version": "1.0.0",
      "max_version_exclusive": "2.0.0"
    },
    "required_primitives": [
      {"primitive_id": "echo", "wire_contract_id": "aneb-echo-v1"},
      {"primitive_id": "token_sim", "wire_contract_id": "aneb-token-task-v1"},
      {"primitive_id": "download", "wire_contract_id": "aneb-download-v1"}
    ]
  }
}
```

- ［KNOWN｜HIGH］P1 引擎身份为 `aneb-token-simulation-engine@1.0.0`，必须落在 Profile 声明
  的半开版本区间内。
- ［KNOWN｜HIGH］P2 回执必须携带同一三项 `primitive_id/wire_contract_id` 映射，并在
  `validated_profiles` 中精确声明 `token_multimodal_quick@1.2.0`。
- ［KNOWN｜HIGH］Profile 的 `canonical-json-sha256-v1` 摘要为
  `38b85843a4216312836bf7f0509bb005356262fa917e235879b3ffeb9ca525e4`；回执中使用
  `sha256:` 前缀形式。该摘要不是把自身写入 Profile 后再计算的自引用字段，而是由 manifest
  与服务端/P1 运行时独立核对。

## 4. 运行顺序与失败语义

1. ［KNOWN｜HIGH］P1 先加载并校验随 App 发布的 Quick `profile.json`、`runtime_plan.json`
   和 `manifest.sha256`。
2. ［KNOWN｜HIGH］P1 可以向目标节点发送一次 `/api/v1/serverinfo` 预检请求，读取
   `execution_capabilities`；该请求不是 Quick 业务测量原语。
3. ［KNOWN｜HIGH］P1 依次核对本地引擎版本、回执合同版本、精确 Profile ID/版本/哈希和三项
   必需原语的线路合同。
4. ［KNOWN｜HIGH］只有全部核对通过后，P1 才能发出首个 echo、token-sim 或 download 业务
   请求。任一核对失败都必须 fail closed，并给出明确中文错误，不能退回“尽力执行”。
5. ［KNOWN｜HIGH］未知的额外 server capability 可以忽略；重复原语 ID 必须拒绝，即使重复项
   的线路合同相同也不能依赖数组顺序决定结果。

## 5. 兼容性与安全边界

- ［KNOWN｜HIGH］12 个 Published Profile 中只有 Quick 声明 `execution_requirements`；旧 11
  个 Profile 不因缺少该字段失败。
- ［KNOWN｜HIGH］P2 0.8.0 启动时只把已通过 schema、身份、manifest、规范化哈希和原语合同
  校验的 Quick 写入回执；无法证明时拒绝启动。
- ［KNOWN｜HIGH］P1 对 Quick 的能力门禁发生在业务流量之前，因此不兼容节点不会产生部分
  Token 任务、部分下载或可被误评分的半次 run。
- ［KNOWN｜HIGH］执行合同只决定“能不能安全执行”，不改变“测什么、如何打分或如何下结论”。

## 6. 验证证据与边界

| 验证层 | 结果 | 可支持的结论 | 不能支持的结论 |
|---|---|---|---|
| P3 schema、生成器、catalog 与 manifest | ［KNOWN｜HIGH］离线测试通过 | Quick 1.2.0 执行要求可生成、可校验，旧 Profile 兼容，精确哈希已冻结 | 不能证明 Android 已正确阻断业务请求 |
| P2 能力加载、规范化哈希、回执与三项真实 handler | ［KNOWN｜HIGH］Go 离线测试通过 | 0.8.0 候选能 fail closed，并能为精确 Quick 生成能力回执 | 不能证明 E-01 已运行 0.8.0 |
| 部署脚本安全与候选回执 smoke | ［KNOWN｜HIGH］离线测试通过；首次公网切换实际执行后自动回滚 | 0.8.0 候选回执、旧端点 smoke 和 0.7.0 回滚路径均在 E-01 实际运行；最终修复提交 `4030179` 已保留防火墙语义、消除仅由 iptables-save 运行时间造成的漂移，并拒绝空/畸形/错误工具族快照 | 不能据此声称 0.8.0 已部署，也不能把事故后稳定性追溯成切换窗口绝无并发语义变化 |
| P1 解析、能力门禁与零业务请求路径 | ［KNOWN｜HIGH］26 项执行合同定向测试通过，覆盖严格解析、精确旧版白名单、运行包路径绑定与 fail-closed；全量 97 suites / 577 tests，0 failure / 0 error / 0 skipped；assembleDebug、Lint 0 error | 缺失/冲突回执在控制面后、首个业务请求前拒绝；Quick 运行包不能被其他 variant 的自洽文件替换；合成 transport 的业务请求数为 0 | 不能证明 E-01 公网或 P40 真机路径 |
| GitHub CI 与云端 APK | ［KNOWN｜HIGH］commit `708ced3` 的 6 个 CI jobs 全部成功；工件 manifest/checksum 已核对并安装到 P40 | 当前真机所装 0.5.11 与云端构建工件身份精确一致 | 不能证明 E-01 0.8.0 或正向 Quick 已验收 |
| P40 Pro / E-01 负向真机联调 | ［KNOWN｜HIGH］E-01 0.7.0 上两次 Quick 均以 `receipt_missing` fail closed | P1 会在 serverinfo 控制面预检后、echo/token-sim/download 业务阶段前拒绝不兼容节点，并持久化 INVALID/未评分结果 | 不能声称 0.8.0 正向业务路径、蜂窝/Wi-Fi 体验质量或评分正确性已验收 |
| 全仓质量门 | ［KNOWN｜HIGH］2026-07-18 本地执行 PASS | Android 97 suites / 577 tests（0 failure / 0 error / 0 skipped）、Release 边界、Debug 候选身份、8 Schema/catalog、结果合同、Go server/gateway、87 项脚本测试（86 通过、1 项按设计跳过）和 P3 38 项测试通过；部署修复另经 PowerShell/Bash 语法、catalog、Go 全量和部署安全 15/15 复核；自动 Verifier 的状态/探针/并发失败路径及 AI 实时打断 300ms 合同均纳入回归 | 不能证明修复后的 0.8.0 公网重试或正向真机路径已完成 |

### 6.1 云端工件与 0.7.0 负向真机证据

- ［KNOWN｜HIGH］GitHub commit `708ced3bb4939148c3cc9817849ab10cd4ea3317` 产出的
  `ANEB-Probe-0.5.11-codex-debug.apk` 已按 manifest/checksum 核对后安装到 P40；包名为
  `com.aneb.probe.codex`，SHA-256 为
  `7586B874EE53DFDB75C0E56EE0B50F43CCA2D1480ECA01E39256CDCCC3AAA0CC`。
- ［KNOWN｜HIGH］E-01 仍为 0.7.0、`/serverinfo` 不含 `execution_capabilities` 时，真机 run
  `019f74f6-7e49-7623-938e-37044826b06e` 与
  `019f74f8-ac46-78f6-9a55-25c9d3511dc1` 均以 `receipt_missing` 拒绝；两条记录的
  score/grade 为 null、verdict/confidence 为 `INVALID`，且日志未进入 echo、token-sim 或 download
  业务阶段。这证明的是“不兼容节点零业务 fail closed”，不是 Quick 业务性能。

### 6.2 首次 0.8.0 切换、回滚与证据边界

- ［KNOWN｜HIGH］首次受保护切换中，0.8.0 的精确能力回执、manifest/Profile 哈希、既有 TCP/UDP
  与合成弱网 smoke 均通过；旧部署器随后在 live 基线比较中报告全防火墙指纹变化，并自动恢复
  0.7.0。回滚后 0.7.0 header/body 与全量旧端点 smoke 再次通过。结果是“候选曾通过 smoke 后被
  回滚”，不是“0.8.0 已部署”。
- ［COMPUTED｜HIGH］E-01 上间隔约两秒连续采集时，raw iptables v4/v6 SHA 会变化；仅移除
  `iptables-save`/`ip6tables-save` 自动生成的 `Generated/Completed` 运行时间后，v4/v6 指纹稳定。
  当前 0.7.0 为 `active`，PID `3775079`，二进制 SHA-256 前缀 `9208aba2…`；三次连续规范化
  `full/v4/v6/nft/Docker` 与 `eth0` qdisc 指纹逐项相同，且没有 `wg-aneb-lab`、`ifb-aneb-lab`、
  `ANEB_LAB` 规则或 UDP 51820 监听。
- ［INFERRED｜MED］旧脚本的 mismatch 与每次采集写入墙钟时间完全一致，因此时间噪声是本次误报
  的直接解释；但旧部署器没有保留可用于事后语义差异比较的切换前 raw 快照，所以不能仅凭当前
  稳定性追溯证明切换窗口绝无并发防火墙语义变化。保守处理是先独立交接，再重试完整门禁。
- ［KNOWN｜HIGH］最终修复提交 `403017928824cf730f944d8606431a8108290808` 在初始修复
  `1fbdb1f058010fb4df0a1e1267a4ba65ce0ac185` 上继续 fail closed；它只规范化上述运行时间，并且
  工具版本、backend、warning、链、policy、规则、规则注释与 nft 内容均参与比较。脚本现在在切换
  前同组连续采集两份快照并要求完全一致，记录 v4/v6/nft/Docker/full 分项指纹；采集失败、字段异常、
  基线不稳定或任一语义差异仍 fail closed。该修复已通过 15/15 部署安全测试和全仓质量门，但尚未
  用于第二次 E-01 切换。对应 GitHub Actions run `29644046419` 的 6 个 jobs 全部成功。

## 7. 发布与部署门禁

- ［KNOWN｜HIGH］共享资源联调只使用 commit `708ced3` 的 GitHub CI 工件；已安装 APK 的 SHA-256
  必须为 `7586B874EE53DFDB75C0E56EE0B50F43CCA2D1480ECA01E39256CDCCC3AAA0CC`。先前本地候选
  `B4CC8A69…` 不是本次真机工件，不得把两者混写或用本地摘要替代云端身份。
- ［KNOWN｜HIGH］首次质量门中的凭据扫描覆盖 571 个 Git 跟踪文件和 0 个暂存路径，因此不能作为
  新增文件的发布证据。全部 48 个预期变更进入暂存区后已单独重跑：覆盖 587 个跟踪文件并重扫
  48 个暂存路径，结果 PASS；`git diff --cached --check` 同时通过。
- ［KNOWN｜HIGH］本次事故 lease 已结束操作并处于“待交接”；不得复用。必须由另一固定角色或
  受限自动 `Verifier` 独立只读复核并释放为“空闲”，随后 Codex 生成全新 128-bit lease 自动接管，再把同一 lease 传给修复后的
  部署脚本。脚本只读断言“进行中/Codex/E-01/同一 lease”，冻结健康 0.7.0 与同组双快照共享主机
  基线，并按 `TEST_SERVER_CAPABILITIES.md` 的切换/强回滚门禁从头执行。
- ［KNOWN｜HIGH］0.8.0 公网回执和既有端点 smoke 全部通过后，才允许在 P40 上做一次 Quick
  正向验收和至少一次不兼容回执的零业务请求验收。
- ［KNOWN｜HIGH］任何清理或回滚状态不明时必须置为“异常锁定”，不得继续测试或宣称完成。

## 8. 离线复核入口

```powershell
python scripts/verify_spec_catalog.py
python -m unittest discover -s scripts/tests -v
Push-Location tools/aneb-ai-behavior-model; $env:PYTHONPATH = "src"; python -m unittest discover -s tests -v; Pop-Location
Push-Location server; go test ./...; Pop-Location
Push-Location app; .\gradlew.bat ':probe:testDebugUnitTest'; Pop-Location
powershell -ExecutionPolicy Bypass -File scripts/quality_gate.ps1
```

［KNOWN｜HIGH］上述全量质量门、87 项脚本测试与暂存后凭据扫描已按本记录执行并通过；commit
`708ced3` 的 GitHub CI 和精确云端 APK 负向真机证据也已另行核对。当前缺口仍是：独立交接释放、
用 `4030179` 最终修复后的部署器在新 lease 下重新完成 0.8.0 公网切换，以及同一云端 APK 的 P40
Quick 正向 run。缺口完成前不得把本记录改写成已部署或完整验收。
