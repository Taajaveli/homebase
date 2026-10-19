package com.taajaveli.homebase

import android.content.Context

/** Favorites order, hidden apps and settings, kept on the box only. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("homebase", Context.MODE_PRIVATE)

    fun favorites(): List<String> =
        (prefs.getString(KEY_FAVS, "") ?: "").split(',').filter { it.isNotBlank() }

    private fun saveFavorites(list: List<String>) {
        prefs.edit().putString(KEY_FAVS, list.joinToString(",")).apply()
    }

    fun isFavorite(pkg: String) = pkg in favorites()

    fun addFavorite(pkg: String) {
        val list = favorites()
        if (pkg !in list) saveFavorites(list + pkg)
    }

    fun removeFavorite(pkg: String) {
        saveFavorites(favorites() - pkg)
    }

    fun moveFavorite(pkg: String, by: Int) {
        val list = favorites().toMutableList()
        val from = list.indexOf(pkg)
        val to = from + by
        if (from < 0 || to !in list.indices) return
        list.removeAt(from)
        list.add(to, pkg)
        saveFavorites(list)
    }

    fun hidden(): Set<String> = (prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()).toSet()

    fun setHidden(pkg: String, hide: Boolean) {
        val set = hidden().toMutableSet()
        if (hide) set += pkg else set -= pkg
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    var showHidden: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        }

    var wallpaper: String
        get() = prefs.getString(KEY_WALLPAPER, "AURORA") ?: "AURORA"
        set(value) {
            prefs.edit().putString(KEY_WALLPAPER, value).apply()
        }

    var continueWatching: Boolean
        get() = prefs.getBoolean(KEY_CW, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CW, value).apply()
        }

    var askedTvAccess: Boolean
        get() = prefs.getBoolean(KEY_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ASKED, value).apply()
        }

    fun dismissed(): Set<String> = (prefs.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()).toSet()

    fun dismiss(key: String) {
        // Keep the list from growing forever.
        val set = dismissed().toMutableList().apply { add(key) }.takeLast(300).toSet()
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply()
    }

    private companion object {
        const val KEY_FAVS = "favorites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_SHOW_HIDDEN = "showHidden"
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_CW = "continueWatching"
        const val KEY_ASKED = "askedTvAccess"
        const val KEY_DISMISSED = "dismissedWatchNext"
    }
}
