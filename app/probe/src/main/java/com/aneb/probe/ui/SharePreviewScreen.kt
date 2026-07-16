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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aneb.probe.ui.components.AnebPageIntro
import com.aneb.probe.ui.components.AnebTopBar
import com.aneb.probe.ui.components.pressable
import com.aneb.probe.ui.theme.AnebPalette
import com.aneb.probe.ui.theme.AnebTheme
import com.aneb.probe.ui.theme.AnebType

/** 分享预览页：原生对应 `share.html`，确认后才保存或打开系统分享面板。 */
@Composable
fun SharePreviewScreen(
    model: ShareCard.Model,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = AnebTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(AnebPalette.Dark.DeepBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        AnebTopBar(showBack = true, onBack = onBack)
        AnebPageIntro(
            eyebrow = "SHARE CARD",
            title = "分享成绩",
            subtitle = "生成一张不包含精确位置和设备标识的成绩卡。",
            modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
        )

        SharePreviewCard(model)
        Text(
            "成绩卡不会显示精确定位、密钥、服务器地址或设备标识。",
            fontSize = 9.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            color = colors.faint,
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ShareAction("保存图片", primary = false, onClick = onSave, modifier = Modifier.weight(1f))
            ShareAction("分享", primary = true, onClick = onShare, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SharePreviewCard(model: ShareCard.Model) {
    val colors = AnebTheme.colors
    val accent = Color(model.gradeColorArgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF121A32), Color(0xFF080B18)),
                ),
            )
            .border(1.dp, colors.excellent.copy(alpha = 0.22f), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ANEB PROBE", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = colors.muted)
            Spacer(Modifier.weight(1f))
            Text("AI NETWORK EXPERIENCE", fontSize = 9.sp, letterSpacing = 1.0.sp, color = colors.faint)
        }

        Text(
            model.score?.toString() ?: "—",
            style = AnebType.DisplayScore,
            fontSize = 74.sp,
            fontWeight = FontWeight(520),
            color = accent,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 34.dp),
        )
        Text(
            "${model.gradeLabel} · AI 体验分",
            fontSize = 12.sp,
            fontWeight = FontWeight(580),
            color = accent,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
        )
        Text(
            model.networkLine,
            fontSize = 10.sp,
            color = colors.muted,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
        )
        Text(
            model.verdict,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            color = colors.ink.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 16.dp),
        )

        Row(
            Modifier.fillMaxWidth().border(1.dp, colors.hairline, RoundedCornerShape(2.dp)).padding(vertical = 11.dp),
        ) {
            model.tiles.take(3).forEachIndexed { index, tile ->
                if (index > 0) Box(Modifier.width(1.dp).height(38.dp).background(colors.hairline))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tile.value, style = AnebType.StatValue, fontSize = 15.sp, color = Color(tile.colorArgb))
                    Text(tile.label, fontSize = 9.sp, color = colors.muted, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.Bottom) {
            Column {
                Text("由 ANEB Probe 在本机生成", fontSize = 9.sp, color = colors.faint)
                Text("应用层路径体验 · 非运营商全网评级", fontSize = 9.sp, color = colors.faint, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.width(38.dp).height(38.dp).border(1.dp, colors.hairline, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("AN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.muted) }
        }
    }
}

@Composable
private fun ShareAction(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AnebTheme.colors
    Box(
        modifier
            .height(45.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (primary) Color(0xFF071118) else Color.Transparent)
            .border(1.dp, if (primary) colors.excellent.copy(alpha = 0.48f) else colors.hairline, RoundedCornerShape(999.dp))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight(580), color = if (primary) colors.excellent else colors.ink.copy(alpha = 0.78f))
    }
}

