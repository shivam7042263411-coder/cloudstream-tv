package com.lagradost.cloudstream3.ui.kumo.details

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentKumoDetailsBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.cloudstream3.ui.result.ACTION_SHOW_OPTIONS
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.result.ActorAdaptor
import com.lagradost.cloudstream3.ui.result.EpisodeClickEvent
import com.lagradost.cloudstream3.ui.result.EpisodeRange
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.ResumeWatchingStatus
import com.lagradost.cloudstream3.ui.result.ResultFragment.StoredData
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.ui.result.ResultFragment.updateUIEvent
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import coil3.request.crossfade
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogInstant
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.ui.result.callback
import com.lagradost.cloudstream3.ui.result.getOptions
import com.lagradost.cloudstream3.ui.result.getTitle
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.setTextHtml
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe

/**
 * KUMO phone Details page. Presentation-only layer on top of the EXISTING
 * CloudStream result architecture:
 *
 *   KumoDetailsFragment → existing ResultViewModel2 → existing MainAPI.load()
 *   → existing LoadResponse → KUMO presentation
 *
 * Episodes: KUMO UI → existing viewModel.handleAction(ACTION_CLICK_DEFAULT)
 *   → existing RepoLinkGenerator → APIRepository.loadLinks → ExtractorApi → CS3IPlayer.
 * Favorites/watch state: existing DataStoreHelper via ResultViewModel2.
 * No scraping, resolving, playback, or storage logic lives here.
 */
class KumoDetailsFragment : BaseFragment<FragmentKumoDetailsBinding>(
    BindingCreator.Inflate(FragmentKumoDetailsBinding::inflate)
) {
    companion object {
        private const val TAG = "KumoDetails"
    }

    private lateinit var viewModel: ResultViewModel2
    private var storedData: StoredData? = null

    private var episodeAdapter: KumoEpisodeAdapter? = null
    private var seasonAdapter: KumoOptionAdapter? = null
    private var rangeAdapter: KumoOptionAdapter? = null
    private var dubAdapter: KumoOptionAdapter? = null
    private var castAdapter: ActorAdaptor? = null

    private var latestResume: ResumeWatchingStatus? = null
    private var latestMovie: ResultEpisode? = null
    private var latestEpisodes: List<ResultEpisode> = emptyList()
    private var isDescriptionExpanded = false
    private var isComingSoon = false
    private var initialFocusDone = false
    private var isPastHero = false

    private var loadingDialog: BottomSheetDialog? = null
    private var popupDialog: BottomSheetDialog? = null

    override fun pickLayout(): Int = R.layout.fragment_kumo_details

    override fun fixLayout(view: View) {
        // KUMO Details handles its own insets via the scroll content padding.
    }

    /** TV D-pad guidance: every hero control visibly grows on focus. */
    private fun View.tvFocusScale(scale: Float = 1.08f) {
        setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    /** Ditto focus language: focused icon control = white disc + black icon.
     * Restores the original background/tint on unfocus. Includes the scale. */
    private fun android.widget.ImageButton.tvContrastFocus() {
        val defaultBg = background
        val defaultTint = imageTintList
        setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                setBackgroundResource(R.drawable.kumo_play_button_white)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFF14141C.toInt())
            } else {
                background = defaultBg
                imageTintList = defaultTint
            }
            val s = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    private fun reloadViewModel(forceReload: Boolean) {
        val data = storedData ?: return
        if (!viewModel.hasLoaded() || forceReload) {
            viewModel.load(
                activity,
                data.url,
                data.apiName,
                data.showFillers,
                data.dubStatus,
                data.start,
                // No trailer UI: skip resolve entirely so the page posts faster.
                loadTrailers = false
            )
        }
    }

    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += ::reloadViewModel
        // Refresh watch/progress badges when returning from the player.
        // No focus steal: onPlayerUpdate reloads the same data, and focus
        // stays where the user left it (row position preserved).
        if (::viewModel.isInitialized && viewModel.hasLoaded()) {
            viewModel.reloadEpisodes()
        }
    }

    override fun onStop() {
        afterPluginsLoadedEvent -= ::reloadViewModel
        super.onStop()
    }

    override fun onDestroyView() {
        // Stock parity: the player fires this AFTER persisting watch state,
        // so reloading here (not just in onResume) is what flips Play→Resume.
        updateUIEvent -= ::onPlayerUpdate
        (activity as? androidx.activity.ComponentActivity)
            ?.detachBackPressedCallback("KumoDetails_BackPress")
        super.onDestroyView()
    }

    /** Re-read store after player exit → resume info + Play label flip. */
    private fun onPlayerUpdate(id: Int?) {
        if (::viewModel.isInitialized && viewModel.hasLoaded()) {
            viewModel.reloadEpisodes()
        }
    }

    override fun onBindingCreated(binding: FragmentKumoDetailsBinding, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(this)[ResultViewModel2::class.java]
        updateUIEvent += ::onPlayerUpdate
        // Back collapses the expanded description first (stock drawer pattern).
        (activity as? androidx.activity.ComponentActivity)?.attachBackPressedCallback("KumoDetails_BackPress") {
            if (isDescriptionExpanded) {
                isDescriptionExpanded = false
                binding.kumoDetailsDescription.maxLines = 2
                binding.kumoDetailsMore.text = "Show More ▾"
            } else {
                runDefault()
            }
        }

        val data = getStoredData() ?: return
        storedData = data
        if (!viewModel.hasLoaded()) {
            viewModel.load(
                activity,
                data.url,
                data.apiName,
                data.showFillers,
                data.dubStatus,
                data.start,
                // No trailer UI: skip resolve entirely so the page posts faster.
                loadTrailers = false
            )
        }

        binding.apply {
            // Visible focus states for TV (no effect on touch).
            // Ditto language: focused = white + black, unfocused = original.
            // Play pill: no outline ring, negligible zoom only.
            kumoDetailsPlay.tvFocusScale(1.02f)
            kumoDetailsMoreActions.tvContrastFocus()
            kumoDetailsSaved.tvContrastFocus()
            // "Show More" is display-only: not focusable, not clickable.

            // All horizontal rows: hold-D-pad hardening. A bigger view cache keeps
            // the focused child alive when fast repeat outpaces layout (otherwise
            // the focused view gets recycled mid-scroll, focus drops, and the next
            // press refocuses somewhere above — the row-jump bug). Fixed size +
            // prefetch keep targets laid out ahead of focus; no animator removes
            // change-animation focus yanks. Touch behavior unchanged.
            fun androidx.recyclerview.widget.RecyclerView.hardenRow(prefetch: Int = 4) {
                setHasFixedSize(true)
                setItemViewCacheSize(8)
                itemAnimator = null
                // Focused children scale up; never let the row clip them.
                clipChildren = false
                (layoutManager as? LinearLayoutManager)?.apply {
                    isItemPrefetchEnabled = true
                    initialPrefetchItemCount = prefetch
                }
            }

            // Row containers catch focus and forward it to their first visible
            // child. Without this, explicit down-targets resolve to an
            // unfocusable container (null) and geometric search skips rows
            // whose scroll position doesn't line up — e.g. the range row.
            // TV-only visual no-op; touch unaffected (not focusable in touch).
            fun androidx.recyclerview.widget.RecyclerView.forwardRowFocus() {
                if (!isLayout(TV or EMULATOR)) return
                isFocusable = true
                isFocusableInTouchMode = false
                onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) return@OnFocusChangeListener
                    val lm = layoutManager as? LinearLayoutManager ?: return@OnFocusChangeListener
                    val first = lm.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: 0
                    lm.findViewByPosition(first)?.requestFocus()
                }
            }

            kumoDetailsEpisodes.setLinearListLayout(
                isHorizontal = true,
                nextLeft = FOCUS_SELF,
                nextRight = FOCUS_SELF
            )
            episodeAdapter = KumoEpisodeAdapter { click ->
                viewModel.handleAction(click)
            }
            kumoDetailsEpisodes.adapter = episodeAdapter
            kumoDetailsEpisodes.hardenRow()
            kumoDetailsEpisodes.forwardRowFocus()

            kumoDetailsSeasons.setLinearListLayout(
                isHorizontal = true,
                nextLeft = FOCUS_SELF,
                nextRight = FOCUS_SELF
            )
            seasonAdapter = KumoOptionAdapter { id ->
                viewModel.changeSeason(id)
            }
            kumoDetailsSeasons.adapter = seasonAdapter
            kumoDetailsSeasons.hardenRow()
            kumoDetailsSeasons.forwardRowFocus()

            kumoDetailsRanges.setLinearListLayout(
                isHorizontal = true,
                nextLeft = FOCUS_SELF,
                nextRight = FOCUS_SELF
            )
            rangeAdapter = KumoOptionAdapter { id ->
                viewModel.changeRange(rangeOptions.getOrNull(id)?.second ?: return@KumoOptionAdapter)
            }
            kumoDetailsRanges.adapter = rangeAdapter
            kumoDetailsRanges.hardenRow()
            kumoDetailsRanges.forwardRowFocus()

            kumoDetailsDubs.setLinearListLayout(
                isHorizontal = true,
                nextLeft = FOCUS_SELF,
                nextRight = FOCUS_SELF
            )
            dubAdapter = KumoOptionAdapter { id ->
                viewModel.changeDubStatus(dubOptions.getOrNull(id)?.second ?: return@KumoOptionAdapter)
            }
            kumoDetailsDubs.adapter = dubAdapter
            kumoDetailsDubs.hardenRow()
            kumoDetailsDubs.forwardRowFocus()

            kumoDetailsCast.setLinearListLayout(
                isHorizontal = true,
                nextLeft = FOCUS_SELF,
                nextRight = FOCUS_SELF
            )
            castAdapter = ActorAdaptor(nextFocusUpId = R.id.kumoDetailsDescription)
            kumoDetailsCast.adapter = castAdapter
            kumoDetailsCast.hardenRow()
            kumoDetailsCast.forwardRowFocus()

            kumoDetailsPlay.setOnClickListener {
                playPrimary(data)
            }

            // Nuvio-style backdrop dim: scroll-driven ONLY (never focus).
            // On hero: art full bright + scrim on. Scrolled past half the
            // hero: art to 15% + scrim off. 300ms down, 800ms back up.
            // No focus writes here: navigation is untouched by construction.
            kumoDetailsScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateBackdropDim(scrollY)
            }

            // "..." button: episode options for the next play target
            // (quality / source / external player). Existing pipeline.
            kumoDetailsMoreActions.setOnClickListener {
                nextPlayTarget()?.let { target ->
                    viewModel.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, target))
                }
            }

            // "+" button: always visible, opens the library category list
            // (Watching / Completed / On Hold / Dropped / Plan to Watch / None).
            // Same categories + storage as the stock TV bookmark button.
            kumoDetailsSaved.isVisible = true
            kumoDetailsSaved.setOnClickListener {
                val current = viewModel.watchStatus.value ?: WatchType.NONE
                activity?.showBottomDialog(
                    WatchType.entries.map { it.stringRes.let { res -> getString(res) } },
                    current.ordinal,
                    getString(R.string.action_add_to_bookmarks),
                    showApply = false,
                    {}) {
                    viewModel.updateWatchStatus(WatchType.entries[it], context)
                }
            }
            // Long-press "+" keeps the old favorite toggle without adding UI.
            kumoDetailsSaved.setOnLongClickListener {
                viewModel.toggleFavoriteStatus(context) { newStatus ->
                    if (newStatus == true) {
                        showToast(R.string.favorite_added, Toast.LENGTH_SHORT)
                    } else {
                        showToast(R.string.favorite_removed, Toast.LENGTH_SHORT)
                    }
                }
                true
            }

            // "Show More" is display-only: no click wiring on TV.

            kumoDetailsRetry.setOnClickListener {
                viewModel.load(
                    activity,
                    data.url,
                    data.apiName,
                    data.showFillers,
                    data.dubStatus,
                    data.start,
                    // No trailer UI: skip resolve entirely so the page posts faster.
                    loadTrailers = false
                )
            }
            kumoDetailsRetry.tvFocusScale()
        }

        observePage()
        observeEpisodes()
        observeResume()
        observeFavorite()
        observeWatchlist()
        observeSelections()
        observeDialogs()
    }

    private var rangeOptions: List<Pair<UiText?, EpisodeRange>> = emptyList()
    private var dubOptions: List<Pair<UiText?, DubStatus>> = emptyList()

    /** Next episode to play: resume → movie → first episode (existing behavior). */
    private fun nextPlayTarget(): ResultEpisode? {
        latestResume?.let { return it.result }
        latestMovie?.let { return it }
        return latestEpisodes.firstOrNull()
    }

    private fun playLabel(target: ResultEpisode?): String {
        if (target == null) return "▶ Play"
        val prefix = if (latestResume != null) "Resume" else "Play"
        // Movies are a single ResultEpisode with episode 0 and no season:
        // plain "Play"/"Resume", never "E0".
        if (target.season == null && target.episode == 0) return "▶ $prefix"
        val season = target.season
        return if (season != null) {
            "▶ $prefix S${season}E${target.episode}"
        } else {
            "▶ $prefix E${target.episode}"
        }
    }

    /** Existing PLAY/resume behavior: resume → movie → first episode. */
    private fun playPrimary(data: StoredData) {
        val resume = latestResume
        if (resume != null) {
            android.util.Log.d(
                TAG, "play: resume S${resume.result.season}E${resume.result.episode} " +
                    "id=${resume.result.id} -> handleAction(${data.playerAction})"
            )
            viewModel.handleAction(EpisodeClickEvent(data.playerAction, resume.result))
            return
        }
        nextPlayTarget()?.let { target ->
            android.util.Log.d(
                TAG, "play: S${target.season}E${target.episode} id=${target.id} " +
                    "api=${target.apiName} -> handleAction(CLICK_DEFAULT) -> RepoLinkGenerator"
            )
            viewModel.handleAction(EpisodeClickEvent(ACTION_CLICK_DEFAULT, target))
        } ?: android.util.Log.w(TAG, "play: no target (no resume/movie/episodes)")
    }

    private fun observePage() {
        observe(viewModel.page) { page ->
            val binding = binding ?: return@observe
            val ctx = context ?: return@observe
            when (page) {
                is Resource.Success -> {
                    binding.kumoDetailsLoading.isGone = true
                    binding.kumoDetailsError.isGone = true
                    binding.kumoDetailsScroll.isVisible = true
                    // TV: land D-pad focus on Play exactly once per page.
                    // Never again after: focus moves only by the user (or to
                    // Retry on error), never by data emissions.
                    if (!initialFocusDone && isLayout(TV or EMULATOR)) {
                        initialFocusDone = true
                        binding.kumoDetailsPlay.requestFocus()
                    }
                    page.value?.let { d ->
                        isComingSoon = d.comingSoon
                        binding.kumoDetailsBackdrop.loadImage(
                            d.posterBackgroundImage ?: d.posterImage,
                            headers = d.posterHeaders,
                            builder = { crossfade(true) }
                        )
                        // Nuvio-style logo treatment: logo image when available, title text otherwise.
                        // setText first: it re-shows the title view, so bindLogo
                        // must run after to enforce logo-XOR-title.
                        binding.kumoDetailsTitle.setText(d.titleText)
                        bindLogo(
                            url = d.logoUrl,
                            headers = d.posterHeaders,
                            logoView = binding.kumoDetailsLogo,
                            titleView = binding.kumoDetailsTitle
                        )

                        val genres = d.tags.filter { it.isNotBlank() }.take(3)
                        binding.kumoDetailsGenres.text = genres.joinToString(" • ")
                        binding.kumoDetailsGenres.isGone = genres.isEmpty()

                        val year = d.yearText?.asString(ctx)?.takeIf { it.isNotBlank() }
                        binding.kumoDetailsYear.text = year.orEmpty()
                        binding.kumoDetailsYear.isGone = year.isNullOrBlank()

                        val runtime = d.durationText?.asString(ctx)?.takeIf { it.isNotBlank() }
                        binding.kumoDetailsRuntime.text = runtime.orEmpty()
                        binding.kumoDetailsRuntime.isGone = runtime.isNullOrBlank()

                        val age = d.contentRatingText?.asString(ctx)?.takeIf { it.isNotBlank() }
                        binding.kumoDetailsAgeBadge.text = age.orEmpty()
                        binding.kumoDetailsAgeBadge.isGone = age.isNullOrBlank()

                        val status = d.onGoingText?.asString(ctx)?.takeIf { it.isNotBlank() }
                        binding.kumoDetailsStatusBadge.text = status.orEmpty()
                        binding.kumoDetailsStatusBadge.isGone = status.isNullOrBlank()

                        val rating = d.ratingText?.asString(ctx)
                            ?.takeIf { it.isNotBlank() }
                            // Providers often send "8.2/10.0": show the bare number.
                            ?.substringBefore("/")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        binding.kumoDetailsImdbBadge.isGone = rating.isNullOrBlank()
                        binding.kumoDetailsRating.text = rating.orEmpty()
                        binding.kumoDetailsRating.isGone = rating.isNullOrBlank()

                        // Collapse leading gaps: first visible meta item sits flush.
                        val metaGap = (14 * ctx.resources.displayMetrics.density).toInt()
                        var metaFirst = true
                        for (i in 0 until binding.kumoDetailsMetaRow.childCount) {
                            val child = binding.kumoDetailsMetaRow.getChildAt(i)
                            if (child.isVisible) {
                                (child.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                                    it.marginStart = if (metaFirst) 0 else metaGap
                                    child.layoutParams = it
                                }
                                metaFirst = false
                            }
                        }
                        var meta2First = true
                        var meta2Any = false
                        for (i in 0 until binding.kumoDetailsMetaRow2.childCount) {
                            val child = binding.kumoDetailsMetaRow2.getChildAt(i)
                            if (child.isVisible) {
                                meta2Any = true
                                (child.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                                    it.marginStart = if (meta2First) 0 else metaGap
                                    child.layoutParams = it
                                }
                                meta2First = false
                            }
                        }
                        binding.kumoDetailsMetaRow2.isGone = !meta2Any
                        repairFocusChain()
                        updatePlayButton()

                        val actors = d.actors.orEmpty()
                        val actorsFallback =
                            d.actorsText?.asString(ctx)?.takeIf { it.isNotBlank() }
                        binding.kumoDetailsCastLabel.isGone =
                            actors.isEmpty() && actorsFallback.isNullOrBlank()
                        binding.kumoDetailsCast.isGone = actors.isEmpty()
                        if (actors.isNotEmpty()) {
                            (binding.kumoDetailsCast.adapter as? ActorAdaptor)?.submitList(actors)
                            binding.kumoDetailsCastLabel.text = "Cast"
                        } else if (!actorsFallback.isNullOrBlank()) {
                            // Imageless cast: stock-style text fallback.
                            binding.kumoDetailsCastLabel.text = actorsFallback
                        }

                        if (d.plotText.asString(ctx).isBlank()) {
                            binding.kumoDetailsDescription.isGone = true
                            binding.kumoDetailsMore.isGone = true
                        } else {
                            binding.kumoDetailsDescription.isVisible = true
                            binding.kumoDetailsMore.isVisible = true
                            binding.kumoDetailsDescription.setTextHtml(d.plotText)
                        }
                    }
                }
                is Resource.Failure -> {
                    binding.kumoDetailsLoading.isGone = true
                    binding.kumoDetailsScroll.isGone = true
                    binding.kumoDetailsError.isVisible = true
                    binding.kumoDetailsErrorText.text = page.errorString
                    repairFocusChain()
                    if (isLayout(TV or EMULATOR)) {
                        binding.kumoDetailsRetry.requestFocus()
                    }
                }
                else -> {
                    binding.kumoDetailsLoading.isVisible = true
                    binding.kumoDetailsScroll.isGone = true
                    binding.kumoDetailsError.isGone = true
                }
            }
        }

        observeNullable(viewModel.episodesCountText) { count ->
            binding?.kumoDetailsEpisodeCount?.setText(count)
        }
    }

    private fun observeEpisodes() {
        observeNullable(viewModel.episodes) { episodes ->
            val binding = binding ?: return@observeNullable
            val list = (episodes as? Resource.Success)?.value.orEmpty()
            latestEpisodes = list
            episodeAdapter?.submitList(list)
            updatePlayButton()
            updateEpisodesSection()
            repairFocusChain()
        }

        observeNullable(viewModel.movie) { movie ->
            latestMovie = (movie as? Resource.Success)?.value?.second
            updatePlayButton()
            updateEpisodesSection()
        }
    }

    /** Movies hide the whole episodes block instead of showing "No episodes". */
    private fun updateEpisodesSection() {
        val binding = binding ?: return
        val isMovie = latestMovie != null
        val empty = latestEpisodes.isEmpty()
        binding.kumoDetailsEpisodesHeader.isGone = isMovie && empty
        binding.kumoDetailsEpisodes.isGone = empty
        binding.kumoDetailsNoEpisodes.isVisible = empty && !isMovie
        if (empty && !isMovie) {
            binding.kumoDetailsNoEpisodes.text = getString(R.string.no_episodes_found)
        }
    }

    private fun observeResume() {
        observeNullable(viewModel.resumeWatching) { resume ->
            val binding = binding ?: return@observeNullable
            latestResume = resume
            if (resume == null) {
                binding.kumoDetailsResumeInfo.isGone = true
            } else {
                binding.kumoDetailsResumeInfo.isVisible = true
                binding.kumoDetailsResumeInfo.text =
                    getString(R.string.resume) + " " + context?.getNameFull(
                        resume.result.name,
                        resume.result.episode,
                        resume.result.season
                    ).orEmpty()
            }
            updatePlayButton()
        }
    }

    private fun updatePlayButton() {
        val binding = binding ?: return
        if (isComingSoon) {
            binding.kumoDetailsPlay.text = getString(R.string.coming_soon)
            binding.kumoDetailsMoreActions.isGone = true
            return
        }
        val target = nextPlayTarget()
        binding.kumoDetailsPlay.text = playLabel(target)
        binding.kumoDetailsMoreActions.isVisible = target != null
    }

    private fun observeFavorite() {
        observeNullable(viewModel.favoriteStatus) { isFavorite ->
            val binding = binding ?: return@observeNullable
            // Only override the "+" icon when nothing is bookmarked;
            // otherwise the watchlist state icon (below) takes precedence.
            if ((viewModel.watchStatus.value ?: WatchType.NONE) == WatchType.NONE) {
                binding.kumoDetailsSaved.setImageResource(
                    if (isFavorite == true) R.drawable.ic_baseline_check_24
                    else R.drawable.ic_baseline_add_24
                )
            }
        }
    }

    /** "+" icon reflects the current library category (same source as stock TV). */
    private fun observeWatchlist() {
        observe(viewModel.watchStatus) { watchType ->
            val binding = binding ?: return@observe
            binding.kumoDetailsSaved.setImageResource(
                if (watchType == WatchType.NONE) R.drawable.ic_baseline_add_24
                else R.drawable.ic_baseline_check_24
            )
        }
    }

    /** Nuvio parity: backdrop 1f<->0.15f, scrim 1f<->0f.
     * Scroll inverts the flag only on change, so no animation spam.
     * Separate short/long durations match Nuvio (300ms down, 800ms up). */
    private fun updateBackdropDim(scrollY: Int) {
        val binding = binding ?: return
        val heroH = binding.kumoDetailsHero.height.takeIf { it > 0 } ?: return
        val past = scrollY > heroH / 2
        if (past == isPastHero) return
        isPastHero = past
        val duration = if (past) 300L else 800L
        binding.kumoDetailsBackdrop.animate().alpha(if (past) 0.15f else 1f)
            .setDuration(duration).start()
        binding.kumoDetailsScrim.animate().alpha(if (past) 0f else 1f)
            .setDuration(duration).start()
    }

    /** Write row exits to the LayoutManager (not the container): the custom
     * LinearListLayout resolves up/down/edge moves from its own fields. */
    private fun setRowExits(
        row: androidx.recyclerview.widget.RecyclerView,
        up: Int,
        down: Int,
    ) {
        val lm = row.layoutManager as? com.lagradost.cloudstream3.ui.result.LinearListLayout
        if (lm != null) {
            lm.nextFocusUp = up
            lm.nextFocusDown = down
        } else {
            row.nextFocusUpId = up
            row.nextFocusDownId = down
        }
    }

    /** Rebuild D-pad exits around whatever is actually visible.
     * Static XML cannot express conditional rows (trailer off, single-season,
     * movies), so every state change funnels through here. */
    private fun repairFocusChain() {
        val binding = binding ?: return
        val seasonsOn = binding.kumoDetailsSeasons.isVisible
        val dubsOn = binding.kumoDetailsDubRow.isVisible
        val rangesOn = binding.kumoDetailsRangeRow.isVisible
        val episodesOn = binding.kumoDetailsEpisodes.isVisible
        val errorOn = binding.kumoDetailsError.isVisible

        // Hero row: skip the trailer circle when hidden.
        binding.kumoDetailsMoreActions.nextFocusLeftId = R.id.kumoDetailsSaved
        binding.kumoDetailsSaved.nextFocusRightId = R.id.kumoDetailsMoreActions
        // Play's right neighbor is the "+" button.
        val moreOn = binding.kumoDetailsMoreActions.isVisible
        binding.kumoDetailsPlay.nextFocusRightId =
            if (moreOn) R.id.kumoDetailsSaved else View.NO_ID

        // First visible row below the hero.
        val belowHero = when {
            seasonsOn -> R.id.kumoDetailsSeasons
            episodesOn -> R.id.kumoDetailsEpisodes
            else -> View.NO_ID
        }
        binding.kumoDetailsPlay.nextFocusDownId = belowHero
        binding.kumoDetailsMoreActions.nextFocusDownId = belowHero
        binding.kumoDetailsSaved.nextFocusDownId = belowHero

        // Row order: Seasons → Dubs → Ranges → Episodes. Skip hidden rows.
        // Written to the LayoutManagers: the custom LinearListLayout resolves
        // up/down/edge moves from its own fields, not the container attrs.
        setRowExits(binding.kumoDetailsSeasons, R.id.kumoDetailsPlay, when {
            dubsOn -> R.id.kumoDetailsDubs
            rangesOn -> R.id.kumoDetailsRanges
            episodesOn -> R.id.kumoDetailsEpisodes
            else -> View.NO_ID
        })
        setRowExits(
            binding.kumoDetailsDubs,
            if (seasonsOn) R.id.kumoDetailsSeasons else R.id.kumoDetailsPlay,
            when {
                rangesOn -> R.id.kumoDetailsRanges
                episodesOn -> R.id.kumoDetailsEpisodes
                else -> View.NO_ID
            }
        )
        setRowExits(
            binding.kumoDetailsRanges,
            when {
                dubsOn -> R.id.kumoDetailsDubs
                seasonsOn -> R.id.kumoDetailsSeasons
                else -> R.id.kumoDetailsPlay
            },
            if (episodesOn) R.id.kumoDetailsEpisodes else View.NO_ID
        )
        setRowExits(
            binding.kumoDetailsEpisodes,
            when {
                rangesOn -> R.id.kumoDetailsRanges
                dubsOn -> R.id.kumoDetailsDubs
                seasonsOn -> R.id.kumoDetailsSeasons
                else -> R.id.kumoDetailsPlay
            },
            View.NO_ID
        )

        // Down target: skip the display-only "Show More" — Cast, Seasons,
        // or Episodes.
        val castOn = binding.kumoDetailsCast.isVisible
        val downFromDesc = when {
            castOn -> R.id.kumoDetailsCast
            seasonsOn -> R.id.kumoDetailsSeasons
            episodesOn -> R.id.kumoDetailsEpisodes
            else -> View.NO_ID
        }
        binding.kumoDetailsDescription.nextFocusDownId = downFromDesc
        setRowExits(
            binding.kumoDetailsCast,
            R.id.kumoDetailsPlay,
            when {
                seasonsOn -> R.id.kumoDetailsSeasons
                episodesOn -> R.id.kumoDetailsEpisodes
                else -> View.NO_ID
            }
        )

        // Error state: Retry stands alone (remote back key exits).
    }

    private fun observeSelections() {
        observeNullable(viewModel.seasonSelections) { selections ->
            val binding = binding ?: return@observeNullable
            val list = selections.orEmpty()
            binding.kumoDetailsSeasons.isGone = list.isEmpty()
            val selected = viewModel.selectedSeasonIndex.value ?: 0
            seasonAdapter?.submitList(list.mapIndexed { index, (label, season) ->
                KumoOption(
                    label?.asStringNull(binding.root.context) ?: "Season ${index + 1}",
                    index == selected,
                    season
                )
            })
            repairFocusChain()
        }
        observeNullable(viewModel.selectedSeasonIndex) {
            // Re-render selection state through the selections observer data.
            viewModel.seasonSelections.value?.let { selections ->
                val binding = binding ?: return@let
                val selected = it ?: 0
                seasonAdapter?.submitList(selections.mapIndexed { index, (label, season) ->
                    KumoOption(
                        label?.asStringNull(binding.root.context) ?: "Season ${index + 1}",
                        index == selected,
                        season
                    )
                })
                repairFocusChain()
            }
        }

        observeNullable(viewModel.rangeSelections) { selections ->
            val binding = binding ?: return@observeNullable
            rangeOptions = selections.orEmpty()
            binding.kumoDetailsRangeRow.isVisible = rangeOptions.size > 1
            val selected = viewModel.selectedRangeIndex.value ?: 0
            rangeAdapter?.submitList(rangeOptions.mapIndexed { index, (label, _) ->
                KumoOption(
                    label?.asStringNull(binding.root.context) ?: "${index + 1}",
                    index == selected,
                    index
                )
            })
            repairFocusChain()
        }
        observeNullable(viewModel.selectedRangeIndex) { index ->
            val binding = binding ?: return@observeNullable
            val selected = index ?: 0
            rangeAdapter?.submitList(rangeOptions.mapIndexed { i, (label, _) ->
                KumoOption(
                    label?.asStringNull(binding.root.context) ?: "${i + 1}",
                    i == selected,
                    i
                )
            })
            repairFocusChain()
        }

        observeNullable(viewModel.dubSubSelections) { selections ->
            val binding = binding ?: return@observeNullable
            dubOptions = selections.orEmpty()
            binding.kumoDetailsDubRow.isVisible = dubOptions.size > 1
            val selected = viewModel.selectedDubStatusIndex.value ?: 0
            dubAdapter?.submitList(dubOptions.mapIndexed { index, (label, _) ->
                KumoOption(
                    label?.asStringNull(binding.root.context) ?: "${index + 1}",
                    index == selected,
                    index
                )
            })
            repairFocusChain()
        }
        observeNullable(viewModel.selectedDubStatusIndex) { index ->
            val binding = binding ?: return@observeNullable
            val selected = index ?: 0
            dubAdapter?.submitList(dubOptions.mapIndexed { i, (label, _) ->
                KumoOption(
                    label?.asStringNull(binding.root.context) ?: "${i + 1}",
                    i == selected,
                    i
                )
            })
            repairFocusChain()
        }
    }

    private fun observeDialogs() {
        observeNullable(viewModel.selectPopup) { popup ->
            if (popup == null) {
                popupDialog?.dismissSafe(activity)
                popupDialog = null
                return@observeNullable
            }
            popupDialog?.dismissSafe(activity)
            popupDialog = activity?.let { act ->
                val options = popup.getOptions(act)
                val title = popup.getTitle(act)
                act.showBottomDialogInstant(
                    options, title, {
                        popupDialog = null
                        popup.callback(null)
                    }, {
                        popupDialog = null
                        popup.callback(it)
                    }
                )
            }
        }

        observeNullable(viewModel.loadedLinks) { load ->
            if (load == null) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
                return@observeNullable
            }
            if (loadingDialog?.isShowing != true) {
                loadingDialog?.dismissSafe(activity)
                loadingDialog = null
            }
            loadingDialog = loadingDialog ?: context?.let { ctx ->
                val builder = BottomSheetDialog(ctx)
                builder.setContentView(R.layout.bottom_loading)
                builder.setOnDismissListener {
                    loadingDialog = null
                    viewModel.cancelLinks()
                }
                builder.setCanceledOnTouchOutside(true)
                builder.show()
                builder
            }
            loadingDialog?.findViewById<MaterialButton>(R.id.overlay_loading_skip_button)?.apply {
                if (load.linksLoaded <= 0) {
                    visibility = View.INVISIBLE
                } else {
                    setOnClickListener {
                        viewModel.skipLoading()
                    }
                    visibility = View.VISIBLE
                    text = "${context.getString(R.string.skip_loading)} (${load.linksLoaded})"
                }
            }
        }
    }
}
