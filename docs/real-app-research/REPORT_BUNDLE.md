# 本地离线研究包

这是研究交付工具，不是正式 evidence schema、Android 内置导出或评分器。它复用
`research_report.py`，不运行分析、不合组、不作跨 App 排名。

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

全部输入通过检查后才创建输出。输入缺失/错配返回非0和 `BUNDLE_INPUT_INVALID:<code>`，
不生成包。已存在目录拒绝，不覆盖。写盘故障可能留下部分新目录，不能当完成包；保留现场，
不自动清理或复用。不要让其它进程修改导出目录。

## 内容与隐私

- `index.html` → 各 App HTML：已有指标、NA/uncertain、结论卡原文、返回入口。
- `sources/`：所选 analysis / adapted input / 结论卡原字节；不会沿任何内嵌路径追读媒体。
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
文件清单、转义、媒体不追读以及拒绝覆盖/路径逃逸。没有 Android、设备、正式发布门。
