package com.lagradost.cloudstream3.ui.kumo.catalog

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

/**
 * Local-only addon URL storage for the KUMO catalog browser.
 * Additive DataStore keys — no existing schema touched, no Supabase.
 */
object StremioCatalogStore {
    private const val KEY_ADDONS = "kumo_catalog_addons"

    fun getAddons(context: Context): List<String> {
        return try {
            context.getKey<List<String>>(KEY_ADDONS).orEmpty()
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotBlank() }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addAddon(context: Context, rawUrl: String): Boolean {
        val url = rawUrl.trim().trimEnd('/')
        if (url.isBlank() || !url.startsWith("http")) return false
        return try {
            val current = getAddons(context).toMutableList()
            if (current.any { it.equals(url, ignoreCase = true) }) return true
            current.add(url)
            context.setKey(KEY_ADDONS, current)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun removeAddon(context: Context, url: String): Boolean {
        return try {
            val current = getAddons(context).toMutableList()
            val removed = current.removeAll { it.equals(url.trim(), ignoreCase = true) }
            if (removed) context.setKey(KEY_ADDONS, current)
            removed
        } catch (_: Exception) {
            false
        }
    }
}
