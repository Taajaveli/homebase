package com.taajaveli.homebase

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

enum class Scene(val label: String) {
    OFF("Still (no animation)"),
    AURORA("Aurora"),
    STARFIELD("Starfield"),
    WAVES("Waves"),
    EMBERS("Embers"),
    SYSTEM("System live wallpaper…");

    companion object {
        fun from(name: String?): Scene = values().firstOrNull { it.name == name } ?: AURORA
    }
}

/**
 * Animated backgrounds drawn by Homebase itself (Google TV usually has no
 * live-wallpaper support of its own). Runs at ~30 fps and only while the
 * home screen is visible, so it costs nothing while you're in an app.
 */
class LiveWallpaperView(context: Context) : View(context) {

    var scene: Scene = Scene.OFF
        set(value) {
            if (field == value) return
            field = value
            setup()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val path = Path()
    private val m = Matrix()
    private val rnd = Random(System.nanoTime())
    private val frame = Runnable { invalidate() }

    private var running = false
    private var last = 0L
    private var t = 0f

    private class Blob(
        val shader: RadialGradient,
        val fx: Float, val fy: Float, val rf: Float,
        val sx: Float, val sy: Float, val p: Float,
    )
    private var blobs: List<Blob> = emptyList()

    // Shared particle buffers (stars / embers).
    private var n = 0
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pz = FloatArray(0)
    private var ps = FloatArray(0)
    private var pp = FloatArray(0)
    private var pv = FloatArray(0)

    fun setRunning(on: Boolean) {
        running = on
        removeCallbacks(frame)
        last = 0L
        if (on) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setup()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    // ------------------------------------------------------------------ setup

    private fun setup() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val clamp = Shader.TileMode.CLAMP

        bgPaint.shader = when (scene) {
            Scene.AURORA -> LinearGradient(0f, 0f, 0f, h, 0xFF04060B.toInt(), 0xFF0A0F1A.toInt(), clamp)
            Scene.STARFIELD -> RadialGradient(w / 2, h / 2, max(w, h) * 0.7f, 0xFF0D1426.toInt(), 0xFF020308.toInt(), clamp)
            Scene.WAVES -> LinearGradient(0f, 0f, 0f, h, 0xFF050B14.toInt(), 0xFF0B1E33.toInt(), clamp)
            Scene.EMBERS -> LinearGradient(0f, 0f, 0f, h, 0xFF050303.toInt(), 0xFF1F0B05.toInt(), clamp)
            else -> LinearGradient(0f, 0f, w, h, BG_TOP, BG_BOTTOM, clamp)
        }

        when (scene) {
            Scene.AURORA -> blobs = listOf(
                blob(0xFF14B8A6.toInt(), 0.25f, 0.30f, 0.55f, 0.13f, 0.10f, 0.0f),
                blob(0xFF7C3AED.toInt(), 0.75f, 0.35f, 0.60f, 0.11f, 0.14f, 2.0f),
                blob(0xFF22C55E.toInt(), 0.55f, 0.80f, 0.50f, 0.16f, 0.08f, 4.0f),
                blob(0xFF2563EB.toInt(), 0.15f, 0.85f, 0.45f, 0.09f, 0.12f, 5.5f),
            )
            Scene.STARFIELD -> {
                allocate(170)
                for (i in 0 until n) {
                    px[i] = r(-1f, 1f); py[i] = r(-1f, 1f); pz[i] = r(0.05f, 1f); pp[i] = r(0f, 6.28f)
                }
            }
            Scene.EMBERS -> {
                allocate(60)
                for (i in 0 until n) spawnEmber(i, initial = true)
            }
            else -> Unit
        }
    }

    private fun blob(color: Int, fx: Float, fy: Float, rf: Float, sx: Float, sy: Float, p: Float) = Blob(
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(alpha(color, 0x66), alpha(color, 0x22), alpha(color, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        ),
        fx, fy, rf, sx, sy, p,
    )

    private fun allocate(count: Int) {
        n = count
        px = FloatArray(count); py = FloatArray(count); pz = FloatArray(count)
        ps = FloatArray(count); pp = FloatArray(count); pv = FloatArray(count)
    }

    private fun spawnEmber(i: Int, initial: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        px[i] = r(0f, w)
        py[i] = if (initial) r(0f, h) else h + r(0f, 40f * density)
        pv[i] = r(0.03f, 0.09f)
        ps[i] = density * r(1f, 2.6f)
        pp[i] = r(0f, 6.28f)
        pz[i] = r(0.2f, 1f)
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (bgPaint.shader == null) setup()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        t += dt

        when (scene) {
            Scene.AURORA -> drawAurora(canvas, w, h)
            Scene.STARFIELD -> drawStars(canvas, w, h, dt)
            Scene.WAVES -> drawWaves(canvas, w, h)
            Scene.EMBERS -> drawEmbers(canvas, w, h, dt)
            else -> Unit
        }

        if (running && scene != Scene.OFF && scene != Scene.SYSTEM) {
            removeCallbacks(frame)
            postDelayed(frame, FRAME_MS)
        }
    }

    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        val dim = max(w, h)
        for (b in blobs) {
            val cx = w * (b.fx + 0.18f * sin(t * b.sx + b.p))
            val cy = h * (b.fy + 0.16f * cos(t * b.sy + b.p * 1.7f))
            val radius = dim * b.rf * (1f + 0.1f * sin(t * 0.2f + b.p))
            m.setScale(radius, radius)
            m.postTranslate(cx, cy)
            b.shader.setLocalMatrix(m)
            paint.shader = b.shader
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null
    }

    private fun drawStars(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val cx = w / 2
        val cy = h / 2
        val f = max(w, h) * 0.5f
        for (i in 0 until n) {
            pz[i] -= dt * 0.07f
            val sx = cx + px[i] / pz[i] * f * 0.5f
            val sy = cy + py[i] / pz[i] * f * 0.5f
            if (pz[i] <= 0.03f || sx < -10f || sx > w + 10f || sy < -10f || sy > h + 10f) {
                px[i] = r(-1f, 1f); py[i] = r(-1f, 1f); pz[i] = 1f
                continue
            }
            val depth = 1f - pz[i]
            val twinkle = 0.7f + 0.3f * sin(t * 2.5f + pp[i])
            paint.color = 0xFFDCE6FF.toInt()
            paint.alpha = (255 * depth * twinkle).toInt().coerceIn(0, 255)
            canvas.drawCircle(sx, sy, density * (0.5f + depth * 2.2f), paint)
        }
    }

    private fun drawWaves(canvas: Canvas, w: Float, h: Float) {
        val step = 16f * density
        for (i in 0 until 4) {
            val base = h * (0.50f + i * 0.12f)
            val amp = h * (0.03f + i * 0.01f)
            val k = (2 * PI / (w * (1.1f - i * 0.15f))).toFloat()
            val speed = 0.35f + i * 0.15f
            path.reset()
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + step) {
                val y = base +
                    amp * sin(x * k + t * speed + i * 1.7f) +
                    amp * 0.4f * sin(x * k * 2.3f - t * speed * 0.8f + i)
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(w, h)
            path.close()
            paint.color = WAVE_COLORS[i]
            canvas.drawPath(path, paint)
        }
    }

    private fun drawEmbers(canvas: Canvas, w: Float, h: Float, dt: Float) {
        for (i in 0 until n) {
            py[i] -= pv[i] * h * dt
            if (py[i] < -20f * density) spawnEmber(i, initial = false)
            val x = px[i] + sin(t * 0.6f + pp[i]) * 30f * density * pz[i]
            val life = (py[i] / h).coerceIn(0f, 1f)
            val flicker = 0.75f + 0.25f * sin(t * 4f + pp[i] * 3f)

            paint.color = 0xFFFF7A2F.toInt()
            paint.alpha = (60 * life * flicker).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, py[i], ps[i] * 4f, paint)

            paint.color = 0xFFFFC27A.toInt()
            paint.alpha = (230 * life * flicker).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, py[i], ps[i], paint)
        }
    }

    // ------------------------------------------------------------------ util

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    companion object {
        private const val FRAME_MS = 33L
        val BG_TOP = 0xFF0A0C10.toInt()
        val BG_BOTTOM = 0xFF151B24.toInt()
        private val WAVE_COLORS = intArrayOf(0x331D4ED8, 0x330EA5E9, 0x4414B8A6, 0x552563EB)
    }
}
