# ANEB Prototype 0.1

当前树是可独立检查的发布骨架，不是可运行发布包。#14 的 G0 已批准；本树仍未取得真实 server/APK，artifact admission 保持阻塞。启动器会安全停止，不会制造 READY 或结果。

## 五步快速开始

1. 解压固定 ZIP。
2. 双击 START_ANEB.bat。
3. 安装并打开包内 Android APK。
4. 在应用中输入启动器显示的节点地址，然后运行 Quick。
5. 打开生成的离线 report.html。

## 当前骨架状态

server/aneb-server.exe 与 android/aneb-prototype-0.1.apk 尚未进入本树。四个 G0 machine contracts 的源权威仍在 `contracts/prototype-0.1/`，但发布包按冻结的 06 版式把相同字节放在扁平的 `contracts/` 下；VERSION.json 绑定扁平路径、大小和 SHA。`evidence-schema.json` 不是本版本合同文件。不要把源代码、debug APK 或历史目录当作发布输入。启动器、doctor、包校验器和机械七文件 finalizer 已可在 Windows PowerShell 上独立验证；这不代表 G3/G4 通过。

严格 `RELEASE_CANDIDATE` 校验还需要包外、不可由调用者自证的 artifact-admission 与执行面信任根，以及由发布流程固定的 server/APK 版本、源码提交、完整 SHA-256 和 APK signer 证书绑定。在 #15/#16 的真实制品、builder-derived signer/provenance 与包外固定收据到位前，校验器对所有 candidate 明确返回 `P007_ARTIFACT_ADMISSION_TRUST_ROOT_REQUIRED`；不会把伪 PE、伪 APK、任意 signer 字符串或调用者自生成的 receipt+pin 当成正式成功。包内 VERSION、manifest、SHA256SUMS 或单独的 receipt 不是独立信任根；若包与 verifier/VERSION 被协同修改，仍必须由包外签名或固定哈希发布收据在更高发布门（如 G3/G6）阻断，当前骨架不宣称能单独完成该认证。

finalizer 在真实 G0 语义校验器和制品 admission 尚未交付前只会生成 `NON_RC_ASSEMBLED_UNVERIFIED`，不会写入或宣传 `publication_status=verified`。

## 失败处理

P001_PACKAGE_INTEGRITY：重新解压同一固定包并重新运行校验。
P002_OUTPUT_NOT_WRITABLE：选择可写的结果目录后重试。
P003_PORT_IN_USE：停止已知冲突或使用已批准的端口配置。
P004_SERVER_START_FAILED：停止，不继续运行；查看脱敏诊断日志。
P005_NO_LAN_ADDRESS：让 PC 与手机连接同一局域网；ADB 仅作开发诊断，不是正式验收路径。
P006_NODE_UNREACHABLE：检查显示的节点地址、防火墙和局域网。
P007_CONTRACT_MISMATCH：使用同一发布包中的 APK、server 和 contracts。
P008_STREAM_INTERRUPTED：保留 partial 证据，重试未完成 campaign。
P009_INVALID_SEQUENCE：保留证据并停止把该 campaign 当作成功。
P010_CAMPAIGN_CANCELLED：查看 partial 证据或开始新的 campaign。
P012_FINALIZE_FAILED：不要覆盖 partial 目录，保留诊断后重新处理。

Prototype 0.1 只比较确定性应用层合成条件；不表示 IP 丢包、无线/RAN 状态、运营商 SLA、第三方 App 或模型推理性能。
