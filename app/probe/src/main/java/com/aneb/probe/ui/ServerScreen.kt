package com.aneb.probe.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.net.ReachabilityProbe
import com.aneb.probe.ui.components.SectionLabel
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebSectionTitle
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebTheme

/** V1 节点页：只展示真实上线的 E-01 与当前自定义节点，不伪造设计稿中的未部署城市。 */
@Composable
internal fun ServerScreen(
    currentUrl: String,
    reach: ReachabilityProbe.DualReach?,
    refreshing: Boolean,
    error: String?,
    onSelectE01: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = AnebTheme.colors
    val selected = ProbeNodeCatalog.nodeForUrl(currentUrl) == ProbeNodeCatalog.e01

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack)
        AnebPageIntro(
            eyebrow = "TEST SERVER",
            title = "选择测试节点",
            subtitle = "优先使用低延迟节点，结果更接近真实 AI 使用体验。",
            modifier = Modifier.padding(top = 3.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 17.dp, start = 10.dp, end = 10.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(colors.brand.copy(alpha = 0.08f)).border(1.dp, colors.brand.copy(alpha = 0.28f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Text("ϟ", fontSize = 15.sp, color = colors.brand)
            }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text("自动选择最优", fontSize = 12.sp, color = colors.ink)
                Text("每次测试前刷新可达性", fontSize = 10.sp, color = colors.muted)
            }
            Text(if (selected) "已启用" else "选择 E-01", fontSize = 10.sp, color = colors.brand, modifier = Modifier.pressable(onClick = onSelectE01))
        }

        AnebSectionTitle(
            "附近节点",
            action = if (refreshing) "检测中…" else "刷新",
            onAction = onRefresh,
            modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp, bottom = 7.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(17.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xD6191F3A), androidx.compose.ui.graphics.Color(0xDB0B0F21))))
                .border(1.dp, colors.hairline, RoundedCornerShape(17.dp)),
        ) {
            NodeDesignRow(
                selected = selected,
                title = ProbeNodeCatalog.e01.displayName,
                subtitle = "${ProbeNodeCatalog.e01.provider} · 自动旁路",
                reach = bestReach(reach),
                onClick = onSelectE01,
            )
            if (!selected) {
                HairlineDivider()
                NodeDesignRow(
                    selected = true,
                    title = ProbeNodeCatalog.customLabel(currentUrl),
                    subtitle = "自定义地址",
                    reach = null,
                    onClick = {},
                )
            }
        }

        SectionLabel("连接可达性")
        GroupedCard {
            ReachRow("SNI 域名", reach?.sni)
            HairlineDivider()
            ReachRow("bare-IP", reach?.ip)
        }
        error?.let {
            Text(it, fontSize = 11.sp, color = colors.fair, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(14.dp))
        IosSoftButton(
            label = "编辑自定义地址",
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "V1 当前只有 1 个已部署并留有测量证据的国内节点；未上线节点不会显示为可选项。" +
                "AQS 只代表终端到所选节点的应用层路径。",
            fontSize = 10.5.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 16.dp, bottom = 84.dp),
        )
    }
}

private fun bestReach(reach: ReachabilityProbe.DualReach?): ReachabilityProbe.Reach? =
    listOfNotNull(reach?.sni, reach?.ip).filter { it.status == "ok" }.minByOrNull { it.elapsedMs ?: Long.MAX_VALUE }

@Composable
private fun NodeDesignRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    reach: ReachabilityProbe.Reach?,
    onClick: () -> Unit,
) {
    val colors = AnebTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(if (selected) colors.brand.copy(alpha = 0.035f) else androidx.compose.ui.graphics.Color.Transparent)
            .pressable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).border(1.dp, if (selected) colors.brand else colors.hairline, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Text("✓", fontSize = 10.sp, color = colors.brand)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(subtitle, fontSize = 10.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            reach?.elapsedMs?.let { "$it ms" } ?: "—",
            fontSize = 11.sp,
            fontWeight = FontWeight(560),
            color = if (reach?.status == "ok") colors.excellent else colors.muted,
        )
    }
}

@Composable
private fun ReachRow(label: String, reach: ReachabilityProbe.Reach?) {
    val colors = AnebTheme.colors
    val (text, color) = when (reach?.status) {
        "ok" -> "可达${reach.elapsedMs?.let { " · ${it}ms" } ?: ""}" to colors.excellent
        "rst" -> "连接被重置" to colors.poor
        "timeout" -> "超时" to colors.fair
        null -> "未检测" to colors.muted
        else -> "不可达" to colors.poor
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.5.sp, color = colors.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(text, fontSize = 11.5.sp, color = color, modifier = Modifier.padding(start = 7.dp))
        }
    }
}
