# R1 Android：本地 SAMPLE / OBSERVED 纯批次记录

入口：结果 → App 研究记录 · 人工来源。

1. 选择 JSON（可用测试资源 `src/test/resources/research/r1-sample.json`）。
2. 预览三次尝试、原始时间区间/单位、缺失原因和证据引用；确认保存。
3. 返回列表、重新打开；退出 App 后原文仍在 App 私有目录中。
4. 点击导出，确认隐私提示后原文写入 Downloads/ANEB。不会上传或附带录像。

样例来自 1a R1 alignment-1，仅将仓库 fixture 的换行规范为 LF；虚构数据，没有真实证据。
支持顶层及各记录 record_kind=SAMPLE 或 OBSERVED，整批必须一致；混批/缺失/未知类型明确报错，不静默丢条或转成SAMPLE。
SAMPLE是虚构样例；OBSERVED仅表示输入声明来自实际观察，不表示成功、准确、媒体已核验或验收通过。
OBSERVED缺evidence.local_ref时显示“缺少本地来源引用，请人工核对”；缺sha256提示未记录/不适用及查缺失原因，不生成假哈希。not_run仍保留，不要求伪造录像。
列表、预览、详情和导出确认都标明来源；OBSERVED导出文件名不标成SAMPLE。软件不打开引用路径、不检查媒体存在性、不验证录像/哈希。
保留输入 UTF-8 原始字节及 SHA-256；相同原文重复导入不覆盖、不追加。
attempt_id 在批次内唯一，错误定位冲突的记录序号，整批不保存、不自动去重。不同批次按原文哈希独立保存，不自动合组。
内部document.id是原始字节SHA-256，与3a的source_sha256+attempt_id关联，不添加第二套派生计算或hash别名。
显示输入的 time_unit/clock.unit 和原始事件，不计算 TTFR/TTFC/Completion/stall 或评分。
结果状态、可见完成和遵循指令独立显示；UNKNOWN/null 不填 0。证据引用不自动打开/核验。

存储：noBackupFilesDir/r1-research；与 Room、AQS、Prototype publication 无关。
原文件验证后以临时文件+atomic move落盘。损坏文件仍在列表显示错误，不能导出为成功。
本地导入资源限制：1 MiB、JSON最大组合嵌套64；不是方法或正式证据schema。
导出复用 data/Exporter 的 pending MediaStore/失败清理，输出原始字节，不重序列化。

定点命令（app目录）：

```text
gradlew.bat :probe:testDebugUnitTest --tests com.aneb.probe.research.ResearchRecordStoreTest --tests com.aneb.probe.data.ExporterTest --no-daemon --no-parallel --max-workers=1
```

OBSERVED软件测试输入由SAMPLE生成并显式标注TEST ONLY，包含失败/未完成/未执行，不是实际研究数据或实测成功。
尚未完成：手工表单、真实媒体核验、设备/UI实测。未保存的原文/分析预览在Activity重建后需重新选择文件；已确认保存的原文和分析副本不受影响。
手机验收由2a执行；Android全量质量门按PMO构建队列安排，不操作设备/签名/发布。

## 导入3a分析并独立保存

1. 打开一份**已保存**的原始记录，点击“选择分析 JSON”。
2. 选择3a alignment-1输出；本机核 source_sha256 等于原文**原始字节**SHA、record_kind一致、attempt_id无重复/缺少/额外，以及input_record的身份一致。不同JSON编码的原文即使内容相似也不能共用旧哈希。
3. 预览明确显示原文和分析各自SHA、来源、计数、秒区间、NA/uncertain及原因；确认后保存**独立副本**，不会修改原文。
4. 返回列表后重开原文，再点对应哈希的分析副本；同一份分析去重，不同字节副本各自保留。损坏副本显示错误，不静默切换到其它副本。
5. 原文与分析分别导出原始字节。两者都需隐私确认；分析包含input_record上下文和证据引用，不包含媒体文件、不上传、不自动分享。

分析保存于 noBackupFilesDir/r1-research-analysis/<source_sha256>/<analysis_sha256>.json，独立于原文目录。分析也使用1MiB/深度64本地资源预算及临时文件+atomic move。
关联检查不是3a程序签名验证，也不是重算指标或核验媒体；input_record保留在分析原字节中，界面原始上下文始终来自独立原文。不要把导入值理解为Android计算或独立核实的结论。

- TTFR/TTFC/Completion按 `interval_s` 显示秒区间，不显示中点、不按输入毫秒重复换算。
- 保留NA/uncertain/reason。停顿只显示已给出的分类与区间；right_censored尾段不是完整停顿时长，total_count/max_interval_s缺失显示未知，已观察计数0不证明没有停顿。
- counts采用分析提供的planned/attempted/not_run/visible_completed_confirmed，不由UI另算。
- groups为空不制造分组；存在时显示可比性键、valid_n和point_n。point_median_s/point_range_s只标**等端点子集**中位数/范围，绝不标成整组中位数。

测试资源 `src/test/resources/research/r1-analysis-sample.json` 是3a commit `498b81186869b8f84a312c40db142f9f1f0b67a2` 交接输出的原字节副本：8296B，SHA256 `FCF17F2D54F21E8DA6E73872CFF5779C5D47980857A76853FE48F572EBEC3756`。其source_sha256与现有r1-sample.json实际核对相同：`959615aa5e5c06a96d13cf3ec94f7554f2c84b116b4e6a8dedf2688fee4bfa40`。它仅为SAMPLE，不是实测。分组/uncertain显示测试使用显式TEST ONLY派生载体，不修改该官方样例。

本切定点：上述命令再增加 `--tests com.aneb.probe.research.ResearchAnalysisStoreTest`。完整Android门等待PMO统一排队；旧APK/旧完整门不能继承到当前代码。
