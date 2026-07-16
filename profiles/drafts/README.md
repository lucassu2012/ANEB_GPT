# Draft Profile Contract v2

本目录不被当前 Go 服务端递归加载。APK 会打包目录文件用于 Profile Registry
合同审计，但候选项不得被运行引擎自动选择、不得执行或进入评分；内容仍是待
Product Owner 批准门限、权重和新编排后的候选合同。

- `network_comprehensive_standard.json`：网络综合性能候选 Profile。
- Token 与实时语音候选 Profile 由独立
  `DevSpace/aneb-ai-behavior-model-v0.1.0` 工程生成，输出包含完整指标目录、模型 id、
  版本、哈希和校准状态。

严禁把 `*-draft` 的 target/score policy 当成已发布评分。
