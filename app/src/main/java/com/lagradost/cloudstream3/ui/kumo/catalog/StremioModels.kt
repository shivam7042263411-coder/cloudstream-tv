package com.lagradost.cloudstream3.ui.kumo.catalog

/**
 * Nuvio-pattern catalog models, adapted to KUMO/CloudStream.
 * Pure data holders — no network, no Android dependencies.
 * Mirrors Nuvio's AddonCatalog / MetaPreview / HomeCatalogSection shapes
 * without importing Nuvio/KMP code.
 */

/** One catalog entry from an addon manifest.json (type/id/name + extra caps). */
data class StremioCatalogDef(
    val manifestUrl: String,
    val addonName: String,
    val type: String,
    val id: String,
    val name: String,
    val supportsSkip: Boolean = false,
    val supportsSearch: Boolean = false,
    val supportsGenre: Boolean = false,
    val genreOptions: List<String> = emptyList(),
)

/** One browsable item from a /catalog response metas[] entry. */
data class StremioMeta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
) {
    fun stableKey(): String = "$type:$id"
}

/** One home/browse row: definition + current items + pagination cursor. */
data class StremioRow(
    val def: StremioCatalogDef,
    val items: List<StremioMeta> = emptyList(),
    val nextSkip: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = nextSkip != null && !isLoading
    val key: String get() = "${def.manifestUrl}|${def.type}|${def.id}"
}
