package com.aneb.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.apiprobe.AiReachabilityProbe
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebShapes
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType

/**
 * ① AI 全家可达性看板（mode①，iOS 化）。消费 [AiReachabilityProbe] 的每家结果，展示"连接层
 * TLS 握手是否可达"——**免 key**、best-effort。此屏是 API 探针的姊妹入口（从 [ApiProbeScreen]
 * 的"① 全家可达性看板（免 key）"进入），与真实 API 探针（TTFT/ITL）明确分口径。
 *
 * **口径红线（与显示强绑定，勿混）**：claim_scope=`application_reachability_tls_no_key`，只看
 * "能否完成完整 TLS 握手（拿到任意 HTTP 响应即通）"，**不测 TTFT、不进 AQS**，不看 2xx/4xx 语义。
 * 因此每行右侧只呈现 状态分类 + TLS 握手耗时 + http_code（诊断辅助），绝不呈现任何"速度分/体验分"。
 *
 * iOS 材质：毛玻璃顶栏 [GlassHeader]、#1C1C1E 连续圆角卡（[AnebShapes.tile]）、tabular 数值
 * （[AnebType.StatValue]）、[AnebTheme.colors] 语义色状态圆点。深浅色跟随系统（色取主题字段）。
 *
 * 纯 UI 层：rows/running/回调全部由上层（MainActivity，E 对接）提升，本屏不持状态、不碰测量语义。
 *
 * 状态色映射（[statusStyle]）：OK→绿（可达）· RST→红（被阻断）· TIMEOUT→橙 · DNS_FAIL→中性灰 ·
 * ERROR→红 · UNPROBED→faint "—"。
 *
 * @param rows 每家预置一条结果（消费 B 的 [AiReachabilityProbe.Result]）；空或全 UNPROBED 时显引导。
 * @param running 探测进行中：主按钮禁用 + 转圈。
 * @param onRun 触发一次免 key 全家探测。
 * @param onBack 返回。
 * @param lastRunLabel 上次探测摘要（如"刚刚 · 9 家 · 7 通"）；null 不显。
 * @param claimScopeNote 页脚口径说明（连接层 / 不测 TTFT / 不进 AQS）。
 */
@Composable
fun ReachabilityBoardScreen(
    rows: List<AiReachabilityProbe.Result>,
    running: Boolean,
    onRun: () -> Unit,
    onBack: () -> Unit,
    lastRunLabel: String?,
    claimScopeNote: String,
) {
    val colors = AnebTheme.colors
    val unprobed = rows.isEmpty() || rows.all { it.status == AiReachabilityProbe.Status.UNPROBED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        GlassHeader("① AI 全家可达性", onBack)

        // ---- 主操作：免 key 全家探测（running 时禁用 + 转圈）----
        Spacer(Modifier.height(14.dp))
        RunButton(running = running, onRun = onRun, modifier = Modifier.fillMaxWidth())
        lastRunLabel?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.5.sp, color = colors.muted, modifier = Modifier.fillMaxWidth())
        }

        // ---- 看板主体 ----
        Spacer(Modifier.height(16.dp))
        if (unprobed) {
            GuidanceCard()
            Spacer(Modifier.height(12.dp))
        }
        rows.forEach { row ->
            ReachRow(row)
            Spacer(Modifier.height(8.dp))
        }

        // ---- 页脚：口径说明（连接层 / 不测 TTFT / 不进 AQS）----
        Spacer(Modifier.height(12.dp))
        Text(claimScopeNote, fontSize = 11.sp, color = colors.faint, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 免 key 主按钮：常态品牌实心 + 按压缩放；running 时降级为静态灰底 + 转圈 + "探测中…"（禁用交互）。
 */
@Composable
private fun RunButton(running: Boolean, onRun: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Box(
        modifier = modifier
            .clip(AnebShapes.button)
            .background(if (running) colors.surfaceMuted else colors.brand)
            .then(if (running) Modifier else Modifier.pressable(onClick = onRun))
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = colors.muted,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                if (running) "探测中…" else "开始探测（免 key）",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (running) colors.faint else Color.White,
            )
        }
    }
}

/**
 * 单家一行卡片：左 displayName + verified 角标 + host(mono/muted)；右 状态圆点/标签 + TLS "xx ms"
 * (null→"…"，不顶 0) + http_code。全行连续圆角瓦片 + hairline 描边（#1C1C1E 面）。
 */
@Composable
private fun ReachRow(row: AiReachabilityProbe.Result) {
    val colors = AnebTheme.colors
    val st = statusStyle(row.status)
    // TLS 握手 ms：null 显 "…"（未测/失败，绝不顶 0）。
    val tlsLabel = row.tlsHandshakeMs?.let { "%,d ms".format(it) } ?: "…"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AnebShapes.tile)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    // verified：✅ 已核实 / ⚠️ 以官方文档为准
                    Text(if (row.verified) "✅" else "⚠️", fontSize = 11.sp)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    row.host,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(AnebShapes.pill)
                            .background(st.color),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(st.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = st.color)
                }
                Spacer(Modifier.height(3.dp))
                Text(tlsLabel, style = AnebType.StatValue, fontSize = 12.sp, color = colors.ink)
                row.httpCode?.let {
                    Text("HTTP $it", style = AnebType.StatValue, fontSize = 10.5.sp, color = colors.faint)
                }
            }
        }
        // 失败时（非 OK）在行下方追加诊断备注（faint mono 小字），复用 err 行展示风格。
        if (row.status != AiReachabilityProbe.Status.OK) {
            row.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = colors.faint,
                )
            }
        }
    }
}

/** 未探测引导卡（rows 空或全 UNPROBED）：说明"仅连接层 TLS 握手、不需要 API key"。 */
@Composable
private fun GuidanceCard() {
    val colors = AnebTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AnebShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AnebShapes.card)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("尚未探测", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "点击上方开始（仅连接层 TLS 握手，不需要 API key）",
            fontSize = 12.sp,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/** 状态 → 语义色 + 短标签（单一映射源；含 else 兜底 faint，防 B 端枚举扩展致 when 不穷尽）。 */
private data class StatusStyle(val color: Color, val label: String)

@Composable
private fun statusStyle(status: AiReachabilityProbe.Status): StatusStyle {
    val colors = AnebTheme.colors
    return when (status) {
        AiReachabilityProbe.Status.OK -> StatusStyle(colors.excellent, "OK")
        AiReachabilityProbe.Status.RST -> StatusStyle(colors.poor, "RST")
        AiReachabilityProbe.Status.TIMEOUT -> StatusStyle(colors.fair, "TIMEOUT")
        AiReachabilityProbe.Status.DNS_FAIL -> StatusStyle(colors.muted, "DNS_FAIL")
        AiReachabilityProbe.Status.ERROR -> StatusStyle(colors.poor, "ERROR")
        AiReachabilityProbe.Status.UNPROBED -> StatusStyle(colors.faint, "—")
        else -> StatusStyle(colors.faint, "—")
    }
}
