package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.KumoHomePosterBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * Nuvio-style home poster cards: rounded image with title + year BELOW
 * (no overlay text). Taps fire the EXISTING SearchClickCallback contract
 * (SEARCH_ACTION_LOAD → handleSearchClickCallback → details).
 */
class KumoHomePosterAdapter(
    private val clickCallback: (SearchClickCallback) -> Unit,
) : NoStateAdapter<SearchResponse>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.url == b.url && a.name == b.name
}, contentSame = { a, b ->
    a == b
})) {
    init {
        // Stable IDs from the same key as the diff: keeps D-pad focus glued
        // to its poster across library reloads (no jump-back like seasons had).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val item = getItem(position)
        return (item.url + item.name).hashCode().toLong()
    }

    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return ViewHolderState(
            KumoHomePosterBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: SearchResponse, position: Int) {
        val binding = holder.view as? KumoHomePosterBinding ?: return
        binding.apply {
            // Focus lands on the card itself so the white outline shows;
            // clicks move with it (root keeps no separate handler).
            kumoHomePosterCard.isFocusable = true
            kumoHomePosterCard.isFocusableInTouchMode = false
            kumoHomePosterCard.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                // Explicit foreground swap: state-selector foregrounds are
                // unreliable here, so drive the white border directly.
                kumoHomePosterCard.foreground = if (hasFocus)
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        v.context, R.drawable.outline
                    ) else null
                val s = if (hasFocus) 1.05f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
            // Sync recycled state (a reused focused view must not keep
            // a stale border/scale, nor lose a live one).
            if (kumoHomePosterCard.hasFocus()) {
                kumoHomePosterCard.foreground =
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        root.context, R.drawable.outline
                    )
                kumoHomePosterCard.scaleX = 1.05f
                kumoHomePosterCard.scaleY = 1.05f
            } else {
                kumoHomePosterCard.foreground = null
                kumoHomePosterCard.scaleX = 1f
                kumoHomePosterCard.scaleY = 1f
            }
            kumoHomePosterImage.loadImage(item.posterUrl, item.posterHeaders)
            kumoHomePosterTitle.text = item.name
            val year = (item as? AnimeSearchResponse)?.year?.toString()
            if (year.isNullOrBlank()) {
                kumoHomePosterYear.isGone = true
            } else {
                kumoHomePosterYear.isVisible = true
                kumoHomePosterYear.text = year
            }
            kumoHomePosterCard.setOnClickListener { view ->
                clickCallback(SearchClickCallback(SEARCH_ACTION_LOAD, view, position, item))
            }
            // Drop the old root click so OK-press can't double-fire.
            root.setOnClickListener(null)
        }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        (holder.view as? KumoHomePosterBinding)?.let {
            clearImage(it.kumoHomePosterImage)
        }
        super.onClearView(holder)
    }
}
