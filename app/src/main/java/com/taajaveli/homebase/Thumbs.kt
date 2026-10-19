package com.taajaveli.homebase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny image loader for Continue Watching artwork. No library needed. */
object Thumbs {
    private const val MAX_BYTES = 6 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun into(view: ImageView, uri: Uri, widthPx: Int) {
        val key = uri.toString()
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }

        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = try { decode(ctx, uri, widthPx) } catch (e: Throwable) { null }
            if (bmp != null) {
                cache.put(key, bmp)
                main.post { if (view.tag == key) view.setImageBitmap(bmp) }
            }
        }
    }

    private fun decode(ctx: Context, uri: Uri, widthPx: Int): Bitmap? {
        val bytes = read(ctx, uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun read(ctx: Context, uri: Uri): ByteArray? {
        val stream = when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return null
                }
                conn.inputStream
            }
            "content", "android.resource", "file" -> ctx.contentResolver.openInputStream(uri)
            else -> null
        } ?: return null

        return stream.use { s ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = s.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return null
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    }
}
