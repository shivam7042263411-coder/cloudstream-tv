package com.lagradost.cloudstream3.ui.kumo.library

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.FragmentKumoLibraryBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.KumoHomePosterAdapter
import com.lagradost.cloudstream3.ui.kumo.details.KumoOption
import com.lagradost.cloudstream3.ui.kumo.details.KumoOptionAdapter
import com.lagradost.cloudstream3.ui.library.LibraryViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getFavoritesData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nuvio-style Library on CloudStream data (presentation only).
 *
 * - Continue Watching: resume states joined with saved title metadata
 *   (favorites/bookmarks) + episode progress. Tap opens the parent
 *   details page where the EXISTING resume flow takes over.
 * - Library rows: the SAME LibraryViewModel pages (bookmarks/watchlist/
 *   watched) rendered as Nuvio rows with KUMO poster cards.
 * - Tap any card → existing loadSearchResult → details → playback.
 * No new storage, no new playback path.
 */
class KumoLibraryFragment : BaseFragment<FragmentKumoLibraryBinding>(
    BindingCreator.Inflate(FragmentKumoLibraryBinding::inflate)
) {
    companion object {
        private const val TAG = "KumoLibrary"
    }

    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private var continueAdapter: KumoContinueWatchingAdapter? = null
    private var tabAdapter: KumoOptionAdapter? = null
    private var gridAdapter: KumoHomePosterAdapter? = null
    private var pages: List<SyncAPI.Page> = emptyList()
    private var selectedTab: Int = 0

    override fun pickLayout(): Int = R.layout.fragment_kumo_library

    override fun fixLayout(view: View) {
    }

    override fun onResume() {
        super.onResume()
        libraryViewModel.reloadPages(false)
        loadContinueWatching()
    }

    override fun onDestroyView() {
        (activity as? androidx.activity.ComponentActivity)?.detachBackPressedCallback("KumoLibrary_BackPress")
        super.onDestroyView()
    }

    override fun onBindingCreated(binding: FragmentKumoLibraryBinding, savedInstanceState: Bundle?) {
        (activity as? androidx.activity.ComponentActivity)?.attachBackPressedCallback("KumoLibrary_BackPress") {
            // Grid first: move focus up to the selected tab instead of exiting.
            val grid = binding.kumoLibraryGrid
            if (grid.hasFocus()) {
                val tabsLm = binding.kumoLibraryTabs.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val target = tabsLm?.findViewByPosition(selectedTab)
                    ?: tabsLm?.findViewByPosition(0)
                if (target != null) {
                    target.requestFocus()
                } else {
                    runDefault()
                }
            } else {
                runDefault()
            }
        }
        binding.apply {
            kumoLibraryContinue.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            continueAdapter = KumoContinueWatchingAdapter { item ->
                activity?.loadSearchResult(item.parent)
            }
            kumoLibraryContinue.adapter = continueAdapter

            kumoLibraryTabs.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            tabAdapter = KumoOptionAdapter { id ->
                selectTab(id)
            }
            kumoLibraryTabs.adapter = tabAdapter

            kumoLibraryGrid.layoutManager = GridLayoutManager(context, 6)
            kumoLibraryGrid.setItemViewCacheSize(8)
            kumoLibraryGrid.itemAnimator = null
            // Grid posters scale on focus; never clip them.
            kumoLibraryGrid.clipChildren = false
            gridAdapter = KumoHomePosterAdapter { callback ->
                if (callback.action == SEARCH_ACTION_LOAD) {
                    activity?.loadSearchResult(callback.card)
                }
            }
            kumoLibraryGrid.adapter = gridAdapter
        }

        observe(libraryViewModel.pages) { pages ->
            val binding = binding ?: return@observe
            val list = (pages as? Resource.Success)?.value.orEmpty()
            Log.d(TAG, "library pages: ${list.size}")
            binding.kumoLibraryLoading.isGone = true
            renderTabs(binding, list)
        }

        loadContinueWatching()
    }

    data class ContinueItem(
        val parent: SearchResponse,
        val season: Int?,
        val episode: Int?,
        val progress01: Float,
    )

    private fun loadContinueWatching() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val ids = try {
                    getAllResumeStateIds().orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                ids.mapNotNull { id ->
                    try {
                        val resume = getLastWatched(id) ?: return@mapNotNull null
                        val parent = getFavoritesData(resume.parentId)
                            ?: getBookmarkedData(resume.parentId)
                            ?: return@mapNotNull null
                        val pos = getViewPos(resume.episodeId)
                        val progress = if (pos != null && pos.duration > 0) {
                            (pos.position.toFloat() / pos.duration.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        resume to ContinueItem(parent, resume.season, resume.episode, progress)
                    } catch (_: Exception) {
                        null
                    }
                }.sortedByDescending { (resume, _) -> resume.updateTime }
                    .take(20)
                    .map { (_, item) -> item }
            }
            Log.d(TAG, "continue watching: ${items.size} items")
            binding?.apply {
                kumoLibraryContinueSection.isVisible = items.isNotEmpty()
                continueAdapter?.submitList(items)
            }
            updateEmpty()
        }
    }

    private fun renderTabs(binding: FragmentKumoLibraryBinding, list: List<SyncAPI.Page>) {
        pages = list.filter { it.items.isNotEmpty() }
        if (selectedTab >= pages.size) selectedTab = 0
        val ctx = binding.root.context
        tabAdapter?.submitList(pages.mapIndexed { index, page ->
            KumoOption(
                page.title.asString(ctx) ?: "List ${index + 1}",
                index == selectedTab,
                index
            )
        })
        binding.kumoLibraryTabs.isGone = pages.isEmpty()
        renderGrid(binding)
    }

    private fun selectTab(index: Int) {
        val binding = binding ?: return
        if (index == selectedTab || index !in pages.indices) return
        selectedTab = index
        val ctx = binding.root.context
        // Refresh selected pill without stealing D-pad focus (stays on tabs).
        tabAdapter?.submitList(pages.mapIndexed { i, page ->
            KumoOption(
                page.title.asString(ctx) ?: "List ${i + 1}",
                i == selectedTab,
                i
            )
        })
        renderGrid(binding, scrollTop = true)
    }

    private fun renderGrid(binding: FragmentKumoLibraryBinding, scrollTop: Boolean = false) {
        val items = pages.getOrNull(selectedTab)?.items.orEmpty()
        gridAdapter?.submitList(items)
        binding.kumoLibraryGrid.isGone = items.isEmpty()
        // Only jump to top on explicit tab switches, never on data refreshes,
        // so grid position survives reloads while browsing.
        if (scrollTop && items.isNotEmpty()) {
            binding.kumoLibraryGrid.scrollToPosition(0)
        }
        updateEmpty()
    }

    private fun updateEmpty() {
        val binding = binding ?: return
        val hasContinue = (continueAdapter?.immutableCurrentList?.size ?: 0) > 0
        val hasGrid = (gridAdapter?.immutableCurrentList?.size ?: 0) > 0
        binding.kumoLibraryEmpty.isVisible = !hasContinue && !hasGrid
    }
}
