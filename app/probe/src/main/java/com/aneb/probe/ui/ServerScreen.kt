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
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        GlassHeader("测试节点", onBack)

        SectionLabel("已验证国内节点")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, if (selected) colors.brand else colors.hairline, RoundedCornerShape(16.dp))
                .pressable(onClick = onSelectE01)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(colors.brand.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("01", fontSize = 10.sp, fontWeight = FontWeight.Black, color = colors.brand2)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(
                        ProbeNodeCatalog.e01.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.ink,
                    )
                    Text(ProbeNodeCatalog.e01.provider, fontSize = 11.sp, color = colors.muted)
                }
                Text(if (selected) "✓ 已选择" else "选择", fontSize = 11.5.sp, color = colors.brand2)
            }
            Spacer(Modifier.height(11.dp))
            Text(
                "默认经 sslip.io 公共证书连接；检测到运营商 SNI-RST 时自动切换同节点 bare-IP。",
                fontSize = 11.sp,
                color = colors.muted,
            )
        }

        SectionLabel("连接可达性")
        GroupedCard {
            ReachRow("SNI 域名", reach?.sni)
            HairlineDivider()
            ReachRow("bare-IP", reach?.ip)
        }
        Spacer(Modifier.height(10.dp))
        IosFilledButton(
            label = if (refreshing) "检测中…" else "刷新可达性",
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(it, fontSize = 11.sp, color = colors.fair, modifier = Modifier.padding(top = 8.dp))
        }

        if (!selected) {
            SectionLabel("当前自定义节点")
            GroupedCard {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(ProbeNodeCatalog.customLabel(currentUrl), fontSize = 13.sp, color = colors.ink)
                    Text(currentUrl, fontSize = 10.5.sp, color = colors.muted)
                }
            }
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
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
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
