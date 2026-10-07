package com.lagradost.cloudstream3.ui.home

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentKumoHomeBinding
import com.lagradost.cloudstream3.ui.kumo.catalog.KumoCatalogResolve
import com.lagradost.cloudstream3.ui.kumo.catalog.KumoCatalogRowAdapter
import com.lagradost.cloudstream3.ui.kumo.catalog.StremioCatalogApi
import com.lagradost.cloudstream3.ui.kumo.catalog.StremioCatalogStore
import com.lagradost.cloudstream3.ui.kumo.catalog.StremioMeta
import com.lagradost.cloudstream3.ui.kumo.catalog.StremioRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate

class KumoHomeFragment : BaseFragment<FragmentKumoHomeBinding>(
    BindingCreator.Bind(FragmentKumoHomeBinding::bind)
) {
    private val homeViewModel: HomeViewModel by activityViewModels()
    private val accountViewModel: AccountViewModel by activityViewModels()

    private var currentApiName: String? = null
    private var bottomSheetDialog: BottomSheetDialog? = null
    private var kumoAdapter: KumoHomeAdapter? = null
    private var lastSavedHomepage: String? = null

    private var metadataRowAdapter: KumoCatalogRowAdapter? = null
    private var metadataRows: List<StremioRow> = emptyList()
    private val metadataRowsMutex = Mutex()
    private var metadataResolving = false

    private var heroItems: List<com.lagradost.cloudstream3.SearchResponse> = emptyList()
    private var heroIndex: Int = 0
    private var metadataHeroItems: List<StremioMeta> = emptyList()
    private var metadataHeroIndex: Int = 0
    private var usingMetadataHero: Boolean = false
    private var metadataHeroActive: Boolean = false
    private val heroHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heroRunnable = object : Runnable {
        override fun run() {
            if (usingMetadataHero) {
                if (metadataHeroItems.size > 1) {
                    showMetadataHero((metadataHeroIndex + 1) % metadataHeroItems.size)
                }
            } else if (heroItems.size > 1) {
                showHero((heroIndex + 1) % heroItems.size)
            }
            heroHandler.postDelayed(this, 5000)
        }
    }

    /** Metadata catalog is the main homescreen whenever addon rows exist. */
    private fun hasMetadataHome(): Boolean = metadataRows.isNotEmpty()

    override fun pickLayout(): Int = R.layout.fragment_kumo_home

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        bottomSheetDialog?.let { dialog ->
            try { dialog.show() } catch (_: Throwable) {}
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    /** Nuvio-style centered hero: backdrop + title + type • year + dots. */
    private fun showHero(index: Int) {
        val binding = binding ?: return
        val item = heroItems.getOrNull(index) ?: return
        heroIndex = index
        binding.apply {
            kumoHeroContainer.setTag(R.id.kumo_hero_container, item)
            kumoHeroImage.loadImage(item.posterUrl, item.posterHeaders)
            kumoHeroTitle.text = item.name
            val typeLabel = when (item.type) {
                com.lagradost.cloudstream3.TvType.TvSeries -> "Series"
                com.lagradost.cloudstream3.TvType.Anime -> "Anime"
                com.lagradost.cloudstream3.TvType.Movie,
                com.lagradost.cloudstream3.TvType.AnimeMovie -> "Movie"
                com.lagradost.cloudstream3.TvType.OVA -> "OVA"
                else -> item.type?.name
            }
            val year = (item as? com.lagradost.cloudstream3.AnimeSearchResponse)?.year?.toString()
            val meta = listOfNotNull(
                typeLabel?.takeIf { it.isNotBlank() },
                year?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            kumoHeroMeta.text = meta
            kumoHeroMeta.isGone = meta.isBlank()
            renderHeroDots(heroItems.size, index)
        }
    }

    private fun renderHeroDots(count: Int, selected: Int) {
        val binding = binding ?: return
        binding.apply {
            kumoHeroDots.removeAllViews()
            if (count > 1) {
                kumoHeroDots.isVisible = true
                val density = resources.displayMetrics.density
                repeat(count) { i ->
                    val dot = android.widget.ImageView(context).apply {
                        setImageResource(
                            if (i == selected) R.drawable.kumo_hero_dot_active
                            else R.drawable.kumo_hero_dot
                        )
                    }
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.marginStart = (4 * density).toInt()
                    params.marginEnd = (4 * density).toInt()
                    kumoHeroDots.addView(dot, params)
                }
            } else {
                kumoHeroDots.isGone = true
            }
        }
    }

    /** Metadata hero: same centered treatment, driven by a catalog item. */
    private fun showMetadataHero(index: Int) {
        val binding = binding ?: return
        val item = metadataHeroItems.getOrNull(index) ?: return
        metadataHeroIndex = index
        usingMetadataHero = true
        binding.apply {
            kumoHeroContainer.setTag(R.id.kumo_hero_container, item)
            kumoHeroImage.loadImage(item.poster ?: item.background)
            kumoHeroTitle.text = item.name
            val meta = listOfNotNull(
                item.type.takeIf { it.isNotBlank() }?.replaceFirstChar { c -> c.uppercase() },
                item.year?.toString()
            ).joinToString(" • ")
            kumoHeroMeta.text = meta
            kumoHeroMeta.isGone = meta.isBlank()
            renderHeroDots(metadataHeroItems.size, index)
        }
    }

    private fun startHeroRotation() {
        heroHandler.removeCallbacks(heroRunnable)
        val count = if (usingMetadataHero) metadataHeroItems.size else heroItems.size
        if (count > 1) {
            heroHandler.postDelayed(heroRunnable, 5000)
        }
    }

    private fun stopHeroRotation() {
        heroHandler.removeCallbacks(heroRunnable)
    }

    override fun onDestroyView() {
        stopHeroRotation()
        (activity as? ComponentActivity)?.detachBackPressedCallback("KumoHomeFragment_BackPress")
        bottomSheetDialog?.dismissSafe()
        // Restore CloudStream's default bottom nav for other tabs (phone)
        if (isLayout(com.lagradost.cloudstream3.ui.settings.Globals.PHONE)) {
            activity?.findViewById<View>(R.id.nav_view)?.isGone = false
        }
        super.onDestroyView()
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view, padTop = false, padBottom = false, padLeft = isLayout(TV))
        HomeFragment.configEvent.invoke()
    }

    private fun getDisplayName(apiName: String?): String {
        val ctx = context ?: return apiName ?: ""
        return when (apiName) {
            com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi.name -> ctx.getString(R.string.none)
            com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi.name -> ctx.getString(R.string.home_random)
            else -> apiName ?: ""
        }
    }

    private fun showProviderPicker() {
        val ctx = context ?: return
        val validAPIs = ctx.filterProviderByPreferredMedia().toMutableList()
        validAPIs.add(0, com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi)
        validAPIs.add(0, com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi)

        val names = validAPIs.map { getDisplayName(it.name) }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.home_source)
            .setItems(names) { _, which ->
                val api = validAPIs[which]
                homeViewModel.loadAndCancel(api.name, forceReload = true, fromUI = true)
            }
            .show()
            .setDefaultFocus()
    }

    override fun onBindingCreated(binding: FragmentKumoHomeBinding) {
        context?.let { HomeChildItemAdapter.updatePosterSize(it) }

        (activity as? ComponentActivity)?.attachBackPressedCallback("KumoHomeFragment_BackPress") {
            handleTvBackPress(this)
        }

        val apiChangeClickListener = View.OnClickListener {
            showProviderPicker()
        }

        // Premium KUMO header — still uses real HomeViewModel provider
        binding.apply {
            kumoChangeApi.setOnClickListener(apiChangeClickListener)
            kumoChangeApi.setOnLongClickListener {
                if (currentApiName == com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi.name) return@setOnLongClickListener false
                homeViewModel.loadAndCancel(currentApiName, forceReload = true, fromUI = true)
                com.lagradost.cloudstream3.CommonActivity.showToast(R.string.action_reload, Toast.LENGTH_SHORT)
                true
            }
            kumoAccountButton.setOnClickListener {
                activity?.showAccountSelectLinear()
            }
            kumoSearchButton.setOnClickListener {
                // KUMO header search → existing CloudStream search destination
                activity?.navigate(R.id.navigation_search)
            }
            kumoBrowseButton.setOnClickListener {
                // KUMO header browse → Nuvio-style catalog browser (Stremio rows)
                activity?.navigate(R.id.action_navigation_home_to_navigation_kumo_catalog)
            }
            // Hero container click → metadata resolve when catalog is home, else details.
            kumoHeroContainer.setOnClickListener { v ->
                if (usingMetadataHero) {
                    metadataHeroItems.getOrNull(metadataHeroIndex)?.let { onMetadataCardClick(it) }
                    return@setOnClickListener
                }
                val firstItem = kumoHeroContainer.getTag(R.id.kumo_hero_container) as? com.lagradost.cloudstream3.SearchResponse
                if (firstItem != null) {
                    handleSearchClickCallback(
                        com.lagradost.cloudstream3.ui.search.SearchClickCallback(
                            com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD,
                            v,
                            -1,
                            firstItem
                        )
                    )
                }
            }

            // Hero View Details — metadata resolve when catalog is home, else details.
            // (My List lives on the details page, matching the reference design.)
            kumoHeroPlay.setOnClickListener { v ->
                if (usingMetadataHero) {
                    metadataHeroItems.getOrNull(metadataHeroIndex)?.let { onMetadataCardClick(it) }
                    return@setOnClickListener
                }
                val heroItem = heroItems.getOrNull(heroIndex)
                    ?: kumoHeroContainer.getTag(R.id.kumo_hero_container) as? com.lagradost.cloudstream3.SearchResponse
                if (heroItem != null) {
                    handleSearchClickCallback(
                        com.lagradost.cloudstream3.ui.search.SearchClickCallback(
                            com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD,
                            v,
                            -1,
                            heroItem
                        )
                    )
                }
            }

            kumoAdapter = KumoHomeAdapter(
                clickCallback = { callback -> handleSearchClickCallback(callback) },
                moreInfoCallback = { expandable -> homeViewModel.popup(expandable) },
                expandCallback = { name -> homeViewModel.expand(name) }
            )
            kumoMasterRecycler.setRecycledViewPool(KumoHomeAdapter.sharedPool)
            kumoMasterRecycler.adapter = kumoAdapter

            kumoReloadConnectionerror.setOnClickListener(apiChangeClickListener)
            kumoReloadConnectionOpenInBrowser.setOnClickListener {
                val currentApi = currentApiName?.let { getApiFromNameNull(it) }
                    ?: homeViewModel.apiName.value?.let { getApiFromNameNull(it) }
                val mainUrl = currentApi?.mainUrl
                if (!mainUrl.isNullOrBlank()) {
                    context?.openBrowser(mainUrl)
                }
            }
            kumoReloadConnectionGoToDownloads.setOnClickListener {
                activity?.navigate(R.id.navigation_downloads)
            }

            // Stock CloudStream bottom navigation stays visible on KUMO home (phone only).
            // The custom KUMO bottom bar is kept hidden to match stock behavior.
            // On TV the side rail is the nav — forcing nav_view visible would
            // show two nav bars at once, so only do it on phone.
            kumoBottomNavContainer.isGone = true
            if (isLayout(com.lagradost.cloudstream3.ui.settings.Globals.PHONE)) {
                activity?.findViewById<View>(R.id.nav_view)?.isVisible = true
            }
        }

        /** Metadata catalog rows: fetched concurrently, published progressively. */
        fun loadMetadataSection() {
            val ctx = context ?: return
            lifecycleScope.launch {
                val urls = withContext(Dispatchers.IO) {
                    StremioCatalogStore.getAddons(ctx)
                }
                if (urls.isEmpty()) {
                    metadataRowsMutex.withLock { metadataRows = emptyList() }
                    binding?.kumoMetadataSection?.isGone = true
                    return@launch
                }
                binding?.kumoMetadataSection?.isVisible = true
                val defs = withContext(Dispatchers.IO) {
                    urls.flatMap { url ->
                        try {
                            StremioCatalogApi.fetchDefinitions(url)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }.take(6)
                }
                // Publish rows as each first page lands (progressive, no head-blocking).
                val fresh = withContext(Dispatchers.IO) {
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
                metadataRowsMutex.withLock { metadataRows = fresh }
                binding?.kumoMetadataRows?.let { rv ->
                    metadataRowAdapter?.submitList(fresh.toList())
                }
                binding?.kumoMetadataSection?.isGone = fresh.isEmpty()
                if (fresh.isNotEmpty()) {
                    metadataHeroItems = fresh.first().items.filter { it.poster != null }.take(8)
                    metadataHeroIndex = 0
                    usingMetadataHero = metadataHeroItems.isNotEmpty()
                } else {
                    metadataHeroItems = emptyList()
                    usingMetadataHero = false
                    metadataHeroActive = false
                }
                updateHomeContentPriority()
            }
        }

        fun onMetadataLoadMore(row: StremioRow) {
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
                metadataRowsMutex.withLock {
                    metadataRows = metadataRows.map { current ->
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
                binding?.kumoMetadataRows?.let { rv ->
                    metadataRowAdapter?.submitList(metadataRows.toList())
                }
                Unit
            }
        }

        // Metadata catalog section (Stremio rows, primary on open when configured).
        binding?.apply {
            kumoMetadataRows.layoutManager = LinearLayoutManager(context)
            kumoMetadataRows.isNestedScrollingEnabled = false
            metadataRowAdapter = KumoCatalogRowAdapter(
                onCardClick = { meta -> onMetadataCardClick(meta) },
                onLoadMore = { row -> onMetadataLoadMore(row) },
                onTitleLongClick = { },
            )
            kumoMetadataRows.adapter = metadataRowAdapter
            kumoMetadataHeader.setOnClickListener {
                activity?.navigate(R.id.action_navigation_home_to_navigation_kumo_catalog)
            }
        }
        loadMetadataSection()

        observe(homeViewModel.apiName) { apiName ->
            currentApiName = apiName
            binding.kumoChangeApi.text = getDisplayName(apiName)
            if (apiName == com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi.name) {
                binding.kumoHeroContainer.isGone = true
            }
        }

        observe(homeViewModel.page) { data ->
            binding.apply {
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        val list = d.values.map { it.copy(list = it.list.copy(list = it.list.list.toMutableList())) }
                        (kumoMasterRecycler.adapter as? KumoHomeAdapter)?.submitList(list)

                        // Provider hero only when metadata catalog is not the home.
                        // Nuvio-style rotating hero: pool from first non-empty section.
                        val pool = list.firstOrNull { it.list.list.isNotEmpty() }
                            ?.list?.list?.filter { it.posterUrl != null }?.take(8).orEmpty()
                        heroItems = pool
                        heroIndex = 0
                        usingMetadataHero = false
                        if (!hasMetadataHome() && pool.isNotEmpty()) {
                            kumoHeroContainer.isVisible = true
                            kumoHeroContainer.isFocusable = isLayout(TV)
                            kumoHeroContainer.isFocusableInTouchMode = isLayout(TV)
                            kumoHeroContainer.nextFocusDownId = kumoMasterRecycler.id
                            kumoHeroContainer.nextFocusLeftId = R.id.nav_rail_view
                            showHero(0)
                            startHeroRotation()
                        } else if (!hasMetadataHome()) {
                            stopHeroRotation()
                            kumoHeroContainer.isGone = true
                        }
                        updateHomeContentPriority()

                        kumoLoading.isGone = true
                        kumoLoadingError.isGone = true
                        kumoMasterRecycler.isVisible = true
                        kumoEmpty.isGone = true
                        kumoLoadingShimmer.stopShimmer()

                        if (list.isEmpty() || list.all { it.list.list.isEmpty() }) {
                            kumoMasterRecycler.isGone = true
                            kumoEmpty.isVisible = true
                            kumoHeroContainer.isGone = true
                        }
                        saveHomepageToTV(d)
                    }
                    is Resource.Failure -> {
                        kumoLoadingShimmer.stopShimmer()
                        kumoLoading.isGone = true
                        kumoLoadingError.isVisible = true
                        kumoMasterRecycler.isInvisible = true
                        kumoEmpty.isGone = true
                        kumoHeroContainer.isGone = true

                        val hasNoNetworkConnection = context?.isNetworkAvailable() == false
                        val isNetworkError = data.isNetworkError
                        kumoReloadConnectionGoToDownloads.isVisible = hasNoNetworkConnection || isNetworkError
                        kumoReloadConnectionOpenInBrowser.isGone = hasNoNetworkConnection
                        kumoResultErrorText.text = if (hasNoNetworkConnection) {
                            getString(R.string.no_internet_connection)
                        } else {
                            data.errorString
                        }
                        (kumoMasterRecycler.adapter as? KumoHomeAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                    }
                    is Resource.Loading -> {
                        kumoLoadingShimmer.startShimmer()
                        kumoLoading.isVisible = true
                        kumoLoadingError.isGone = true
                        kumoMasterRecycler.isInvisible = true
                        kumoEmpty.isGone = true
                        kumoHeroContainer.isGone = true
                        (kumoMasterRecycler.adapter as? KumoHomeAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                    }
                }
            }
        }

        observeNullable(homeViewModel.popup) { item ->
            if (item == null) {
                bottomSheetDialog?.dismissSafe()
                bottomSheetDialog = null
                return@observeNullable
            }
            if (bottomSheetDialog != null) return@observeNullable
            val (expandable, delete) = item
            val ctx = context ?: run {
                homeViewModel.popup(null)
                return@observeNullable
            }
            // Simple KUMO bottom sheet for expanded catalog — reuses existing binding but avoids companion extension dependency
            val dialog = BottomSheetDialog(ctx)
            val sheetBinding = com.lagradost.cloudstream3.databinding.HomeEpisodesExpandedBinding.inflate(
                dialog.layoutInflater,
                null,
                false
            )
            dialog.setContentView(sheetBinding.root)
            sheetBinding.homeExpandedText.text = expandable.list.name
            sheetBinding.homeExpandedDelete.isGone = delete == null
            sheetBinding.homeExpandedDelete.setOnClickListener {
                delete?.invoke()
                dialog.dismissSafe()
            }
            sheetBinding.homeExpandedDragDown.setOnClickListener { dialog.dismissSafe() }
            // Setup recycler with SearchAdapter (same as CloudStream)
            val adapter = com.lagradost.cloudstream3.ui.search.SearchAdapter(
                sheetBinding.homeExpandedRecycler,
                expandable.list.isHorizontalImages
            ) { callback ->
                handleSearchClickCallback(callback)
                val action = callback.action
                if (action == com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD ||
                    action == com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
                ) {
                    dialog.dismissSafe()
                }
            }
            sheetBinding.homeExpandedRecycler.setRecycledViewPool(com.lagradost.cloudstream3.ui.search.SearchAdapter.sharedPool)
            sheetBinding.homeExpandedRecycler.adapter = adapter
            adapter.submitList(expandable.list.list)
            // Span count adapts to orientation
            sheetBinding.homeExpandedRecycler.spanCount = ctx.getSpanCount(expandable.list.isHorizontalImages)
            dialog.setOnDismissListener {
                homeViewModel.popup(null)
                bottomSheetDialog = null
            }
            dialog.show()
            bottomSheetDialog = dialog
        }

        homeViewModel.reloadStored()
        // KUMO initialization — mirrors original HomeFragment's startup:
        // Original reads DataStoreHelper.currentHomePage (persisted via fromUI=true) and posts Loading until plugins load,
        // then afterPluginsLoadedEvent triggers reload with the same key.
        // For fresh installs where currentHomePage is null/none (separate debug prefs), auto-select first available provider
        // so catalogs appear without manual picker — still respects existing selection when present.
        val initialHome = DataStoreHelper.currentHomePage?.takeIf { it.isNotBlank() }?.let { name ->
            // Respect stored selection if it still resolves to a known provider
            if (getApiFromNameNull(name) != null || name == com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi.name || name == com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi.name) name else null
        } ?: context?.filterProviderByPreferredMedia()?.firstOrNull { it.hasMainPage }?.name
            ?: DataStoreHelper.currentHomePage
        homeViewModel.loadAndCancel(initialHome, false)
    }

    /** Catalog rows are the main homescreen whenever they exist. */
    private fun updateHomeContentPriority() {
        val binding = binding ?: return
        if (!hasMetadataHome()) {
            usingMetadataHero = false
            metadataHeroActive = false
            return
        }
        binding.apply {
            kumoMetadataSection.isVisible = true
            // Provider chrome yields to metadata home.
            kumoMasterRecycler.isGone = true
            kumoEmpty.isGone = true
            kumoLoading.isGone = true
            kumoLoadingError.isGone = true
            kumoLoadingShimmer.stopShimmer()
            if (usingMetadataHero && !metadataHeroActive) {
                kumoHeroContainer.isVisible = true
                kumoHeroContainer.isFocusable = isLayout(TV)
                kumoHeroContainer.isFocusableInTouchMode = isLayout(TV)
                kumoHeroContainer.nextFocusDownId = kumoMetadataRows.id
                kumoHeroContainer.nextFocusLeftId = R.id.nav_rail_view
                showMetadataHero(0)
                startHeroRotation()
                metadataHeroActive = true
            } else if (usingMetadataHero) {
                kumoHeroContainer.isVisible = true
            }
        }
    }

    private fun onMetadataCardClick(meta: StremioMeta) {
        if (metadataResolving) return
        metadataResolving = true
        lifecycleScope.launch {
            val result = KumoCatalogResolve.resolveAndOpen(activity as? FragmentActivity, meta)
            metadataResolving = false
            when (result) {
                is KumoCatalogResolve.ResolveResult.Opened -> Unit
                is KumoCatalogResolve.ResolveResult.ProviderMissing -> {
                    showToast(R.string.no_provider_found, Toast.LENGTH_LONG)
                }
                is KumoCatalogResolve.ResolveResult.NotFound -> {
                    context?.let {
                        showToast(it.getString(R.string.no_results_found, meta.name), Toast.LENGTH_LONG)
                    }
                }
                is KumoCatalogResolve.ResolveResult.Failed -> {
                    showToast(R.string.error, Toast.LENGTH_SHORT)
                }
            }
            Unit
        }
    }

    private fun saveHomepageToTV(page: Map<String, HomeViewModel.ExpandableHomepageList>) {
        if (!isLayout(TV)) return
        val (name, data) = page.entries.firstOrNull() ?: return
        if (name == lastSavedHomepage) return
        lastSavedHomepage = name
        try {
            val ctx = context ?: return
            val existingId = com.lagradost.cloudstream3.utils.TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                ioSafe {
                    try {
                        com.lagradost.cloudstream3.utils.TvChannelUtils.deleteStoredPrograms(ctx)
                        com.lagradost.cloudstream3.utils.TvChannelUtils.addPrograms(ctx, existingId, data.list.list)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleTvBackPress(helper: BackPressedCallbackHelper.CallbackHelper) {
        if (!isLayout(TV)) {
            helper.runDefault()
            return
        }
        val currentFocus = activity?.currentFocus ?: run {
            helper.runDefault()
            return
        }
        var parent = currentFocus.parent
        var isInsideRecycler = false
        while (parent != null) {
            if (parent is View && parent.id == R.id.kumo_master_recycler) {
                isInsideRecycler = true
                break
            }
            if (parent is View && parent.id == R.id.kumo_row_recycler) {
                isInsideRecycler = true
                break
            }
            parent = parent.parent
        }
        when {
            isInsideRecycler -> {
                binding?.kumoMasterRecycler?.scrollToPosition(0)
                binding?.kumoChangeApi?.post { binding?.kumoChangeApi?.requestFocus() }
            }
            currentFocus.id == R.id.kumo_change_api ||
            currentFocus.id == R.id.kumo_search_button ||
            currentFocus.id == R.id.kumo_account_button -> {
                activity?.findViewById<View>(R.id.navigation_home)?.requestFocus()
            }
            else -> helper.runDefault()
        }
    }
}
