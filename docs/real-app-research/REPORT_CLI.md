# R1 中文离线报告

运行：`python -B scripts/research_report.py analysis.json -o report.html`

仅消费3a的alignment-1派生输出。输出必须不存在；CLI不覆盖已有文件，不读取媒体，不启动网络，不重新计算指标/成功率/排名。HTML是UTF-8单文件，无外部资源或脚本，可直接在浏览器打开。source_sha256绑定原输入，attempt_id绑定逐次记录。报告不是证据真实性认证。

采用1b REPORT_CONTENT_HANDOFF（2026-09-13 01:22）的SAMPLE披露、未执行优先、未知值、partial/right_censored和网络归因限制。原始毫秒不转存；只展示3a给出的秒区间。NA/uncertain不当零。点值中位数仅代表point_n子集，不代表valid_n整组。

隐私：只展示App名称/版本/模式、attempt_id、状态、派生指标、计数和证据引用/哈希。默认不复制提示词、原始事件、任意missing_reasons、密钥或视频。证据引用为转义后的纯文本，不自动打开。报告仍可能包含本地引用和App信息，应按私人研究文件管理，分享前人工检查；本工具不是任意敏感文本检测器。

输入应由已审3a CLI生成；不是通用敌对JSON验证器，不修改正式schema。OBSERVED只是输入声明；groups为空时不补造比较组。

测试：`python -B -m unittest discover -s tests -p test_research_report.py -v`
测试覆盖实际CLI文件、禁止覆盖、输入保持、HTML转义/无活动资源、隐私白名单、停顿/点值子集限制、未执行披露。旧static/report-template.html和dashboard.py属于合成负载/AQS，不直接套用其测量语义。仅沿用单文件布局和stdlib转义方式。

本切不运行Android/server全量quality_gate，由PMO整合排程。无正式发布、签名、设备或网络性能结论。
