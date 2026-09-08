# ANEB Prototype 0.1

本文件定义固定 Windows 发布包的使用入口；源码 checkout 本身不是发布包。只有 `VERSION.json` 为 `RELEASE_CANDIDATE`、包内校验通过、且 ZIP SHA-256 与 Product Owner 同渠道发布收据逐字一致的目录才可按下述步骤使用。不要混用历史 APK、单独下载的 Server、Debug 包或另一份 ZIP 中的文件。

包内包含已签名 Android APK、Windows Server、离线 evidence verifier、四份冻结合同、启动器和离线报告模板。普通运行不需要 Python、Node、Go、Gradle、ADB、管理员权限或互联网；手机与电脑只通过同一私有局域网通信，不向云端上传 Prototype 证据。

## 五步快速开始

1. 把完整 ZIP 解压到新的本地目录；不要直接在 ZIP 内运行，也不要覆盖旧候选目录。
2. 双击 `START_ANEB.bat`。首次运行若 Windows 防火墙询问，只允许“专用网络”。窗口会显示可用 LAN 地址、端口和结果目录。
3. 将 `android\aneb-prototype-0.1.apk` 复制到手机并手动安装，然后打开 ANEB Prototype。若 Android 报签名不一致，先停止，不要绕过；参见下方“安装与回滚”。
4. 在 Prototype Mode 输入启动器显示的同一 LAN 地址，先确认节点兼容，再运行 Quick。一次 Quick 固定按 Baseline → Slow → Unstable 执行。
5. 完成后在启动器显示的 `results\<campaign_id>\` 中打开 `report.html`；`manifest.json`、CSV 和 JSONL 是同一份可验证证据，不要单独改写。

## 包完整性与发布身份

`START_ANEB.bat` 会先运行包内完整性检查，再启动 Server；校验失败时不会继续。也可以手动执行：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\verify-package.ps1 -Root .
```

该命令证明当前解压目录内部的 checksum closure 和制品格式，输出会明确标注 `runtime integrity only`。正式候选在发布前还必须由发布流程使用包外、固定 SHA-256 的 admission receipt 运行 `-RequireExternalAdmission`；最终用户的分发信任根是 Product Owner 同渠道公布的 ZIP SHA-256。包内文件不能自证其分发来源。

发布操作者的正式门必须同时提供包外 receipt、receipt SHA-256 和未解压的原始 ZIP，缺一项都不是正式 admission：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\doctor.ps1 -Root . -RequireExternalAdmission `
  -AdmissionReceiptPath '<external-receipt.json>' `
  -ExpectedAdmissionReceiptSha256 '<64-lowercase-hex>' `
  -PackageZipPath '<ANEB-Prototype-0.1-rc-id-windows-x64.zip>'
```

该门会把 receipt 固定到同一份 ZIP，并重新核对 APK、Server、evidence executable 和完整 evidence runtime tree；不要把 receipt 放进 ZIP 或解压目录。

Android 正式包身份为 `com.aneb.probe`，版本 `0.2.0`（versionCode 20），批准的签名证书 SHA-256 为：

`b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`

## 安装与回滚

- 同一签名的后续候选可由 Android 正常覆盖安装；覆盖前仍应导出或复制重要结果。
- 若系统提示签名不一致，说明设备上的同包名应用不是这条正式签名线。不要用“忽略签名”工具。先保存需要的本地结果，再卸载冲突应用；卸载会清除该应用的 Room 数据，然后安装本包 APK。
- Prototype 0.1 不承诺数据库原地降级。回滚到旧 APK 前，先保留 Windows `results` 和手机可导出的结果，再卸载当前版本并做干净安装。
- 回滚 Server 时必须回滚整份 ZIP；不要只替换 `aneb-server.exe`、APK、contracts 或 verifier。

## 运行、停止与维护

- 每个候选使用独立解压目录；每次正式验收使用新的 campaign ID 和新的结果目录。
- 正常停止必须在启动器窗口输入 `Q` 后按 Enter；启动器随后只清理它自己启动并记录 PID 的 Server。不要把“直接关闭窗口”当作清理动作。若窗口被意外关闭，先在任务管理器确认没有遗留的 `aneb-server.exe`，再重启。
- ZIP 初始只包含空的 `results\`。正式 campaign 写入 `results\<campaign_id>\`；Server 的兼容运行数据写入 `results\legacy\`。不要把一个 campaign 的文件搬到另一个目录。
- 同一 campaign 的四份原始上传文本完全相同时，重复提交返回原发布回执，七份证据文件不重写。内容不同时会拒绝，不能用重传覆盖已发布证据；应保留原报告并使用新的 campaign。
- 冲突诊断位于 `results\.publication diagnostics\`（目录名含空格），不属于七份证据。每个已发布 campaign 最多保留第一份冲突摘要，单文件不超过 2 KiB；只含固定原因、时间及内容摘要，不含请求原文。它不是完整重试历史，也不是新的测试结果。维护者排查 P018 时可查看；不得把它移进 campaign 目录。
- 不要编辑 `VERSION.json`、`SHA256SUMS.txt`、contracts 或结果 manifest。任何编辑都会使完整性或 evidence 校验失败。
- `complete`、`partial`、`cancelled`、`failed` 或 `invalid` campaign 都可能发布 Windows 可验证报告；`verified` 只表示证据字节、结构和跨文件关系已通过验证，不表示 campaign 或任一 run 成功（`verified` means evidence integrity only; it does not mean campaign or run success）。必须同时查看 `campaign_status`、`run_status`、`success_rate` 以及 RPI/null reason；手机仍保留本地结果。
- 维护者构建、签名和独立验签步骤见 `docs/RELEASE_BUILD.md`（源码仓库文件，不在普通运行路径中）。

## 验证 evidence

对一个已经完成的 campaign 运行包内离线 verifier：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\verify-evidence.ps1 `
  -Bundle .\results\<campaign_id>
```

只有退出码为 0 且底层 verifier 输出 `G0_VERIFY_OK` 才表示该目录通过冻结 evidence 合同。不要只凭 `report.html`、文件存在或启动器 READY 判断 evidence 有效。

## 失败处理

P001_PACKAGE_INTEGRITY：重新解压同一固定包并重新运行校验。
P002_OUTPUT_NOT_WRITABLE：选择可写的结果目录后重试。
P003_PORT_IN_USE：停止已知冲突或使用已批准的端口配置。
P004_SERVER_START_FAILED：停止，不继续运行；查看脱敏诊断日志。
P005_NO_LAN_ADDRESS：让 PC 与手机连接同一局域网；ADB 仅作开发诊断，不是正式验收路径。
P006_NODE_UNREACHABLE：检查显示的节点地址、防火墙和局域网。同一 Wi-Fi 不保证手机能访问电脑；公共网络可能隔离客户端或阻止入站连接。不要关闭防火墙或把公共 Wi-Fi 改成受信任网络；改用你控制的私有局域网，或由网络管理员完成受限配置后再试。
P007_CONTRACT_MISMATCH：使用同一发布包中的 APK、server 和 contracts。
P008_STREAM_INTERRUPTED：保留 partial 证据，重试未完成 campaign。
P009_INVALID_SEQUENCE：保留证据并停止把该 campaign 当作成功。
P010_CAMPAIGN_CANCELLED：查看 partial 证据或开始新的 campaign。
P011_RELEASE_SIGNER_NOT_APPROVED：停止安装或发布，使用批准签名生成的新候选。
P012_FINALIZE_FAILED：不要覆盖 partial 目录，保留诊断后重新处理。
P018_EVIDENCE_PUBLICATION_FAILED：手机本地结果已优先保留；检查结果目录权限和 verifier。已发布内容冲突时查看上述诊断，不要删除或覆盖原证据；排除问题后运行新的 campaign。诊断目录本身不可写时仍会拒绝，不能据此宣称诊断已保存。
P019_PROTOTYPE_SOURCE_COMMIT_NOT_BOUND：该 APK 缺少精确源码提交绑定，不得作为 RC。

Prototype 0.1 只比较确定性应用层合成条件；不表示 IP 丢包、无线/RAN 状态、运营商 SLA、第三方 App 或模型推理性能。
