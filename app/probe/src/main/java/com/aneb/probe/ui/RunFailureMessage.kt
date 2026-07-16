package com.aneb.probe.ui

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 把底层异常收敛成普通用户可行动的提示；诊断细节仍保留在 AnebProbe 日志。 */
internal object RunFailureMessage {
    fun forError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val message = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            chain.any { it is SocketTimeoutException } ||
                "timeout" in message || "timed out" in message ->
                "测试节点响应超时。请检查网络，或切换测试节点后重试。"

            chain.any { it is UnknownHostException } || "unable to resolve host" in message ->
                "无法解析测试节点地址。请检查网络或自定义服务器地址。"

            "no active network" in message || "network_unavailable" in message ||
                "network is unreachable" in message ->
                "当前没有可用网络。连接 WiFi 或蜂窝网络后再试。"

            chain.any { it is ConnectException } || "connection reset" in message ||
                "failed to connect" in message ->
                "无法连接测试节点。可先在节点页刷新可达性，再重试。"

            else -> "测试未完成。请检查网络和测试节点后重试。"
        }
    }
}
