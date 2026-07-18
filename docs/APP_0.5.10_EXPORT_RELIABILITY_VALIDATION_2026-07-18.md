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

全量门禁：90 suites / 551 JVM tests / 0 failures / 0 errors / 0 skipped；Android Lint 0 errors / 11 dependency-SDK notices；release boundary、8 Schema/catalog、12 项测量/结果测试、6 项候选打包测试、31 项行为模型、Go server 与 gateway 全部 PASS。Profile catalog 1.3.1 只同步 P1 0.5.10 消费者版本，不改任何 Profile。

## 4. 当前产物与设备边界

- 包名：`com.aneb.probe.codex`
- 版本：`0.5.10-codex`，versionCode 42
- APK：61,850,452 bytes
- APK SHA-256：`82A1A3C45A3ECD5C695417F65BFCF67311C94A571467EFB2E79525C8EBE5BB1F`
- Android Debug 证书 SHA-256：`6644DDCF728B5BC9EFAA07361FC828B9F419D977681000F2E4136C24340B89D9`

［KNOWN｜HIGH］该精确二进制尚未在 P40 安装。Experience Lab 已退出 WireGuard，但没有明确交还共享手机；Codex 继续遵守“不抢手机”的协调规则。0.5.9 的蜂窝 Quick/JSONL 是有效历史证据，但不冒充 0.5.10 真机证据。收到明确释放后，下一步是安装这一 SHA、验证首次启动、保存单条 JSONL、混合 v1+v2 批量导出，并在最后主动退出 ANEB。
