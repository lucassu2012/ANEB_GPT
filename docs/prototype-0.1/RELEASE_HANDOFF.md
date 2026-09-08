# ANEB Prototype 0.1

固定候选已完成原定质量门、离线校验和 P40 普通局域网验收。发布沿用以下完整已验证文件名与字节，不重新打包。

同一个 Android Campaign 依次运行 Baseline、Slow、Unstable，输出 TTFT、Completion、Stall、Success Rate、RPI-0.1 和可验证的 Campaign Summary。本版补齐正常的 Saved campaigns 入口，可在离开结果页或重启应用后重新打开保存的结果，无需先连上节点。

## 文件与身份

保留完整已验证文件名 **ANEB-Prototype-0.1-rc-20260909-03675a9-windows-x64.zip**；展示标题为 **ANEB Prototype 0.1**。这是 PO 批准的命名方式，不重命名或重打包。

包外 `external-admission-receipt.json` 与 `artifact-build-receipt.json` 应与 ZIP 一起保存，不放进 ZIP 或产品目录。制品源码 `03675a93b9b39c122680204256814740f7f6e178`，源码树 `fcf5a5db530a9eefc5a67a8d69d64c5f3342b360`。[PR59](https://github.com/lucassu2012/ANEB_GPT/pull/59) 的合并提交 `90602c712e7a9c9e8fcc1b68a782ba3d4f7e52d9` 与之树相同；制品身份仍是实际构建提交。

| 对象 | SHA-256 |
|---|---|
| Windows ZIP，38,889,601 bytes | `ec1bd2c406ce2610b915396cba0de614281df58c81a16800ea8f34158545f3ca` |
| external-admission-receipt.json | `f82a2d1542048d8f4d7f8d2491270a511a81f265c1b8c62480db46c513c8690e` |
| artifact-build-receipt.json | `c1786b46ffc930d9d77b79100907969aebbbb72d2b6f8cdb18a3eaceaef97138` |
| Android APK | `93d56810103875f395d3a8493f6eff7dac747f6f6e4a90b7bf95f2095407eda4` |
| Windows Server | `d70646452fb3be39b9be29c2b4358aa87db7dd82c6b47ef7f618ebbe074474bf` |
| Evidence verifier | `70b3ab929b860c219736733a45f7399b918648ca7f166fb7c2484d0c8fe5cd20` |
| Evidence runtime tree | `54d3ce885a2b788396d7be8826a661ae83b81e85270761be0f7f22e6ee11cb3d` |

APK 为 `com.aneb.probe` / `0.2.0` / versionCode `20`，属于同一个 Prototype 0.1 产品包。批准证书 SHA-256：`b33df25ffa3b1bedc7e08af77b442bc205aeab553c07ee72344e19ed0f7b6003`。

## 五步使用

1. 核对 ZIP 校验值，完整解压到一个新目录，保留完整目录结构，不覆盖旧 results。
2. 双击 `START_ANEB.bat`，等待 READY，选择与手机处于同一可互访局域网的节点地址。READY 不代表手机已经连通。
3. 将 `android\aneb-prototype-0.1.apk` 复制到手机并安装。覆盖安装前保存重要结果；签名冲突时停止，不绕过系统验签。
4. 打开 Prototype Mode，填写节点地址，Test connection 显示 Compatible 后运行 Quick（3-run）或 Acceptance（9-run）。
5. 打开 `results\<campaign_id>\report.html`，保留整个七文件目录并做离线验证。结束时在启动器输入 `Q` 并回车，等待正常退出。

在解压后的产品目录中，将实际结果目录替换下列占位符：

```powershell
powershell.exe -NoLogo -NoProfile -File .\tools\verify-evidence.ps1 -Bundle ".\results\<campaign_id>"
```

退出码 0 且输出 `G0_VERIFY_OK` 才表示证据合同通过；证据通过不等于测量成功，仍须查看状态、success rate、RPI 和 null reason。

## 重新打开与恢复

- Prototype Mode → Saved campaigns → 按完整任务 ID 和原节点选择。打开只读取保存的数据，不启动新测量或自动上传。
- 发布失败时，恢复结果页所示的原节点，再显式选择 `Retry evidence publication`。它使用同一保存任务与原采集身份，不重新测量，不把失败或中断改成成功。
- 手机的 Export ZIP / Share ZIP 是标明 unverified 的五文件备份；正式验证针对节点上的七文件目录。
- 同内容重发返回原回执；冲突内容被拒绝，不改写已发布结果。不要手改 manifest、VERSION、校验清单或 contracts。

## 环境、维护与边界

- Windows x64 和 Huawei P40 Pro；其他设备/系统不自动继承本次验收。
- 启动器与服务使用普通用户权限，只需包内文件和 Windows 自带能力，不依赖 Python、Node、Go、Java、ADB、Docker 或运行时互联网。受限 Windows 防火墙环境可能需要管理员先授权指定程序和本地端口；本次测试仅向同网 P40 临时开放，未关闭防火墙或更改网络类别。开发观察工具不是普通运行依赖。
- 手机证据发送至所选本地节点，不上传云端。相同 Wi-Fi 不保证客户端互访；不要关闭防火墙或冒充网络信任等级。
- RPI-0.1 仅描述冻结的应用层合成条件，不是 MOS、IP 层丢包、RAN 指标、第三方 App/模型质量、运营商评级或 SLA。
- 保存原 ZIP、包外回执及完整 results。回滚使用整份已批准旧包，不混换单文件；不承诺任意数据库降级。卸载会删除应用私有数据，必须先保存重要结果。

## 验收与已知限制

- [固定候选 G5 完整索引](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589818454)：同一候选完成受控迁移、Quick 3/3、Acceptance 9/9、导出保存、系统分享面板、取消和断流恢复。Quick 与 Acceptance 每次成功运行均有 120 条内容事件及有效结束回执；四个新任务的七文件证据均由原包验证器通过。
- [取消 / P008 恢复](https://github.com/lucassu2012/ANEB_GPT/issues/18#issuecomment-5589718698)：保留中断事件、未开始尾部与空 RPI；恢复原节点后，显式 Retry 只恢复证据发布，不重测、不改成成功。
- [原定九个离线向量及真实符号链接负例](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5588636391)均得到预期结果。P007 另有原 APK 的工程负例证明，但使用测试适配器，不能替代局域网实机验收。
- [主机重复 / 冲突检查](https://github.com/lucassu2012/ANEB_GPT/issues/17#issuecomment-5589918604)：成功 / 相同回执 / 拒绝冲突，七文件不变，诊断仅 1,052 字节且不含原始载荷。包篡改和目录不可写负例亦已完成。
- 制品源码 `03675a9` 的完整本地质量门、独立签名构建及来源核验通过；Android 791 项测试，0 失败/错误、2 项既有忽略；原始 Go 测试通过；合并前后 CI 全绿。未把诊断覆盖层加入发布包。
- 本机首次局域网访问受 Windows 防火墙阻止；正常的限定授权后取得实际验收，结束后权限全部恢复。不能据此承诺所有电脑首次启动都不需要网络访问配置。产品运行本身不要求管理员权限。
- 旧候选一次取消发布 P018 和一次早期本地合成 Go500 保留，根因未知；本次修复的是保存结果的正常重开入口，不宣称已解释历史故障。新候选取消发布及断流后的显式恢复已实际通过。
- 主机合成测试的首个 HTTP502 保留；只调整该次命令的本机回环路由后，原三个请求通过。未修改系统代理或安全设置。
- 本次是受控保留数据升级，不是卸载清空；不承诺任意旧版本降级或其他设备兼容性。系统分享面板已实际打开，未向联系人发送文件。

本版未改变冻结的 workload、RPI-0.1、证据 schema、claim 或 G2–G5 标准。完整验收和发布状态见 [Issue #13](https://github.com/lucassu2012/ANEB_GPT/issues/13#issuecomment-5572409181)。测试用服务、转发和临时权限均已清理；不继续后台测试。
