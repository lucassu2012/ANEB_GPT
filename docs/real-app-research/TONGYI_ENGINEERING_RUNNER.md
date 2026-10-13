# 通义普通文本私有工程采集器

## 显式只读 UI_SOURCE_INSPECTION

［KNOWN｜HIGH］`--ui-source-inspection` 必须与固定 release 的 `execution_mode=UI_SOURCE_INSPECTION` 同时出现，并仍需 `--execute-new-feature-release`。这是同一 HOST/Native/private-core 的一次新 create-once 事务，不是 W1/C1 采集、input-only UIQ 重试或新工具。`pair_id-SOURCE` 和 `-SOURCE_CONSUMED.json` 独立消费，`retry_allowed=false`；旧 r2 的 UNKNOWN 及其原件不改变。

［KNOWN｜HIGH］该路径通过既有 `run_pair` → `TongyiWholeEngineeringInstrumentation` → `TongyiUiReferenceQualificationRunner.runSourceInspectionOnce`。后者及 Android `retainSource` 是明确新增的公开边界，不把缺符号/编译错误冒称既有 API 的自然 RED。软件测试只替换外部 Android/OS/time/file 边界；不能证明实际设备资格。

［KNOWN｜HIGH］只观察两个独立当前 HOME，分别标记 `source-home-1`、`source-home-2`，不加入原 UIQ 六个 Stage。每次仍为 Native-before → 原始 UiAutomator XML → 公开 UiAutomation 截图 → Native-after；恰好一张截图/一份 software Bitmap，同一 Bitmap 生成 PNG 和原封不动的 `ANEB807_MENU_ARGB_V1` canonical digest。保存并逐字节读回四份真实原件及 bytes/SHA；两个不同 observation ID、顺序时钟、owner、显示/root 和完整当前结构绑定必须闭合。两个 menu digest 必须存在且完全相等；不要求它等于旧离线 MENU_SHA256，不做 normalization、容差、遮罩或自动 repin。

［KNOWN｜HIGH］这是 source acquisition/retention/owned cleanup 闭合，绝非 reference 接受：`reference_accepted=false`、`accepted_stages=[]`、`retained_stages=[]`、SEND0、capture `NOT_STARTED`、metrics `null`、formal0、RPI `not_computed`。不点击菜单/新会话/输入框，不 TYPE/清草稿/SEND/IME/Enter，不录像或切网络。原 PREP180/READY20/input5/HOST220 限制及 reader 原数据保留的 update/restore/companion 清场不变。

［KNOWN｜HIGH］source 模式每个已取得原件在后续检查前保存；缺原件、不可读、过期、owner/结构漂移或两幅 digest 不同，均保存实际完整或 partial 原件并停止为 UNKNOWN/STOPPED，不重试、不会改旧18ac常量。HOST原件pin失败也保留已拉回的全部文件，不把未知发送归零。实际 source identity、两套原件和当前手机资格仍由 Root 独立检查；只有另行记录的 Root 决策才能接受 versioned current FRAME 并同步常量/直接受影响夹具/卡。本功能本身不授权手机、不接纳参考、不放行输入。

［KNOWN｜HIGH］这是现有 `:probe` 的 Debug/test-only 功能，不是第二个产品、Windows GUI 的替代工具、生产发布或 M6 样本入口。正式新增槽为 0。它不计算 TTFR/TTFC/Completion/Stall/RPI，也不判断回复正确性或网络性能；原视频语义覆盖和后台模型/会话身份保留 UNKNOWN。

## 整件入口

HOST 入口：`scripts/tongyi_ordinary_text_engineering_runner.py` 的 `run_pair(release, output_root)`；Android 入口：`TongyiWholeEngineeringInstrumentation.collectOnePrivateEngineeringObservation`；纯 Debug 核心：`TongyiEngineeringRunner.runOnce`。

HOST 顺序执行 W1→C1。仅 W1 原件封存、原生终端及自有清场都闭合，C1 才能开始。每轮至多一个发送调用；调用抛错或结果不明保留 UNKNOWN，不重试。CLI 导入和缺少显式新功能放行时没有设备动作。输出目录及 Android run-id 目录均 create-once，已消费机会不可重放。

私有新合同允许官方 Android instrumentation/UiAutomator/UiAutomation；不把 681 DeepSeek、旧 M6、旧 HOST 模型或 797–804 失败当通义资格，不通过本工具规避 Windows 工具限制。新设备执行必须由 Root 在固定 APK/配置/动作/目录和当前设备事实上另行放行。

［KNOWN｜HIGH］HOST 准入/收口读取完整 `dumpsys window` 的唯一当前 focus，并保留独立 activity resumed Launcher、在线/RC、进程冲突与归属门；不从 vendor 的 windows 子集缺字段推断当前焦点。网络门只理解明确且完整边界的 `Current Networks` 中实际 `NetworkAgentInfo` 和唯一 `Active default network`：每个当前 agent 的身份/CONNECTED/物理 WIFI或CELLULAR transport/NOT_VPN 均须可理解，default必须对应当前 agent且有INTERNET/VALIDATED。任何真VPN当前agent（包括非default）、未知transport/agent/default、空/缺失/结构不明输出均继续HOLD。系统server的VPN LISTEN或历史/请求模板不是活动agent，不单独据其拒绝或声明全局VPN/内核清洁。此兼容修正只依据Root固定只读原件的结构；不证明当前WiFi是获准热点、不替代Root当前设备前后置，不改变原APK身份/保留数据恢复合同。

有限HOST回归通过真实 `run_pair` 仅替换外部ADB边界，使用脱敏结构正文验证完整W1→C1收口；没有私有SSID/地址或旧聊天进入Git。旧foreground/真VPN/归属拒绝和有限未知当前网络负例保留。HOST通过不回填旧Android门/CI/APK，也不算只读诊断已消费采集机会。

## 构建与检查

在 `app/` 目录：

```powershell
.\gradlew.bat :probe:testDebugUnitTest --tests com.aneb.probe.research.engineering.TongyiEngineeringRunnerTest --offline --no-daemon --no-parallel --max-workers=1
.\gradlew.bat :probe:assembleDebug :probe:assembleDebugAndroidTest --offline --no-daemon --no-parallel --max-workers=1
```

HOST 检查从仓库根执行：

```powershell
python -m unittest scripts.test_tongyi_ordinary_text_engineering_runner
```

这些不是完整 Android quality gate、CI、APK 实机资格或科学测量验收。首次缓存缺失时只能在另行允许的既有官方仓库边界内获取原声明依赖，不改版本/仓库/签名。正式移交前 Root 运行一次完整门并核验固定源与 APK；不运行 connectedAndroidTest 作为离线检查。

## Root 放行配置：必须真实提供，不能填占位值

配置是本次新功能执行控制，不是正式 evidence schema。需要以下字段：

| 字段 | 实际来源与门 |
|---|---|
| `live_release_authorized` | Root 新功能固定卡明确 true；CLI 还必须带执行开关 |
| `serial`, `pair_id` | 实际设备序列号、新工程首对身份，1–64 个字母数字/下划线/短横线 |
| `adb`, `java`, `apksigner_jar`, `aapt` | 现有官方本机文件绝对路径，各自 `*_sha256` 真实完整 SHA |
| `debug_apk`, `test_apk` | 新候选 APK 绝对路径，各自 `*_sha256`；实际 aapt 包名与 apksigner 证书检查 |
| `approved_debug_certificate_sha256` | Root 固定的新/旧 Debug 证书；不能借生产 signer |
| `original_debug_apk`, `original_debug_apk_sha256` | 若当前已装研究阅读器，Root 固定的可恢复原 APK；执行前 fresh pull 当前唯一 base APK，完整 SHA/证书必须相符 |
| `network_transition_authorized` | 只在 Root 允许这轮 W1 WiFi→C1 SIM 的切换和恢复时 true；未 true 时 HOST 不切网络，Android 仍须实际条件匹配，否则停止 |
| `additional_conflicting_packages` | Root 已知需要排除的 VPN/采集/业务包，不由 HOST 猜身份 |
| `ui_contract` | 下述当前可见语义对象及空态/普通文本/无附件合同；历史 XML 不替代当前事实 |

`COLLECTION` 的 `ui_contract` 必须含 `initial_empty`, `ordinary_mode`, `menu`, `new_session`, `new_empty`, `focus_hint`, `send` 七个 selector。每个 selector 至少包含一个非空 `text` / `description` / `resource_id` / `hint`，并可提供其余字段作精确收窄。Android 每次重读实际当前树、所有祖先 visible-to-user、目标包、唯一匹配；点击还须 enabled/clickable。没有当前可识别 SEND/mode 对象就停止，绝不猜坐标或用旧树自动填值。

另外必须有 `attachment_resource_ids`；非空时实际可见树扫描附件 ID。显式 `[]` 只允许 `new_session_text_only_attachment_contract=true` 的已接受新会话纯文本合同；此合同不是通用证明 App 内没有任何隐藏附件。执行不打开附件选择器或读取旧聊天。Root 首实机必须核实当前空态/模式/无附件与完整文本原件，不能由 HOST 绿推导。

运行命令（只有 Root 新放行后）：

```powershell
python scripts/tongyi_ordinary_text_engineering_runner.py --release <Root固定配置.json> --output <新独占绝对目录> --execute-new-feature-release
```

## 同件 PREPARATION_ONLY（SEND0，不是采集资格）

［FRAME｜LOW］这是 Root 已接受的当前 `1200×2640 / rotation=0 / root[0,0][1200,2486]` 工程 image-node 绑定，不是 vendor semantic、跨版本菜单身份或截图/树/点击的原子事务。实际公开 `UiAutomation.takeScreenshot()` 的 menu 区域 `[56,193][156,293]` 必须按 UTF-8头 `ANEB807_MENU_ARGB_V1\n100\n100\n` 加 y/x 顺序 ARGB字节的规范 SHA256，完全等于 `18accc0d7ec5ea76e5add0fec6ce6898809bbf73fb4760604ac30f2ca25da89f`。任何一像素、alpha、viewport/rotation/可见性/owner/结构不符即停止；不近似、不缩放、不自动重绑、不坐标点击、不 hidden API。

［KNOWN｜HIGH］核心真实入口是 `TongyiPreparationRunner.runOnce`；Native 同一 instrumentation method 在 `execution_mode=PREPARATION_ONLY` 时调用它。输入-only Android 能力面没有 send/recording 方法；Native 原 send/recording 方法还拒绝 preparation mode。HOST 同一 `run_pair` 公共入口根据固定 release mode 只执行一次 `-PREP`，不进入 W1/C1循环、不传 network_condition、不切 WiFi；仍复用全套 APK/cert/fresh reader 检查、owned instrumentation清场、数据保留恢复与 postflight。HOST supervisor≤220s，Android PREP180/READY20/focus与输入读回5s不放宽。

新 release 需 `execution_mode="PREPARATION_ONLY"` 且 CLI 明确 `--preparation-only --execute-new-feature-release`，不得 `network_transition_authorized=true`。其它工具/APK/cert/serial/pair_id/原reader pins沿用上表，`ui_contract` 只需：

```json
{
  "binding_scope": "root_current_807_ui_frame",
  "menu_argb_sha256": "18accc0d7ec5ea76e5add0fec6ce6898809bbf73fb4760604ac30f2ca25da89f",
  "attachment_resource_ids": [],
  "new_session_text_only_attachment_contract": true
}
```

［KNOWN｜HIGH］未知 send selector 可以缺席，不调用原 collection snapshot/matching("send")。menu筛选唯一可见 enabled/clickable FrameLayout[56,193][156,293]，精确 parent/toolbar结构及空主页/快速见证；截图前后树相等，立即动作前重新读 owner/tree/pixels并 refresh。新建对话用真实 text=`新建对话`、resource ID=`com.aliyun.tongyi:id/tv_new_session` 的唯一 TextView到精确direct LinearLayout parent[242,2339][813,2486]；focus用唯一 text=`发消息或按住说话...`到精确direct ViewGroup parent[40,2348][1160,2486]，不任意向上找clickable ancestor。每步 public ACTION_CLICK后需真正观察预期drawer/new-empty/focused editor；返回true本身不是后状态。

［KNOWN｜HIGH］focused EditText 的 `text=发消息...` 不是字面空值。只按公开 `isShowingHintText/hintText` 区分：非hint文本必须真实为空；hint旗标true时hint须非空且text为空或完整等于hint。未知或不一致就HOLD；真实草稿恰好写着`发消息...`而旗标false不得归零。唯一编辑器须editable/focused/enabled、公开ACTION_SET_TEXT可用。完整239B PAYLOAD一次SET_TEXT并读回 exact全文/非hint，保存draft-current PNG/XML；清除前再核当前owner/编辑器/全文仍完全匹配，才SET_TEXT空并hint-aware读回空。部分/未知/替代草稿不删除，own closure未证实保留UNKNOWN。

［KNOWN｜HIGH］保留新会话/new-empty与draft-current PNG/XML及各自owner时间戳，额外public menu-frame-N.png/json记录本次真实像素pin/viewport/root/owner/单调时间；PNG/XML不是原子同帧。模式无SEND_INTENT、IME/Enter、录像/网络capture、性能结果、W1C1 claim；terminal 为 preparation_inspection_only、send_calls=0、capture=NOT_STARTED、send_selector=UNKNOWN、metrics=null、formal_new_slots=0、rpi=not_computed。HOST只在fresh合法terminal后接受0；异常终端值或不完整原件保留实际值/UNKNOWN并停止，不用计划SEND0覆盖异常观察。

［KNOWN｜HIGH］r4的core/HOST external-platform seam和APK编译不证明实际Android截图兼容、hint flags、TYPE/readback/清draft或设备清场。Root单独新资格之前这些仍NOT_RUN。现有COLLECTION的语义selector、SEND/capture/条件和consumed-intent合同保留；PREPARATION_ONLY成功不自动放行COLLECTION，不补旧机会。后续SEND发现只由Root审阅新draft-current原件并另行固定合同，本轮不发现后继续SEND。

## 安装、原阅读器与数据边界（共用）

当前安装包不明、split APK、证书不符、完整 APK SHA 不符或 companion test 包已存在，均 HOLD，不覆盖、不卸载、不清数据。已固定原阅读器时，仅相同证书的 `install -r -d -t` 数据保留更新；结束恢复原 APK 并重新 fresh pull 核完整 SHA/证书。原包绝不卸载。只有本轮前不存在且自己安装的 package 才可卸载；当前自有/未知进程不闭合时连卸载/恢复都停止，原件保留并交 Root。

原件传输不完整或 native terminal 缺失时，保留存放远端原件的新 Debug 包，不卸载它来丢失未传回文件；明确 PACKAGE_CLEANUP 未完成，交 Root 恢复。恢复原有 APK 不清数据，保留该包的原件路径。

使用现有 Debug 身份 `com.aneb.probe.codex`，companion 为它的 instrumentation test APK；不另造 App 身份绕开当前阅读器。更新不重建数据库、不清应用数据；候选在旧 reader 数据上运行的安全性仍需固定源完整门与新设备资格，不由 install Success 宣称。

## 时限、原件和终端含义

Android elapsedRealtimeNanos 的准备预算 180 秒、就绪等待最多 20 秒、输入/GO 新鲜度 5 秒均未放宽。录像从录制请求起最多 90 秒，不用 send-return 重新增加一段录像。HOST `am instrument` supervisor 上限 310 秒仅覆盖准备 180+录像 90+有界清场 40，不能代替或延长 Android 准备时限。

SEND 前完整固定文本 UTF-8/noBOM/no尾换行 239B，SHA `f1e312c9123478fcf64c9ad056a436a493f6bd5dde09af029fc8ad62186efac5`。先封存当前 PNG/XML，再 fsync SEND_INTENT，之后一次 ACTION_CLICK。`send_calls=1` 只表示该调用返回，不代表送达/回复/完成；不明始终 UNKNOWN。

screenrecord 的启动门是同 PID/start-ticks、当前自身 MP4 的 ftyp 及文件实际增长，不是 PID=首帧或 0B 文件=READY。结束用 MediaExtractor 保存实际 video track、sample count、原件 presentation-us 范围；这是媒体结构事实，不是解码/全片语义、TTFR 或网络时延证明。原视频是否覆盖 SEND/首响应仍 UNKNOWN，必须下游真正观察原视频才裁决。

每轮保留 complete-draft / after-send / capture-end PNG/XML、OWNED_TARGET、OWNED_CAPTURE、OWNED_INSTRUMENTATION、SEND_INTENT、原 MP4、screenrecord stdout/stderr、FINAL_MEDIA、TERMINAL 和 HOST 每条 argv/raw/RC/进程结束/EOF 收据。部分 pull 失败仍 pin 已到文件，缺失原件不补造。运行目录原样保留，失败不贡献任何指标。

Android 原始 operational clock 与 HOST monotonic 是不同域，不相减；MP4 PTS 为第三个原件域，不默认映射前二者。后台模型、网络实际路径、会话独立性、回复成功率和评分均 UNKNOWN/not_computed。

## 取消与清场

在本轮 HOST 输出目录创建 `CANCEL` 可请求停止；HOST 只向自身 run-id 路径写 CANCEL，Android 在等待/观察/动作门检查，不重新 SEND。超时/中断只终止本 HOST argv 子进程；远端身份仍须独立核实，不能把 adb 退出当手机清洁。

Android 只结束自己启动且 PID/UID/start-ticks仍匹配的通义与 screenrecord；只有当前编辑框恰为本轮固定全文才清除本轮草稿，未知替代草稿不触碰。它不终止自身来伪造正常 instrumentation return。HOST 在原件 pull/terminal 后核 OWNED_INSTRUMENTATION 与 fresh PID/UID/ticks/cmdline，才停止自己的 target app 进程并作 fresh Launcher/冲突/VPN 检查。

自有进程状态统一由现有官方 Android test-shell 观察，NativePhone 的录像与通义清场实际调用 Debug 核心 `OwnedProcessLifecycle`。只有 RC0、实际流 EOF、未超期且格式明确的完整进程列表才可能证实 Absent；App 身份下 `/proc/PID/stat` 文件不可见不等于退出。Alive 在停止前 fresh 核 PID、实际 UID、start-ticks、完整 cmdline（录像还须本轮原件路径）。空输出、权限拒绝、非零 RC、超时或解析失败都是 Unknown，不停止未知/替换身份、不清除 owner、不验收收口、不启 C1。每次 shell 原件与显式 RC/EOF 保存为 process-shell-*.json。

［KNOWN｜HIGH］API29 的公开 `UiAutomation.executeShellCommand(String)` 在 AOSP 经 `Runtime.exec(String)` 按空白分词，不解析 `sh -c '多词正文'` 的引号。现有两个真实入口——processShell 的 RC 包装与 screenrecord 启动——共用 Debug 核心 `OwnedShellScriptTransport`：在已独占的本轮目录 create-once 写入并 fsync 完整 `shell-script-*.sh` 私有原件，再直接提交 `sh <单个安全路径token>`。路径有空白、不安全字符、别名目录或已有脚本时，调用前 fail-closed；不调用 hidden/Rw/Rwe、不反射、不增权限、不提高 minSdk。旧简单 pidof/cat 读路由不变。

有限回归直接检验同一 production transport 的实际 String emission，经外部 AOSP/JVM `StringTokenizer` 边界读取完整进程和录像正文；旧 quoted emission 的 RED 与修正 emission 的 GREEN 都是 HOST/platform carrier，不是实际 UiAutomation/P40 成功。脚本、原 stdout、显式 marker/RC、实际 EOF/截止、全进程身份与录像原件路径约束均保留；当前设备能否读/执行本轮路径仍须 Root 另行资格。平台依据：[AOSP Android10 connection](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android10-release/core/java/android/app/UiAutomationConnection.java)、[Android Runtime](https://android.googlesource.com/platform/libcore/+/master/ojluni/src/main/java/java/lang/Runtime.java)。

录像 SIGINT 后在原有20秒清场预算内同时要求同通道实际进程退出、原录像流真实 EOF 无读错与 MediaExtractor 封口；未知退出/媒体保留，不自动重试停止。通义 force-stop 也须同通道确认原 PID 退出及完整 NAME 列表中无目标包/子进程；只有已确认的自有目标收口后才回 Home。此 shell/进程列表在当前设备是否支持、真实媒体/清场是否成功仍待 Root 新资格，HOST 外部 OS 回归与编译不替代实机证明。

只切换获准 WiFi，原 mobile_data/stayon 只读不改；结束恢复本轮 WiFi 原值并核三设置。此工程设置值恢复不单独证明原 SSID、全部后台或网络路由恢复；Root 最后仍按 AGENTS 做现场前置/后置，未知会话不结束，不能凭 Launcher 就宣称全局 clean。

若原件传输、native terminal、录像/目标归属、自有进程退出、设置恢复或原 APK 恢复任一不明，PAIR_STOPPED，C1 NOT_RUN（如尚未开始），原输出保留。不能换目录/runId补旧失败，不把独立之后清场回填原轮成功。
