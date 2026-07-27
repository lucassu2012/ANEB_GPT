# M0-EC3 网络综合 Quick 正负 READY 验收

> 验收日期：2026-07-27（Asia/Shanghai）
>
> 验收范围：P40 Pro + ANEB Codex Debug + E-01 `aneb-server/0.8.2` 的单节点 Network Quick 窄切片。
>
> 证据边界：这是 ANEB 到受控探针节点的一次应用层工程样本，不是运营商无线覆盖、IP 层丢包、第三方 AI 应用体验或正式 95% 网络质量基线。

## 1. 结案判断

- ［KNOWN｜HIGH］M0-EC3 的 EC3-01～EC3-10 已闭环：同一 source、同一 CI APK、同一签名、同一服务器二进制和同一 Profile/runtime 上，正向与负向均发布独立 `READY.json`，独立 release verifier 均为 `status=pass`、`reason_code=ok`。
- ［KNOWN｜HIGH］正向真实完成 echo/download/upload/ANEB2 UDP 四类业务；负向只从真实上游 `/serverinfo` 删除能力 receipt，客户端在首个业务请求前以 `receipt_missing` 拒绝，客户端与服务端业务产物均为零。
- ［KNOWN｜HIGH］权威正向结果为 `79/B`，但证据覆盖率只有 0.5，最终 verdict 为 `INCONCLUSIVE/LOW`。该分数不得写成当前网络已经达到正式质量目标。
- ［KNOWN｜HIGH］最终 P40、ADB reverse、采集器、负向代理、E-01 锁和远端临时标记均已清理；共享服务器身份与指纹未漂移。

## 2. 冻结候选与来源

| 项目 | 精确身份 |
|---|---|
| Git source | `ea9de17c2acea763513b144b4fb9942a3d54c5c6` (`Share negative proxy audit scope policy`) |
| GitHub Actions | run `30266912724`，7/7 jobs success |
| Artifact | artifact id `8653462642` |
| Android APK | SHA-256 `e1af670c5c95063a2be9d0acd3e1f138e643d13b625a0a7a5fa8e16eef63db0e`；`0.5.14-codex` / code 46 |
| Android signer | SHA-256 `0936cdcf4d46e61c0532ae1a35b378db2bed9629cce0bf7080badde8828df1f3` |
| Provenance | 独立 verifier PASS；候选、source、workflow、run 与 APK/signature 精确绑定 |
| Server | `aneb-server/0.8.2`；binary SHA-256 `62ff966bf396abe836c6179053ee549110e41e16af569cdeadc97535bc64c96e`；PID `1295423`；InvocationID `d975f7c374aa4ef3a490210d0a495e53` |
| Profile/runtime | `network_comprehensive_quick@1.2.0`；Profile SHA `15ae5187fac72d86b78ff89ad44d5a51706dc7c4e4cf01432f367acd9ed082cc`；runtime SHA `8981267030abd4cd95dabe3e3bff8d2af4b7de6b8659cc8c267c97f519cf2603` |

［KNOWN｜HIGH］候选修复后的 Network/Realtime 交叉回归为 189 tests PASS（另有 1 个既有平台 skip），GitHub CI 7/7；最终权威正负 READY 都绑定上述 `ea9de17` 候选，不混用后续文档提交生成的 APK。

## 3. 负向 READY

| 项目 | 结果 |
|---|---|
| Collection | `m0-ec3-network-quick-20260727T125716Z-5183afc555814bbfa060c5eeece0bdc0` |
| Run | `019fa3a7-b34a-7a0c-b45f-3e6e3d7b0d8c` |
| READY SHA-256 | `7fa7fb24214a5cbd892c9cc04ef5ba2374ca5816ac77d30aecd615bff6970878` |
| Manifest SHA-256 | `36308df65a853423761b629ae5d7bc3de6efbff525a99b2c00d24e6fb9c32ef8` |
| Verification report SHA-256 | `c361b9b0a7ad5e6920e108ab54b61adf34d859df7e6e0d45194ce6ecb7ac97dd` |
| Cross binding | `cross_bound=true`；`contract_status=rejected`；`terminal_status=contract_rejected` |

- ［KNOWN｜HIGH］客户端持久化 `receipt_missing`；app request attempts/successes、download/upload bytes、UDP sent/returned 全为 0。
- ［KNOWN｜HIGH］服务端 request-entry 审计中 download/echo/upload/udp_echo 全为 0；cross-bound verifier 把代理回执、Room INVALID 终态、服务端窗口与同一 run/Profile/runtime 绑定。
- ［KNOWN｜HIGH］负向没有修改 E-01；它只验证“能力 receipt 缺失时业务前 fail closed”，不证明一般网络故障处理全部正确。

## 4. 正向 READY 与本次工程样本

| 项目 | 结果 |
|---|---|
| Collection | `m0-ec3-network-quick-20260727T134932Z-f2a017052c274dcd895e5e4edceec92d` |
| Run | `019fa3d7-8ab2-76eb-90bd-182a482b3c7f` |
| READY SHA-256 | `e153ee46f616e9c34f92b4c58443595d14954291f86ff6a40a9fe20f097c3837` |
| Manifest SHA-256 | `2f877ebf30f1619422b5de9d4fce487367022b7afcbeb6b787bd13dc5f030d0a` |
| Verification report SHA-256 | `7b1e213e15cecdc9455ea053879ea7559d78decfa8615eaf6284eaee0cd4ddbf` |
| Cross binding | `cross_bound=true`；`contract_status=authorized`；`terminal_status=completed` |

［KNOWN｜HIGH］客户端记录 44/44 应用请求成功、下载 `98,942,976` bytes、上传 `41,156,608` bytes、50/50 个同-run UDP 数据报返回。服务端独立审计记录 echo 45、download 4、upload 6、UDP 50，UDP 序号精确为 0..49、每包 256 bytes。

| 指标 | 本次值 | Profile 目标达标比例 |
|---|---:|---:|
| 下载持续有效速率 P05 | 61.88 Mbps | 100.0%（目标 ≥25 Mbps） |
| 上传持续有效速率 P05 | 39.90 Mbps | 100.0%（目标 ≥10 Mbps） |
| 空闲 RTT P95 | 148.27 ms | 90.0%（目标 ≤100 ms） |
| 负载 RTT P95 | 291.05 ms | 65.4%（目标 ≤200 ms） |
| 负载时延增量 | 198.87 ms | 61.5%（目标 ≤100 ms） |
| RTT 变化 | 56.09 ms | 90.0%（目标 ≤30 ms） |
| 应用请求失败率 | 0% | 100.0% |
| UDP 应用数据报未返回率 | 0% | 100.0% |

- ［INFERRED｜HIGH］该样本的容量不是主要问题，主要瓶颈是负载下时延增量与负载 RTT；如果用于交互型 AI 业务，优先优化队列管理和上/下行并发负载时的响应性，而不是继续提高峰值带宽。
- ［KNOWN｜HIGH］无线采样因 Android 权限未提供而缺失，RSRP/RSRQ/SINR 没有被补造；UDP 指标只代表应用数据报是否返回，不能改写成 IP 层丢包率。

## 5. 失败发现与纠正记录

1. ［KNOWN｜HIGH］首次真机采集暴露 Network publisher 误用 Realtime manifest schema；早先“Windows 文件锁”判断没有证据支持，已撤回并用 schema 参数化修正。
2. ［KNOWN｜HIGH］负向代理有两份重复的 audit-scope 白名单：session 层和 HTTPS fetcher 层先后拒绝合法 `network_run`。两处已收敛为单一不可变 `ALLOWED_AUDIT_SCOPES`，并补充真实 fetcher 层红绿回归。
3. ［KNOWN｜HIGH］一次受保护换装脚本把 ADB 正常进度 stderr 当成终止错误，导致包短时处于卸载状态；现场立即 fail closed，重新安装并按 5 个文件 SHA 精确恢复 Room/偏好数据，没有把该轮计入验收。
4. ［KNOWN｜HIGH］文档补录指标时误用普通 SQLite 连接读取已冻结的首份正向 bundle，触发自动 checkpoint：主 DB 字节变化且 WAL/SHM 被删除。独立 READY consumer 随后以 `manifest_file_binding_mismatch` 正确拒绝。该旧 collection `…T130040Z-e7f610…` 和 READY `14ea8c7f…a83b` 从此只保留为被事后污染的历史证据，不再是权威验收输入；没有猜测或重建原 WAL/SHM。
5. ［KNOWN｜HIGH］恢复采集第一次业务完成，但手机在 finally 时为 `Asleep + Keyguard showing`，焦点停在 NotificationShade，清理门以 `focus_component_invalid` 拒绝并且没有发布 READY。唤醒、dismiss keyguard、HOME 后 revision-4 PhoneGuard 恢复稳定基线，只做一次重试并得到本节的新权威 READY。

这些失败包只保留为诊断证据，最终正负 READY 均来自修复后同一 `ea9de17` 候选。

## 6. 最终清理与边界

- ［KNOWN｜HIGH］权威正向 bundle 内 `phone-postflight-receipt.json` 文件 SHA-256=`2efbbd94771fca87d9be30bdf5498c22cd2bdc51ec9f0ec269d06138f4fe7009`；其后独立 revision-4 PhoneGuard 命令 exit 0，stable state SHA-256=`277824515c65d20e6db2f3874ed4f938160dffde51dd7aac1bff148041c21198`。独立 receipt 的完整哈希没有被保留，故不补写。
- ［KNOWN｜HIGH］Huawei Launcher 前台；相关 PID/service/accessibility/VPN/tun=0；Wi-Fi on；`stayon=7`；`adb reverse --list` 为空；采集器/代理/ADB/SSH 子进程为 0。
- ［KNOWN｜HIGH］负向与权威正向远端锁 nonce 分别为 `5f02d4efe3f5404c80bef2269eb3e90f`、`eeac3cfc58334312892244c5904cf0b7`，均已释放；失败的首次恢复 nonce `abe304f93dac4c618f1c9f11c8f17b31` 也有独立释放回执。Docker、eth0 qdisc 与 firewall full 指纹保持冻结值。
- ［KNOWN｜HIGH］M0-EC3 只关闭 Network Quick 跨端执行合同。Network Standard/Recovery、正式重复性矩阵、蜂窝/点位/运营商覆盖、弱网标定和公开 Release 仍未完成。
- ［INFERRED｜HIGH］下一最小阶段是 S2：统一三族已经实证的机械采集/发布事务与 provenance 边界，同时继续保留 Token、Realtime、Network 各自独立的业务判定器。
