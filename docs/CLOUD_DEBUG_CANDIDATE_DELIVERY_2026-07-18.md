# ANEB 云端 Debug 候选交付闭环

> 日期：2026-07-18（Asia/Shanghai）
> 当前状态：本地实现、真实 APK 打包、工作流静态检查和全量质量门已通过；真实 GitHub Actions run 待提交触发后回填。

## 1. 反方事实

- ［KNOWN｜HIGH］旧 GitHub CI 只监听 `main`，Codex 云端分支推送不会触发。
- ［KNOWN｜HIGH］旧 Android job 虽执行测试、Lint 和 `assembleDebug`，但不上传 APK，也没有包名、版本、签名或 SHA-256 清单。
- ［INFERRED｜HIGH］因此旧状态只能证明“云端可能编译”，不能证明 Product Owner 能从云端拿到正确且可核验的安装文件。

## 2. 新交付合同

`main` 和 `codex/**` push、面向 main 的 PR、手动触发都会进入 CI。候选 APK 只在以下前置 job 全绿后构建：

1. Profile catalog、结果 Schema 和 18 项脚本测试；
2. Go 测试服务器；
3. 专用网关及隔离网络命名空间集成；
4. AI 行为模型；
5. Android 551 项 JVM 测试、Lint、Debug 构建及 Debug/Release 组件边界。

打包器随后 fail-closed 校验：APK ZIP 完整且含 Manifest/classes、Gradle 输出与 APK 的包名/版本一致、单一 universal APK、包名必须为 `com.aneb.probe.codex`、版本必须以 `-codex` 结尾、签名必须是 Android Debug。输出目录非空时拒绝覆盖。

## 3. 交付内容

- `ANEB-Probe-<version>-debug.apk`
- `build-manifest.json`
- `checksums.sha256`
- `ANEB-安装说明.txt`

非 PR 构建还使用 GitHub artifact attestation 绑定工作流来源；工件保留 30 天。该能力依据 GitHub 官方[工作流工件](https://docs.github.com/en/actions/tutorials/store-and-share-data)与[构建来源证明](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)文档实现。

## 4. 已取得的本地证据

- actionlint 1.7.12：工作流零错误；
- scripts tests：18/18 PASS，其中候选打包 6/6；
- 真实本地 0.5.10 APK：包名、versionCode 42、`0.5.10-codex`、Debug 证书、ZIP 和 Gradle 身份交叉验证 PASS；
- 生成 APK SHA-256：`82A1A3C45A3ECD5C695417F65BFCF67311C94A571467EFB2E79525C8EBE5BB1F`；
- 全量 `scripts/quality_gate.ps1` 已实际调用候选打包器并 PASS。

## 5. 尚未取得的证据

［KNOWN｜HIGH］尚未触发包含本次工作流修改的 GitHub run，因此不能宣称云端工件或 attestation 已成功生成。提交并推送后必须核对：所有 job 结论、工件文件列表、云端 APK 身份/哈希、attestation 是否存在；失败则继续修复，不把本地模拟冒充云端成功。
