package com.taajaveli.homebase

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.WindowManager
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
    private lateinit var wallpaper: LiveWallpaperView
    private lateinit var scroll: ScrollView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var favHint: TextView
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var favRow: LinearLayout
    private lateinit var cwTitle: TextView
    private lateinit var cwHint: TextView
    private lateinit var cwScroll: HorizontalScrollView
    private lateinit var cwRow: LinearLayout
    private lateinit var appsLabel: TextView
    private lateinit var grid: GridLayout

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var apps: Map<String, AppEntry> = emptyMap()
    private var rendered = false
    private var loadGen = 0

    private var cwItems: List<WatchNextItem> = emptyList()
    private var cwAccess = true
    private var cwRendered = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContentView(buildUi())
        applyWallpaper()
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
        wallpaper.setRunning(true)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(timeReceiver) } catch (e: Exception) { }
        wallpaper.setRunning(false)
    }

    override fun onResume() {
        super.onResume()
        reload()
        // Ask once for Continue Watching access; after that it lives in Options.
        if (!store.askedTvAccess && store.continueWatching && !WatchNext.granted(this)) {
            store.askedTvAccess = true
            requestTvAccess()
        }
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

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQ_TV) return
        if (!WatchNext.granted(this)) {
            Toast.makeText(
                this,
                "Your box didn't allow it from here. Run tools/debloat.sh again from your computer to switch it on.",
                Toast.LENGTH_LONG,
            ).show()
        }
        reload()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = FrameLayout(this)

        wallpaper = LiveWallpaperView(this)
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        // Darkens the background a little so tiles and text stay easy to read.
        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x40000000, 0x8C000000.toInt()),
            )
        }, FrameLayout.LayoutParams(MATCH, MATCH))

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

        // Header: clock + date on the left, buttons on the right.
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
        header.addView(pill("Background") { showWallpaperPicker() })
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
        favRow = row()
        favScroll = hScroll(favRow)
        column.addView(favScroll)

        // Continue Watching row.
        cwTitle = sectionTitle("Continue Watching").apply { visibility = View.GONE }
        column.addView(cwTitle)
        cwHint = label(14f, MUTED).apply {
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
        }
        column.addView(cwHint)
        cwRow = row()
        cwScroll = hScroll(cwRow).apply { visibility = View.GONE }
        column.addView(cwScroll)

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

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }

    private fun hScroll(child: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        addView(child)
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
        val ctx = applicationContext
        try {
            io.execute {
                val loaded = AppLoader.load(packageManager, self)
                main.post {
                    if (gen != loadGen || isDestroyed) return@post
                    val changed = loaded.keys != apps.keys
                    apps = loaded
                    if (changed || !rendered) renderApps()
                }

                val result = if (store.continueWatching) {
                    WatchNext.load(ctx, loaded.mapValues { it.value.label }, store.dismissed())
                } else {
                    WatchNextResult(emptyList(), true)
                }
                main.post {
                    if (gen != loadGen || isDestroyed) return@post
                    val same = cwRendered &&
                        result.hasAccess == cwAccess &&
                        result.items.map { it.key to it.progress } == cwItems.map { it.key to it.progress }
                    cwItems = result.items
                    cwAccess = result.hasAccess
                    if (!same) renderContinue()
                }
            }
        } catch (e: Exception) {
            // Executor shut down while finishing; nothing to do.
        }
    }

    /**
     * Rebuild Favorites + Apps. Keeps focus on the same app when possible, or on
     * [focusPkg] (in Favorites if [focusInFav]) when given.
     */
    private fun renderApps(focusPkg: String? = null, focusInFav: Boolean = false) {
        rendered = true
        val focused = currentFocus
        val focusInApps = focused != null && (focused.parent === favRow || focused.parent === grid)
        val keepPkg = focusPkg ?: if (focusInApps) focused?.tag as? String else null
        val keepInFav = if (focusPkg != null) focusInFav else focused?.parent === favRow

        favRow.removeAllViews()
        grid.removeAllViews()

        val favorites = store.favorites().mapNotNull { apps[it] }
        favorites.forEach { favRow.addView(tile(it, inFav = true)) }
        favHint.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
        favScroll.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE

        val hidden = store.hidden()
        val showHidden = store.showHidden
        apps.values.filter { showHidden || it.pkg !in hidden }.forEach { app ->
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
            focusInApps || focused == null || !focused.isAttachedToWindow -> firstTile()?.requestFocus()
        }
    }

    private fun renderContinue() {
        cwRendered = true
        val focused = currentFocus
        val focusInCw = focused != null && focused.parent === cwRow
        val keepKey = if (focusInCw) focused?.tag as? String else null
        val keepIndex = if (focusInCw) cwRow.indexOfChild(focused) else -1

        cwRow.removeAllViews()

        when {
            !store.continueWatching || (cwAccess && cwItems.isEmpty()) -> {
                cwTitle.visibility = View.GONE
                cwHint.visibility = View.GONE
                cwScroll.visibility = View.GONE
            }
            !cwAccess -> {
                cwTitle.visibility = View.VISIBLE
                cwHint.text = "Needs one-time access: open Options → Turn on Continue Watching."
                cwHint.visibility = View.VISIBLE
                cwScroll.visibility = View.GONE
            }
            else -> {
                cwTitle.visibility = View.VISIBLE
                cwHint.visibility = View.GONE
                cwScroll.visibility = View.VISIBLE
                cwItems.forEach { cwRow.addView(cwCard(it)) }
            }
        }

        if (focusInCw) {
            val target = keepKey?.let { findTile(cwRow, it) }
                ?: cwRow.getChildAt(keepIndex.coerceAtMost(cwRow.childCount - 1).coerceAtLeast(0))
            (target ?: firstTile())?.requestFocus()
        } else if (focused == null || !focused.isAttachedToWindow) {
            firstTile()?.requestFocus()
        }
    }

    private fun findTile(parent: ViewGroup, tagValue: String): View? =
        (0 until parent.childCount).map { parent.getChildAt(it) }.firstOrNull { it.tag == tagValue }

    private fun firstTile(): View? = favRow.getChildAt(0) ?: cwRow.getChildAt(0) ?: grid.getChildAt(0)

    private fun goTop() {
        scroll.smoothScrollTo(0, 0)
        favScroll.smoothScrollTo(0, 0)
        cwScroll.smoothScrollTo(0, 0)
        firstTile()?.requestFocus()
    }

    // ---------------------------------------------------------------- cards

    /** A focusable rounded card that grows and gets a white ring when selected. */
    private fun focusCard(tagValue: String, onClick: () -> Unit, onMenu: () -> Unit): FrameLayout {
        val ring = rounded(Color.TRANSPARENT, dp(10), dp(3), Color.WHITE)
        return FrameLayout(this).apply {
            tag = tagValue
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = rounded(CARD, dp(10))
            clipToOutline = true

            setOnFocusChangeListener { v, has ->
                foreground = if (has) ring else null
                v.animate()
                    .scaleX(if (has) 1.08f else 1f)
                    .scaleY(if (has) 1.08f else 1f)
                    .translationZ(if (has) dp(8).toFloat() else 0f)
                    .setDuration(140)
                    .start()
            }
            setOnClickListener { onClick() }
            setOnLongClickListener { onMenu(); true }
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    onMenu(); true
                } else false
            }
        }
    }

    private fun tile(app: AppEntry, inFav: Boolean): View {
        val margin = dp(TILE_M)
        val card = focusCard(app.pkg, { launch(app) }, { showAppOptions(app, inFav) })
        card.contentDescription = app.label
        card.layoutParams = if (inFav) {
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

    private fun cwCard(item: WatchNextItem): View {
        val w = dp(CW_W)
        val h = dp(CW_H)
        val margin = dp(TILE_M)
        val app = apps[item.pkg]

        val card = focusCard(CW_PREFIX + item.key, { openWatchNext(item) }, { showWatchNextOptions(item) })
        card.contentDescription = "${item.title}, ${item.subtitle}"
        card.layoutParams = LinearLayout.LayoutParams(w, h).apply {
            setMargins(margin, margin, margin, margin)
        }

        // Placeholder: the app's own banner or icon, until artwork arrives.
        val banner = app?.banner
        if (banner != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(banner.copy())
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (app != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(app.icon.copy())
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER).apply { bottomMargin = dp(24) })
        }

        item.image?.let { uri ->
            val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            card.addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            Thumbs.into(art, uri, w)
        }

        card.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x00000000),
            )
        }, FrameLayout.LayoutParams(MATCH, dp(76), Gravity.BOTTOM))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        info.addView(label(13f, Color.WHITE).apply {
            text = item.title
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(label(11f, SUBTLE).apply {
            text = item.subtitle
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(info, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        if (item.progress > 0f) {
            card.addView(View(this).apply { setBackgroundColor(0x40FFFFFF) },
                FrameLayout.LayoutParams(MATCH, dp(3), Gravity.BOTTOM))
            val filled = (w * item.progress).toInt().coerceAtLeast(dp(4))
            card.addView(View(this).apply { setBackgroundColor(ACCENT) },
                FrameLayout.LayoutParams(filled, dp(3), Gravity.BOTTOM or Gravity.START))
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

    /** Resume playback using the link the app saved; fall back to opening the app. */
    private fun openWatchNext(item: WatchNextItem) {
        val intent = item.intentUri?.let { safeIntent(it, item.pkg) }
        if (intent != null) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // fall through
            }
        }
        val app = apps[item.pkg]
        if (app != null) launch(app) else Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show()
    }

    /** Only lets a Continue Watching link open the app that created it. */
    private fun safeIntent(uri: String, pkg: String): Intent? {
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return null
        }
        intent.selector = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        val target = intent.component?.packageName ?: intent.`package`
        if (target == null) intent.setPackage(pkg) else if (target != pkg) return null
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun showWatchNextOptions(item: WatchNextItem) {
        val app = apps[item.pkg]
        val labels = mutableListOf("Play")
        val actions = mutableListOf<() -> Unit>({ openWatchNext(item) })
        if (app != null) {
            labels += "Open ${app.label}"
            actions += { launch(app) }
        }
        labels += "Remove from Continue Watching"
        actions += {
            store.dismiss(item.key)
            cwItems = cwItems.filter { it.key != item.key }
            renderContinue()
        }
        AlertDialog.Builder(this, DIALOG)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showAppOptions(app: AppEntry, inFav: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun item(text: String, action: () -> Unit) { labels += text; actions += action }

        val isFav = store.isFavorite(app.pkg)
        val isHidden = app.pkg in store.hidden()

        if (isFav) {
            item("Move left") { store.moveFavorite(app.pkg, -1); renderApps(app.pkg, true) }
            item("Move right") { store.moveFavorite(app.pkg, 1); renderApps(app.pkg, true) }
            item("Remove from Favorites") { store.removeFavorite(app.pkg); renderApps(app.pkg, false) }
        } else {
            item("Pin to Favorites") { store.addFavorite(app.pkg); renderApps(app.pkg, inFav) }
        }
        item(if (isHidden) "Unhide" else "Hide from Apps") {
            store.setHidden(app.pkg, !isHidden)
            renderApps(app.pkg, inFav)
        }
        item("App info") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        item("Uninstall") {
            open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        }

        AlertDialog.Builder(this, DIALOG)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showLauncherOptions() {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun item(text: String, action: () -> Unit) { labels += text; actions += action }

        item("Background: ${currentScene().label.removeSuffix("…")}") { showWallpaperPicker() }
        if (store.continueWatching && !WatchNext.granted(this)) {
            item("Turn on Continue Watching") { requestTvAccess() }
        }
        item(if (store.continueWatching) "Hide Continue Watching" else "Show Continue Watching") {
            store.continueWatching = !store.continueWatching
            if (store.continueWatching && !WatchNext.granted(this)) requestTvAccess()
            cwRendered = false
            reload()
        }
        item(if (store.showHidden) "Stop showing hidden apps" else "Show hidden apps") {
            store.showHidden = !store.showHidden
            renderApps()
        }
        item("Android TV settings") { open(Intent(Settings.ACTION_SETTINGS)) }
        item("About") {
            AlertDialog.Builder(this, DIALOG)
                .setTitle("Homebase 1.1")
                .setMessage(
                    "A plain home screen: your apps, nothing else.\n\n" +
                        "No ads, no recommendations, no tracking. The only thing it ever " +
                        "downloads is the artwork your own apps list in Continue Watching."
                )
                .setPositiveButton("OK", null)
                .show()
        }

        AlertDialog.Builder(this, DIALOG)
            .setTitle("Homebase")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun requestTvAccess() {
        val missing = WatchNext.PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            reload()
        } else {
            requestPermissions(missing.toTypedArray(), REQ_TV)
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private fun systemWallpaperSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER) &&
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).resolveActivity(packageManager) != null

    private fun currentScene(): Scene {
        val scene = Scene.from(store.wallpaper)
        return if (scene == Scene.SYSTEM && !systemWallpaperSupported()) Scene.AURORA else scene
    }

    private fun applyWallpaper() {
        val scene = currentScene()
        if (scene == Scene.SYSTEM) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            wallpaper.visibility = View.GONE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(LiveWallpaperView.BG_TOP))
            wallpaper.visibility = View.VISIBLE
        }
        wallpaper.scene = scene
    }

    private fun showWallpaperPicker() {
        val scenes = Scene.values().filter { it != Scene.SYSTEM || systemWallpaperSupported() }
        val current = scenes.indexOf(currentScene())
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Background")
            .setSingleChoiceItems(scenes.map { it.label }.toTypedArray(), current) { dialog, which ->
                val picked = scenes[which]
                store.wallpaper = picked.name
                applyWallpaper()
                dialog.dismiss()
                if (picked == Scene.SYSTEM) open(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
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
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }

    private fun pill(title: String, onClick: () -> Unit): TextView = label(15f, Color.WHITE).apply {
        text = title
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
        const val CW_W = 240
        const val CW_H = 135
        const val CW_PREFIX = "cw:"
        const val REQ_TV = 7
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert

        val GRANT_FLAGS =Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

        val CARD = 0xE61C232D.toInt()
        val PILL = 0x33FFFFFF
        val MUTED = 0xFF9AA4B5.toInt()
        val SUBTLE = 0xFFCBD5E1.toInt()
        val ACCENT = 0xFF4ADE80.toInt()
    }
}
