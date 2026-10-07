package com.lagradost.cloudstream3.ui.kumo.catalog

import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge: Nuvio-style catalog item → existing CloudStream provider flow.
 *
 * NEVER passes Stremio/IMDb IDs to the provider (AnimePahe cannot use them).
 * Instead performs a title search on the installed AnimePahe provider and
 * hands the provider's OWN SearchResponse to the existing
 * loadSearchResult() entry — from there the stock pipeline
 * (details → LoadResponse → RepoLinkGenerator → Kwik → player) runs untouched.
 */
object KumoCatalogResolve {
    const val ANIME_PROVIDER_NAME = "AnimePahe"
    private const val TAG = "KumoCatalogResolve"

    sealed interface ResolveResult {
        data object Opened : ResolveResult
        data object ProviderMissing : ResolveResult
        data object NotFound : ResolveResult
        data object Failed : ResolveResult
    }

    suspend fun resolveAndOpen(
        activity: FragmentActivity?,
        meta: StremioMeta,
    ): ResolveResult = withContext(Dispatchers.IO) {
        val act = activity ?: return@withContext ResolveResult.Failed
        android.util.Log.d(TAG, "resolve start: title=\"${meta.name}\" year=${meta.year} type=${meta.type}")
        val api = getApiFromNameNull(ANIME_PROVIDER_NAME)
        if (api == null) {
            android.util.Log.w(TAG, "resolve: provider $ANIME_PROVIDER_NAME missing")
            return@withContext ResolveResult.ProviderMissing
        }
        val isMovie = meta.type.equals("movie", ignoreCase = true)
        val titles = listOf(meta.name)
        android.util.Log.d(TAG, "resolve: searching $ANIME_PROVIDER_NAME for \"${meta.name}\"")
        val results = try {
            api.search(meta.name).orEmpty()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "resolve: provider search threw ${e.javaClass.simpleName}: ${e.message}")
            return@withContext ResolveResult.Failed
        }
        android.util.Log.d(TAG, "resolve: provider returned ${results.size} results")
        if (results.isEmpty()) return@withContext ResolveResult.NotFound
        val best = StremioMatcher.findBestMatch(results, titles, meta.year, isMovie)
        if (best == null) {
            android.util.Log.w(TAG, "resolve: no match above threshold for \"${meta.name}\"")
            return@withContext ResolveResult.NotFound
        }
        android.util.Log.d(TAG, "resolve: best match \"${best.name}\" url=${best.url} -> opening details")
        withContext(Dispatchers.Main) {
            act.loadSearchResult(best)
        }
        android.util.Log.d(TAG, "resolve: opened details, provider takes over (load/links/player)")
        ResolveResult.Opened
    }
}
