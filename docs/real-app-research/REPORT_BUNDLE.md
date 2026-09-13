# 本地离线研究包

这是研究交付工具，不是正式 evidence schema、Android 内置导出或评分器。它复用
`research_report.py`（文本）和`research_video.py`的render（视频），不运行分析、不复制
计算、不合组、不作跨 App 排名。Android与正式schema不变。

## 使用

```powershell
python -B scripts/research_bundle.py C:/local/input-manifest.json -o C:/local/new-bundle
```

输出目录必须不存在，父目录必须存在。双击输出的 `index.html`；移动时保留整个目录。
无需服务器、外部样式或网络。此版本交付目录，不生成 ZIP。

输入清单只用于显式选定本地研究文件。例如（以下名称/哈希为 SAMPLE 占位，须换为实际字节）：

```json
{
  "apps": [{
    "id": "sample",
    "app": "SAMPLE App",
    "analysis_file": "analysis.json",
    "analysis_sha256": "<64 hex>",
    "source_file": "adapted-input.json",
    "source_sha256": "<64 hex>",
    "conclusion_file": "conclusion.md",
    "conclusion_sha256": "<64 hex>",
    "conclusion_analysis_sha256": "<same analysis SHA256>"
  }]
}
```

路径相对输入清单或为绝对本地路径。ID 为小写字母开头、后接小写字母/数字/连字符，
最多64字符，不能是 `index`，不可重复。analysis/source 只能 `.json`，conclusion 只能 `.md`。
结论卡正文必须包含对应 analysis 的完整 SHA256；清单还显式记录卡所绑定的分析 SHA。
analysis.source_sha256 必须对应所选 adapted input。每条分析记录 App 名称必须与清单一致。
这些检查只绑定字节与显式引用，不认证卡的作者/事实正确性；人工审阅仍必需。

## 视频显式入口（兼容旧文本清单）

每entry新增可选`kind`，省略或`text`仍严格执行原文本/结论卡绑定。
视频必须显式`kind: "video"`，不按结构猜测类型；其它值拒绝。
视频entry仅需`id/app/kind/analysis_file/analysis_sha256/source_file/source_sha256`。
source为`research-video-1`的SAMPLE/OBSERVED，或原始
`video_observation_preflight_blocked` NOT_RUN记录；analysis是视频CLI已派生JSON。
视频不需要结论卡，也不读取/复制额外提供的结论字段，页面自动保留原标注与reason。

视频校验analysis.source_sha256、完整input_record与source相符，App、record_kind、
slot/status逐行对应、执行/未执行槽数一致；blocked必须0执行。它不重算/认证已绑定的
首帧区间或窗口指标。视频页面复用既有renderer（含截止NA/uncertain中文原因）。
首页分别标注有文本观察的App数、视频未执行状态、SAMPLE入口数；SAMPLE不算真实观察，
不把视频计划槽算作实测App或播放失败，不生成体验结论/成功率/RPI。

例如在原`apps`数组追加（所有哈希须换为真实字节值）：

```json
{
  "id": "video-not-run",
  "kind": "video",
  "app": "与原JSON一致的App名",
  "source_file": "OBSERVATIONS.json",
  "source_sha256": "<source SHA256>",
  "analysis_file": "ANALYSIS_FINAL_CUTOFF.json",
  "analysis_sha256": "<analysis SHA256>"
}
```

全部输入通过检查后才创建输出。输入缺失/错配返回非0和 `BUNDLE_INPUT_INVALID:<code>`，
不生成包。已存在目录拒绝，不覆盖。写盘故障可能留下部分新目录，不能当完成包；保留现场，
不自动清理或复用。不要让其它进程修改导出目录。

## 内容与隐私

- `index.html` → 各 App HTML：已有指标、NA/uncertain、结论卡原文、返回入口。
- `sources/`：所选 analysis / adapted input / 文本结论卡原字节；视频仅两份JSON。
  不会沿任何内嵌路径追读媒体；SAMPLE仅用于单独演示，不混进实际研究批次。
- `bundle-manifest.json`：改为包内相对路径的相同绑定。
- `README.txt` 与 `SHA256SUMS.txt`：使用说明、自排除校验清单；不是签名或完整性攻击防护。

结论以 HTML 转义的原文显示（含 Markdown 标点，不执行 Markdown/HTML/媒体引用）。
JSON 和卡内可能有本地研究文字及原路径，包仅限授权本地研究使用，不要公开上传。
不含视频/截图/可执行脚本；不使用原始私密视频或准备截图。SAMPLE/OBSERVED 逐 App 标明。
可见结束、指令满足、有效完成时长是不同事实；工具不改这些值。

## 定点测试

```powershell
python -B -m unittest discover -s tests -p 'test_*research*.py' -q
```

测试使用临时合成 SAMPLE 数据，覆盖来源/结论绑定拒绝、原值复用、三 App 本地链接与
文件清单、转义、媒体不追读以及拒绝覆盖/路径逃逸；另含显式视频SAMPLE/NOT_RUN、
跨类型拒绝、来源/slot绑定与混合包链接/计数分离。没有 Android、设备、正式发布门。
