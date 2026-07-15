package com.aneb.probe.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
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
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#0A0E17"))

        val ink = Color.parseColor("#EEF2F8")
        val muted = Color.parseColor("#8B96AC")
        val hair = Color.parseColor("#26314A")
        val brand2 = Color.parseColor("#7D7FFB")
        val grade = model.gradeColorArgb

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val black = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

        // 品牌头
        p.typeface = black
        p.textSize = 46f
        p.color = ink
        c.drawText("A", 72f, 110f, p)
        val aW = p.measureText("A")
        p.color = brand2
        c.drawText("NEB", 72f + aW, 110f, p)
        p.typeface = Typeface.SANS_SERIF
        p.textSize = 28f
        p.color = muted
        c.drawText("智能体网络测试 · Agent 体验分", 72f, 152f, p)

        // 脉冲环
        val cx = W / 2f
        val cy = 470f
        val r = 250f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        p.color = hair
        c.drawCircle(cx, cy, r, p)
        val frac = ((model.score ?: 0).coerceIn(0, 100)) / 100f
        p.color = grade
        p.strokeWidth = 20f
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * frac, false, p)
        // 环刻度
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        val ticks = 60
        for (i in 0 until ticks) {
            val a = Math.toRadians(-90.0 + i.toDouble() / (ticks - 1) * 360.0)
            val lit = i.toFloat() / (ticks - 1) <= frac
            p.color = if (lit) grade else hair
            val r1 = r - 16f
            val r2 = r + 16f
            c.drawLine(
                cx + Math.cos(a).toFloat() * r1, cy + Math.sin(a).toFloat() * r1,
                cx + Math.cos(a).toFloat() * r2, cy + Math.sin(a).toFloat() * r2, p,
            )
        }
        p.style = Paint.Style.FILL

        // 中心分数
        p.typeface = black
        p.textSize = 200f
        p.color = grade
        p.textAlign = Paint.Align.CENTER
        c.drawText(model.score?.toString() ?: "—", cx, cy + 55f, p)
        p.textSize = 44f
        p.typeface = bold
        c.drawText(model.gradeLabel, cx, cy + 150f, p)
        p.textAlign = Paint.Align.LEFT

        // 结论文案（自动换行）
        p.typeface = Typeface.SANS_SERIF
        p.textSize = 38f
        p.color = ink
        drawWrapped(c, p, model.verdict, 72f, 830f, W - 144f, 52f)

        // 三瓦片
        val tiles = model.tiles.take(3)
        val gap = 24f
        val tileW = (W - 144f - gap * 2) / 3f
        val tileY = 990f
        val tileH = 180f
        tiles.forEachIndexed { i, t ->
            val x = 72f + i * (tileW + gap)
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#161D2B")
            c.drawRoundRect(RectF(x, tileY, x + tileW, tileY + tileH), 24f, 24f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = hair
            c.drawRoundRect(RectF(x, tileY, x + tileW, tileY + tileH), 24f, 24f, p)
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = bold
            p.textSize = 52f
            p.color = t.colorArgb
            c.drawText(t.value, x + tileW / 2f, tileY + 90f, p)
            p.typeface = Typeface.SANS_SERIF
            p.textSize = 28f
            p.color = muted
            c.drawText(t.label, x + tileW / 2f, tileY + 140f, p)
            p.textAlign = Paint.Align.LEFT
        }

        // 网络行 + 水印
        p.textSize = 30f
        p.color = muted
        c.drawText(model.networkLine, 72f, 1260f, p)
        p.textAlign = Paint.Align.RIGHT
        p.color = brand2
        p.typeface = bold
        c.drawText("ANEB Probe", W - 72f, 1260f, p)
        p.textAlign = Paint.Align.LEFT

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ANEB")
            }
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
