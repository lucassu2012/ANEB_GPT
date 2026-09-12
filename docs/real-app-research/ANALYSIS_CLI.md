# R1 内部记录处理首切

仅消费1a `alignment-1`，不是正式schema或网络评分。无设备/服务/联网依赖，Python标准库。

```powershell
python -B scripts/analyze_research_records.py E:/ANEB-Research/R1-20260912/1a/SAMPLE_INPUT.json
python -B -m unittest discover -s tests -p test_analyze_research_records.py -v
```

输入只读，JSON输出到stdout。`source_sha256`绑定输入原始字节；`records[].attempt_id`及`input_record`保留来源、原始事件、失败与NA原因。输出包含原始记录，**仅本地使用，不直接贴公开GitHub**。本工具不校验引用媒体存在性，不把SAMPLE变成实测。

毫秒同视频域区间相减，输出显示秒区间`interval_s`，不取中点。未发送/未映射/缺事件/坏区间返回NA+reason；起止顺序不确定返回uncertain/null，不把负下界截断为0。重复attempt_id拒绝，不丢弃记录来凑结果。

TTFR取send到first_feedback；TTFC取send到first_content。Completion仅completed+yes、非空completion_basis、持续可见和正常退出的显式true、complete_confirm下界−last_content上界至少3000ms时取last_content−send，否则NA。依据均为输入标注，不等于独立媒体验证。

counts为该输入内计划数/实际发送数/未执行数/可确认完成数；SAMPLE仍仅SAMPLE。它不是网络成功率、指令满足率，也不跨App排名。不确定完成不算成功。

1a SAMPLE预期：R1 TTFR[.76,.84]/TTFC[1.96,2.04]/Completion[7.96,8.04]秒；R2 [.46,.54]/[.96,1.04]/NA；R3全部NA；计划3、尝试2、确认完成1、未执行1。

停顿原始区间在input_record.observed_intervals内保留；逐区间输出confirmed（下界≥2s）、below_threshold（上界<2s）、uncertain、right_censored、observation_gap或NA。跨观察缺口不确认停顿，不把尾段当完整停顿。confirmed_observed_count仅为已标注完整区间数；当前输入没有整次完整枚举证明，total_count/max_interval_s始终null，不能解释为零。未提供区间时仍not_computed。

批次仅SAMPLE或OBSERVED且逐条一致，未知/混批拒绝。OBSERVED必须提供非空本地来源引用，但工具不验证现场真实性；来源声明不等于已验收。CLI仅source_sha256绑定原始输入字节，与attempt_id共同回指，不是媒体哈希。不跨批次合并或自动累计。

groups仅接受summary_group.confirmed=true且id、record_kind、App/name/version/model_mode、method_id、action_text、condition、metadata/device/network_description、anchors/send/feedback/body均非空且非UNKNOWN/NA。即便id相同，比较字段不同也拆组；视频时钟域不参与跨尝试分组。未入组记录仍在records，不自动补组或跨App排名。

各组TTFR/TTFC/Completion保留attempts逐次值与NA/uncertain原因。valid_n是合法区间数；point_n是上下界完全相等的数量。point_median_s/point_range_s只描述这些确定点值，绝不将区间取中点，不冒充全组中位数或置信区间；没有点值则null。完整停顿的整次total/max继续null。失败和未执行记录不从组内attempts删除。不自动填零、不作统一评分。内部标注输入不是通用不可信JSON验证器。

未运行Android/server全量quality_gate，不声称集成/设备/PO验收通过。
