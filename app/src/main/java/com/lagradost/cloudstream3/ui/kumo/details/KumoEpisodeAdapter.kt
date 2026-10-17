package com.lagradost.cloudstream3.ui.kumo.details

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.KumoDetailsEpisodeBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.result.ACTION_CLICK_DEFAULT
import com.lagradost.cloudstream3.ui.result.ACTION_SHOW_OPTIONS
import com.lagradost.cloudstream3.ui.result.EpisodeClickEvent
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.ui.result.getDisplayPosition
import com.lagradost.cloudstream3.ui.result.getWatchProgress
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * Nuvio EpisodeHorizontalCard (phone tier) translated to Views:
 * 296x184dp landscape art card, EPISODE N badge, extrabold 2-line title,
 * 2-line overview, runtime • IMDb meta row + right-aligned date, watched
 * badge, 4dp bottom progress bar. Click/long-press use the EXISTING
 * CloudStream episode pipeline (handleAction → RepoLinkGenerator → player).
 */
class KumoEpisodeAdapter(
    private val clickCallback: (EpisodeClickEvent) -> Unit,
) : NoStateAdapter<ResultEpisode>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.id == b.id
}, contentSame = { a, b ->
    a == b
})) {
    init {
        // Stable IDs: same focus jump-back protection as the season chips.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()

    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return ViewHolderState(
            KumoDetailsEpisodeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ResultEpisode, position: Int) {
        val binding = holder.view as? KumoDetailsEpisodeBinding ?: return
        binding.apply {
            // TV D-pad: cards must be focusable (no effect on touch) and visibly
            // grow on focus so you always know where you are.
            root.isFocusable = true
            root.isFocusableInTouchMode = false
            // Nuvio parity: focused title marquees (single-line + selected).
            // Reset on bind: recycled views may carry a running marquee.
            kumoEpisodeTitle.isSelected = false
            root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                kumoEpisodeTitle.isSelected = hasFocus
            }
            // No card-level up exit (same reason as KumoOptionAdapter): the
            // episodes row LayoutManager owns the conditional up-target
            // (Ranges → Dubs → Seasons → Play) via repairFocusChain.
            kumoEpisodeThumb.loadImage(item.poster)

            kumoEpisodeNumber.text = "EPISODE ${item.episode}"
            kumoEpisodeTitle.text =
                item.name?.takeIf { it.isNotBlank() } ?: "Episode ${item.episode}"

            if (item.description.isNullOrBlank()) {
                kumoEpisodeDesc.isGone = true
            } else {
                kumoEpisodeDesc.isVisible = true
                kumoEpisodeDesc.text = item.description
            }
            kumoEpisodeFiller.isVisible = item.isFiller == true

            // Meta row mirrors the hero: runtime | yellow IMDb badge | gold rating.
            val runtimeText = item.runTime?.takeIf { it > 0 }?.let { seconds ->
                val minutes = seconds / 60
                if (minutes > 0) {
                    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m"
                    else "${minutes}m"
                } else null
            }
            kumoEpisodeRuntime.text = runtimeText.orEmpty()
            kumoEpisodeRuntime.isGone = runtimeText.isNullOrBlank()

            var ratingText: String? = null
            item.score?.let { score ->
                try {
                    val rating = score.toFloat(10)
                    if (rating > 0f) ratingText = "%.1f".format(rating)
                } catch (_: Exception) {
                }
            }
            kumoEpisodeImdbBadge.isGone = ratingText.isNullOrBlank()
            // Collapse the badge gap when runtime is absent.
            (kumoEpisodeImdbBadge.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = if (runtimeText.isNullOrBlank()) 0 else
                    (6 * kumoEpisodeImdbBadge.resources.displayMetrics.density).toInt()
                kumoEpisodeImdbBadge.layoutParams = it
            }
            // Rating view doubles as the weight spacer: always laid out so the
            // date stays right-aligned even without a rating.
            kumoEpisodeMeta.text = ratingText.orEmpty()
            kumoEpisodeMeta.isVisible = ratingText != null

            try {
                val date = item.airDate?.takeIf { it > 0 }?.let {
                    java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US)
                        .format(java.util.Date(it))
                }
                if (date.isNullOrBlank()) {
                    kumoEpisodeDate.isGone = true
                } else {
                    kumoEpisodeDate.isVisible = true
                    kumoEpisodeDate.text = date
                }
            } catch (_: Exception) {
                kumoEpisodeDate.isGone = true
            }

            val watched = item.videoWatchState == VideoWatchState.Watched
            kumoEpisodeWatched.isVisible = watched

            val display = item.getDisplayPosition()
            if (!watched && item.duration > 0 && display > 0) {
                kumoEpisodeProgress.isVisible = true
                kumoEpisodeProgress.max = 100
                kumoEpisodeProgress.progress = (item.getWatchProgress() * 100).toInt().coerceIn(0, 100)
            } else {
                kumoEpisodeProgress.isGone = true
            }

            root.setOnClickListener {
                clickCallback(EpisodeClickEvent(ACTION_CLICK_DEFAULT, item))
            }
            root.setOnLongClickListener {
                clickCallback(EpisodeClickEvent(ACTION_SHOW_OPTIONS, item))
                true
            }
        }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        (holder.view as? KumoDetailsEpisodeBinding)?.let {
            clearImage(it.kumoEpisodeThumb)
        }
        super.onClearView(holder)
    }
}
