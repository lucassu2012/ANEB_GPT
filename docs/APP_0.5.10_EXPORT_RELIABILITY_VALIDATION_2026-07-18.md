# ANEB App 0.5.10 · 系统下载导出可靠性验证

> 日期：2026-07-18（Asia/Shanghai）
> 范围：P1a 保存/分享/批量导出的 MediaStore 文件生命周期；不改测量、评分或结果合同。

## 1. 反方风险

- ［KNOWN｜HIGH］旧实现创建 Downloads pending 行后，如果输出流为空会直接返回失败但不删除该行，可能留下半成品。
- ［KNOWN｜HIGH］旧实现没有检查完成更新的影响行数；完成标记失败时仍可能返回成功。
- ［INFERRED｜HIGH］公开发布后，这类低概率故障会直接表现为“下载目录里有坏文件”或“APP 说成功但用户找不到可用证据”，属于产品可靠性缺陷，不是测量算法问题。

## 2. 0.5.10 合同

导出只有在以下三步全部成功后才返回成功：

1. 创建 `IS_PENDING=1` 的系统下载行；
2. 完整写入 UTF-8 内容并关闭输出流；
3. 完成更新影响且仅影响该 URI 对应的一行。

创建后的打开、写入或完成失败均尝试删除 pending 行。删除失败时不声称已清理，而是返回原 URI 与 `cleanup_failed`，供日志和后续诊断。创建本身失败时不执行无意义清理。

该合同由 `Exporter.exportWithSink` 承载，Android MediaStore 只是生产 sink；故障注入测试不需要伪造 Android 系统。结果正文、规范化摘要、Schema、Profile、评分和 UI 成功文案均未改变。

## 3. 自动化证据

新增 6 个生命周期测试：

- UTF-8 成功写入、完成一次且不清理；
- 创建失败不清理；
- 输出流为空时清理；
- 写入异常时清理；
- 完成更新失败时不得报成功并清理；
- 清理失败时保留 URI 与明确错误。

全量门禁：90 suites / 551 JVM tests / 0 failures / 0 errors / 0 skipped；Android Lint 0 errors / 11 dependency-SDK notices；release boundary、8 Schema/catalog、12 项测量/结果测试、8 项候选打包测试、6 项凭据安全测试、31 项行为模型、Go server 与 gateway 全部 PASS。Profile catalog 1.3.1 只同步 P1 0.5.10 消费者版本，不改任何 Profile。

## 4. 本地产物边界

- 包名：`com.aneb.probe.codex`
- 版本：`0.5.10-codex`，versionCode 42
- APK：61,850,452 bytes
- APK SHA-256：`82A1A3C45A3ECD5C695417F65BFCF67311C94A571467EFB2E79525C8EBE5BB1F`
- Android Debug 证书 SHA-256：`6644DDCF728B5BC9EFAA07361FC828B9F419D977681000F2E4136C24340B89D9`

［KNOWN｜HIGH］以上是本机构建，不是本轮 P40 安装对象。GitHub runner 使用另一把临时 Debug key，云端候选与本机 APK 的哈希和签名必然不同；真机验收必须锁定实际安装的云端 SHA，不能用本机近似二进制代替。

## 5. 云端候选与数据保全

GitHub Actions run `29635434193` 从 commit `51fdd7c81f1f63a7202dd40d8ce86f5931d0d1a2` 成功生成工件 `8427011992`。云端 APK SHA-256 为 `49244B3157FCC47D54EDA61A51EAF4B69A71BD2B95314BAE54E327CE8B0F6D85`，身份为 `com.aneb.probe.codex` / `0.5.10-codex` / versionCode 42；attestation `35945988`、Rekor `2193995642` 已离线核验。

- ［KNOWN｜HIGH］由于旧包与云端候选的 Debug 签名不同，本次按“备份普通数据 → 卸载旧包 → 安装云端候选 → 恢复普通数据”执行；不是 Android 原位升级，也不是 Room schema migration。安全偏好/API key 未恢复。
- ［KNOWN｜HIGH］恢复后 Room 仍为 v19，integrity check 为 OK；`result_envelope=36`、`test_run=10`。

## 6. P40 导出证据

- ［KNOWN｜HIGH］混合批量文件 `aneb_results_32_of_36_20260718_161911.jsonl` 为 1,554,624 bytes，SHA-256 `F026CC05E057CF4A04035B94BC1EDE11EB909A18224D9677E2C9408F7DAD10C4`。离线 verifier 通过 32 个文档：v1=27、v2=5；Token=10、AI 实时=14、网络综合=8；唯一 run id=32、重复=0。其余 4 条完整性异常被透明拒绝，没有重算、修补或混入。
- ［KNOWN｜HIGH］单条 v2 文件 `aneb_result_ai_realtime_simulation_019f7377_20260718_162513.jsonl` 为 44,377 bytes，SHA-256 `FE964695E19997796F5FEB84E05F50FB69F61F2C6299FA0C577263E5198F7EA9`。run `019f7377-9a61-7db5-a8c4-1ac57de1a486` 通过 v2 verifier，且正文与批次对应行逐字节一致。
- ［KNOWN｜HIGH］批量与单条两条 MediaStore 记录均为 `is_pending=0`，证明两个成功路径已完成，不是半成品。

## 7. 剩余边界

- ［KNOWN｜HIGH］真机成功导出不证明创建、打开、写入、完成或清理失败分支都在 Android 系统上发生过；这些失败路径由 6 项自动化故障注入覆盖。
- ［KNOWN｜HIGH］云端产物是 `debug_non_release`，不具备正式发布签名、商店发布或无缝升级资格。
- ［KNOWN｜HIGH］本轮使用受控开发/ADB 路径；普通用户不依赖 ADB 的下载、系统安装、首次启动、测试、导出/分享整链仍未证明。
