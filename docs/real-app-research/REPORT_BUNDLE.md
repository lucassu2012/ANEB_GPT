# 本地离线研究包

本页区分 Android 私有文本 ZIP、视频 ZIP 与已有桌面研究包。它们都不是正式 evidence schema
或评分器，不重新分析、合组或作跨 App 排名；不改变原研究标注或指标。

## Android 私有文本 ZIP

入口在现有研究记录详情：选择一份**已保存文本原文**及对应分析，再打开已保存的
匹配人工结论卡，点击“导出私有离线报告 ZIP”。没有匹配结论时先导入/选择该分析的
人工结论；原有分别导出原文和分析 JSON 的入口仍独立可用。视频不在这个 ZIP 入口范围内。

确认页显示原文、分析、结论的三个完整 SHA-256，并明确说明：导出整批已保存记录，
不应用当前 App、来源、condition 或会话筛选；包可能含私密文字和本地引用路径。
选择“取消 · 不导出”不创建下载；选择“确认导出 ZIP · 仅本机”后在后台生成。
忙碌时不接受第二次导出。只有写入、ZIP 完成和输出流关闭均成功，才发布到
`Downloads/ANEB` 并显示成功；失败保留本机原文/分析/结论，不用不完整包冒充成功。

每包固定五个安全文件名，不从输入引用拼接路径：

- `index.html`：UTF-8 离线首页、整批逐次原值/缺失状态、已有导入分组及人工结论原文。
- `source.json`、`analysis.json`、`conclusion.md`：重新从实际 store 验证读取的原字节，
  不重序列化、不换行转换。
- `SHA256SUMS.txt`：覆盖首页与三原件，自排除清单；仅字节核对，不是签名或真实性认证。

将 ZIP 解压到一个目录，保留全部文件，再用浏览器打开 `index.html`；无需服务器或网络。
外来文字按 HTML 转义的文本显示，Markdown/HTML 不执行；链接只指向固定包内原件。
不会沿 `local_ref` 打开文件，不含截图/录像/脚本，不上传或自动分享。包仍可能包含敏感
文字/原路径，只应在获授权的私有范围内保存。

SAMPLE/OBSERVED 是原输入声明，不做真实性认证；UNKNOWN、NA、uncertain、not_computed、
未执行与不完整的原提示保留。可见完成、指令满足和有效完成时长是不同事实，不能据此
新增成功率、停顿/RPI 或网络因果。修改原值展示时复用已有 reader，不另写科学计算。

实现位于 `ResearchOfflineReportExporter`、既有 `ResearchConclusionStore` 和
`ResearchRecordsScreen`/`ResearchOfflineReportConfirmation`；Downloads 发布仍复用
原 `Exporter`。对应 `ResearchOfflineReportExporterTest` 与
`ResearchOfflineReportDownloadsTest` 使用合成 SAMPLE 和真实 store/ZIP，系统输出边界
可注入失败。代码/JVM 回归、完整门/CI/APK及真机实际点击/Downloads读回分别验收，
不能由这些 JVM 测试推定后几项已完成。

## Android 私有视频 ZIP

在现有研究记录详情打开**已保存的视频原文**，选择本批已保存的匹配分析后，点击
“导出私有视频报告 ZIP”。原文或分析仍为未保存预览时不能导出；视频不需要、也不生成
人工结论卡。支持现有 `research-video-1` SAMPLE/OBSERVED 及已有的 blocked NOT_RUN 格式，
不新增采集、播放或测量入口。历史 NOT_RUN 的交付不是新播放观察，也不计播放失败。

确认页列出原文与分析两个完整 SHA-256，说明私密研究文字/本地路径、整批、不联网、
不上传或分享。取消不创建下载；改选原文或分析使旧确认失效，已消费的确认不能再次写入。
后台 IO 与忙碌防重复复用现有机制；取消是合作式检查，不承诺立即中断同步压缩。
write、ZIP finish 或实际输出 close 失败均不发布成功，本机两原件保留。

每包固定四个文件：`index.html`、`source.json`、`analysis.json`、`SHA256SUMS.txt`。
两份 JSON 是从实际 store 重新校验读取的原字节；清单覆盖首页及两原件，自排除清单。
入口只写 `Downloads/ANEB`。解压并保留四件，再打开 UTF-8 首页即可离线阅读；不需服务器。
不含媒体、截图、APK 或脚本，不沿引用路径读取文件；外来文字/URL 仅转义显示。
ZIP 仍可能含私密原文字段，不应公开上传。

导出前核 source SHA、完整 `input_record`、有序 slot/status 和批次整数 counts。
完整上下文按已有 CLI 的 JSON 解析语义比较，不因对象键序/空白或合法数字往返错拒：
整数保留精确区分，小数/指数按现有 Python CLI 的 binary64 值匹配；这只是关联检查，
不改写两原件、不重新计算首画面/窗口。损坏或错配时拒绝输出，不自动修复/删除输入。

可读页复用现有视频 presentation：保留逐槽原状态、导入秒区间、NA/uncertain/NOT_RUN、
原因、计划终点与覆盖声明。窗口完整不是软件验证连续播放，已执行不是成功率；
不套用文本时长模型、不生成缓冲/暂停、视频 RPI、平均/排名或网络因果。
SAMPLE 仅合成演示，OBSERVED 只是原输入声明，SHA 不认证观察真实性。

维护入口为 `ResearchVideoOfflineReportExporter`、`ResearchVideoOfflineReportConfirmation`
及既有 `ResearchRecordsScreen`，实际发布仍由原 `Exporter` 负责。视频与 849 文本 API 独立，
不要为了视频伪造结论或改变文本五件 ZIP。定点测试类为
`ResearchVideoOfflineReportExporterTest`、`ResearchVideoOfflineReportDownloadsTest`；测试用
合成资源与真实 store/ZIP，只有 OS Downloads 边界替身，非手机点击证据。
新旧文本/视频导入、阅读与 JSON 导出需一起回归。JVM、完整门/CI/APK、浏览器宽窄呈现、
实际手机确认/取消/失败及 Downloads 读回分别验收，不用前一阶段票代替后一阶段。

## 桌面 CLI 使用

以下为原有桌面工具：复用 `research_report.py`（文本）和 `research_video.py` 的 render
（视频）；这一路仍输出目录而非 Android ZIP。

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

## 桌面 CLI 定点测试

```powershell
python -B -m unittest discover -s tests -p 'test_*research*.py' -q
```

测试使用临时合成 SAMPLE 数据，覆盖来源/结论绑定拒绝、原值复用、三 App 本地链接与
文件清单、转义、媒体不追读以及拒绝覆盖/路径逃逸；另含显式视频SAMPLE/NOT_RUN、
跨类型拒绝、来源/slot绑定与混合包链接/计数分离。没有 Android、设备、正式发布门。
