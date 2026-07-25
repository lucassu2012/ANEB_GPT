# M0-EC1 Token Quick 正负 READY 验收记录

> 验收日期：2026-07-25（Asia/Shanghai）
>
> 范围：`token_multimodal_quick@1.2.1`、P40 Pro、E-01 `aneb-server/0.8.0`、
> D-82/D-86/D-87 现场证据与 READY 发布合同。

## 1. 先给反方结论

- ［KNOWN｜HIGH］本记录只支持 **M0-EC1 Token Quick 窄切片的正负跨端执行合同验收完成**；
  不能据此声称全部 Profile 已统一、M0 全部完成、外场网络质量已达标、正式 Release 已发布，
  或真实 Kimi/DeepSeek/千问业务画像已经校准。
- ［KNOWN｜HIGH］正向与负向分别使用两个 clean commit 的 CI APK。两提交之间
  `app/`、`profiles/`、`spec/`、`tools/`、`server/`、`gateway/` 没有文件差异，但 CI APK
  的字节摘要和 Debug 签名不同。因此两条证据可以验证各自的合同闭环和 fail-closed 语义，
  不能冒充“同一二进制的严格 A/B 性能对比”。
- ［KNOWN｜HIGH］正向结果为 99.2/A，但覆盖率只有 0.15，冻结结论仍是
  `LOW/INCONCLUSIVE`；该分数不能扩大解释为网络或产品正式达标。
- ［KNOWN｜HIGH］P40 与 E-01 在两次运行后均完成受控清理；本记录不重新占用 P40，
  不触碰 E-01，也不使用已退役的共享状态文件作为授权。

## 2. 冻结版本与来源

| 对象 | 正向 | 负向 |
|---|---|---|
| source commit | `10927c197770fec02db8237d184f76fa0edc88f2` | `67eb66d3632f39f1445bb8785c047cc080f624a6` |
| GitHub Actions run | `30124854408` | `30162011890` |
| App 版本 | `0.5.12-codex` / code 44 | `0.5.12-codex` / code 44 |
| APK SHA-256 | `aeb429f28c057853939a4ebe957c2d9abd8ec287809ad7a029dfd32a959205fd` | `f86aef14c086acb0dc0343b6aec05239af9e40efdaa1bfabee2b8cacb6b5c26e` |
| signer SHA-256 | `c71831f1f390b4b06ff65037bcee31e24f3c9efd05b7ea5c540382236958a85e` | `04af1db86b405c7edc1f68091f791d1de584263bb54af9dd68643c23c9ca4784` |
| CI attestation SHA-256 | `43b9c0a9baf6a72ccb8bd89a69f67b4af981e8cee342a6c5ae18f127bd9a602b` | `e0ca7b9d4aa1a57dd962daa5438eba90b9bcacf3380a35eb216e99632f2dffd4` |
| CI build manifest SHA-256 | `d6bacce8c54658723f721b7a856c6e4b6f3505dee135db5a218d8b66f2dfcc6d` | `a6829204ed4ddcabff4b03f0bc9f628997f9c1f9945cc98ab8ea0ea531c7a5fe` |
| CI checksums SHA-256 | `b2438e286047b0bd33830381521dd82a36aabdadcca7c4e55f5079ef8226f6fa` | `74fa5ea6bb40bdc19d28b9e9a413b62b81d28f75aa1adf614ba46d8d2170fb4d` |

- ［KNOWN｜HIGH］两条运行绑定同一 P40，设备序列号只以
  SHA-256 `b9c9d6…efb48` 进入可发布证据，不公开原始序列号。
- ［KNOWN｜HIGH］两条运行绑定同一 E-01 版本 `aneb-server/0.8.0` 和 live binary
  SHA-256 `fad6fdd53ebb73c63b2bf3b9f03106f1348626853cb344d72c3f6d08511fdce7`。
- ［COMPUTED｜HIGH］`git diff --name-only 10927c1..67eb66d -- app profiles spec tools server gateway`
  为空；差异位于采集、复核和测试工具，不在 App 业务引擎、Profile、模型或服务端运行源。

## 3. 正向 READY

| 证据 | 值 |
|---|---|
| run UUID | `019f95f9-a317-7766-9725-243b9660b9f1` |
| READY 文件 | `C:\Users\lucas\AppData\Local\ANEB\ValidationEvidence\d82-token-quick-20260724T211213Z-8c2beeef0b084815a95a74b782bcd8a5.READY.json` |
| READY SHA-256 | `d67efb7fa453fbec4e656aeb9d371b7d167c1da31b4046aaa9438522de58790b` |
| final manifest SHA-256 | `bd58c0db8419186d8f5a78b4cbaf208013750a1decf51c31b10fc6f26c6a4bc3` |
| verification report SHA-256 | `0e77f01362ed8aad60d11ae52c56e762a3eba5d27f820f74108d3ef1ca28f9a6` |
| request-entry | echo 20 / token-sim 3 / download 1；control 2 |
| 客户端产物 | 3 个成功任务、14 个类型化指标、26 个信封指标、8 条结论 |
| 评分 | 99.2 / A |
| 置信结论 | `LOW/INCONCLUSIVE`，coverage 0.15 |

- ［KNOWN｜HIGH］独立 release verifier 重新解析 READY、final manifest 与 verification
  report，并重新核对来源、设备、APK、服务器、Profile、请求计数、Room 结果、原始状态和时间链后 PASS。
- ［KNOWN｜HIGH］20/3/1 是服务端 request-entry coverage；它与同 run 客户端冻结结果共同支持
  Quick 执行合同闭环，但不单独证明每个网络请求的端到端业务成功。
- ［KNOWN｜HIGH］无线权限在本次运行中为 denied/unavailable，缺失项保持显式状态；不能从该运行
  推导蜂窝 RSRP/SINR 或射频质量结论。

## 4. 负向 READY

| 证据 | 值 |
|---|---|
| run UUID | `019f99c7-5b40-75ba-ad58-b5b522e9abf9` |
| READY 文件 | `C:\Users\lucas\AppData\Local\ANEB\ValidationEvidence\d82-token-quick-20260725T145528Z-2d95e650668640509909dea397b5a4e3.READY.json` |
| READY SHA-256 | `b801131d2b62743850be2d17f500fdd82aa1a52841409887b20a911d06e907af` |
| final manifest SHA-256 | `d7d71f51ce1547f071d95263a3ecc2fdd284a9da455e2f97f630c2baac949d94` |
| verification report SHA-256 | `d8f46828dc454f87972b51f8cdddb76a0aed6b7b3633a26bbeb0144251d2e907` |
| execution mode | `negative_receipt_missing` |
| 机器原因 | `receipt_missing` |
| 请求 | control 1 / business 0 |
| 客户端业务产物 | task 0 / KPI 0 / artifact 0 / network score 0 |
| 评分 | score/grade `null` |

- ［KNOWN｜HIGH］一次性 loopback 代理只从真实上游回执删除
  `execution_capabilities`；E-01 没有被降级或修改。
- ［KNOWN｜HIGH］独立 verifier 重算 12 份原始代理证据，并确认
  `client_delivery_proven=false`、业务入口为零、客户端业务产物为零、机器原因稳定。
- ［KNOWN｜HIGH］生命周期日志只有
  `START → RADIO(permission_denied) → DB_WRITE(ok=true) → CONTRACT(rejected, receipt_missing) → END(contract_rejected)`；
  拒绝结果只持久化一次。

## 5. Fail-closed 门禁实际拦截的问题

1. ［KNOWN｜HIGH］Huawei `dumpsys window` 可同时出现陈旧的重复 WMS 段；前台权威来源改为
   ActivityManager 的 focused/resumed pair，WMS 只保留为原始旁证。修复提交
   `b39815fbc212fb42800e11f33cb46a8a78c79fbd`，CI run `30160113019` 全绿。
2. ［KNOWN｜HIGH］首轮负向业务和清理均成功，但 verifier 把
   `adb reverse --list` 的 Huawei/Windows transport label `UsbFfs` 错当设备序列号摘要，
   因 `negative_proxy_evidence_invalid` 拒绝发布 READY。失败证据保留在
   `d82-token-quick-20260725T140026Z-a3d56c5f3213488c8eb0e2df33cc9c08.verification-failed.partial`。
3. ［KNOWN｜HIGH］提交 `67eb66d3632f39f1445bb8785c047cc080f624a6`
   将该字段改为 `adb_transport_label_sha256`；设备身份继续由 device policy、preflight、
   device verifier 和 collector plan 独立绑定。负向代理定向测试 25/25、collector 89/89、
   raw 41/41、bundle 118/118、release 27/27 通过，同提交 CI 7/7 jobs 全绿。

## 6. 清理与共享资源结论

- ［KNOWN｜HIGH］最终 P40 PhoneGuard receipt 为
  `f1614e31abc84589f2373cfeba4b1da9b6bfb6b84dd7b5f8ffd405d80745bcef`，
  stable state 为 `277824515c65d20e6db2f3874ed4f938160dffde51dd7aac1bff148041c21198`。
- ［KNOWN｜HIGH］结束现场为 Huawei Launcher；本轮 ANEB、代理、VPN、抓包相关
  PID/service/accessibility/VPN/tun 均为 0，Wi-Fi 开启，`stayon=7` 已恢复。
- ［KNOWN｜HIGH］ADB reverse 已精确清除；E-01 临时 marker 不存在，远端锁以 nonce
  `4dba7c48862e482885133a9c329aa40c` 验证释放。

## 7. 里程碑裁定与下一门

- ［KNOWN｜HIGH］EC1-01～EC1-10 在本窄切片内均有可复核证据，M0-EC1 Token Quick
  正负跨端合同可标记完成。
- ［KNOWN｜HIGH］M0 总体仍是部分完成：其余 Published Profile 尚未形成等价的 P1/P2/P3
  通用执行合同；M2 外场、真实授权业务画像、正式签名 Release 和普通用户非 ADB 发布链仍未完成。
- ［KNOWN｜HIGH］下一阶段不重跑已闭环的 Token Quick；优先继续计划架构中的下一项未完成门。
  A6 盲审受 D-110 撤回约束，在新的 v3 neutral package 原子发布并明确交接前保持纯离线暂停，
  禁止打开旧 material PNG 或 template-v2。
