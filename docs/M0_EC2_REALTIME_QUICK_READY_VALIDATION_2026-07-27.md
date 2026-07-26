# M0-EC2 AI 实时 Quick 正负 READY 验收

> 验收日期：2026-07-27（Asia/Shanghai）
>
> 验收范围：P40 Pro + ANEB Codex Debug + E-01 `aneb-server/0.8.1` 的单节点 AI 实时 Quick 窄切片。
>
> 证据边界：ANEB 模拟实时 AI 语音交互，不调用第三方 AI API；本文件不把一次 Quick 扩写为正式无线体验基线。

## 1. 结案判断

- ［KNOWN｜HIGH］M0-EC2 的 EC2-01～EC2-10 已在同一客户端提交、同一 CI APK、同一服务器二进制上闭环；正向与负向 collection 均发布独立 `READY.json`，独立 release verifier 均为 `status=pass`、`reason_code=ok`。
- ［KNOWN｜HIGH］正向业务链真实完成；负向只删除客户端可见的能力 receipt，E-01 未被修改，客户端在业务前以机器原因 `receipt_missing` 拒绝，服务端实时业务入口计数为 0。
- ［KNOWN｜HIGH］正向结果虽然显示 `100/A`，但 Profile 按证据覆盖率将结论限定为 `INCONCLUSIVE/LOW`、coverage `0.1`；不得表述为网络已达到正式 95% 质量基线。
- ［KNOWN｜HIGH］结束后 P40、ADB reverse、E-01 锁及共享主机指纹全部恢复干净。

## 2. 冻结候选与来源

| 项目 | 精确身份 |
|---|---|
| Git source | `fe60c1cc044a19ae2109847226a35d2326ada54e` (`Fix realtime negative terminal contract`) |
| GitHub Actions | run `30215857444`，7/7 jobs success |
| Artifact | `aneb-probe-debug-verified-fe60c1cc044a19ae2109847226a35d2326ada54e`，artifact id `8635893322` |
| Artifact archive digest | `sha256:28400f2f0e3f5efa0dead0dddacd81c089958c9b40629a344021a3e9056620fd` |
| Android APK | `ANEB-Probe-0.5.13-codex-debug.apk`，SHA-256 `3855b972d66597f4c9b15a0a5532696f5eab9a4b32397d322a648446efab4664`，58,185,230 bytes |
| Android signer | SHA-256 `c9be4b8f70b6621370daf7221e8f4a5d4dc857be2efe3443b38dde2b9e7fe639` |
| Provenance | 独立 verifier PASS；GitHub-hosted runner、SLSA v1、workflow `ci.yml`、run `30215857444` 与 source commit 精确绑定 |
| Server | `aneb-server/0.8.1`，binary SHA-256 `43e7dc1696f08ec3c460fe094f021274d54492612a910aee0c2db98c39445197` |
| Profile/runtime | `ai_realtime_voice_quick@1.1.1`；Profile SHA `701c43cb19644e732c59faa6141b5b8bbc069e6c2ef006c410ee2bc0b51b30f7`；runtime SHA `f2472d2faa7a3ab51582e1496a6925d106806fdd9747e097e20e38e921d9dc07` |

受保护 Debug-signer 迁移后，Room `user_version=19`、`integrity_check=ok`；17 个表计数保持，包含 `token_event=21900`、`radio_sample=4429`、`realtime_simulation_result=54`。

## 3. 正向 READY

| 项目 | 结果 |
|---|---|
| Collection | `m0-ec2-realtime-20260726T200635Z-8a02df3dc9ec4e6da5584bb70487be6a` |
| Run | `019fa00a-3e17-7c9d-959b-50aab47c1b91` |
| READY SHA-256 | `20486929a27f2b0e6efb1097312b0e1ab58dd6eb224dc0c4176c7f6d76e8c4ad` |
| Manifest SHA-256 | `362069c41ea16fae65504f331133cc1260acf57cd0cf6cb695793423c31a38a1` |
| Verification report SHA-256 | `dd60cd9e388d6a064ada524ba4f34a3305aae3840d3eb121a8a7d14a90ea1b04` |
| Collector/release | `status=pass`；`cleanup_failures=[]`；独立 release verifier PASS |

［KNOWN｜HIGH］单次 Quick 的可复算业务结果：

- 1 个会话、3/3 轮交互完成，持续约 25.6 秒；会话建立 636.1 ms。
- 响应超额时延 P95 67.8 ms；打断响应 P95 38.1 ms。
- 676 帧音频准时率 100%，卡顿率 0%；会话 RTT P95 34.5 ms，到达变化 `P95-P50` 1.9 ms，负载 RTT P95 49.9 ms。
- 持续净荷 P05：上行 0.257 Mbps、下行 0.384 Mbps。
- 无线层采样因 Android 权限不可用而为 0；这是 completeness 缺口，不得补造 RSRP/RSRQ/SINR。

## 4. 负向 READY

| 项目 | 结果 |
|---|---|
| Collection | `m0-ec2-realtime-20260726T200941Z-66a44f984e0f415bab296460022699b2` |
| Run | `019fa00d-17f3-71d3-b2d9-af2e9271c96d` |
| READY SHA-256 | `e48fa31ec66c6a09a3f15d923da9898575b413b60f5dda763f856e09317e979f` |
| Manifest SHA-256 | `613b4ee16172257df236701a489cf28e3be3b39092b49cdbd760113bb8725e57` |
| Verification report SHA-256 | `8aeb65b50c60902203a02ed0d71435ad23a74cab64c37770f5c2f726fa3d5949` |
| Collector/release | `status=pass`；`cleanup_failures=[]`；独立 release verifier PASS |

- ［KNOWN｜HIGH］客户端保留 invalid run，`invalid_reason=receipt_missing`，score/grade 为 `null`，score state 为 `suppressed_invalid`。
- ［KNOWN｜HIGH］Room 报告为 0 session、0 turn、0 downlink frame、0 loaded-RTT attempt；21 个类型化指标均为 missing，没有伪造业务结果。
- ［KNOWN｜HIGH］服务端审计为 control=1、business total=0、`realtime_sim=0`、unattributed business=0；cross-bound verifier 将代理回执、客户端拒绝态、Room 和 journal 绑定到同一 run。

## 5. 最终清理与共享主机保护

- ［KNOWN｜HIGH］最终 PhoneGuard receipt SHA-256：`16ac15cae3622bff4a346b8aebc1ceecb12f0156f0ed44aeefbef15a338595d5`；stable state SHA-256：`277824515c65d20e6db2f3874ed4f938160dffde51dd7aac1bff148041c21198`。
- ［KNOWN｜HIGH］Huawei Launcher 前台；ANEB/业务 App/WireGuard/PCAPdroid 相关 PID、active service、Accessibility、VPN/tun 均为 0；Wi-Fi on；`stayon=7`。
- ［KNOWN｜HIGH］`adb reverse --list` 为空；负向代理已退出。
- ［KNOWN｜HIGH］E-01 lock nonce `afc91243646343979d53e239b07ad08e` 已 `LOCK_RELEASED`；PID `495860`、systemd invocation、boot id、server binary、Docker、eth0 qdisc、firewall full/v4/v6/nft 均与进入前一致。

## 6. 限制与下一步

1. ［KNOWN｜HIGH］本结论只关闭 AI 实时 Quick 的执行合同，不关闭 Standard/Recovery、RTP/WebRTC、真实第三方 AI App 或正式无线体验基线。
2. ［KNOWN｜HIGH］Quick 只有 1 个会话和 3 轮，多个指标未达到 Profile 的最小样本数；`100/A` 必须与 `INCONCLUSIVE/LOW` 同时展示。
3. ［INFERRED｜HIGH］计划架构的下一最小闭环应为 M0-EC3 网络综合 Quick：沿用同一 clean-commit/CI/provenance、正负 READY、Room/服务端同-run 与精确清理框架，不重新开发已结案的 EC1/EC2。
