# R1 本地研究记录与分析工作流

本功能把人工研究记录、桌面分析与 Android 中文查看接起来。它不自动操控其他 App、不访问录像、不把端到端耗时归因为网络，也不改变 Prototype / AQS / RPI 或已发布包。

## 仓库内可重复的 SAMPLE 路径

从仓库根目录运行：

~~~powershell
python -B scripts/analyze_research_records.py app/probe/src/test/resources/research/r1-sample.json
python -B -m unittest discover -s tests -p test_analyze_research_records.py -v
~~~

首个命令只读输入，将分析 JSON 写到 stdout，不修改原文。需要保存输出时使用 UTF-8，不改变原始输入；Windows PowerShell 5.1 的默认重定向编码不能直接当 UTF-8 文件。

仓库已带同一实际 CLI 输出，便于不重写数据地联调：

- 原文：app/probe/src/test/resources/research/r1-sample.json
- 分析：app/probe/src/test/resources/research/r1-analysis-sample.json

二者都是明确虚构的 SAMPLE。预期三条记录：计划3、已尝试2、未执行1、可见完成已确认1；无已确认可比组。R1 首反馈[0.76,0.84]秒、首内容[1.96,2.04]秒、完成[7.96,8.04]秒；R2完成不可用，R3未执行，不填零。

## Android 用户路径

结果页 → App研究记录 → 选择原文 JSON → 预览并保存 → 选择分析 JSON → 核对关联提示并保存分析副本 → 查看中文逐次结果。重开原文后可选择已保存分析副本。原文和分析分别确认隐私后导出，不附带录像，不自动上传或分享。

原文与分析按原始输入字节的 source_sha256 和批次内 attempt_id 配对。文件字节改变即新来源，不能只因文件名/尝试ID相同而混用。匹配仅说明文件/身份关联；不证明媒体真实或分析数值已独立复算。

显示保留秒区间、NA、不确定及原因。尾段尚未恢复不当作完整停顿；缺全程覆盖时总停顿/最大停顿未知，不填零。分组统计中的 point_n、中位数、范围仅属于确定点值子集，不能冒充全组统计或跨 App 排名。

## 实际观察与隐私

OBSERVED 只表示输入声称来自实际观察。记录/查看工具保留此声明并提示未核验；缺来源引用/媒体哈希应人工核对，不生成假的录像或哈希。分析 CLI 要求实际观察记录有本地来源引用，但不会打开或核验该媒体。失败、取消、不完整与现场确认的未执行均保留；SAMPLE 和 OBSERVED 不混批。

此版本支持导入已有记录，不包含手工录入表单或自动采集。原始记录和完整分析输出可能含操作文本和本地证据引用，应只放本机；不要贴公开 issue。未保存预览在 Activity 重建后需重新选择；已保存文件与分析副本独立持久化。

## 验证边界

组件测试、Android debug 质量门、集成门、真机界面与 PO 验收分别记录，不能相互代替。debug 能力包不是已签名发布候选。真机操作仅由指定设备任务在即时安全检查通过后执行；现场未知会话不接管。

本批集成来源：共同基线73913491，Android f190088（含3ece166d、909627b），分析498b811（含5df8c6c、1598b72）。具体用法另见 app/probe/RESEARCH_RECORDS.md 与本目录 ANALYSIS_CLI.md。
