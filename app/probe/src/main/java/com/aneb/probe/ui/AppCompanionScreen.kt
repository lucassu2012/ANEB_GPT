package com.aneb.probe.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.data.BasicSpeedResultEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.data.VoiceResultEntity
import com.aneb.probe.engine.AnebTestMode
import com.aneb.probe.ui.components.AnebGlyph
import com.aneb.probe.ui.components.AnebGradientCard
import com.aneb.probe.ui.components.AnebIcon
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebColors
import com.aneb.probe.ui.theme.AnebTheme

/** 支持一键拉起对照实测的热门目标 App（零无障碍权限，不静默操控第三方 App）。 */
data class CompanionAppTarget(
    val id: String,
    val displayName: String,
    val packageName: String,
    val guideText: String,
)

object RealAppCompanionRegistry {
    val targets: List<CompanionAppTarget> = listOf(
        CompanionAppTarget(
            id = "douyin",
            displayName = "抖音",
            packageName = "com.ss.android.ugc.aweme",
            guideText = "刷 3–5 条视频观察首帧起播与播中是否转圈，对照 ANEB 1.5/2.0/4.0 Mbps 拐点结论。",
        ),
        CompanionAppTarget(
            id = "doubao",
            displayName = "豆包",
            packageName = "com.larus.nova",
            guideText = "发送长问答或发起一次实时语音通话，对照 ANEB 首字 TTFT 与 Profile 4 口到耳实测。",
        ),
        CompanionAppTarget(
            id = "kimi",
            displayName = "Kimi",
            packageName = "com.moonshot.kimichat",
            guideText = "上传文档或提问观察长流式吐字是否停顿，对照 ANEB 编码/多模态体验结论。",
        ),
    )

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
            .getOrDefault(false)

    fun launch(context: Context, packageName: String): Boolean = runCatching {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        true
    }.getOrDefault(false)
}

/**
 * 抖音 / 豆包体验与真实 App 伴侣屏：
 * 1. 基于 ANEB 实测数据与 P40 Pro 校准拐点，直接输出抖音短视频、豆包对话、豆包实时语音体验结论；
 * 2. 一键发起对应的 ANEB 网络/语音/可达性测试；
 * 3. 一键拉起本机已安装的抖音/豆包/Kimi 做人工对照，并可进入人工实测留证记录。
 */
@Composable
fun AppCompanionScreen(
    lastTokenRun: TestRun?,
    lastTokenScenarios: List<ScenarioResultEntity>,
    lastBasicRun: BasicSpeedResultEntity?,
    lastVoiceRun: VoiceResultEntity?,
    running: Boolean,
    onRunTestMode: (AnebTestMode) -> Unit,
    onOpenVoice: () -> Unit,
    onOpenReachBoard: () -> Unit,
    onOpenResearchRecords: () -> Unit,
    onBack: () -> Unit,
    onOpenVideoTheater: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = AnebTheme.colors
    var launchNotice by remember { mutableStateOf<String?>(null) }

    val douyinVerdict = remember(lastBasicRun) {
        BusinessExperienceEvaluator.evaluateDouyin(lastBasicRun)
    }
    val doubaoChatVerdict = remember(lastTokenRun, lastTokenScenarios, lastBasicRun) {
        BusinessExperienceEvaluator.evaluateDoubaoChat(lastTokenRun, lastTokenScenarios, lastBasicRun)
    }
    val voiceVerdict = remember(lastVoiceRun) {
        BusinessExperienceEvaluator.evaluateVoice(lastVoiceRun)
    }
    val installedMap = remember(context) {
        RealAppCompanionRegistry.targets.associate { it.id to RealAppCompanionRegistry.isInstalled(context, it.packageName) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack)
        AnebPageIntro(
            eyebrow = "APP EXPERIENCE LAB",
            title = "抖音 / 豆包体验评估",
            subtitle = "校准模型网络实测 + 一键拉起真实 App 人工对照（零无障碍权限）。",
            modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
        )

        launchNotice?.let { note ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.brand.copy(alpha = 0.12f))
                    .border(1.dp, colors.brand.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(note, fontSize = 11.sp, color = colors.ink)
            }
        }

        if (onOpenVideoTheater != null) {
            AnebGradientCard(Modifier.fillMaxWidth(), radius = 18.dp) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            AnebIcon(glyph = AnebGlyph.Video, tint = colors.brand, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("短视频流媒体实时播放压测", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                                Text(
                                    "真 H.264 视频播放 · 实时 ABR 清晰度/首帧/卡顿/速率/抖动",
                                    fontSize = 9.5.sp,
                                    color = colors.muted,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(colors.brand.copy(alpha = 0.16f))
                            .border(1.dp, colors.brand.copy(alpha = 0.50f), RoundedCornerShape(11.dp))
                            .pressable(onClick = onOpenVideoTheater)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "进入短视频流媒体实时剧场 ›",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.brand,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        AnebSectionTitle("校准网络体验评估", Modifier.padding(start = 4.dp, bottom = 8.dp))

        ExperienceVerdictCard(
            verdict = douyinVerdict,
            actionLabel = if (running) "已有测试运行中" else "立即测短视频网络",
            actionEnabled = !running,
            onAction = { onRunTestMode(AnebTestMode.NETWORK_BASIC) },
        )
        Spacer(Modifier.height(9.dp))

        ExperienceVerdictCard(
            verdict = doubaoChatVerdict,
            actionLabel = if (running) "已有测试运行中" else "立即测 AI 对话流",
            actionEnabled = !running,
            onAction = { onRunTestMode(AnebTestMode.TOKEN_EXPERIENCE) },
        )
        Spacer(Modifier.height(9.dp))

        ExperienceVerdictCard(
            verdict = voiceVerdict,
            actionLabel = "进入实时语音测速（Profile 4）",
            actionEnabled = true,
            onAction = onOpenVoice,
        )

        Spacer(Modifier.height(10.dp))
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onClick = onOpenReachBoard)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("主流 AI 服务直连可达性（REACH）", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text("一键实测豆包、DeepSeek、Kimi、通义千问等连接握手时延", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
                }
                Text("测通 ›", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.brand)
            }
        }

        AnebSectionTitle("一键拉起真实 App 人工对照", Modifier.padding(top = 18.dp, start = 4.dp, bottom = 8.dp))
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 18.dp) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RealAppCompanionRegistry.targets.forEachIndexed { idx, target ->
                    val installed = installedMap[target.id] == true
                    if (idx > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(target.displayName, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                                Text(
                                    if (installed) "已安装" else "未检测到",
                                    fontSize = 9.sp,
                                    color = if (installed) colors.excellent else colors.faint,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (installed) colors.excellent.copy(alpha = 0.14f) else Color(0x14FFFFFF))
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                target.guideText,
                                fontSize = 10.sp,
                                lineHeight = 14.5.sp,
                                color = colors.muted,
                                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                            )
                        }
                        Text(
                            if (installed) "打开实测" else "未安装",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (installed) colors.brand else colors.faint,
                            modifier = Modifier
                                .clip(CircleShape)
                                .border(
                                    1.dp,
                                    if (installed) colors.brand.copy(alpha = 0.45f) else colors.hairline,
                                    CircleShape,
                                )
                                .pressable(enabled = installed, onClick = {
                                    val ok = RealAppCompanionRegistry.launch(context, target.packageName)
                                    launchNotice = if (ok) {
                                        "已拉起 ${target.displayName}，实测完成后可切回 ANEB 对照或留证。"
                                    } else {
                                        "未能拉起 ${target.displayName}（系统限制或应用未启用）。"
                                    }
                                })
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        AnebGradientCard(Modifier.fillMaxWidth(), radius = 16.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onClick = onOpenResearchRecords)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("人工实测留证与对比记录", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text("录入真实抖音/豆包观感与录屏附件，导出离线对照报告", fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
                }
                Text("进入 ›", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.brand)
            }
        }

        Text(
            "安全与口径说明：${BusinessExperienceEvaluator.CALIBRATION_CLAIM_SCOPE} 独立手机版不申请无障碍权限，不静默操控第三方商业 App。",
            fontSize = 9.5.sp,
            lineHeight = 14.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 14.dp, bottom = 28.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
internal fun ExperienceVerdictCard(
    verdict: BusinessExperienceEvaluator.Verdict,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val colors = AnebTheme.colors
    val accent = verdictToneColor(verdict.tone, colors)
    AnebGradientCard(Modifier.fillMaxWidth(), radius = 18.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(8.dp))
                    Text(verdict.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                }
                Text(
                    verdict.badge,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.32f), CircleShape)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            Text(
                verdict.headline,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                verdict.detail,
                fontSize = 10.5.sp,
                lineHeight = 15.5.sp,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                verdict.calibrationBasis,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                color = colors.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    actionLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (actionEnabled) colors.brand else colors.faint,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (actionEnabled) colors.brand.copy(alpha = 0.10f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (actionEnabled) colors.brand.copy(alpha = 0.42f) else colors.hairline,
                            CircleShape,
                        )
                        .pressable(enabled = actionEnabled, onClick = onAction)
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                )
            }
        }
    }
}

internal fun verdictToneColor(tone: BusinessExperienceEvaluator.Tone, colors: AnebColors): Color = when (tone) {
    BusinessExperienceEvaluator.Tone.EXCELLENT -> colors.excellent
    BusinessExperienceEvaluator.Tone.GOOD -> colors.brand
    BusinessExperienceEvaluator.Tone.CAUTION -> colors.fair
    BusinessExperienceEvaluator.Tone.POOR -> colors.poor
    BusinessExperienceEvaluator.Tone.NEUTRAL -> colors.muted
}
