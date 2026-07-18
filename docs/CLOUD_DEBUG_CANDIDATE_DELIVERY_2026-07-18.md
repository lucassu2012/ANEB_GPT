# ANEB 云端 Debug 候选交付闭环

> 日期：2026-07-18（Asia/Shanghai）
> 当前状态：本地与 GitHub 云端交付链均已通过；云端 APK、清单、校验和与来源证明已独立复核。P40 同一云端二进制的用户路径验收仍待共享设备释放。

## 1. 反方事实

- ［KNOWN｜HIGH］旧 GitHub CI 只监听 `main`，Codex 云端分支推送不会触发。
- ［KNOWN｜HIGH］旧 Android job 虽执行测试、Lint 和 `assembleDebug`，但不上传 APK，也没有包名、版本、签名或 SHA-256 清单。
- ［INFERRED｜HIGH］因此旧状态只能证明“云端可能编译”，不能证明 Product Owner 能从云端拿到正确且可核验的安装文件。

## 2. 新交付合同

`main` 和 `codex/**` push、面向 main 的 PR、手动触发都会进入 CI。候选 APK 只在以下前置 job 全绿后构建：

1. Profile catalog、结果 Schema 和 20 项脚本测试；
2. Go 测试服务器；
3. 专用网关控制面、并发竞争与 Linux 构建；只有仓库配置外部固定 CA 叶证书密钥时，才额外执行隔离 TLS/netem 命名空间测试；
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
- scripts tests：20/20 PASS，其中测量/结果 12/12、候选打包 8/8；
- 真实本地 0.5.10 APK：包名、versionCode 42、`0.5.10-codex`、Debug 证书、ZIP 和 Gradle 身份交叉验证 PASS；
- 生成 APK SHA-256：`82A1A3C45A3ECD5C695417F65BFCF67311C94A571467EFB2E79525C8EBE5BB1F`；
- 全量 `scripts/quality_gate.ps1` 已实际调用候选打包器并 PASS。

## 5. 云端闭环证据

- ［KNOWN｜HIGH］成功 run：[`29633753923`](https://github.com/lucassu2012/ANEB_GPT/actions/runs/29633753923)，source commit `2dada77485891e117e448f58fa95020584c9d342`，分支 `codex/app-0.5.10-export-reliability`。
- ［KNOWN｜HIGH］五个 job 均成功：合同、Go 服务端、专用网关、行为模型、Android 候选。网关外部固定 CA 的隔离 TLS/netem 命名空间步骤因未配置 `ANEB_GATEWAY_TEST_CERT_PEM/KEY` 而明确跳过；控制面、竞争测试和 Linux 构建通过。该跳过项不是 PASS。
- ［KNOWN｜HIGH］工件 ID `8426436270`，名称 `aneb-probe-debug-2dada77485891e117e448f58fa95020584c9d342`，ZIP 大小 24,842,607 bytes，GitHub 工件摘要 `sha256:95570efa913f2d6efc5150ac404f81a69565c7e6fb9988216d9fea86cff589cc`，到期时间 `2026-08-17T06:26:44Z`。
- ［KNOWN｜HIGH］ZIP 文件集合精确为 APK、`build-manifest.json`、`checksums.sha256` 和中文安装说明；内部三份 SHA-256 全部匹配。
- ［KNOWN｜HIGH］云端 APK：58,053,434 bytes，SHA-256 `2C05E347E66CC2049292452745DD68B6EDF2CECE2CB8501D509C4B9A6653DED1`；包名 `com.aneb.probe.codex`，`versionCode=42`，`versionName=0.5.10-codex`，`minSdk=29`，`targetSdk=35`，Android Debug 证书 SHA-256 `8909F1E107AE2C74D6BE8711AEB249E8E9A4D8F8D6D7B6A8D941A65BD55A7D6E`。
- ［KNOWN｜HIGH］云端 APK 与本机 APK 的哈希/大小/Debug 证书不同，是干净 GitHub runner 使用独立临时 Debug keystore 的预期结果；两者都不是正式 Release。

## 6. 失败修复记录

- ［KNOWN｜HIGH］run `29632621959` 暴露合同 job 未安装 `jsonschema`，且把缺少外部固定 CA 叶证书误当作网关代码失败；依赖已补齐，外部证书测试改为显式条件边界。
- ［KNOWN｜HIGH］run `29632846984` 暴露 Linux runner 上 Gradle wrapper 不可执行；已提交可执行位。
- ［KNOWN｜HIGH］run `29632913335` 暴露干净云端构建未生成 Release 合并清单；候选 job 现在先执行 Release manifest 处理再验边界。
- ［KNOWN｜HIGH］run `29633286702` 暴露 Android build-tools 发现/`aapt2` 输出的跨平台差异；CI 与打包器固定 build-tools 35.0.0，并以独立字段 fail-closed 解析。新增两项回归后，候选打包测试由 6 项增至 8 项。

## 7. 来源证明独立复核

- ［KNOWN｜HIGH］GitHub attestation ID [`35942948`](https://github.com/lucassu2012/ANEB_GPT/attestations/35942948) 将云端 APK 精确 SHA-256 绑定到上述 commit、分支和 `.github/workflows/ci.yml`。
- ［KNOWN｜HIGH］证明使用 GitHub-hosted runner 和 Sigstore Public Good 实例，Rekor transparency log index 为 `2193564202`。
- ［KNOWN｜HIGH］下载公开 bundle 后，使用官方 GitHub CLI 2.96.0 离线执行 `gh attestation verify`，同时锁定 repository、workflow、source digest、source ref 并拒绝 self-hosted runner；退出码 0，验证通过。官方 CLI 下载包也已与 GitHub 发布页校验和匹配。

## 8. 剩余边界

- ［KNOWN｜HIGH］尚未在 P40 安装这一个云端 APK SHA；Experience Lab 未明确释放共享设备前，Codex 不抢占手机。
- ［KNOWN｜HIGH］该产物是 30 天保留的 `debug_non_release` 候选，不具备公开发布签名、商店发布或后续无缝升级资格。
