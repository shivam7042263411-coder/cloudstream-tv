package com.lagradost.cloudstream3.ui.kumo.catalog

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentKumoCatalogBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Nuvio-style catalog browser for KUMO phone.
 *
 * BROWSE WITH NUVIO-PROTOCOL → PLAY WITH CLOUDSTREAM:
 * Stremio addon → catalog rows (MetaPreview shapes) → tap card →
 * title search on installed AnimePahe → provider's own SearchResponse →
 * existing loadSearchResult → KumoDetails → RepoLinkGenerator → Kwik → player.
 *
 * The catalog never streams and never fabricates provider URLs.
 * Addon URLs are stored locally (DataStore, additive keys, no Supabase).
 */
class KumoCatalogFragment : BaseFragment<FragmentKumoCatalogBinding>(
    BindingCreator.Inflate(FragmentKumoCatalogBinding::inflate)
) {
    private var rowAdapter: KumoCatalogRowAdapter? = null
    private var rows: List<StremioRow> = emptyList()
    private val rowsMutex = Mutex()
    private var resolving = false

    override fun pickLayout(): Int = R.layout.fragment_kumo_catalog

    override fun fixLayout(view: View) {
    }

    override fun onBindingCreated(binding: FragmentKumoCatalogBinding, savedInstanceState: Bundle?) {
        binding.apply {
            kumoCatalogBack.setOnClickListener {
                dispatchBackPressed()
            }
            kumoCatalogAdd.setOnClickListener {
                showAddAddonDialog()
            }
            kumoCatalogEmptyAdd.setOnClickListener {
                showAddAddonDialog()
            }
            kumoCatalogRows.layoutManager = LinearLayoutManager(context)
            rowAdapter = KumoCatalogRowAdapter(
                onCardClick = { meta -> onCardClick(meta) },
                onLoadMore = { row -> onLoadMore(row) },
                onTitleLongClick = { row -> onRowRemove(row) },
            )
            kumoCatalogRows.adapter = rowAdapter
        }
        reload()
    }

    private fun setLoading(loading: Boolean) {
        binding?.apply {
            kumoCatalogLoading.isVisible = loading && rows.isEmpty()
            kumoCatalogEmpty.isGone = loading || rows.isNotEmpty()
            kumoCatalogRows.isGone = loading && rows.isEmpty()
        }
    }

    private fun publish() {
        val current = rows
        binding?.apply {
            rowAdapter?.submitList(current.toList())
            kumoCatalogRows.isVisible = current.isNotEmpty()
            kumoCatalogEmpty.isGone = current.isNotEmpty()
            kumoCatalogLoading.isGone = true
        }
    }

    fun reload() {
        val ctx = context ?: return
        setLoading(true)
        lifecycleScope.launch {
            val urls = withContext(Dispatchers.IO) {
                StremioCatalogStore.getAddons(ctx)
            }
            if (urls.isEmpty()) {
                rowsMutex.withLock { rows = emptyList() }
                setLoading(false)
                publish()
                return@launch
            }
            // Manifests and first pages fetch concurrently so one slow
            // host never head-blocks the whole screen.
            val fresh = withContext(Dispatchers.IO) {
                val defs = urls.map { url ->
                    async {
                        try {
                            StremioCatalogApi.fetchDefinitions(url)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
                defs.map { def ->
                    async {
                        val page = try {
                            StremioCatalogApi.fetchPage(def)
                        } catch (_: Exception) {
                            StremioCatalogApi.CatalogPage(emptyList(), 0, null)
                        }
                        StremioRow(def, page.items, page.nextSkip)
                    }
                }.awaitAll().filter { it.items.isNotEmpty() }
            }
            rowsMutex.withLock { rows = fresh }
            setLoading(false)
            publish()
        }
    }

    private fun onLoadMore(row: StremioRow) {
        if (!row.canLoadMore) return
        lifecycleScope.launch {
            val skip = row.nextSkip ?: return@launch
            val page = withContext(Dispatchers.IO) {
                try {
                    StremioCatalogApi.fetchPage(row.def, skip = skip)
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            rowsMutex.withLock {
                rows = rows.map { current ->
                    if (current.key == row.key) {
                        current.copy(
                            items = StremioCatalogApi.mergeItems(current.items, page.items),
                            nextSkip = page.nextSkip,
                        )
                    } else {
                        current
                    }
                }
            }
            publish()
        }
    }

    private fun onCardClick(meta: StremioMeta) {
        if (resolving) return
        resolving = true
        binding?.kumoCatalogResolving?.isVisible = true
        lifecycleScope.launch {
            val result = KumoCatalogResolve.resolveAndOpen(activity as? FragmentActivity, meta)
            resolving = false
            binding?.kumoCatalogResolving?.isGone = true
            val ctx = context
            when (result) {
                is KumoCatalogResolve.ResolveResult.Opened -> Unit
                is KumoCatalogResolve.ResolveResult.ProviderMissing -> {
                    showToast(R.string.no_provider_found, Toast.LENGTH_LONG)
                }
                is KumoCatalogResolve.ResolveResult.NotFound -> {
                    if (ctx != null) {
                        showToast(ctx.getString(R.string.no_results_found, meta.name), Toast.LENGTH_LONG)
                    }
                }
                is KumoCatalogResolve.ResolveResult.Failed -> {
                    showToast(R.string.error, Toast.LENGTH_SHORT)
                }
            }
            Unit
        }
    }

    private fun onRowRemove(row: StremioRow) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(row.def.name)
            .setMessage(ctx.getString(R.string.remove_addon_confirm, row.def.addonName))
            .setPositiveButton(R.string.yes) { _, _ ->
                ioSafe {
                    StremioCatalogStore.removeAddon(ctx, row.def.manifestUrl)
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddAddonDialog() {
        val ctx = context ?: return
        KumoCatalogAddonDialog.show(ctx) { reload() }
    }
}
