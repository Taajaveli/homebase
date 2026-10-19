package com.taajaveli.homebase

import android.content.Context
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri

data class WatchNextItem(
    val key: String,
    val pkg: String,
    val title: String,
    val subtitle: String,
    val image: Uri?,
    val progress: Float,
    val intentUri: String?,
    val lastEngaged: Long,
)

class WatchNextResult(val items: List<WatchNextItem>, val hasAccess: Boolean)

/**
 * Reads Android TV's system "Watch Next" list — the same list apps fill so the
 * stock Google TV home can show Continue Watching.
 *
 * Other apps' rows are only visible with READ_TV_LISTINGS, which the box can
 * grant at runtime or `adb shell pm grant` can grant (tools/debloat.sh does it).
 */
object WatchNext {
    const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
    const val READ_EPG_DATA = "com.android.providers.tv.permission.READ_EPG_DATA"
    val PERMISSIONS = arrayOf(READ_TV_LISTINGS, READ_EPG_DATA)

    private const val MAX_ITEMS = 20

    // Plain column names (stable since Android 8) so nothing depends on SDK constants.
    private val PROJECTION = arrayOf(
        "_id",
        "package_name",
        "title",
        "episode_title",
        "season_display_number",
        "episode_display_number",
        "thumbnail_uri",
        "poster_art_uri",
        "last_playback_position_millis",
        "duration_millis",
        "intent_uri",
        "last_engagement_time_utc_millis",
        "browsable",
        "watch_next_type",
    )

    fun granted(ctx: Context): Boolean =
        ctx.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    /** [labels] maps installed package → app name; rows from other packages are dropped. */
    fun load(ctx: Context, labels: Map<String, String>, dismissed: Set<String>): WatchNextResult {
        if (!granted(ctx)) return WatchNextResult(emptyList(), false)

        val cursor = try {
            ctx.contentResolver.query(
                TvContract.WatchNextPrograms.CONTENT_URI, PROJECTION, null, null, null
            )
        } catch (e: SecurityException) {
            return WatchNextResult(emptyList(), false)
        } catch (e: Exception) {
            null
        } ?: return WatchNextResult(emptyList(), true)

        val out = ArrayList<WatchNextItem>()
        cursor.use { c ->
            fun str(col: String): String? {
                val i = c.getColumnIndex(col)
                return if (i < 0 || c.isNull(i)) null else c.getString(i)?.trim()?.ifEmpty { null }
            }
            fun num(col: String): Long {
                val i = c.getColumnIndex(col)
                return if (i < 0 || c.isNull(i)) 0L else c.getLong(i)
            }

            while (c.moveToNext()) {
                val pkg = str("package_name") ?: continue
                val appName = labels[pkg] ?: continue
                val key = "$pkg:${num("_id")}"
                if (key in dismissed) continue

                val bi = c.getColumnIndex("browsable")
                if (bi >= 0 && !c.isNull(bi) && c.getInt(bi) == 0) continue

                val season = str("season_display_number")
                val episode = str("episode_display_number")
                val epTitle = str("episode_title")
                val kind = when (num("watch_next_type").toInt()) {
                    1 -> "Up next"
                    2 -> "New"
                    3 -> "Watchlist"
                    else -> null
                }
                val se = when {
                    season != null && episode != null -> "S$season E$episode"
                    episode != null -> "E$episode"
                    else -> null
                }
                val subtitle = listOfNotNull(kind, se, epTitle).joinToString(" · ").ifEmpty { appName }

                val pos = num("last_playback_position_millis")
                val dur = num("duration_millis")
                val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

                val image = (str("thumbnail_uri") ?: str("poster_art_uri"))?.let {
                    try { Uri.parse(it) } catch (e: Exception) { null }
                }

                out += WatchNextItem(
                    key = key,
                    pkg = pkg,
                    title = str("title") ?: appName,
                    subtitle = subtitle,
                    image = image,
                    progress = progress,
                    intentUri = str("intent_uri"),
                    lastEngaged = num("last_engagement_time_utc_millis"),
                )
            }
        }

        val items = out
            .sortedByDescending { it.lastEngaged }
            .distinctBy { it.key }
            .take(MAX_ITEMS)
        return WatchNextResult(items, true)
    }
}
