# Draft Profile Contract v2

本目录不被当前 Go 服务端递归加载，也不被当前 APK 打包。D-37 已批准 Profile Contract v2
的 provisional v1 目标、独立评分权重和 fail-closed 门控作为实验性实施基线；这里的文件仍是
尚未完成运行时接入、端到端验证和版本化发布的候选合同。

- `network_comprehensive_standard.json`：网络综合性能候选 Profile。
- Token 与实时语音候选 Profile 由独立
  `tools/aneb-ai-behavior-model/` 工程生成，输出包含完整指标目录、模型 id、
  版本、哈希和校准状态。

严禁把 `*-draft` 的 target/score policy 当成已发布评分；从 `drafts/` 晋升必须保留 D-37 的
三套评分相互独立、必需指标缺失为 `null` 且不重分权的语义。
