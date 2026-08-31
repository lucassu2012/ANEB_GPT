package com.aneb.probe.ui

import android.content.Context
import androidx.core.content.edit
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.engine.TestEngine
import com.aneb.probe.prototype.PrototypeNodeSettings

/** 可跨进程重启恢复的非敏感测量设置。API key 仍由独立的加密存储负责。 */
internal data class ProbeSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val testMode: AnebTestMode = AnebTestMode.TOKEN_EXPERIENCE,
    val mode: TestEngine.Mode = TestEngine.Mode.QUICK,
    val transport: TestEngine.TransportMode = TestEngine.TransportMode.AUTO,
    val driveTest: Boolean = false,
) {
    companion object {
        const val DEFAULT_SERVER_URL = "https://120-79-148-0.sslip.io:8443"
    }
}

/**
 * SharedPreferences 只保存字符串；这里集中做向后兼容解码，未知枚举值安全回退。
 * 保留自定义服务器原文（仅去首尾空白），让内网节点和未来协议无需发版即可使用。
 */
internal object ProbeSettingsCodec {
    fun decode(
        serverUrl: String?,
        mode: String?,
        transport: String?,
        driveTest: Boolean,
        testMode: String? = null,
    ): ProbeSettings = ProbeSettings(
        serverUrl = serverUrl?.trim().takeUnless { it.isNullOrEmpty() }
            ?: ProbeSettings.DEFAULT_SERVER_URL,
        testMode = when (testMode) {
            AnebTestMode.NETWORK_BASIC.name -> AnebTestMode.NETWORK_BASIC
            else -> AnebTestMode.TOKEN_EXPERIENCE
        },
        mode = when (mode) {
            TestEngine.Mode.FORENSIC.name -> TestEngine.Mode.FORENSIC
            else -> TestEngine.Mode.QUICK
        },
        transport = when (transport) {
            TestEngine.TransportMode.WIFI.name -> TestEngine.TransportMode.WIFI
            TestEngine.TransportMode.CELLULAR.name -> TestEngine.TransportMode.CELLULAR
            else -> TestEngine.TransportMode.AUTO
        },
        driveTest = driveTest,
    )
}

internal data class ProbeLaunchOverrides(
    val serverUrl: String? = null,
    val testMode: AnebTestMode? = null,
    val mode: TestEngine.Mode? = null,
    val transport: TestEngine.TransportMode? = null,
    val driveTest: Boolean? = null,
)

/**
 * 手动启动读取持久化设置；autorun 从固定默认值起步，避免 ADB 验收被上一次人工选择污染。
 * 显式 intent 参数始终优先；路测还必须在启动时拥有完整无线/定位权限。
 */
internal fun resolveLaunchSettings(
    saved: ProbeSettings,
    overrides: ProbeLaunchOverrides,
    autorun: Boolean,
    hasFullRadioEvidence: Boolean,
): ProbeSettings {
    val base = if (autorun) ProbeSettings() else saved
    return ProbeSettings(
        serverUrl = overrides.serverUrl ?: base.serverUrl,
        testMode = overrides.testMode ?: base.testMode,
        mode = overrides.mode ?: base.mode,
        transport = overrides.transport ?: base.transport,
        driveTest = (overrides.driveTest ?: base.driveTest) && hasFullRadioEvidence,
    )
}

internal class ProbeSettingsStore(context: Context) : PrototypeNodeSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ProbeSettings = ProbeSettingsCodec.decode(
        serverUrl = prefs.getString(KEY_SERVER_URL, null),
        mode = prefs.getString(KEY_MODE, null),
        transport = prefs.getString(KEY_TRANSPORT, null),
        driveTest = prefs.getBoolean(KEY_DRIVE_TEST, false),
        testMode = prefs.getString(KEY_TEST_MODE, null),
    )

    fun saveServerUrl(value: String) {
        prefs.edit { putString(KEY_SERVER_URL, value.trim()) }
    }

    fun saveMode(value: TestEngine.Mode) {
        prefs.edit { putString(KEY_MODE, value.name) }
    }

    fun saveTestMode(value: AnebTestMode) {
        prefs.edit { putString(KEY_TEST_MODE, value.name) }
    }

    fun saveTransport(value: TestEngine.TransportMode) {
        prefs.edit { putString(KEY_TRANSPORT, value.name) }
    }

    fun saveDriveTest(value: Boolean) {
        prefs.edit { putBoolean(KEY_DRIVE_TEST, value) }
    }

    override fun loadNodeUrl(): String = prefs.getString(KEY_PROTOTYPE_NODE_URL, null)
        ?.trim()
        .orEmpty()

    override fun saveNodeUrl(nodeBaseUrl: String) {
        prefs.edit { putString(KEY_PROTOTYPE_NODE_URL, nodeBaseUrl.trim()) }
    }

    private companion object {
        const val PREFS_NAME = "probe_settings_v1"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_MODE = "mode"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_TRANSPORT = "transport"
        const val KEY_DRIVE_TEST = "drive_test"
        const val KEY_PROTOTYPE_NODE_URL = "prototype_node_url"
    }
}
