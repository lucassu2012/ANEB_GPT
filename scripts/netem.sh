#!/usr/bin/env bash
#
# netem.sh — ANEB 弱网剖面注入包装（设计文档 §6：VM 预置 tc netem 脚本，
# 用于工具灵敏度验证与红队闭环实验，如阶段 1 验收② netem 100ms/1%）。
#
# 用法:
#   ./netem.sh <iface> <delay_ms> <loss_pct>   在指定网卡 root qdisc 上
#                                              施加固定时延 + 随机丢包
#   ./netem.sh <iface> clear                   移除 netem，恢复默认 qdisc
#
# 示例:
#   sudo ./netem.sh eth0 100 1     # +100ms 时延、1% 丢包
#   sudo ./netem.sh eth0 clear     # 恢复
#
# ！！谨慎提示（E-01 共用服务器）！！
#   netem 作用于整块网卡的全部出向流量——SSH、其他项目的服务一并劣化。
#   只允许在测量窗口内启用，测完立即 clear；启用期间的时间点应记入
#   evidence/（哪些 run 处于弱网剖面下必须可追溯）。忘 clear 的保险丝：
#   建议启用时同时挂一个 `sleep 1800 && tc qdisc del ...` 的兜底任务。
#
# 备注:
#   - 施加用 `tc qdisc replace`（幂等：重复执行=更新参数，不会因 qdisc
#     已存在而报错）；clear 用 `tc qdisc del`。
#   - netem 只整形出向（egress）。服务端网卡上的 netem 劣化"服务端→手机"
#     方向（下行 token 流）；要劣化上行需在客户端侧或中间路由做对称配置。

set -euo pipefail

usage() {
    echo "usage: $0 <iface> <delay_ms> <loss_pct>" >&2
    echo "       $0 <iface> clear" >&2
    exit 2
}

[ "$#" -ge 2 ] || usage

IFACE="$1"

# ---- 存在检查 --------------------------------------------------------------
if ! command -v tc >/dev/null 2>&1; then
    echo "error: tc not found (install iproute2)" >&2
    exit 1
fi
if [ ! -d "/sys/class/net/${IFACE}" ]; then
    echo "error: interface '${IFACE}' does not exist. available:" >&2
    ls /sys/class/net >&2
    exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
    echo "error: must run as root (sudo)" >&2
    exit 1
fi

# ---- clear ----------------------------------------------------------------
if [ "$2" = "clear" ]; then
    if tc qdisc show dev "${IFACE}" | grep -q netem; then
        tc qdisc del dev "${IFACE}" root
        echo "netem removed from ${IFACE}. current qdisc:"
    else
        echo "no netem on ${IFACE}, nothing to clear. current qdisc:"
    fi
    tc qdisc show dev "${IFACE}"
    exit 0
fi

# ---- add / update ----------------------------------------------------------
[ "$#" -eq 3 ] || usage
DELAY_MS="$2"
LOSS_PCT="$3"

case "${DELAY_MS}" in *[!0-9]*|"") echo "error: delay_ms must be a non-negative integer, got '${DELAY_MS}'" >&2; exit 2;; esac
case "${LOSS_PCT}" in *[!0-9.]*|"") echo "error: loss_pct must be a non-negative number, got '${LOSS_PCT}'" >&2; exit 2;; esac

# replace = add-or-update，幂等。
tc qdisc replace dev "${IFACE}" root netem delay "${DELAY_MS}ms" loss "${LOSS_PCT}%"

echo "netem active on ${IFACE}: delay ${DELAY_MS}ms, loss ${LOSS_PCT}%"
echo "REMINDER: shared server — clear it right after the measurement window:"
echo "  sudo $0 ${IFACE} clear"
tc qdisc show dev "${IFACE}"
