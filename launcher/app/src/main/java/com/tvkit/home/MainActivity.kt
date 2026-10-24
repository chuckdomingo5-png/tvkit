package com.tvkit.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal Apple TV-style home: wallpaper, clock, and a dock of favourite apps plus "All Apps".
 * Everything is built in code with plain framework views: no libraries, fast start.
 */
class MainActivity : Activity() {

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshDock() }

    private lateinit var wallpaper: WallpaperView
    private lateinit var dock: LinearLayout
    private lateinit var overlay: FrameLayout
    private var allApps: List<AppEntry> = emptyList()
    private var overlayDirty = true

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 800)
        }
    }

    private val roundProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(26).toFloat())
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.clipChildren = false

        wallpaper = WallpaperView(this)
        root.addView(wallpaper, FrameLayout.LayoutParams(matchParent, matchParent))

        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0xB3000000.toInt())
        )
        root.addView(scrim, FrameLayout.LayoutParams(matchParent, dp(380), Gravity.BOTTOM))

        val clock = TextClock(this)
        clock.format12Hour = "EEE, MMM d    h:mm a"
        clock.format24Hour = "EEE, MMM d    HH:mm"
        clock.setTextColor(Color.WHITE)
        clock.textSize = 24f
        clock.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        val clockLp = FrameLayout.LayoutParams(wrapContent, wrapContent, Gravity.TOP or Gravity.END)
        clockLp.setMargins(0, dp(40), dp(56), 0)
        root.addView(clock, clockLp)

        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        dock.clipChildren = false
        dock.clipToPadding = false
        dock.setPadding(dp(28), dp(24), dp(28), dp(36))
        val pill = GradientDrawable()
        pill.cornerRadius = dp(46).toFloat()
        pill.setColor(0x4D000000)
        dock.background = pill
        val dockLp = FrameLayout.LayoutParams(wrapContent, wrapContent, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dockLp.bottomMargin = dp(48)
        root.addView(dock, dockLp)

        overlay = FrameLayout(this)
        overlay.visibility = View.GONE
        overlay.setBackgroundColor(0xE6101418.toInt())
        root.addView(overlay, FrameLayout.LayoutParams(matchParent, matchParent))

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        filter.addDataScheme("package")
        registerReceiver(packageReceiver, filter)
        wallpaper.start()
        refreshDock()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(packageReceiver) } catch (e: IllegalArgumentException) { }
        handler.removeCallbacks(refreshRunnable)
        wallpaper.stop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while we are already showing: go back to the dock.
        if (overlay.visibility == View.VISIBLE) hideOverlay()
        focusFirst()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // A launcher never exits; Back only closes All Apps.
            if (overlay.visibility == View.VISIBLE) hideOverlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ dock

    private fun refreshDock() {
        val hadFocus = dock.hasFocus()
        dock.removeAllViews()
        allApps = AppResolver.allApps(this)
        val favs = AppResolver.favorites(this, allApps)
        for (a in favs) {
            addToDock(makeTile(a.label, a.icon, dp(116), true) { launch(a) })
        }
        addToDock(makeTile(getString(R.string.all_apps), GridDotsDrawable(), dp(116), true) { showOverlay() })
        overlayDirty = true
        if (hadFocus || overlay.visibility != View.VISIBLE) dock.post { focusFirst() }
    }

    private fun addToDock(tile: View) {
        val lp = LinearLayout.LayoutParams(wrapContent, wrapContent)
        lp.setMargins(dp(14), 0, dp(14), 0)
        dock.addView(tile, lp)
    }

    private fun focusFirst() {
        if (dock.childCount == 0) return
        ((dock.getChildAt(0) as ViewGroup).getChildAt(0)).requestFocus()
    }

    private fun launch(a: AppEntry) {
        try {
            startActivity(a.launch)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ all apps overlay

    private fun showOverlay() {
        if (overlayDirty) buildOverlay()
        overlay.visibility = View.VISIBLE
        overlay.post {
            val scroll = overlay.getChildAt(0) as? ViewGroup
            val grid = scroll?.getChildAt(0) as? ViewGroup
            val first = grid?.getChildAt(0) as? ViewGroup
            first?.getChildAt(0)?.requestFocus()
        }
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        if (dock.childCount > 0) {
            ((dock.getChildAt(dock.childCount - 1) as ViewGroup).getChildAt(0)).requestFocus()
        }
    }

    private fun buildOverlay() {
        overlay.removeAllViews()
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.setPadding(dp(64), dp(56), dp(64), dp(56))

        val grid = GridLayout(this)
        grid.columnCount = 7
        grid.clipChildren = false
        grid.clipToPadding = false
        for (a in allApps) {
            val tile = makeTile(a.label, a.icon, dp(104), false) {
                overlay.visibility = View.GONE
                launch(a)
            }
            val lp = GridLayout.LayoutParams()
            lp.setMargins(dp(12), dp(12), dp(12), dp(16))
            grid.addView(tile, lp)
        }
        scroll.addView(grid, ViewGroup.LayoutParams(wrapContent, wrapContent))
        overlay.addView(scroll, FrameLayout.LayoutParams(matchParent, matchParent))
        overlayDirty = false
    }

    // ------------------------------------------------------------------ tiles

    private fun tileBackground(focused: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(26).toFloat()
        d.setColor(if (focused) 0x66FFFFFF else 0x2EFFFFFF)
        if (focused) d.setStroke(dp(3), Color.WHITE)
        return d
    }

    /** Returns a holder whose first child is the focusable tile and second child the label. */
    private fun makeTile(label: String, icon: Drawable, size: Int, labelOnFocusOnly: Boolean, onClick: () -> Unit): View {
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.gravity = Gravity.CENTER_HORIZONTAL
        holder.clipChildren = false
        holder.clipToPadding = false

        val tile = FrameLayout(this)
        tile.isFocusable = true
        tile.isClickable = true
        tile.background = tileBackground(false)
        tile.outlineProvider = roundProvider
        tile.clipToOutline = true

        val img = ImageView(this)
        img.setImageDrawable(icon)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(dp(12), dp(12), dp(12), dp(12))
        tile.addView(img, FrameLayout.LayoutParams(size, size))
        holder.addView(tile, LinearLayout.LayoutParams(size, size))

        val text = TextView(this)
        text.text = label
        text.setTextColor(Color.WHITE)
        text.textSize = 16f
        text.gravity = Gravity.CENTER
        text.maxLines = 1
        text.ellipsize = TextUtils.TruncateAt.END
        text.setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
        text.alpha = if (labelOnFocusOnly) 0f else 0.8f
        val textLp = LinearLayout.LayoutParams(size + dp(24), wrapContent)
        textLp.topMargin = dp(18)
        holder.addView(text, textLp)

        tile.setOnFocusChangeListener { v, hasFocus ->
            v.background = tileBackground(hasFocus)
            val s = if (hasFocus) 1.18f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            v.elevation = if (hasFocus) dp(12).toFloat() else 0f
            val a = if (hasFocus) 1f else if (labelOnFocusOnly) 0f else 0.8f
            text.animate().alpha(a).setDuration(140).start()
        }
        tile.setOnClickListener { onClick() }
        return holder
    }

    /** Nine dots: the "All Apps" icon. */
    private class GridDotsDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.color = Color.WHITE
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val cell = minOf(b.width(), b.height()) / 5f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            for (r in -1..1) {
                for (c in -1..1) {
                    canvas.drawCircle(cx + c * cell * 1.3f, cy + r * cell * 1.3f, cell * 0.38f, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
