# ANEB Pilot v0.2 外部试用与操作反馈表

- **协调 Issue**：[#61](https://github.com/lucassu2012/ANEB_GPT/issues/61)
- **模板 Schema**：`aneb-pilot-v0.2-feedback-0.1`（对应同目录 `PILOT_V02_FEEDBACK_TEMPLATE.json`）
- **当前默认执行状态**：**`NOT_RUN`**（外部真实试点尚未执行；填写前禁止引用历史测试数据冒充本次外部试用结果）

> **状态枚举**：`PASS | HOLD | FAIL | NOT_RUN`
> **协助级别枚举**：`NONE | OPERATOR_ASSISTED | NOT_RUN`

---

## 1. 交付包与校验元数据（操作员填写）

| 字段 | 填写内容 |
|---|---|
| `kit_version` | `pilot-v0.2` |
| `release_candidate` | `____` |
| `source_commit`（40 位 hex） | `____` |
| `verify-pilot-v0.2-kit.ps1` 退出码 (`kit_verifier_exit_code`) | `____` |
| `kit_verifier_status` | `PASS | HOLD | FAIL | NOT_RUN` |

---

## 2. 场次与环境信息（操作员 + 复核者填写）

| 字段 | 填写内容 |
|---|---|
| 日期 / UTC 时间 (`date_utc`) | `____` |
| 时区 (`timezone`) | `____` |
| 参与者代号 (`participant_alias`) | `____` |
| 操作员 (`operator_name`) | `____` |
| 独立复核者 (`independent_reviewer`) | `____` |
| Windows 版本 (`windows_os_version`) | `____` |
| 手机型号 (`device_model`) | `____` |
| Android 系统版本 (`android_version`) | `____` |
| 网络环境 (`network_environment`) | `WIFI | 5G_NR | LAN_PRIVATE | MIXED | UNKNOWN` |

---

## 3. 数据保管与脱敏确认（外发前必签）

| 字段 | 填写内容 |
|---|---|
| 保管人 (`custodian`) | `____` |
| 允许接收人 (`approved_recipients`) | `____` |
| 保留复核日期 (`retention_review_date`) | `____` |
| 是否已完成隐私与密钥脱敏 (`pii_and_secrets_redacted`) | `true | false`（已移除密钥/Token/密码/私人截图与通知栏） |

---

## 4. 分步执行检查清单

| 步骤标识 | 检查内容 | 状态 (`PASS/HOLD/FAIL/NOT_RUN`) | 协助 (`NONE/OPERATOR_ASSISTED/NOT_RUN`) | 关键记录（Run/Campaign ID、变体、耗时、退出码、原话备注） |
|---|---|---|---|---|
| `kit_offline_verify` | 运行 `verify-pilot-v0.2-kit.ps1` 并确认 `PASS PILOT_V02_KIT_VERIFIED` | `NOT_RUN` | `NOT_RUN` | 耗时（分）：`____`；备注：`____` |
| `apk_install_and_switch` | 保存旧数据后安装/切换 `probe-release.apk` 或 `probe-prototypeRelease.apk`（无签名冲突） | `NOT_RUN` | `NOT_RUN` | 当前变体：`release / prototypeRelease`；备注：`____` |
| `miw2_basic_and_loaded_responsiveness` | `probe-release.apk` 连接 `miw2` 完成基础测速与 `LoadedResponsiveness v2` | `NOT_RUN` | `NOT_RUN` | `run_id`：`____`；`loaded_status`：`____` |
| `miw2_token_aqs_and_paired_compare` | 完成 Wi-Fi 与 5G Token AQS 测试并在 `结果` 页配对对比 + 导出 MD/PNG | `NOT_RUN` | `NOT_RUN` | Wi-Fi `run_id`：`____`；5G `run_id`：`____`；导出 MD/PNG：`____` |
| `miw2_profile_v2_trace` | `设置 -> Profile 与指标合同 -> 多模态 Token 业务 -> 运行行为轨迹仿真`（20/20） | `NOT_RUN` | `NOT_RUN` | `run_id`：`____`；完成任务数：`____ / 20` |
| `prototype_launcher_ready_and_compatible` | `prototype-launcher\START_ANEB.bat` 就绪且手机 `检查连接` 显示 `兼容` | `NOT_RUN` | `NOT_RUN` | 所选地址类型（Wi-Fi/有线/高级）：`____` |
| `prototype_quick_3` | 运行一次 `Quick`（3 次）、导出 `Export ZIP` 并校验 7 文件证据（`G0_VERIFY_OK`） | `NOT_RUN` | `NOT_RUN` | `campaign_id`：`____`；状态：`____`；`verify-evidence` 退出码：`____` |
| `prototype_acceptance_9` | 运行一次 `Acceptance`（9 次）、导出 `Export ZIP` 并校验 7 文件证据（`G0_VERIFY_OK`） | `NOT_RUN` | `NOT_RUN` | `campaign_id`：`____`；状态：`____`；`verify-evidence` 退出码：`____` |
| `saved_campaigns_reopen` | 离开结果页后从 `已保存的测试` 重开历史任务（ID/状态/数值一致且未重测） | `NOT_RUN` | `NOT_RUN` | 备注：`____` |
| `clean_exit_and_device_cleanup` | 原启动器输入 `Q` + Enter 退出，手机停止本轮页面/服务且无残留设置 | `NOT_RUN` | `NOT_RUN` | 备注：`____` |

---

## 5. 可用性、理解度与试点决策访谈

1. **最难的一步 (`hardest_step`)**：`____`
2. **参与者原话困惑记录 (`verbatim_confusion_quotes`)**：`____`
3. **能否准确区分 `verified / G0_VERIFY_OK` 与测量成功 (`can_explain_verified_vs_success`)**：`true | false | null`
4. **能否准确区分合成节点测试与真实第三方 App / 运营商 SLA (`can_explain_synthetic_vs_real_app`)**：`true | false | null`
5. **本轮报告能支持什么具体决定 (`supported_decision`)**：`____`
6. **报告还缺少哪些关键信息 (`missing_report_information`)**：`____`
7. **阻塞性问题 (`blocking_issues`)**：`____`
8. **最小可落地的改进建议 (`minimal_improvement_suggestions`)**：`____`

---

## 6. 独立上手评估与下一阶段门禁（禁止代填购买意愿）

| 字段 | 填写内容 |
|---|---|
| 独立完成评估 (`independent_completion_assessment`) | `PASS | PARTIAL | BLOCKED | NOT_RUN` |
| 预算决策角色 (`budget_owner_role`) | `____` |
| 进入有价试点的现实障碍 (`paid_pilot_barriers`) | `____` |
| 下一步行动 (`next_action`) | `____` |
