# evidence/ — 验收证据目录

借鉴 ANEB Android Echo 切片项目的四态证据制度（见 `docs/参考_ChatGPT侧ANEB_AndroidEcho方案与进展_2026-07-11.md` §4）。

## 规则

1. **四态**：任何验收检查只允许 `PASS / FAIL / NOT_EXECUTED / BLOCKED_EXTERNAL` 四种状态。缺设备、缺服务器、缺端点**绝不折算成 PASS**。
2. **PASS 必须有证据**：命令 + 原始输出 + 产物文件落盘到本目录对应子目录（按阶段分：`phase0/`、`phase1/`…）。
3. **清单脚本化**：`sha256-manifest.txt` 由脚本生成（挂进验证链），禁止手动维护——手动清单必过期。
4. **命名禁区**：本目录及子目录命名避开 `build`（防被构建产物排除规则误伤）。
5. **日志编码**：Windows 下重定向日志一律 `Out-File -Encoding utf8`，保留失败的原始输出，失败-修复链必须可审计。
6. **成功主路径优先**：每阶段第一条证据必须是"成功主路径完整实跑"（如阶段 0 的 S1 全流程 + 全时间戳打印），不允许只留 fail-closed 防御分支的证据。

## 状态文件

各阶段验收状态记录在 `phaseN/STATUS.json`，字段：`{check_id, state, command, evidence_files[], date}`。
