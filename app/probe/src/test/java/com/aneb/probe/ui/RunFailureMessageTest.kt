package com.aneb.probe.ui

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertTrue
import org.junit.Test

class RunFailureMessageTest {
    @Test fun `timeout is actionable`() {
        assertTrue("超时" in RunFailureMessage.forError(SocketTimeoutException("timeout")))
    }

    @Test fun `dns failure is actionable`() {
        assertTrue("解析" in RunFailureMessage.forError(UnknownHostException("node")))
    }

    @Test fun `nested connection failure is actionable`() {
        assertTrue("无法连接" in RunFailureMessage.forError(IllegalStateException(ConnectException("reset"))))
    }

    @Test fun `unknown failure does not expose exception details`() {
        val text = RunFailureMessage.forError(IllegalStateException("secret implementation detail"))
        assertTrue("测试未完成" in text)
        assertTrue("secret" !in text)
    }
}
