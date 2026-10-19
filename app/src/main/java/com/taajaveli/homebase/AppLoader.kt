package com.taajaveli.homebase

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    val leanback: Boolean,
    val banner: Drawable?,
    val icon: Drawable,
)

object AppLoader {

    /**
     * Every launchable app, one entry per package, sorted A–Z.
     * TV (leanback) entries win; phone-style apps you sideload are included too.
     */
    fun load(pm: PackageManager, self: String): Map<String, AppEntry> {
        val found = LinkedHashMap<String, AppEntry>()

        fun scan(category: String, leanback: Boolean) {
            val results = try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in results) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == self || found.containsKey(ai.packageName)) continue
                val banner = if (leanback) {
                    try { ai.loadBanner(pm) } catch (e: Exception) { null }
                } else null
                found[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    component = ComponentName(ai.packageName, ai.name),
                    label = ri.loadLabel(pm).toString().trim(),
                    leanback = leanback,
                    banner = banner,
                    icon = ri.loadIcon(pm),
                )
            }
        }

        scan(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        scan(Intent.CATEGORY_LAUNCHER, false)

        return found.values.sortedBy { it.label.lowercase() }.associateBy { it.pkg }
    }
}
