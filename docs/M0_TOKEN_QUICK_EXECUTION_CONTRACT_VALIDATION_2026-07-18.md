# M0-EC1 Token Quick 跨端执行合同与验证记录

> ［KNOWN｜HIGH］D-80 已于 2026-07-19 取代本文中的共享状态、lease、待交接和受限 Verifier
> 操作规则；这些词在事故叙述中仅表示历史事实，不能再作为当前门禁。

> ［KNOWN｜HIGH］反方结论：M0-EC1 仍不能标记为“跨端正向验收完成”，但 E-01 服务端切换
> 子阶段已经完成，不能继续写“0.8.0 尚未部署”。commit `49095c0` 的六个 GitHub CI job 已成功，
> 0.5.12/code 44 云端 APK 已核对但尚未安装到 P40。E-01 当前运行 0.8.0；终态 success 证据和
> 独立锁内验后检查支持这一部署状态，同时原部署进程 rc=99 必须保留为 watchdog 清理竞态误报，
> 不能改写为命令返回成功。剩余门禁是 P40 0.5.12 正/负向 run、同-run双 barrier 审计和结束清理。

## 1. 目标与非目标

- ［KNOWN｜HIGH］本切片只解决一个问题：P1 在执行 Token Quick 前，能否证明本地执行引擎、
  P2 节点能力和本次精确 Profile 是互相兼容的。
- ［KNOWN｜HIGH］目标 Profile 只有 `token_multimodal_quick@1.2.1`；历史 1.2.0 只声明了
  download 能力却未在 Quick 计划中选择返回附件，不能作为本轮正向验收对象；其余 11 个 Published
  Profile 保持既有兼容行为。
- ［KNOWN｜HIGH］本切片不修改业务指标、网络指标、质量目标、门限、AQS 权重、T4 否决、
  结论算法、Room schema 或结果合同。
- ［KNOWN｜HIGH］Profile 只能引用预定义原语和线路合同，不能下发任意 URL、脚本或命令。

## 2. 候选版本边界

| 单元 | 候选版本 | 当前状态 |
|---|---:|---|
| P1 / ANEB Probe Android | 0.5.12-codex，code 44，Room v19 | ［KNOWN｜HIGH］commit `49095c0314ac3900b6ed0c306d2eeaafc2edd87f` 的 GitHub Actions run `29659812753` 六个 job 成功；云端 APK SHA-256=`04853208A59E35906366A61A92251CDED8BEDEA307753A37CD14844926FAD7EA`，包名/版本/签名/zipalign 已核对。尚未安装到 P40，也未完成 0.8.0 正向 run；0.5.11 负向证据只保留为历史。 |
| P2 / aneb-server | 0.8.0 | ［KNOWN｜HIGH］E-01 当前 active；live binary SHA-256=`fad6fdd53ebb73c63b2bf3b9f03106f1348626853cb344d72c3f6d08511fdce7`，来源 commit=`49095c0314ac3900b6ed0c306d2eeaafc2edd87f`。能力回执、旧端点、合成弱网与 UDP smoke 通过；部署终态 success 证据闭合，锁内验后共享主机指纹一致且临时残留为 0。原部署进程 rc=99 是 success 证据提交后的 watchdog collect/stop 误报，不能写成 rc=0。 |
| P3 / aneb-ai-behavior-model | 0.3.1 | ［KNOWN｜HIGH］生成器按模型与 seed 可复现选择一个 1MiB 返回附件；本地全仓门禁中的 38 项行为模型测试通过 |
| catalog / Profile 治理 | 1.5.0 | ［KNOWN｜HIGH］索引 Quick 1.2.1，并登记/冻结 request-entry 精确计数证据合同；本地 catalog 门禁通过 |
| Token Quick Profile | 1.2.1 | ［KNOWN｜HIGH］manifest 绑定精确 Profile 与 runtime plan；task-0006 真实触发 1MiB download |
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
  `validated_profiles` 中精确声明 `token_multimodal_quick@1.2.1`。
- ［KNOWN｜HIGH］Profile 的 `canonical-json-sha256-v1` 摘要为
  `caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773`；回执中使用
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
6. ［KNOWN｜HIGH］0.5.12 为同一 Token run 的 reachability、能力回执及三项业务原语附加同一个
   `X-Aneb-Run-Id`；0.8.0 只记录固定 class/method/path 和净化后的 run ID，不记录 query、body、
   远端地址或非法原值。审计不得同步阻塞响应路径，丢弃必须可观测。

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
| P3 schema、生成器、catalog 与 manifest | ［KNOWN｜HIGH］定向生成器测试与最终全仓门禁均通过；行为模型 38/38 通过，catalog 报告 8 个 schema、2 个 Profile family、16 个 Profile、6 个运行包、6 个内嵌网络 Profile 与 4 个行为模型全部一致 | Quick 1.2.1 执行要求可生成、可校验，并从模型派生计划真实选择 1MiB 返回附件；旧 Profile 兼容，精确哈希已冻结 | 不能证明 Android 已正确阻断业务请求或 E-01 已执行 download |
| P2 能力加载、规范化哈希、回执与三项真实 handler | ［KNOWN｜HIGH］Go 离线测试与 E-01 live smoke 通过，当前 `/serverinfo` 精确返回 0.8.0 能力回执 | 0.8.0 能 fail closed，并能为精确 Quick 生成能力回执；E-01 当前已运行对应二进制 | 不能证明 P40 已调用 handler、接收完整响应或完成跨端正向验收 |
| 部署脚本安全与候选回执 smoke | ［KNOWN｜HIGH］第二次受锁公网切换已执行；success 终态证据闭合，锁内验后 live/共享主机/残留检查通过；原部署进程仍如实记录 rc=99 | 当前 E-01 0.8.0 部署状态、精确来源与切换前后共享主机指纹可审计；首次自动回滚路径也保留历史实测 | 不能把 rc=99 改写成部署命令成功，也不能据此声明 P40 正向 run 或完整 M0-EC1 已完成 |
| P1 解析、能力门禁与零业务请求路径 | ［KNOWN｜HIGH］0.5.11 历史门禁曾通过；0.5.12 的同-run传递定向测试及最终 Android 单测/构建/Lint/发布边界门禁均通过；本地 Debug 候选 SHA-256 为 `8CCBD5402352639B5E6F32A165D69888ABAFD6655F39E235410C3A2D624E7687`，但它不是云端工件 | 缺失/冲突回执在控制面后、首个业务请求前拒绝；Quick 运行包不能被其他 variant 的自洽文件替换；合成 transport 的业务请求数为 0；同一 Token run 可绑定服务端控制/业务日志 | 不能证明 E-01 公网或 P40 真机路径 |
| GitHub CI 与云端 APK | ［KNOWN｜HIGH］commit `49095c0` 的 run `29659812753` 六个 CI jobs 全部成功；0.5.12 APK 的 manifest/package/version/SHA/signature/zipalign 已独立核对 | 0.5.12 有精确可追溯云端候选，可进入签名兼容检查和受控真机安装 | 不能证明 APK 已安装到 P40、能原位升级现有包或正向 Quick 已验收 |
| P40 Pro / E-01 负向真机联调 | ［KNOWN｜HIGH］两次 Quick 客户端结果均 fail closed，持久化 INVALID/未评分且任务/KPI 字段为 0；界面原因指向能力回执缺失，但 retained result 未持久化机器 `reason_code` | P1 的客户端门禁会拒绝不兼容节点并且不产生客户端业务产物 | 现有包缺原始 `/serverinfo`、同 run 服务端访问计数/日志和 PCAP，不能单靠它证明目标必为 0.7 或服务端绝对零业务 HTTP 请求；也不能声称 0.8.0 正向路径已验收 |
| 全仓质量门 | ［KNOWN｜HIGH］2026-07-19 当前门禁 PASS：Android 单测/构建/Lint/发布边界、Profile/结果合同、server、gateway、137 项脚本测试（4 项因当前 Windows 不具备对应 Linux 原语而按设计跳过）及 38 项行为模型测试全部通过；599 个跟踪文件凭据扫描 PASS；watchdog 修复 commit `d0a904d` 的 GitHub run `29661388755` 六个 job 全绿 | 部署侧覆盖 clean commit 绑定构建、`GOFIPS140=off` 污染覆盖、严格 staged/live 回执、远端互斥、原子回滚、终态证据和 watchdog 最终状态判定 | 本地/CI 门禁不能替代 P40 正/负向跨端证据；新一键取证工具仍须完成故障注入与真机验收 |

### 6.1 云端工件与现场 0.7.0 语境下的客户端负向证据

- ［KNOWN｜HIGH］GitHub commit `708ced3bb4939148c3cc9817849ab10cd4ea3317` 产出的
  `ANEB-Probe-0.5.11-codex-debug.apk` 已按 manifest/checksum 核对后安装到 P40；包名为
  `com.aneb.probe.codex`，SHA-256 为
  `7586B874EE53DFDB75C0E56EE0B50F43CCA2D1480ECA01E39256CDCCC3AAA0CC`。
- ［KNOWN｜HIGH］联调现场观察到 E-01 为 0.7.0、`/serverinfo` 不含 `execution_capabilities` 时，真机 run
  `019f74f6-7e49-7623-938e-37044826b06e` 与
  `019f74f8-ac46-78f6-9a55-25c9d3511dc1` 均被客户端拒绝；两条记录的 score/grade 为 null、
  verdict/confidence 为 `INVALID`，任务/KPI 字段为 0，且没有客户端业务产物。retained result 没有
  `endpoint.server_version` 或机器 `reason_code`，证据包也没有原始 `/serverinfo`、服务端 access
  log/计数或 PCAP。因此它证明客户端 fail closed，但不能单靠现有包独立证明目标必为 0.7，或服务端
  绝对没有收到业务 HTTP 请求；这也不是 Quick 业务性能证据。

### 6.2 两次 0.8.0 切换与证据边界

- ［KNOWN｜HIGH］首次受保护切换中，0.8.0 的精确能力回执、manifest/Profile 哈希、既有 TCP/UDP
  与合成弱网 smoke 均通过；旧部署器随后在 live 基线比较中报告全防火墙指纹变化，并自动恢复
  0.7.0。回滚后 0.7.0 header/body 与全量旧端点 smoke 再次通过。结果是“候选曾通过 smoke 后被
  回滚”，不是“0.8.0 已部署”。
- ［KNOWN｜HIGH］第二次受保护切换使用 commit `49095c0314ac3900b6ed0c306d2eeaafc2edd87f`，
  live binary SHA-256=`fad6fdd53ebb73c63b2bf3b9f03106f1348626853cb344d72c3f6d08511fdce7`。
  `ANEB_DEPLOY_RESULT` 原样为 `status=failed exit_code=99 primary_reason=cleanup_failed`；因此不能说
  “部署命令成功”。但在该错误之前 success 终态证据已经提交，随后独立锁内验后确认当前 live 为精确
  0.8.0、共享主机 full/v4/v6/nft/Docker/qdisc 指纹与切换前一致、staging/watchdog/owned-path
  残留均为 0。二者共同支持“当前 0.8.0 已部署并运行”，不支持“P40 正向验收完成”。
- ［COMPUTED｜HIGH］E-01 上间隔约两秒连续采集时，raw iptables v4/v6 SHA 会变化；仅移除
  `iptables-save`/`ip6tables-save` 自动生成的 `Generated/Completed` 运行时间后，v4/v6 指纹稳定。
  2026-07-18 22:01 的 T+0/T+10秒 最终复核确认当前 0.7.0 为 `active`；六项完整 SHA-256 为：
  binary=`9208aba26f18ea00d18d1bbcf3f1c6f7042e66b341675a58048894b168ba6b5b`，
  qdisc=`e9455ff1a3a44f3b5979ee068f8c4e3fe90aa0ebdd30e89add8299403958cbac`，
  firewall full=`08e3d3dfeb9f3e4ddc69ba440c5af7697536b0d45c3016068b33cb9d36ab75dd`，
  v4=`66b46a501b972e9b8d3d7fa0ab38e9e2b0fb24f5e521f4c5ca11ef60a53a0100`，
  v6=`192a359dda179d478c0e99eb3b0817894794ce62495afd489ed12a5e433c395e`，
  nft=`dd5369267b8eb08ddfdfde3a0e1c57f034951d608c45ac1409ccdafc77024657`。
  当时没有 `wg-aneb-lab`、`ifb-aneb-lab`、`ANEB_LAB` 规则或 UDP 51820 监听；这组检查只能证明
  运行态无 Phase 0 残留，不能替代持久路径和 systemd enabled 状态复核。完整证据边界见
  `SHARED_RELEASE_VERIFIER_VALIDATION_2026-07-18.md`。
- ［INFERRED,post-hoc｜MED］旧脚本的 mismatch 与每次采集写入墙钟时间完全一致，因此时间噪声是本次误报
  的直接解释；但旧部署器没有保留可用于事后语义差异比较的切换前 raw 快照，所以不能仅凭当前
  稳定性追溯证明切换窗口绝无并发防火墙语义变化。保守处理是先确认实时现场干净，再重试完整门禁。
- ［KNOWN｜HIGH］首次事故的防火墙修复提交 `403017928824cf730f944d8606431a8108290808` 在初始修复
  `1fbdb1f058010fb4df0a1e1267a4ba65ce0ac185` 上继续 fail closed；它只规范化上述运行时间，并且
  工具版本、backend、warning、链、policy、规则、规则注释与 nft 内容均参与比较。脚本现在在切换
  前同组连续采集两份快照并要求完全一致，记录 v4/v6/nft/Docker/full 分项指纹；采集失败、字段异常、
  基线不稳定或任一语义差异仍 fail closed。该修复已用于第二次 E-01 切换。随后暴露的 rc=99 是
  transient watchdog 已被 systemd collect 后 `stop` 返回非零所致；当前本地修复改为核对
  `LoadState/ActiveState/Job` 最终状态，查询失败、active、failed 或 pending job 仍 fail closed。

## 7. 发布与部署门禁

- ［KNOWN｜HIGH］commit `708ced3` 的 0.5.11 工件只用于解释历史负向真机证据。下一次 P40 联调使用
  commit `49095c0` 的 0.5.12 云端候选，并先核对现有安装包与候选签名是否兼容；不能用本地摘要或旧
  0.5.11 身份替代云端 manifest、APK SHA-256 和签名证据。
- ［KNOWN｜HIGH］全部 44 个预期变更进入暂存区后，最终质量门中的凭据扫描覆盖 599 个 Git 跟踪文件并重扫
  44 个暂存路径，结果 PASS；`git diff --cached --check` 同时通过。扫描前发现的一个假私钥负向夹具字面量已改为
  运行时拼接并重新验证，未发现真实 GitHub Token、API key 或私钥。
- ［KNOWN｜HIGH］本次事故 lease 在 22:01 完成 T+0/T+10秒 独立只读复核属于历史证据；共享状态、
  lease 与 Verifier 流程已于 2026-07-19 退役。只有离线门禁全绿、P40 实时为干净桌面且准备实际切换时，
  才能操作 P40。E-01 已完成当前 0.8.0 切换；服务器未变化时不得为了追求 rc=0 再次部署。未来任何
  live 变更仍必须取得远端内核互斥锁并按 `TEST_SERVER_CAPABILITIES.md` 的完整切换/强回滚门禁执行。
- ［KNOWN｜HIGH］只读复核 0.8.0 公网回执和既有端点仍通过后，才允许在 P40 实时现场干净的前提下做一次 Quick
  正向验收和至少一次不兼容回执的零业务请求验收；若既有会话无法安全归属，不得擅自停止或覆盖。
- ［KNOWN｜HIGH］0.8.0 staging 必须来自精确 clean commit 的隔离 Git 快照；本地 provenance、
  `go version -m`、上传 manifest、staged/live receipt、安装后全量工件 manifest 与持久证据目录必须
  互相一致。远端部署必须持有全生命周期互斥锁；证据未原子完成、私钥清理不确定或回滚未恢复时均失败。
- ［KNOWN｜HIGH］正向/负向 run 必须同时保存原始 `/serverinfo`、机器 reason code、客户端冻结结果与
  D-81 服务端 request-entry 审计。审计使用与目标 run 两两不同的 start/end UUID；两个 `/serverinfo`
  barrier 分别携带 `X-Aneb-Audit-Role: window_start/window_end`。判定必须确认唯一双边界、同一
  `instance_id`、严格连续 `seq`、恰好一个且先于业务的 `capability`，并拒绝 drop、重启、缺号、乱序、
  并发窗口、合同外或未归因业务。判定器 PASS 只证明请求进入审计边界，不证明 handler、响应体或客户端
  接收成功；必须与同 run 客户端结果结合。没有完整双边证据时，不得把“客户端无业务产物”扩大为
  “服务端零请求”。
- ［KNOWN｜HIGH］任何清理或回滚状态不明时必须停止后续测试、报告实际残留，不得宣称完成；不得再写
  已退役的共享状态文件来代替现场清理。
- ［KNOWN｜HIGH］P40 run 结束后必须停止本轮全部相关 App、VPN、抓包与临时网络规则，恢复临时设置，
  返回 Huawei Launcher 并立即只读复核；E-01/阿里云仍须独立完成受保护预检、远端 `flock`、原子
  回滚保险丝与验后基线检查。

### 7.1 同-run审计操作顺序（D-81）

［KNOWN｜HIGH］以下三个 ID 必须是规范小写 UUID 且两两不同；`RUN_ID` 由本次 App 冻结结果读取，
不得复用旧 run。控制器必须自行生成本次 start/end ID，不能从旧证据复制。开始前冻结 journal cursor、
boot ID、systemd invocation ID、MainPID、节点身份和本地时间，并在整个窗口持有与部署互斥的 E-01
审计锁；同时保存两次 barrier 的原始响应头和 body：

```powershell
$StartBarrierId = [guid]::NewGuid().ToString().ToLowerInvariant()
$EndBarrierId = [guid]::NewGuid().ToString().ToLowerInvariant()

# 1. 在启动 App run 之前请求同一 E-01 节点：
curl.exe -fsS -D start-barrier.headers -o start-barrier.json `
  -H "X-Aneb-Run-Id: $StartBarrierId" `
  -H "X-Aneb-Audit-Role: window_start" `
  "$ServerBase/api/v1/serverinfo"

# 2. 完成且冻结一次 App run，随后从客户端结果读取其 RUN_ID。

# 3. App run 结束后立即关闭窗口：
curl.exe -fsS -D end-barrier.headers -o end-barrier.json `
  -H "X-Aneb-Run-Id: $EndBarrierId" `
  -H "X-Aneb-Audit-Role: window_end" `
  "$ServerBase/api/v1/serverinfo"

# 4. 从开始前 cursor 导出原始 `journalctl -o cat` 文本后判定：
python scripts/verify_token_run_audit.py token-run-audit.log `
  --run-id $RunId `
  --start-barrier-id $StartBarrierId `
  --barrier-id $EndBarrierId `
  --mode positive `
  --profile-contract token_multimodal_quick@1.2.1
```

［KNOWN｜HIGH］负向 run 将 `--mode` 改为 `negative`。任一 barrier HTTP/节点身份异常、日志导出不完整、
判定器非零退出、报告 schema/version 或 `evidence_scope=request_entry_coverage_only` 不匹配，或客户端结果缺失，
都不得写 PASS。`token-run-audit.log` 必须从 pre-start cursor 导出的原始 journald JSON 逐条提取 `MESSAGE`，
不能从终端复制粘贴；最终 manifest 同时哈希 pre-start receipt、raw JSON、派生日志、判定报告、原始
serverinfo/barrier 响应和客户端冻结结果。判定器本身不证明记录“新鲜”，缺上述 D-82 来源绑定时只能写
“给定日志内容通过”，不能写“本次现场验收通过”。

［KNOWN｜HIGH］正式 Token Quick 正向验收必须使用上述 `--profile-contract`；v2.1.0 报告须同时声明
`profile_contract=token_multimodal_quick@1.2.1`、`profile_contract_enforcement=positive_exact_business_counts`
和 `expected_business_counts={echo:20,token_sim:3,download:1}`，且
`profile_contract_definition_sha256` 必须等于本次 catalog 为
`spec/execution-contracts/token_multimodal_quick-1.2.1.request-entry.json` 冻结的
`canonical-json-sha256-v1` 摘要；原始文件字节摘要由 D-82 manifest 另行保存。未带参数的通用正向模式只证明三类入口均至少一次，
不能替代 Token Quick 的精确执行合同。负向模式携带同一参数时仍要求业务入口总数为 0，并在报告中声明
`profile_contract_enforcement=negative_zero_business`。

［KNOWN｜HIGH］候选构建和部署前 Go 测试还必须显式固定 `GOFIPS140=off`。宿主为 `latest` 时可生成不同
二进制；未记录或未覆盖该污染的 candidate/provenance 不可部署。

## 8. 离线复核入口

```powershell
python scripts/verify_spec_catalog.py
python -m unittest discover -s scripts/tests -v
Push-Location tools/aneb-ai-behavior-model; $env:PYTHONPATH = "src"; python -m unittest discover -s tests -v; Pop-Location
Push-Location server; go test ./...; Pop-Location
Push-Location app; .\gradlew.bat ':probe:testDebugUnitTest'; Pop-Location
powershell -ExecutionPolicy Bypass -File scripts/quality_gate.ps1
```

［KNOWN｜HIGH］commit `49095c0`、GitHub CI、0.5.12 云端 APK 身份核对和 E-01 0.8.0 服务端切换
均已完成。当前缺口仅剩：确认候选签名兼容，在 P40 实时干净现场完成 0.5.12 正/负向 run、D-82
来源绑定审计和结束清理。缺口完成前不得把本记录改写成完整跨端验收。
