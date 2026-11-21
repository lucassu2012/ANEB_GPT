# ANEB Pilot v0.2 中文快速上手手册（《5 分钟上手卡》）

- **协调 Issue**：[#61](https://github.com/lucassu2012/ANEB_GPT/issues/61)
- **适用版本**：`com.aneb.probe` `0.2.0`（`versionCode = 20`）+ `ANEB-Prototype-0.1` 便携启动器
- **试用状态**：外部真实试点招募 **`NOT_RUN`**；本手册仅面向已获授权的测试环境（Windows x64 电脑 + Android 手机，已验证机型 Huawei P40 Pro）与操作员/复核者使用。

---

## 1. 交付包内容与双 APK 身份说明

解压 `ANEB-Pilot-v0.2-<candidate>-windows-x64.zip` 到**全新的可写目录**后，根目录包含：

- `apks/probe-release.apk`（附带 `apks/probe-release.apk.apksigner.txt`）：**生产公网 HTTPS 版**，用于连接公网正式节点 `miw2`，测量基础/负载响应性、Token AQS、Profile 4 实时语音及 `aneb-profile-v2` 行为轨迹。
- `apks/probe-prototypeRelease.apk`（附带 `apks/probe-prototypeRelease.apk.apksigner.txt`）：**Prototype 本地局域网版**（允许私有局域网 IPv4 明文 HTTP），与 `prototype-launcher/android/aneb-prototype-0.1.apk` 字节完全一致，用于连接本地 Windows 便携启动器。
- `prototype-launcher/`：Windows x64 便携启动器（包含 `START_ANEB.bat`、`bin/aneb-server.exe`、`bin/evidence/aneb-evidence.exe`、`VERSION.json`、`tools/verify-evidence.ps1`、`tools/verify-package.ps1` 及冻结的四份 `contracts/`）。
- `docs/` 与 `templates/`：`PILOT_V02_QUICKSTART_ZH.md`、`PILOT_READINESS_README.md`、`PILOT_V02_FEEDBACK_TEMPLATE.md`、`PILOT_V02_FEEDBACK_TEMPLATE.json`。
- `KIT_RECEIPT.json`、`SHA256SUMS.txt`、`verify-pilot-v0.2-kit.ps1`：交付包元数据清单、全量 SHA-256 校验表与离线校验器。

> **重要提示（双 APK 互斥切换规则）**：
> 两枚 APK 的包名均为 `com.aneb.probe`（`0.2.0` / `versionCode = 20`），且均使用同一正式签名证书（SHA-256 `b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`）。**同一台手机同一时间只能安装其中之一**。
> 1. 切换版本前，先在 App 内导出并保存重要结果（JSON / MD / PNG / Export ZIP）。
> 2. 使用 `adb install -r apks\<target>.apk` 覆盖安装，或在操作员确认后安装目标版本。
> 3. **若手机提示签名冲突或要求卸载清空数据，请立即停止（HOLD）并联系管理员**，切勿擅自绕过校验或清空他人测试数据。

---

## 2. 使用前离线校验（必做，约 30 秒）

在解压后的包根目录打开 PowerShell，执行：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\verify-pilot-v0.2-kit.ps1
```

核对以下两项才算通过：
1. 命令退出码为 `0`；
2. 终端输出 **`PASS PILOT_V02_KIT_VERIFIED`**。

若操作电脑已配置 Android SDK（`apksigner.bat` / `apkanalyzer.bat`）与 Go 工具链（`go.exe`），可追加严格参数做二次深校验：

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\verify-pilot-v0.2-kit.ps1 -RequireSdkTools -RequireGoTool
```

若校验输出 `FAIL ...` 或退出码非 `0`，立即停止分发和使用，并原样保留终端输出反馈给交付负责人。

---

## 3. 路径 A：连接公网节点 `miw2`（使用 `probe-release.apk`）

1. **安装与首页布局**：安装 `apks/probe-release.apk` 并启动 `ANEB Probe`。主界面底部保留 **4 个标签页**：`测试 / 结果 / 地图 / 设置`。
2. **基础测速与负载响应性（`LoadedResponsiveness v2`）**：
   - 在 `测试` 页顶部切换模式为 **基础测速 / 负载响应性**，确认测试节点为公网 `miw2`。
   - 点击开始运行，等待显示 `completed`（完成）；可在 `结果` 页查看空闲 RTT P50、下载/上传负载 P95 延迟膨胀，并导出完整 JSON 记录。
3. **Token 体验测试（S1–S3 + AQS）与专业视图**：
   - 在 `测试` 页选择 Token 体验模式运行 `Quick` 或完整轮次。
   - 完成后进入 `结果` 页，可展开 **专业视图** 查看各业务子分、权重与主要拖累维度，并核对环境监控（温控污染计数 `0`）与协商协议记录。
4. **Wi-Fi vs 5G NR 历史配对对比（`PairedComparisonCard`）**：
   - 在不同网络下分别完成测试（例如先连 Wi-Fi 测一次，再切至 5G 测一次）。
   - 进入 `结果` 页，点击 **`对比两项`**（`Wi-Fi vs 5G 对比`），选择两条记录进行并排对比（支持点击 `⇄ 对调` 交换基准）。
   - 验证 **`导出 MD`** 与 **`分享图片`**（生成 PNG 对比图），确认文件保存成功且无隐私泄露。
5. **Behavior Trace v2（`aneb-profile-v2` 20 任务全量仿真）**：
   - 路径：`设置 -> Profile 与指标合同 -> 多模态 Token 业务 -> 运行行为轨迹仿真`。
   - 等待 `20/20` 任务执行完成，核对 `status = completed`、`computable = true` 与最终得分。
6. **实时语音测量（Profile 4）与高级 API 探针**：
   - 实时语音测量入口位于 `测试` 页底部抽屉或 `设置 -> 实时语音测量（Profile 4）`。
   - `API 探针` 已从主底栏移至 **`设置 -> 高级 -> API 探针`**（Issue #1），仅用于节点连通性排障。

---

## 4. 路径 B：本地局域网 Prototype 模式（使用 `probe-prototypeRelease.apk` + `prototype-launcher`）

1. **启动 Windows 便携节点**：
   - 确认手机与电脑处于**同一个获准且可互访的私人局域网**。
   - 进入 `prototype-launcher/`，双击 `START_ANEB.bat`，等待终端出现 **`ANEB 已就绪 READY`**（`ANEB Prototype 0.1 - READY`）。
   - 优先选用终端提示的 **`Wi-Fi`** 推荐地址（例如 `http://<局域网IP>:18088`）。**注意：`READY` 仅代表本机节点已监听，不代表手机已经连通；不要关闭 Windows 防火墙，也不要把公共网络强设为可信网络。**
2. **手机连接与运行 Quick / Acceptance**：
   - 安装并打开 `apks/probe-prototypeRelease.apk`，点击首页右上角 **`PROTOTYPE`** 胶囊进入 `Prototype Mode`。
   - 填入电脑终端显示的推荐地址，点击 **`检查连接`**（旧版 `Test connection`），**必须看到 `兼容`（`Compatible`）后才能开始**。
   - 依次点击运行 **`Quick`**（固定 **3 次**）与 **`Acceptance`**（固定 **9 次**）。若中途失败或取消，原样保留任务记录，切勿重复凑通过。
3. **导出备份与离线证据核验**：
   - 手机端每次完成后点击 **`Export ZIP`** 并等待明确保存确认提示（手机导出的 5 文件 ZIP 仅作未验证备份，不可替代电脑节点 7 文件）。
   - 在电脑 `prototype-launcher/` 目录打开 PowerShell，对 `results/<campaign_id>` 执行正式证据校验：
     ```powershell
     powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-evidence.ps1 -Bundle .\results\<campaign_id>
     ```
     核对退出码 `0` 与 **`G0_VERIFY_OK`**，并打开 `results/<campaign_id>/report.html` 查看离线报告。
4. **正常停止启动器**：
   - 回到启动器终端窗口，输入 **`Q` 后按 Enter**，等待服务正常退出并保留 `results/` 目录中的所有已保存结果。

---

## 5. 结果理解、现场清场与反馈提交

### 5.1 必须分清的三条解释红线

1. **`verified` / `G0_VERIFY_OK` != 测量成功**：`verify-evidence.ps1` 与 `verify-pilot-v0.2-kit.ps1` 仅证明文件与证据合同**未被篡改且结构完整**；评估测量本身是否成功，必须同时查看 `campaign_status`、`success_rate`、TTFT、Completion、Stall、`RPI` 与 `null reason`（缺失不是零）。
2. **合成测量 != 真实第三方 App / MOS / 运营商评级**：Prototype 合成条件与 `miw2` 探针测的是终端到对应 ANEB 节点的端到端应用层响应，**不代表第三方商业 App 表现、物理层/RAN 状态、主观语音 MOS、运营商评级或 SLA**。
3. **跨网络差异需看业务匹配度**：单看 Ping 和下载带宽往往误判（例如部分 Wi-Fi 在吞吐与 Token 场景占优，但在长静默语音回合可能弱于 5G NR）；请结合业务场景与并排对比判断。

### 5.2 测试前后清场与数据脱敏

- **测试前**：确认测试手机无残留 VPN/代理隧道、抓包工具或冲突测试会话。
- **测试后**：在原型启动器输入 `Q` + Enter 正常停止服务；关闭手机端测试页面与临时设置（不以卸载 App 作为日常退出方式）；保留所需结果文件并还原设备至干净桌面状态。
- **提交反馈**：对照 `templates/PILOT_V02_FEEDBACK_TEMPLATE.md`（或 `.json`）填写实际操作状态（`PASS | HOLD | FAIL | NOT_RUN`）、耗时、遇到的最难步骤及原话反馈。**外发前务必确认数据保管人与接收人，并遮挡或移除通知栏、联系人、私有截图、密钥、Token 与密码**。
