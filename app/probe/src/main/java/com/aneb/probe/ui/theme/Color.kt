package com.aneb.probe.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * ANEB 色板——照交接稿 `design_handoff_aneb_probe/tokens.css` 的 iOS 令牌 1:1 落地。
 *
 * 纯颜色常量表（不含语义映射；语义/分级映射见 [AnebColors] 与 Grade.kt）。视觉语言换成
 * **Apple / iOS**：系统蓝品牌（仅交互态）、iOS 语义四级色、OLED 纯黑底、连续圆角卡面。
 *
 * 命名沿用交接稿：brand=交互态、exc/good/fair/poor=四级语义、
 * a*(App 屏内层)=phone 内容区口径（深浅两套）。
 *
 * 设计取舍：四级语义色编码"好→坏"，只用于仪表/分数/分级角标；品牌系统蓝极度克制，
 * 仅交互态（GO 按钮、当前分段、主按钮、链接），几乎不与语义色同框。
 */
object AnebPalette {

    // ---- 品牌（iOS system blue；仅交互态） ----
    object Brand {
        val Base = Color(0xFF43E1E6) // --cyan：主测试/交互
        val Hover = Color(0xFF67EDCC) // --mint：主交互高亮
        val Press = Color(0xFF3EB4F1) // --blue：按压态
        val LightBase = Color(0xFF007AFF) // 浅色 --brand
        val LightHover = Color(0xFF0A84FF)
    }

    // ---- 四级语义色（iOS system colors；深色/浅色两调） ----
    object Semantic {
        // 深色
        val Excellent = Color(0xFF59E493) // 优 · green
        val Good = Color(0xFF43E1E6) // 良 · cyan
        val Fair = Color(0xFFEFCA72) // 可 · yellow
        val Poor = Color(0xFFEB718D) // 差 · red
        // 浅色（更接近 iOS 默认）
        val ExcellentLight = Color(0xFF34C759)
        val GoodLight = Color(0xFF32ADE6)
        val FairLight = Color(0xFFFF9500)
        val PoorLight = Color(0xFFFF3B30)
    }

    /** 无效/缺失/低置信中性灰（R-10 失败样本：绝不发语义色）——iOS systemGray */
    val Neutral = Color(0xFF7B8BA4)
    val NeutralLight = Color(0xFFAEAEB2)

    // ---- App 内中性色（深色 / OLED 黑；phone 内容区 a* 口径） ----
    object Dark {
        val Background = Color(0xFF070A18) // suite.css --bg
        val DeepBackground = Color(0xFF010207) // home.css --black
        val Card = Color(0xE6131830) // --surface rgba(19,24,48,.9)
        val Card2 = Color(0xD1191F3A) // --surface-2 rgba(25,31,58,.82)
        val Ink = Color(0xFFF3F6FA) // --text
        val Muted = Color(0xADD7E0ED) // --muted rgba(215,224,237,.68)
        val Faint = Color(0x75C6D3E5) // --faint rgba(198,211,229,.46)
        val Hairline = Color(0x24CADBF1) // --line rgba(202,219,241,.14)
        val Material = Color(0xE60D1227) // 深海军蓝半透明材质
    }

    // ---- App 内中性色（浅色；跟随系统深浅色；phone.lightapp 口径） ----
    object Light {
        val Background = Color(0xFFF2F2F7) // --a
        val Card = Color(0xFFFFFFFF) // --acard
        val Card2 = Color(0xFFE9E9EF) // --acard2 / 段控轨
        val Ink = Color(0xFF1D1D1F) // --aink
        val Muted = Color(0x993C3C43) // --amut rgba(60,60,67,.6)
        val Faint = Color(0x573C3C43) // --afaint rgba(60,60,67,.34)
        val Hairline = Color(0x1A3C3C43) // --ahair rgba(60,60,67,.1)
        val Material = Color(0xADFAFAFC) // --amat rgba(250,250,252,.68)
    }
}

// ---------------------------------------------------------------------------
// 分级 / 有效性 → 主题跟随语义色（[AnebColors] 单一事实源的扩展入口）
//
// [AnebColors]（Theme.kt）已定义 gradeColor(Grade?) / gradeColor(String?) / gradeSoft(Grade?)。
// 下面补齐 ResultScreen 专业视图曾**私有写死暗色 hex** 的档色入口——invalid 中性 / 低置信 /
// validity / 徽标反色——全部委托 AnebColors 的深浅两套字段（darkAnebColors / lightAnebColors），
// 浅色主题自动跟随，消除"暗色值在浅色下不切换"的偏差。Grade↔color 仍以 Grade.fromKey +
// AnebColors 字段为唯一事实源（门限委托 ResultFormat / KpiGrading，本层不重定义）。
// ---------------------------------------------------------------------------

/** 分级串（KpiGrading 常量）→ 语义色；未知/缺失 → [invalidNeutral]（R-10：失败样本不折叠成某一档）。 */
fun AnebColors.gradeColorByKey(key: String?): Color = gradeColor(Grade.fromKey(key))

/** 无效/缺失样本的中性灰（= 语义 [AnebColors.neutral]；专业视图 INVALID 文字/角标/说明）。 */
val AnebColors.invalidNeutral: Color get() = neutral

/** 低置信提示色（valid_low_confidence / 低置信标签）——复用"可"档橙 [AnebColors.fair]，跟随主题。 */
val AnebColors.lowConf: Color get() = fair

/** 样本有效性字符串 → 语义色：valid→优 / valid_low_confidence→可(低置信) / 其它(invalid/缺失)→中性。 */
fun AnebColors.validityColor(validity: String): Color = when (validity) {
    "valid" -> excellent
    "valid_low_confidence" -> lowConf
    else -> invalidNeutral
}

/**
 * 分级徽标（底色为分级色的方形角标）之上的文字色：按底色相对亮度择近黑/近白，
 * 保证与徽标底色的 WCAG 对比（深浅主题 × 四档语义色底色各异，单一写死墨色无法同时达标）。
 */
fun AnebColors.onGrade(grade: Grade?): Color {
    val bg = gradeColor(grade)
    return if (contrastRatio(bg, GradeInkDark) >= contrastRatio(bg, GradeInkLight)) GradeInkDark else GradeInkLight
}

/** 徽标反色基线：近黑（iOS 深墨，沿用原写死值 0xFF05121A）/ 近白（主文本浅色）。 */
private val GradeInkDark = Color(0xFF05121A)
private val GradeInkLight = Color(0xFFF5F5F7)

/** WCAG 相对亮度对比度（两端色任意顺序）。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}
