# ANEB 云端续开发 Checkpoint

> 时间：2026-07-16（Asia/Shanghai）  
> GitHub：`lucassu2012/ANEB_GPT`  
> 发布策略：公开仓只包含源码、设计文档和可复现测试；本机 `evidence/`、设备数据库、日志、密钥、APK、构建缓存不发布。

## 1. 当前产品边界

- ANEB App 只在自建 ANEB 节点上模拟 AI 应用行为，不调用 Kimi、DeepSeek、千问等真实 API。
- 现有真实 API 探针代码保留为开发诊断能力，但必须从正式产品导航隐藏。
- AI 真实业务行为模型独立开发，通过冻结的 Profile v2、模型哈希和确定性轨迹与 App 对接。
- Token、AI 实时交互、网络综合性能三类测试独立评分，禁止混入原有 AQS。
- 缺失测量值必须为 `null`/“—”，不得填 0；95% 达标结论必须带有效样本数和置信度。

批准记录见 `docs/DECISION_LOG.md` 的 D-36、D-37；完整指标、目标、评分与结论合同见
`docs/PROFILE_CONTRACT_V2_PROPOSAL_2026-07-16.md`。

## 2. 已完成

- 此前本地开发的 M1 协议仿真、v3.0/v3.1 设计、Android Echo/M2 交接与验证状态已完成恢复审计；安全的历史机器摘要和全部归档哈希见 `docs/references/RECOVERED_LOCAL_DEVELOPMENT_INDEX_2026-07-16.md`。旧代码、旧评分配置、APK、密钥与原始设备证据未导入。
- SpeedTest 级视觉框架、动态仪表、基础网络测速、结果结论和新版图标已在 Android 工程内。
- 独立 `tools/aneb-ai-behavior-model` 已实现确定性 PCG32、Token/实时语音假设模型、轨迹生成、拟合、Schema 和测试。
- Profile Contract v2 Kotlin 数据结构已开始接入：`ProfileModels.kt` 支持 business、measurements、live_presentation、evaluation、trace。
- `ProfileCapability.kt` 已增加 v2 fail-closed 合同校验；未经运行时接入的 v2 模式仍明确显示不可执行。
- 网络综合 Profile 草案位于 `profiles/drafts/network_comprehensive_standard.json`。

## 3. 当前未完成点（恢复后按顺序）

1. 为 Profile v2 Kotlin 解析和校验补单元测试，并运行 Android 全量单测；当前最后一次模型测试为 7/7 通过，但 Kotlin v2 改动后尚未完成编译验收。
2. 正式导航下线 API 探针：底部“探针”页改为 Profile/业务测试目录；设置页删除 API 探针入口；保留 ADB autorun 诊断路径。
3. 将 Token standard/stress Profile 接入独立运行服务、实时 `SIM_TPS_LIVE`、结果实体和 Token Simulation Score v1。
4. 接入 AI 实时语音 WebSocket 仿真：20ms 上行/下行帧、打断、恢复、`AUDIO_ON_TIME_RATIO_2S` 和独立评分。
5. 升级网络综合引擎：loaded RTT、1s goodput 窗口、UDP echo 未返回/乱序、恢复 RTT 和独立评分。
6. 三类结果统一生成完成性、业务行为特征、瓶颈和逐项 95% 网络建议；因果措辞必须服从证据范围。
7. 编译 APK 后再占用 P40 Pro：先检查 `com.aneb.probe` 是否在前台；Claude 测试中则等待。完成后按 HOME 并验证 launcher，主动释放手机。

## 4. 关键目录

- Android/Go 主工程：仓库根目录
- Profile：`profiles/`
- Android App：`app/probe/`
- 自建节点：`server/`
- 独立行为模型：`tools/aneb-ai-behavior-model/`
- UI 设计来源（仅本机，未发布）：`E:\G Project\ANEB\ANEB_UI`
- Claude 参考工程（只读参考，未发布）：`E:\C Project\ANEB`

## 5. 云端恢复命令

```powershell
git clone https://github.com/lucassu2012/ANEB_GPT.git
cd ANEB_GPT
cd tools/aneb-ai-behavior-model
$env:PYTHONPATH = (Join-Path (Get-Location) 'src')
python -m unittest discover -s tests -v
cd ..\..\app
.\gradlew.bat :probe:testDebugUnitTest
```

Android 构建依赖和签名流程见 `docs/RELEASE_BUILD.md`。不要把 `local.properties`、keystore、API key、设备数据库或证据日志提交到公开仓。
