package com.taajaveli.homebase

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.concurrent.Executors

class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var scroll: ScrollView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var favHint: TextView
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var favRow: LinearLayout
    private lateinit var appsLabel: TextView
    private lateinit var grid: GridLayout

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var apps: Map<String, AppEntry> = emptyMap()
    private var rendered = false
    private var loadGen = 0

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContentView(buildUi())
        updateClock()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(timeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(timeReceiver, filter)
        }
        updateClock()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(timeReceiver) } catch (e: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    /** Pressing Home while already home: jump back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goTop()
    }

    /** Back does nothing on a home screen except return to the top. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goTop()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(BG_TOP, BG_BOTTOM),
            )
        }

        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(40))
            clipChildren = false
            clipToPadding = false
        }

        // Header: clock + date on the left, three buttons on the right.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = label(44f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        dateView = label(15f, MUTED)
        timeColumn.addView(clock)
        timeColumn.addView(dateView)
        header.addView(timeColumn, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(pill("Wi-Fi") { open(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        header.addView(pill("Settings") { open(Intent(Settings.ACTION_SETTINGS)) })
        header.addView(pill("Options") { showLauncherOptions() })
        column.addView(header)

        // Favorites row.
        column.addView(sectionTitle("Favorites"))
        favHint = label(14f, MUTED).apply {
            text = "Hold OK on any app below to pin it here."
            setPadding(0, dp(6), 0, dp(6))
        }
        column.addView(favHint)
        favRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            addView(favRow)
        }
        column.addView(favScroll)

        // All apps grid.
        appsLabel = sectionTitle("Apps")
        column.addView(appsLabel)
        grid = GridLayout(this).apply {
            columnCount = columns()
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        column.addView(grid)

        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun columns(): Int {
        val m = resources.displayMetrics
        val widthDp = m.widthPixels / m.density
        return ((widthDp - 96 - 12) / (TILE_W + 2 * TILE_M)).toInt().coerceIn(3, 8)
    }

    // ---------------------------------------------------------------- data

    private fun reload() {
        val gen = ++loadGen
        val self = packageName
        try {
            io.execute {
                val loaded = AppLoader.load(packageManager, self)
                main.post {
                    if (gen != loadGen || isDestroyed) return@post
                    val changed = loaded.keys != apps.keys
                    apps = loaded
                    if (changed || !rendered) render()
                }
            }
        } catch (e: Exception) {
            // Executor shut down while finishing; nothing to do.
        }
    }

    /**
     * Rebuild both rows. Keeps focus on the same app when possible, or on
     * [focusPkg] (in favorites if [focusInFav]) when given.
     */
    private fun render(focusPkg: String? = null, focusInFav: Boolean = false) {
        rendered = true
        val focused = currentFocus
        val wasTile = focused?.tag is String
        val keepPkg = focusPkg ?: (focused?.tag as? String)
        val keepInFav = if (focusPkg != null) focusInFav else focused?.parent === favRow

        favRow.removeAllViews()
        grid.removeAllViews()

        val favorites = store.favorites().mapNotNull { apps[it] }
        favorites.forEach { favRow.addView(tile(it, inFav = true)) }
        favHint.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
        favScroll.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE

        val hidden = store.hidden()
        val showHidden = store.showHidden
        val visible = apps.values.filter { showHidden || it.pkg !in hidden }
        visible.forEach { app ->
            grid.addView(tile(app, inFav = false).apply {
                if (app.pkg in hidden) alpha = 0.4f
            })
        }
        val hiddenCount = apps.keys.count { it in hidden }
        appsLabel.text = when {
            hiddenCount == 0 -> "APPS"
            showHidden -> "APPS · SHOWING $hiddenCount HIDDEN"
            else -> "APPS · $hiddenCount HIDDEN"
        }

        val target = keepPkg?.let {
            (if (keepInFav) findTile(favRow, it) else findTile(grid, it))
                ?: findTile(grid, it)
                ?: findTile(favRow, it)
        }
        when {
            target != null -> target.requestFocus()
            focused == null || wasTile || !focused.isAttachedToWindow -> firstTile()?.requestFocus()
        }
    }

    private fun findTile(parent: ViewGroup, pkg: String): View? =
        (0 until parent.childCount).map { parent.getChildAt(it) }.firstOrNull { it.tag == pkg }

    private fun firstTile(): View? = favRow.getChildAt(0) ?: grid.getChildAt(0)

    private fun goTop() {
        scroll.smoothScrollTo(0, 0)
        favScroll.smoothScrollTo(0, 0)
        firstTile()?.requestFocus()
    }

    // ---------------------------------------------------------------- tiles

    private fun tile(app: AppEntry, inFav: Boolean): View {
        val margin = dp(TILE_M)
        val ring = rounded(Color.TRANSPARENT, dp(10), dp(3), Color.WHITE)

        val card = FrameLayout(this).apply {
            tag = app.pkg
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = rounded(CARD, dp(10))
            clipToOutline = true
            contentDescription = app.label

            setOnFocusChangeListener { v, has ->
                foreground = if (has) ring else null
                v.animate()
                    .scaleX(if (has) 1.08f else 1f)
                    .scaleY(if (has) 1.08f else 1f)
                    .translationZ(if (has) dp(8).toFloat() else 0f)
                    .setDuration(140)
                    .start()
            }
            setOnClickListener { launch(app) }
            setOnLongClickListener { showAppOptions(app, inFav); true }
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    showAppOptions(app, inFav); true
                } else false
            }

            layoutParams = if (inFav) {
                LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply {
                    setMargins(margin, margin, margin, margin)
                }
            } else {
                GridLayout.LayoutParams().apply {
                    width = dp(TILE_W)
                    height = dp(TILE_H)
                    setMargins(margin, margin, margin, margin)
                }
            }
        }

        val banner = app.banner
        if (banner != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(banner.copy())
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            inner.addView(ImageView(this).apply {
                setImageDrawable(app.icon.copy())
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            inner.addView(label(12f, Color.WHITE).apply {
                text = app.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            card.addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        return card
    }

    private fun Drawable.copy(): Drawable =
        constantState?.newDrawable(resources)?.mutate() ?: this

    // ---------------------------------------------------------------- actions

    private fun launch(app: AppEntry) {
        val category = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        open(
            Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppOptions(app: AppEntry, inFav: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun item(text: String, action: () -> Unit) { labels += text; actions += action }

        val isFav = store.isFavorite(app.pkg)
        val isHidden = app.pkg in store.hidden()

        if (isFav) {
            item("Move left") { store.moveFavorite(app.pkg, -1); render(app.pkg, true) }
            item("Move right") { store.moveFavorite(app.pkg, 1); render(app.pkg, true) }
            item("Remove from Favorites") { store.removeFavorite(app.pkg); render(app.pkg, false) }
        } else {
            item("Pin to Favorites") { store.addFavorite(app.pkg); render(app.pkg, inFav) }
        }
        item(if (isHidden) "Unhide" else "Hide from Apps") {
            store.setHidden(app.pkg, !isHidden)
            render(app.pkg, inFav)
        }
        item("App info") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        item("Uninstall") {
            open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showLauncherOptions() {
        val toggleLabel = if (store.showHidden) "Stop showing hidden apps" else "Show hidden apps"
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Homebase")
            .setItems(arrayOf(toggleLabel, "Android TV settings", "About")) { _, which ->
                when (which) {
                    0 -> { store.showHidden = !store.showHidden; render() }
                    1 -> open(Intent(Settings.ACTION_SETTINGS))
                    2 -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Homebase 1.0")
                        .setMessage(
                            "A plain home screen: your apps, nothing else.\n\n" +
                                "No ads, no recommendations, no tracking. It doesn't even " +
                                "have internet permission."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun updateClock() {
        if (!::clock.isInitialized) return
        val now = Date()
        clock.text = DateFormat.getTimeFormat(this).format(now)
        dateView.text = DateFormat.format("EEEE, MMMM d", now)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sp: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
    }

    private fun sectionTitle(title: String) = label(13f, MUTED).apply {
        text = title.uppercase()
        letterSpacing = 0.12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(24), 0, dp(2))
    }

    private fun pill(text: String, onClick: () -> Unit): TextView = label(15f, Color.WHITE).apply {
        this.text = text
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(8), dp(18), dp(8))
        val normal = rounded(PILL, dp(20))
        val focused = rounded(Color.WHITE, dp(20))
        background = normal
        setOnFocusChangeListener { _, has ->
            background = if (has) focused else normal
            setTextColor(if (has) Color.BLACK else Color.WHITE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) }
    }

    private fun rounded(color: Int, radius: Int, strokeWidth: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private companion object {
        const val TILE_W = 150
        const val TILE_H = 84
        const val TILE_M = 6
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val BG_TOP = 0xFF0A0C10.toInt()
        val BG_BOTTOM = 0xFF151B24.toInt()
        val CARD = 0xFF1C232D.toInt()
        val PILL = 0x22FFFFFF
        val MUTED = 0xFF8B95A5.toInt()
    }
}
