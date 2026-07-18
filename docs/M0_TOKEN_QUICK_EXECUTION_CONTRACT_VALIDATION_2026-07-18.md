# M0-EC1 Token Quick 跨端执行合同与验证记录

> ［KNOWN｜HIGH］反方结论：M0-EC1 目前不能标记为“已发布”或“已部署”。P1、P2、P3 与部署
> 脚本与全仓质量门的离线验证已通过，但公网联调尚未完成；E-01 继续运行 0.7.0，本记录没有使用
> P40 Pro、VPN、PCAPdroid、E-01 或阿里云防火墙。

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
| P1 / ANEB Probe Android | 0.5.11-codex，code 43，Room v19 | ［KNOWN｜HIGH］97 suites / 577 JVM tests，0 failure / 0 error / 0 skipped；assembleDebug 与 Lint 通过；尚未真机安装 |
| P2 / aneb-server | 0.8.0 | ［KNOWN｜HIGH］离线测试通过；尚未部署到 E-01 |
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
| 部署脚本安全与候选回执 smoke | ［KNOWN｜HIGH］离线测试通过 | 切换脚本包含候选 Profile、精确回执检查与失败回滚门禁 | 不能证明公网切换或回滚已实际执行 |
| P1 解析、能力门禁与零业务请求路径 | ［KNOWN｜HIGH］26 项执行合同定向测试通过，覆盖严格解析、精确旧版白名单、运行包路径绑定与 fail-closed；全量 97 suites / 577 tests，0 failure / 0 error / 0 skipped；assembleDebug、Lint 0 error | 缺失/冲突回执在控制面后、首个业务请求前拒绝；Quick 运行包不能被其他 variant 的自洽文件替换；合成 transport 的业务请求数为 0 | 不能证明 E-01 公网或 P40 真机路径 |
| P40 Pro / E-01 真机联调 | ［KNOWN｜HIGH］本切片未执行 | 没有占用共享测试资源 | 不能声称真机、公网、蜂窝或 Wi-Fi 已验收 |
| 全仓质量门 | ［KNOWN｜HIGH］2026-07-18 本地执行 PASS | Android 97 suites / 577 tests（0 failure / 0 error / 0 skipped）、Release 边界、Debug 候选身份、8 Schema/catalog、结果合同、Go server/gateway、60 项脚本测试（59 通过、1 项按设计跳过）和 P3 38 项测试在同一门禁中通过；全部 48 个预期变更暂存后，凭据扫描覆盖 587 个跟踪文件并重扫 48 个暂存路径 | 不能证明 GitHub CI、公网部署或真机路径 |

## 7. 发布与部署门禁

- ［KNOWN｜HIGH］Android 单元/集成测试、跨语言 Quick 哈希一致性、assembleDebug、Lint 和仓库全量
  `quality_gate` 已完成，形成可进入共享资源联调的 0.5.11 本地候选 APK；APK 大小为 61,993,252 bytes，
  SHA-256 为 `B4CC8A694BDE245AB99CE673A30089F1671DE4097AE4ADC969E587175F1DE2F9`。该身份只绑定本地候选，
  不代表 GitHub CI 工件、E-01 部署版本或 P40 已安装版本。
- ［KNOWN｜HIGH］首次质量门中的凭据扫描覆盖 571 个 Git 跟踪文件和 0 个暂存路径，因此不能作为
  新增文件的发布证据。全部 48 个预期变更进入暂存区后已单独重跑：覆盖 587 个跟踪文件并重扫
  48 个暂存路径，结果 PASS；`git diff --cached --check` 同时通过。
- ［KNOWN｜HIGH］部署 0.8.0 前必须用全新 128-bit lease 按 `SHARED_TEST_STATUS.md` 自动接管，
  再把同一 lease 传给部署脚本。脚本只读断言“进行中/Codex/E-01/同一 lease”，不会提前交接；
  随后精确冻结 E-01 0.7.0 header/body、二进制与共享主机指纹，并按
  `TEST_SERVER_CAPABILITIES.md` 的切换/强回滚门禁执行。
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

［KNOWN｜HIGH］上述全量质量门与暂存后凭据扫描已按本记录执行并通过；该结果只证明当前本地候选
的离线一致性与本次暂存范围未命中高置信凭据规则。GitHub CI、E-01 公网与 P40 真机证据必须另行记录。
