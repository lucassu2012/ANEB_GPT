# ANEB 云端 Debug 候选交付闭环

> 日期：2026-07-18（Asia/Shanghai）
> 当前状态：本地与 GitHub 云端交付链均已通过；最新云端 APK、清单、校验和与来源证明已独立复核，并已在 P40 完成跨 Debug 签名的数据保全、单条与混合批量导出验收。非 ADB 用户安装链与正式签名 Release 仍未完成。

## 1. 反方事实

- ［KNOWN｜HIGH］旧 GitHub CI 只监听 `main`，Codex 云端分支推送不会触发。
- ［KNOWN｜HIGH］旧 Android job 虽执行测试、Lint 和 `assembleDebug`，但不上传 APK，也没有包名、版本、签名或 SHA-256 清单。
- ［INFERRED｜HIGH］因此旧状态只能证明“云端可能编译”，不能证明 Product Owner 能从云端拿到正确且可核验的安装文件。

## 2. 新交付合同

`main` 和 `codex/**` push、面向 main 的 PR、手动触发都会进入 CI。候选 APK 只在以下前置 job 全绿后构建：

1. Git 跟踪源码与暂存区 blob 的高置信凭据扫描；
2. Profile catalog、结果 Schema 和 26 项脚本测试，其中测量/结果 12 项、候选打包 8 项、凭据安全 6 项；
3. Go 测试服务器；
4. 专用网关控制面、并发竞争与 Linux 构建；只有仓库配置外部固定 CA 叶证书密钥时，才额外执行隔离 TLS/netem 命名空间测试；
5. AI 行为模型；
6. Android 551 项 JVM 测试、Lint、Debug 构建及 Debug/Release 组件边界。

打包器随后 fail-closed 校验：APK ZIP 完整且含 Manifest/classes、Gradle 输出与 APK 的包名/版本一致、单一 universal APK、包名必须为 `com.aneb.probe.codex`、版本必须以 `-codex` 结尾、签名必须是 Android Debug。输出目录非空时拒绝覆盖。

## 3. 交付内容

- `ANEB-Probe-<version>-debug.apk`
- `build-manifest.json`
- `checksums.sha256`
- `ANEB-安装说明.txt`

非 PR 构建还使用 GitHub artifact attestation 绑定工作流来源；工件保留 30 天。该能力依据 GitHub 官方[工作流工件](https://docs.github.com/en/actions/tutorials/store-and-share-data)与[构建来源证明](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)文档实现。

## 4. 已取得的本地证据

- actionlint 1.7.12：工作流零错误；
- scripts tests：共 26 项，其中 25 项 PASS、1 项 SKIPPED；测量/结果 12 项、候选打包 8 项均通过，凭据安全 6 项中 Windows 符号链接权限用例明确 skipped、其余 5 项通过；
- 真实本地 0.5.10 APK：包名、versionCode 42、`0.5.10-codex`、Debug 证书、ZIP 和 Gradle 身份交叉验证 PASS；
- 生成 APK SHA-256：`82A1A3C45A3ECD5C695417F65BFCF67311C94A571467EFB2E79525C8EBE5BB1F`；
- 全量 `scripts/quality_gate.ps1` 已实际调用候选打包器并 PASS。

## 5. 云端闭环证据

- ［KNOWN｜HIGH］成功 run：[`29635434193`](https://github.com/lucassu2012/ANEB_GPT/actions/runs/29635434193)，source commit `51fdd7c81f1f63a7202dd40d8ce86f5931d0d1a2`，分支 `codex/app-0.5.10-export-reliability`。
- ［KNOWN｜HIGH］六个 job 均成功：跟踪源码凭据扫描、合同、Go 服务端、专用网关、行为模型、Android 候选。网关外部固定 CA 的隔离 TLS/netem 命名空间步骤因未配置 `ANEB_GATEWAY_TEST_CERT_PEM/KEY` 而明确跳过；控制面、竞争测试和 Linux 构建通过。该跳过项不是 PASS。
- ［KNOWN｜HIGH］工件 ID `8427011992`，名称 `aneb-probe-debug-51fdd7c81f1f63a7202dd40d8ce86f5931d0d1a2`，大小 24,842,612 bytes，GitHub ZIP 摘要 `sha256:ffef2b3f0c3177e3ac81794b3d7ced536eee3afae71f5927e6a43fd6db3cccb0`，到期时间 `2026-08-17T07:24:33Z`。
- ［KNOWN｜HIGH］ZIP 文件集合精确为 APK、`build-manifest.json`、`checksums.sha256` 和中文安装说明；内部三份 SHA-256 全部匹配。
- ［KNOWN｜HIGH］云端 APK：SHA-256 `49244B3157FCC47D54EDA61A51EAF4B69A71BD2B95314BAE54E327CE8B0F6D85`；包名 `com.aneb.probe.codex`，`versionCode=42`，`versionName=0.5.10-codex`，`minSdk=29`，`targetSdk=35`，Android Debug 签名身份已核验。
- ［KNOWN｜HIGH］云端 APK 与本机 APK 的哈希/大小/Debug 证书不同，是干净 GitHub runner 使用独立临时 Debug keystore 的预期结果；两者都不是正式 Release。

## 6. 失败修复记录

- ［KNOWN｜HIGH］run `29632621959` 暴露合同 job 未安装 `jsonschema`，且把缺少外部固定 CA 叶证书误当作网关代码失败；依赖已补齐，外部证书测试改为显式条件边界。
- ［KNOWN｜HIGH］run `29632846984` 暴露 Linux runner 上 Gradle wrapper 不可执行；已提交可执行位。
- ［KNOWN｜HIGH］run `29632913335` 暴露干净云端构建未生成 Release 合并清单；候选 job 现在先执行 Release manifest 处理再验边界。
- ［KNOWN｜HIGH］run `29633286702` 暴露 Android build-tools 发现/`aapt2` 输出的跨平台差异；CI 与打包器固定 build-tools 35.0.0，并以独立字段 fail-closed 解析。新增两项回归后，候选打包测试由 6 项增至 8 项。

## 7. 来源证明独立复核

- ［KNOWN｜HIGH］GitHub attestation ID [`35945988`](https://github.com/lucassu2012/ANEB_GPT/attestations/35945988) 将云端 APK 精确 SHA-256 绑定到上述 commit、分支和 `.github/workflows/ci.yml`。
- ［KNOWN｜HIGH］证明使用 GitHub-hosted runner 和 Sigstore Public Good 实例，Rekor transparency log index 为 `2193995642`。
- ［KNOWN｜HIGH］下载公开 bundle 后，使用官方 GitHub CLI 2.96.0 离线执行 `gh attestation verify`，同时锁定 repository、workflow、source digest、source ref 并拒绝 self-hosted runner；退出码 0，验证通过。官方 CLI 下载包也已与 GitHub 发布页校验和匹配。

## 8. P40 安装与导出闭环

- ［KNOWN｜HIGH］P40 最终安装身份为 `com.aneb.probe.codex` / `0.5.10-codex` / code 42。由于旧包与云端候选的 Debug 签名不同，本次采用“备份普通数据 → 卸载旧包 → 安装精确云端候选 → 恢复普通数据”；这不是 Android 原位升级，也不是 Room schema migration。
- ［KNOWN｜HIGH］恢复后的 Room 仍为 v19，integrity check 为 OK；`result_envelope=36`、`test_run=10`。安全偏好/API key 未恢复，不能把该结果写成“全部应用数据迁移”。
- ［KNOWN｜HIGH］混合批量文件 `aneb_results_32_of_36_20260718_161911.jsonl` 为 1,554,624 bytes，SHA-256 `F026CC05E057CF4A04035B94BC1EDE11EB909A18224D9677E2C9408F7DAD10C4`。离线验证通过 32 个文档：v1=27、v2=5；Token=10、AI 实时=14、网络综合=8；32 个唯一 run id、重复 0。其余 4 条完整性异常由设备端透明拒绝，未被修补或混入。
- ［KNOWN｜HIGH］单条 v2 文件 `aneb_result_ai_realtime_simulation_019f7377_20260718_162513.jsonl` 为 44,377 bytes，SHA-256 `FE964695E19997796F5FEB84E05F50FB69F61F2C6299FA0C577263E5198F7EA9`；run `019f7377-9a61-7db5-a8c4-1ac57de1a486` 通过 v2 离线验证，并与批次中的对应行逐字节一致。
- ［KNOWN｜HIGH］批量与单条两条 MediaStore 记录均为 `is_pending=0`，证明成功路径完成标记；创建/打开/写入/完成失败后的清理仍由自动化故障注入覆盖，不把成功真机路径冒充为全部失败路径实测。

## 9. 剩余边界

- ［KNOWN｜HIGH］该产物是 30 天保留的 `debug_non_release` 候选，不具备公开发布签名、商店发布或后续无缝升级资格。
- ［KNOWN｜HIGH］本轮使用受控开发/ADB 路径完成安装与证据读取；尚未证明普通用户不依赖 ADB 的“下载 → 系统安装 → 首次启动 → 测试 → 导出/分享”完整路径。
