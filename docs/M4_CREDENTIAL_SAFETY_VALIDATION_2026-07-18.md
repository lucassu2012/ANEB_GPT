# ANEB M4 · 仓库凭据安全门验证

> 日期：2026-07-18（Asia/Shanghai）
> 范围：公开仓源码与 CI；不扫描聊天平台历史，也不替代凭据撤销和供应商审计。

## 1. 反方风险

- ［KNOWN｜HIGH］凭据一旦进入聊天、提交、PR 或 CI 日志，删除可见文本不能保证所有副本消失；应先撤销，再创建最小权限替代凭据。
- ［KNOWN｜HIGH］`.gitignore` 只能阻止已知文件路径，不能阻止把 Token 粘进 Kotlin、Markdown、YAML 或日志样例。
- ［INFERRED｜HIGH］ANEB 要公开发布，仅靠人工提醒不足以守住凭据边界，需要提交前和云端共同 fail-closed。

## 2. 实现合同

`scripts/scan_repository_secrets.py` 读取 `git ls-files` 返回的工作区文件，并单独重读 Git 暂存区中将要提交的 blob，检测高置信 GitHub、OpenAI、Anthropic、AWS、阿里云、Google、Slack 凭据形状和 PEM 私钥头。双读可关闭“先暂存密钥、再只清理工作区”的绕过路径。

- 命中日志只输出规则、文件和行号，不输出匹配值；
- 跟踪文件缺失、路径越界或符号链接拒绝扫描并失败；
- 含 NUL 的二进制文件跳过；APK、AAB、JKS、keystore 与本地 TLS 私钥继续由 `.gitignore` 和发布边界禁止跟踪；
- 不提供通用白名单，避免攻击者用注释绕过；测试夹具使用运行时拼接，源码不保留可复制的完整 Key 形状。

本地 `scripts/quality_gate.ps1` 和 GitHub `Tracked-source credential scan` job 均执行同一脚本；Android 候选必须等待该 job 成功。

## 3. 自动化证据

- 6 项定向测试覆盖 9 类凭据检测、日志脱敏、合法占位符、二进制跳过、符号链接 fail-closed，以及“暂存区有密钥、工作区已清理”的反例；Windows 无符号链接权限时该一项明确 skipped，其余 5 项通过。
- 首次扫描发现既有 `KeyRedactionTest.kt` 含完整形状的合成 Anthropic Key；它不是已知真实凭据，但已改为运行时拼接，未增加白名单。
- 新文件进入 Git 跟踪范围后，本地扫描 571 个文件，结果 PASS；聊天中披露的真实 GitHub Token 未进入工作区文件。
- 初版完整门禁后，暂存区反例暴露并修复；修复版完整本地质量门再次通过：551 项 Android JVM 测试、Lint、Release 边界、工作区 + 6 个暂存路径双扫描、候选打包、Schema/catalog、26 项脚本测试、31 项行为模型测试及 Go 服务/网关全部成功。

## 4. 剩余边界

- ［KNOWN｜HIGH］扫描 PASS 不证明 Git 历史、聊天、截图或供应商日志从未含密钥，也不能让已经披露的凭据恢复安全。
- ［KNOWN｜HIGH］实际披露后的必要动作仍是：撤销旧 Token、创建最小权限替代、检查 GitHub audit/security log；新 Token 不得再次发送到聊天。
- ［KNOWN｜HIGH］新增云端 security job 尚待下一次真实 GitHub Actions run 验证；本地通过不冒充云端通过。
