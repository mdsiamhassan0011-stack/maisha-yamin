package com.maishayamin.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * আলো-ঝলমলে জাদুর গোলক। অবস্থা অনুযায়ী নড়াচড়া বদলায়,
 * আর মাইকের আওয়াজের সাথে সাথে চারপাশের আলোর দাগ নাচে।
 */
class OrbView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE

    private var level = 0f
    private var pal = intArrayOf(
        Color.parseColor("#FF3D9A"),
        Color.parseColor("#FFC857"),
        Color.parseColor("#B388FF")
    )
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var glowShader: Shader? = null
    private var coreShader: Shader? = null
    private var sweepShader: Shader? = null
    private var baseR = 0f
    private var avatar: Bitmap? = null
    private var avatarShader: BitmapShader? = null
    private val avatarMatrix = Matrix()
    private var cx = 0f
    private var cy = 0f
    private var phase = 0f
    private var lastT = 0L

    fun setPalette(c: IntArray) {
        pal = c
        rebuild()
        invalidate()
    }

    /** গোলকের ভেতরে প্রোফাইল ছবি বসায়। null দিলে ছবি সরে যায়। */
    fun setAvatar(b: Bitmap?) {
        avatar = b
        rebuildAvatar()
        invalidate()
    }

    private fun rebuildAvatar() {
        val b = avatar
        if (b == null || baseR <= 0f) {
            avatarShader = null
            return
        }
        val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = (2f * baseR) / min(b.width, b.height).toFloat()
        avatarMatrix.reset()
        avatarMatrix.setScale(scale, scale)
        avatarMatrix.postTranslate(cx - b.width * scale / 2f, cy - b.height * scale / 2f)
        shader.setLocalMatrix(avatarMatrix)
        avatarShader = shader
    }

    fun pushLevel(rmsDb: Float) {
        val target = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        level = level * 0.5f + target * 0.5f
    }

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun darken(color: Int): Int =
        Color.rgb(Color.red(color) / 3, Color.green(color) / 3, Color.blue(color) / 3)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        baseR = min(w, h) * 0.19f
        rebuild()
    }

    private fun rebuild() {
        if (baseR <= 0f) return
        glowShader = RadialGradient(
            cx, cy, baseR * 2.3f,
            intArrayOf(withAlpha(pal[0], 150), withAlpha(pal[2], 60), Color.TRANSPARENT),
            floatArrayOf(0.25f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        coreShader = RadialGradient(
            cx - baseR * 0.35f, cy - baseR * 0.4f, baseR * 1.5f,
            intArrayOf(Color.WHITE, pal[1], pal[0], darken(pal[2])),
            floatArrayOf(0f, 0.25f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(pal[0], pal[1], pal[2], pal[0]),
            null
        )
        rebuildAvatar()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseR <= 0f) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1000f).coerceAtMost(0.05f)
        lastT = now
        val time = now / 1000f

        val speed = when (mode) {
            Mode.IDLE -> 0.35f
            Mode.LISTENING -> 0.9f
            Mode.THINKING -> 2.6f
            Mode.SPEAKING -> 1.4f
        }
        phase += dt * speed
        if (mode != Mode.LISTENING) level *= 0.93f

        val amp = when (mode) {
            Mode.LISTENING -> 0.15f + level * 0.85f
            Mode.SPEAKING -> 0.5f + 0.35f * abs(sin(time * 9f))
            Mode.THINKING -> 0.3f
            Mode.IDLE -> 0.08f
        }
        val pulse = when (mode) {
            Mode.IDLE -> 0.025f * sin(time * 1.6f)
            Mode.LISTENING -> 0.02f + level * 0.16f
            Mode.THINKING -> 0.04f * sin(time * 10f)
            Mode.SPEAKING -> 0.05f + 0.05f * sin(time * 8f)
        }

        // গোলকের আলো ও ভেতরের অংশ
        canvas.save()
        val s = 1f + pulse
        canvas.scale(s, s, cx, cy)
        paint.style = Paint.Style.FILL
        paint.shader = glowShader
        canvas.drawCircle(cx, cy, baseR * 2.3f, paint)
        paint.shader = coreShader
        canvas.drawCircle(cx, cy, baseR, paint)
        paint.shader = null
        val av = avatarShader
        if (av != null) {
            paint.shader = av
            canvas.drawCircle(cx, cy, baseR * 0.97f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d * 2f
            paint.color = withAlpha(Color.WHITE, 170)
            canvas.drawCircle(cx, cy, baseR, paint)
            paint.style = Paint.Style.FILL
        }
        for (i in 0 until (if (av == null) 4 else 0)) {
            val a = phase * (1.2f + i * 0.5f) + i * 1.57f
            val rr = baseR * 0.45f
            val x = cx + cos(a) * rr
            val y = cy + sin(a * 1.3f) * rr * 0.8f
            paint.color = withAlpha(Color.WHITE, 40)
            canvas.drawCircle(x, y, baseR * (0.22f + 0.05f * i), paint)
        }
        canvas.restore()

        // ঘুরন্ত রঙিন বলয়
        canvas.save()
        canvas.rotate(phase * 70f, cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d * 5f
        paint.shader = sweepShader
        canvas.drawCircle(cx, cy, baseR * 1.16f * (1f + pulse * 0.6f), paint)
        paint.shader = null
        canvas.restore()

        // তিনটি আলোর চাপ
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0..2) {
            val rr = baseR * (1.32f + 0.13f * i) * (1f + pulse * 0.4f)
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            paint.color = withAlpha(pal[i], 200 - i * 40)
            paint.strokeWidth = d * (3f - i * 0.7f)
            val dir = if (i % 2 == 0) 1f else -1f
            canvas.drawArc(rect, phase * (50f + 30f * i) * dir + i * 70f, 90f + i * 40f, false, paint)
        }

        // আওয়াজের সাথে নাচা আলোর দাগ
        val n = 56
        val inner = baseR * 1.62f
        paint.strokeWidth = d * 2.2f
        for (i in 0 until n) {
            val ang = i * (2f * Math.PI.toFloat() / n) + phase * 0.15f
            val w = 0.5f + 0.5f * sin(time * 5f + i * 0.55f) * sin(time * 2.3f + i * 0.21f)
            val len = d * 3f + d * 30f * amp * (0.35f + 0.65f * abs(w))
            paint.color = withAlpha(pal[i % 3], 140 + (115f * amp).toInt())
            canvas.drawLine(
                cx + cos(ang) * inner, cy + sin(ang) * inner,
                cx + cos(ang) * (inner + len), cy + sin(ang) * (inner + len),
                paint
            )
        }

        // ঝিকমিক তারা
        paint.style = Paint.Style.FILL
        for (i in 0 until 22) {
            val dirSign = if (i % 2 == 0) 1f else -1f
            val a = phase * (0.25f + (i % 5) * 0.07f) * dirSign + i * 0.9f
            val dist = baseR * (1.75f + 0.3f * sin(time * 0.7f + i)) + (i % 4) * d * 8f
            val x = cx + cos(a) * dist
            val y = cy + sin(a) * dist
            val tw = 0.5f + 0.5f * sin(time * 2f + i * 1.7f)
            paint.color = withAlpha(pal[i % 3], (60 + 150 * tw).toInt())
            canvas.drawCircle(x, y, d * (1.2f + (i % 3) * 0.7f) * (0.6f + 0.4f * tw), paint)
        }

        postInvalidateOnAnimation()
    }
}
