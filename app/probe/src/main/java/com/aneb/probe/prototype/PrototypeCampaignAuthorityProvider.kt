package com.aneb.probe.prototype

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.aneb.probe.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest

internal data class PrototypeAndroidRuntimeCapture(
    val sourceCommit: String,
    val androidVersionName: String,
    val androidVersionCode: Int,
    val apkSha256: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceOsRelease: String,
    val deviceSdkInt: Int,
)

internal data class PrototypeCampaignAuthorityBundle(
    val campaignAuthorityJson: String,
    val runAuthorityJsonByRunId: Map<String, String>,
)

internal class PrototypeCampaignAuthorityProvider(
    private val runtime: PrototypeAndroidRuntimeCapture,
) {
    fun capture(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    ): PrototypeCampaignAuthorityBundle {
        val campaignAuthority = PrototypeCaptureAuthority.buildCampaign(
            PrototypeCaptureAuthority.CampaignCapture(
                campaignId = config.campaignId,
                campaignStartedAtUtc = requireNotNull(result.campaignStartedAtUtc),
                campaignEndedAtUtc = requireNotNull(result.campaignEndedAtUtc),
                sourceCommit = runtime.sourceCommit,
                androidVersionName = runtime.androidVersionName,
                androidVersionCode = runtime.androidVersionCode,
                apkSha256 = runtime.apkSha256,
                deviceManufacturer = runtime.deviceManufacturer,
                deviceModel = runtime.deviceModel,
                deviceOsRelease = runtime.deviceOsRelease,
                deviceSdkInt = runtime.deviceSdkInt,
                transportKind = transportKind(config.nodeTicket.nodeBaseUrl),
            ),
        )
        val runAuthority = result.runs.associate { run ->
            run.runId to PrototypeCaptureAuthority.buildRun(
                PrototypeCaptureAuthority.RunCapture(
                    campaignId = config.campaignId,
                    runId = run.runId,
                    runIndex = run.runIndex,
                    state = run.status.toAuthorityState(),
                    clockDomainId = requireNotNull(run.clockDomainId),
                    t0Nanos = run.t0MonotonicNanos,
                    attemptStartedAtUtc = run.attemptStartedAtUtc,
                    attemptEndedAtUtc = run.attemptEndedAtUtc,
                ),
            )
        }
        require(runAuthority.size == result.runs.size) {
            "prototype run authority requires unique run ids"
        }
        return PrototypeCampaignAuthorityBundle(campaignAuthority, runAuthority)
    }

    private fun transportKind(nodeBaseUrl: String): String {
        val endpoint = PrototypeNodeEndpoint.parse(nodeBaseUrl)
        val host = URI(endpoint.baseUrl).host
        val octets = host.split('.').map { part ->
            part.toIntOrNull()?.takeIf { value -> value in 0..255 }
                ?: throw IllegalArgumentException("Prototype authority requires a literal private IPv4 node")
        }
        require(octets.size == 4) { "Prototype authority requires a literal private IPv4 node" }
        return when {
            octets[0] == 127 -> "adb_reverse"
            octets[0] == 10 -> "lan"
            octets[0] == 172 && octets[1] in 16..31 -> "lan"
            octets[0] == 192 && octets[1] == 168 -> "lan"
            else -> throw IllegalArgumentException("Prototype authority requires a private LAN or loopback node")
        }
    }

    private fun PrototypeQuickCampaignRunner.RunStatus.toAuthorityState() = when (this) {
        PrototypeQuickCampaignRunner.RunStatus.COMPLETE ->
            PrototypeCaptureAuthority.RunState.COMPLETE
        PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED ->
            PrototypeCaptureAuthority.RunState.INTERRUPTED
        PrototypeQuickCampaignRunner.RunStatus.INVALID_SEQUENCE ->
            PrototypeCaptureAuthority.RunState.INVALID
        PrototypeQuickCampaignRunner.RunStatus.CANCELLED ->
            PrototypeCaptureAuthority.RunState.CANCELLED
        PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED ->
            PrototypeCaptureAuthority.RunState.NOT_STARTED
    }
}

internal fun interface PrototypeCampaignAuthorityWriter {
    suspend fun save(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
        campaignAuthorityJson: String,
        runAuthorityJsonByRunId: Map<String, String>,
    )
}

internal class PrototypeCampaignAuthorityResultStore(
    private val provider: PrototypeCampaignAuthorityProvider,
    private val writer: PrototypeCampaignAuthorityWriter,
) : PrototypeCampaignResultStore {
    override suspend fun save(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
    ) {
        val authority = provider.capture(config, result)
        writer.save(
            config,
            result,
            authority.campaignAuthorityJson,
            authority.runAuthorityJsonByRunId,
        )
    }
}

internal object PrototypeAndroidRuntimeCaptureReader {
    fun read(context: Context): PrototypeAndroidRuntimeCapture {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = Math.toIntExact(PackageInfoCompat.getLongVersionCode(packageInfo))
        val apk = File(context.applicationInfo.sourceDir)
        require(apk.isFile) { "installed Prototype APK is not readable" }
        return PrototypeAndroidRuntimeCapture(
            sourceCommit = BuildConfig.PROTOTYPE_SOURCE_COMMIT,
            androidVersionName = requireNotNull(packageInfo.versionName),
            androidVersionCode = versionCode,
            apkSha256 = sha256(apk),
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            deviceOsRelease = Build.VERSION.RELEASE,
            deviceSdkInt = Build.VERSION.SDK_INT,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
