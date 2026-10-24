package com.tvkit.home

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import org.json.JSONArray
import java.io.File

/** One launchable app. The icon is loaded lazily so building the app list stays fast. */
class AppEntry(
    val label: String,
    val pkg: String,
    val launch: Intent,
    iconLoader: () -> Drawable
) {
    val icon: Drawable by lazy(iconLoader)
}

object AppResolver {

    /** Every launchable app, TV-optimised (leanback) entries preferred, sorted by name. */
    fun allApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val byPkg = LinkedHashMap<String, AppEntry>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || byPkg.containsKey(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                byPkg[pkg] = AppEntry(ri.loadLabel(pm).toString(), pkg, launch) { ri.loadIcon(pm) }
            }
        }
        return byPkg.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Resolve the dock favourites. Each favourite lists words to look for in the package name or
     * app label, tried in order; an app can only fill one slot. Missing apps are skipped.
     * Override the list by pushing favorites.json to
     * /sdcard/Android/data/com.tvkit.home/files/favorites.json
     */
    fun favorites(ctx: Context, apps: List<AppEntry>): List<AppEntry> {
        val json = readFavorites(ctx)
        val claimed = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            val label = o.optString("label")
            val needles = o.optJSONArray("match") ?: continue
            var found: AppEntry? = null
            search@ for (n in 0 until needles.length()) {
                val needle = needles.getString(n).lowercase()
                for (a in apps) {
                    if (claimed.contains(a.pkg)) continue
                    if (a.pkg.lowercase().contains(needle) || a.label.lowercase().contains(needle)) {
                        found = a
                        break@search
                    }
                }
            }
            val hit = found ?: continue
            claimed.add(hit.pkg)
            out.add(AppEntry(if (label.isEmpty()) hit.label else label, hit.pkg, hit.launch) { hit.icon })
        }
        return out
    }

    private fun readFavorites(ctx: Context): JSONArray {
        try {
            val override = File(ctx.getExternalFilesDir(null), "favorites.json")
            if (override.isFile) return JSONArray(override.readText())
        } catch (e: Exception) {
            // fall through to the bundled list
        }
        return try {
            JSONArray(ctx.assets.open("favorites.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            JSONArray()
        }
    }
}
