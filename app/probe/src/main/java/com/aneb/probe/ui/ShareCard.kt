package com.aneb.probe.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import java.io.OutputStream

/**
 * 分享成图（设计稿 §分享成图）：把普通结果离屏渲染成一张分享位图（AQS/grade/verdict/
 * 三瓦片/网络 + 品牌水印），经 MediaStore 存图并 ACTION_SEND。日志 KEY=SHARE。
 *
 * 用 android.graphics.Canvas 离屏绘制（不依赖 Compose 截图，稳定可控、无窗口耦合）。
 * 全部输入来自结果页既有展示态（[Model]），本层零重算。
 */
object ShareCard {

    private const val W = 1080
    private const val H = 1350

    /** 一张分享卡所需的展示态（由 ResultScreen 从落库实体投影，绝不重算）。 */
    data class Model(
        val score: Int?,
        val gradeLabel: String,
        val gradeColorArgb: Int,
        val verdict: String,
        val tiles: List<Tile>,
        val networkLine: String,
    ) {
        data class Tile(val value: String, val label: String, val colorArgb: Int)
    }

    /**
     * 重活：离屏渲染 + 存 Pictures/ANEB，返回图片 Uri（失败 null）。
     * **必须在 IO 线程调用**（Canvas 渲染与 MediaStore 写盘）；不含 startActivity。
     */
    fun renderAndSave(context: Context, model: Model): Uri? {
        val uri = saveToPictures(context, render(model))
        Log.i("AnebProbe", "SHARE_SAVE status=${if (uri != null) "ok" else "fail"} uri=${uri ?: "null"}")
        return uri
    }

    /** 拉起系统分享（startActivity，**须在主线程**）。返回 UI 状态串。 */
    fun launchShare(context: Context, uri: Uri?): String {
        val status: String
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享成绩").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            status = "SHARE status=ok uri=$uri"
        } else {
            status = "SHARE status=fail uri=null"
        }
        Log.i("AnebProbe", status)
        return status
    }

    /** 纯离屏渲染（可在无 Activity 上下文时单独取 Bitmap，便于预览/调试）。 */
    fun render(model: Model): Bitmap {
        val bmp = createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = "#080B18".toColorInt()
        c.drawColor(bg)

        val ink = "#F3F6FA".toColorInt()
        val muted = "#A3ADBF".toColorInt()
        val faint = "#738094".toColorInt()
        val hair = "#26334B".toColorInt()
        val grade = model.gradeColorArgb

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val black = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

        // ANEB_UI 分享卡：深海军蓝斜向材质 + 右上分档柔光。
        p.shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), "#121A32".toColorInt(), bg, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), p)
        p.shader = RadialGradient(W * 0.82f, H * 0.16f, W * 0.38f, Color.argb(54, Color.red(grade), Color.green(grade), Color.blue(grade)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(W * 0.82f, H * 0.16f, W * 0.38f, p)
        p.shader = null

        // 品牌头
        p.typeface = black
        p.textSize = 40f
        p.letterSpacing = 0.08f
        p.color = muted
        c.drawText("ANEB PROBE", 72f, 104f, p)
        p.typeface = Typeface.SANS_SERIF
        p.letterSpacing = 0.12f
        p.textSize = 24f
        p.textAlign = Paint.Align.RIGHT
        p.color = faint
        c.drawText("AI NETWORK EXPERIENCE", W - 72f, 104f, p)

        // 中心分数
        val cx = W / 2f
        p.typeface = black
        p.textSize = 250f
        p.color = grade
        p.textAlign = Paint.Align.CENTER
        c.drawText(model.score?.toString() ?: "—", cx, 440f, p)
        p.textSize = 42f
        p.typeface = bold
        c.drawText("${model.gradeLabel} · AI 体验分", cx, 515f, p)
        p.typeface = Typeface.SANS_SERIF
        p.textSize = 28f
        p.color = muted
        c.drawText(model.networkLine, cx, 564f, p)

        // 结论文案（自动换行）
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.SANS_SERIF
        p.textSize = 36f
        p.color = Color.argb(196, Color.red(ink), Color.green(ink), Color.blue(ink))
        drawWrapped(c, p, model.verdict, 96f, 690f, W - 192f, 52f)

        // 三指标：只有真实展示态值；缺失仍为破折号。
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = hair
        c.drawLine(72f, 780f, W - 72f, 780f, p)
        c.drawLine(72f, 965f, W - 72f, 965f, p)
        val tiles = model.tiles.take(3)
        val tileW = (W - 144f) / 3f
        tiles.forEachIndexed { i, t ->
            val x = 72f + i * tileW
            if (i > 0) {
                p.style = Paint.Style.STROKE
                p.color = hair
                c.drawLine(x, 818f, x, 925f, p)
            }
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = bold
            p.textSize = 48f
            p.color = t.colorArgb
            c.drawText(t.value, x + tileW / 2f, 872f, p)
            p.typeface = Typeface.SANS_SERIF
            p.textSize = 25f
            p.color = muted
            c.drawText(t.label, x + tileW / 2f, 920f, p)
            p.textAlign = Paint.Align.LEFT
        }

        // 口径边界 + 本机生成标识（不伪造二维码）。
        p.typeface = Typeface.SANS_SERIF
        p.textSize = 27f
        p.color = faint
        c.drawText("由 ANEB Probe 在本机生成", 72f, 1188f, p)
        c.drawText("应用层路径体验 · 非运营商全网评级", 72f, 1230f, p)
        p.textAlign = Paint.Align.RIGHT
        p.color = muted
        p.typeface = bold
        c.drawText("AN", W - 72f, 1230f, p)
        p.textAlign = Paint.Align.LEFT
        p.letterSpacing = 0f

        return bmp
    }

    private fun drawWrapped(
        c: Canvas, p: Paint, text: String, x: Float, y: Float, maxWidth: Float, lineH: Float,
    ) {
        var line = StringBuilder()
        var cy = y
        for (ch in text) {
            line.append(ch)
            if (p.measureText(line.toString()) > maxWidth) {
                val s = line.toString()
                c.drawText(s.dropLast(1), x, cy, p)
                line = StringBuilder(s.takeLast(1))
                cy += lineH
            }
        }
        if (line.isNotEmpty()) c.drawText(line.toString(), x, cy, p)
    }

    private fun saveToPictures(context: Context, bmp: Bitmap): Uri? {
        val name = "aneb_share_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ANEB")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out: OutputStream? = resolver.openOutputStream(uri)
            out?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            uri
        } catch (e: Exception) {
            Log.i("AnebProbe", "SHARE_SAVE_FAIL error=${e.javaClass.simpleName}")
            null
        }
    }
}
