# 业务视频：独立复核结果的保存、重开与离线交接

这是手机业务 Profile 纵切中的结果交接组件，不是把产品目标改成资料阅读器。一个有效场景、端点/方法资格及受控网络对照仍须独立通过；导入或导出成功不能替代它们。

## 复用的入口

在原有研究 Reader 导入并保存 `business-video-raw-1` 原文，然后使用同页“选择本次复核 JSON”。预览确认后保存独立复核副本，可重开、单独导出 JSON，或经原有本机确认流程导出含复核的 ZIP。也可复用原有批量导入，按来源 SHA 关联原文与复核，不依赖选择顺序。

旧“导出采集原文报告 ZIP”仍只含原文；“导出含复核的业务视频报告 ZIP”含原文、所选已保存复核、可读首页和 SHA 清单。它们共用原有 Store、ZIP 编码器和 Downloads 发布器，不新增 Reader、索引、上传或媒体打包服务。

## 私有 sidecar：`business-video-review-1`

这是与旧正式 evidence schema、alignment-1、research-video-1、原始采集分开的导入格式，旧数据和原件不迁移、不重写。

根字段：

- `format` 固定为 `business-video-review-1`，`record_kind` 为原文类型 `UNVERIFIED_RAW`。
- `source_sha256` 对应原始 JSON 的实际字节 SHA；`input_record` 必须在结构/类型/值上等于完整原文。对象键顺序和空白不作身份依据，原文字节另按 SHA 保留。
- `method_id` 是非空的方法声明，不是软件认证。
- `records` 只能有该原文的一项，`attempt_id` 与原文 `runId` 一致。

该项含：

- `qualification` 为 `NOT_QUALIFIED` 或 `QUALIFIED`。停止或清理未确认的原文不能被声明为合格；即使原文允许该声明，也只是导入复核意见，不是 App 验收/媒体认证。
- `metrics` 恰含 `visible_pointer_to_target_wait`、`visible_switch_wait`。每项包括 `status`、`interval_s` 和非空 `reason`；`interval` 为非负有序数字秒区间，`NA`/`uncertain` 必须携带 null 而非伪造的零区间。只核结构，不重算端点、达标结论或网络原因。
- `stall_status` 必须为 `not_computed`。
- `observations`、`limitations` 是文本数组；`evidence_references` 是 `reference` 文本与声明 SHA-256 的列表。引用不解析、不读取、不链接到外部媒体，也不证明对应文件存在或来源真实。

单份仍使用现有 1 MiB/UTF-8/JSON 深度保护及 content-addressed 原子保存。类型由已通过的解析分支确定，旧分析的任意 `format` 扩展字段不能改变呈现类型。

## 结果如何阅读

原始执行状态、类别、原因与 `ownedCleanup` 始终置前；复核声明另区显示。`STOPPED`、`false`、`UNKNOWN`、NA 和 not_computed 不被导入副本或稍后清场结论覆盖。

两项新方法区间与原因使用独立标签，不回填原始 null，不替代旧 V0/VF/Completion，不计算评分、跨 App 排名、成功率或网络因果。区间仅按现有显示规则向外取整，原始 JSON 不变。

离线报告的 source.json/analysis.json 保留原字节；SHA 清单是完整性核对，不是签名或真实性认证。报告不附录像/截图。复核者若要重建过程，仍需另取得已保留的原始媒体、操作、身份与清场原件；单独 ZIP 不提供这些缺失的证明。

## 验收边界

定点用例覆盖保存/重开/原字节导出、失败源不被声明洗绿、NA不夹零、输入完整绑定、既有批量导入和复用 ZIP/文本引用。真实私有失败原文和复核只经外部 opt-in 测试回放，不进入仓库或常规 CI。

固定候选完整门、CI与APK pin后，再另定一次纯产品 Reader QA：导入既有原文/复核、保存、重开、本机离线导出并清场。不启动业务 App、不新增研究操作/样本，不由产品 QA 释放新的方法资格或网络对照。
