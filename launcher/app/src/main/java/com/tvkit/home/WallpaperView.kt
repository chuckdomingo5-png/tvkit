package com.tvkit.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Full-screen wallpaper that cross-fades to the next picture every few minutes.
 * Pictures: bundled assets/wallpapers plus anything in
 * /sdcard/Android/data/com.tvkit.home/files/wallpapers (push your own with adb).
 * Optional files/config.json: {"intervalMinutes": 10}
 */
class WallpaperView(context: Context) : FrameLayout(context) {

    private class Source(val asset: String?, val file: File?)

    private val bottom = newImageView()
    private val top = newImageView()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var sources: List<Source> = emptyList()
    private var index = -1
    private var intervalMs = 10 * 60 * 1000L
    private var running = false
    private var loadGen = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            next()
            handler.postDelayed(this, intervalMs)
        }
    }

    init {
        setBackgroundColor(Color.rgb(8, 12, 20))
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        top.visibility = View.INVISIBLE
        loadConfig()
    }

    private fun newImageView(): ImageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }

    private fun loadConfig() {
        val list = ArrayList<Source>()
        try {
            val dir = File(context.getExternalFilesDir(null), "wallpapers")
            dir.listFiles()?.filter { isImage(it.name) }?.sortedBy { it.name }?.forEach { list.add(Source(null, it)) }
        } catch (e: Exception) {
            // no custom wallpapers
        }
        try {
            context.assets.list("wallpapers")?.filter { isImage(it) }?.sorted()
                ?.forEach { list.add(Source("wallpapers/$it", null)) }
        } catch (e: Exception) {
            // no bundled wallpapers
        }
        list.shuffle()
        sources = list
        try {
            val cfg = File(context.getExternalFilesDir(null), "config.json")
            if (cfg.isFile) {
                val minutes = JSONObject(cfg.readText()).optDouble("intervalMinutes", 10.0)
                intervalMs = (minutes.coerceIn(0.5, 1440.0) * 60_000).toLong()
            }
        } catch (e: Exception) {
            // keep default interval
        }
    }

    private fun open(s: Source): InputStream =
        if (s.asset != null) context.assets.open(s.asset) else s.file!!.inputStream()

    private fun decode(src: Source): Bitmap? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1280)
        val h = dm.heightPixels.coerceAtLeast(720)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(src).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(src).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Fade to the next wallpaper (decoded off the main thread). */
    fun next() {
        if (sources.isEmpty()) return
        index = (index + 1) % sources.size
        val src = sources[index]
        val gen = ++loadGen
        io.execute {
            val bmp = try { decode(src) } catch (e: Throwable) { null }
            if (bmp != null) handler.post { if (gen == loadGen) show(bmp) }
        }
    }

    private fun show(bmp: Bitmap) {
        if (bottom.drawable == null) {
            bottom.setImageBitmap(bmp)
            return
        }
        top.animate().cancel()
        top.setImageBitmap(bmp)
        top.alpha = 0f
        top.visibility = View.VISIBLE
        top.animate().alpha(1f).setDuration(900).withEndAction {
            bottom.setImageBitmap(bmp)
            top.visibility = View.INVISIBLE
            top.setImageDrawable(null)
        }.start()
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }
}
