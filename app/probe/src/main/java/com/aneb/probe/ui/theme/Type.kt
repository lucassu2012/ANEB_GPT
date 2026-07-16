package com.aneb.probe.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * ANEB 排版系统——照交接稿 tokens.css §5.5 的 iOS 字阶（尺寸相关字距：大字负、正文归零、
 * 小字微开）。用系统字（Apple 端解析为 SF + 苹方；华为/其它端为鸿蒙/思源/雅黑），不打包
 * 字体（即装即用、无授权/体积负担）。数字处处 tabular（tnum，等宽防跳动）。
 */
object AnebType {

    /** 系统默认字族——运行期解析为 SF Pro / PingFang / HarmonyOS Sans / Roboto */
    private val SystemSans = FontFamily.Default

    /** 表格数字特性：等宽数字位，仪表分数/KPI 值不因字宽变化而横向抖动 */
    private const val TABULAR = "tnum"

    /** 大字紧收行高（分数用；对齐 line-height .86），文本框贴合大数字 */
    private val TightLineHeight = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    )

    /**
     * 巨大分数样式（仪表中心 89 / 62 …）。字号由调用点按仪表尺寸覆盖（sp 参数），此处锁定
     * 字重 ~680、字距 −.05em、行高 .86、等宽数字。（保留名 [DisplayScore] 供既有组件取用。）
     */
    val DisplayScore: TextStyle = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight(680),
        letterSpacing = (-0.05).em,
        lineHeight = 0.86.em,
        lineHeightStyle = TightLineHeight,
        fontFeatureSettings = TABULAR,
        textAlign = TextAlign.Center,
    )

    /** 标题（顶栏标题/字标 22px/640/−.02em） */
    val Title: TextStyle = TextStyle(
        fontFamily = SystemSans,
        fontSize = 22.sp,
        fontWeight = FontWeight(640),
        letterSpacing = (-0.02).em,
    )

    /** 正文（结论句/正文 16px/450/−.01em） */
    val Body: TextStyle = TextStyle(
        fontFamily = SystemSans,
        fontSize = 16.sp,
        fontWeight = FontWeight(450),
        letterSpacing = (-0.01).em,
    )

    /** 说明小字（12.5px/0） */
    val Caption: TextStyle = TextStyle(
        fontFamily = SystemSans,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.em,
    )

    /** 分区 overline（11px/640/+.09em/大写；大写由调用点传大写文本或 textTransform 处理） */
    val Overline: TextStyle = TextStyle(
        fontFamily = SystemSans,
        fontSize = 11.sp,
        fontWeight = FontWeight(640),
        letterSpacing = 0.09.em,
    )

    /** 瓦片/KPI 大数值：半粗 + 等宽数字（响应速度 35ms、上传 12.5 …；字号调用点覆盖） */
    val StatValue: TextStyle = TextStyle(
        fontFamily = SystemSans,
        fontWeight = FontWeight(660),
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TABULAR,
    )

    /** Material3 Typography——覆盖字族/字重/iOS 字距锚点，尺寸走 M3 默认（组件按需覆盖） */
    val Typography: Typography = Typography().run {
        val lh = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        )
        copy(
            displayLarge = displayLarge.copy(
                fontFamily = SystemSans, fontWeight = FontWeight(680),
                letterSpacing = (-0.05).em, lineHeightStyle = lh,
            ),
            headlineMedium = headlineMedium.copy(
                fontFamily = SystemSans, fontWeight = FontWeight(640), letterSpacing = (-0.02).em,
            ),
            titleLarge = titleLarge.copy(
                fontFamily = SystemSans, fontWeight = FontWeight(640), letterSpacing = (-0.02).em,
            ),
            titleMedium = titleMedium.copy(
                fontFamily = SystemSans, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em,
            ),
            bodyLarge = bodyLarge.copy(
                fontFamily = SystemSans, fontWeight = FontWeight(450), letterSpacing = (-0.01).em,
            ),
            bodyMedium = bodyMedium.copy(fontFamily = SystemSans, letterSpacing = (-0.006).em),
            // bodySmall 必须与 bodyLarge 同单位（.em）：M3 OutlinedTextField 浮动标签在
            // bodyLarge(静息)↔bodySmall(上浮) 间做 TextStyle.lerp，字距一 Em 一 Sp 会抛
            // IllegalArgumentException: Cannot perform operation for Em and Sp（设置页崩溃根因）。
            bodySmall = bodySmall.copy(fontFamily = SystemSans, letterSpacing = 0.em),
            labelLarge = labelLarge.copy(fontFamily = SystemSans, fontWeight = FontWeight.SemiBold),
            labelSmall = labelSmall.copy(
                fontFamily = SystemSans, fontWeight = FontWeight(640), letterSpacing = 0.09.em,
            ),
        )
    }
}
