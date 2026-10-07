package com.lagradost.cloudstream3.ui.kumo.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Nuvio-pattern Stremio catalog transport, adapted to KUMO Android.
 * Speaks the public Stremio addon protocol against USER-INSTALLED addon URLs:
 *   GET {base}/manifest.json
 *   GET {base}/catalog/{type}/{id}.json
 *   GET {base}/catalog/{type}/{id}/skip={n}.json
 *   GET {base}/catalog/{type}/{id}/search={q}.json
 *   GET {base}/catalog/{type}/{id}/genre={g}&skip={n}.json
 * No Nuvio backend, no Supabase, no TMDB. Pure OkHttp + org.json.
 */
object StremioCatalogApi {
    const val CATALOG_PAGE_SIZE = 100
    private const val DUPLICATE_PAGE_LIMIT = 3

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class CatalogPage(
        val items: List<StremioMeta>,
        val rawItemCount: Int,
        val nextSkip: Int?,
    )

    fun transportBase(manifestUrl: String): String {
        var base = manifestUrl.trim()
        if (base.endsWith("/manifest.json", ignoreCase = true)) {
            base = base.dropLast("/manifest.json".length)
        }
        return base.trimEnd('/')
    }

    private fun encodeSegment(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    fun buildCatalogUrl(
        manifestUrl: String,
        type: String,
        catalogId: String,
        genre: String? = null,
        search: String? = null,
        skip: Int? = null,
    ): String {
        val base = transportBase(manifestUrl)
        val extras = mutableListOf<String>()
        if (!search.isNullOrBlank()) extras.add("search=${encodeSegment(search)}")
        if (!genre.isNullOrBlank()) extras.add("genre=${encodeSegment(genre)}")
        if (skip != null && skip > 0) extras.add("skip=$skip")
        val id = encodeSegment(catalogId)
        return if (extras.isEmpty()) {
            "$base/catalog/$type/$id.json"
        } else {
            "$base/catalog/$type/$id/${extras.joinToString("&")}.json"
        }
    }

    private fun httpGetText(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "KUMO/1.0")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Fetch + parse one addon manifest into catalog definitions. */
    fun fetchDefinitions(manifestUrl: String): List<StremioCatalogDef> {
        val base = transportBase(manifestUrl)
        val payload = httpGetText("$base/manifest.json") ?: return emptyList()
        return try {
            val root = JSONObject(payload)
            val addonName = root.optString("name", base)
            val catalogs = root.optJSONArray("catalogs") ?: JSONArray()
            List(catalogs.length()) { i ->
                val c = catalogs.optJSONObject(i) ?: return@List null
                val extra = c.optJSONArray("extra") ?: JSONArray()
                var skip = false
                var search = false
                var genre = false
                val genreOptions = mutableListOf<String>()
                for (j in 0 until extra.length()) {
                    val e = extra.optJSONObject(j) ?: continue
                    when (e.optString("name").lowercase()) {
                        "skip" -> skip = true
                        "search" -> if (!e.optBoolean("isRequired", false)) search = true
                        "genre" -> {
                            genre = true
                            val opts = e.optJSONArray("options")
                            if (opts != null) for (k in 0 until opts.length()) {
                                opts.optString(k)?.takeIf { it.isNotBlank() }?.let { genreOptions.add(it) }
                            }
                        }
                    }
                }
                // Search-only catalogs (search required) are not browsable rows.
                val searchRequired = (0 until extra.length()).any { j ->
                    val e = extra.optJSONObject(j) ?: return@any false
                    e.optString("name").equals("search", ignoreCase = true) &&
                        e.optBoolean("isRequired", false)
                }
                if (searchRequired) return@List null
                StremioCatalogDef(
                    manifestUrl = manifestUrl,
                    addonName = addonName,
                    type = c.optString("type"),
                    id = c.optString("id"),
                    name = c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("id"),
                    supportsSkip = skip,
                    supportsSearch = search,
                    supportsGenre = genre,
                    genreOptions = genreOptions,
                )
            }.mapNotNull { it }.filter { it.type.isNotBlank() && it.id.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseYear(releaseInfo: String?): Int? {
        if (releaseInfo.isNullOrBlank()) return null
        return Regex("\\b(19|20)\\d{2}\\b").find(releaseInfo)?.value?.toIntOrNull()
    }

    fun parseMetas(payload: String, fallbackType: String): List<StremioMeta> {
        return try {
            val root = JSONObject(payload)
            val metas = root.optJSONArray("metas") ?: JSONArray()
            val out = ArrayList<StremioMeta>(metas.length())
            val seen = HashSet<String>()
            for (i in 0 until metas.length()) {
                val m = metas.optJSONObject(i) ?: continue
                val id = m.optString("id").trim()
                val type = m.optString("type").trim().ifBlank { fallbackType }
                val name = m.optString("name").trim()
                if (id.isBlank() || name.isBlank() || type.isBlank()) continue
                val key = "$type:$id"
                if (!seen.add(key)) continue
                val genres = mutableListOf<String>()
                val g = m.optJSONArray("genres")
                if (g != null) for (j in 0 until g.length()) {
                    g.optString(j)?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
                }
                val releaseInfo = m.optString("releaseInfo").trim().ifBlank { null }
                out.add(
                    StremioMeta(
                        id = id,
                        type = type,
                        name = name,
                        poster = m.optString("poster").trim().ifBlank { null },
                        background = (m.optString("background").trim().ifBlank { null }
                            ?: m.optString("banner").trim().ifBlank { null }),
                        logo = m.optString("logo").trim().ifBlank { null },
                        description = m.optString("description").trim().ifBlank { null },
                        releaseInfo = releaseInfo,
                        year = parseYear(releaseInfo),
                        genres = genres,
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Fetch one page; computes the next skip cursor (server-driven). */
    fun fetchPage(
        def: StremioCatalogDef,
        genre: String? = null,
        search: String? = null,
        skip: Int? = null,
    ): CatalogPage {
        val url = buildCatalogUrl(def.manifestUrl, def.type, def.id, genre, search, skip)
        val payload = httpGetText(url) ?: return CatalogPage(emptyList(), 0, null)
        val items = parseMetas(payload, def.type)
        val raw = try {
            JSONObject(payload).optJSONArray("metas")?.length() ?: items.size
        } catch (_: Exception) {
            items.size
        }
        val next = if (!def.supportsSkip || raw == 0) {
            null
        } else {
            (skip ?: 0) + raw
        }
        return CatalogPage(items, raw, next)
    }

    /** Merge incoming items into existing, deduplicating by stable key. */
    fun mergeItems(existing: List<StremioMeta>, incoming: List<StremioMeta>): List<StremioMeta> {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        val seen = HashSet<String>(existing.size + incoming.size)
        val out = ArrayList<StremioMeta>(existing.size + incoming.size)
        for (item in existing + incoming) {
            if (seen.add(item.stableKey())) out.add(item)
        }
        return out
    }
}
